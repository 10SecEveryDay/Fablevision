package com.fablevision.client.seedfinder;

import com.fablevision.VillageDiagnostic;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import net.minecraft.SharedConstants;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityBufferPool;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * 26.3 CHANGED HOW CLIMATE IS SAMPLED, so the assumption the 1.45.0 port rests on is measured, not trusted.
 *
 * The game fills a chunk's biomes by sampling the six climate functions over the whole chunk at once
 * (MultiNoiseBiomeSource.createResolverForChunk, called from ChunkGenerator.doCreateBiomes: a VOLUME
 * sample, quart by quart, through a caching context). Its structure step asks single POINTS through a
 * caching context, which can hand back a value an earlier volume sample left in its cache. The finder and
 * the map ask single points with no cache at all (WorldgenContext.climate). All of that is one answer only
 * if a volume sample and a point sample of the same quart are the same number. 26.3 evaluates density
 * functions in single precision and compiles separate code for the two paths, so that is not a given.
 *
 * This compares them bit for bit: every quart of whole chunks, Overworld and Nether, all six climate
 * values — volume exactly as doCreateBiomes builds it, against point exactly as the finder builds it. Any
 * difference fails the run, and then WorldgenContext.climate is the wrong design and must change.
 *
 * {@code gradlew samplerDiag --args="[seeds per dimension] [chunks per seed]"}
 */
public final class SamplerDiagnostic {

   private static final String[] NAMES = {"temperature", "humidity", "continentalness", "erosion", "depth", "weirdness"};

   public static void main(String[] args) {
      int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 6;
      int chunksPerSeed = args.length > 1 ? Integer.parseInt(args[1]) : 24;
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);

      System.out.println("=========== CLIMATE: CHUNK VOLUME vs SINGLE POINT (" + seeds + " seeds x "
            + chunksPerSeed + " chunks per dimension) ===========");
      long compared = 0;
      long differ = 0;
      long[] differBy = new long[6];
      float worst = 0;
      for (Dim dim : Dim.values()) {
         int minY = dim == Dim.NETHER ? 0 : -64;
         int height = dim == Dim.NETHER ? 128 : 384;
         java.util.Random rng = new java.util.Random(7919L * (dim.ordinal() + 1));
         long dimCompared = 0;
         long dimDiffer = 0;
         for (int s = 0; s < seeds; s++) {
            long seed = rng.nextLong();
            RandomState rs = ctx.newFullRandomState(dim, seed);
            Climate.Sampler point = WorldgenContext.climate(rs);
            DensitySampler.Bound[] pointFns = bound(point);
            for (int c = 0; c < chunksPerSeed; c++) {
               int minX = (rng.nextInt(2501) - 1250) * 16;   // anywhere within about 20,000 blocks
               int minZ = (rng.nextInt(2501) - 1250) * 16;
               // VOLUME, exactly as ChunkGenerator.doCreateBiomes + MultiNoiseBiomeSource.createResolverForChunk.
               DensityBufferPool pool = rs.acquireDensityBufferPool();
               try {
                  Climate.Sampler chunk = rs.createClimateSampler(
                        SamplerContext.builder().enableCaches().useBufferArena(pool).build());
                  DensitySampler.Bound[] volumeFns = bound(chunk);
                  DensityVolume volume = new DensityVolume(QuartPos.fromBlock(16), QuartPos.fromBlock(height),
                        QuartPos.fromBlock(16), minX, minY, minZ, 4, 4, 4);
                  DensityBuffer[] buffers = new DensityBuffer[6];
                  for (int k = 0; k < 6; k++) {
                     buffers[k] = DensityBuffer.createUnpooled(volume.size());
                     volumeFns[k].sampleVolume(buffers[k], volume);
                  }
                  for (int x = 0; x < volume.sizeX(); x++) {
                     for (int z = 0; z < volume.sizeZ(); z++) {
                        for (int y = 0; y < volume.sizeY(); y++) {
                           int index = volume.indexUnchecked(x, y, z);
                           boolean any = false;
                           for (int k = 0; k < 6; k++) {
                              float v = buffers[k].get(index);
                              float p = pointFns[k].sampleValue(volume.blockX(x), volume.blockY(y), volume.blockZ(z));
                              if (Float.floatToIntBits(v) != Float.floatToIntBits(p)) {
                                 any = true;
                                 differBy[k]++;
                                 worst = Math.max(worst, Math.abs(v - p));
                                 if (differ < 5) {
                                    System.out.println("  DIFFER " + dim + " seed " + seed + " block " + volume.blockX(x)
                                          + "," + volume.blockY(y) + "," + volume.blockZ(z) + " " + NAMES[k]
                                          + ": volume " + v + " point " + p);
                                 }
                              }
                           }
                           compared++;
                           dimCompared++;
                           if (any) {
                              differ++;
                              dimDiffer++;
                           }
                        }
                     }
                  }
               } finally {
                  rs.releaseDensityBufferPool(pool);
               }
            }
         }
         System.out.println("  " + dim + ": " + dimCompared + " quarts compared, " + dimDiffer + " differ");
         // THE CONTROL: the same comparison against a DIFFERENT seed's points must see differences. If it
         // does not, the comparison above is blind and its "0 differ" means nothing.
         if (!controlSeesDifference(ctx, dim, minY, height)) {
            System.out.println("!!! CONTROL FAILED in " + dim + ": a different seed compared equal — this check cannot fail.");
            System.exit(1);
         }
         System.out.println("  " + dim + " control: a different seed's points differ, as they must");
      }
      System.out.println();
      if (differ == 0) {
         System.out.println("OK — " + compared + " quarts, all six climate values identical bit for bit. A chunk's"
               + " biome and a single lookup of the same quart cannot disagree.");
      } else {
         StringBuilder by = new StringBuilder();
         for (int k = 0; k < 6; k++) {
            if (differBy[k] > 0) {
               by.append(' ').append(NAMES[k]).append('=').append(differBy[k]);
            }
         }
         System.out.println("!!! " + differ + " of " + compared + " quarts DIFFER (" + by.toString().trim()
               + "; worst " + worst + "). The point lookups the finder and map use are not the game's answer.");
      }
      System.exit(differ == 0 ? 0 : 1);
   }

   /** One chunk: seed 1's volume against seed 2's points. True when the comparison notices. */
   private static boolean controlSeesDifference(WorldgenContext ctx, Dim dim, int minY, int height) {
      RandomState a = ctx.newFullRandomState(dim, 1L);
      RandomState b = ctx.newFullRandomState(dim, 2L);
      DensitySampler.Bound[] volumeFns = bound(a.createClimateSampler(SamplerContext.builder().enableCaches().build()));
      DensitySampler.Bound[] pointFns = bound(WorldgenContext.climate(b));
      DensityVolume volume = new DensityVolume(4, QuartPos.fromBlock(height), 4, 0, minY, 0, 4, 4, 4);
      for (int k = 0; k < 6; k++) {
         DensityBuffer buffer = DensityBuffer.createUnpooled(volume.size());
         volumeFns[k].sampleVolume(buffer, volume);
         for (int i = 0; i < volume.sizeY(); i++) {
            int index = volume.indexUnchecked(0, i, 0);
            if (Float.floatToIntBits(buffer.get(index))
                  != Float.floatToIntBits(pointFns[k].sampleValue(volume.blockX(0), volume.blockY(i), volume.blockZ(0)))) {
               return true;
            }
         }
      }
      return false;
   }

   private static DensitySampler.Bound[] bound(Climate.Sampler s) {
      return new DensitySampler.Bound[]{s.temperature(), s.humidity(), s.continentalness(), s.erosion(), s.depth(),
            s.weirdness()};
   }

   private SamplerDiagnostic() {
   }
}
