package com.fablevision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import com.fablevision.client.seedfinder.VillageLayout;
import com.fablevision.client.seedfinder.WorldgenContext;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Dev-only: works out WHAT EACH WOODLAND MANSION ROOM ACTUALLY IS.
 *
 * The problem this exists to solve is that the mansion is the one structure whose piece names carry
 * no meaning. A village says {@code village/plains/houses/plains_butcher_shop} and the row writes
 * itself; a mansion says {@code mansion/1x2_s2} and that is a grid size and a letter. There is no
 * "secret_room" template, no "arena", no name for anything. Every other row in this project was
 * derived by matching a word a player uses against a word the assets use, and here the assets have
 * no words at all.
 *
 * So the derivation has to go one level deeper, to the BLOCKS. A room is identified by what is
 * inside it, and this reads that straight out of the template file: every block kind, every entity,
 * counted. Then it asks which of those blocks are RARE ACROSS THE MANSION — a room containing the
 * only diamond block in the whole building is identifiable whatever its file is called, and a room
 * whose entire contents are dark oak planks and cobblestone is not identifiable at all and must
 * never become a row.
 *
 * The rarity threshold is measured, not chosen: a block counts as distinctive when it appears in at
 * most {@link #DISTINCT_MAX_TEMPLATES} of the mansion's templates. Nothing here knows what a secret
 * room is; it reports what is unusual and the human names it.
 *
 * Part two is the rate. A room that is in every mansion is not a search — that is the trail-ruins
 * tower lesson, and it has already cost this project one wasted row. Mansions are assembled for
 * real and every template code counted, so "worth shipping" is a number rather than an impression.
 *
 * {@code gradlew mansionDiag --args="<mansions to assemble> [blocks|rates|all]"}
 */
public final class MansionRoomDiagnostic {

   /**
    * The folder every mansion template lives under — DERIVED, and the first run proved why.
    *
    * The obvious guess is "mansion/", which is what {@link VillageLayout}'s own javadoc said, and
    * it matches nothing: this version files them under {@code woodland_mansion/}. A hardcoded
    * "mansion/" produced a clean, confident, completely empty census. That is the {@code toolsmith}
    * trap again, so the folder is found the same way every token in this project is — by asking the
    * assets rather than remembering.
    */
   private static String mansionFolder(StructureTemplateManager manager) {
      Set<String> folders = new LinkedHashSet<>();
      manager.listTemplates().map(Identifier::getPath)
            .filter(p -> p.toLowerCase(java.util.Locale.ROOT).contains("mansion") && p.contains("/"))
            .forEach(p -> folders.add(p.substring(0, p.indexOf('/') + 1)));
      if (folders.size() != 1) {
         System.out.println("  mansion templates are spread over " + folders + " — check this.");
      }
      return folders.isEmpty() ? "mansion/" : folders.iterator().next();
   }

   private static String mansionFolder = "mansion/";

   /**
    * A block is "distinctive" when no more than this many mansion templates contain it.
    *
    * The mansion ships ~80 templates, so a block in three of them is in under 4% of the building
    * and names those three; a block in twenty is scenery. This is the only tuned number here and it
    * only affects what gets PRINTED — the census below it is complete, so a wrong threshold hides a
    * line rather than inventing one.
    */
   private static final int DISTINCT_MAX_TEMPLATES = 4;

   public static void main(String[] args) {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      StructureTemplateManager manager = VillageLayout.templates();
      if (manager == null) {
         System.out.println("FAILED: no structure templates — nothing here can be trusted.");
         return;
      }

      int sample = 40;
      String mode = "all";
      for (String a : args) {
         try {
            sample = Integer.parseInt(a);
         } catch (NumberFormatException notANumber) {
            mode = a.toLowerCase(java.util.Locale.ROOT);
         }
      }

      mansionFolder = mansionFolder(manager);
      List<Identifier> templates = manager.listTemplates()
            .filter(id -> id.getPath().startsWith(mansionFolder))
            .sorted(Comparator.comparing(Identifier::getPath))
            .toList();
      System.out.println("========== WOODLAND MANSION: WHAT IS IN EACH ROOM ==========");
      System.out.println("  " + templates.size() + " templates under \"" + mansionFolder + "\"");

      // ── Census ───────────────────────────────────────────────────────────────
      // blockCounts: template path -> block id -> how many of that block the file contains.
      Map<String, Map<String, Integer>> blockCounts = new TreeMap<>();
      Map<String, Map<String, Integer>> entityCounts = new TreeMap<>();
      Map<String, Integer> templatesWithBlock = new HashMap<>();
      Map<String, int[]> sizes = new TreeMap<>();
      for (Identifier id : templates) {
         StructureTemplate template = manager.get(id).orElse(null);
         if (template == null) {
            System.out.println("  could not load " + id);
            continue;
         }
         CompoundTag saved;
         try {
            saved = template.save(new CompoundTag());
         } catch (Throwable t) {
            System.out.println("  could not read " + id + ": " + t);
            continue;
         }
         Map<String, Integer> blocks = census(saved);
         blockCounts.put(id.getPath(), blocks);
         entityCounts.put(id.getPath(), entities(saved));
         var size = template.getSize();
         sizes.put(id.getPath(), new int[]{size.getX(), size.getY(), size.getZ()});
         for (String block : blocks.keySet()) {
            templatesWithBlock.merge(block, 1, Integer::sum);
         }
      }

      if (!"rates".equals(mode)) {
         System.out.println();
         System.out.println("---------- STEP 1: RAREST BLOCK IN THE WHOLE MANSION ----------");
         System.out.println("  every block kind that appears in at most " + DISTINCT_MAX_TEMPLATES
               + " of the " + blockCounts.size() + " templates, and which ones have it.");
         System.out.println("  These are the only things a room can be RECOGNISED by; anything more");
         System.out.println("  common than this is scenery and names nothing.");
         System.out.println();
         List<Map.Entry<String, Integer>> rare = new ArrayList<>(templatesWithBlock.entrySet());
         rare.sort(Map.Entry.comparingByValue());
         for (Map.Entry<String, Integer> e : rare) {
            if (e.getValue() > DISTINCT_MAX_TEMPLATES) {
               continue;
            }
            List<String> where = new ArrayList<>();
            for (var t : blockCounts.entrySet()) {
               Integer n = t.getValue().get(e.getKey());
               if (n != null) {
                  where.add(shortName(t.getKey()) + "×" + n);
               }
            }
            System.out.println("   " + pad(e.getKey(), 34) + pad("in " + e.getValue(), 7)
                  + String.join(", ", where));
         }

         System.out.println();
         System.out.println("---------- STEP 2: EVERY TEMPLATE AND WHAT MAKES IT ITSELF ----------");
         System.out.println("  \"distinctive\" = blocks in <=" + DISTINCT_MAX_TEMPLATES + " templates."
               + "  \"(nothing)\" = this room cannot be told");
         System.out.println("  apart from its neighbours by its contents, so it must never become a row.");
         System.out.println();
         for (var t : blockCounts.entrySet()) {
            List<String> marks = new ArrayList<>();
            List<Map.Entry<String, Integer>> ordered = new ArrayList<>(t.getValue().entrySet());
            ordered.sort(Comparator.comparingInt(x -> templatesWithBlock.getOrDefault(x.getKey(), 999)));
            for (var b : ordered) {
               if (templatesWithBlock.getOrDefault(b.getKey(), 999) <= DISTINCT_MAX_TEMPLATES) {
                  marks.add(b.getKey().replace("minecraft:", "") + "×" + b.getValue());
               }
            }
            Map<String, Integer> ents = entityCounts.getOrDefault(t.getKey(), Map.of());
            for (var e : ents.entrySet()) {
               marks.add("ENTITY " + e.getKey().replace("minecraft:", "") + "×" + e.getValue());
            }
            int[] size = sizes.getOrDefault(t.getKey(), new int[]{0, 0, 0});
            System.out.println("   " + pad(shortName(t.getKey()), 18)
                  + pad(size[0] + "x" + size[1] + "x" + size[2], 12)
                  + pad(t.getValue().size() + " kinds", 10)
                  + (marks.isEmpty() ? "(nothing distinctive)" : String.join(", ", marks)));
         }
      }

      if ("blocks".equals(mode)) {
         System.out.println();
         System.out.println("(census only — rerun without \"blocks\" for the assembly rates)");
         return;
      }

      // ── Rates ────────────────────────────────────────────────────────────────
      System.out.println();
      System.out.println("---------- STEP 3: HOW OFTEN EACH TEMPLATE ACTUALLY LANDS ----------");
      System.out.println("  " + sample + " mansions assembled for real, at chunk 0,0, one per seed.");
      System.out.println("  A room in every mansion is NOT a search (the trail-ruins tower lesson).");
      System.out.println();
      Holder<net.minecraft.world.level.levelgen.structure.Structure> mansion =
            reg.lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.WOODLAND_MANSION);

      Map<String, Integer> mansionsWith = new TreeMap<>();
      Map<String, Integer> totalPieces = new TreeMap<>();
      int built = 0;
      int attempts = 0;
      int totalPieceCount = 0;
      for (long seed = 1; built < sample && seed <= 100_000L; seed++) {
         attempts++;
         RandomState full = ctx.fullRandomState(Dim.OVERWORLD, seed);
         List<Identifier> pieces = VillageLayout.pieceTemplates(
               reg, ctx, seed, new ChunkPos(0, 0), mansion, full);
         if (pieces.isEmpty()) {
            continue;   // no ground high enough at 0,0 for this seed — a terrain miss, not a bug
         }
         built++;
         totalPieceCount += pieces.size();
         Set<String> here = new LinkedHashSet<>();
         for (Identifier id : pieces) {
            String path = id.getPath();
            totalPieces.merge(path, 1, Integer::sum);
            here.add(path);
         }
         for (String path : here) {
            mansionsWith.merge(path, 1, Integer::sum);
         }
      }
      System.out.println("  assembled " + built + " mansions from " + attempts + " seeds"
            + " (average " + (built == 0 ? 0 : totalPieceCount / built) + " pieces each)");
      System.out.println();
      System.out.println("   " + pad("template", 18) + pad("in N/" + built, 12) + pad("%", 6)
            + pad("total placed", 14) + "distinctive contents");
      List<String> byRate = new ArrayList<>(blockCounts.keySet());
      byRate.sort(Comparator.comparingInt((String p) -> -mansionsWith.getOrDefault(p, 0))
            .thenComparing(p -> p));
      for (String path : byRate) {
         int n = mansionsWith.getOrDefault(path, 0);
         int pct = built == 0 ? 0 : n * 100 / built;
         List<String> marks = new ArrayList<>();
         for (var b : blockCounts.getOrDefault(path, Map.of()).entrySet()) {
            if (templatesWithBlock.getOrDefault(b.getKey(), 999) <= DISTINCT_MAX_TEMPLATES) {
               marks.add(b.getKey().replace("minecraft:", "") + "×" + b.getValue());
            }
         }
         System.out.println("   " + pad(shortName(path), 18)
               + pad(n + "/" + built, 12) + pad(pct + "%", 6)
               + pad(String.valueOf(totalPieces.getOrDefault(path, 0)), 14)
               + (n == 0 ? "NEVER PLACED — "
                     : "")
               + (marks.isEmpty() ? "(nothing distinctive)" : String.join(", ", marks)));
      }
      // ── The shipped rows, re-proved against the same run ─────────────────────
      // Same discipline as `professionDiag --args="verify"`: the catalog's own tokens are read
      // back out of the live catalog and checked against what really assembled, so a row can never
      // drift from the derivation that justified it. A token that never fires is a search that
      // silently answers no forever, and the only way to catch one is to ask.
      System.out.println();
      System.out.println("---------- STEP 4: THE TOKENS THE CATALOG ACTUALLY SHIPS ----------");
      int shipped = 0;
      int dead = 0;
      for (var row : com.fablevision.client.seedfinder.SeedCatalog.structures(ctx)) {
         if (row.building == null || !row.building.startsWith(mansionFolder)) {
            continue;
         }
         shipped++;
         int hits = 0;
         for (var e : mansionsWith.entrySet()) {
            if (com.fablevision.client.seedfinder.VillageLayout.tokenMatches(e.getKey(), row.building)) {
               hits += e.getValue();
            }
         }
         long files = templates.stream().filter(id -> com.fablevision.client.seedfinder.VillageLayout.tokenMatches(id.getPath(), row.building)).count();
         System.out.println("   " + pad(row.label, 26) + pad(row.building, 32)
               + pad(files + " file" + (files == 1 ? "" : "s"), 10)
               + pad(hits + "/" + built, 12) + (built == 0 ? 0 : hits * 100 / built) + "%"
               + (files == 0 ? "   DEAD TOKEN — matches no template at all"
                     : hits == 0 ? "   never landed in " + built + " mansions" : ""));
         if (files == 0 || hits == 0) {
            dead++;
         }
      }
      System.out.println("   " + shipped + " mansion rows shipped, " + dead + " with a problem"
            + (dead == 0 ? " — every token matches exactly one template and really fires." : ""));

      System.out.println();
      System.out.println("  A row is worth shipping only where BOTH columns agree: the rate is well");
      System.out.println("  under 100% (so it is a real filter) AND the contents name something a");
      System.out.println("  player would recognise on sight.");
      System.out.println("=============================================================");
   }

   /** Block id -> count, read out of the template's own saved NBT. The palette is the list of
    *  distinct block states; each entry in "blocks" points at one by index. */
   private static Map<String, Integer> census(CompoundTag saved) {
      Map<String, Integer> out = new TreeMap<>();
      List<String> palette = paletteNames(saved);
      if (palette.isEmpty()) {
         return out;
      }
      ListTag blocks = saved.getListOrEmpty(StructureTemplate.BLOCKS_TAG);
      for (int i = 0; i < blocks.size(); i++) {
         CompoundTag b = blocks.getCompoundOrEmpty(i);
         int state = b.getIntOr(StructureTemplate.BLOCK_TAG_STATE, -1);
         if (state >= 0 && state < palette.size()) {
            String name = palette.get(state);
            // Air is in every template by definition and says nothing about the room.
            if (!name.endsWith(":air") && !name.endsWith(":cave_air")) {
               out.merge(name, 1, Integer::sum);
            }
         }
      }
      return out;
   }

   /**
    * The palette as block ids, handling both shapes a template file can take: one palette
    * ("palette") or several alternatives ("palettes"). Only the first alternative is read — the
    * alternatives are colour swaps of the same room, so the block KINDS are what differ least, and
    * a room is being identified here, not rendered.
    */
   private static List<String> paletteNames(CompoundTag saved) {
      ListTag palette = saved.getListOrEmpty(StructureTemplate.PALETTE_TAG);
      if (palette.isEmpty()) {
         ListTag alternatives = saved.getListOrEmpty(StructureTemplate.PALETTE_LIST_TAG);
         if (!alternatives.isEmpty()) {
            palette = alternatives.getListOrEmpty(0);
         }
      }
      List<String> out = new ArrayList<>(palette.size());
      for (int i = 0; i < palette.size(); i++) {
         out.add(palette.getCompoundOrEmpty(i).getStringOr("Name", "?"));
      }
      return out;
   }

   /** Entity ids baked into the template. A mob that is part of the FILE is as certain as a block;
    *  one the structure code spawns afterwards is not, and does not appear here. */
   private static Map<String, Integer> entities(CompoundTag saved) {
      Map<String, Integer> out = new TreeMap<>();
      ListTag list = saved.getListOrEmpty(StructureTemplate.ENTITIES_TAG);
      for (int i = 0; i < list.size(); i++) {
         String id = list.getCompoundOrEmpty(i)
               .getCompoundOrEmpty(StructureTemplate.ENTITY_TAG_NBT)
               .getStringOr("id", "");
         if (!id.isEmpty()) {
            out.merge(id, 1, Integer::sum);
         }
      }
      return out;
   }

   private static String shortName(String path) {
      return path.startsWith(mansionFolder) ? path.substring(mansionFolder.length()) : path;
   }

   private static String pad(String s, int width) {
      StringBuilder sb = new StringBuilder(s);
      while (sb.length() < width) {
         sb.append(' ');
      }
      return sb.toString();
   }

   private MansionRoomDiagnostic() {
   }
}
