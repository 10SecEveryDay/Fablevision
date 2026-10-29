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
 *   - every report carries the wish and the seed stream that replays the search;
 *   - structure checks failing wholesale stop the search — counted PER SEARCH (1.47.3): a stopped search's
 *     straggling worker does not count against the next one, and the same failures counted for the next one
 *     do stop it (the control);
 *   - a search whose world-gen cannot load ends with its error and leaves Searching (1.47.3); the control
 *     runs 1.47.2's engine from the shipped jar on the same failure and must find it stuck.
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
      // As the engine records a find since 1.46.0: the list AND the count (SeedSearchApi drains the list).
      found.results.add(null);
      found.found.set(1);
      check("a search that found something is not \"past its estimate\"", wf.check(t0 + s).isEmpty());

      // ── a search for SEVERAL worlds (1.46.0): the line scales with the worlds found, and a find is progress ──
      SeedFinder.Job three = job(3);
      SearchWatchdog w3 = new SearchWatchdog(three, t0);
      three.expectedChance = 1 / 1000.0;
      three.checked.set(19_000);
      check("N=3, none found: 19x one match is not reported", w3.check(t0 + s).isEmpty());
      three.checked.set(20_001);
      List<String> r3 = w3.check(t0 + 2 * s);
      check("N=3, none found: past 20x one match is reported, as for one world",
            r3.size() == 1 && r3.get(0).contains("0 of 3 worlds found") && three.slowNote != null);
      three.found.set(1);
      three.checked.set(20_100);
      check("a world found clears the screen's line", w3.check(t0 + 3 * s).isEmpty() && three.slowNote == null);
      three.checked.set(39_000);
      check("N=3, one found: 39x is within the allowance for the second world", w3.check(t0 + 4 * s).isEmpty());
      three.checked.set(40_001);
      List<String> r3b = w3.check(t0 + 5 * s);
      check("N=3, one found: past 2 matches' worth it is reported again, for the second world",
            r3b.size() == 1 && r3b.get(0).contains("1 of 3 worlds found") && three.slowNote != null);
      check("  and the report says how many of how many", r3b.get(0).contains("1 found of 3 worlds"));
      three.found.set(3);
      three.checked.set(900_000);
      check("all N found: never \"past its estimate\"", w3.check(t0 + 6 * s).isEmpty());

      // A find with no new seed counted is still progress (checked moves with it in practice; this is
      // the property, not the usual case).
      SeedFinder.Job quiet = job(3);
      quiet.workers[1] = stuck;
      SearchWatchdog wq = new SearchWatchdog(quiet, t0);
      quiet.checked.set(5);
      wq.check(t0);
      quiet.found.set(1);
      wq.check(t0 + 59 * s);
      check("a world found counts as progress for \"no seed has finished\"",
            wq.check(t0 + 61 * s).stream().noneMatch(x -> x.contains("no seed has finished")));

      // ── structure checks failing wholesale, with REAL failures from the real check ──
      net.minecraft.SharedConstants.tryDetectVersion();
      net.minecraft.server.Bootstrap.bootStrap();
      VillageLayout.templates();
      SeedFinder.Job broken = job();
      SearchWatchdog wb = new SearchWatchdog(broken, t0);
      failChecks(broken, 60);   // this thread's checks count for that search, as a worker's do
      broken.checked.set(60);
      check("real check failures are counted, for the search whose worker ran them",
            broken.checks.failures.get() == 60 && broken.checks.attempts.get() == 60);
      List<String> rb = wb.check(t0 + 5 * s);
      check("60 of 60 structure checks throwing stops the search", !broken.running && rb.size() == 1
            && rb.get(0).contains("structure checks threw"));
      check("  with an error the search screen shows", broken.error != null && broken.error.contains("logs/latest.log"));
      check("  and the screen leaves Searching (both flags down)", !broken.searching() && broken.finishedMs > 0);
      check("  and the exception's stack in the report", rb.get(0).contains("last failure: java.lang.")
            && rb.get(0).contains("VillageLayout.generatesHere"));
      SeedFinder.Job fine = job();
      SearchWatchdog wfine = new SearchWatchdog(fine, t0);
      fine.checks.attempts.addAndGet(100);
      fine.checks.failures.addAndGet(3);
      check("an odd failure among many checks does not stop a search", wfine.check(t0 + 5 * s).isEmpty() && fine.running);

      // ── PER SEARCH (1.47.3): a stopped search's workers, finishing their last seed, do not count against the next ──
      SeedFinder.Job stopped = job();
      stopped.end(null);
      SeedFinder.Job next = job();
      SearchWatchdog wn = new SearchWatchdog(next, t0);
      failChecks(stopped, 60);   // the old search's straggling worker: every one of its checks throws
      next.checked.set(60);
      check("60 failures from the STOPPED search's worker do not stop the next search",
            wn.check(t0 + 5 * s).isEmpty() && next.running && next.checks.failures.get() == 0);
      // CONTROL: the very same 60 failures, run by the next search's own worker, must stop it — so the line
      // above passes because the failures are kept apart, not because 60 was too few to count.
      failChecks(next, 60);
      next.checked.set(120);
      boolean tripped = !wn.check(t0 + 10 * s).isEmpty() && !next.running;
      check("CONTROL: the same 60 failures counted for the next search DO stop it", tripped);

      // ── A SEARCH WHOSE WORLD-GEN CANNOT LOAD ENDS, AND SAYS WHY (1.47.3) ──
      // RegistryAccess.EMPTY: registries with nothing in them, so WorldgenContext.get throws on its first lookup,
      // in every worker — the case that left the screen on Searching for ever.
      SeedFinder.Job dead = new SeedFinder.Job(new SeedCriteria(), net.minecraft.core.RegistryAccess.EMPTY, 9L, 1, false);
      dead.threads = 3;
      Thread[] ws = new Thread[dead.threads];
      for (int i = 0; i < ws.length; i++) {
         final int index = i;
         ws[i] = new Thread(() -> SeedFinder.worker(dead, index), "FableVision-SeedFinder-" + i);
         ws[i].start();
      }
      for (Thread t : ws) {
         t.join(30_000);
      }
      check("every worker's bootstrap throwing: the search is no longer Searching", !dead.searching());
      check("  both flags down (running and bootstrapping)", !dead.running && !dead.bootstrapping);
      check("  with the error the screen shows", dead.error != null && dead.error.startsWith("World-gen bootstrap failed"));
      check("  and its end time set", dead.finishedMs > 0);
      // CONTROL: the 1.47.2 engine, run the same way on the same registries, leaves the search Searching.
      String old = oldBootstrapFailure();
      if (old != null) {
         check("CONTROL: 1.47.2's engine on the same failure stays Searching (the bug) — " + old,
               old.startsWith("running=false bootstrapping=true"));
      }
      SeedFinder.Job ended = job();
      ended.end("first");
      ended.end("second");
      check("end(): the first error given is the one kept, and an end is final",
            "first".equals(ended.error) && !ended.searching());

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
      return job(1);
   }

   private static SeedFinder.Job job(int worlds) {
      SeedCriteria c = new SeedCriteria();
      SeedFinder.Job j = new SeedFinder.Job(c, null, 77L, worlds, false);
      j.threads = 2;
      Thread ok = new Thread(() -> { }, "FableVision-SeedFinder-0");
      j.workers[0] = ok;
      return j;
   }

   /**
    * Runs {@code n} real structure checks that throw, counted for {@code job} the way its worker's would be.
    * No world-gen context: the check throws inside its own try, exactly where a real failure would.
    */
   private static void failChecks(SeedFinder.Job job, int n) {
      VillageLayout.countFor(job.checks);
      try {
         for (int i = 0; i < n; i++) {
            boolean said = VillageLayout.generatesHere(null, null, i, new net.minecraft.world.level.ChunkPos(i, 0),
                  null, null, h -> true, SeedCriteria.Dim.OVERWORLD);
            if (said) {
               check("a check that threw must not say yes", false);
            }
         }
      } finally {
         VillageLayout.countFor(null);
      }
   }

   /**
    * 1.47.2's SeedFinder worker, from the shipped jar, on registries that cannot be read: what its job's flags
    * are afterwards ("running=… bootstrapping=… error=…"), or null when the jar is not there.
    */
   private static String oldBootstrapFailure() {
      try {
         if (OldJar.get() == null) {
            return null;
         }
         Class<?> finder = OldJar.cls("com.fablevision.client.seedfinder.SeedFinder");
         Class<?> jobClass = OldJar.cls("com.fablevision.client.seedfinder.SeedFinder$Job");
         Class<?> criteriaClass = OldJar.cls("com.fablevision.client.seedfinder.SeedCriteria");
         Object criteria = OldJar.make(criteriaClass, new Class<?>[0]);
         Object job = OldJar.make(jobClass, new Class<?>[]{criteriaClass, net.minecraft.core.HolderLookup.Provider.class,
               Long.class, int.class, boolean.class}, criteria, net.minecraft.core.RegistryAccess.EMPTY, 9L, 1, false);
         OldJar.call(finder, null, "worker", new Class<?>[]{jobClass, int.class}, job, 0);
         return "running=" + OldJar.field(job, "running") + " bootstrapping=" + OldJar.field(job, "bootstrapping")
               + " error=" + OldJar.field(job, "error");
      } catch (ReflectiveOperationException | LinkageError e) {
         return "could not run the old engine: " + OldJar.why(e);
      }
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
