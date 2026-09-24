package com.fablevision.client.seedfinder;

import java.util.Arrays;
import java.util.Random;
import com.fablevision.VillageDiagnostic;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * WHAT A TERRAIN-HEIGHT SEARCH WOULD COST, measured before anything is built.
 *
 * "Flat land near spawn, in a height range" is a real gap against Chunkbase and the obvious next
 * feature. It is also the first thing this project has considered that reads TERRAIN rather than
 * placement math, and terrain is the expensive half of world generation — so the question is not
 * whether it can be done but whether it can be done at a speed that leaves a search usable.
 *
 * Three tiers exist and this measures all three, for cost AND for accuracy:
 *
 *   TRUTH        {@code getBaseHeight} — vanilla's real answer. It walks the noise column cell by
 *                cell looking for the first solid block, so it is the honest number and the slow one.
 *   APPROXIMATE  {@code NoiseRouter.preliminarySurfaceLevel} — ONE density-function evaluation at
 *                (x, 0, z). This is not a shortcut invented here: it is what vanilla itself uses to
 *                decide where structures can sit, via NoiseChunk.preliminarySurfaceLevel.
 *   SHAPE ONLY   the {@code erosion} and {@code depth} inputs, which say what KIND of terrain this
 *                is without committing to a height.
 *
 * The accuracy half matters as much as the cost. An approximation that is 20 blocks out is useless
 * for "a flat spot between y=64 and y=80" and fine for "not a mountain", and those are different
 * features — so the error is measured rather than assumed, and reported as a distribution rather
 * than an average, because the worst case is what decides whether a promise can be made.
 *
 * {@code gradlew terrainCost --args="<columns>"}
 */
public final class TerrainHeightDiagnostic {

   public static void main(String[] args) {
      int columns = args.length > 0 ? Integer.parseInt(args[0]) : 20000;
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);

      long seed = 12345L;
      WorldgenContext.Scaffold scaffold = ctx.scaffold(SeedCriteria.Dim.OVERWORLD);
      RandomState rs = ctx.fullRandomState(SeedCriteria.Dim.OVERWORLD, seed);
      DensityFunction prelim = rs.router().preliminarySurfaceLevel();
      DensityFunction erosion = rs.router().erosion();

      int[] xs = new int[columns];
      int[] zs = new int[columns];
      Random rng = new Random(99);
      for (int i = 0; i < columns; i++) {
         xs[i] = rng.nextInt(4001) - 2000;
         zs[i] = rng.nextInt(4001) - 2000;
      }

      // Warm up, so the first numbers are not measuring the JIT.
      for (int i = 0; i < Math.min(2000, columns); i++) {
         scaffold.generator().getBaseHeight(xs[i], zs[i], Heightmap.Types.WORLD_SURFACE_WG,
               scaffold.height(), rs);
         prelim.compute(new DensityFunction.SinglePointContext(xs[i], 0, zs[i]));
      }

      System.out.println("=========== COST PER COLUMN (" + columns + " columns, ±2000 of spawn) ===========");

      int[] truth = new int[columns];
      long t0 = System.nanoTime();
      for (int i = 0; i < columns; i++) {
         truth[i] = scaffold.generator().getBaseHeight(xs[i], zs[i], Heightmap.Types.WORLD_SURFACE_WG,
               scaffold.height(), rs);
      }
      double truthUs = (System.nanoTime() - t0) / 1000.0 / columns;

      int[] approx = new int[columns];
      t0 = System.nanoTime();
      for (int i = 0; i < columns; i++) {
         approx[i] = Mth.floor(prelim.compute(new DensityFunction.SinglePointContext(xs[i], 0, zs[i])));
      }
      double approxUs = (System.nanoTime() - t0) / 1000.0 / columns;

      t0 = System.nanoTime();
      double sink = 0;
      for (int i = 0; i < columns; i++) {
         sink += erosion.compute(new DensityFunction.SinglePointContext(xs[i], 0, zs[i]));
      }
      double erosionUs = (System.nanoTime() - t0) / 1000.0 / columns;

      System.out.printf("  getBaseHeight (TRUTH)          %8.2f us/column%n", truthUs);
      System.out.printf("  preliminarySurfaceLevel        %8.2f us/column   (%.0fx cheaper)%n",
            approxUs, truthUs / approxUs);
      System.out.printf("  erosion only (shape)           %8.2f us/column   (%.0fx cheaper)%n",
            erosionUs, truthUs / erosionUs);
      System.out.println("  (sink " + (long) sink + " — keeps the erosion loop from being optimised away)");

      System.out.println();
      System.out.println("=========== HOW WRONG IS THE CHEAP ONE? ===========");
      int[] err = new int[columns];
      for (int i = 0; i < columns; i++) {
         err[i] = approx[i] - truth[i];
      }
      int[] abs = new int[columns];
      for (int i = 0; i < columns; i++) {
         abs[i] = Math.abs(err[i]);
      }
      Arrays.sort(abs);
      long sum = 0;
      for (int v : abs) {
         sum += v;
      }
      System.out.printf("  error |approx - truth| in blocks:  mean %.1f   median %d   p90 %d   p99 %d   max %d%n",
            sum / (double) columns, abs[columns / 2], abs[(int) (columns * 0.90)],
            abs[(int) (columns * 0.99)], abs[columns - 1]);
      int within2 = 0;
      int within5 = 0;
      int within10 = 0;
      for (int v : abs) {
         if (v <= 2) {
            within2++;
         }
         if (v <= 5) {
            within5++;
         }
         if (v <= 10) {
            within10++;
         }
      }
      System.out.printf("  within 2 blocks %.1f%%   within 5 %.1f%%   within 10 %.1f%%%n",
            100.0 * within2 / columns, 100.0 * within5 / columns, 100.0 * within10 / columns);

      System.out.println();
      System.out.println("=========== WHAT THAT MEANS FOR A SEARCH ===========");
      // A "flat area" test has to sample a PATCH, not a point: the question is whether the ground
      // stays level across somewhere you could build. A 32x32 area sampled every 8 blocks is 25
      // columns; every 4 blocks is 81.
      for (int grid : new int[]{9, 25, 81}) {
         double truthMs = truthUs * grid / 1000.0;
         double approxMs = approxUs * grid / 1000.0;
         System.out.printf("  %2d columns/candidate:  truth %6.2f ms   approx %6.3f ms%n",
               grid, truthMs, approxMs);
      }
      patchSpread(ctx, scaffold, rs, prelim);
      // The number that decides everything: Exact mode already pays ~10.6ms per seed for
      // findSpawnPosition, and that sets the scale a new per-seed cost is judged against.
      System.out.println();
      System.out.printf("  For scale: Exact mode already pays ~10.6 ms per seed for findSpawnPosition,%n"
            + "  and a whole Exact-mode seed evaluation is ~15 ms.%n");
      System.out.printf("  25 columns of TRUTH  = %.2f ms = %.0f%% of a seed's current cost.%n",
            truthUs * 25 / 1000.0, truthUs * 25 / 1000.0 / 15.0 * 100);
      System.out.printf("  25 columns of APPROX = %.3f ms = %.1f%% of a seed's current cost.%n",
            approxUs * 25 / 1000.0, approxUs * 25 / 1000.0 / 15.0 * 100);
   }

   /**
    * THE QUESTION THE ABSOLUTE ERROR DOES NOT ANSWER.
    *
    * The cheap estimate is a mean of ~19 blocks away from the truth, which rules it out for "a spot
    * between y=64 and y=80" — the error is wider than the window. But "flat" is not a question about
    * absolute height at all; it is a question about the SPREAD across a patch. If the estimate is
    * wrong by a similar amount everywhere within one 32x32 area, the spread survives even though the
    * height does not, and the cheap tier can answer flatness while being useless for altitude.
    *
    * That is a claim about spatial correlation, so it gets measured rather than assumed: real
    * patches, real spreads, both tiers, and how often they AGREE ON THE VERDICT — which is the only
    * thing a feature would actually use.
    */
   private static void patchSpread(WorldgenContext ctx, WorldgenContext.Scaffold scaffold,
                                   RandomState rs, DensityFunction prelim) {
      System.out.println();
      System.out.println("=========== IS IT STILL RIGHT ABOUT *FLATNESS*? ===========");
      System.out.println("  32x32 patches sampled every 8 blocks (25 columns each).");
      int patches = 240;
      int flatIfSpreadAtMost = 4;   // "flat enough to build on" for the purposes of this test
      Random rng = new Random(4242);
      int agree = 0;
      int truthFlat = 0;
      int approxFlat = 0;
      int falseFlat = 0;
      int missedFlat = 0;
      long spreadErrSum = 0;
      for (int p = 0; p < patches; p++) {
         int ox = rng.nextInt(4001) - 2000;
         int oz = rng.nextInt(4001) - 2000;
         int tMin = Integer.MAX_VALUE;
         int tMax = Integer.MIN_VALUE;
         int aMin = Integer.MAX_VALUE;
         int aMax = Integer.MIN_VALUE;
         for (int dx = 0; dx < 32; dx += 8) {
            for (int dz = 0; dz < 32; dz += 8) {
               int x = ox + dx;
               int z = oz + dz;
               int t = scaffold.generator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG,
                     scaffold.height(), rs);
               int a = Mth.floor(prelim.compute(new DensityFunction.SinglePointContext(x, 0, z)));
               tMin = Math.min(tMin, t);
               tMax = Math.max(tMax, t);
               aMin = Math.min(aMin, a);
               aMax = Math.max(aMax, a);
            }
         }
         int tSpread = tMax - tMin;
         int aSpread = aMax - aMin;
         spreadErrSum += Math.abs(aSpread - tSpread);
         boolean tFlat = tSpread <= flatIfSpreadAtMost;
         boolean aFlat = aSpread <= flatIfSpreadAtMost;
         if (tFlat) {
            truthFlat++;
         }
         if (aFlat) {
            approxFlat++;
         }
         if (tFlat == aFlat) {
            agree++;
         } else if (aFlat) {
            falseFlat++;    // the cheap tier would PROMISE flat ground that is not flat
         } else {
            missedFlat++;   // it would skip a genuinely flat spot: costs speed, not honesty
         }
      }
      System.out.printf("  truth says flat: %d/%d    approx says flat: %d/%d%n",
            truthFlat, patches, approxFlat, patches);
      System.out.printf("  spread error |approx - truth|: mean %.1f blocks%n",
            spreadErrSum / (double) patches);
      System.out.printf("  verdicts agree: %d/%d (%.0f%%)%n", agree, patches, 100.0 * agree / patches);
      System.out.println("  FALSE FLAT (would promise flat ground that isn't): " + falseFlat
            + (falseFlat == 0 ? "  <- the one that would be dishonest" : "  <- these are the problem"));
      System.out.println("  missed flat (would skip a good spot; costs speed only): " + missedFlat);
   }

   private TerrainHeightDiagnostic() {
   }
}
