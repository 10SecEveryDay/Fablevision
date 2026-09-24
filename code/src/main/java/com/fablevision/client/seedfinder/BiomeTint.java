package com.fablevision.client.seedfinder;

import java.util.Locale;

/**
 * The colour each biome is painted on the spawn map.
 *
 * WHY A TABLE AND NOT THE GAME'S OWN COLOURS. A biome already carries colours — grass, foliage,
 * water, fog — and using them looks like the obvious answer until you draw a map with them. They are
 * TERRAIN colours, chosen so that a jungle and a swamp look right when you are standing in one, and
 * from above at two hundred blocks a pixel they are eleven shades of the same green. The job here is
 * the opposite: two biomes that meet must be TELLABLE APART at three pixels, whether or not their
 * grass is a similar colour. So this is a map legend, not a render.
 *
 * (Grass colour is also not readable without the game's colour-map textures loaded, so a headless
 * check of this file could not run — and everything in this project that could not be checked
 * headlessly is a thing that shipped wrong at least once.)
 *
 * THE RULES THE TABLE FOLLOWS, so a new biome can be added without guessing:
 *   1. Colour says CLIMATE FAMILY first — the eye should read "cold", "wet", "dry" before it reads
 *      which biome. Snow is white-blue, ocean is blue, desert is pale yellow, badlands is orange.
 *   2. Neighbours in the same family differ in LIGHTNESS, not in hue. Ocean, deep ocean and frozen
 *      ocean are three depths of blue, so the shape of a coastline survives even if the exact biome
 *      does not read.
 *   3. The rare thing is the bright thing. A cherry grove and a mushroom island are the two most
 *      saturated entries in the table, because on the one map in a thousand that has one, that is
 *      the thing the player opened the map to see.
 *
 * An unknown biome — a datapack's, or one added by a future version — comes back as a mid grey,
 * which is deliberately the one colour nothing else in the table uses. A wrong colour would be read
 * as a real biome; grey reads as "this map does not know", which is the truth.
 */
public final class BiomeTint {

   /** What an unlisted biome is drawn as. Not a plausible green — see the class note. */
   public static final int UNKNOWN = 0xFF5A5F66;

   /**
    * The colour for a biome id path ("cherry_grove", "minecraft:ocean" — either spelling works).
    * Opaque ARGB, ready to hand straight to a fill.
    */
   public static int of(String path) {
      String p = path == null ? "" : path.toLowerCase(Locale.ROOT).replace("minecraft:", "");
      return switch (p) {
         // ── Grass and flowers ────────────────────────────────────────────────────────────────
         case "plains" -> 0xFF8DB360;
         case "sunflower_plains" -> 0xFFB5DB68;
         case "meadow" -> 0xFF6FAF52;
         case "flower_forest" -> 0xFF9FE05A;
         // ── Forest ───────────────────────────────────────────────────────────────────────────
         case "forest" -> 0xFF3F7A32;
         case "birch_forest" -> 0xFF54804A;
         case "old_growth_birch_forest" -> 0xFF63946A;
         case "dark_forest" -> 0xFF29471F;
         // The pale garden is a grey-green nothing else in the table is near, because a pale garden
         // is the point of any map that has one on it.
         case "pale_garden" -> 0xFFB9BFA8;
         case "cherry_grove" -> 0xFFE8A5C4;
         case "windswept_forest" -> 0xFF5E7A56;
         // ── Taiga ────────────────────────────────────────────────────────────────────────────
         case "taiga" -> 0xFF2F6B4F;
         case "old_growth_pine_taiga" -> 0xFF3D6B45;
         case "old_growth_spruce_taiga" -> 0xFF4A6B3D;
         case "snowy_taiga" -> 0xFF75A18C;
         // ── Jungle ───────────────────────────────────────────────────────────────────────────
         case "jungle" -> 0xFF2E8B2E;
         case "sparse_jungle" -> 0xFF4E9B45;
         case "bamboo_jungle" -> 0xFF74B32E;
         // ── Wet ──────────────────────────────────────────────────────────────────────────────
         case "swamp" -> 0xFF4C6246;
         case "mangrove_swamp" -> 0xFF3B5A4A;
         case "river" -> 0xFF3F76C4;
         case "frozen_river" -> 0xFFA0C8E8;
         // ── Dry ──────────────────────────────────────────────────────────────────────────────
         case "desert" -> 0xFFE0D08A;
         case "savanna" -> 0xFFBFB755;
         case "savanna_plateau" -> 0xFFB0A94E;
         case "windswept_savanna" -> 0xFFA8A257;
         case "badlands" -> 0xFFD06B33;
         case "wooded_badlands" -> 0xFFB07240;
         case "eroded_badlands" -> 0xFFE0803C;
         // ── Cold ─────────────────────────────────────────────────────────────────────────────
         case "snowy_plains" -> 0xFFE8EEF2;
         case "ice_spikes" -> 0xFFB8E4F0;
         case "snowy_beach" -> 0xFFE4E2C8;
         case "snowy_slopes" -> 0xFFD8E2EA;
         case "grove" -> 0xFFAFC3B4;
         case "frozen_peaks" -> 0xFFC9DCEA;
         case "jagged_peaks" -> 0xFFB6BCC4;
         case "stony_peaks" -> 0xFF9E9A90;
         // ── Hills and shore ──────────────────────────────────────────────────────────────────
         case "windswept_hills" -> 0xFF7C8A72;
         case "windswept_gravelly_hills" -> 0xFF8E9384;
         case "beach" -> 0xFFEBE0A0;
         case "stony_shore" -> 0xFF8A8A82;
         // ── Ocean, by depth then by temperature ──────────────────────────────────────────────
         case "warm_ocean" -> 0xFF2CA9C4;
         case "lukewarm_ocean" -> 0xFF2E86B8;
         case "deep_lukewarm_ocean" -> 0xFF23689B;
         case "ocean" -> 0xFF2A5FA8;
         case "deep_ocean" -> 0xFF1E4380;
         case "cold_ocean" -> 0xFF3E6FA8;
         case "deep_cold_ocean" -> 0xFF2A4E7A;
         case "frozen_ocean" -> 0xFF8FB4D6;
         case "deep_frozen_ocean" -> 0xFF6A90B8;
         // ── The odd ones out ─────────────────────────────────────────────────────────────────
         case "mushroom_fields" -> 0xFFC57BD6;
         // Cave biomes cannot be on the surface, so they are only ever reached by a map that
         // samples below sea level. Coloured anyway rather than left to fall through to grey.
         case "dripstone_caves" -> 0xFF9B7B5E;
         case "lush_caves" -> 0xFF5FA84A;
         case "deep_dark" -> 0xFF122A33;
         case "the_void" -> 0xFF0A0A0E;
         // ── Nether ───────────────────────────────────────────────────────────────────────────
         case "nether_wastes" -> 0xFF8A2626;
         case "soul_sand_valley" -> 0xFF5A4A3C;
         case "crimson_forest" -> 0xFFB4222E;
         case "warped_forest" -> 0xFF1B7A78;
         case "basalt_deltas" -> 0xFF4A4650;
         // ── End ──────────────────────────────────────────────────────────────────────────────
         case "the_end" -> 0xFF2A2438;
         case "end_highlands" -> 0xFFD8D6A8;
         case "end_midlands" -> 0xFFC2C094;
         case "end_barrens" -> 0xFF8E8C70;
         case "small_end_islands" -> 0xFF3A3450;
         default -> UNKNOWN;
      };
   }

   /** True when this biome has a colour of its own rather than the "don't know" grey. Exists so
    *  {@code mapDiag} can walk the live biome registry and report anything the table has missed —
    *  the same check {@code iconDiag} does for the picker's icons, for the same reason. */
   public static boolean known(String path) {
      return of(path) != UNKNOWN;
   }

   private BiomeTint() {
   }
}
