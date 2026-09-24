package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fablevision.VillageDiagnostic;
import com.fablevision.client.seedfinder.SeedCriteria.StructureTarget;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * "I PICKED WEAPONSMITH AND SOMETIMES GOT THE ARMORER'S HOUSE."
 *
 * Three questions, each answered from the game's own files rather than from this project's tokens:
 *
 *   1. CAN THE TOKEN MATCH THE WRONG HOUSE? Every template the row's token matches is opened and its
 *      blocks counted. A weaponsmith template with a blast furnace in it would be the bug. So would a
 *      weaponsmith house the token misses — which is how snowy villages came to be listed as having
 *      no weaponsmith: the file is spelled {@code snowy_weapon_smith_1}.
 *
 *   2. WHAT STANDS AT THE SECOND COORDINATE? Run the shipped search, read the "weaponsmith N blocks
 *      further on, at X, Z" text a player reads, re-assemble the village independently, and list every
 *      HOUSE whose footprint covers that column. It must be exactly one, and it must be a weaponsmith.
 *
 *   3. WHAT STANDS AT THE FIRST COORDINATE? The structure position is where a player lands first.
 *      If an armorer is the nearest house there, "I went there and it was the armorer" is a correct
 *      report of a correct search whose second line was not read.
 *
 * {@code gradlew smithDiag --args="<hits per mode> [radius] [row label]"}
 */
public final class SmithDiagnostic {

   private static final Pattern SECOND = Pattern.compile("further on, at (-?\\d+), (-?\\d+)");
   private static final Pattern FIRST = Pattern.compile("(-?\\d+), (-?\\d+)");

   public static void main(String[] args) {
      int want = args.length > 0 ? Integer.parseInt(args[0]) : 20;
      int radius = args.length > 1 ? Integer.parseInt(args[1]) : 300;
      String label = args.length > 2 ? args[2].replace('_', ' ') : "Village with Weaponsmith";

      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      if (!VillageLayout.available()) {
         System.out.println("FAILED: no structure templates.");
         System.exit(1);
      }
      StructureTarget row = null;
      for (StructureTarget t : SeedCatalog.structures(ctx)) {
         if (t.label.equals(label)) {
            row = t;
         }
      }
      if (row == null || row.building == null) {
         System.out.println("FAILED: no content row called " + label);
         System.exit(1);
      }
      int failures = 0;

      // ── 1. the templates ───────────────────────────────────────────────────────────────────────
      System.out.println("========== 1. WHAT THE TOKEN MATCHES, FROM THE TEMPLATE FILES ==========");
      System.out.println("  row token: \"" + row.building + "\"");
      String[] biomes = {"plains", "desert", "savanna", "snowy", "taiga"};
      for (String biome : biomes) {
         for (Identifier id : houseTemplates(biome)) {
            String path = id.getPath();
            boolean matched = VillageLayout.hasBuilding(List.of(id), row.building);
            boolean smithy = path.contains("weaponsmith") || path.contains("weapon_smith");
            boolean armorer = path.contains("armorer");
            if (!matched && !smithy && !armorer) {
               continue;
            }
            Map<String, Integer> blocks = census(id);
            String verdict = "";
            if (matched && blocks.getOrDefault("blast_furnace", 0) > 0) {
               verdict = "  <-- TOKEN MATCHES A BLAST-FURNACE HOUSE";
               failures++;
            } else if (smithy && !matched) {
               verdict = "  <-- WEAPONSMITH TEMPLATE THE TOKEN MISSES";
               failures++;
            }
            System.out.printf("  %-6s %-48s %s%s%n", matched ? "MATCH" : "-", path, blocks, verdict);
         }
      }

      // ── 2 + 3. the coordinates a player is handed ─────────────────────────────────────────────
      System.out.println();
      System.out.println("========== 2. WHAT STANDS AT EACH PRINTED COORDINATE ==========");
      for (boolean fast : new boolean[]{true, false}) {
         StructureTarget target = fast ? row.withRadius(radius) : row;
         int hits = 0;
         int secondRight = 0;
         int secondWrong = 0;
         int noSecond = 0;
         int firstArmorer = 0;
         Map<String, Integer> byBiome = new TreeMap<>();
         for (long seed = 1; seed <= 300_000L && hits < want; seed++) {
            SeedCriteria criteria = new SeedCriteria();
            criteria.structures.add(target);
            List<String> lines = new ArrayList<>();
            if (!criteria.test(seed, ctx, lines, fast, null, true)) {
               continue;
            }
            String line = null;
            for (String l : lines) {
               if (l.startsWith(row.label + " ")) {
                  line = l;
               }
            }
            if (line == null) {
               continue;
            }
            Matcher m1 = FIRST.matcher(line);
            if (!m1.find()) {
               continue;
            }
            BlockPos structure = new BlockPos(Integer.parseInt(m1.group(1)), 64, Integer.parseInt(m1.group(2)));
            Matcher m2 = SECOND.matcher(line);
            BlockPos second = m2.find()
                  ? new BlockPos(Integer.parseInt(m2.group(1)), 64, Integer.parseInt(m2.group(2))) : null;
            StructureStart start = rederive(ctx, target, seed, structure);
            if (start == null) {
               System.out.println("  seed " + seed + ": printed village could not be re-derived  <-- INVESTIGATE");
               failures++;
               continue;
            }
            hits++;
            List<VillageLayout.PieceAt> houses = new ArrayList<>();
            for (VillageLayout.PieceAt p : VillageLayout.piecesAt(start.getPieces())) {
               if (p.id().getPath().contains("/houses/")) {
                  houses.add(p);
               }
            }
            String biome = houses.isEmpty() ? "?" : houses.get(0).id().getPath().split("/")[1];
            byBiome.merge(biome, 1, Integer::sum);

            // The column the player is told to walk to. Under 12 blocks there is no second
            // coordinate and the first one IS the building's.
            BlockPos column = second != null ? second : structure;
            List<String> under = new ArrayList<>();
            for (VillageLayout.PieceAt p : houses) {
               BoundingBox b = p.box();
               if (column.getX() >= b.minX() && column.getX() <= b.maxX()
                     && column.getZ() >= b.minZ() && column.getZ() <= b.maxZ()) {
                  under.add(p.id().getPath().substring(p.id().getPath().lastIndexOf('/') + 1));
               }
            }
            boolean right = under.size() == 1 && VillageLayout.tokenMatches(under.get(0), row.building);
            if (second == null) {
               noSecond++;
            }
            if (right) {
               secondRight++;
            } else {
               secondWrong++;
               failures++;
            }
            String nearestToFirst = nearestHouse(houses, structure);
            if (nearestToFirst.contains("armorer")) {
               firstArmorer++;
            }
            System.out.printf("  %-5s seed %-7d %-8s first %-12s nearest house there: %-26s second %-12s under it: %s%s%n",
                  fast ? "fast" : "exact", seed, biome, structure.getX() + "," + structure.getZ(),
                  nearestToFirst, second == null ? "(none)" : second.getX() + "," + second.getZ(),
                  under, right ? "" : "  <-- WRONG PIECE");
         }
         System.out.println();
         System.out.printf("  %s: %d finds · second coordinate on the right house %d, wrong %d (%d had no second line)%n",
               fast ? "FAST" : "EXACT", hits, secondRight, secondWrong, noSecond);
         System.out.printf("         the house nearest the FIRST coordinate was an armorer in %d of %d%n",
               firstArmorer, hits);
         System.out.println("         village types: " + byBiome);
         System.out.println();
      }
      System.out.println(failures == 0 ? "ALL CHECKS PASS" : failures + " FAILURE(S)");
      System.exit(failures == 0 ? 0 : 1);
   }

   /** Every house template a village biome can build, read from the jar's own structure folder. */
   private static List<Identifier> houseTemplates(String biome) {
      List<Identifier> out = new ArrayList<>();
      VillageLayout.templates().listTemplates()
            .filter(id -> id.getPath().startsWith("village/" + biome + "/houses/"))
            .sorted()
            .forEach(out::add);
      return out;
   }


   /** The blocks that tell the smith houses apart, counted in the template itself. */
   private static Map<String, Integer> census(Identifier id) {
      Map<String, Integer> out = new TreeMap<>();
      StructureTemplate template = VillageLayout.templates().get(id).orElse(null);
      if (template == null) {
         return out;
      }
      // Built here, not as a static: Blocks cannot be touched before Bootstrap has run.
      Block[] telltale = {Blocks.LAVA, Blocks.CHEST, Blocks.BLAST_FURNACE, Blocks.GRINDSTONE,
            Blocks.SMOOTH_STONE, Blocks.SMITHING_TABLE};
      for (Block b : telltale) {
         int n = template.filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), b).size();
         if (n > 0) {
            out.put(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath(), n);
         }
      }
      return out;
   }

   private static String nearestHouse(List<VillageLayout.PieceAt> houses, BlockPos at) {
      String best = "(none)";
      double bestD = Double.MAX_VALUE;
      for (VillageLayout.PieceAt p : houses) {
         BoundingBox b = p.box();
         double d = Math.hypot((b.minX() + b.maxX()) / 2.0 - at.getX(), (b.minZ() + b.maxZ()) / 2.0 - at.getZ());
         if (d < bestD) {
            bestD = d;
            best = p.id().getPath().substring(p.id().getPath().lastIndexOf('/') + 1);
         }
      }
      return best;
   }

   /** The printed village, found again through stage0 and assembled with no help from the funnel. */
   private static StructureStart rederive(WorldgenContext ctx, StructureTarget row, long seed, BlockPos claimed) {
      RandomState rs = ctx.randomState(row.dim, seed);
      ChunkGeneratorStructureState st = ctx.structureState(row.dim, seed, rs);
      for (SeedCriteria.Cand cand : row.stage0(seed, false)) {
         @SuppressWarnings("unchecked")
         Holder<Structure>[] out = new Holder[1];
         BlockPos pos = row.confirm(seed, ctx, rs, st, cand, out);
         if (pos == null || pos.getX() != claimed.getX() || pos.getZ() != claimed.getZ()) {
            continue;
         }
         try {
            ChunkPos chunk = cand.chunk();
            WorldgenContext.Scaffold scaffold = ctx.scaffold(row.dim);
            StructureStart start = out[0].value().generate(out[0], net.minecraft.world.level.Level.OVERWORLD,
                  (RegistryAccess) ctx.source, scaffold.generator(), ctx.biomeSource(row.dim),
                  ctx.fullRandomState(row.dim, seed), VillageLayout.templates(), seed, chunk, 0,
                  scaffold.height(), h -> true);
            return start != null && start.isValid() ? start : null;
         } catch (Throwable t) {
            return null;
         }
      }
      return null;
   }

   private SmithDiagnostic() {
   }
}
