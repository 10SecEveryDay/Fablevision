package com.fablevision.client.seedfinder;

import com.fablevision.VillageDiagnostic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;

/**
 * CAVE BIOMES ARE UNDERGROUND. How often does each one turn up near spawn at each height?
 *
 * The biome search samples one height, Y=64, for every biome. Deep Dark, Lush Caves and Dripstone
 * Caves are placed by the "depth" climate value, which is only high well below the surface, so at Y=64
 * they appear only where the ground is far above it. This measures the share of seeds that have each
 * cave biome within a radius of spawn, per height, so the height the search uses is chosen from data
 * and the difference is on record.
 *
 * It also runs the SHIPPED search on the cave rows (SeedCriteria.test) and fails if a cave row is found
 * in fewer seeds than its own best height would give — i.e. if the search is still looking in the
 * wrong place.
 *
 * {@code gradlew caveDiag --args="[seeds] [radius]"}
 */
public final class CaveBiomeDiagnostic {

   private static final String[] CAVES = {"deep_dark", "lush_caves", "dripstone_caves"};
   private static final int[] HEIGHTS = {64, 32, 0, -24, -48};

   public static void main(String[] args) {
      int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 200;
      int radius = args.length > 1 ? Integer.parseInt(args[1]) : 200;
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      BiomeSource bs = ctx.biomeSource(Dim.OVERWORLD);

      System.out.println("========== CAVE BIOMES WITHIN " + radius + " BLOCKS OF SPAWN, BY HEIGHT (" + seeds + " seeds) ==========");
      StringBuilder head = new StringBuilder(String.format("  %-18s", "biome"));
      for (int y : HEIGHTS) {
         head.append(String.format("%9s", "Y=" + y));
      }
      head.append(String.format("%12s", "search now"));
      System.out.println(head);
      int problems = 0;
      for (String cave : CAVES) {
         ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace(cave));
         int[] hits = new int[HEIGHTS.length];
         for (long seed = 1; seed <= seeds; seed++) {
            Climate.Sampler sampler = ctx.randomState(Dim.OVERWORLD, seed).sampler();
            BlockPos spawn = sampler.findSpawnPosition();
            for (int i = 0; i < HEIGHTS.length; i++) {
               // The search's own question: a 32-block grid, and only a patch inside the CIRCLE counts
               // (the scan is square; the search throws away corner finds past the radius).
               var found = bs.findBiomeHorizontal(spawn.getX(), HEIGHTS[i], spawn.getZ(), radius, 32,
                     h -> h.is(key), RandomSource.create(seed), true, sampler);
               if (found != null && Math.hypot(found.getFirst().getX() - spawn.getX(),
                     found.getFirst().getZ() - spawn.getZ()) <= radius) {
                  hits[i]++;
               }
            }
         }
         // The SHIPPED search, Exact mode, the same radius: what a player's search actually finds.
         int shipped = 0;
         SeedCriteria c = new SeedCriteria();
         c.composed = true;
         c.biomes.add(new SeedCriteria.BiomeTarget(key, SeedCatalogCache.prettify(cave), Dim.OVERWORLD, radius, 0));
         for (long seed = 1; seed <= seeds; seed++) {
            if (c.test(seed, ctx, new java.util.ArrayList<>(), false)) {
               shipped++;
            }
         }
         StringBuilder row = new StringBuilder(String.format("  %-18s", cave));
         int best = 0;
         for (int h : hits) {
            row.append(String.format("%8.0f%%", 100.0 * h / seeds));
            best = Math.max(best, h);
         }
         row.append(String.format("%11.0f%%", 100.0 * shipped / seeds));
         // The search must do at least as well as its best single height (a little slack for the
         // coarser grid it scans with).
         if (shipped < best * 0.8) {
            row.append("   <-- the search looks in the wrong place");
            problems++;
         }
         System.out.println(row);
      }
      System.out.println(problems == 0 ? "OK" : problems + " PROBLEM(S)");
      System.exit(problems == 0 ? 0 : 1);
   }

   private CaveBiomeDiagnostic() {
   }
}
