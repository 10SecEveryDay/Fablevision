package com.fablevision.client.seedfinder;

import com.fablevision.VillageDiagnostic;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;

/**
 * THE "BOTS" SETTING (1.47.0): {@code gradlew threadsDiag}.
 *
 *  1. MEMORY PER WORKER, measured: the live heap after a full collection while 1, 2, 4, 8, auto and as many
 *     workers as the machine has run a search that assembles villages in Exact mode; the slope per worker
 *     is what {@link SeedFinder#WORKER_MB} must cover. FAILS if a worker measures more than that constant.
 *  2. THE COUNT RULE: auto is cores - 1 up to 12 exactly as before; a number is taken as asked between 1
 *     and the maximum; the maximum is the lowest of the logical processors, what fits in half the heap and
 *     (1.47.3) 16, the most ever tested — with 1.47.2's rule, from the shipped jar, as the control.
 *  3. STOP at 1 worker, auto and the maximum: every worker has left its loop within a few seconds of the
 *     flag Stop sets (the screen itself changes at once — it reads the same flag).
 *  4. THE WATCHDOG at 1, auto and the maximum: a worker stuck on one seed is reported by its own number,
 *     including past 12 (the per-worker arrays used to stop at 12, which a setting can now exceed).
 *
 * Every seed a search returns at 1, auto and the maximum is held to multiDiag's independent check by
 * {@code multiDiag --args="3 both worlds threads=1,auto,max"}.
 */
public final class ThreadsDiagnostic {

   private static int failed;

   public static void main(String[] args) throws Exception {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      VillageLayout.templates();
      SeedCriteria.StructureTarget armorer = null;
      for (SeedCriteria.StructureTarget t : SeedCatalog.structures(ctx)) {
         if (t.label.equals("Village with Armorer")) {
            armorer = t;
         }
      }
      if (armorer == null) {
         System.out.println("FAIL the catalog has no Village with Armorer");
         System.exit(1);
      }
      SeedCriteria heavy = new SeedCriteria();
      heavy.structures.add(armorer.withRadius(500));
      if (args.length > 0 && args[0].equals("gc")) {
         // gradlew threadsDiag -Pheap=512M --args="gc 16": the same search in a heap of that size. Exits 0
         // when it fits (GC under GC_LIMIT of the time), 1 when it does not — the 96 MB control must say 1.
         boolean fits = gcPressure(reg, ctx, heavy, args.length > 1 ? Integer.parseInt(args[1]) : SeedFinder.maxWorkers());
         System.exit(fits ? 0 : 1);
      }
      int cores = Runtime.getRuntime().availableProcessors();
      int auto = SeedFinder.autoWorkers();
      int max = SeedFinder.maxWorkers();
      System.out.println("========== THE BOTS SETTING ==========");
      System.out.println(String.format("  this machine: %d logical processors, heap %,d MB → auto %d, maximum %d"
            + " (WORKER_MB %d, HEAP_SHARE %.0f%%)", cores, Runtime.getRuntime().maxMemory() >> 20, auto, max,
            SeedFinder.WORKER_MB, SeedFinder.HEAP_SHARE * 100));

      memory(reg, ctx, heavy, cores, auto);
      rule();
      TreeSet<Integer> counts = new TreeSet<>(List.of(1, auto, max));
      stop(reg, ctx, heavy, counts);
      watchdog(reg, heavy, counts);

      System.out.println();
      System.out.println(failed == 0 ? "ALL CHECKS PASS" : failed + " CHECK(S) FAILED");
      System.exit(failed == 0 ? 0 : 1);
   }

   // ── 1. memory ────────────────────────────────────────────────────────────────────────────────────

   private static void memory(RegistryAccess.Frozen reg, WorldgenContext ctx, SeedCriteria heavy, int cores, int auto)
         throws InterruptedException {
      System.out.println();
      System.out.println("-- 1. memory per worker (live heap after a full GC, while the workers search; Exact, villages assembled) --");
      // Everything shared is loaded first (world-gen context, templates, the JIT), so the steps measure workers.
      Pool warm = Pool.start(reg, ctx, heavy, Math.min(4, cores), 0xA1L);
      Thread.sleep(4_000);
      warm.stop();
      long base = liveMb(5, 100);
      System.out.println(String.format("  no workers: %,d MB live", base));
      TreeSet<Integer> steps = new TreeSet<>(List.of(1, 2, 4, 8, auto, Math.min(16, cores)));
      List<long[]> points = new ArrayList<>();
      points.add(new long[]{0, base});
      double worstPerWorker = 0;
      double worstFootprint = 0;
      for (int k : steps) {
         Pool p = Pool.start(reg, ctx, heavy, k, 0xB0L + k);
         // The heap in use between collections (garbage included) — what a worker really occupies in a
         // heap that is not being collected for it — sampled for 5 s, then the live set after full GCs.
         long footprint = 0;
         for (int i = 0; i < 25; i++) {
            Thread.sleep(200);
            footprint = Math.max(footprint, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20);
         }
         long peak = liveMb(6, 400);
         long checked = p.job.checked.get();
         p.stop();
         points.add(new long[]{k, peak});
         double per = (peak - base) / (double) k;
         worstPerWorker = Math.max(worstPerWorker, per);
         worstFootprint = Math.max(worstFootprint, footprint);
         // In use between collections is how much garbage the collector lets pile up in THIS heap (6 GB:
         // ~300 MB whatever the count), so it is printed, not divided per worker — see the gc mode for "fits".
         System.out.println(String.format("  %2d worker(s): live %,5d MB → %5.1f MB a worker kept · heap in use at the peak %,5d MB"
               + " (%,d seeds)", k, peak, per, footprint, checked));
      }
      double slope = slope(points);
      System.out.println(String.format("  what a worker KEEPS: slope %.1f MB a worker, most %.1f", slope, worstPerWorker));
      check("a worker keeps (live after a full GC) under a tenth of WORKER_MB (" + SeedFinder.WORKER_MB + " MB)",
            Math.max(slope, worstPerWorker) <= SeedFinder.WORKER_MB / 10.0);
      System.out.println("  what a worker NEEDS (its garbage included) is checked by running them in exactly the heap the"
            + " rule allows: threadsDiag -Pheap=512M --args=\"gc 16\" (16 x 32 MB), with a 96 MB control");
      System.out.println(String.format("  so in the vanilla launcher's default 2 GB heap the maximum is %d workers;"
                  + " in 4 GB, %d; in 8 GB, %d (each capped at the logical processors)",
            SeedFinder.maxWorkers(1024, 2048L << 20), SeedFinder.maxWorkers(1024, 4096L << 20),
            SeedFinder.maxWorkers(1024, 8192L << 20)));
   }

   /**
    * WHAT "FITS" MEANS IN PRACTICE: k workers on the slowest search there is, in whatever heap this JVM was
    * given — seeds per second, the share of wall time spent collecting garbage, how fast each worker
    * allocates, and the most the heap held. Run at 6 GB, 1 GB and 512 MB and compared: a heap the workers
    * do not fit in shows up as GC time and lost speed long before it runs out.
    */
   /** A heap the workers fit in spends less than this share of the time collecting garbage. */
   static final double GC_LIMIT = 0.10;

   private static boolean gcPressure(RegistryAccess.Frozen reg, WorldgenContext ctx, SeedCriteria heavy, int k)
         throws InterruptedException {
      var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
      Pool warm = Pool.start(reg, ctx, heavy, Math.min(4, k), 0xD0L);
      Thread.sleep(3_000);
      warm.stop();
      long gcBefore = gcMs();
      Pool p = Pool.start(reg, ctx, heavy, k, 0xE0L + k);
      long t0 = System.nanoTime();
      long[] alloc0 = new long[k];
      for (int i = 0; i < k; i++) {
         alloc0[i] = threads.getThreadAllocatedBytes(p.ts[i].threadId());
      }
      long peakUsed = 0;
      for (int i = 0; i < 60; i++) {
         Thread.sleep(250);
         peakUsed = Math.max(peakUsed, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
      }
      double secs = (System.nanoTime() - t0) / 1e9;
      long allocated = 0;
      for (int i = 0; i < k; i++) {
         allocated += Math.max(0, threads.getThreadAllocatedBytes(p.ts[i].threadId()) - alloc0[i]);
      }
      long checked = p.job.checked.get();
      String crashed = p.job.error;   // an OutOfMemoryError in a worker ends the search with an error
      p.stop();
      double gcShare = crashed != null ? 1.0 : (gcMs() - gcBefore) / (secs * 1000);
      if (crashed != null) {
         System.out.println("  the search ended with an error: " + crashed);
      }
      long heapMb = Runtime.getRuntime().maxMemory() >> 20;
      System.out.println(String.format("  heap %,d MB, %d workers, %.0f s: %,.1f seeds/s · GC %.1f%% of the time · %,.0f MB/s"
                  + " allocated a worker · heap peaked at %,d MB",
            heapMb, k, secs, checked / secs, gcShare * 100, allocated / secs / k / (1 << 20), peakUsed >> 20));
      boolean fits = gcShare < GC_LIMIT;
      System.out.println(String.format("  %s — %d workers %s in %,d MB (the rule allows them %,d MB: %d x WORKER_MB %d)",
            fits ? "FITS" : "DOES NOT FIT", k, fits ? "run" : "spend over " + (int) (GC_LIMIT * 100) + "% of the time in GC",
            heapMb, (long) k * SeedFinder.WORKER_MB, k, SeedFinder.WORKER_MB));
      return fits;
   }

   private static long gcMs() {
      long ms = 0;
      for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
         ms += Math.max(0, gc.getCollectionTime());
      }
      return ms;
   }

   /** The live heap, in MB: a full collection, then the heap in use; the largest of {@code samples}. */
   private static long liveMb(int samples, long gapMs) throws InterruptedException {
      MemoryMXBean mx = ManagementFactory.getMemoryMXBean();
      long most = 0;
      for (int i = 0; i < samples; i++) {
         System.gc();
         most = Math.max(most, mx.getHeapMemoryUsage().getUsed());
         Thread.sleep(gapMs);
      }
      return most >> 20;
   }

   private static double slope(List<long[]> pts) {
      double n = pts.size();
      double sx = 0;
      double sy = 0;
      double sxx = 0;
      double sxy = 0;
      for (long[] p : pts) {
         sx += p[0];
         sy += p[1];
         sxx += (double) p[0] * p[0];
         sxy += (double) p[0] * p[1];
      }
      return (n * sxy - sx * sy) / (n * sxx - sx * sx);
   }

   // ── 2. the rule ──────────────────────────────────────────────────────────────────────────────────

   private static void rule() {
      System.out.println();
      System.out.println("-- 2. how many workers a setting starts --");
      long gb2 = 2048L << 20;
      long gb8 = 8192L << 20;
      check("auto on 16 threads is 15 → capped at 12, as before", SeedFinder.workerCount(0, 16, gb8) == 12);
      check("auto on 8 threads is 7", SeedFinder.workerCount(0, 8, gb8) == 7);
      check("auto on 2 threads is 1, on 1 thread is 1", SeedFinder.workerCount(0, 2, gb8) == 1 && SeedFinder.workerCount(0, 1, gb8) == 1);
      check("1 is 1", SeedFinder.workerCount(1, 16, gb8) == 1);
      check("a number is taken as asked (7 of 16)", SeedFinder.workerCount(7, 16, gb8) == 7);
      check("never more than the logical processors (32 asked on 16)", SeedFinder.workerCount(32, 16, 1L << 40) == 16);
      check("never more than the logical processors (14 asked on 8)", SeedFinder.workerCount(14, 8, 1L << 40) == 8);
      check("may exceed auto's 12 when the CPU and the heap allow (16 on 16, big heap)",
            SeedFinder.workerCount(16, 16, 1L << 40) == 16);
      long mb512 = 512L << 20;
      long fit512 = SeedFinder.heapWorkers(mb512);
      check("never more than fits in half the heap (16 asked, 16 threads, 512 MB heap → " + fit512 + ")",
            fit512 < 16 && SeedFinder.workerCount(16, 16, mb512) == fit512);
      // 1.47.3: a NUMBER never goes past MANUAL_MAX_WORKERS, the most ever tested, whatever the CPU and heap.
      boolean capped = SeedFinder.MANUAL_MAX_WORKERS == 16 && SeedFinder.workerCount(32, 32, 1L << 40) == 16
            && SeedFinder.workerCount(64, 64, 1L << 40) == 16 && SeedFinder.maxWorkers(64, gb2) == 16;
      check("never more than 16, the most tested (32 asked on 32 threads, 64 on 64, huge heap → 16)", capped);
      // CONTROL: 1.47.2's rule, from the shipped jar, on the same machine — it must start more than 16.
      Integer old = oldWorkerCount(32, 32, 1L << 40);
      if (old != null) {
         check("CONTROL: 1.47.2's rule starts " + old + " workers for 32 asked on 32 threads — past 16", old > 16);
      }
      check("auto is unchanged by the cap (64 threads → 12; 4 threads → 3)",
            SeedFinder.workerCount(0, 64, 1L << 40) == SeedFinder.AUTO_MAX_WORKERS && SeedFinder.workerCount(0, 4, gb8) == 3);
      check("a job has a slot for the most any setting can start", SeedFinder.SLOTS >= SeedFinder.MANUAL_MAX_WORKERS
            && SeedFinder.SLOTS >= SeedFinder.AUTO_MAX_WORKERS);
      check("a tiny heap still allows one", SeedFinder.maxWorkers(16, 1L << 20) == 1);
      check("busy() is exactly 'more than auto'", !SeedFinder.busy(SeedFinder.autoWorkers())
            && SeedFinder.busy(SeedFinder.autoWorkers() + 1));
      int max = SeedFinder.maxWorkers();
      boolean up = SpawnWishScreen.nextBots(0, 1) == 1 && SpawnWishScreen.nextBots(max, 1) == 0;
      boolean down = SpawnWishScreen.nextBots(0, -1) == max && SpawnWishScreen.nextBots(1, -1) == 0;
      for (int n = 1; n < max; n++) {
         up &= SpawnWishScreen.nextBots(n, 1) == n + 1;
         down &= SpawnWishScreen.nextBots(n + 1, -1) == n;
      }
      check("the Bots button steps auto, 1, 2 … " + max + ", auto — and back the other way", up && down);
      check("a saved number above this machine's maximum steps from the maximum",
            SpawnWishScreen.nextBots(max + 50, -1) == max - 1 && SpawnWishScreen.nextBots(max + 50, 1) == 0);
      check("the setting is read where the search starts: auto → " + SeedFinder.autoWorkers() + ", 1 → 1, 999 → " + max,
            SeedFinder.workerCount(0) == SeedFinder.autoWorkers() && SeedFinder.workerCount(1) == 1
                  && SeedFinder.workerCount(999) == max);
   }

   /** 1.47.2's SeedFinder.workerCount(setting, cores, heap), from the shipped jar; null if it cannot be run. */
   private static Integer oldWorkerCount(int setting, int cores, long heap) {
      try {
         if (OldJar.get() == null) {
            return null;
         }
         Class<?> finder = OldJar.cls("com.fablevision.client.seedfinder.SeedFinder");
         return (Integer) OldJar.call(finder, null, "workerCount", new Class<?>[]{int.class, int.class, long.class},
               setting, cores, heap);
      } catch (ReflectiveOperationException | LinkageError e) {
         System.out.println("  (control could not run the old rule: " + OldJar.why(e) + ")");
         return null;
      }
   }

   // ── 3. Stop ──────────────────────────────────────────────────────────────────────────────────────

   private static void stop(RegistryAccess.Frozen reg, WorldgenContext ctx, SeedCriteria heavy, TreeSet<Integer> counts)
         throws InterruptedException {
      System.out.println();
      System.out.println("-- 3. Stop, at 1 worker, auto and the maximum (Exact, villages assembled — the slowest seeds there are) --");
      for (int k : counts) {
         Pool p = Pool.start(reg, ctx, heavy, k, 0xC0L + k);
         Thread.sleep(4_000);
         long t0 = System.nanoTime();
         p.job.running = false;   // exactly what SeedFinder.stop() does
         boolean all = p.join(10_000);
         long ms = (System.nanoTime() - t0) / 1_000_000;
         check(String.format("%2d worker(s): every one out of its loop %,d ms after Stop (%,d seeds checked)", k, ms,
               p.job.checked.get()), all && ms <= 3_000);
      }
   }

   // ── 4. the watchdog ──────────────────────────────────────────────────────────────────────────────

   private static void watchdog(RegistryAccess.Frozen reg, SeedCriteria heavy, TreeSet<Integer> counts) throws Exception {
      System.out.println();
      System.out.println("-- 4. the watchdog, at 1 worker, auto and the maximum --");
      long s = 1_000_000_000L;
      CountDownLatch inside = new CountDownLatch(1);
      Thread stuck = new Thread(() -> {
         inside.countDown();
         try {
            Thread.sleep(Long.MAX_VALUE);
         } catch (InterruptedException ignored) {
         }
      }, "FableVision-SeedFinder-stuck");
      stuck.setDaemon(true);
      stuck.start();
      inside.await();
      for (int k : counts) {
         SeedFinder.Job job = new SeedFinder.Job(heavy, reg, 7L, 1, false);
         job.threads = k;
         check(k + " worker(s): the job has a slot for every one", job.seedStartNs.length() >= k && job.workers.length >= k);
         long t0 = System.nanoTime();
         SearchWatchdog w = new SearchWatchdog(job, t0);
         int last = k - 1;
         for (int i = 0; i < k; i++) {
            job.seedNow.set(i, 1000 + i);
            job.seedStartNs.set(i, t0);
         }
         job.workers[last] = stuck;
         job.checked.set(10);
         boolean quiet = w.check(t0 + 5 * s).isEmpty();
         // Every worker but the last moves on; the last stays on its seed.
         for (int i = 0; i < last; i++) {
            job.seedStartNs.set(i, t0 + 60 * s);
         }
         job.checked.set(20);
         List<String> r = w.check(t0 + 61 * s);
         check(k + " worker(s): quiet while healthy, then worker " + last + " (the last) reported stuck on its seed",
               quiet && r.size() == 1 && r.get(0).contains("worker " + last + " has been on one seed")
                     && r.get(0).contains(Long.toString(1000 + last)));
      }
   }

   // ── a search with no gate, for measuring (the same loop body as WorldsRun) ───────────────────────────

   private static final class Pool {
      SeedFinder.Job job;
      Thread[] ts;

      static Pool start(RegistryAccess.Frozen reg, WorldgenContext ctx, SeedCriteria criteria, int k, long stream) {
         Pool p = new Pool();
         p.job = new SeedFinder.Job(criteria, reg, stream, Integer.MAX_VALUE, false);
         SeedFinder.warmTemplates(p.job);
         p.job.threads = k;
         p.ts = new Thread[k];
         for (int i = 0; i < k; i++) {
            final int index = i;
            p.ts[i] = new Thread(() -> {
               SplittableRandom random = SeedFinder.workerStream(stream, index);
               List<String> lines = new ArrayList<>(4);
               int[] worst = new int[1];
               while (p.job.running) {
                  if (!SeedFinder.consider(p.job, ctx, index, random.nextLong(), lines, worst)) {
                     return;
                  }
                  synchronized (p.job.results) {
                     p.job.results.clear();   // pregen's shape drains; nothing kept for memory to count
                  }
               }
            }, "threadsDiag-" + i);
            p.ts[i].setDaemon(true);
            p.ts[i].setPriority(Thread.MIN_PRIORITY);
            p.job.workers[i] = p.ts[i];
            p.ts[i].start();
         }
         return p;
      }

      void stop() throws InterruptedException {
         job.running = false;
         join(30_000);
      }

      boolean join(long ms) throws InterruptedException {
         long until = System.currentTimeMillis() + ms;
         for (Thread t : ts) {
            t.join(Math.max(1, until - System.currentTimeMillis()));
            if (t.isAlive()) {
               return false;
            }
         }
         return true;
      }
   }

   private static void check(String what, boolean ok) {
      System.out.println("  " + (ok ? "ok   " : "FAIL ") + what);
      if (!ok) {
         failed++;
      }
   }

   private ThreadsDiagnostic() {
   }
}
