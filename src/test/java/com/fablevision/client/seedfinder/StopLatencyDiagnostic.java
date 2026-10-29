package com.fablevision.client.seedfinder;

import com.fablevision.VillageDiagnostic;
import com.fablevision.client.FableVisionConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;

/**
 * HOW LONG STOP TAKES WHEN A WORKER IS IN THE MIDDLE OF AN ASSEMBLY (1.47.3): {@code gradlew stopDiag}.
 *
 * Stop is a flag every worker reads between seeds ({@link SeedFinder#stop}), so the wait is however much of
 * its seed a worker still had to do. The slow seeds are the content rows, which assemble structures, and the
 * slowest are the big ones: mansions and trial chambers. For each case this runs a real search on auto's
 * worker count (the worker loop's own body, {@link SeedFinder#consider}, without the gate — see WorldsRun),
 * watches the workers' stacks until one is INSIDE an assembly (VillageLayout.assemble's lambda, around
 * vanilla's Structure.generate), sets the flag exactly as Stop does, and times how long that worker and then
 * every worker take to leave the loop. Trials per case: --args="<n, default 10>".
 *
 * It measures; it decides nothing. The owner's line is about a second.
 */
public final class StopLatencyDiagnostic {

   record Case(String row, boolean fast, int radius) {
      String name() {
         return row + (fast ? ", Fast " + radius : ", Exact " + radius);
      }
   }

   public static void main(String[] args) throws Exception {
      int trials = args.length > 0 ? Integer.parseInt(args[0]) : 10;
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      VillageLayout.templates();
      int workers = SeedFinder.autoWorkers();
      System.out.println("========== STOP WHILE A WORKER IS MID-ASSEMBLY ==========");
      System.out.println("  " + workers + " workers (auto), " + trials + " trials a case; times from the Stop flag");
      List<Case> cases = List.of(
            new Case("Mansion with Lava Vault", false, FableVisionConfig.SEED_EXACT_RADIUS),
            new Case("Mansion with Lava Vault", true, 800),
            new Case("Trial Chamber with Eruption Room", false, FableVisionConfig.SEED_EXACT_RADIUS),
            new Case("Trial Chamber with Eruption Room", true, 800));
      long worstAll = 0;
      for (Case c : cases) {
         SeedCriteria.StructureTarget row = null;
         for (SeedCriteria.StructureTarget t : SeedCatalog.structures(ctx)) {
            if (t.label.equals(c.row())) {
               row = t;
            }
         }
         if (row == null) {
            System.out.println("  FAIL the catalog has no " + c.row());
            System.exit(1);
         }
         SeedCriteria criteria = new SeedCriteria();
         criteria.structures.add(row.withRadius(c.radius()));
         long[] caught = new long[trials];
         long[] all = new long[trials];
         long[] age = new long[trials];
         int done = 0;
         for (int trial = 0; trial < trials; trial++) {
            long[] r = trial(reg, ctx, criteria, c.fast(), workers, 0x5700L + trial * 31L + c.hashCode());
            if (r == null) {
               System.out.println("  " + c.name() + ": no worker reached an assembly within 60 s — skipped");
               break;
            }
            caught[done] = r[0];
            all[done] = r[1];
            age[done] = r[2];
            done++;
         }
         if (done == 0) {
            continue;
         }
         caught = Arrays.copyOf(caught, done);
         all = Arrays.copyOf(all, done);
         age = Arrays.copyOf(age, done);
         Arrays.sort(caught);
         Arrays.sort(all);
         long[] aged = Arrays.stream(age).filter(a -> a >= 0).sorted().toArray();
         int inRecheck = done - aged.length;
         worstAll = Math.max(worstAll, all[done - 1]);
         System.out.println(String.format("  %-48s the worker caught assembling: median %,5d ms, worst %,5d ms ·"
                     + " every worker out: median %,5d ms, worst %,5d ms · %s (%d trials)",
               c.name(), caught[done / 2], caught[done - 1], all[done / 2], all[done - 1],
               (aged.length == 0 ? "" : String.format("that worker's seed was %,d-%,d ms old", aged[0], aged[aged.length - 1]))
                     + (inRecheck == 0 ? "" : (aged.length == 0 ? "" : ", ") + inRecheck + " caught in a hit's re-check"),
               done));
      }
      System.out.println(String.format("  WORST: every worker out of its loop %,d ms after Stop%s", worstAll,
            worstAll > 1_000 ? " — OVER the ~1 s line" : " — within the ~1 s line"));
      System.exit(0);
   }

   /**
    * One trial: workers start; as soon as one is seen inside an assembly the flag goes down. Returns {ms until
    * that worker left, ms until all had, how long that worker had been on its seed}, or null when no worker
    * reached an assembly in 60 s.
    */
   private static long[] trial(RegistryAccess.Frozen reg, WorldgenContext ctx, SeedCriteria criteria, boolean fast,
                               int k, long stream) throws InterruptedException {
      SeedFinder.Job job = new SeedFinder.Job(criteria, reg, stream, Integer.MAX_VALUE, fast);
      SeedFinder.warmTemplates(job);
      job.threads = k;
      Thread[] ts = new Thread[k];
      for (int i = 0; i < k; i++) {
         final int index = i;
         ts[i] = new Thread(() -> {
            SplittableRandom random = SeedFinder.workerStream(stream, index);
            List<String> lines = new ArrayList<>(4);
            int[] worst = new int[1];
            while (job.running) {
               if (!SeedFinder.consider(job, ctx, index, random.nextLong(), lines, worst)) {
                  return;
               }
               synchronized (job.results) {
                  job.results.clear();
               }
            }
         }, "stopDiag-" + i);
         ts[i].setDaemon(true);
         ts[i].setPriority(Thread.MIN_PRIORITY);
         job.workers[i] = ts[i];
         ts[i].start();
      }
      Thread.sleep(500);   // past the first seeds, so a trial is not timing class loading
      long until = System.currentTimeMillis() + 60_000;
      int inside = -1;
      while (inside < 0 && System.currentTimeMillis() < until) {
         for (int i = 0; i < k && inside < 0; i++) {
            if (assembling(ts[i])) {
               inside = i;
            }
         }
         if (inside < 0) {
            Thread.sleep(2);
         }
      }
      long t0 = System.nanoTime();
      // 0 = between seeds as the engine counts them: the worker is in describe(), the re-check of a hit, which
      // assembles again after the seed's own clock has stopped. Reported as -1 and left out of the range.
      long startedNs = inside < 0 ? 0 : job.seedStartNs.get(inside);
      long seedAgeMs = startedNs == 0 ? -1 : (t0 - startedNs) / 1_000_000;
      job.running = false;   // exactly what SeedFinder.stop() does
      long caughtMs = -1;
      if (inside >= 0) {
         ts[inside].join(60_000);
         caughtMs = (System.nanoTime() - t0) / 1_000_000;
      }
      for (Thread t : ts) {
         t.join(60_000);
      }
      long allMs = (System.nanoTime() - t0) / 1_000_000;
      return inside < 0 ? null : new long[]{caughtMs, allMs, seedAgeMs};
   }

   /** Is this thread inside a structure assembly right now? */
   private static boolean assembling(Thread t) {
      for (StackTraceElement e : t.getStackTrace()) {
         if (e.getClassName().equals(VillageLayout.class.getName()) && e.getMethodName().startsWith("lambda$assemble")) {
            return true;
         }
      }
      return false;
   }

   private StopLatencyDiagnostic() {
   }
}
