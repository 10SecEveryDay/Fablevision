package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.fablevision.client.FableVisionClient;

/**
 * WATCHES A RUNNING SEARCH AND WRITES DOWN WHAT IT WAS DOING IF IT STOPS MAKING SENSE (1.44.5).
 *
 * Why it exists. In the 1.44.5 regression one run of featureDiag spent over two hours on a row that
 * takes a minute and a half, and a rerun could not reproduce it. Stack samples taken by hand showed a
 * search moving from seed to seed and finding nothing — not frozen. Nothing in the mod could have
 * said so on a player's machine: a search that never finishes looked exactly like a rare wish. This is
 * so that if it happens again, to anyone, {@code logs/latest.log} holds enough to diagnose it.
 *
 * Four things are watched, every {@link #EVERY_MS}:
 *
 *   0. STRUCTURE CHECKS FAILING WHOLESALE — at least {@link #FAILING_SHARE} of THIS search's structure
 *      checks since the last look threw ({@link SeedFinder.Job#checks}; counted per job since 1.47.3, so a
 *      stopped search's workers finishing their last seed cannot count against the next one). Every such
 *      seed counts as a miss, so the search would run for ever finding nothing; it is STOPPED, with an error
 *      on screen and the exception's stack in the log. The one thing here that acts rather than logs.
 *   1. ONE SEED TAKING TOO LONG — a worker on the same seed for more than {@link #SEED_STALL_MS}. A
 *      seed normally takes milliseconds, about a second for one that assembles a village; a minute
 *      is a hang. Logged with that worker's stack, and again every {@link #REPEAT_MS} while it lasts.
 *   2. NO PROGRESS AT ALL — no seed finished anywhere for {@link #NO_PROGRESS_MS}. Every worker's
 *      stack.
 *   3. FAR PAST ITS OWN ESTIMATE — {@link #PAST_ESTIMATE} times the seeds the rarity estimate predicts
 *      per match (more for an estimate marked rough), with nothing found. Logged once, and the search
 *      screen says so in one line ({@link SeedFinder.Job#slowNote}). This is the featureDiag profile:
 *      a search that runs and runs, where no single seed is slow.
 *      A SEARCH FOR N WORLDS (1.46.0) is allowed that many matches' worth for the world it is on: with
 *      k found, the line is (k + 1) times the single threshold, so it scales up to N times for the last
 *      one. Each world found is progress — it clears the screen's line and lets the check fire again for
 *      the next world. With one world wanted this is exactly the old check.
 *
 * Every report carries what is needed to replay the search: the wish, the mode, the seed stream (see
 * {@link SeedFinder.Job#streamId}), and the counts. Apart from 0, it only logs.
 */
public final class SearchWatchdog {

   static final long EVERY_MS = 5_000;
   static final long SEED_STALL_MS = 60_000;
   static final long NO_PROGRESS_MS = 60_000;
   static final long REPEAT_MS = 300_000;
   static final double PAST_ESTIMATE = 20;
   static final double PAST_ROUGH_ESTIMATE = 50;
   /** Stack frames kept per thread in a report: enough to reach the mod's own frames. */
   static final int FRAMES = 60;

   private final SeedFinder.Job job;
   private long lastChecked = -1;
   private long lastProgressNs;
   private long lastNoProgressReportNs;
   private final Map<Integer, long[]> seedReported = new HashMap<>();   // worker -> {seed, reported at}
   /** How many worlds were found when "far past the estimate" was last reported; -1 = not yet. */
   private int pastEstimateReportedAt = -1;
   /** Results found at the last look — a new one is progress. */
   private int lastFound;
   private long lastFailures;
   private long lastAttempts;
   /**
    * At least this share of the structure checks since the last look threw, over at least
    * {@link #FAILING_MIN} checks: the search is stopped. Judged against checks ATTEMPTED, not seeds —
    * most seeds are turned down by cheap tests before any structure check, so even a total failure
    * would be a small share of seeds.
    */
   static final double FAILING_SHARE = 0.9;
   static final long FAILING_MIN = 50;

   SearchWatchdog(SeedFinder.Job job, long nowNs) {
      this.job = job;
      this.lastProgressNs = nowNs;
      this.lastFailures = job.checks.failures.get();
      this.lastAttempts = job.checks.attempts.get();
   }

   /** Starts a watchdog thread for this job; it ends by itself when the job does. */
   static void watch(SeedFinder.Job job) {
      Thread t = new Thread(() -> {
         SearchWatchdog w = new SearchWatchdog(job, System.nanoTime());
         while (job.running) {
            try {
               Thread.sleep(EVERY_MS);
            } catch (InterruptedException stop) {
               Thread.currentThread().interrupt();   // the thread ends here, but the flag is not ours to drop
               return;
            }
            if (!job.running) {
               return;
            }
            try {
               for (String report : w.check(System.nanoTime())) {
                  FableVisionClient.LOGGER.warn(report);
               }
            } catch (Throwable t2) {
               FableVisionClient.LOGGER.debug("Search watchdog failed", t2);   // it must never be the problem
            }
         }
      }, "FableVision-SearchWatchdog");
      t.setDaemon(true);
      t.setPriority(Thread.MIN_PRIORITY);
      t.start();
   }

   /** One look at the job. Returns the reports to log (empty when all is well). Package-private for watchdogDiag. */
   List<String> check(long nowNs) {
      List<String> out = new ArrayList<>();
      long checked = job.checked.get();
      int found = job.found.get();
      if (checked != lastChecked || found != lastFound) {
         lastChecked = checked;
         lastProgressNs = nowNs;
      }
      // A WORLD FOUND IS PROGRESS (1.46.0). The "far past the estimate" line was about the world being
      // hunted; once that one turns up, it no longer says anything true, and the next world gets its
      // own allowance (see 3).
      if (found > lastFound) {
         lastFound = found;
         job.slowNote = null;
      }

      // 0. Structure checks failing wholesale: the search cannot answer, so it stops and says so,
      //    instead of treating every seed as a miss for ever.
      long failures = job.checks.failures.get();
      long attempts = job.checks.attempts.get();
      long newFailures = failures - lastFailures;
      long newAttempts = attempts - lastAttempts;
      if (newAttempts >= FAILING_MIN && newFailures >= FAILING_SHARE * newAttempts) {
         Throwable last = job.checks.last;
         job.end("Structure checks keep failing, so this search can't find anything. Details are in"
               + " logs/latest.log — please include that file if you report it.");
         StringBuilder sb = new StringBuilder(header(String.format("%,d of the last %,d structure checks threw;"
               + " the search was stopped", newFailures, newAttempts), nowNs));
         sb.append("\n  last failure: ").append(last);
         if (last != null) {
            for (StackTraceElement e : last.getStackTrace()) {
               sb.append("\n      at ").append(e);
            }
         }
         out.add(sb.toString());
         return out;
      }
      if (newAttempts >= FAILING_MIN) {
         lastFailures = failures;
         lastAttempts = attempts;
      }

      // 1. A worker stuck on one seed.
      for (int i = 0; i < job.threads; i++) {
         long started = job.seedStartNs.get(i);
         if (started == 0) {
            continue;
         }
         long seed = job.seedNow.get(i);
         long onItMs = (nowNs - started) / 1_000_000;
         if (onItMs < SEED_STALL_MS) {
            continue;
         }
         long[] prev = seedReported.get(i);
         if (prev != null && prev[0] == seed && (nowNs - prev[1]) / 1_000_000 < REPEAT_MS) {
            continue;
         }
         seedReported.put(i, new long[]{seed, nowNs});
         out.add(header("worker " + i + " has been on one seed for " + onItMs / 1000 + " s", nowNs)
               + "\n" + worker(i, nowNs));
      }

      // 2. Nothing finishing anywhere.
      long idleMs = (nowNs - lastProgressNs) / 1_000_000;
      if (idleMs >= NO_PROGRESS_MS && (lastNoProgressReportNs == 0
            || (nowNs - lastNoProgressReportNs) / 1_000_000 >= REPEAT_MS)) {
         lastNoProgressReportNs = nowNs;
         out.add(header("no seed has finished for " + idleMs / 1000 + " s", nowNs) + "\n" + allWorkers(nowNs));
      }

      // 3. Far past the estimate for the world being hunted — with one world wanted, nothing found.
      double chance = job.expectedChance;
      if (pastEstimateReportedAt != found && chance > 0 && found < job.wantCount) {
         double factor = job.expectedRough ? PAST_ROUGH_ESTIMATE : PAST_ESTIMATE;
         double expected = 1 / chance;
         // (found + 1) matches' worth: the worlds already found, and the one still being hunted.
         double allowed = factor * expected * (found + 1);
         if (checked > allowed) {
            pastEstimateReportedAt = found;
            job.slowNote = "This is taking far longer than its estimate. Details are in logs/latest.log"
                  + " — please include that file if you report it.";
            String what = found == 0 && job.wantCount == 1
                  ? String.format("%,d seeds checked and nothing found, %.0f times the %,.0f per match"
                        + " the estimate expected", checked, checked / expected, expected)
                  : String.format("%,d seeds checked and %d of %d worlds found, %.0f times the %,.0f per match"
                        + " the estimate expected for %d", checked, found, job.wantCount,
                        checked / (expected * (found + 1)), expected, found + 1);
            out.add(header(what, nowNs) + "\n" + allWorkers(nowNs));
         }
      }
      return out;
   }

   private String header(String what, long nowNs) {
      long elapsedMs = job.elapsedMs();
      long checked = job.checked.get();
      return "FableVision seed search watchdog: " + what + "\n"
            + "  wish      : " + job.summary + "\n"
            + "  mode      : " + (job.fast ? "Fast" : "Exact") + ", " + job.threads + " workers, seed stream "
            + job.streamId + " (replays this search)\n"
            + String.format("  progress  : %,d seeds in %,d s (%,d/s), %d found%s%n", checked, elapsedMs / 1000,
                  elapsedMs > 0 ? checked * 1000 / elapsedMs : 0, job.found.get(),
                  job.wantCount > 1 && job.wantCount != Integer.MAX_VALUE ? " of " + job.wantCount + " worlds" : "")
            + "  estimate  : " + (job.expectedChance > 0
                  ? String.format("1 in %,.0f%s", 1 / job.expectedChance, job.expectedRough ? " (rough)" : "")
                  : "none");
   }

   private String allWorkers(long nowNs) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < job.threads; i++) {
         sb.append(worker(i, nowNs)).append('\n');
      }
      return sb.toString();
   }

   /** One worker: what seed, how long on it, its thread state, and its stack. */
   private String worker(int i, long nowNs) {
      Thread t = job.workers[i];
      long started = job.seedStartNs.get(i);
      String on = started == 0 ? "between seeds"
            : "seed " + job.seedNow.get(i) + " for " + (nowNs - started) / 1_000_000 + " ms";
      return "  worker " + i + " (" + on + ")\n" + threadReport(t);
   }

   /** A thread's name, state, and the top of its stack — the same thing a jstack line shows. */
   public static String threadReport(Thread t) {
      if (t == null) {
         return "    (no thread)";
      }
      StringBuilder sb = new StringBuilder("    thread \"" + t.getName() + "\" " + t.getState()
            + (t.isAlive() ? "" : " (dead)") + "\n");
      StackTraceElement[] stack = t.getStackTrace();
      for (int k = 0; k < Math.min(FRAMES, stack.length); k++) {
         sb.append("      at ").append(stack[k]).append('\n');
      }
      if (stack.length > FRAMES) {
         sb.append("      … ").append(stack.length - FRAMES).append(" more\n");
      }
      return sb.toString();
   }
}
