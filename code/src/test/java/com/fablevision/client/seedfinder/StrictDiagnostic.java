package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fablevision.VillageDiagnostic;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import com.fablevision.client.seedfinder.SeedCriteria.StructureTarget;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Dev-only: how often does the finder's biome test disagree with VANILLA's biome test?
 *
 * The walk tool has been printing a {@code strict=} column for weeks and nobody has said what it
 * measures or whether a "no" matters. It measures this:
 *
 *   The finder's {@code confirm()} samples the biome ONCE, at the structure's locate position, at
 *   a fixed {@code sampleY} (64 for surface things, -40 for an ancient city). That is cheap, and
 *   cheap is the whole reason the funnel can look at thousands of seeds a second.
 *
 *   Vanilla does something different. It runs the structure's own {@code findGenerationPoint}
 *   first, which decides where the structure will REALLY sit — the terrain height under it, the
 *   jigsaw's chosen offset, an ancient city's actual depth — and only then samples the biome, at
 *   that point, and tests it against {@code structure.biomes()}. That is
 *   {@code Structure.isValidBiome}, and it is the last word: if it says no, the chunk generates
 *   with nothing in it.
 *
 * So a {@code strict=no} row is not a tool quirk. It is a coordinate the shipped finder is willing
 * to hand a player, at which the game will build nothing. The only open question is how often, and
 * whether it happens to the row a player is actually sent to (the nearest confirmed hit) or only to
 * the also-rans further down the walk table.
 *
 * This asks exactly that, per structure kind: take the hit the finder would report, assemble it
 * both ways, and count the disagreements.
 *
 * {@code gradlew strictDiag --args="<seeds per structure> [radius] [label substring]"}
 */
public final class StrictDiagnostic {

   public static void main(String[] args) {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      if (!VillageLayout.available()) {
         System.out.println("FAILED: no structure templates.");
         System.exit(2);   // printed FAILED and exited 0 until 1.44.2
      }

      int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 30;
      int reach = args.length > 1 ? Integer.parseInt(args[1]) : 1200;
      String only = args.length > 2 ? args[2].replace('_', ' ').toLowerCase(java.util.Locale.ROOT) : "";
      // "why" asks the follow-up question: is the disagreement about the HEIGHT the finder samples
      // at, or about the horizontal position? Only the first has a cheap fix.
      boolean why = args.length > 3 && "why".equalsIgnoreCase(args[3]);

      System.out.println("========== DOES VANILLA'S BIOME TEST AGREE WITH THE FINDER'S? ==========");
      System.out.println("  " + seeds + " seeds per structure, nearest confirmed hit within " + reach + " blocks.");
      System.out.println("  \"confirmed\"  = the finder said yes and would print these coordinates.");
      System.out.println("  \"vanilla no\" = Structure.isValidBiome rejects it at the REAL generation");
      System.out.println("                 point, so the game builds nothing there. A false positive.");
      System.out.println();

      // One row per structure KIND. The catalog holds several rows per kind (Igloo, Igloo with
      // Basement) and they share the same placement and the same biome test, so measuring each
      // would just repeat the same number under different names. The building token is dropped for
      // the same reason: this is a question about biomes, not contents.
      List<StructureTarget> kinds = catalogKinds(ctx, only);

      if ("cost".equalsIgnoreCase(only)) {
         cost(ctx, catalogKinds(ctx, ""), seeds, reach);
         return;
      }

      System.out.println("   " + pad("structure", 22) + pad("confirmed", 12) + pad("vanilla no", 12)
            + pad("rate", 8) + pad("finder now", 12) + "what a \"no\" means here");
      int totalConfirmed = 0;
      int totalBad = 0;
      int totalCaught = 0;
      List<String> worrying = new ArrayList<>();
      for (StructureTarget kind : kinds) {
         StructureTarget target = kind.withRadius(reach);
         int confirmed = 0;
         int bad = 0;
         int caught = 0;
         int noPieces = 0;
         for (long seed = 1; seed <= seeds; seed++) {
            Placed.Spot spot = Placed.nearest(ctx, target, seed, target.dim, reach);
            if (spot == null) {
               continue;   // this seed simply has none in reach; not a disagreement
            }
            confirmed++;
            RandomState full = ctx.fullRandomState(target.dim, seed);
            boolean loose = generates(ctx, seed, spot, full, target.dim, h -> true);
            boolean strict = generates(ctx, seed, spot, full, target.dim,
                  VillageLayout.ownBiomes(spot.winner()));
            if (!loose) {
               // The structure declines for a reason that is not the biome — no island high
               // enough for an End City, no ground for a mansion. A different problem, already
               // handled by the existence tokens, and counted separately so it cannot be mistaken
               // for this one.
               noPieces++;
            } else if (!strict) {
               bad++;
               if (why) {
                  explain(ctx, target, spot, seed);
               }
            }
            // THE SHIPPED CODE'S OWN VERDICT, not a re-implementation of it. If the strict fix is
            // doing what it claims, this rejects exactly the hits vanilla rejects — and if the two
            // columns ever drift apart, the fix is not the thing that was measured.
            if (!target.layoutMatches(seed, spot.chunk(), spot.winner(), ctx)) {
               caught++;
            }
         }
         totalConfirmed += confirmed;
         totalBad += bad;
         totalCaught += caught;
         int pct = confirmed == 0 ? 0 : bad * 100 / confirmed;
         String verdict = bad == 0
               ? (noPieces > 0 ? noPieces + " declined for non-biome reasons" : "agrees every time")
               : caught >= bad ? "caught by the finder now" : "STILL SENDS PLAYERS TO NOTHING";
         if (bad > caught) {
            worrying.add(kind.label + " " + (bad - caught) + "/" + confirmed);
         }
         System.out.println("   " + pad(kind.label, 22) + pad(String.valueOf(confirmed), 12)
               + pad(String.valueOf(bad), 12) + pad(pct + "%", 8)
               + pad("rejects " + caught, 12) + verdict);
      }

      System.out.println();
      System.out.println("  confirmed hits examined : " + totalConfirmed);
      System.out.println("  vanilla disagreed on    : " + totalBad
            + "  (" + (totalConfirmed == 0 ? 0 : totalBad * 100 / totalConfirmed) + "%)");
      System.out.println("  the finder now rejects  : " + totalCaught
            + "   (STRICT_BIOME=" + SeedFunnel.STRICT_BIOME + ")");
      System.out.println(worrying.isEmpty()
            ? "  Every hit vanilla would have rejected is now rejected by the shipped funnel too."
            : "  Still slipping through: " + String.join(", ", worrying));
      System.out.println("=====================================================================");
      // "STILL SENDS PLAYERS TO NOTHING" used to be printed and the run exited 0 (fixed 1.44.2). A
      // check that examined nothing fails too — the release suite must not record a vacuous pass.
      System.exit(worrying.isEmpty() && totalConfirmed > 0 ? 0 : 1);
   }

   /** One row per structure KIND. The catalog holds several rows per kind (Igloo, Igloo with
    *  Basement) sharing the same placement and the same biome test, so measuring each would
    *  repeat one number under different names. The building token is dropped for the same
    *  reason: this is a question about biomes, not contents. */
   private static List<StructureTarget> catalogKinds(WorldgenContext ctx, String only) {
      List<StructureTarget> kinds = new ArrayList<>();
      Set<String> seenKinds = new LinkedHashSet<>();
      for (StructureTarget t : SeedCatalog.structures(ctx)) {
         if (t.special != SeedCriteria.Special.NORMAL || t.wanted.isEmpty()) {
            continue;
         }
         List<String> ids = t.wanted.stream().map(id -> id.getPath()).sorted().toList();
         if (!seenKinds.add(t.dim + String.join(",", ids))) {
            continue;
         }
         if (!only.isEmpty() && !t.label.toLowerCase(java.util.Locale.ROOT).contains(only)) {
            continue;
         }
         kinds.add(t.withBuilding(null));
      }
      return kinds;
   }

   /**
    * WHAT WOULD FIXING IT COST?
    *
    * The exact test is {@code Structure.findValidGenerationPoint}: work out where the structure
    * really starts, then sample the biome there. Whether that is affordable in the funnel comes
    * down to one question nobody has measured — does finding the generation point of a JIGSAW
    * structure do the piece placement, or only decide the start position and hand the placement
    * back as a lambda for {@code generate} to run later?
    *
    * If it is the second, the exact biome test is nearly free and every row can have it. If it is
    * the first, a village costs about a second and only the rows that already assemble can afford
    * it. Timing both against each other answers it without reading a decompiler.
    */
   private static void cost(WorldgenContext ctx, List<StructureTarget> kinds, int seeds, int reach) {
      System.out.println("   " + pad("structure", 22) + pad("hits", 8) + pad("generate()", 16)
            + pad("findValidGenPoint()", 22) + "cheaper by");
      for (StructureTarget kind : kinds) {
         StructureTarget target = kind.withRadius(reach);
         List<Placed.Spot> spots = new ArrayList<>();
         for (long seed = 1; seed <= seeds; seed++) {
            Placed.Spot spot = Placed.nearest(ctx, target, seed, target.dim, reach);
            if (spot != null) {
               spots.add(spot);
            }
         }
         if (spots.isEmpty()) {
            continue;
         }
         long full = 0;
         long quick = 0;
         int agree = 0;
         for (int i = 0; i < spots.size(); i++) {
            Placed.Spot spot = spots.get(i);
            long seed = i + 1L;
            RandomState rs = ctx.fullRandomState(target.dim, seed);
            long t0 = System.nanoTime();
            boolean a = generates(ctx, seed, spot, rs, target.dim,
                  VillageLayout.ownBiomes(spot.winner()));
            long t1 = System.nanoTime();
            boolean b = validPoint(ctx, seed, spot, rs, target.dim);
            long t2 = System.nanoTime();
            full += t1 - t0;
            quick += t2 - t1;
            if (a == b) {
               agree++;
            }
         }
         int n = spots.size();
         System.out.println("   " + pad(kind.label, 22) + pad(String.valueOf(n), 8)
               + pad(full / n / 1000 + " us", 16) + pad(quick / n / 1000 + " us", 22)
               + (quick == 0 ? "inf" : (full / Math.max(1, quick)) + "x")
               + (agree == n ? "" : "   !!! DISAGREES on " + (n - agree) + " — not equivalent"));
      }
      System.out.println();
      System.out.println("  Same verdict from both columns means findValidGenerationPoint is the");
      System.out.println("  cheap form of the exact test and can go in the funnel; a disagreement");
      System.out.println("  means it is answering a different question and must not.");
      System.out.println("=====================================================================");
   }

   /** The cheap half of vanilla's test: where does the structure really start, and is the biome
    *  there allowed? No pieces are placed if the structure defers them. */
   private static boolean validPoint(WorldgenContext ctx, long seed, Placed.Spot spot, RandomState full,
                                     Dim dim) {
      try {
         WorldgenContext.Scaffold scaffold = ctx.scaffold(dim);
         Holder<Structure> structure = spot.winner();
         Structure.GenerationContext gc = new Structure.GenerationContext(
               (RegistryAccess) ctx.source, scaffold.generator(), ctx.biomeSource(dim), full,
               VillageLayout.templates(), seed, spot.chunk(), scaffold.height(),
               VillageLayout.ownBiomes(structure));
         return structure.value().findValidGenerationPoint(gc).isPresent();
      } catch (Throwable t) {
         System.out.println("   findValidGenerationPoint threw for " + spot.chunk() + ": " + t);
         return false;
      }
   }

   /**
    * WHY did vanilla say no here — the wrong height, or the wrong place?
    *
    * The finder samples one biome, at the locate position, at the row's fixed {@code sampleY}.
    * Vanilla samples at the structure's real generation point, which is a different y (terrain
    * height, or an ancient city's chosen depth) and can be a different x/z as well. Only the first
    * of those has a cheap fix — moving {@code sampleY} — so the two have to be told apart before
    * anything is changed. This walks the column above and below the locate position and prints
    * which heights, if any, would have answered yes.
    */
   private static void explain(WorldgenContext ctx, StructureTarget target, Placed.Spot spot, long seed) {
      RandomState rs = ctx.randomState(target.dim, seed);
      var biomes = ctx.biomeSource(target.dim);
      var sampler = rs.sampler();
      var allowed = spot.winner().value().biomes();
      StringBuilder yes = new StringBuilder();
      StringBuilder line = new StringBuilder();
      for (int y = -64; y <= 200; y += 8) {
         Holder<Biome> here = biomes.getNoiseBiome(
               net.minecraft.core.QuartPos.fromBlock(spot.pos().getX()),
               net.minecraft.core.QuartPos.fromBlock(y),
               net.minecraft.core.QuartPos.fromBlock(spot.pos().getZ()), sampler);
         if (allowed.contains(here)) {
            yes.append(yes.length() > 0 ? "," : "").append(y);
         }
         if (y == target.sampleY || (y <= target.sampleY && y + 8 > target.sampleY)) {
            line.append(here.unwrapKey().map(k -> k.identifier().getPath()).orElse("?"));
         }
      }
      System.out.println("      seed " + seed + " @ " + spot.pos().getX() + "," + spot.pos().getZ()
            + "  sampleY=" + target.sampleY + " reads " + line
            + "  |  heights in the column that WOULD pass: "
            + (yes.length() == 0 ? "NONE — this is the wrong PLACE, not the wrong height" : yes));
   }

   /** Does the structure really assemble here under this biome rule? Uses {@code isValid} rather
    *  than the piece list, because a Nether Fortress is built from CODE and has no templates at
    *  all — an empty template list there means "no names", not "no structure". */
   private static boolean generates(WorldgenContext ctx, long seed, Placed.Spot spot, RandomState full,
                                    Dim dim, java.util.function.Predicate<Holder<Biome>> validBiome) {
      try {
         WorldgenContext.Scaffold scaffold = ctx.scaffold(dim);
         Holder<Structure> structure = spot.winner();
         StructureStart start = structure.value().generate(
               structure,
               dim == Dim.NETHER ? Level.NETHER : Level.OVERWORLD,
               (RegistryAccess) ctx.source, scaffold.generator(), ctx.biomeSource(dim), full,
               VillageLayout.templates(), seed, spot.chunk(), 0, scaffold.height(), validBiome);
         return start != null && start.isValid();
      } catch (Throwable t) {
         System.out.println("   assembly threw for " + spot.chunk() + ": " + t);
         return false;
      }
   }

   private static String pad(String s, int width) {
      StringBuilder sb = new StringBuilder(s);
      while (sb.length() < width) {
         sb.append(' ');
      }
      return sb.toString();
   }

   private StrictDiagnostic() {
   }
}
