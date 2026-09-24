package com.fablevision.api;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.HolderLookup;

import com.fablevision.client.FableVisionClient;
import com.fablevision.client.seedfinder.SeedAccess;
import com.fablevision.client.seedfinder.SeedCriteria;
import com.fablevision.client.seedfinder.SeedFinder;
import com.fablevision.client.seedfinder.WorldgenContext;

/**
 * THE ONLY THING OTHER MODS MAY CALL. Everything else in FableVision is internal.
 *
 * Why this exists: the pregen mod used to reach straight into {@link SeedFinder},
 * {@link SeedCriteria} and {@link WorldgenContext}. Those are engine internals that move every
 * release — and they did. Pregen was still calling {@code WorldgenContext.findSource(null)}, which
 * stopped returning registries in 1.40.0, so the mod had been unable to start a single search for
 * several versions while looking like it worked. Nothing announced the break because nothing had
 * ever promised not to.
 *
 * So this class is a promise, and a narrow one: a list of labels, a search that streams hits, and
 * a way to stop it. It is versioned ({@link #API_VERSION}) and the consumer declares a matching
 * range in its fabric.mod.json, so a mismatched pair is refused by the loader with a readable
 * message instead of dying on a {@code NoSuchMethodError} halfway through a search.
 *
 * <b>The access gate applies here exactly as it does everywhere else.</b> {@link SeedAccess} is
 * not bypassed, weakened or wrapped: a search runs on the Create World screen, with no world
 * loaded, or it does not run. That is a compliance boundary, not a convenience one — see
 * SEED-FINDER-SCOPE.md. An API for other mods is precisely where that rule would erode first, so
 * the only way to obtain registries here is {@link #registriesFor(CreateWorldScreen)}, which is
 * {@link SeedAccess#sourceFor} under a different name and nothing more.
 */
public final class SeedSearchApi {

   /**
    * The API contract version. Bumped whenever anything in this class changes shape.
    *
    * <b>The release convention this depends on, because a consumer's version range is useless
    * without it:</b> API version N lives in FableVision 1.(43+N).x. API 1 is 1.44.x. A consumer
    * therefore pins {@code ">=1.44.0 <1.45.0"} and that range means exactly "API version 1".
    *
    * So: changing anything in this file means bumping BOTH this number and FableVision's MINOR
    * version, together, in the same commit. Patch releases (1.44.1, 1.44.2, …) may not touch this
    * file. Everything else in the mod is free to change in a patch without forcing consumers to
    * rebuild, which is the whole point of the split.
    */
   public static final int API_VERSION = 1;

   /** The FableVision version range a consumer of {@link #API_VERSION} should declare. */
   public static final String COMPATIBLE_RANGE = ">=1.44.0 <1.45.0";

   private SeedSearchApi() {
   }

   // ── What can be searched for ──────────────────────────────────────────────

   /**
    * Structure labels this version can search for, in catalog order.
    *
    * Read from the live registry rather than hard-coded, because what exists depends on the game
    * version and the data packs chosen for the world being created. A consumer should treat a
    * label it wants but does not find here as "not available in this setup" rather than as an
    * error.
    */
   public static List<String> structureLabels(HolderLookup.Provider registries) {
      if (registries == null) {
         return List.of();
      }
      try {
         List<String> out = new ArrayList<>();
         for (SeedCriteria.StructureTarget t : SeedCriteria.catalog(WorldgenContext.get(registries))) {
            out.add(t.label);
         }
         return List.copyOf(out);
      } catch (Throwable t) {
         FableVisionClient.LOGGER.warn("SeedSearchApi: could not build the structure catalog", t);
         return List.of();
      }
   }

   // ── Where a search is allowed ─────────────────────────────────────────────

   /**
    * Registries for a search, or null when this is not a place a search may happen.
    *
    * The {@link CreateWorldScreen} argument is the whole gate: there is no overload that takes
    * null, and no other way to get registries out of this class. A caller inside a loaded world
    * has nothing to pass and gets nothing back.
    */
   public static HolderLookup.Provider registriesFor(CreateWorldScreen screen) {
      return SeedAccess.sourceFor(screen);
   }

   /** True when a search may run right now. False inside any world, including your own. */
   public static boolean available() {
      return SeedAccess.allowed();
   }

   /** The one sentence to show when {@link #available()} is false. */
   public static String unavailableReason() {
      return SeedAccess.ONLY_ON_CREATE_WORLD;
   }

   /**
    * Whether the screen's World Type is one the finder can honestly answer for, or the refusal
    * to show. Null means the type is fine.
    */
   public static String worldTypeProblem(CreateWorldScreen screen) {
      return SeedAccess.worldTypeProblem(screen);
   }

   // ── A search ──────────────────────────────────────────────────────────────

   /**
    * One seed that matched, in the two forms a consumer needs: the number, and the finished
    * sentences describing what was found and how far away it is.
    *
    * Deliberately not {@code SeedFinder.Result} — that also carries structured {@code Find}
    * objects tied to internal types, and exposing them here would make every future change to
    * them a breaking change for every consumer.
    */
   public record Hit(long seed, List<String> matches) {}

   /** A running search. Obtained from {@link #start}; stop it when you are done with it. */
   public interface Search {

      /** The structure label this search is hunting. */
      String label();

      /** False once the search has stopped, for any reason. */
      boolean running();

      /** Why it stopped badly, or null if nothing went wrong. */
      String error();

      /** Seeds examined so far. */
      long checked();

      /** Hits delivered to the callback so far. */
      long found();

      /** Stops the search. Safe to call more than once, and safe after it has already ended. */
      void stop();
   }

   /**
    * Hunts seeds that put {@code structureLabel} within {@code radiusBlocks} of the world's real
    * spawn point, calling {@code onHit} for each one, and keeps going until it is stopped.
    *
    * <b>It does not stop at the first hit.</b> That is the difference from every screen-driven
    * search in this mod, and it is the reason this method exists: a stockpiler wants a stream,
    * and restarting a fresh search per seed throws away the world-gen bootstrap every time.
    *
    * <b>Distances are measured from the real spawn point</b>, never from 0,0. A seed banked under
    * the fast 0,0 approximation describes a walk from a place the player never stands.
    *
    * {@code onHit} is called on a background thread, may be called concurrently, and must not
    * block — anything slow belongs on the consumer's own thread.
    *
    * @param registries     from {@link #registriesFor(CreateWorldScreen)}; null is refused
    * @param structureLabel one of {@link #structureLabels}
    * @param radiusBlocks   maximum distance from spawn
    * @param workerPriority a {@link Thread} priority for the search workers. Use
    *                       {@link Thread#NORM_PRIORITY} for unattended work and
    *                       {@link Thread#MIN_PRIORITY} when the player is sitting in front of it
    *                       waiting — see {@link SeedFinder} for why that choice matters.
    * @param onHit          called once per matching seed
    * @return the search, already running, or one that is already stopped with {@link Search#error}
    *         set if it could not start
    */
   public static Search start(HolderLookup.Provider registries, String structureLabel,
                              int radiusBlocks, int workerPriority, Consumer<Hit> onHit) {
      RunningSearch search = new RunningSearch(structureLabel, onHit);
      if (registries == null) {
         search.fail(SeedAccess.ONLY_ON_CREATE_WORLD);
         return search;
      }
      if (!SeedAccess.allowed()) {
         search.fail(SeedAccess.ONLY_ON_CREATE_WORLD);
         return search;
      }

      SeedCriteria.StructureTarget target;
      try {
         target = find(registries, structureLabel);
      } catch (Throwable t) {
         FableVisionClient.LOGGER.warn("SeedSearchApi: catalog lookup failed", t);
         search.fail("World-gen catalog unavailable: " + t.getClass().getSimpleName());
         return search;
      }
      if (target == null) {
         search.fail("No structure called '" + structureLabel + "' in this version.");
         return search;
      }

      SeedCriteria criteria = new SeedCriteria();
      criteria.structures.add(target.withRadius(radiusBlocks));

      // wantCount is effectively unbounded so the job never stops itself on a hit. A side effect
      // worth knowing: the finder's "aim right at spawn" tiering only engages for single-result
      // searches, so it is off here — which is what a stockpiler wants anyway. Every hit inside
      // the radius counts, and none are held back waiting for a tighter one.
      //
      // fast = false: measure from the real spawn. See the method note.
      SeedFinder.Job job = SeedFinder.start(criteria, registries, null, Integer.MAX_VALUE, false,
            clampPriority(workerPriority));
      if (job.error != null) {
         search.fail(job.error);
         return search;
      }
      search.begin(job);
      return search;
   }

   private static SeedCriteria.StructureTarget find(HolderLookup.Provider registries, String label) {
      for (SeedCriteria.StructureTarget t : SeedCriteria.catalog(WorldgenContext.get(registries))) {
         if (t.label.equals(label)) {
            return t;
         }
      }
      return null;
   }

   private static int clampPriority(int priority) {
      return Math.max(Thread.MIN_PRIORITY, Math.min(Thread.MAX_PRIORITY, priority));
   }

   /**
    * Turns the finder's "results pile up in a list" model into a stream of callbacks.
    *
    * A pump thread rather than a hook inside the worker loop: the workers are the hot path, and
    * a consumer-supplied callback running there could stall every search thread on somebody
    * else's disk write. Polling costs one lock acquisition every 50ms and cannot misbehave.
    *
    * Draining the list as it reads is not just tidiness. With an unbounded want-count the
    * finder never trims it, so an overnight run would hold every Result it had ever produced.
    */
   private static final class RunningSearch implements Search {
      private final String label;
      private final Consumer<Hit> onHit;
      private volatile SeedFinder.Job job;
      private volatile boolean stopped;
      private volatile String error;
      private volatile long delivered;
      private Thread pump;

      RunningSearch(String label, Consumer<Hit> onHit) {
         this.label = label;
         this.onHit = onHit;
      }

      void fail(String why) {
         this.error = why;
         this.stopped = true;
      }

      void begin(SeedFinder.Job started) {
         this.job = started;
         Thread t = new Thread(this::pump, "FableVision-SeedSearchApi-" + label);
         t.setDaemon(true);
         // The pump only moves finished results across a lock; the workers do the work.
         t.setPriority(Thread.MIN_PRIORITY);
         this.pump = t;
         t.start();
      }

      private void pump() {
         SeedFinder.Job mine = job;
         try {
            while (!stopped) {
               // A screen-driven search calls SeedFinder.stop() before starting its own, so the
               // player opening Custom Spawn mid-run silently replaces our job. Noticing is the
               // difference between "pregen stopped because you started a search" and a stall
               // nobody can explain.
               if (SeedFinder.current() != mine) {
                  error = "Another search took over — a seed search was started somewhere else.";
                  break;
               }
               drain(mine);
               if (!mine.running) {
                  break;
               }
               Thread.sleep(50);
            }
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         } catch (Throwable t) {
            FableVisionClient.LOGGER.error("SeedSearchApi pump crashed", t);
            error = "Search pump crashed: " + t.getClass().getSimpleName() + " (see log)";
         } finally {
            // One last pass: workers can bank a result between the final drain and the exit.
            try {
               drain(mine);
            } catch (Throwable ignored) {
               // nothing useful to do while shutting down
            }
            if (error == null && mine.error != null) {
               error = mine.error;
            }
            stopped = true;
         }
      }

      private void drain(SeedFinder.Job mine) {
         List<SeedFinder.Result> batch;
         synchronized (mine.results) {
            if (mine.results.isEmpty()) {
               return;
            }
            batch = new ArrayList<>(mine.results);
            mine.results.clear();
         }
         for (SeedFinder.Result r : batch) {
            delivered++;
            try {
               onHit.accept(new Hit(r.seed(), r.matches()));
            } catch (Throwable t) {
               // A consumer that throws must not take the search down with it.
               FableVisionClient.LOGGER.warn("SeedSearchApi: hit callback threw", t);
            }
         }
      }

      @Override
      public String label() {
         return label;
      }

      @Override
      public boolean running() {
         SeedFinder.Job j = job;
         return !stopped && j != null && j.running;
      }

      @Override
      public String error() {
         return error;
      }

      @Override
      public long checked() {
         SeedFinder.Job j = job;
         return j == null ? 0L : j.checked.get();
      }

      @Override
      public long found() {
         return delivered;
      }

      @Override
      public void stop() {
         stopped = true;
         SeedFinder.Job mine = job;
         // Only stop the finder if the running job is still OURS. Calling the global stop after
         // someone else's search replaced ours would kill their search instead.
         if (mine != null && SeedFinder.current() == mine) {
            SeedFinder.stop();
         }
         Thread t = pump;
         if (t != null) {
            t.interrupt();
         }
      }
   }
}
