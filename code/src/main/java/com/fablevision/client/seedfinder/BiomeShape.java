package com.fablevision.client.seedfinder;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * What each biome REALLY measures, and how far apart two things are when a player would call them
 * neighbours — the numbers the size-tier and "next to" features are built on.
 *
 * Every figure below came out of {@code gradlew biomeShape --args="60 all"}, which walks the very
 * same {@link SeedCriteria#biomeSpan} the search runs, over 60 seeds, looking within 2000 blocks of
 * 0,0. Nothing here is estimated, and a biome nobody measured is simply absent — the picker then
 * offers no size control for it rather than inventing a threshold, exactly as
 * {@link SeedCriteria.StructureTarget#rarityPct} prints nothing rather than a plausible guess.
 *
 * WHY THE THRESHOLDS ARE PER BIOME. A single global "big" is not possible: the median pale garden
 * spans 32 blocks and the median warm ocean spans 480, so any one number either accepts every warm
 * ocean or rejects every pale garden. {@link #smallMax} is that biome's own p33 and {@link #largeMin}
 * its own p67, so "big" always means "big for a pale garden" and always lands near the top third.
 *
 * WHAT IS DELIBERATELY MISSING. Six overworld biomes measured 32 small / 64 large with a maximum
 * barely above that — river, frozen river, beach, snowy beach, stony shore and jagged peaks are
 * effectively one size, so they carry no tier at all. A control that cannot change the answer is
 * worse than no control: it reads as a filter and filters nothing.
 *
 * Spans are quantised to 32 blocks because the measuring ray steps by 32. That is the floor on how
 * fine a tier can be and the reason there are three tiers rather than five.
 */
public final class BiomeShape {

   /** Which end of a biome's own size distribution the player asked for. */
   public enum Size { ANY, SMALL, LARGE }

   /**
    * One biome's measurement.
    *
    * @param nearPct  seeds out of 60 (as a percentage) with this biome anywhere within 2000 blocks
    *                 of 0,0 — the honest "how often is this even an option" number
    * @param smallMax p33 of the measured spans: at or under this is SMALL for this biome
    * @param largeMin p67: at or over this is LARGE. 0 in both when the bands collapsed and no
    *                 tier is offered
    */
   public record Shape(int nearPct, int smallMax, int largeMin) {
      /** True when this biome has a usable size control at all. */
      public boolean tiered() {
         return smallMax > 0 && largeMin > smallMax;
      }

      /**
       * True when "big X" is a weak filter — the two bands sit within a ray step or two of each
       * other, so a large one is barely distinguishable from a small one on the ground.
       *
       * Derived from the measurement rather than listed, and it currently catches exactly one
       * shipped biome: the pale garden, whose three measured sizes are 32, 64 and 96.
       */
      public boolean weakSize() {
         return tiered() && largeMin <= 64;
      }
   }

   /**
    * Measured spans, one line per biome: near-spawn rate, then this biome's own small/large cut.
    *
    * Order follows {@link SeedCatalog}'s biome list so the two can be read side by side. Nether and
    * End biomes are absent on purpose — the measurement only covered the overworld, and a tier
    * invented for the basalt deltas would be exactly the kind of plausible-looking number this
    * project keeps refusing to print.
    */
   private static final Map<String, Shape> SHAPES = new LinkedHashMap<>();

   static {
      // ── flat plains-likes and forests ────────────────────────────────────────────────────────
      put("plains", 100, 160, 256);
      put("sunflower_plains", 78, 128, 160);
      put("forest", 100, 160, 224);
      put("flower_forest", 83, 128, 224);
      put("birch_forest", 98, 96, 192);
      put("old_growth_birch_forest", 98, 128, 256);
      put("dark_forest", 95, 160, 224);
      put("cherry_grove", 58, 64, 96);
      // 23 of 60 seeds, three measured sizes (32/64/96). Both facts matter and both are warned
      // about: the biome itself is uncommon near spawn AND its size control barely separates.
      put("pale_garden", 38, 32, 64);
      put("taiga", 93, 96, 160);
      put("snowy_taiga", 65, 96, 224);
      put("old_growth_pine_taiga", 75, 64, 192);
      put("old_growth_spruce_taiga", 75, 96, 160);
      // ── mountains and cold ───────────────────────────────────────────────────────────────────
      put("meadow", 92, 64, 96);
      put("grove", 80, 64, 96);
      put("snowy_slopes", 65, 32, 96);
      // jagged_peaks: 32/64, essentially one size — rate only, no tier.
      put("jagged_peaks", 53, 0, 0);
      put("frozen_peaks", 53, 32, 96);
      put("stony_peaks", 38, 64, 96);
      put("ice_spikes", 48, 64, 160);
      put("snowy_plains", 65, 160, 256);
      // ── wet and warm ─────────────────────────────────────────────────────────────────────────
      put("swamp", 63, 160, 288);
      put("mangrove_swamp", 32, 128, 224);
      put("jungle", 87, 128, 192);
      put("sparse_jungle", 87, 96, 160);
      put("bamboo_jungle", 72, 96, 160);
      put("desert", 57, 160, 224);
      put("savanna", 92, 160, 256);
      put("savanna_plateau", 63, 64, 96);
      put("windswept_savanna", 60, 96, 128);
      put("badlands", 52, 96, 224);
      put("wooded_badlands", 42, 160, 256);
      put("eroded_badlands", 42, 96, 160);
      // ── oceans — the biggest spans in the game, which is why one global threshold cannot work ─
      put("ocean", 100, 192, 288);
      put("deep_ocean", 93, 288, 512);
      put("frozen_ocean", 62, 160, 352);
      put("deep_frozen_ocean", 48, 192, 480);
      put("cold_ocean", 95, 192, 320);
      put("deep_cold_ocean", 87, 288, 416);
      put("lukewarm_ocean", 93, 224, 416);
      put("deep_lukewarm_ocean", 85, 192, 352);
      put("warm_ocean", 52, 288, 608);
      // ── edges: all measured 32/64, all rate-only ─────────────────────────────────────────────
      put("river", 100, 0, 0);
      put("frozen_river", 60, 0, 0);
      put("beach", 100, 0, 0);
      put("snowy_beach", 53, 0, 0);
      put("stony_shore", 100, 0, 0);
      // ── caves — RE-MEASURED UNDERGROUND in 1.44.2 ─────────────────────────────────────────────
      // These three were measured at Y=64 like every other biome, which is not where they are. Deep
      // Dark came out at "3 seeds in 60", and this comment called that "a very long search" — the
      // search was sampling the surface, where Deep Dark does not generate. Measured again at each
      // biome's own heights (SeedCriteria.biomeHeights), with biomeShape using the search's locator:
      // all three are within 2000 blocks in every seed, and their patches are far larger than the
      // surface slivers the old measurement caught. Old values: 5/64/128, 98/160/288, 85/96/192.
      put("deep_dark", 100, 160, 384);
      put("dripstone_caves", 100, 192, 480);
      put("lush_caves", 100, 288, 512);
   }

   private static void put(String path, int nearPct, int smallMax, int largeMin) {
      SHAPES.put(path, new Shape(nearPct, smallMax, largeMin));
   }

   /** The measurement for a biome, or null when it was never measured (Nether, End, modded). */
   public static Shape of(String path) {
      return path == null ? null : SHAPES.get(path.toLowerCase(Locale.ROOT));
   }

   public static Shape of(Identifier id) {
      return id == null ? null : of(id.getPath());
   }

   /** True when this biome has a size control worth showing. */
   public static boolean tiered(Identifier id) {
      Shape s = of(id);
      return s != null && s.tiered();
   }

   /** The span requirement a size tier means for this biome: {@code {minSpan, maxSpan}}, either
    *  entry 0 for "no limit". ANY, an unmeasured biome or a flat one all give no limits at all. */
   public static int[] bounds(String path, Size size) {
      Shape s = of(path);
      if (s == null || !s.tiered() || size == Size.ANY) {
         return new int[]{0, 0};
      }
      return size == Size.LARGE ? new int[]{s.largeMin(), 0} : new int[]{0, s.smallMax()};
   }

   // ── "Next to" ────────────────────────────────────────────────────────────────────────────────

   /**
    * Default reach for "X next to Y", measured rather than chosen.
    *
    * {@code biomeShape --args="60 adjacent"} found the nearest patch of B to a given A, over seven
    * pairs. The p75 of that distance clustered around 180-290 blocks for biome pairs (pale garden ↔
    * dark forest 181, plains ↔ forest 286, ice spikes ↔ snowy plains 181) and 435-1194 for
    * biome ↔ structure pairs (cherry grove ↔ ruined portal 435, cherry grove ↔ village 862).
    * Structures are sparser than biomes, so they need the wider default.
    *
    * The same run killed the cheap implementation. Measuring adjacency from the positions the finder
    * already holds — each found by searching outward from SPAWN — was wrong on every pair by 2x to
    * 8x, because the patch nearest the player is not the patch nearest the other thing. Adjacency
    * therefore runs its own search, anchored on the biome, and that is the cost it carries.
    */
   public static final int NEXT_TO_BIOME = 256;
   public static final int NEXT_TO_STRUCTURE = 512;

   /** Distances the picker offers, tightest first. 64 is the floor because the biome grid is
    *  sampled in 4-block cells and a smaller answer would be finer than the data. */
   public static final int[] NEXT_TO_CHOICES = {64, 100, 128, 192, 256, 384, 512, 768, 1024};

   /** Under this, a "next to" is tighter than the closest MEDIAN any measured pair managed (181
    *  blocks), so most seeds will fail it. Not forbidden — warned about. */
   public static final int TIGHT = 192;

   public static int clampNextTo(int blocks) {
      return Math.max(NEXT_TO_CHOICES[0], Math.min(NEXT_TO_CHOICES[NEXT_TO_CHOICES.length - 1], blocks));
   }

   /**
    * True when "A next to B" barely narrows anything, because both biomes are near spawn in almost
    * every seed anyway.
    *
    * Derived from the per-biome near-spawn rates above rather than from a table of pairs, since only
    * seven pairs were ever measured and there are thousands. It catches the case the measurement
    * actually found: plains and forest are both within reach in 60 of 60 seeds and typically 181
    * blocks apart, so "plains next to forest" is true of very nearly every seed in the game.
    */
   public static boolean weakPair(String aPath, String bPath) {
      Shape a = of(aPath);
      Shape b = of(bPath);
      return a != null && b != null && a.nearPct() >= 90 && b.nearPct() >= 90;
   }

   /** How finely the "next to" search steps. Tight requests need a finer grid than the 32 the
    *  spawn-wide search uses, or a 100-block answer would be rounded to the nearest 32. */
   static int stepFor(int within) {
      return Math.max(8, Math.min(32, within / 8));
   }

   private BiomeShape() {
   }
}
