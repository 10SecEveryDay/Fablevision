package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fablevision.VillageDiagnostic;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;

/**
 * DOES A "WITHIN N" SEARCH EVER REPORT A FIND FURTHER THAN N AWAY — IN EVERY DIMENSION AND EVERY
 * MODE?
 *
 * The original question was narrower and so was this file. A search asked for 500 blocks reported a
 * structure at 508, from two causes with one shape — the thing being FILTERED is not the thing being
 * PRINTED:
 *
 *   STRUCTURES — the radius filter reads a candidate chunk's MIDDLE block, while the coordinate
 *   handed to the player is the structure's locate position (the chunk's min corner plus the
 *   placement's offset). Up to 11.3 blocks apart on the diagonal.
 *
 *   BIOMES — {@code findBiomeHorizontal} walks rings of a SQUARE and applies no distance test at
 *   all, so a patch found on the diagonal of a 500-block scan sits up to 707 blocks away.
 *
 * WHY IT IS BIGGER NOW. In 1.41.3 a player asked for a Nether Fortress within 100 and got one at
 * about 200. Nothing above was broken; three other things were, and this file could not have seen
 * any of them:
 *
 *   1. {@code dimRadius} silently raised a Nether radius to a floor of 208 before the search ever
 *      ran, so the funnel honoured 208 faithfully and the control said 100.
 *   2. Exact mode skipped the real-spawn lookup for a wish with nothing in the overworld, so a
 *      Nether-only search measured from Nether 0,0 rather than from the portal-in point derived
 *      from the player's spawn — up to ~268 Nether blocks apart.
 *   3. This diagnostic read distances back with the regex "(\\d+) blocks from spawn", which is the
 *      OVERWORLD line's wording. A Nether line says "blocks from where you arrive if you build a
 *      portal at your Overworld spawn". So every Nether find was invisible to the check, and the
 *      check would have passed no matter how wrong the Nether was.
 *
 * The third is the one worth remembering: the tool that verifies the fix had the same overworld-only
 * assumption as the code it was verifying. So this now runs EVERY case in EVERY dimension and in
 * BOTH modes, and reads the numbers two independent ways — out of the finished English (what the
 * player reads) and out of the structured {@link SeedCriteria.Find} list (what the app believes).
 * They must agree, and both must be inside the asked radius.
 *
 * {@code gradlew radiusDiag --args="<seeds> <radius>"}
 */
public final class RadiusDiagnostic {

   private record Outcome(boolean hit, long worstReported, long worstVerified, List<String> lines) {}

   /** One row to test, in its own dimension, at its own radius. */
   private record Case(String name, boolean structure, Dim dim, int radius) {}

   public static void main(String[] args) {
      int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 4000;
      int radius = args.length > 1 ? Integer.parseInt(args[1]) : 500;
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      List<SeedCriteria.StructureTarget> catalog = SeedCatalog.structures(ctx);

      int problems = 0;
      problems += checkNoFloors();
      problems += checkNoEnd(catalog, ctx);

      System.out.println();
      System.out.println("Asked radius: " + radius + " blocks, " + seeds + " seeds per case.");
      System.out.println();
      // Two structure rows and two biome rows in the OVERWORLD, because the two original causes are
      // different code paths — and the same again in the NETHER, at a radius well under the floor
      // that used to be applied there, because that is the case the 1.41.3 bug lived in.
      List<Case> cases = List.of(
            new Case("Surface Village", true, Dim.OVERWORLD, radius),
            new Case("Desert Pyramid", true, Dim.OVERWORLD, radius),
            new Case("jungle", false, Dim.OVERWORLD, radius),
            new Case("cherry_grove", false, Dim.OVERWORLD, radius),
            // 100 is the tightest the Fast control offers and less than half the old 208 floor. If
            // anything still widens a Nether search, this is where it shows.
            new Case("Nether Fortress", true, Dim.NETHER, 100),
            new Case("Bastion Remnant", true, Dim.NETHER, 100),
            new Case("Nether Fortress", true, Dim.NETHER, radius),
            new Case("crimson_forest", false, Dim.NETHER, 100),
            new Case("warped_forest", false, Dim.NETHER, radius));

      for (Case c : cases) {
         problems += compare(c, catalog, ctx, seeds);
      }

      System.out.println("================================================");
      System.out.println(problems == 0
            ? "  OK — every dimension honours the radius it was given, in both modes"
            : "  " + problems + " problem(s)");
      System.exit(problems == 0 ? 0 : 1);
   }

   // ── The two things that must be true before any seed is tested ───────────

   /**
    * NOTHING WIDENS A SEARCH ANY MORE. This is the direct check for the 1.41.3 bug: the radius the
    * player picks is the radius the row carries, in every dimension.
    */
   private static int checkNoFloors() {
      System.out.println("=========== DOES ANYTHING SILENTLY WIDEN A RADIUS? ===========");
      int bad = 0;
      for (Dim d : Dim.values()) {
         for (int r : new int[]{48, 64, 100, 200, 500, 1000}) {
            int structure = SeedCriteria.dimRadius(d, r);
            int biome = SeedCriteria.dimBiomeRadius(d, r);
            if (structure != r || biome != r) {
               System.out.println("  " + d + " asked " + r + " -> structure " + structure
                     + ", biome " + biome + "   <-- WIDENED, the control is lying");
               bad++;
            }
         }
      }
      System.out.println(bad == 0 ? "  every dimension, every radius: asked == searched  OK" : "");
      return bad;
   }

   /** THE END IS NOT SEARCHABLE, checked against the live catalog rather than against a memory of
    *  having deleted two rows. See SeedCriteria.Dim. */
   private static int checkNoEnd(List<SeedCriteria.StructureTarget> catalog, WorldgenContext ctx) {
      System.out.println();
      System.out.println("=========== IS ANYTHING STILL SEARCHING THE END? ===========");
      int bad = 0;
      System.out.println("  dimensions the finder has: " + java.util.Arrays.toString(Dim.values()));
      for (Dim d : Dim.values()) {
         if ("END".equals(d.name())) {
            System.out.println("  Dim still has an END value  <-- the removal is not real");
            bad++;
         }
      }
      for (SeedCriteria.StructureTarget t : catalog) {
         String low = t.label.toLowerCase(java.util.Locale.ROOT);
         if (low.contains("end city") || low.contains("end ship") || low.startsWith("end ")) {
            System.out.println("  catalog row \"" + t.label + "\" is still an End row  <-- BUG");
            bad++;
         }
      }
      for (Identifier b : SeedCatalog.biomes(ctx)) {
         String p = b.getPath();
         if (p.startsWith("end_") || p.equals("the_end") || p.equals("small_end_islands")) {
            System.out.println("  biome row \"" + p + "\" is an End biome  <-- BUG");
            bad++;
         }
      }
      // The wish path's vocabulary is the catalog, so the two checks above cover it — but the
      // player's own words are matched separately, and that has to fire.
      if (!WishParser.mentionsEnd("i want an end city with the elytra ship")) {
         System.out.println("  a wish naming an End City is not recognised as an End wish  <-- BUG");
         bad++;
      }
      System.out.println(bad == 0 ? "  no End rows, no End biomes, no End dimension  OK" : "");
      return bad;
   }

   // ── The per-case A/B ─────────────────────────────────────────────────────

   private static int compare(Case c, List<SeedCriteria.StructureTarget> catalog,
                              WorldgenContext ctx, int seeds) {
      System.out.println("=========== " + c.dim() + " · " + c.name() + " within " + c.radius()
            + " ===========");
      int problems = 0;
      for (boolean fast : new boolean[]{false, true}) {
         problems += run(c, catalog, ctx, seeds, fast);
      }
      System.out.println();
      return problems;
   }

   private static int run(Case c, List<SeedCriteria.StructureTarget> catalog, WorldgenContext ctx,
                          int seeds, boolean fast) {
      int hitsBefore = 0;
      int hitsAfter = 0;
      long worstBefore = 0;
      long worstAfter = 0;
      long worstVerifiedAfter = 0;
      int overBefore = 0;
      int dropped = 0;
      int falseDrops = 0;
      int gained = 0;
      int recovered = 0;
      List<String> examples = new ArrayList<>();

      for (long seed = 1; seed <= seeds; seed++) {
         SeedFunnel.EXACT_RADIUS = false;
         Outcome before = once(c, catalog, ctx, seed, fast);
         SeedFunnel.EXACT_RADIUS = true;
         Outcome after = once(c, catalog, ctx, seed, fast);

         if (before.hit()) {
            hitsBefore++;
            worstBefore = Math.max(worstBefore, before.worstReported());
            if (before.worstReported() > c.radius()) {
               overBefore++;
            }
         }
         if (after.hit()) {
            hitsAfter++;
            worstAfter = Math.max(worstAfter, after.worstReported());
            worstVerifiedAfter = Math.max(worstVerifiedAfter, after.worstVerified());
         }
         if (before.hit() && !after.hit()) {
            dropped++;
            if (before.worstReported() <= c.radius()) {
               falseDrops++;
               System.out.println("  FALSE DROP seed " + seed + " reported " + before.worstReported()
                     + " (inside " + c.radius() + ") and is now rejected  <-- BUG IN THE FIX");
            } else if (examples.size() < 2) {
               String last = before.lines().isEmpty() ? ""
                     : "\n        was: " + before.lines().get(before.lines().size() - 1);
               examples.add("  seed " + seed + " was reporting " + before.worstReported()
                     + " for a \"" + c.radius() + " or less\" search" + last);
            }
         }
         if (!before.hit() && after.hit()) {
            // IMPOSSIBLE IN EXACT MODE, LEGITIMATE IN FAST. The fix only removes candidates, but in
            // Fast mode the old code could take an out-of-range candidate FIRST and then lose the
            // whole seed to the spawn re-check; the fix skips it and takes the in-range one behind
            // it. Seed 2441, Bastion within 100, does exactly this (86 blocks), and does it on the
            // 1.42.0 code too. It is only a bug if the gained hit is itself out of range, which the
            // radius checks below already catch — so in Fast mode it is counted, not failed.
            if (fast && after.worstVerified() <= c.radius()) {
               recovered++;
            } else {
               gained++;
            }
         }
      }

      String mode = fast ? "FAST " : "EXACT";
      System.out.printf("  %s  hits before %,d  after %,d   worst reported %d (asked %d)%n",
            mode, hitsBefore, hitsAfter, worstAfter, c.radius());
      int problems = 0;
      if (worstAfter > c.radius()) {
         System.out.println("        REPORTS " + (worstAfter - c.radius()) + " OVER THE ASKED RADIUS"
               + "  <-- BUG");
         problems++;
      }
      // THE SECOND READING, and the one the Nether needed. worstVerified comes from the structured
      // finds rather than from the English, so a dimension whose sentence this file cannot parse
      // still cannot hide a distance here.
      if (worstVerifiedAfter > c.radius()) {
         System.out.println("        the structured finds say " + worstVerifiedAfter
               + ", outside the asked " + c.radius() + "  <-- BUG");
         problems++;
      }
      if (hitsAfter == 0) {
         // Not automatically a bug — a tight Nether radius really is rare, and saying so is now the
         // app's job rather than the search's. But a case that never hits proves nothing, and a
         // check that proves nothing must say so out loud.
         System.out.println("        no hits in " + seeds + " seeds — nothing was actually verified"
               + " here (a tight radius in this dimension is genuinely rare)");
      }
      if (falseDrops > 0) {
         System.out.println("        " + falseDrops + " false drop(s)  <-- BUG");
         problems++;
      }
      if (overBefore > 0 && !examples.isEmpty()) {
         for (String ex : examples) {
            System.out.println(ex);
         }
      }
      if (recovered > 0) {
         System.out.println("        " + recovered + " seed(s) recovered in Fast mode: the fix took an in-range"
               + " structure the old code skipped past (expected, not a bug)");
      }
      if (gained > 0) {
         System.out.println("        " + gained + " seed(s) GAINED a hit  <-- impossible, BUG");
         problems++;
      }
      return problems;
   }

   /** One seed through the real funnel, returning the worst distance any line claims AND the worst
    *  the structured finds carry. */
   private static Outcome once(Case c, List<SeedCriteria.StructureTarget> catalog,
                               WorldgenContext ctx, long seed, boolean fast) {
      SeedCriteria crit = new SeedCriteria();
      if (c.structure()) {
         SeedCriteria.StructureTarget row = null;
         for (SeedCriteria.StructureTarget t : catalog) {
            if (t.label.equals(c.name())) {
               row = t;
            }
         }
         if (row == null) {
            return new Outcome(false, 0, 0, List.of());
         }
         crit.structures.add(row.withRadius(c.radius()));
      } else {
         crit.biomes.add(new SeedCriteria.BiomeTarget(
               ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace(c.name())),
               c.name(), c.dim(), c.radius(), 0, 0));
      }
      List<String> lines = new ArrayList<>();
      List<SeedCriteria.Find> finds = new ArrayList<>();
      // describe = true so the Fast-mode spawn re-check runs and the structured finds are produced.
      // That check is half of what is being tested: in Fast mode it is the thing that holds a Nether
      // find to the asked radius.
      boolean hit = crit.test(seed, ctx, lines, fast, null, true, finds);
      long shown = 0;
      for (SeedCriteria.Find f : finds) {
         shown = Math.max(shown, f.shown());
      }
      return new Outcome(hit, hit ? worstOf(lines) : 0, hit ? shown : 0, List.copyOf(lines));
   }

   /**
    * The largest distance any match line actually claims, whatever dimension wrote it.
    *
    * DIMENSION-BLIND ON PURPOSE. The old pattern was "(\\d+) blocks from spawn", which only the
    * overworld line ever says — so this method silently scored every Nether search as zero. Every
    * line {@code findLine} produces has the shape "· <n> blocks", and Fast mode appends the verified
    * distance as "(<n> from your spawn)", so both are matched and neither depends on the sentence
    * that follows.
    *
    * "right at spawn" carries no number and means ≤48 by construction, which is inside every radius
    * this file tests.
    */
   private static long worstOf(List<String> lines) {
      Pattern shown = Pattern.compile("· (\\d+) blocks");
      Pattern verified = Pattern.compile("\\((\\d+) from your spawn\\)");
      long worst = 0;
      for (String l : lines) {
         Matcher m = shown.matcher(l);
         while (m.find()) {
            worst = Math.max(worst, Long.parseLong(m.group(1)));
         }
         Matcher v = verified.matcher(l);
         while (v.find()) {
            worst = Math.max(worst, Long.parseLong(v.group(1)));
         }
      }
      return worst;
   }

   private RadiusDiagnostic() {
   }
}
