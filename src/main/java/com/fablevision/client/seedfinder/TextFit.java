package com.fablevision.client.seedfinder;

/**
 * How wide a piece of text can possibly be, without a font.
 *
 * WHY THIS EXISTS. Every text overflow this project has shipped was found by a person looking at a
 * screen, and the reason is that the check needs {@code Font}, which needs the game's loaded
 * resources, which means it cannot run in a diagnostic. So the layout was verified at 360 window
 * sizes and the TEXT INSIDE the layout was verified at whatever size the developer's window
 * happened to be — which is how "64 blocks is tight — most seeds will f…" and a stopped-search
 * message drawn through the Must-have button both reached a player.
 *
 * The way out is to stop asking for the exact width and ask for the WORST CASE. Minecraft's default
 * font is at most 6 pixels per glyph including the one-pixel gap (most letters are 6; i, l, ., ,, !
 * and the like are narrower; nothing in the ASCII range is wider). So {@code chars x 6} is an upper
 * bound on the real width, and a string that fits by this measure fits in the game, at every GUI
 * scale, on every machine, without anyone opening it.
 *
 * IT IS PESSIMISTIC AND THAT IS THE POINT. A string this rejects might have fitted — the bound is
 * about 15% loose on ordinary prose. The alternative is a check that passes strings which then get
 * truncated on somebody else's window, and between "occasionally asks for a shorter sentence" and
 * "occasionally ships an ellipsis" the choice is not close.
 *
 * The screen still measures with the real {@code Font} at draw time; this is only for asserting,
 * ahead of time, that the fixed strings CANNOT overflow.
 */
public final class TextFit {

   /** Widest any glyph of the default font gets, including its trailing pixel of spacing. */
   public static final int MAX_GLYPH_W = 6;

   /**
    * The widest this string could be drawn, in pixels, ignoring §-codes (which draw nothing).
    *
    * Note what is NOT handled: §l (bold) adds a pixel per glyph. Nothing measured by this file uses
    * it, and a bold string would have to be treated as 7 — recorded here so the next person adding
    * one does not discover it on a screenshot.
    */
   public static int worstWidth(String s) {
      if (s == null || s.isEmpty()) {
         return 0;
      }
      int glyphs = 0;
      for (int i = 0; i < s.length(); i++) {
         if (s.charAt(i) == '§') {
            i++;   // skip the code that follows; the pair draws nothing
            continue;
         }
         glyphs++;
      }
      return glyphs * MAX_GLYPH_W;
   }

   /** Can this string be drawn in {@code px} pixels without being cut off, on any window? */
   public static boolean fits(String s, int px) {
      return worstWidth(s) <= px;
   }

   /** How many characters of visible text fit in {@code px} — what a message has to be shortened
    *  to. Used by the diagnostic's report so a failure says how much has to go. */
   public static int maxChars(int px) {
      return Math.max(0, px / MAX_GLYPH_W);
   }

   private TextFit() {
   }
}
