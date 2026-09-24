package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.fablevision.VillageDiagnostic;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import com.fablevision.client.seedfinder.SeedCriteria.StructureTarget;

import com.mojang.datafixers.util.Pair;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Dev-only: HOW BIG ARE BIOMES, and HOW CLOSE IS "NEXT TO"?
 *
 * Two questions that have to be answered with numbers before either feature can be built, because
 * both of them are thresholds and a guessed threshold is a row that lies.
 *
 * SIZE. The catalog already has one size rule — the jungle row's {@code minSpan >= 384} — and it
 * was picked by hand for one biome. Generalising that to "small / medium / large" needs the real
 * distribution PER BIOME, because biomes are not the same size as each other by any stretch: a
 * pale garden and a deep ocean measured against one shared threshold would put every pale garden
 * in "small" and every ocean in "large", which tells a player nothing. So the tiers here are
 * derived per biome, from that biome's own measured spans — a "large pale garden" means large
 * FOR A PALE GARDEN.
 *
 * ADJACENCY. "Next to" needs a distance, and it also needs a decision the design has been quiet
 * about: the finder locates each biome by searching outward FROM SPAWN, so it finds the patch
 * nearest the player, not the patch nearest the other thing. If those two are usually the same
 * patch, the cheap check is fine. If they are not, "pale garden next to dark oak" would reject
 * seeds that really do have the pair, just not the pair it happened to look at. This measures the
 * gap rather than assuming it away.
 *
 * {@code gradlew biomeShape --args="<seeds> [size|adjacent|all]"}
 */
public final class BiomeShapeDiagnostic {

   /** Pairs worth measuring, taken from the wishes that prompted the feature. */
   private record Pair2(String a, String b, boolean bIsStructure) {}

   private static final List<Pair2> PAIRS = List.of(
         new Pair2("pale_garden", "dark_forest", false),
         new Pair2("cherry_grove", "Surface Village", true),
         new Pair2("cherry_grove", "Ruined Portal", true),
         new Pair2("plains", "forest", false),
         new Pair2("jungle", "desert", false),
         new Pair2("swamp", "Surface Village", true),
         new Pair2("ice_spikes", "snowy_plains", false));

   public static void main(String[] args) {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);

      int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 60;
      String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "all";

      if ("cost".equals(mode)) {
         cost(ctx, seeds);
         return;
      }
      if ("closest".equals(mode)) {
         closest(ctx, seeds);
         return;
      }
      if (!"adjacent".equals(mode)) {
         sizes(ctx, seeds);
      }
      if (!"size".equals(mode)) {
         adjacency(ctx, reg, seeds);
      }
   }

   /**
    * What the two new checks would cost per seed that reaches them.
    *
    * Three things are timed against each other, because the feature's price is the difference
    * between them and not any one in isolation: the biome search the finder ALREADY runs
    * (findClosest=false, stops early), the same search asked for the nearest match instead
    * (findClosest=true, cannot stop early — this is what adjacency needs), and the span
    * measurement a size tier would add.
    */
   /**
    * What actually changes when the biome search asks for the NEAREST patch instead of a random one.
    *
    * Written because the benchmark showed the two disagreeing on which SEEDS match, not merely on
    * which patch they report, and "nearest finds fewer" is not something a nearest-first search
    * should be able to do — anything the random pick can find, the nearest pick should also find.
    * Rather than reason about vanilla's loop from memory, this counts the four outcomes directly:
    * both found, neither found, and each one finding where the other did not. If the last two
    * columns are both non-zero then the two are sampling DIFFERENT grids rather than one being
    * strictly better, and that is a fact worth stating plainly instead of guessing at.
    *
    * The distance columns are the payoff either way: they say how much closer the reported patch is.
    */
   private static void closest(WorldgenContext ctx, int seeds) {
      System.out.println("========== RANDOM PATCH vs NEAREST PATCH ==========");
      System.out.println("  radius 1000, step 32, measured from 0,0 — the shipped biome search");
      System.out.println();
      System.out.println("   " + pad("biome", 26) + pad("both", 7) + pad("neither", 9)
            + pad("only rnd", 10) + pad("only near", 11) + pad("median rnd", 12) + "median near");
      BiomeSource bs = ctx.biomeSource(Dim.OVERWORLD);
      String[] probes = {"plains", "forest", "jungle", "cherry_grove", "pale_garden", "desert", "swamp"};
      for (String path : probes) {
         ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME,
               Identifier.withDefaultNamespace(path));
         int both = 0;
         int neither = 0;
         int onlyRandom = 0;
         int onlyNearest = 0;
         List<Integer> dRandom = new ArrayList<>();
         List<Integer> dNearest = new ArrayList<>();
         for (long seed = 1; seed <= seeds; seed++) {
            Climate.Sampler sampler = ctx.randomState(Dim.OVERWORLD, seed).sampler();
            BlockPos r = find(bs, sampler, key, 0, 0, 1000, seed, false);
            BlockPos n = find(bs, sampler, key, 0, 0, 1000, seed, true);
            if (r != null && n != null) {
               both++;
               dRandom.add((int) Math.round(Math.hypot(r.getX(), r.getZ())));
               dNearest.add((int) Math.round(Math.hypot(n.getX(), n.getZ())));
            } else if (r == null && n == null) {
               neither++;
            } else if (r != null) {
               onlyRandom++;
            } else {
               onlyNearest++;
            }
         }
         System.out.println("   " + pad(path, 26) + pad(String.valueOf(both), 7)
               + pad(String.valueOf(neither), 9) + pad(String.valueOf(onlyRandom), 10)
               + pad(String.valueOf(onlyNearest), 11)
               + pad(median(dRandom) + " blocks", 12) + median(dNearest) + " blocks");
      }
      System.out.println();
      System.out.println("  'only rnd' and 'only near' both non-zero = two different sample grids,");
      System.out.println("  not one strictly finding more. The median columns are the real gain.");
      System.out.println("==================================================");
   }

   private static int median(List<Integer> v) {
      if (v.isEmpty()) {
         return -1;
      }
      List<Integer> s = new ArrayList<>(v);
      java.util.Collections.sort(s);
      return s.get(s.size() / 2);
   }

   private static void cost(WorldgenContext ctx, int seeds) {
      System.out.println("========== WHAT THE TWO NEW CHECKS COST ==========");
      System.out.println("  per call, averaged over seeds where the biome was found at all");
      System.out.println();
      System.out.println("   " + pad("biome", 26) + pad("find (today)", 15)
            + pad("find CLOSEST", 15) + pad("span", 10) + "adjacency adds");
      BiomeSource bs = ctx.biomeSource(Dim.OVERWORLD);
      String[] probes = {"plains", "forest", "cherry_grove", "pale_garden", "jungle"};
      for (String path : probes) {
         ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME,
               Identifier.withDefaultNamespace(path));
         long tFind = 0;
         long tClosest = 0;
         long tSpan = 0;
         int n = 0;
         for (long seed = 1; seed <= seeds; seed++) {
            RandomState rs = ctx.randomState(Dim.OVERWORLD, seed);
            Climate.Sampler sampler = rs.sampler();
            // WARM-UP, and the first version of this was wrong without it. The first biome query
            // on a fresh seed builds that seed's noise state, so whichever call went first
            // absorbed several milliseconds that had nothing to do with it — which made the
            // EXTRA search look cheaper than the one already being paid for. Throwing one call
            // away first makes the three columns comparable.
            BlockPos at = find(bs, sampler, key, 0, 0, 2000, seed, false);
            if (at == null) {
               continue;
            }
            long t0 = System.nanoTime();
            find(bs, sampler, key, 0, 0, 2000, seed, false);
            long t1 = System.nanoTime();
            find(bs, sampler, key, 0, 0, 2000, seed, true);
            long t2 = System.nanoTime();
            SeedCriteria.biomeSpan(bs, sampler, key, at);
            long t3 = System.nanoTime();
            tFind += t1 - t0;
            tClosest += t2 - t1;
            tSpan += t3 - t2;
            n++;
         }
         if (n == 0) {
            continue;
         }
         System.out.println("   " + pad(path, 26) + pad(tFind / n / 1000 + " us", 15)
               + pad(tClosest / n / 1000 + " us", 15) + pad(tSpan / n / 1000 + " us", 10)
               + "+" + (tClosest / n / 1000) + " us");
      }
      System.out.println();
      System.out.println("  Adjacency adds a findClosest search; a size tier adds a span measure.");
      System.out.println("  Both only run on seeds that already passed everything else, so multiply");
      System.out.println("  by the HIT rate, not by the seeds scanned.");
      System.out.println("=================================================");
   }

   // ── SIZE ─────────────────────────────────────────────────────────────────

   /**
    * Per biome: how often it is anywhere near spawn at all, and the spread of patch sizes when it
    * is. The tier cut points are the 33rd and 67th percentiles of that biome's own spans, so each
    * tier is roughly a third of the biomes you would actually run into — which is the only reading
    * of "small / medium / large" that stays true for both a pale garden and an ocean.
    */
   private static void sizes(WorldgenContext ctx, int seeds) {
      System.out.println("========== BIOME SIZE: WHAT DOES EACH ONE ACTUALLY MEASURE ==========");
      System.out.println("  " + seeds + " seeds per biome, searched within 2000 blocks of 0,0.");
      System.out.println("  span = the shipped SeedCriteria.biomeSpan measurement (min of x and z),");
      System.out.println("  so these are the numbers a size filter would really be compared against.");
      System.out.println();
      System.out.println("   " + pad("biome", 26) + pad("found", 10) + pad("min", 7) + pad("p33", 7)
            + pad("median", 8) + pad("p67", 7) + pad("max", 7) + "-> small <= / large >=");

      BiomeSource bs = ctx.biomeSource(Dim.OVERWORLD);
      for (Identifier id : SeedCatalog.biomes(ctx)) {
         if (SeedCatalog.biomeDim(id) != Dim.OVERWORLD) {
            continue;
         }
         ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, id);
         List<Integer> spans = new ArrayList<>();
         for (long seed = 1; seed <= seeds; seed++) {
            RandomState rs = ctx.randomState(Dim.OVERWORLD, seed);
            Climate.Sampler sampler = rs.sampler();
            // false, matching the shipped search: the span measured has to be the span of the
            // patch a real search would have landed on, not of a nicer one nearby.
            BlockPos at = find(bs, sampler, key, 0, 0, 2000, seed, false);
            if (at == null) {
               continue;
            }
            int[] span = SeedCriteria.biomeSpan(bs, sampler, key, at);
            spans.add(Math.min(span[0], span[1]));
         }
         if (spans.isEmpty()) {
            System.out.println("   " + pad(id.getPath(), 26) + pad("0/" + seeds, 10)
                  + "never within 2000 of spawn in this sample");
            continue;
         }
         Collections.sort(spans);
         int p33 = spans.get(spans.size() * 33 / 100);
         int p67 = spans.get(Math.min(spans.size() - 1, spans.size() * 67 / 100));
         System.out.println("   " + pad(id.getPath(), 26) + pad(spans.size() + "/" + seeds, 10)
               + pad(String.valueOf(spans.get(0)), 7) + pad(String.valueOf(p33), 7)
               + pad(String.valueOf(spans.get(spans.size() / 2)), 8) + pad(String.valueOf(p67), 7)
               + pad(String.valueOf(spans.get(spans.size() - 1)), 7)
               + p33 + " / " + p67);
      }
      System.out.println();
      System.out.println("  A tier is only worth shipping where the three bands are actually apart.");
      System.out.println("  Where p33 and p67 are close, that biome has one size and 'big' means nothing.");
      System.out.println("=====================================================================");
   }

   // ── ADJACENCY ────────────────────────────────────────────────────────────

   /**
    * For each pair: how far apart the two really are, measured two ways.
    *
    * FROM-SPAWN is what the finder can do cheaply today — locate each thing independently by
    * searching out from spawn, then measure between them. NEAREST-TO-A searches for B starting at
    * A's position instead, which is the true answer to "is there a B next to this A".
    *
    * The gap between those two columns is the whole design decision. If they agree, adjacency is a
    * free post-check on positions the search already has. If from-spawn is routinely much larger,
    * then a cheap check would reject seeds that genuinely satisfy the wish, and the feature needs
    * the second search — which costs a real biome scan per candidate.
    */
   private static void adjacency(WorldgenContext ctx, RegistryAccess.Frozen reg, int seeds) {
      System.out.println();
      System.out.println("========== ADJACENCY: HOW FAR APART IS \"NEXT TO\"? ==========");
      System.out.println("  " + seeds + " seeds per pair. Both members must be within 2000 of 0,0.");
      System.out.println();
      System.out.println("   " + pad("pair", 34) + pad("both near", 11)
            + pad("FROM-SPAWN med", 16) + pad("NEAREST-TO-A med", 18) + "agree?");

      BiomeSource bs = ctx.biomeSource(Dim.OVERWORLD);
      List<StructureTarget> catalog = SeedCatalog.structures(ctx);
      for (Pair2 p : PAIRS) {
         ResourceKey<Biome> a = ResourceKey.create(Registries.BIOME,
               Identifier.withDefaultNamespace(p.a()));
         ResourceKey<Biome> b = p.bIsStructure() ? null
               : ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace(p.b()));
         StructureTarget st = p.bIsStructure() ? catalog.stream()
               .filter(t -> t.label.equals(p.b())).findFirst().orElse(null) : null;
         if (p.bIsStructure() && st == null) {
            System.out.println("   " + pad(p.a() + " + " + p.b(), 34) + "no such catalog row");
            continue;
         }

         List<Integer> fromSpawn = new ArrayList<>();
         List<Integer> nearestToA = new ArrayList<>();
         int both = 0;
         for (long seed = 1; seed <= seeds; seed++) {
            RandomState rs = ctx.randomState(Dim.OVERWORLD, seed);
            Climate.Sampler sampler = rs.sampler();
            // A is located exactly as the shipped search locates it — random patch in radius —
            // because that is the A a real search would be holding when adjacency is checked.
            BlockPos posA = find(bs, sampler, a, 0, 0, 2000, seed, false);
            if (posA == null) {
               continue;
            }
            BlockPos posB;
            BlockPos bestB;
            if (st != null) {
               // Structures: every one that really generates within reach, so "nearest to A" is a
               // real minimum over the same set rather than a second search with its own rules.
               List<Placed.Spot> spots = Placed.near(ctx, st.withRadius(2000), seed, Dim.OVERWORLD, 2000);
               if (spots.isEmpty()) {
                  continue;
               }
               posB = spots.get(0).pos();
               bestB = spots.stream()
                     .min((x, y) -> Long.compare(d2(posA, x.pos()), d2(posA, y.pos())))
                     .map(Placed.Spot::pos).orElse(posB);
            } else {
               posB = find(bs, sampler, b, 0, 0, 2000, seed, false);
               if (posB == null) {
                  continue;
               }
               bestB = find(bs, sampler, b, posA.getX(), posA.getZ(), 2000, seed, true);
               if (bestB == null) {
                  bestB = posB;
               }
            }
            both++;
            fromSpawn.add((int) Math.round(Math.sqrt(d2(posA, posB))));
            nearestToA.add((int) Math.round(Math.sqrt(d2(posA, bestB))));
         }
         if (both == 0) {
            System.out.println("   " + pad(p.a() + " + " + p.b(), 34) + pad("0/" + seeds, 11)
                  + "never both near spawn in this sample");
            continue;
         }
         Collections.sort(fromSpawn);
         Collections.sort(nearestToA);
         int medSpawn = fromSpawn.get(fromSpawn.size() / 2);
         int medNear = nearestToA.get(nearestToA.size() / 2);
         int p25 = nearestToA.get(nearestToA.size() / 4);
         int p75 = nearestToA.get(Math.min(nearestToA.size() - 1, nearestToA.size() * 3 / 4));
         System.out.println("   " + pad(p.a() + " + " + p.b(), 34) + pad(both + "/" + seeds, 11)
               + pad(String.valueOf(medSpawn), 16)
               + pad(p25 + "/" + medNear + "/" + p75, 18)
               + (medSpawn > medNear * 2 ? "cheap check WRONG — " + (medSpawn / Math.max(1, medNear))
                           + "x too far" : "cheap check ok"));
      }
      System.out.println();
      System.out.println("  p25/p50/p75 of the NEAREST-TO-A column is what a default \"next to\"");
      System.out.println("  distance should be built from — it is the real spread of how close");
      System.out.println("  these things sit when they are neighbours at all.");
      System.out.println("=====================================================================");
   }

   /**
    * {@code closest} is the argument the shipped finder passes as FALSE, and the first run of this
    * diagnostic copied it and produced nonsense — a median of 1590 blocks from a plains patch to
    * the nearest forest, which anyone who has played the game knows is wrong.
    *
    * The reason is that vanilla's last boolean is {@code findClosest}: with false it returns a
    * RANDOM matching position inside the radius, not the nearest one. That is defensible for
    * "is there a jungle within 500 blocks" — any jungle answers it — and it is not defensible for
    * measuring how far apart two things are, which is a question about a specific pair.
    */
   private static BlockPos find(BiomeSource bs, Climate.Sampler sampler, ResourceKey<Biome> want,
                                int x, int z, int radius, long seed, boolean closest) {
      // THE SEARCH'S OWN LOCATOR since 1.44.2: at every height the biome occupies. This used Y=64 for
      // everything, which is how Deep Dark's "5% of seeds" got into BiomeShape — true at the surface,
      // and the surface is not where Deep Dark is.
      return SeedCriteria.locateBiome(bs, sampler, want, x, z, radius, 32, seed, closest);
   }

   private static long d2(BlockPos a, BlockPos b) {
      long dx = a.getX() - b.getX();
      long dz = a.getZ() - b.getZ();
      return dx * dx + dz * dz;
   }

   private static String pad(String s, int width) {
      StringBuilder sb = new StringBuilder(s);
      while (sb.length() < width) {
         sb.append(' ');
      }
      return sb.toString();
   }

   private BiomeShapeDiagnostic() {
   }
}
