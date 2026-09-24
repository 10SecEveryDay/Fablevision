package com.fablevision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.fablevision.client.seedfinder.VillageLayout;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Does a BLACKSMITH still exist as its own building — the old lava-pit-and-double-chest hut — or is
 * "blacksmith" now just the word people use for the armorer's house?
 *
 * This is a question about what this version's data pack actually SHIPS, and the only honest way to
 * answer it is to read the template files. Everyone including the wiki will tell you the pre-1.14
 * blacksmith was split into the armorer, the weaponsmith and the toolsmith; that is a claim about
 * history, not about whether a lava-and-loot house template survives in this version under some
 * other name. So this looks for the SHAPE of the thing rather than the word: lava next to a chest,
 * inside a village house template.
 *
 * The three professions are printed alongside so the answer can be read as a comparison rather than
 * taken on trust — if the armorer's house turns out to contain lava and a chest itself, then a
 * separate "Blacksmith" row would be a second name for a building the catalog already has.
 *
 * {@code gradlew blacksmithDiag}
 */
public final class BlacksmithDiagnostic {

   /** What the old blacksmith was recognisable BY. Lava and a chest in one building is the pattern;
    *  the profession workstations are here to tell the three modern houses apart from it. */
   private static final List<String> MARKERS = List.of(
         "minecraft:lava", "minecraft:chest", "minecraft:trapped_chest", "minecraft:cauldron",
         "minecraft:anvil", "minecraft:blast_furnace", "minecraft:furnace", "minecraft:smithing_table",
         "minecraft:grindstone", "minecraft:smoker", "minecraft:barrel");

   public static void main(String[] args) {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      StructureTemplateManager manager = VillageLayout.templates();
      if (manager == null) {
         System.out.println("FAILED: no structure templates — nothing here can be trusted.");
         return;
      }

      List<Identifier> templates = manager.listTemplates()
            .filter(id -> id.getPath().startsWith("village/"))
            .sorted(Comparator.comparing(Identifier::getPath))
            .toList();
      System.out.println("========== IS THERE STILL A BLACKSMITH? ==========");
      System.out.println("  " + templates.size() + " village templates in this version");
      System.out.println();

      // ── 1. Does anything even claim the name? ────────────────────────────────────────────────
      List<String> named = new ArrayList<>();
      for (Identifier id : templates) {
         String p = id.getPath().toLowerCase(java.util.Locale.ROOT);
         if (p.contains("blacksmith") || p.contains("smithy") || p.contains("forge")) {
            named.add(id.getPath());
         }
      }
      System.out.println("  1. TEMPLATES NAMED blacksmith / smithy / forge : "
            + (named.isEmpty() ? "NONE" : String.join(", ", named)));

      // ── 2. The shape: lava inside a house ────────────────────────────────────────────────────
      Map<String, Map<String, Integer>> counts = new TreeMap<>();
      for (Identifier id : templates) {
         StructureTemplate t = manager.get(id).orElse(null);
         if (t == null) {
            continue;
         }
         CompoundTag saved;
         try {
            saved = t.save(new CompoundTag());
         } catch (Exception unreadable) {
            continue;
         }
         counts.put(id.getPath(), census(saved));
      }

      System.out.println();
      System.out.println("  2. EVERY VILLAGE TEMPLATE CONTAINING LAVA:");
      int withLava = 0;
      for (var e : counts.entrySet()) {
         int lava = e.getValue().getOrDefault("minecraft:lava", 0);
         if (lava == 0) {
            continue;
         }
         withLava++;
         int chest = e.getValue().getOrDefault("minecraft:chest", 0)
               + e.getValue().getOrDefault("minecraft:trapped_chest", 0);
         System.out.println("     " + pad(e.getKey(), 52) + "lava x" + lava + "  chest x" + chest);
      }
      if (withLava == 0) {
         System.out.println("     NONE — no village template in this version contains lava at all.");
      }

      // ── 3. What the three modern smith houses actually contain ───────────────────────────────
      System.out.println();
      System.out.println("  3. THE THREE MODERN SMITH HOUSES, for comparison:");
      System.out.println("     " + pad("template", 52) + "markers");
      for (var e : counts.entrySet()) {
         String p = e.getKey();
         if (!p.contains("armorer") && !p.contains("weaponsmith") && !p.contains("tool_smith")
               && !p.contains("toolsmith")) {
            continue;
         }
         List<String> found = new ArrayList<>();
         for (String m : MARKERS) {
            int n = e.getValue().getOrDefault(m, 0);
            if (n > 0) {
               found.add(m.substring("minecraft:".length()) + " x" + n);
            }
         }
         System.out.println("     " + pad(p, 52) + String.join(", ", found));
      }

      System.out.println();
      System.out.println("  VERDICT: a separate \"Blacksmith\" row is only honest if section 2 lists a");
      System.out.println("  template with BOTH lava and a chest that is NOT one of the houses in");
      System.out.println("  section 3. Otherwise \"blacksmith\" names a building this version does not");
      System.out.println("  have, and the phrase book mapping it to the armorer is the whole answer.");
      System.out.println("==================================================");
      System.exit(0);
   }

   /** Block id -> count, read out of the template's own saved NBT (same method mansionDiag uses). */
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
            out.merge(palette.get(state), 1, Integer::sum);
         }
      }
      return out;
   }

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
         out.add(palette.getCompoundOrEmpty(i).getStringOr("Name", ""));
      }
      return out;
   }

   private static String pad(String s, int width) {
      StringBuilder sb = new StringBuilder(s);
      while (sb.length() < width) {
         sb.append(' ');
      }
      return sb.toString();
   }

   private BlacksmithDiagnostic() {
   }
}
