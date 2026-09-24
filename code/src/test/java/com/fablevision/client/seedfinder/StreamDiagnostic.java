package com.fablevision.client.seedfinder;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Does running the same search twice explore the same seeds?
 *
 * Reported as: "searched pillager outpost + mansion, got a seed after ~200M; ran it again and got
 * the SAME seed", with the reasonable guess that the finder scans upward from 0. It does not, and
 * never did — the one live caller has always asked for random seeds — so this exists to make that
 * checkable rather than arguable, and to prove the 1.41.0 stream rework did not break it.
 *
 * It tests the REAL generator, via {@link SeedFinder#workerStream}, which is the same call the
 * worker loop makes. Reimplementing the formula here would produce a test that agrees with itself
 * whatever the workers actually do — and this project has twice found the checking tool to be the
 * thing that was wrong.
 *
 * Four questions:
 *   1. Do back-to-back jobs get different streams? (If not, identical criteria WOULD repeat.)
 *   2. Do two unpinned runs examine different seeds?
 *   3. Does a PINNED stream reproduce exactly? (The reproduce-a-run feature.)
 *   4. Do the workers within one job explore different ground, rather than overlapping?
 *
 * No Minecraft needed: Job construction touches only the criteria and the config defaults.
 *
 * {@code gradlew streamDiag}
 */
public final class StreamDiagnostic {

   public static void main(String[] args) {
      int problems = 0;
      problems += distinctPerJob();
      problems += differentRuns();
      problems += pinnedReproduces();
      problems += workersDoNotOverlap();

      System.out.println();
      System.out.println(problems == 0
            ? "✓ searches are random by default, reproducible when pinned, and workers do not overlap."
            : problems + " PROBLEM(S) — see above.");
      // IT COUNTED PROBLEMS AND EXITED 0 (fixed 1.44.2), so the release suite recorded it as passing
      // whatever it found. Found by auditing every diagnostic's exit code after iconDiag had the same flaw.
      System.exit(problems == 0 ? 0 : 1);
   }

   /** A fresh job every time must mean a fresh stream every time. */
   private static int distinctPerJob() {
      System.out.println("=========== 1. BACK-TO-BACK JOBS GET DIFFERENT STREAMS ===========");
      int jobs = 2000;
      Set<Long> seen = new HashSet<>();
      for (int i = 0; i < jobs; i++) {
         seen.add(newJob(null).streamId);
      }
      int dupes = jobs - seen.size();
      System.out.println("  " + jobs + " jobs created back to back -> " + seen.size() + " distinct streams");
      System.out.println("  collisions: " + dupes + (dupes == 0 ? "  ✓" : "  <-- BUG: identical criteria would repeat"));
      return dupes == 0 ? 0 : 1;
   }

   /** The player-facing question, in the player's terms: same wish twice, same world? */
   private static int differentRuns() {
      System.out.println();
      System.out.println("=========== 2. TWO RUNS OF THE SAME WISH EXAMINE DIFFERENT SEEDS ===========");
      SeedFinder.Job a = newJob(null);
      SeedFinder.Job b = newJob(null);
      Set<Long> first = firstSeeds(a, 8, 2000);
      Set<Long> second = firstSeeds(b, 8, 2000);
      Set<Long> shared = new LinkedHashSet<>(first);
      shared.retainAll(second);
      System.out.println("  run " + SeedFinder.streamTag(a.streamId) + ": first 16,000 seeds");
      System.out.println("  run " + SeedFinder.streamTag(b.streamId) + ": first 16,000 seeds");
      System.out.println("  seeds examined by BOTH: " + shared.size()
            + (shared.isEmpty() ? "  ✓ (no overlap at all)" : "  <-- runs are not independent"));
      return shared.isEmpty() ? 0 : 1;
   }

   /** The reproduce path: pinning the stream must replay the same seeds in the same order. */
   private static int pinnedReproduces() {
      System.out.println();
      System.out.println("=========== 3. A PINNED STREAM REPRODUCES EXACTLY ===========");
      long pin = 0x1234_5678_9ABC_DEF0L;
      SeedFinder.Job a = newJob(pin);
      SeedFinder.Job b = newJob(pin);
      boolean same = a.streamId == b.streamId;
      long[] one = firstSeedsOfWorker(a, 0, 500);
      long[] two = firstSeedsOfWorker(b, 0, 500);
      boolean identical = java.util.Arrays.equals(one, two);
      System.out.println("  pinned to " + Long.toHexString(pin) + " -> streamId honoured: " + same);
      System.out.println("  first 500 seeds identical: " + identical + (identical && same ? "  ✓" : "  <-- BUG"));
      System.out.println("  first three seeds: " + one[0] + ", " + one[1] + ", " + one[2]);
      // And a DIFFERENT pin must give a different replay, or "reproducible" would just mean "fixed".
      long[] other = firstSeedsOfWorker(newJob(pin + 1), 0, 500);
      boolean differs = !java.util.Arrays.equals(one, other);
      System.out.println("  a different pin gives different seeds: " + differs + (differs ? "  ✓" : "  <-- BUG"));
      return same && identical && differs ? 0 : 1;
   }

   /** Eight workers sharing one job must not re-test each other's seeds. */
   private static int workersDoNotOverlap() {
      System.out.println();
      System.out.println("=========== 4. WORKERS WITHIN ONE JOB DO NOT OVERLAP ===========");
      SeedFinder.Job j = newJob(null);
      int workers = 8;
      int each = 4000;
      Set<Long> all = new HashSet<>();
      int collisions = 0;
      for (int w = 0; w < workers; w++) {
         for (long seed : firstSeedsOfWorker(j, w, each)) {
            if (!all.add(seed)) {
               collisions++;
            }
         }
      }
      System.out.println("  " + workers + " workers x " + each + " seeds = " + (workers * each)
            + " draws -> " + all.size() + " distinct");
      System.out.println("  wasted duplicate work: " + collisions + (collisions == 0 ? "  ✓" : "  <-- overlapping streams"));
      return collisions == 0 ? 0 : 1;
   }

   private static SeedFinder.Job newJob(Long pin) {
      // An empty criteria is enough: nothing about seed selection depends on what is being searched
      // for, which is itself worth asserting by construction.
      return new SeedFinder.Job(new SeedCriteria(), null, pin, 1, true);
   }

   private static Set<Long> firstSeeds(SeedFinder.Job j, int workers, int each) {
      Set<Long> out = new LinkedHashSet<>();
      for (int w = 0; w < workers; w++) {
         for (long seed : firstSeedsOfWorker(j, w, each)) {
            out.add(seed);
         }
      }
      return out;
   }

   private static long[] firstSeedsOfWorker(SeedFinder.Job j, int workerIndex, int count) {
      SplittableRandom r = SeedFinder.workerStream(j.streamId, workerIndex);
      long[] out = new long[count];
      for (int i = 0; i < count; i++) {
         out[i] = r.nextLong();
      }
      return out;
   }

   private StreamDiagnostic() {
   }
}
