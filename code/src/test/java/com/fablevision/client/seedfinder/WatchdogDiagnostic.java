package com.fablevision.client.seedfinder;

import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * DOES THE SEARCH WATCHDOG CATCH WHAT IT IS FOR, AND ONLY THAT?
 *
 * {@link SearchWatchdog} only earns its place if, when a search misbehaves on a player's machine, the
 * log says what the search was doing. So this builds a real search job, gives it real worker threads —
 * one of them genuinely stuck inside a method named for the purpose — and drives the watchdog's check
 * with controlled times, asserting what it reports:
 *
 *   - a healthy search: nothing;
 *   - one seed held for over a minute: reported once, with the seed, the thread state and a stack that
 *     reaches the stuck method; not repeated for five minutes; repeated after;
 *   - no seed finishing for a minute: every worker's stack;
 *   - far past the estimate with nothing found: reported once, the screen's line set; a rough estimate
 *     gets more room before it counts;
 *   - every report carries the wish and the seed stream that replays the search.
 */
public final class WatchdogDiagnostic {

   private static int failed;

   public static void main(String[] args) throws Exception {
      System.out.println("========== THE SEARCH WATCHDOG ==========");
      long s = 1_000_000_000L;

      // ── healthy ──
      SeedFinder.Job job = job();
      long t0 = System.nanoTime();
      SearchWatchdog w = new SearchWatchdog(job, t0);
      job.checked.set(10);
      job.seedNow.set(0, 111);
      job.seedStartNs.set(0, t0);
      check("a healthy search reports nothing", w.check(t0 + 5 * s).isEmpty());

      // ── one seed held for over a minute, by a real thread stuck in a named method ──
      CountDownLatch inside = new CountDownLatch(1);
      Thread stuck = new Thread(() -> stuckInsideWorldgen(inside), "FableVision-SeedFinder-1");
      stuck.setDaemon(true);
      stuck.start();
      inside.await();
      job.workers[1] = stuck;
      job.seedNow.set(1, 424242L);
      job.seedStartNs.set(1, t0);
      job.checked.set(20);   // the other worker is still finishing seeds
      job.seedNow.set(0, 112);
      job.seedStartNs.set(0, t0 + 60 * s);   // ...and is on a fresh one
      List<String> r = w.check(t0 + 61 * s);
      String stuckReport = r.isEmpty() ? "" : r.get(0);
      check("one seed held 61 s is reported", r.size() == 1 && stuckReport.contains("on one seed"));
      check("  with the seed", stuckReport.contains("424242"));
      check("  with the thread's state", stuckReport.contains("TIMED_WAITING"));
      check("  and a stack that reaches the stuck code", stuckReport.contains("stuckInsideWorldgen"));
      check("  and the seed stream that replays the search",
            stuckReport.contains("seed stream " + job.streamId));
      job.checked.set(30);
      job.seedStartNs.set(0, t0 + 120 * s);
      check("not repeated a minute later", w.check(t0 + 121 * s).stream().noneMatch(x -> x.contains("on one seed")));
      job.checked.set(40);
      job.seedStartNs.set(0, t0 + 361 * s);
      check("repeated after five minutes if still stuck",
            w.check(t0 + 362 * s).stream().anyMatch(x -> x.contains("on one seed")));
      job.seedStartNs.set(1, 0);

      // ── nothing finishing anywhere ──
      SeedFinder.Job idle = job();
      idle.workers[1] = stuck;
      SearchWatchdog wi = new SearchWatchdog(idle, t0);
      idle.checked.set(5);
      wi.check(t0);
      check("no report while it is still within the minute", wi.check(t0 + 59 * s).isEmpty());
      List<String> ri = wi.check(t0 + 61 * s);
      check("no seed finished for 61 s is reported with every worker",
            ri.size() == 1 && ri.get(0).contains("no seed has finished") && ri.get(0).contains("worker 0")
                  && ri.get(0).contains("worker 1") && ri.get(0).contains("stuckInsideWorldgen"));

      // ── far past the estimate ──
      SeedFinder.Job rare = job();
      SearchWatchdog wr = new SearchWatchdog(rare, t0);
      rare.expectedChance = 1 / 1000.0;
      rare.checked.set(19_000);
      check("19x the estimate is not reported yet", wr.check(t0 + s).isEmpty() && rare.slowNote == null);
      rare.checked.set(20_001);
      List<String> rr = wr.check(t0 + 2 * s);
      check("20x the estimate with nothing found is reported", rr.size() == 1 && rr.get(0).contains("estimate expected"));
      check("  and the search screen gets its line", rare.slowNote != null && rare.slowNote.contains("logs/latest.log"));
      rare.checked.set(40_000);
      check("  once only", wr.check(t0 + 3 * s).isEmpty());
      SeedFinder.Job rough = job();
      SearchWatchdog wro = new SearchWatchdog(rough, t0);
      rough.expectedChance = 1 / 1000.0;
      rough.expectedRough = true;
      rough.checked.set(30_000);
      check("a ROUGH estimate gets more room (30x not reported)", wro.check(t0 + s).isEmpty());
      SeedFinder.Job found = job();
      SearchWatchdog wf = new SearchWatchdog(found, t0);
      found.expectedChance = 1 / 1000.0;
      found.checked.set(50_000);
      found.results.add(null);
      check("a search that found something is not \"past its estimate\"", wf.check(t0 + s).isEmpty());

      // ── structure checks failing wholesale, with REAL failures from the real check ──
      net.minecraft.SharedConstants.tryDetectVersion();
      net.minecraft.server.Bootstrap.bootStrap();
      VillageLayout.templates();
      SeedFinder.Job broken = job();
      SearchWatchdog wb = new SearchWatchdog(broken, t0);
      long failsBefore = VillageLayout.CHECK_FAILURES.get();
      for (int i = 0; i < 60; i++) {
         // No world-gen context: the check throws inside its own try, exactly where a real failure would.
         boolean said = VillageLayout.generatesHere(null, null, i, new net.minecraft.world.level.ChunkPos(i, 0),
               null, null, h -> true, SeedCriteria.Dim.OVERWORLD);
         if (said) {
            check("a check that threw must not say yes", false);
         }
      }
      broken.checked.set(60);
      check("real check failures are counted", VillageLayout.CHECK_FAILURES.get() - failsBefore == 60);
      List<String> rb = wb.check(t0 + 5 * s);
      check("60 of 60 structure checks throwing stops the search", !broken.running && rb.size() == 1
            && rb.get(0).contains("structure checks threw"));
      check("  with an error the search screen shows", broken.error != null && broken.error.contains("logs/latest.log"));
      check("  and the exception's stack in the report", rb.get(0).contains("last failure: java.lang.")
            && rb.get(0).contains("VillageLayout.generatesHere"));
      SeedFinder.Job fine = job();
      SearchWatchdog wfine = new SearchWatchdog(fine, t0);
      VillageLayout.CHECK_ATTEMPTS.addAndGet(100);
      VillageLayout.CHECK_FAILURES.addAndGet(3);
      check("an odd failure among many checks does not stop a search", wfine.check(t0 + 5 * s).isEmpty() && fine.running);

      System.out.println(failed == 0 ? "ALL CHECKS PASS" : failed + " FAILED");
      if (!stuckReport.isEmpty()) {
         System.out.println();
         System.out.println("  what a stuck-seed report looks like in logs/latest.log:");
         for (String line : stuckReport.split("\n")) {
            if (!line.contains("java.base") && !line.contains("jdk.internal")) {
               System.out.println("  | " + line);
            }
         }
      }
      System.exit(failed == 0 ? 0 : 1);
   }

   /** A search job as SeedFinder.start makes one, without starting workers. */
   private static SeedFinder.Job job() {
      SeedCriteria c = new SeedCriteria();
      SeedFinder.Job j = new SeedFinder.Job(c, null, 77L, 1, false);
      j.threads = 2;
      Thread ok = new Thread(() -> { }, "FableVision-SeedFinder-0");
      j.workers[0] = ok;
      return j;
   }

   /** Stands in for world-generation code that never returns. */
   private static void stuckInsideWorldgen(CountDownLatch inside) {
      inside.countDown();
      try {
         Thread.sleep(Long.MAX_VALUE);
      } catch (InterruptedException ignored) {
      }
   }

   private static void check(String what, boolean ok) {
      System.out.println("  " + (ok ? "ok   " : "FAIL ") + what);
      if (!ok) {
         failed++;
      }
   }

   private WatchdogDiagnostic() {
   }
}
