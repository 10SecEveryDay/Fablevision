package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.List;

import com.fablevision.VillageDiagnostic;
import com.fablevision.client.FableVisionConfig;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;

/**
 * IS THE SEARCH ETA IN THE RIGHT BALLPARK? The screen quotes "about 1 in N seeds". This computes that
 * estimate for a handful of wishes, then counts how many seeds the real search really tests per match
 * on the same criteria — and flags any wish where the two differ by more than {@link #TOLERANCE} times.
 *
 * The estimate is documented as rough (items are not independent), so the tolerance is wide on
 * purpose. What this catches is an estimate that is wrong by an order of magnitude, which would make
 * the number worse than no number.
 *
 * {@code gradlew etaDiag --args="[matches per wish]"}
 */
public final class EtaDiagnostic {
   private static final double TOLERANCE = 6.0;

   public static void main(String[] args) throws Exception {
      int wantMatches = args.length > 0 ? Integer.parseInt(args[0]) : 12;
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      SeedCatalogCache.ensure(reg);
      for (int waited = 0; !SeedCatalogCache.ready() && waited < 60_000; waited += 100) {
         Thread.sleep(100);
      }
      WorldgenContext ctx = WorldgenContext.get(reg);
      FableVisionConfig.seedFastMode = true;
      FableVisionConfig.seedFastDistance = 200;

      String[] wishes = {
         "village",
         "ruined portal and a shipwreck",
         "cherry grove",
         "village next to a plains",
         "village with an armorer",
         "pillager outpost",
         // 1.43.1: a structure-to-structure pair. Its estimate used to crash silently, so the
         // screen showed no ETA at all for this kind of wish.
         "village next to a ruined portal",
      };
      System.out.println("========== SEARCH ETA: ESTIMATE AGAINST THE REAL SEARCH (Fast, 200 blocks) ==========");
      System.out.printf("  %-34s %14s %14s %8s%n", "wish", "estimate 1 in", "measured 1 in", "ratio");
      int bad = 0;
      for (String wish : wishes) {
         LocalWish.Result local = LocalWish.parse(wish);
         WishParser.Outcome out = local.found() ? WishParser.parse(local.json().toString(), wish) : null;
         if (out == null || out.criteria() == null) {
            System.out.println("  " + wish + ": could not be read — " + (out == null ? local.unknown() : out.error()));
            bad++;
            continue;
         }
         SeedCriteria criteria = out.criteria();
         SeedFinder.Estimate e = SeedFinder.estimate(criteria, ctx, true, 3000);
         if (e == null) {
            System.out.println("  " + wish + ": NO ESTIMATE - the screen would show none");
            bad++;
            continue;
         }
         long tested = 0;
         int matches = 0;
         long until = System.currentTimeMillis() + 120_000;
         List<String> lines = new ArrayList<>();
         java.util.Random rnd = new java.util.Random(wish.hashCode());
         while (matches < wantMatches && System.currentTimeMillis() < until) {
            lines.clear();
            if (criteria.test(rnd.nextLong(), ctx, lines, true)) {
               matches++;
            }
            tested++;
         }
         double measured = matches == 0 ? Double.POSITIVE_INFINITY : (double) tested / matches;
         double estimated = e.seedsPerMatch();
         double ratio = Math.max(measured, estimated) / Math.min(measured, estimated);
         boolean ok = matches == 0 ? e.atMost() : ratio <= TOLERANCE;
         System.out.printf("  %-34s %14s %14s %8s%s%n", wish,
               String.format("%,d%s", (long) estimated, e.atMost() ? "+" : ""),
               matches == 0 ? ">" + String.format("%,d", tested) : String.format("%,.0f", measured),
               matches == 0 ? "-" : String.format("%.1fx", ratio),
               (ok ? "" : "  <-- OFF BY MORE THAN " + TOLERANCE + "x") + (e.rough() ? "  (rough)" : "")
                     + "  " + matches + " matches in " + String.format("%,d", tested));
         if (!ok) {
            bad++;
         }
      }
      // THE ESTIMATE MUST NOT OUTSTAY ITS WELCOME (1.43.1). It runs beside the search it is
      // describing, on the core the search did not take, so it is given a ceiling and a way to be
      // called off. Both are measured here rather than trusted: a stop that is only checked between
      // items would still sit through the 40-trial floor of the item it is in.
      System.out.println();
      System.out.println("========== THE ESTIMATE STOPS WHEN IT IS TOLD TO ==========");
      LocalWish.Result big = LocalWish.parse("village and a ruined portal and a shipwreck and a desert pyramid");
      WishParser.Outcome bundle = big.found() ? WishParser.parse(big.json().toString(), "bundle") : null;
      if (bundle == null || bundle.criteria() == null) {
         System.out.println("  could not build a bundle wish to test with");
         bad++;
      } else {
         long t0 = System.currentTimeMillis();
         SeedFinder.Estimate capped = SeedFinder.estimate(bundle.criteria(), ctx, true, 1500, 2000, () -> false);
         long cappedMs = System.currentTimeMillis() - t0;
         t0 = System.currentTimeMillis();
         SeedFinder.Estimate stopped = SeedFinder.estimate(bundle.criteria(), ctx, true, 1500, 60_000, () -> true);
         long stoppedMs = System.currentTimeMillis() - t0;
         System.out.println("  2s ceiling  : finished in " + cappedMs + "ms, "
               + (capped == null ? "no estimate" : "estimate given" + (capped.rough() ? " (rough)" : "")));
         System.out.println("  told to stop: finished in " + stoppedMs + "ms, "
               + (stopped == null ? "no estimate (correct)" : "STILL RETURNED ONE"));
         if (cappedMs > 3500) {
            System.out.println("  <-- the total ceiling was not honoured");
            bad++;
         }
         if (stoppedMs > 500 || stopped != null) {
            System.out.println("  <-- being told to stop did not stop it");
            bad++;
         }
      }

      System.out.println(bad == 0 ? "ALL WITHIN " + TOLERANCE + "x" : bad + " FAILED");
      System.exit(bad == 0 ? 0 : 1);
   }

   private EtaDiagnostic() {
   }
}
