package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fablevision.client.seedfinder.SeedCriteria.Cand;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import com.fablevision.client.seedfinder.SeedCriteria.StructureTarget;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Dev-only: WHERE a structure really is for a seed, using the shipped funnel rather than a
 * re-implementation — {@link StructureTarget#stage0} for candidate chunks, then
 * {@link StructureTarget#confirm} for the vanilla-exact placement, variant pick and biome test.
 *
 * Why the diagnostics need this and chunk 0,0 no longer suffices:
 *   The overworld derivations assemble at {@code ChunkPos(0,0)} and ask a hypothetical — "if a
 *   village started here, what would it build". That is the right question for proving assembly
 *   works, and it survives because every overworld seed HAS terrain at 0,0.
 *
 *   In the Nether it stops being true — a fortress or a bastion at chunk 0,0 is a hypothetical
 *   about a chunk that may hold neither — so the only honest way to ask what one builds is to ask
 *   a chunk that has one.
 *
 *   (This note used to be about End Cities, whose generation depends on an outer island being
 *   high enough. That dependency is exactly why the End was removed in 1.41.4 — see
 *   SeedCriteria.Dim — so it is no longer an example, it is the reason there is nothing to
 *   exemplify.)
 *
 * Lives in the seedfinder package because stage0/confirm are package-private — same reason the
 * walk diagnostic does.
 */
public final class Placed {

   /** One structure that really generates: its chunk, the variant that won the set's pick, the
    *  locate position, and how far that is from the dimension's origin. */
   public record Spot(ChunkPos chunk, Holder<Structure> winner, BlockPos pos, long dist) {}

   /**
    * The origin distances are measured from, mirroring {@link SeedCriteria} exactly: the real
    * spawn in the overworld, and that spawn divided by 8 in the Nether (portal maths — where you
    * arrive if you light a portal at spawn).
    *
    * This is the "at-spawn portal distance model", and it is the only distance model that means
    * anything to a player: a bastion 200 blocks from Nether-spawn is a short walk from a portal
    * they will actually build, while the same bastion is 1600 overworld blocks from home.
    */
   public static BlockPos origin(WorldgenContext ctx, long seed, Dim dim) {
      BlockPos spawn = ctx.randomState(Dim.OVERWORLD, seed).sampler().findSpawnPosition();
      return dim == Dim.NETHER ? new BlockPos(spawn.getX() / 8, 64, spawn.getZ() / 8) : spawn;
   }

   /**
    * Every structure of this target that really generates within {@code reach} of the dimension's
    * origin, nearest first. Nothing is dropped: a candidate silently removed here is a row missing
    * from a walk table, which is the one failure that would make these tools useless.
    */
   public static List<Spot> near(WorldgenContext ctx, StructureTarget target, long seed, Dim dim,
                                 int reach) {
      BlockPos from = origin(ctx, seed, dim);
      RandomState rs = ctx.randomState(dim, seed);
      ChunkGeneratorStructureState st = ctx.structureState(dim, seed, rs);

      List<Spot> out = new ArrayList<>();
      for (Cand cand : target.stage0(seed)) {
         @SuppressWarnings("unchecked")
         Holder<Structure>[] winnerOut = new Holder[1];
         BlockPos pos = target.confirm(seed, ctx, rs, st, cand, winnerOut);
         if (pos == null) {
            continue;   // placement, the variant pick or the biome said no
         }
         long d = Math.round(Math.hypot(pos.getX() - from.getX(), pos.getZ() - from.getZ()));
         if (d <= reach) {
            out.add(new Spot(cand.chunk(), winnerOut[0], pos, d));
         }
      }
      out.sort(Comparator.comparingLong(Spot::dist));
      return out;
   }

   /** The nearest one, or null if this seed has none in reach. */
   public static Spot nearest(WorldgenContext ctx, StructureTarget target, long seed, Dim dim,
                              int reach) {
      List<Spot> all = near(ctx, target, seed, dim, reach);
      return all.isEmpty() ? null : all.get(0);
   }

   private Placed() {
   }
}
