package com.fablevision;

import com.fablevision.client.seedfinder.SlimeChunks;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Two questions about slime chunks, both of which have to be answered before a row is worth adding.
 *
 * ONE: is the predicate right? The salt is the only value in {@link SlimeChunks} that cannot be read
 * from a vanilla field, so it is the only thing that could be silently wrong. A wrong salt does not
 * throw and does not look broken — it produces a perfectly plausible scattering of chunks that
 * happens to be the wrong scattering. What it CANNOT do is land on one chunk in ten, so measuring
 * the rate over millions of chunks is the check.
 *
 * TWO: does asking for N of them near spawn actually narrow anything? This matters more than it
 * looks. Slime chunks are 10% of ALL chunks, and the number of chunks inside a radius grows with its
 * square, so a radius that sounds tight contains far more of them than intuition suggests. If
 * "3 slime chunks within 200" is true of essentially every seed then it is not a search, it is a
 * decoration, and shipping it as a row would waste a player's time twice: once picking it and once
 * waiting for a filter that filters nothing.
 *
 * {@code gradlew slimeDiag --args="[seeds]"}
 */
public final class SlimeDiagnostic {

   /** The radii worth asking about: small enough to build in, out to "the local area". */
   private static final int[] RADII = {32, 48, 64, 96, 128, 200, 400};
   private static final int[] COUNTS = {1, 2, 3, 5, 8};

   public static void main(String[] args) {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 2000;

      System.out.println("=========== IS THE PREDICATE RIGHT? ===========");
      System.out.println("  vanilla's rule is nextInt(" + SlimeChunks.ONE_IN + ") == 0, so the rate");
      System.out.println("  must land on " + (100 / SlimeChunks.ONE_IN) + "%. Anything else means the salt is wrong.");
      long total = 0;
      long slime = 0;
      for (long seed = 1; seed <= 200; seed++) {
         for (int x = -80; x < 80; x++) {
            for (int z = -80; z < 80; z++) {
               total++;
               if (SlimeChunks.isSlimeChunk(seed, x, z)) {
                  slime++;
               }
            }
         }
      }
      double pct = 100.0 * slime / total;
      System.out.printf("  %,d chunks over 200 seeds: %,d slime (%.3f%%)  %s%n",
            total, slime, pct, Math.abs(pct - 10.0) < 0.2 ? "OK" : "<-- WRONG, do not ship this");
      // "Do not ship this" was printed and the run still exited 0 — fixed 1.44.2, with the table below.
      int problems = Math.abs(pct - 10.0) < 0.2 ? 0 : 1;

      System.out.println();
      System.out.println("=========== HOW MANY ARE NEAR SPAWN? over " + seeds + " seeds ===========");
      System.out.println("  measured from 0,0 — the count a player would have around them");
      System.out.printf("  %-10s %10s %10s%n", "RADIUS", "AVERAGE", "FEWEST");
      for (int r : RADII) {
         long sum = 0;
         int min = Integer.MAX_VALUE;
         for (long seed = 1; seed <= seeds; seed++) {
            int n = SlimeChunks.count(seed, 0, 0, r, 10_000);
            sum += n;
            min = Math.min(min, n);
         }
         System.out.printf("  %-10s %10.1f %10d%n", "<=" + r, (double) sum / seeds, min);
      }

      System.out.println();
      System.out.println("=========== SO WHICH ASKS ARE REAL SEARCHES? ===========");
      System.out.println("  % of seeds with AT LEAST n slime chunks within r. Anything at 100% is a");
      System.out.println("  filter that rejects nothing and must be labelled as such in the picker.");
      System.out.printf("  %-10s", "RADIUS");
      for (int n : COUNTS) {
         System.out.printf("%9s", n + "+");
      }
      System.out.println();
      for (int r : RADII) {
         int[] hits = new int[COUNTS.length];
         for (long seed = 1; seed <= seeds; seed++) {
            int n = SlimeChunks.count(seed, 0, 0, r, 10_000);
            for (int i = 0; i < COUNTS.length; i++) {
               if (n >= COUNTS[i]) {
                  hits[i]++;
               }
            }
         }
         System.out.printf("  %-10s", "<=" + r);
         for (int h : hits) {
            System.out.printf("%8.0f%%", 100.0 * h / seeds);
         }
         System.out.println();
         // Does SlimeChunks.narrows() agree with what just happened? It predicts from the binomial
         // rather than from a table, so this is the line that keeps the prediction honest: a "yes"
         // against a measured 100% would mean the app is calling a certainty a search.
         System.out.printf("  %-10s", "  predict");
         for (int i = 0; i < COUNTS.length; i++) {
            boolean predicted = SlimeChunks.narrows(COUNTS[i], r);
            System.out.printf("%9s", predicted ? "filters" : "no-op");
            // The failure the comment above describes, now counted: the app calling a certainty a search.
            if (predicted && hits[i] >= seeds * 995L / 1000) {
               problems++;
            }
         }
         System.out.println();
      }
      System.out.println("===============================================");
      System.out.println(problems == 0 ? "OK" : problems + " PROBLEM(S) — see above");
      System.exit(problems == 0 ? 0 : 1);
   }

   private SlimeDiagnostic() {
   }
}
