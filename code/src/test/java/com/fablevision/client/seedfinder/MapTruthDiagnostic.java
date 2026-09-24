package com.fablevision.client.seedfinder;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fablevision.VillageDiagnostic;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import com.fablevision.client.seedfinder.SeedMapData.MapDim;

/**
 * DOES EVERYTHING ON THE MAP REALLY GENERATE? Checked against the game itself, not against the search.
 *
 * {@code mapDiag} asks the SEARCH whether it agrees with the map. That is the check that let the
 * phantom Trial Chamber ship: both sides ran the same cheap test (placement, plus the biome at the
 * surface), so they agreed with each other and were both wrong. Agreement between two copies of one
 * idea is not evidence.
 *
 * This one asks the WORLD. For every chunk any structure could start in, it builds the chunk
 * generator exactly the way a new Default world does (from the "normal" world preset, not from this
 * mod's own scaffold), and runs vanilla's own structure-start step, {@code ChunkGenerator
 * .createStructures}, on a fresh proto-chunk. That is the step a real world runs when a chunk is first
 * loaded; whatever it produces is what will be standing there. Then two numbers per dimension:
 *
 *   - PHANTOMS: the map draws a structure the game did not start there. Must be zero.
 *   - MISSED:   the game starts one the map does not draw, inside the part of the map that was
 *               filled. Allowed, but printed, because a map with holes is a known cost, not a lie.
 *
 * The End is checked the same way, and the End tab's structures stay off unless its phantom count is
 * zero (see {@link SeedMapData#END_STRUCTURES}).
 *
 * {@code gradlew mapTruthDiag --args="<seeds> [reach]"}
 */
public final class MapTruthDiagnostic {

   public static void main(String[] args) {
      int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 6;
      int reach = args.length > 1 ? Integer.parseInt(args[1]) : 1500;
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      if (!VillageLayout.available()) {
         System.out.println("NO TEMPLATES - cannot run the game's structure step headlessly");
         System.exit(2);
      }

      Map<MapDim, int[]> totals = new LinkedHashMap<>();   // phantoms, missed, drawn, real, typed, wrong type
      java.util.Random pick = new java.util.Random(1430L);
      for (int i = 0; i < seeds; i++) {
         long seed = i == 0 ? 1L : pick.nextLong();
         BlockPos spawn = SeedMapData.overworldSpawn(ctx, seed);
         System.out.println();
         System.out.println("seed " + seed + "   spawn " + spawn.getX() + ", " + spawn.getZ());
         for (MapDim dim : MapDim.values()) {
            // -Pdims=END (or NETHER,END): a wider pass on one dimension without paying for the others.
            String only = System.getProperty("fv.dims", "");
            if (!only.isEmpty() && !only.contains(dim.name())) {
               continue;
            }
            int[] t = totals.computeIfAbsent(dim, d -> new int[6]);
            check(reg, ctx, seed, spawn, dim, reach, t);
         }
      }

      System.out.println();
      System.out.println("================================================");
      int bad = 0;
      for (var e : totals.entrySet()) {
         int[] t = e.getValue();
         System.out.println("  " + e.getKey() + ": drawn " + t[2] + ", really there " + t[3]
               + ", PHANTOMS " + t[0] + ", missed " + t[1]
               + (t[4] > 0 ? ", named by type " + t[4] + " (WRONG " + t[5] + ")" : ""));
         bad += t[0] + t[5];
         // Printed as a failure, not a footnote: a type check that checked nothing passes as loudly as
         // one that checked everything. The Nether has bastions and the End has cities on every seed
         // this run uses, so zero typed labels there means the naming never ran.
         if (t[4] == 0 && e.getKey() != MapDim.OVERWORLD && t[2] > 0
               && !(e.getKey() == MapDim.END && !SeedMapData.END_STRUCTURES)) {
            System.out.println("    <-- no " + (e.getKey() == MapDim.END ? "End City" : "bastion")
                  + " was named by type: the naming was not verified");
            bad++;
         }
         if (t[2] == 0 && t[3] > 0 && !(e.getKey() == MapDim.END && !SeedMapData.END_STRUCTURES)) {
            System.out.println("    <-- the map drew nothing while the world has structures: nothing was verified");
            bad++;
         }
      }
      // HOW LONG THE WIDEST MAP TAKES, since every marker now costs a real generation check.
      for (MapDim dim : MapDim.values()) {
         long t = System.nanoTime();
         SeedMapData.View wide = SeedMapData.build(ctx, 99L, dim, SeedMapData.overworldSpawn(ctx, 99L), SeedMapData.MAX_REACH);
         System.out.println("  widest " + dim + " map (20,000 blocks across): " + wide.nearby().size() + " structures in "
               + (System.nanoTime() - t) / 1_000_000 + "ms");
      }
      System.out.println(bad == 0 ? "  OK - every structure on the map is really there" : "  " + bad + " problem(s)");
      System.exit(bad == 0 ? 0 : 1);
   }

   private static void check(RegistryAccess.Frozen reg, WorldgenContext ctx, long seed, BlockPos spawn, MapDim dim,
                             int reach, int[] totals) {
      int cx = SeedMapData.originX(dim, spawn);
      int cz = SeedMapData.originZ(dim, spawn);

      long t0 = System.nanoTime();
      SeedMapData.View view = SeedMapData.buildAt(ctx, seed, dim, spawn, cx, cz, reach);
      long mapMs = (System.nanoTime() - t0) / 1_000_000;

      t0 = System.nanoTime();
      Map<String, String> real = truth(reg, seed, dim, cx, cz, reach);
      long truthMs = (System.nanoTime() - t0) / 1_000_000;

      // Only compare inside the part of the map that was filled: the map keeps the nearest few
      // hundred, and one further out than its last kept marker is not a miss.
      long keptR2 = 0;
      for (SeedMapData.Nearby n : view.nearby()) {
         keptR2 = Math.max(keptR2, sq(n.x() - cx) + sq(n.z() - cz));
      }
      boolean capped = view.capped();

      int phantoms = 0;
      int typed = 0;
      int wrongType = 0;
      Set<String> drawnKeys = new LinkedHashSet<>();
      for (SeedMapData.Nearby n : view.nearby()) {
         String key = n.chunkX() + "," + n.chunkZ();
         drawnKeys.add(key + "=" + n.id());
         String there = real.get(key);
         if (there == null || !there.contains(n.id())) {
            phantoms++;
            System.out.println("    PHANTOM " + dim + " " + n.label() + " (" + n.id() + ") at " + n.x() + ", " + n.z()
                  + " - the game starts " + (there == null ? "nothing" : there) + " in that chunk");
            continue;
         }
         // A RIGHT PLACE WITH THE WRONG NAME IS STILL A LIE. "Treasure Bastion" over a bridge bastion
         // sends the player to the right spot for the wrong reason, so the type is held to the game too.
         if (n.detail() != null) {
            typed++;
            if (!java.util.Arrays.asList(there.split("\\|")).contains(n.id() + "#" + n.detail())) {
               wrongType++;
               System.out.println("    WRONG TYPE " + dim + " map says " + n.label() + " at " + n.x() + ", " + n.z()
                     + " - the game built " + there);
            }
         }
      }
      int missed = 0;
      Map<String, Integer> missedBy = new HashMap<>();
      int realCount = 0;
      for (var e : real.entrySet()) {
         for (String entry : e.getValue().split("\\|")) {
            String id = entry.contains("#") ? entry.substring(0, entry.indexOf('#')) : entry;
            String[] c = e.getKey().split(",");
            int x = Integer.parseInt(c[0]) * 16 + 8;
            int z = Integer.parseInt(c[1]) * 16 + 8;
            if (SeedMapData.skippedOnPurpose(ctx, id, reach)) {
               continue;
            }
            // PER CELL since 1.44.5: a cell that took its share keeps the ones nearest ITS middle, so one
            // further from that middle than its furthest kept one is not a miss. Judged at the structure's
            // LOCATE point — the point the map sorts and files by — not the chunk middle: near a cell's
            // edge the two can fall in different cells, which first showed up here as eight false misses.
            BlockPos locate = locateOf(ctx, id, Integer.parseInt(c[0]), Integer.parseInt(c[1]));
            int lx = locate == null ? x : locate.getX();
            int lz = locate == null ? z : locate.getZ();
            int cell = view.cellOf(lx, lz);
            long cellFull = view.cellFull()[cell];
            if (cellFull >= 0 && sq(lx - view.cellMiddleX(cell)) + sq(lz - view.cellMiddleZ(cell)) >= cellFull) {
               continue;
            }
            realCount++;
            if (!drawnKeys.contains(e.getKey() + "=" + id)) {
               missed++;
               missedBy.merge(id, 1, Integer::sum);
               if (Boolean.getBoolean("fv.verbose")) {
                  System.out.println("    missed " + dim + " " + id + " chunk " + e.getKey() + " at " + (int) Math.sqrt(sq(x - cx) + sq(z - cz))
                        + " blocks; map filled to " + (int) Math.sqrt(keptR2) + (capped ? " (capped)" : "")
                        + "; cell " + cell + " kept to " + (cellFull < 0 ? "all" : (int) Math.sqrt(cellFull))
                        + ", this one " + (int) Math.sqrt(sq(x - view.cellMiddleX(cell)) + sq(z - view.cellMiddleZ(cell)))
                        + " from its middle");
               }
            }
         }
      }
      // STRONGHOLDS COUNTED OUT LOUD (1.44.3): the map draws them now, and a check that never met one
      // would pass exactly as loudly as one that matched every one.
      long drawnStrongholds = view.nearby().stream().filter(n -> n.id().equals("stronghold")).count();
      long realStrongholds = real.values().stream().filter(v -> v.contains("stronghold")).count();
      if (drawnStrongholds + realStrongholds > 0) {
         System.out.println("    strongholds: map " + drawnStrongholds + ", game " + realStrongholds);
      }
      totals[0] += phantoms;
      totals[1] += missed;
      totals[2] += view.nearby().size();
      totals[3] += realCount;
      totals[4] += typed;
      totals[5] += wrongType;
      System.out.println("  " + dim + ": map " + view.nearby().size() + " (" + mapMs + "ms), game " + realCount
            + " (" + truthMs + "ms), phantoms " + phantoms + ", missed " + missed
            + (typed > 0 ? ", types checked " + typed + " (" + wrongType + " wrong)" : "")
            + (missedBy.isEmpty() ? "" : " " + missedBy)
            + (view.unverifiable() > 0 ? ", " + view.unverifiable() + " unverifiable (hidden)" : ""));
   }

   /** Where the map files a structure: its set's own locate point for that chunk. */
   private static BlockPos locateOf(WorldgenContext ctx, String id, int chunkX, int chunkZ) {
      for (var set : ctx.allSets) {
         for (var e : set.value().structures()) {
            if (e.structure().unwrapKey().map(k -> k.identifier().getPath()).orElse("").equals(id)) {
               return set.value().placement().getLocatePos(new ChunkPos(chunkX, chunkZ));
            }
         }
      }
      return null;
   }

   private static long sq(long v) {
      return v * v;
   }

   /**
    * chunk "x,z" -> "id|id" of every structure the game really starts there. The generator comes from
    * the NORMAL world preset, the way a new Default world gets it — not from WorldgenContext, which is
    * the thing being checked.
    */
   static Map<String, String> truth(RegistryAccess.Frozen reg, long seed, MapDim dim, int cx, int cz, int reach) {
      ResourceKey<LevelStem> stemKey = switch (dim) {
         case OVERWORLD -> LevelStem.OVERWORLD;
         case NETHER -> LevelStem.NETHER;
         case END -> LevelStem.END;
      };
      ResourceKey<Level> levelKey = switch (dim) {
         case OVERWORLD -> Level.OVERWORLD;
         case NETHER -> Level.NETHER;
         case END -> Level.END;
      };
      LevelStem stem = reg.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value()
            .createWorldDimensions().get(stemKey).orElseThrow();
      ChunkGenerator gen = stem.generator();
      RandomState rs = RandomState.create(reg,
            ((NoiseBasedChunkGenerator) gen).generatorSettings().unwrapKey().orElseThrow(), seed);
      ChunkGeneratorStructureState state = gen.createState(reg.lookupOrThrow(Registries.STRUCTURE_SET), rs, seed);
      StructureManager sm = new StructureManager(null, new WorldOptions(seed, true, false), null);
      DimensionType type = stem.type().value();
      LevelHeightAccessor height = LevelHeightAccessor.create(type.minY(), type.height());
      PalettedContainerFactory containers = PalettedContainerFactory.create(reg);

      Set<Long> chunks = new LinkedHashSet<>();
      int chunkR = Math.max(1, reach >> 4);
      int ccx = cx >> 4;
      int ccz = cz >> 4;
      for (Holder<StructureSet> set : state.possibleStructureSets()) {
         // RING-PLACED SETS (strongholds), since the map draws them (1.44.3): the game's own ring list,
         // from the game's own structure state, not the map's.
         if (set.value().placement() instanceof net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement ring) {
            List<ChunkPos> at = state.getRingPositionsFor(ring);
            for (ChunkPos c : at == null ? List.<ChunkPos>of() : at) {
               if (Math.abs(c.getMiddleBlockX() - cx) <= reach && Math.abs(c.getMiddleBlockZ() - cz) <= reach) {
                  chunks.add(ChunkPos.pack(c.x(), c.z()));
               }
            }
            continue;
         }
         if (!(set.value().placement() instanceof RandomSpreadStructurePlacement spread)) {
            continue;
         }
         int spacing = spread.spacing();
         for (int rx = Math.floorDiv(ccx - chunkR, spacing); rx <= Math.floorDiv(ccx + chunkR, spacing); rx++) {
            for (int rz = Math.floorDiv(ccz - chunkR, spacing); rz <= Math.floorDiv(ccz + chunkR, spacing); rz++) {
               ChunkPos c = spread.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
               if (Math.abs(c.getMiddleBlockX() - cx) <= reach && Math.abs(c.getMiddleBlockZ() - cz) <= reach) {
                  chunks.add(ChunkPos.pack(c.x(), c.z()));
               }
            }
         }
      }
      Map<String, String> out = new LinkedHashMap<>();
      for (long packed : chunks) {
         ChunkPos pos = ChunkPos.unpack(packed);
         ProtoChunk proto = new ProtoChunk(pos, UpgradeData.EMPTY, height, containers, null);
         gen.createStructures(reg, state, sm, proto, VillageLayout.templates(), levelKey);
         for (Map.Entry<Structure, StructureStart> e : proto.getAllStarts().entrySet()) {
            if (!e.getValue().isValid()) {
               continue;
            }
            var structures = reg.lookupOrThrow(Registries.STRUCTURE);
            String id = structures.getKey(e.getKey()).getPath();
            // WHAT IT BUILT, for the two kinds the map names by it (ship or not; bastion type), read
            // from the game's own StructureStart — the pieces a real world would place.
            String detail = SeedMapData.detailOf(structures.wrapAsHolder(e.getKey()), e.getValue());
            out.merge(pos.x() + "," + pos.z(), detail == null ? id : id + "#" + detail, (a, b) -> a + "|" + b);
         }
      }
      return out;
   }

   private MapTruthDiagnostic() {
   }
}
