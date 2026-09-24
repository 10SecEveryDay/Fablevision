package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import com.fablevision.client.FableVisionClient;
import com.fablevision.client.FableVisionConfig;
import net.minecraft.core.HolderLookup;

/**
 * The search engine: N background workers pushing seeds through {@link SeedCriteria#test}.
 *
 * Zero network, zero AI — pure local math using the game's own world-gen code. Workers are daemon
 * threads at minimum priority, one per core LESS ONE so the machine stays usable while a search
 * runs (see {@link #start}); the GUI polls {@link Job} for live stats. One job runs at a time;
 * starting a new one stops the old.
 *
 * A SEARCH CAN ONLY RUN ON THE CREATE WORLD SCREEN. {@link SeedAccess} is checked before any worker
 * starts and again every few thousand seeds while they run, so a search cannot begin in a world and
 * cannot survive one starting to load. Nothing here ever reads a world that already exists: the
 * seeds come from a random number generator, and the only world-gen data involved is the data packs
 * chosen for the world about to be created.
 */
public final class SeedFinder {
   private static volatile Job job;

   /**
    * A found seed, in two forms: the finished sentences and the same finds as DATA.
    *
    * Both, because they are read by different things. {@code matches} is the long-form explanation
    * a player reads when there is something to explain; {@code finds} is what the short join
    * message and the in-game map are built from. Deriving the second from the first meant parsing
    * English back apart, which is exactly how the spoiler filter came to miss the slime row.
    *
    * {@code finds} can be empty when a re-test failed for some reason, so anything reading it must
    * cope — the sentences are always there.
    */
   public record Result(long seed, List<String> matches, List<SeedCriteria.Find> finds) {
      public Result(long seed, List<String> matches) {
         this(seed, matches, List.of());
      }
   }

   public static final class Job {
      public final SeedCriteria criteria;
      public final String summary;
      public final int wantCount;
      /** How many worker threads ("bots") this job runs on. */
      public int threads = 1;
      /** Fast mode: measure from 0,0 instead of the real spawn (much faster, close-ish). */
      public final boolean fast;
      public final List<Result> results = Collections.synchronizedList(new ArrayList<>());
      public final AtomicLong checked = new AtomicLong();
      /**
       * Fast-mode hits thrown away because the one spawn lookup put a find outside the distance
       * the player asked for.
       *
       * Public and counted so the screen can SAY SO. A search that keeps finding candidates and
       * rejecting them looks identical from outside to a search that is finding nothing, and the
       * two want opposite responses from the player — the first says "this is working, wait", the
       * second says "ask for less".
       */
      public final AtomicLong rejectedAfterSpawnCheck = new AtomicLong();
      public final long startedMs = System.currentTimeMillis();
      public volatile long finishedMs;
      public volatile boolean running = true;
      public volatile boolean bootstrapping = true;
      public volatile String error;
      // ── For SearchWatchdog (1.44.5): what each worker is on, and since when; what the estimate said ──
      /** Per worker: when it started its current seed (nanoTime), or 0 between seeds. */
      final java.util.concurrent.atomic.AtomicLongArray seedStartNs = new java.util.concurrent.atomic.AtomicLongArray(MAX_WORKERS);
      /** Per worker: the seed it is on. */
      final java.util.concurrent.atomic.AtomicLongArray seedNow = new java.util.concurrent.atomic.AtomicLongArray(MAX_WORKERS);
      final Thread[] workers = new Thread[MAX_WORKERS];
      /** The rarity estimate's chance per seed, once known (0 until then), and whether it was rough. */
      public volatile double expectedChance;
      public volatile boolean expectedRough;
      /** Set by the watchdog when the search is far past its estimate; the screen shows it. */
      public volatile String slowNote;
      /** Exact-mode "aim RIGHT at spawn": when >0, a hit whose spawn-measured distances are
       *  all within this is taken instantly; a looser (but ≤ radius) hit is parked in
       *  {@link #backup} while workers keep hunting a tight one for a bounded extra window. */
      public final int tightRadius;
      public volatile Result backup;
      public volatile int backupWorst;
      private volatile long backupDeadline;
      final HolderLookup.Provider registries;
      /**
       * WHERE THIS SEARCH'S SEEDS COME FROM, written down instead of left implicit.
       *
       * Every worker derives its seeds from this one number, so it is the whole identity of a
       * search: two runs with different streamIds cannot examine the same seeds in the same order,
       * and two runs with the SAME streamId examine the same seeds. It is generated fresh per job
       * from the clock unless a caller pins it.
       *
       * It exists because "is this search actually random?" was a fair question that the code could
       * not answer. Seeds used to come from {@code ThreadLocalRandom.current()} inside each worker
       * — genuinely random, but invisible: nothing recorded what a given run had explored, so a run
       * could not be described, compared, or reproduced, and a report of "I got the same seed twice"
       * could only be argued about rather than checked. Now the run has a name.
       */
      public final long streamId;

      Job(SeedCriteria criteria, HolderLookup.Provider registries, Long pinnedStream, int wantCount, boolean fast) {
         this.criteria = criteria;
         this.registries = registries;
         this.summary = criteria.summary();
         this.wantCount = Math.max(1, wantCount);
         // RANDOM BY DEFAULT, and mixed rather than raw: System.nanoTime() alone has poor low bits
         // and two searches started in the same millisecond would begin with neighbouring streams.
         this.streamId = pinnedStream != null ? pinnedStream
               : mix64(System.nanoTime() ^ (System.currentTimeMillis() * 0x9E3779B97F4A7C15L));
         // A "spawn inside" row overrides the player's choice of Fast, because Fast cannot answer
         // it at all — it measures from 0, 0, and the whole question is about where the player
         // actually lands. Silently running it Fast would return seeds that look checked and are
         // not, so the mode is corrected here, once, rather than trusted from the UI.
         this.fast = fast && !criteria.requiresExact();
         // Tiering only makes sense for a single Exact-mode result with something actually
         // measured from the overworld spawn (Fast measures from 0,0 by design).
         this.tightRadius = !fast && this.wantCount == 1 && criteria.hasOverworldTarget()
               && !criteria.composed
               ? FableVisionConfig.SEED_EXACT_TIGHT : 0;
      }

      public boolean done() {
         return !running;
      }

      /** How long the search ran (frozen once it finishes). */
      public long elapsedMs() {
         return (finishedMs > 0 ? finishedMs : System.currentTimeMillis()) - startedMs;
      }
   }

   public static Job current() {
      return job;
   }

   /**
    * Starts a search from a FRESH RANDOM STREAM, stopping any previous one.
    *
    * This is what every player-facing path calls, and the randomness is the point: running the same
    * wish twice explores different seeds and returns a different world.
    */
   public static synchronized Job start(SeedCriteria criteria, HolderLookup.Provider registries,
                                        int wantCount, boolean fast) {
      return start(criteria, registries, null, wantCount, fast);
   }

   /**
    * Same, with the seed stream PINNED so the search examines the same seeds in the same order.
    *
    * For reproducing a run rather than for playing. Note what it does and does not promise: the
    * seeds EXAMINED are reproducible, but which one is returned is not, because several workers race
    * and whichever reaches a match first wins. A player who wants their world back does not need
    * this at all — the seed itself is the reproduction, and it is already typed into the world
    * settings and printed on joining.
    *
    * THE SCAN-FROM-ZERO MODE THIS REPLACES IS GONE AND SHOULD NOT COME BACK. It walked seeds 0, 1,
    * 2, … and had had no caller since the standalone screen was deleted, so it was dead code that
    * made the engine LOOK like it scanned from zero — which is exactly the reading that has to be
    * impossible now, and exactly what an identical-criteria search returning an identical world
    * would be evidence of. A pinned random stream does everything reproduction actually needed.
    */
   public static synchronized Job start(SeedCriteria criteria, HolderLookup.Provider registries,
                                        Long pinnedStream, int wantCount, boolean fast) {
      return start(criteria, registries, pinnedStream, wantCount, fast, Thread.MIN_PRIORITY);
   }

   /**
    * Same, with the worker thread priority chosen by the caller.
    *
    * Added for {@link com.fablevision.api.SeedSearchApi} and the unattended stockpiler behind it.
    * The MIN_PRIORITY default below is right for every search a player is sitting in front of,
    * and wrong for the one case where nobody is: pregen is started deliberately and left alone
    * for an hour, so there is no interactive work to yield to and yielding just makes the wait
    * longer. That case asks for NORM_PRIORITY; everything else keeps the default by not passing
    * this argument at all.
    *
    * The "one core stays free" rule below is NOT negotiable through this parameter, and that is
    * deliberate. Priority decides who wins a contested core; the free core is what keeps the
    * machine able to redraw a window at all. Raising priority on n-1 workers is recoverable —
    * taking the last core is what made the desktop unusable.
    */
   public static synchronized Job start(SeedCriteria criteria, HolderLookup.Provider registries,
                                        Long pinnedStream, int wantCount, boolean fast,
                                        int workerPriority) {
      stop();
      Job started = new Job(criteria, registries, pinnedStream, wantCount, fast);
      job = started;
      // THE ENGINE REFUSES TOO, not just the screens. A search is CPU work on background threads
      // that outlives the screen that started it, so the gate has to be here as well or "the button
      // is only on the Create World screen" would be a fact about buttons.
      if (!SeedAccess.allowed()) {
         started.error = SeedAccess.ONLY_ON_CREATE_WORLD;
         started.running = false;
         started.bootstrapping = false;
         return started;
      }
      if (registries == null) {
         started.error = SeedAccess.ONLY_ON_CREATE_WORLD;
         started.running = false;
         started.bootstrapping = false;
         return started;
      }
      if (criteria.isEmpty()) {
         started.error = "Pick at least one thing to search for.";
         started.running = false;
         started.bootstrapping = false;
         return started;
      }
      // ONE CORE STAYS FREE, AND THE WORKERS RUN LAST IN THE QUEUE. This is a correction, and the
      // reasoning it replaces was wrong in a way that only shows up on a real machine.
      //
      // The old version took EVERY core at NORMAL priority, on the argument that a search can only
      // run on the Create World screen and nothing else wants the CPU there. That argument is about
      // MINECRAFT, and the machine is not only running Minecraft. With n workers on n cores at the
      // same priority as everything else, the whole desktop stops responding: the game itself
      // stutters (it still has a render thread and an audio thread), the fans spin up, and the only
      // input the player can still land is the Stop button. A search that makes the computer
      // unusable is not a fast search, it is a search nobody can sit through.
      //
      // So: cores - 1 workers, at MIN_PRIORITY. The free core is what keeps the UI thread — the
      // game's and the OS's — able to run at all, and the priority means that on the cores the
      // workers do hold, anything else that wants time gets it first. On an otherwise idle machine
      // this costs close to nothing, because a low-priority thread on an idle core still runs flat
      // out; it only yields when there is something to yield to, which is exactly the case the old
      // version handled badly. Roughly one core's worth of throughput is the whole price.
      //
      // (This is also what the in-world path used to do before it was removed, and deleting it with
      // that path was the mistake — the behaviour was never about being in a world, it was about
      // being on somebody's computer.)
      // AND AT MOST MAX_WORKERS (1.44.2). There was no ceiling: availableProcessors counts hyperthreads,
      // so a 32-thread CPU started 31 workers, each keeping its own full world-generation noise state
      // — memory that has to fit in Minecraft's heap (2 GB by default in the vanilla launcher)
      // alongside the game. Twelve is well past where most players' machines stop.
      int cores = Runtime.getRuntime().availableProcessors();
      int threads = Math.max(1, Math.min(MAX_WORKERS, cores - 1));
      started.threads = threads;
      int priority = Math.max(Thread.MIN_PRIORITY, Math.min(Thread.MAX_PRIORITY, workerPriority));
      for (int i = 0; i < threads; i++) {
         final int workerIndex = i;
         Thread t = new Thread(() -> worker(started, workerIndex), "FableVision-SeedFinder-" + i);
         t.setDaemon(true);
         t.setPriority(priority);
         started.workers[i] = t;
         t.start();
      }
      SearchWatchdog.watch(started);
      return started;
   }

   /** The most search workers a job starts, whatever the CPU. See {@link #start}. */
   public static final int MAX_WORKERS = 12;

   /**
    * Stops the current search. A flag, deliberately, not Thread.interrupt: the world-generation code a
    * worker is inside never checks for interrupts, so interrupting would not end a seed sooner. Each
    * worker checks the flag between seeds, so Stop takes effect within one seed's test — milliseconds
    * for most wishes, around a second for one that assembles a village.
    */
   public static void stop() {
      Job current = job;
      if (current != null) {
         current.running = false;
      }
   }

   private static void worker(Job j, int workerIndex) {
      WorldgenContext ctx;
      try {
         ctx = WorldgenContext.get(j.registries);
      } catch (Throwable t) {
         FableVisionClient.LOGGER.error("Seed finder bootstrap failed", t);
         j.error = "World-gen bootstrap failed: " + t.getClass().getSimpleName();
         j.running = false;
         return;
      }
      j.bootstrapping = false;
      // A SEARCH THAT CANNOT CHECK BUILDINGS SAYS SO AND STOPS (1.44.2). Every structure row is checked
      // against the game's structure files (the strict biome test, and any building inside); without
      // them the check now fails closed, so the search would otherwise run forever finding nothing.
      if (!j.criteria.structures.isEmpty() || !j.criteria.excludeStructures.isEmpty()) {
         String cant = VillageLayout.problem();
         if (cant != null) {
            j.error = cant;
            j.running = false;
            return;
         }
      }

      java.util.SplittableRandom random = workerStream(j.streamId, workerIndex);
      List<String> lines = new ArrayList<>(4);
      int[] worst = new int[1];
      long sinceGateCheck = 0;
      while (j.running) {
         // THE GATE IS RE-CHECKED WHILE RUNNING, because a search is the one part of this mod that
         // can outlive the screen that started it. Pressing Create starts loading a world while
         // workers are still going, so "it was allowed when it started" is not the same claim as
         // "it is allowed now". Every 4096 seeds is often enough to stop within a few milliseconds
         // and rare enough that the check does not show up in the hot loop at all.
         if (++sinceGateCheck >= 4096) {
            sinceGateCheck = 0;
            if (!SeedAccess.allowed()) {
               j.running = false;
               break;
            }
         }
         long seed = random.nextLong();
         lines.clear();
         worst[0] = 0;
         boolean hit;
         j.seedNow.set(workerIndex, seed);
         j.seedStartNs.set(workerIndex, System.nanoTime());
         try {
            hit = j.criteria.test(seed, ctx, lines, j.fast, worst);
         } catch (Throwable t) {
            FableVisionClient.LOGGER.error("Seed finder crashed while testing seed {}", seed, t);
            j.error = "Search crashed: " + t.getClass().getSimpleName() + " (see log)";
            j.running = false;
            return;
         }
         j.seedStartNs.set(workerIndex, 0);
         j.checked.incrementAndGet();
         if (hit && j.tightRadius > 0 && worst[0] > j.tightRadius) {
            // Right size (within the Exact radius) but not RIGHT at spawn — park the best
            // such seed as a backup and keep hunting a tighter one. The extra window starts
            // at the FIRST backup and scales with how long that took (4–15s).
            // DESCRIBED BEFORE IT IS PARKED, AND OUTSIDE THE LOCK.
            //
            // A backup used to be built straight from the hot-path lines, which carry no structured
            // finds — and the structured finds are what the SHORT join message and the in-game map
            // are made of. So a search that settled for its backup handed the player the four-line
            // explanation AND a map with nothing on it, every time, for a seed that was perfectly
            // good. Nobody reported it because nothing said the map was supposed to have dots.
            //
            // The describe costs a spawn lookup, so the interest is checked first and the work is
            // done outside the monitor: every worker contends on j.results, and holding it for ten
            // milliseconds would put the other threads to sleep for the duration. The test is then
            // repeated inside, because another worker may have parked a better one meanwhile.
            boolean worthParking;
            synchronized (j.results) {
               worthParking = j.results.isEmpty() && (j.backup == null || worst[0] < j.backupWorst);
            }
            Result parked = worthParking ? describe(j, ctx, seed, lines) : null;
            if (parked != null) {   // a backup its own re-check turned down is not parked (see describe)
               synchronized (j.results) {
                  if (j.results.isEmpty() && (j.backup == null || worst[0] < j.backupWorst)) {
                     if (j.backup == null) {
                        long elapsed = System.currentTimeMillis() - j.startedMs;
                        j.backupDeadline = System.currentTimeMillis()
                              + Math.min(15_000L, Math.max(4_000L, elapsed));
                     }
                     j.backupWorst = worst[0];
                     j.backup = parked;
                  }
               }
            }
         } else if (hit) {
            // VERIFIED BEFORE IT COUNTS AS A RESULT. In Fast mode this is where the one spawn
            // lookup happens and where a find that is not really within the asked distance gets
            // the seed thrown away — see the block at the end of SeedCriteria.test. A null means
            // rejected, and the only thing to do about it is keep searching.
            Result verified = describe(j, ctx, seed, lines);
            if (verified == null) {
               // Counted only when it IS the Fast-mode spawn re-check: the screen explains this
               // number as "looked right from the world origin, too far from the real spawn".
               if (j.fast) {
                  j.rejectedAfterSpawnCheck.incrementAndGet();
               }
               continue;
            }
            synchronized (j.results) {
               if (j.results.size() < j.wantCount) {
                  j.results.add(verified);
               }
               if (j.results.size() >= j.wantCount) {
                  j.running = false;
               }
            }
         }
         // Extra window over with nothing tighter found? Settle for the parked backup.
         if (j.tightRadius > 0 && j.running && j.backup != null && System.currentTimeMillis() > j.backupDeadline) {
            synchronized (j.results) {
               if (j.results.isEmpty()) {
                  j.results.add(j.backup);
               }
               j.running = false;
            }
         }
      }
      // Stopped (user or window) while a backup sat unclaimed — don't waste it, it DID
      // match the wish within the Exact radius; it just wasn't within the tight aim.
      if (j.tightRadius > 0 && j.backup != null) {
         synchronized (j.results) {
            if (j.results.isEmpty()) {
               j.results.add(j.backup);
            }
         }
      }
      if (j.finishedMs == 0) {
         j.finishedMs = System.currentTimeMillis();
      }
   }

   /**
    * Which single item in a bundle is holding the whole search up.
    *
    * A bundle is ANDed, so when nothing turns up the useful question is not "how many seeds have
    * we tried" but "which of these five things is the rare one". This answers it directly: each
    * item is searched ON ITS OWN over the same sample of seeds, and the one that matches least
    * often is the bottleneck.
    *
    * Deliberately NOT done by instrumenting {@link SeedCriteria#test}. Per-target counters there
    * would sit in the hot path of every search forever to serve a report that only matters when a
    * search is already failing — and they would count rejections in funnel order, which says as
    * much about the order targets happen to be tested in as about which is rare. This runs once,
    * off to the side, only when a search has visibly stalled.
    */
   public static String findBottleneck(SeedCriteria criteria, WorldgenContext ctx, boolean fast, int sampleSeeds) {
      record Item(String label, SeedCriteria only) {}
      List<Item> items = new ArrayList<>();
      for (SeedCriteria.StructureTarget t : criteria.structures) {
         SeedCriteria one = new SeedCriteria();
         one.composed = true;
         one.structures.add(t);
         items.add(new Item(t.label, one));
      }
      for (SeedCriteria.BiomeTarget b : criteria.biomes) {
         SeedCriteria one = new SeedCriteria();
         one.composed = true;
         one.biomes.add(b);
         items.add(new Item(b.label, one));
      }
      // A "next to" is its own item, and it has to be, because it is routinely rarer than either
      // half. Both biomes might be common near spawn and still almost never land beside each other
      // — a report that could only ever name one of the two ends would send the player to remove
      // the wrong thing. The pair carries its own ends (it cannot be tested without them), and the
      // SAME target objects are reused: the funnel matches an adjacency's anchor by identity.
      for (SeedCriteria.Adjacency a : criteria.adjacencies) {
         // A STRUCTURE-ANCHORED PAIR has no biome anchor. This loop read a.anchor regardless and threw,
         // the caller swallowed it, and a "village next to a ruined portal" search stuck past 90 s showed
         // "working out which part is the rare one…" forever. The same bug estimate() had, fixed there in
         // 1.43.1 and missed here. Both ends of such a pair are already items above.
         if (a.anchor == null) {
            continue;
         }
         SeedCriteria one = new SeedCriteria();
         one.composed = true;
         one.biomes.add(a.anchor);
         if (a.otherBiome != null) {
            one.biomes.add(a.otherBiome);
         } else {
            one.structures.add(a.otherStructure);
         }
         one.adjacencies.add(a);
         items.add(new Item("\"" + a.anchor.label + " next to " + a.otherLabel()
               + "\" within " + a.within, one));
      }
      if (items.size() < 2) {
         return null; // nothing to compare against; the single item IS the search
      }

      String worstLabel = null;
      int worstHits = Integer.MAX_VALUE;
      List<String> lines = new ArrayList<>(4);
      for (Item item : items) {
         int hits = 0;
         for (int i = 0; i < sampleSeeds; i++) {
            lines.clear();
            try {
               if (item.only().test(ThreadLocalRandom.current().nextLong(), ctx, lines, fast)) {
                  hits++;
               }
            } catch (Throwable ignored) {
               // One item failing to evaluate should not sink the report about the others.
            }
         }
         if (hits < worstHits) {
            worstHits = hits;
            worstLabel = item.label();
         }
      }
      if (worstLabel == null) {
         return null;
      }
      return worstHits == 0
            ? worstLabel + " — not one seed in " + sampleSeeds + " had it. Try dropping it, or allow more distance."
            : worstLabel + " — only " + worstHits + " seed" + (worstHits == 1 ? "" : "s") + " in "
              + sampleSeeds + " had it, and everything else has to line up with it too.";
   }

   /**
    * How rare a whole wish is, roughly: the chance one random seed matches, and the item that makes it
    * so. {@code atMost} means one item never matched in its sample, so {@code chance} is an upper
    * bound and the real wait is at least what it implies.
    */
   public record Estimate(double chance, boolean atMost, boolean rough, String rarest) {
      /** Seeds a search should expect to check per match. */
      public long seedsPerMatch() {
         return chance <= 0 ? Long.MAX_VALUE : Math.max(1, Math.round(1 / chance));
      }
   }

   /**
    * THE SEARCH ETA'S HALF THAT IS NOT THE SPEED: expected rarity, measured rather than guessed.
    *
    * Each item of the wish is tested ON ITS OWN against random seeds, the same way
    * {@link #findBottleneck} does, and the chances are multiplied. Two things make that rougher than it
    * looks, and both are reported rather than hidden:
    *
    *   - ITEMS ARE NOT INDEPENDENT. A village and a plains biome turn up together far more often than
    *     their chances multiplied; a jungle and a snowy village almost never do. The product can be off
    *     several times either way, so the screen says "about" and says "rough".
    *   - CONTENT ROWS ARE NOT SAMPLED, because reading a structure's buildings costs about a second a
    *     time. Their chance is the plain structure's measured chance times the row's measured
    *     {@code rarityPct} ("2% of mansions have the lava vault"). A content row with no measured rate
    *     marks the estimate {@code rough} and contributes the structure's chance only, which makes the
    *     estimate optimistic.
    *
    * Each item gets a time budget rather than a fixed sample, because an Exact-mode test pays a spawn
    * lookup per seed and a Fast one does not. Run off the render thread, at minimum priority.
    */
   public static Estimate estimate(SeedCriteria criteria, WorldgenContext ctx, boolean fast, long budgetMsPerItem) {
      return estimate(criteria, ctx, fast, budgetMsPerItem, Long.MAX_VALUE, () -> false);
   }

   /**
    * The same, with a ceiling on the WHOLE job and a way to call it off.
    *
    * THE ESTIMATE MUST NOT COMPETE WITH THE SEARCH IT IS ESTIMATING. Per item it is cheap, but a
    * bundle is eight or ten items, each with a floor of 40 trials in case the budget is too tight for
    * even one — so a wish with several slow Exact-mode items could keep a core busy for half a minute,
    * working out how long a search would take while making that search slower. The search runs on
    * cores-1 threads; this one is the extra.
    *
    * {@code stop} is asked between items and inside the sampling loop, so when the search finishes
    * first — which is the happy case, and common for easy wishes — the estimate stops rather than
    * finishing a report nobody will read. Items that never got sampled are dropped and the estimate
    * says {@code rough}; if none was sampled, it returns null and the screen says it could not be
    * measured.
    */
   public static Estimate estimate(SeedCriteria criteria, WorldgenContext ctx, boolean fast, long budgetMsPerItem,
                                   long totalBudgetMs, java.util.function.BooleanSupplier stop) {
      record Item(String label, SeedCriteria only, double factor, boolean unknownFactor) {}
      List<Item> items = new ArrayList<>();
      for (SeedCriteria.StructureTarget t : criteria.structures) {
         SeedCriteria one = new SeedCriteria();
         one.composed = true;
         double factor = 1;
         boolean unknown = false;
         SeedCriteria.StructureTarget plain = t;
         if (t.building != null && !t.without) {
            plain = plain.withBuilding(null);
            if (t.rarityPct >= 0) {
               factor *= Math.max(0.5, t.rarityPct) / 100.0;
            } else {
               unknown = true;
            }
         }
         if (t.minPieces > 0) {
            // "Big" is defined as the row's own measured p67, so one in three by construction.
            plain = plain.withMinPieces(0);
            factor /= 3;
         }
         if (t.spawnInside) {
            unknown = true;
         }
         one.structures.add(plain);
         items.add(new Item(t.label, one, factor, unknown));
      }
      for (SeedCriteria.BiomeTarget b : criteria.biomes) {
         SeedCriteria one = new SeedCriteria();
         one.composed = true;
         one.biomes.add(b);
         items.add(new Item(b.label, one, 1, false));
      }
      for (SeedCriteria.Adjacency a : criteria.adjacencies) {
         // A STRUCTURE-ANCHORED PAIR ("village next to a ruined portal") has no biome anchor. This
         // loop used to read a.anchor regardless, threw on the null, and the whole estimate died
         // silently — which is why those searches never showed an ETA. Both ends of such a pair are
         // already items above (addStructurePair puts both structures in the wish), so the pair adds
         // only its closeness, which is folded into those items' chances; it is marked rough instead.
         if (a.anchor == null) {
            continue;
         }
         SeedCriteria one = new SeedCriteria();
         one.composed = true;
         one.biomes.add(a.anchor);
         if (a.otherBiome != null) {
            one.biomes.add(a.otherBiome);
         } else {
            one.structures.add(a.otherStructure.building != null ? a.otherStructure.withBuilding(null) : a.otherStructure);
         }
         one.adjacencies.add(a);
         items.add(new Item("\"" + a.anchor.label + " next to " + a.otherLabel() + "\"", one, 1, false));
      }
      boolean structurePair = criteria.adjacencies.stream().anyMatch(a -> a.anchor == null);
      for (SeedCriteria.SlimeTarget s : criteria.slimes) {
         SeedCriteria one = new SeedCriteria();
         one.composed = true;
         one.slimes.add(s);
         items.add(new Item(s.label(), one, 1, false));
      }
      for (SeedCriteria.StructureTarget t : criteria.excludeStructures) {
         SeedCriteria one = new SeedCriteria();
         one.composed = true;
         one.excludeStructures.add(t);
         items.add(new Item("no " + t.label, one, 1, false));
      }
      for (SeedCriteria.BiomeTarget b : criteria.excludeBiomes) {
         SeedCriteria one = new SeedCriteria();
         one.composed = true;
         one.excludeBiomes.add(b);
         items.add(new Item("no " + b.label, one, 1, false));
      }
      double chance = 1;
      boolean atMost = false;
      if (items.isEmpty()) {
         return null;   // nothing to measure: the caller says it can't estimate rather than "1 in 1"
      }
      boolean rough = items.size() > 1 || structurePair;
      double rarestChance = 2;
      String rarest = null;
      List<String> lines = new ArrayList<>(4);
      long hardStop = totalBudgetMs == Long.MAX_VALUE ? Long.MAX_VALUE
            : System.currentTimeMillis() + totalBudgetMs;
      int measured = 0;
      for (Item item : items) {
         if (stop.getAsBoolean() || System.currentTimeMillis() >= hardStop) {
            rough = true;   // the rest were never sampled; what is left is a partial product
            break;
         }
         int hits = 0;
         int tried = 0;
         long until = Math.min(System.currentTimeMillis() + budgetMsPerItem, hardStop);
         while (tried < 2000 && (tried < 40 || System.currentTimeMillis() < until)) {
            lines.clear();
            try {
               if (item.only().test(ThreadLocalRandom.current().nextLong(), ctx, lines, fast)) {
                  hits++;
               }
            } catch (Throwable ignored) {
               // An item that cannot be evaluated counts as a miss; the estimate is only ever a guide.
            }
            tried++;
            // Checked every few trials rather than every one: the check is cheap, a trial is not, and
            // the floor of 40 must not outlive a search that has already finished.
            if ((tried & 7) == 0 && stop.getAsBoolean()) {
               break;
            }
         }
         if (tried == 0) {
            continue;
         }
         measured++;
         double p;
         if (hits == 0) {
            // Nothing in the sample: all that is known is that it is rarer than about 1 in tried.
            p = 1.0 / (tried + 1);
            atMost = true;
         } else {
            p = (double) hits / tried;
         }
         p *= item.factor();
         rough |= item.unknownFactor();
         chance *= p;
         if (p < rarestChance) {
            rarestChance = p;
            rarest = item.label();
         }
      }
      if (measured == 0) {
         return null;   // stopped before anything was sampled — no number to show, and it says so
      }
      return new Estimate(chance, atMost, rough, rarest);
   }

   /**
    * Checks the winner against the real spawn and writes the lines a player should read — or
    * returns null, meaning this seed does not actually satisfy the wish and the search goes on.
    *
    * Fast mode measures from the world origin because finding the real spawn costs about 10.6ms,
    * and paying that per SEED is the entire difference between the two modes: a common wish
    * matches one seed in five, and doing it per match once measured Fast mode running 5.8x slower
    * than its own benchmark. So the search runs without it and this pays it exactly once, on the
    * one seed that is a candidate to be kept.
    *
    * WHAT CHANGED IN 1.39.1 is what happens with the answer. This used to only re-describe: it
    * measured the true distances, printed them, and kept the seed whatever they were — which is
    * how a search set to 100 blocks handed back a village 139 blocks from spawn and said "139" on
    * its own confirmation line. Now the same pass rejects the seed if anything is outside the
    * reach that was asked for, so the control's number is a promise rather than a hint.
    *
    * A THROW ALSO REJECTS. The old code kept the seed and logged a warning, on the reasoning that
    * a seed that matched is still a match; that reasoning does not survive the check becoming part
    * of the verdict. If the spawn re-check cannot be run, the distance is unverified, and this
    * project's rule for an unverifiable predicate is that it never reports a match.
    *
    * Exact mode already measures everything from the real spawn, so its lines are complete as they
    * stand and there is nothing here to re-check.
    */
   private static Result describe(Job j, WorldgenContext ctx, long seed, List<String> lines) {
      try {
         List<String> full = new ArrayList<>(lines.size() + 2);
         List<SeedCriteria.Find> finds = new ArrayList<>(4);
         if (j.criteria.test(seed, ctx, full, j.fast, null, true, finds)) {
            return new Result(seed, List.copyOf(full), List.copyOf(finds));
         }
         // In FAST mode a false verdict here is the spawn re-check turning the seed down. In EXACT mode
         // the re-run should agree with the pass that just succeeded, and it used to be KEPT when it
         // did not — "the seed still matched". But it matched one pass and failed another, and the two
         // modes disagreed about what that means. Since 1.44.2 both treat it the same way: a seed any
         // check turned down is not handed to a player, and the search goes on. In Exact mode it is
         // logged, because the two passes disagreeing is itself a bug worth seeing.
         if (!j.fast) {
            FableVisionClient.LOGGER.warn("Exact-mode re-check disagreed with the search for seed {}; skipped", seed);
         }
         return null;
      } catch (Throwable t) {
         FableVisionClient.LOGGER.warn("Result re-check failed for seed {}", seed, t);
         return null;
      }
   }

   /**
    * The seed stream ONE WORKER of a job draws from.
    *
    * SplittableRandom rather than ThreadLocalRandom because the job's streamId has to actually
    * determine which seeds get examined — that is what makes a pinned run reproducible — and
    * ThreadLocalRandom cannot be seeded.
    *
    * SPLIT, NOT RE-SEEDED, and the first attempt got this wrong in a way worth recording. It gave
    * worker w the stream {@code new SplittableRandom(streamId + w * 0x9E3779B97F4A7C15L)}, on the
    * reasoning that a golden-ratio stride puts the workers far apart. That constant is
    * SplittableRandom's OWN internal step: its nextLong() is mix64(seed += GOLDEN_GAMMA), so
    * offsetting the seed by one gamma produces a stream that is worker 0's stream shifted by one
    * draw. Every worker was walking the same sequence a step behind the last. {@code streamDiag}
    * measured it: 8 workers x 4000 draws produced 4007 distinct seeds instead of 32000 — seven
    * eighths of the search re-testing seeds another thread had already done.
    *
    * {@code split()} is the documented way to get INDEPENDENT streams: each split gets its own
    * gamma rather than an offset into a shared one. It is deterministic from the parent seed, so a
    * pinned stream still reproduces exactly.
    *
    * PUBLIC so {@code gradlew streamDiag} can check the generator the workers really use instead of
    * a copy of this formula — which is what caught the bug above. This project has twice found the
    * checking tool to be the thing that was wrong, and a test that reimplements what it is testing
    * agrees with itself whatever the real code does.
    */
   public static java.util.SplittableRandom workerStream(long streamId, int workerIndex) {
      java.util.SplittableRandom parent = new java.util.SplittableRandom(streamId);
      java.util.SplittableRandom out = parent.split();
      for (int i = 0; i < workerIndex; i++) {
         out = parent.split();
      }
      return out;
   }

   /**
    * MurmurHash3's 64-bit finaliser. Turns a clock reading into a value whose bits are all usable.
    *
    * Needed because the raw inputs are poor: consecutive nanoTime() readings differ in their low
    * bits only, and a stream started from one would begin next door to a stream started a moment
    * later. Mixing makes two searches started back to back explore genuinely unrelated ground.
    */
   private static long mix64(long z) {
      z = (z ^ (z >>> 33)) * 0xFF51AFD7ED558CCDL;
      z = (z ^ (z >>> 33)) * 0xC4CEB9FE1A85EC53L;
      return z ^ (z >>> 33);
   }

   /** A short, readable tag for one search's stream, so two runs can be told apart at a glance. */
   public static String streamTag(long streamId) {
      return String.format("%06x", (int) (streamId >>> 40) & 0xFFFFFF);
   }

   /** "under a second", "14s", "2m 05s" — for the searched-N-seeds lines. */
   public static String prettyMs(long ms) {
      long s = ms / 1000;
      if (s < 1) {
         return "under a second";
      }
      if (s < 120) {
         return s + "s";
      }
      return (s / 60) + "m " + String.format("%02d", s % 60) + "s";
   }

   private SeedFinder() {
   }
}
