package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fablevision.VillageDiagnostic;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Every picker row's icon, checked against the LIVE catalog rather than against the switch.
 *
 * Two failures, and both had shipped:
 *
 *   MISSING — a row with no case in {@link SeedIcons} falls through to a filled map. That is not a
 *   picture of anything, and because it is silent it stayed that way: the three ruined-portal
 *   placement rows and the slime row were all showing it. Reading the switch would not have found
 *   them; only walking the catalog does, because the catalog is what the player sees.
 *
 *   SHARED — two rows in the same list showing the same item. "Village with Stables" and "Trail
 *   Ruins with Stables" were both a hay block, and a player who reads the picture rather than the
 *   text was being sent to a different structure in a different biome. This cannot be caught by
 *   reading either row on its own, which is exactly why it needs a whole-list check.
 *
 * NEAR-IDENTICAL textures are not detectable from here (an oak boat and a spruce boat are two
 * different items and one picture). Those are called out in the comments in SeedIcons instead.
 *
 * {@code gradlew iconDiag}
 */
public final class IconDiagnostic {

   public static void main(String[] args) {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);

      int problems = 0;

      System.out.println("=========== STRUCTURE ROW ICONS ===========");
      List<String> structureLabels = new ArrayList<>();
      for (SeedCriteria.StructureTarget t : SeedCatalog.structures(ctx)) {
         if (t.special == SeedCriteria.Special.NORMAL) {
            structureLabels.add(t.label);
         }
      }
      // The slime row is not a structure but it IS a row in the same list, so it is checked here.
      structureLabels.add(SlimeChunks.ROW_LABEL);
      problems += report(structureLabels, SeedIcons::structureItem);

      System.out.println();
      System.out.println("=========== BIOME ROW ICONS ===========");
      List<String> biomePaths = new ArrayList<>();
      for (Identifier id : SeedCatalog.biomes(ctx)) {
         biomePaths.add(id.getPath());
      }
      problems += report(biomePaths, SeedIcons::biomeItem);

      // THE MAP'S OWN NAMES. The map labels some structures by what was built ("End City with Ship",
      // "Treasure Bastion"), and those labels borrow picker icons — so they are checked here too, as
      // one list per map tab, the way the player sees them side by side.
      System.out.println();
      System.out.println("=========== MAP-ONLY LABELS ===========");
      problems += report(List.of("End City with Ship", "End City"), SeedIcons::structureItem);

      System.out.println();
      System.out.println(problems == 0
            ? "✓ every row has its own icon, and no two rows in a list share one."
            : problems + " ICON PROBLEM(S) — see above.");
      // It printed problems and exited 0, so the regression run recorded it as passing whatever it
      // found. A check that cannot fail is not a check.
      System.exit(problems == 0 ? 0 : 1);
   }

   private static int report(List<String> rows, java.util.function.Function<String, Item> icon) {
      Map<String, List<String>> byItem = new LinkedHashMap<>();
      List<String> missing = new ArrayList<>();
      for (String row : rows) {
         Item item = icon.apply(row);
         String name = String.valueOf(item);
         if (item == Items.FILLED_MAP) {
            missing.add(row);
            continue;   // the fallback is expected to repeat; it is reported as missing instead
         }
         byItem.computeIfAbsent(name, k -> new ArrayList<>()).add(row);
      }
      System.out.println("  " + rows.size() + " rows, " + byItem.size() + " distinct icons");
      int problems = 0;
      for (String row : missing) {
         problems++;
         System.out.println("  MISSING  " + row + "   <-- falls back to a filled map");
      }
      for (Map.Entry<String, List<String>> e : byItem.entrySet()) {
         if (e.getValue().size() > 1) {
            problems++;
            System.out.println("  SHARED   " + e.getKey() + "  ->  " + String.join(" | ", e.getValue()));
         }
      }
      if (problems == 0) {
         System.out.println("  ✓ all present and all distinct");
      }
      return problems;
   }

   private IconDiagnostic() {
   }
}
