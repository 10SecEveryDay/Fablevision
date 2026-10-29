package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Every rectangle of the custom-spawn screen while it is finding, or showing, SEVERAL worlds (1.46.0) —
 * worked out in one place from the window size, like {@link WishLayout}, and for the same reason: so
 * {@code layoutDiag} can build it at every window size and check it without a game.
 *
 * One column, top to bottom: the title and the "real seeds" line (where the single search draws them),
 * a band of status lines, the list of worlds, the feedback band, and the bottom buttons. The list takes
 * what is left, so the status band shrinks before the list does — on the smallest window the list still
 * shows one world's seed, its buttons and a line under it, and scrolls for the rest.
 *
 * The list itself is rows ({@link #rows}): a world is a 20-pixel head (its number, its seed, and the Use
 * / Copy / Map buttons) followed by its find lines, wrapped to the column, 12 pixels each. It scrolls by
 * row, and only rows that fit WHOLE are drawn, so no button is ever drawn half outside the list.
 */
public record ResultsLayout(int width, int height, int panelX, int panelY, int panelW, int panelH,
                            int contentX, int contentW, int titleY, int subtitleY,
                            int statusY, int statusLines, int listY, int listBottom, int scrollX,
                            int useX, int useW, int copyX, int copyW, int mapX, int mapW, boolean shortLabels,
                            int bottomY, int feedbackY, int feedbackLines, boolean tooSmall) {

   /** The line under the title on every searching view — single or several — kept here so layoutDiag
    *  measures the string the screen draws. */
   public static final String SUBTITLE = "§7Real seeds from the game's own world-gen — you spawn naturally, "
         + "nothing is moved or spawned in.";

   public static final int LINE = 12;
   /** A world's head row: 18-pixel buttons and two pixels under them. */
   public static final int HEAD_H = 20;
   /** Extra space after a world's last line, so worlds read as separate things. */
   public static final int GAP_AFTER = 4;
   /** The least the list may have: one head and one line under it. */
   public static final int MIN_LIST_H = HEAD_H + LINE;
   /** The find lines start this far in from the seed. */
   public static final int INDENT = 10;
   public static final int SCROLL_W = 4;
   public static final int BTN_GAP = 3;
   /** Where the status band starts — where the single search's status starts. */
   private static final int STATUS_OFFSET = SearchingText.STATUS_OFFSET;
   /** Status lines while SEARCHING (the wish, progress, the estimate, notes) and on the RESULTS view. */
   public static final int SEARCHING_STATUS = 6;
   public static final int RESULTS_STATUS = 4;

   /** The three buttons on a world's head. Long labels where they fit, short ones where they do not;
    *  layoutDiag measures every label with the game's font against these widths. */
   public static final int USE_W = 84;
   public static final int COPY_W = 62;
   public static final int MAP_W = 74;
   public static final int USE_SHORT_W = 30;
   public static final int COPY_SHORT_W = 36;
   public static final int MAP_SHORT_W = 30;
   /** Room the head keeps for "#10  -9223372036854775808", the widest number-and-seed there is. */
   public static final int HEAD_TEXT_W = 146;

   public static final String[] USE = {"Use this seed", "Use"};
   public static final String[] USE_APPLIED = {"✓ In use", "✓"};
   public static final String[] COPY = {"Copy seed", "Copy"};
   public static final String[] MAP = {"View on map", "Map"};
   /** The RESULTS view's bottom buttons, longest wording first. */
   public static final String[] BACK = {"◂ Back to wish", "◂ Wish", "◂"};
   public static final String[] COPY_ALL = {"Copy all", "Copy all"};
   public static final String[] DONE = {"Done"};

   /** A label for the slot: the long wording, or the short one when the layout chose short labels. */
   public String label(String[] wordings) {
      return shortLabels ? wordings[wordings.length - 1] : wordings[0];
   }

   /**
    * The layout for this window, told how many lines the feedback message needs (0 = no message) and how
    * many status lines this view wants. Like {@link WishLayout#of}, the feedback band gives up lines
    * before the list gives up its last row.
    */
   public static ResultsLayout of(int width, int height, int feedbackLines, int maxStatus) {
      int want = Math.max(0, Math.min(WishLayout.MAX_FEEDBACK_LINES, feedbackLines));
      for (int n = want; n > 1; n--) {
         ResultsLayout candidate = build(width, height, n, maxStatus);
         if (!candidate.tooSmall()) {
            return candidate;
         }
      }
      return build(width, height, Math.min(want, 1), maxStatus);
   }

   private static ResultsLayout build(int width, int height, int fb, int maxStatus) {
      // The panel and its floor are the picker's, so switching views does not move the frame.
      WishLayout base = WishLayout.of(width, height, 0, 1);
      int panelX = base.panelX();
      int panelY = base.panelY();
      int panelW = base.panelW();
      int panelH = base.panelH();
      int bottomY = base.bottomY();
      int contentX = panelX + 10;
      int contentW = panelW - 20;
      int titleY = panelY + 10;
      int subtitleY = panelY + 24;
      int statusY = panelY + STATUS_OFFSET;

      int feedbackY = fb > 0 ? bottomY - LINE * fb - 2 : bottomY;
      int floor = feedbackY - 4;
      int statusLines = Math.max(1, Math.min(maxStatus, (floor - statusY - 4 - MIN_LIST_H) / LINE));
      int listY = statusY + statusLines * LINE + 4;
      int listBottom = floor;
      boolean tooSmall = listBottom - listY < MIN_LIST_H;

      boolean shortLabels = contentW - (USE_W + COPY_W + MAP_W + 2 * BTN_GAP) - 4 < HEAD_TEXT_W;
      int mapW = shortLabels ? MAP_SHORT_W : MAP_W;
      int copyW = shortLabels ? COPY_SHORT_W : COPY_W;
      int useW = shortLabels ? USE_SHORT_W : USE_W;
      int mapX = contentX + contentW - mapW;
      int copyX = mapX - BTN_GAP - copyW;
      int useX = copyX - BTN_GAP - useW;
      // In the panel's right padding, clear of the Map buttons and inside the border.
      int scrollX = panelX + panelW - 3 - SCROLL_W;
      return new ResultsLayout(width, height, panelX, panelY, panelW, panelH, contentX, contentW, titleY,
            subtitleY, statusY, statusLines, listY, listBottom, scrollX, useX, useW, copyX, copyW, mapX, mapW,
            shortLabels, bottomY, feedbackY, fb, tooSmall);
   }

   /** Where the status band must stop: the list starts four pixels under it. */
   public int statusBottom() {
      return statusY + statusLines * LINE;
   }

   /** The pixels the head has for "#n  seed": from the left edge to a gap before the Use button. */
   public int headTextW() {
      return useX - 4 - contentX;
   }

   /** The width a find line is wrapped to. */
   public int lineW() {
      return contentW - INDENT;
   }

   /** The RESULTS view's four bottom buttons — Back, Copy all, Coords, Done — as {x0, w0, … x3, w3}. */
   public int[] bottomBand() {
      int w = (contentW - 3 * 4) / 4;
      int[] out = new int[8];
      for (int i = 0; i < 4; i++) {
         out[i * 2] = contentX + i * (w + 4);
         out[i * 2 + 1] = i == 3 ? contentX + contentW - out[i * 2] : w;
      }
      return out;
   }

   /** The SEARCHING view's Stop button, where the single search has it: {x, w}. On the content edge, like
    *  every line above it (1.46.2; it was centred while the text was not). */
   public int[] stopButton() {
      return new int[]{contentX, Math.min(STOP_W, contentW)};
   }

   public static final int STOP_W = 160;

   // ── The list, as rows ─────────────────────────────────────────────────────────────────────────────

   /** One row of the list: a world's head ({@code line} -1) or one wrapped line of its finds. */
   public record Row(int world, int line, String text, int height) {
      public boolean head() {
         return line < 0;
      }
   }

   /** The rows for these worlds, each given as its lines already wrapped to {@link #lineW()}. */
   public static List<Row> rows(List<List<String>> wrappedPerWorld) {
      List<Row> out = new ArrayList<>();
      for (int i = 0; i < wrappedPerWorld.size(); i++) {
         List<String> lines = wrappedPerWorld.get(i);
         boolean last = i == wrappedPerWorld.size() - 1;
         int gap = last ? 0 : GAP_AFTER;
         out.add(new Row(i, -1, null, HEAD_H + (lines.isEmpty() ? gap : 0)));
         for (int j = 0; j < lines.size(); j++) {
            out.add(new Row(i, j, lines.get(j), LINE + (j == lines.size() - 1 ? gap : 0)));
         }
      }
      return out;
   }

   /** How many rows, from {@code from}, fit WHOLE in {@code listH} pixels. */
   public static int fitting(List<Row> rows, int from, int listH) {
      int used = 0;
      int n = 0;
      for (int i = Math.max(0, from); i < rows.size(); i++) {
         Row r = rows.get(i);
         // The gap after a world's last row may hang below the list: it is empty space, not content.
         int content = r.head() ? HEAD_H : LINE;
         if (used + content > listH) {
            break;
         }
         used += r.height();
         n++;
      }
      return n;
   }

   /** The furthest the list can scroll: the first row from which every remaining row fits. */
   public static int maxScroll(List<Row> rows, int listH) {
      for (int from = 0; from < rows.size(); from++) {
         if (from + fitting(rows, from, listH) >= rows.size()) {
            return from;
         }
      }
      return 0;
   }

   /**
    * Greedy word wrap with the game's font widths, carrying the colour code in force onto each new line
    * so a wrapped grey line stays grey. A word wider than the line is cut by characters. Used by the
    * screen to draw and by layoutDiag to check, so the two cannot disagree about how many lines it takes.
    */
   public static List<String> wrap(String text, int px, ToIntFunction<String> width) {
      List<String> out = new ArrayList<>();
      if (text == null || text.isEmpty()) {
         return out;
      }
      String[] words = text.split(" ");
      StringBuilder line = new StringBuilder();
      String colour = "";
      for (String word : words) {
         String candidate = line.length() == 0 ? colour + word : line + " " + word;
         if (width.applyAsInt(candidate) <= px || line.length() == 0 && width.applyAsInt(colour + word) <= px) {
            line.setLength(0);
            line.append(candidate);
         } else if (line.length() > 0 && width.applyAsInt(colour(line.toString(), colour) + word) <= px) {
            out.add(line.toString());
            colour = colour(line.toString(), colour);
            line.setLength(0);
            line.append(colour).append(word);
         } else {
            // A word that does not fit a line on its own: finish the current line, then cut the word.
            if (line.length() > 0) {
               out.add(line.toString());
               colour = colour(line.toString(), colour);
               line.setLength(0);
            }
            String rest = word;
            while (!rest.isEmpty()) {
               int n = rest.length();
               while (n > 1 && width.applyAsInt(colour + rest.substring(0, n)) > px) {
                  n--;
               }
               String piece = rest.substring(0, n);
               rest = rest.substring(n);
               if (rest.isEmpty()) {
                  line.append(colour).append(piece);
               } else {
                  out.add(colour + piece);
                  colour = colour(colour + piece, colour);
               }
            }
         }
      }
      if (line.length() > 0) {
         out.add(line.toString());
      }
      return out;
   }

   /** The last "§x" colour code in {@code s}, or {@code fallback} if it has none. */
   private static String colour(String s, String fallback) {
      int at = s.lastIndexOf('§');
      while (at >= 0) {
         if (at + 1 < s.length()) {
            return s.substring(at, at + 2);
         }
         at = s.lastIndexOf('§', at - 1);
      }
      return fallback;
   }
}
