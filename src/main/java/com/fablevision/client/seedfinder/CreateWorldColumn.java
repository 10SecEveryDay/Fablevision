package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * WHERE THE FINDER'S BUTTONS GO ON VANILLA'S CREATE WORLD SCREEN (1.46.2): a pure function of the window
 * size and the rectangles vanilla's own widgets occupy.
 *
 * WHY. Up to 1.46.1 the buttons sat at fixed offsets from the top-right corner (102 wide at x = width - 108,
 * the seed-count badge 152 wide at width - 158), chosen on a maximised window. On a smaller window vanilla's
 * centred columns reach that corner: the badge was drawn on top of the Game Mode button, and the column
 * over the World tab's Customize button and the tab bar. Nothing measured vanilla, so nothing noticed.
 *
 * Now the in-game screen hands this the REAL bounds of every vanilla widget — the tab buttons, the widgets
 * of all three tabs (World is the widest, 310), the footer — and this tries places and sizes in order until
 * one touches none of them:
 *
 *   1. the column in the top-right corner, then just under the tab bar — full labels, then short ones
 *      ("✨ Spawn"), then icons;
 *   2. a row along the bottom of the tab area, above Create / Cancel, for the narrowest windows;
 *   3. icons side by side in the header beside the tabs;
 *   4. FEWER: the Custom spawn button alone, in the same places;
 *   5. nothing — never a button on top of vanilla's. layoutDiag requires both buttons at every window size
 *      the game can really produce (320x240 and up).
 *
 * The found seed's count line ("✓ 318 seeds · under a second") goes under the column when it fits there,
 * and into the map button's tooltip when it does not — said once, never twice.
 */
public final class CreateWorldColumn {

   /** A rectangle in screen pixels. */
   public record Box(int x, int y, int w, int h) {
      public int right() {
         return x + w;
      }

      public int bottom() {
         return y + h;
      }

      /** True when the two are closer than {@code gap} pixels (0 = only a real overlap). */
      public boolean near(Box o, int gap) {
         return x < o.x + o.w + gap && o.x < x + w + gap && y < o.y + o.h + gap && o.y < y + h + gap;
      }
   }

   public enum Tier { FULL, SHORT, ICON }

   /** The two buttons' labels by tier, longest first. */
   public static final String[] SPAWN = {"✨ Custom spawn", "✨ Spawn", "✨"};
   public static final String[] MAP = {"🗺 Seed map", "🗺 Map", "🗺"};

   /** The full-label width, as since 1.40: both labels fit it with room (layoutDiag measures them). */
   public static final int FULL_W = 102;
   public static final int ICON_W = 20;
   public static final int BTN_H = 20;
   /** From the window's right edge. */
   public static final int EDGE = 4;
   /** Between our two buttons. */
   public static final int GAP = 2;
   /** The least distance from any vanilla widget. */
   public static final int CLEAR = 2;
   /** The count line's height: one line of the game's font. */
   public static final int TEXT_H = 9;

   /**
    * Where everything went. {@code spawn} is null when even one icon fits nowhere; {@code map} is null when
    * only the Custom spawn button found room; {@code stats} is null when the count line is not drawn (none to
    * draw, or no room — then it belongs in the map button's tooltip).
    */
   public record Placement(Tier tier, String where, Box spawn, Box map, Box stats,
                           String spawnLabel, String mapLabel) {
      public boolean nothing() {
         return spawn == null;
      }
   }

   private CreateWorldColumn() {
   }

   /**
    * @param headerBottom where the tab bar ends (24 in 26.3)
    * @param footerTop    where the footer band starts (height - 33 in 26.3)
    * @param vanilla      every vanilla widget's rectangle, of all three tabs
    * @param stats        the count line to draw, or null when no found seed is in the box
    * @param textW        the game's text width
    */
   public static Placement place(int width, int height, int headerBottom, int footerTop, List<Box> vanilla,
                                 String stats, ToIntFunction<String> textW) {
      int shortW = Math.max(textW.applyAsInt(SPAWN[1]), textW.applyAsInt(MAP[1])) + 10;
      int[] widths = {FULL_W, shortW, ICON_W};
      Tier[] tiers = Tier.values();
      for (boolean both : new boolean[]{true, false}) {
         // The corner first, in every size, so the buttons stay where people look for them…
         for (int t = 0; t < tiers.length; t++) {
            for (int top : new int[]{2, headerBottom + 4}) {
               Placement p = column(width, height, top, widths[t], tiers[t], both, vanilla, stats, textW,
                     top == 2 ? "top right" : "right, under the tabs");
               if (p != null) {
                  return p;
               }
            }
         }
         // …then a row above Create / Cancel, then icons in the header.
         for (int t = 0; t < tiers.length; t++) {
            Placement p = row(width, height, footerTop - 4 - BTN_H, widths[t], tiers[t], both, vanilla, stats, textW,
                  "row above the footer");
            if (p != null) {
               return p;
            }
         }
         Placement p = row(width, height, 2, ICON_W, Tier.ICON, both, vanilla, stats, textW, "row in the header");
         if (p != null) {
            return p;
         }
      }
      return new Placement(Tier.ICON, "nowhere", null, null, null, null, null);
   }

   private static Placement column(int width, int height, int top, int w, Tier tier, boolean both,
                                   List<Box> vanilla, String stats, ToIntFunction<String> textW, String where) {
      int x = width - EDGE - w;
      Box spawn = new Box(x, top, w, BTN_H);
      Box map = both ? new Box(x, top + BTN_H + GAP, w, BTN_H) : null;
      if (!free(spawn, width, height, vanilla) || map != null && !free(map, width, height, vanilla)) {
         return null;
      }
      Box statsBox = null;
      if (stats != null && map != null) {
         int tw = textW.applyAsInt(stats);
         Box under = new Box(x + w - tw, map.bottom() + 3, tw, TEXT_H);
         statsBox = free(under, width, height, vanilla) ? under : null;
      }
      return new Placement(tier, where, spawn, map, statsBox, SPAWN[tier.ordinal()], MAP[tier.ordinal()]);
   }

   private static Placement row(int width, int height, int y, int w, Tier tier, boolean both,
                                List<Box> vanilla, String stats, ToIntFunction<String> textW, String where) {
      Box map = both ? new Box(width - EDGE - w, y, w, BTN_H) : null;
      Box spawn = new Box((map != null ? map.x() - GAP : width - EDGE) - w, y, w, BTN_H);
      if (!free(spawn, width, height, vanilla) || map != null && !free(map, width, height, vanilla)) {
         return null;
      }
      Box statsBox = null;
      if (stats != null && map != null) {
         int tw = textW.applyAsInt(stats);
         Box left = new Box(spawn.x() - 6 - tw, y + (BTN_H - TEXT_H) / 2 + 1, tw, TEXT_H);
         statsBox = free(left, width, height, vanilla) ? left : null;
      }
      return new Placement(tier, where, spawn, map, statsBox, SPAWN[tier.ordinal()], MAP[tier.ordinal()]);
   }

   /** On screen, and at least {@link #CLEAR} pixels from every vanilla widget. */
   static boolean free(Box b, int width, int height, List<Box> vanilla) {
      if (b.x() < 0 || b.y() < 0 || b.right() > width || b.bottom() > height) {
         return false;
      }
      for (Box v : vanilla) {
         if (b.near(v, CLEAR)) {
            return false;
         }
      }
      return true;
   }

   /** Everything placed, for a check: the two buttons and the count line, whichever exist. */
   public static List<Box> placed(Placement p) {
      List<Box> out = new ArrayList<>(3);
      for (Box b : new Box[]{p.spawn(), p.map(), p.stats()}) {
         if (b != null) {
            out.add(b);
         }
      }
      return out;
   }
}
