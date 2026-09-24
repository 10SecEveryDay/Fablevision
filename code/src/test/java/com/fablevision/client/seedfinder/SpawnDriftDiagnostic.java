package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.fablevision.VillageDiagnostic;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;

/**
 * The measurement that decides how Fast mode should be made honest.
 *
 * The complaint: Fast mode set to 100 blocks found something 139 blocks from the real spawn. Fast
 * mode measures from 0,0 and the player spawns wherever {@code findSpawnPosition} puts them, so the
 * two numbers are answers to different questions. Two ways to fix that were on the table and the
 * choice between them is not a matter of taste, it is a matter of how far the spawn actually
 * wanders and how often each check would therefore throw a seed away:
 *
 *   CHEAP  — take the find the fast pass reported, measure IT from the real spawn, reject if that
 *            is over the asked distance. One spawn lookup. Pessimistic: it cannot see that a
 *            DIFFERENT structure of the same kind might be sitting right next to the player.
 *   FULL   — re-run the whole wish measured from the real spawn at the asked radius. Also one
 *            spawn lookup, but it costs the funnel again, and it accepts every seed that really
 *            does satisfy the wish rather than only the ones whose first find happened to qualify.
 *
 * This prints the spawn-drift distribution and then, for a real wish at each of the four Fast
 * distances, what fraction of fast hits each check would keep. That is the whole decision.
 *
 * {@code gradlew spawnDrift [seeds]}
 */
public final class SpawnDriftDiagnostic {

   public static void main(String[] args) {
      int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      List<SeedCriteria.StructureTarget> catalog = SeedCatalog.structures(ctx);

      drift(ctx, seeds);
      System.out.println();
      for (int distance : new int[]{100, 200, 400, 800}) {
         keepRate(ctx, catalog, "Surface Village", distance, 400);
      }
   }

   /** How far the real spawn is from 0,0 — the whole size of the problem, in one table. */
   private static void drift(WorldgenContext ctx, int seeds) {
      System.out.println("=========== HOW FAR THE REAL SPAWN IS FROM 0,0 (" + seeds + " seeds) ===========");
      int[] d = new int[seeds];
      long started = System.currentTimeMillis();
      for (int i = 0; i < seeds; i++) {
         BlockPos spawn = ctx.randomState(SeedCriteria.Dim.OVERWORLD, i + 1L).sampler().findSpawnPosition();
         d[i] = (int) Math.round(Math.hypot(spawn.getX(), spawn.getZ()));
      }
      Arrays.sort(d);
      System.out.printf("  took %.1fs  (%.1f ms per spawn lookup — this is the cost of one re-check)%n",
            (System.currentTimeMillis() - started) / 1000.0,
            (System.currentTimeMillis() - started) / (double) seeds);
      System.out.println("  min " + d[0] + "   p25 " + d[seeds / 4] + "   median " + d[seeds / 2]
            + "   p75 " + d[seeds * 3 / 4] + "   p95 " + d[(int) (seeds * 0.95)] + "   max " + d[seeds - 1]);
      for (int within : new int[]{50, 100, 200, 400, 800, 1600}) {
         int n = 0;
         for (int v : d) {
            if (v <= within) {
               n++;
            }
         }
         System.out.printf("  spawn within %4d of 0,0: %5.1f%%%n", within, 100.0 * n / seeds);
      }
   }

   /**
    * Of the seeds Fast mode would report as hits, how many survive each of the two re-checks.
    *
    * The gap between the two columns is what the FULL check buys, and the second column is what
    * Fast mode would actually deliver — if that number is tiny, tight distances become unusable
    * and honest labelling is the better answer.
    */
   private static void keepRate(WorldgenContext ctx, List<SeedCriteria.StructureTarget> catalog,
                                String label, int distance, int wantHits) {
      SeedCriteria.StructureTarget row = null;
      for (SeedCriteria.StructureTarget t : catalog) {
         if (t.label.equals(label)) {
            row = t;
         }
      }
      if (row == null) {
         return;
      }
      SeedCriteria c = new SeedCriteria();
      c.structures.add(row.withRadius(distance));

      long started = System.currentTimeMillis();
      int scanned = 0;
      int fastHits = 0;
      int keptCheap = 0;
      int keptFull = 0;
      long cheapMs = 0;
      long fullMs = 0;
      List<String> lines = new ArrayList<>();
      for (long seed = 1; fastHits < wantHits && seed < 400_000; seed++) {
         scanned++;
         lines.clear();
         if (!c.test(seed, ctx, lines, true)) {
            continue;
         }
         fastHits++;
         // CHEAP: one spawn lookup, then measure the find that was already reported.
         long t0 = System.nanoTime();
         BlockPos spawn = ctx.randomState(SeedCriteria.Dim.OVERWORLD, seed).sampler().findSpawnPosition();
         boolean cheapOk = true;
         for (String line : lines) {
            int[] xz = coordsOf(line);
            if (xz != null && Math.hypot(xz[0] - spawn.getX(), xz[1] - spawn.getZ()) > distance) {
               cheapOk = false;
            }
         }
         cheapMs += (System.nanoTime() - t0) / 1_000_000;
         if (cheapOk) {
            keptCheap++;
         }
         // FULL: the same wish, measured from the real spawn — which is exactly Exact mode.
         t0 = System.nanoTime();
         List<String> exact = new ArrayList<>();
         if (c.test(seed, ctx, exact, false)) {
            keptFull++;
         }
         fullMs += (System.nanoTime() - t0) / 1_000_000;
      }
      System.out.printf("  %-16s ≤%-4d  scanned %,7d  fast hits %4d (1 in %,d)   "
                  + "cheap keeps %5.1f%%   full keeps %5.1f%%   cheap %,dms  full %,dms   total %.1fs%n",
            label, distance, scanned, fastHits, fastHits == 0 ? 0 : scanned / fastHits,
            pct(keptCheap, fastHits), pct(keptFull, fastHits), cheapMs, fullMs,
            (System.currentTimeMillis() - started) / 1000.0);
   }

   private static double pct(int a, int b) {
      return b == 0 ? 0 : 100.0 * a / b;
   }

   /** The "Overworld X, Z" pair out of a match line, or null when the line has no position. */
   private static int[] coordsOf(String line) {
      java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("Overworld (-?\\d+), (-?\\d+)").matcher(line);
      return m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))} : null;
   }

   private SpawnDriftDiagnostic() {
   }
}
