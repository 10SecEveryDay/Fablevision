package com.fablevision.client.seedfinder;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * THE WORLDS ONE SEARCH FOUND, WHEN IT WAS ASKED FOR MORE THAN ONE (1.46.0).
 *
 * A single search applies its first find and goes straight back to Create World, as it always has. Asked
 * for N worlds, the search keeps going until N different seeds have each passed the whole check, and the
 * player picks one: nothing goes into the world settings by itself. This is that list, kept apart from
 * the screen so it outlives one (closing Custom spawn and opening it again shows the same worlds) and so
 * {@code worldsDiag} can drive it without a game.
 *
 * WHAT IT IS NOT. Every seed here is a candidate for a NEW world on the Create World screen, found by the
 * same search as always — the list holds what the search returned and nothing else. It reads no world,
 * writes nothing to disk ("Copy" is the clipboard), and belongs to the Create World screen that started
 * it: a different screen, or the same screen with different data packs, gets an empty list, because the
 * worlds were checked against the world-gen those settings had (see {@link #belongsTo}).
 *
 * Each seed is held with its own finds and lines and the seeds-checked / time when it was found, so
 * "Use this seed" stores exactly what a single search stores for its one result — see {@link #use}.
 */
public final class FoundWorlds {

   /**
    * The most worlds one search may look for. TEN, AND RAISING IT IS THE OWNER'S CALL: every extra world
    * is a whole extra search, and the list, its layout check and the time estimate are sized for ten.
    */
   public static final int MAX = 10;

   /**
    * What the Worlds button steps through: EVERY number from 1 to {@link #MAX} (1.46.2). 1.46.0 stepped
    * 1, 2, 3, 5, 10, so 4 and 6 to 9 could only be had by typing them into the wish. 1 is a normal search,
    * exactly as before 1.46.0.
    */
   public static final int[] CHOICES = java.util.stream.IntStream.rangeClosed(1, MAX).toArray();

   /** One found world: the seed, what the search wrote about it, and when in the search it turned up. */
   public record World(long seed, List<String> matches, List<SeedCriteria.Find> finds, long checked,
                       long elapsedMs) {
      public String seedText() {
         return Long.toString(seed);
      }
   }

   /** The list the Custom spawn screen shows. One at a time, like the search itself. */
   public static final FoundWorlds CURRENT = new FoundWorlds();

   private final List<World> worlds = new ArrayList<>();
   private int want;
   private int asked;
   private String summary = "";
   private String note = "";
   private WeakReference<Object> owner = new WeakReference<>(null);
   private WeakReference<Object> registries = new WeakReference<>(null);
   private SeedFinder.Job job;
   private boolean stopped;
   private boolean ended;
   private long checked;
   private long elapsedMs;
   private String error;

   /** The next value of the Worlds button after {@code n}: one more, and {@link #MAX} wraps to 1. Anything
    *  unknown goes back to 1. */
   public static int next(int n) {
      return n >= 1 && n < MAX ? n + 1 : 1;
   }

   /** The value before {@code n} (a right-click or shift-click): one fewer, and 1 wraps to {@link #MAX}. */
   public static int previous(int n) {
      return n > 1 && n <= MAX ? n - 1 : MAX;
   }

   /**
    * A new N-world search has started: the old list goes, and this one fills from {@code job}.
    *
    * @param asked    what the wish said when it was more than {@link #MAX} (so the screen can say it was
    *                 capped), or 0
    * @param owner    the Create World screen the search runs for — held weakly, compared by identity
    * @param registries the registries the search checks against — likewise
    */
   public synchronized void begin(SeedFinder.Job job, int want, int asked, String summary, String note,
                                  Object owner, Object registries) {
      clear();
      this.job = job;
      this.want = Math.max(1, Math.min(MAX, want));
      this.asked = asked;
      this.summary = summary == null ? "" : summary;
      this.note = note == null ? "" : note;
      this.owner = new WeakReference<>(owner);
      this.registries = new WeakReference<>(registries);
   }

   /** Empties the list — a new search of any size has started, or the worlds no longer apply. */
   public synchronized void clear() {
      worlds.clear();
      want = 0;
      asked = 0;
      summary = "";
      note = "";
      owner = new WeakReference<>(null);
      registries = new WeakReference<>(null);
      job = null;
      stopped = false;
      ended = false;
      checked = 0;
      elapsedMs = 0;
      error = null;
   }

   /**
    * Takes one verified result. False when it is already here (the same seed twice) or the list is full.
    *
    * DEDUPLICATED HERE AS WELL AS IN THE ENGINE ({@code SeedFinder.Job#accept}), on purpose: the engine's
    * check is what makes the search keep going until N DIFFERENT seeds; this one is what makes the list
    * the player reads never show a seed twice, whatever fed it.
    */
   public synchronized boolean add(SeedFinder.Result r, long checkedAt, long elapsedAt) {
      if (r == null || worlds.size() >= want) {
         return false;
      }
      for (World w : worlds) {
         if (w.seed() == r.seed()) {
            return false;
         }
      }
      worlds.add(new World(r.seed(), r.matches() == null ? List.of() : r.matches(),
            r.finds() == null ? List.of() : r.finds(), checkedAt, elapsedAt));
      return true;
   }

   /**
    * Copies in whatever the search has found since the last call, each with the seeds-checked and time
    * the engine recorded when it accepted that seed. Returns how many were new. Cheap enough per frame.
    */
   public synchronized int sync() {
      SeedFinder.Job j = job;
      if (j == null) {
         return 0;
      }
      List<SeedFinder.Result> now;
      synchronized (j.results) {
         now = new ArrayList<>(j.results);
      }
      int added = 0;
      for (SeedFinder.Result r : now) {
         long[] at = j.foundAt(r.seed());
         if (add(r, at != null ? at[0] : j.checked.get(), at != null ? at[1] : j.elapsedMs())) {
            added++;
         }
      }
      checked = j.checked.get();
      elapsedMs = j.elapsedMs();
      if (j.done()) {
         ended = true;
         error = j.error;
      }
      return added;
   }

   /** The player pressed Stop: what is here stays, and the screen says it was stopped. */
   public synchronized void stopPressed() {
      stopped = true;
   }

   /**
    * Are these worlds about THIS Create World screen and THESE registries? Both by identity. A new Create
    * World screen, or new data packs on the same one, gives new registries — and a seed checked against
    * one world-gen is not checked against another, so the list does not carry over (fail closed).
    */
   public synchronized boolean belongsTo(Object owner, Object registries) {
      return owner != null && registries != null && this.owner.get() == owner
            && this.registries.get() == registries;
   }

   public synchronized List<World> list() {
      return List.copyOf(worlds);
   }

   public synchronized World get(int index) {
      return index >= 0 && index < worlds.size() ? worlds.get(index) : null;
   }

   public synchronized int size() {
      return worlds.size();
   }

   public synchronized boolean isEmpty() {
      return worlds.isEmpty();
   }

   public synchronized int want() {
      return want;
   }

   /** What the wish asked for when it was over {@link #MAX}, or 0. */
   public synchronized int asked() {
      return asked;
   }

   public synchronized boolean complete() {
      return want > 0 && worlds.size() >= want;
   }

   public synchronized boolean running() {
      return job != null && !job.done();
   }

   public synchronized boolean ended() {
      return ended;
   }

   public synchronized boolean stopped() {
      return stopped;
   }

   public synchronized String error() {
      return error;
   }

   public synchronized long checked() {
      return checked;
   }

   public synchronized long elapsedMs() {
      return elapsedMs;
   }

   public synchronized String summary() {
      return summary;
   }

   public synchronized String note() {
      return note;
   }

   /** Every seed, one per line — for a server's level-seed, or a list of seeds to play through. */
   public synchronized String copyAll() {
      StringBuilder sb = new StringBuilder();
      for (World w : worlds) {
         if (sb.length() > 0) {
            sb.append('\n');
         }
         sb.append(w.seed());
      }
      return sb.toString();
   }

   /**
    * "Use this seed": makes world {@code index} the applied seed, with ITS finds, lines and stats — the
    * same {@link AppliedSeed.Applied#of} the single search uses for its one result, so the badge and the
    * join message describe the world that is actually created. Returns what was stored, or null for an
    * index that is not in the list. Putting the seed in the Create World box is the screen's job.
    */
   public synchronized AppliedSeed.Applied use(int index) {
      World w = get(index);
      if (w == null) {
         return null;
      }
      AppliedSeed.Applied a = AppliedSeed.Applied.of(w.seed(), w.matches(), w.finds(), w.checked(),
            w.elapsedMs(), note);
      AppliedSeed.set(a);
      return a;
   }
}
