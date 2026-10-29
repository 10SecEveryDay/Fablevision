package com.fablevision.client.seedfinder;

/**
 * What fits on the action bar (1.46.2).
 *
 * The action bar is ONE line, drawn centred at its full width and never wrapped, so anything wider than
 * the window is simply cut off at both edges. The N-key refusal on a server was the whole sentence from
 * {@link SeedAccess} — about 470 pixels in the game's font — and lost both ends on any window narrower
 * than that, i.e. most of them. The action bar gets a short wording now; the screen measures it with the
 * game's font against the window it is really drawn in, and anything that still does not fit goes to
 * chat, which wraps. layoutDiag checks every short wording against the narrowest window the game makes.
 *
 * The long sentences stay in SeedAccess untouched; this only chooses what to SAY on the bar.
 */
public final class ActionBar {

   /** Kept clear at each side of the window. */
   public static final int MARGIN = 8;
   /** The narrowest GUI the game hands out at its own scale (it lowers the scale until the GUI is 320 wide). */
   public static final int MIN_GUI_W = 320;

   /** The in-world map's refusals, short enough for the bar, by the sentence SeedAccess gives. */
   public static final String MAP_OWN_WORLD_SHORT = "The map only opens in your own single-player world";
   public static final String MAP_DEFAULT_WORLD_SHORT = "The map only shows Default world type worlds";

   private ActionBar() {
   }

   /** The action-bar wording for a refusal; an unknown one is returned as it is (and goes to chat if long). */
   public static String shortFor(String problem) {
      if (SeedAccess.MAP_OWN_WORLD_ONLY.equals(problem)) {
         return MAP_OWN_WORLD_SHORT;
      }
      if (SeedAccess.MAP_DEFAULT_WORLD_ONLY.equals(problem)) {
         return MAP_DEFAULT_WORLD_SHORT;
      }
      return problem == null ? "" : problem;
   }

   /** Every short wording this class can put on the bar, for layoutDiag. */
   public static String[] all() {
      return new String[]{MAP_OWN_WORLD_SHORT, MAP_DEFAULT_WORLD_SHORT};
   }

   /** True when text {@code textWidth} pixels wide fits a bar in a GUI {@code guiWidth} pixels wide. */
   public static boolean fits(int textWidth, int guiWidth) {
      return textWidth <= guiWidth - 2 * MARGIN;
   }
}
