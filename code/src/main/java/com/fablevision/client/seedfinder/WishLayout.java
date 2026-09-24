package com.fablevision.client.seedfinder;

/**
 * Every rectangle on the custom-spawn screen, worked out in ONE place from the window size.
 *
 * It exists because the screen had its coordinates scattered across {@code init()} and
 * {@code render()} as a mix of constants counted down from the top (110, 86, 64) and constants
 * counted up from the bottom (height-116, height-86, height-46). Two sets of magic numbers growing
 * towards each other from opposite ends of the window is fine on a big monitor and collides on a
 * small one — which is exactly where the overlapping text showed up. Nothing in the middle knew how
 * much room anything else had taken.
 *
 * So the bands are now derived in order and each one is told where the previous one ended. That
 * makes the whole layout a pure function of (width, height), which in turn makes it TESTABLE without
 * a game: {@code gradlew layoutDiag} builds this at two dozen window sizes and asserts that no two
 * bands overlap and nothing escapes the panel. A layout bug that only appears at 320x240 is not
 * something to find by resizing a Minecraft window by hand.
 *
 * All fields are absolute screen pixels, and every "…Bottom" is EXCLUSIVE — the first pixel the
 * band no longer owns — so a band and the next one may share a coordinate without overlapping.
 */
public record WishLayout(
      int width, int height,
      int panelX, int panelY, int panelW, int panelH,
      int titleY, int modeX, int modeW, int modeY, int subX, int subW,
      int wishX, int wishY, int wishW, int aiX, int aiW, int keyX, int keyW,
      int leftX, int leftW, int rightX, int rightW,
      int filterY, int tabsY, int listY, int listBottom, int rowH, int visibleRows,
      int scrollX, int listCountY,
      int rightHeaderY, int rightListY, int rightListBottom, int pickH,
      int linkY, int visibleLinks, int toggleY, int bottomY, int feedbackY, int feedbackLines,
      boolean tooSmall) {

   /** Widest the whole thing is allowed to get. Past this it stops being a dialog and becomes a
    *  page: the eye has to travel the width of a 32-inch monitor to get from a row to the pick it
    *  just made, and the two columns lose any sense of belonging together. */
   public static final int PANEL_MAX_W = 640;
   /** Below this the two columns cannot both hold readable text, so the panel stops shrinking and
    *  is simply clipped by the window — better a panel with its edges off-screen than two columns
    *  of five characters each. */
   public static final int PANEL_MIN_W = 340;
   public static final int PANEL_MIN_H = 210;

   private static final int OUTER = 10;
   private static final int PAD = 10;
   private static final int GAP = 8;
   private static final int LINE = 12;
   private static final int BTN = 20;
   private static final int ROW_H = 28;
   /**
    * A chosen row is 30 tall, not 26, and the four pixels are the fix for a real overlap.
    *
    * The row draws up to four 18-pixel buttons across its top (size or count, spawn-inside, link,
    * remove) and two lines of text. At 26 the second line sat at +14, which is INSIDE the button
    * band — so the sentence under a pick was drawn through the buttons beside it, and the only
    * defence was reserving 62 pixels of width, which on a narrow window left the sentence ten
    * characters. Dropping the line below the buttons costs four pixels of row height and buys back
    * the whole column width, exactly as the same change did for the "next to" rows.
    */
   private static final int PICK_H = 30;
   /** Width of the scrollbar gutter down the right of the browse list. */
   public static final int SCROLL_W = 5;
   /** The Mode button: its longest label, "Mode: 🎯Exact ▸ Fast", is 98 px in the game's font. */
   public static final int MODE_W = 110;
   /** The Within button: its longest label, "Within 1000", is 54 px. */
   public static final int SUB_W = 62;

   /**
    * The most lines the feedback line is allowed to grow to.
    *
    * RAISED FROM THREE TO FOUR IN 1.41.4, because three was not enough for the longest message the
    * screen can produce. The refusal for a pair the world can never build ("these two are placed by
    * the same rule…") runs past 150 characters, and on a half-screen window three lines cut it off —
    * which is the failure this whole band exists to prevent. Four lines covers every fixed message
    * this screen has, and {@code layoutDiag} is what says so rather than a count done by eye.
    */
   public static final int MAX_FEEDBACK_LINES = 4;

   /**
    * THE TITLE, from longest to shortest. Every wording keeps "NEW world": the title's job is to say
    * which world is being acted on, and that ambiguity is what got the mod rejected once.
    *
    * WHY A LADDER (1.43.2). The title shares its line with the Within and Mode buttons and nothing
    * reserved it any room: it was drawn at full length from the left edge, and on a 320–360 pixel
    * window only 60–75 pixels were left before the first button, so the three were drawn through each
    * other. layoutDiag passed because it compared the top row only vertically — it explicitly excused
    * "title and mode toggle share a line" — and never asked where the title ENDS.
    */
   public static final String[] TITLES = {
      "§f✨ Custom spawn §7— for the NEW world you're creating",
      "§f✨ Custom spawn §7— for your NEW world",
      "§f✨ Custom spawn §7— NEW world",
      "§f✨ NEW world spawn"};

   /** The longest {@link #TITLES} wording that fits {@code px}, measured by {@code width}; the shortest
    *  if none does (layoutDiag fails any real window size where that happens). */
   public static String pickTitle(java.util.function.ToIntFunction<String> width, int px) {
      for (String t : TITLES) {
         if (width.applyAsInt(t) <= px) {
            return t;
         }
      }
      return TITLES[TITLES.length - 1];
   }

   /** The pixels the title may use: from the left edge to a gap before the first top-row button. The
    *  Within button exists only in Fast mode, so Exact mode's title may run up to the Mode button. */
   public int titleW(boolean withinButtonShown) {
      return (withinButtonShown ? subX : modeX) - GAP - wishX;
   }

   public static WishLayout of(int width, int height, int linkCount) {
      return of(width, height, linkCount, 1);
   }

   /**
    * The same layout, told how many lines the feedback message needs.
    *
    * It is a parameter for the same reason {@code linkCount} is: the band takes room from the list
    * above it, and the whole point of this record is that nothing works out its own position
    * without the bands around it being told. The feedback line used to be exactly one line tall and
    * the screen cut any longer message off with an ellipsis — which is fine for "pick something
    * first" and useless for a provider error, the one message worth reading in full and the one
    * that is never short. Now the screen measures the wrapped message and asks for the height.
    */
   /** The Ask-me toggle's wordings, longest first; the screen draws the longest that fits. */
   public static final String[] ASK_ON = {"Ask me: ON", "Ask: ON"};
   public static final String[] ASK_OFF = {"Ask me: OFF", "Ask: OFF"};
   /** The Coords toggle's wordings (whether a found seed's coordinates are shown), longest first. */
   public static final String[] COORDS_SHOWN = {"Coords: shown", "Coords: on", "XY: on"};
   public static final String[] COORDS_HIDDEN = {"Coords: hidden", "Coords: off", "XY: off"};

   /**
    * The bottom-left band's three buttons — No thanks, Ask me, Coords — as {x0, w0, x1, w1, x2, w2}.
    *
    * THREE SINCE 1.44.3 (the Coords toggle came back). "No thanks" gets the biggest share because it
    * has no shorter wording; the two toggles fall back to shorter ones on a narrow panel. layoutDiag
    * checks the shortest wording of each against these widths at every window size.
    */
   public int[] bottomLeft() {
      int room = leftW - 2 * 4;
      int w0 = room * 38 / 100;
      int w1 = (room - w0) / 2;
      int w2 = room - w0 - w1;
      int x1 = leftX + w0 + 4;
      int x2 = x1 + w1 + 4;
      return new int[]{leftX, w0, x1, w1, x2, w2};
   }

   public static WishLayout of(int width, int height, int linkCount, int feedbackLines) {
      int want = Math.max(1, Math.min(MAX_FEEDBACK_LINES, feedbackLines));
      // AS MANY LINES AS THE WINDOW CAN AFFORD, worked out by BUILDING each candidate and asking it
      // whether the result is usable — not by a rule of thumb about how many pixels a line costs.
      //
      // The rule of thumb was the first attempt and it was wrong in a way that took a diagnostic to
      // see. It shrank the band while the browse LIST would be left with no rows, which is the
      // obvious cost and only half of it: the extra lines also push the right column's Must-have
      // toggle up, so on a narrow window a three-line message starved the CHOSEN list instead and
      // the panel declared itself too small. Showing an error would have switched the screen off.
      //
      // {@code tooSmall} already means "either column has run out of room", so asking the finished
      // layout is both simpler and complete: take the tallest band that does not make the panel
      // unusable, and if even one line does not (a genuinely tiny window), stop pretending and
      // return that.
      for (int n = want; n > 1; n--) {
         WishLayout candidate = build(width, height, linkCount, n);
         if (!candidate.tooSmall()) {
            return candidate;
         }
      }
      // Nothing taller than one line was usable — including, possibly, one line, on a window too
      // small for the panel at all. The single-line band is what that case gets: it is the least
      // room the feedback can take, so it is also the version of the layout whose other bands are
      // least squeezed, which matters because the screen is about to draw "make the window taller"
      // over the top of it anyway.
      return build(width, height, linkCount, 1);
   }

   private static WishLayout build(int width, int height, int linkCount, int fbLines) {
      // The minimums are floors on how far the panel will SHRINK, not licence to grow past the
      // window: clamping to the window last means a tiny window gets a cramped panel rather than
      // one whose top and bottom are off-screen, which is what an unclamped minimum produced.
      int panelW = Math.min(Math.max(PANEL_MIN_W, Math.min(PANEL_MAX_W, width - OUTER * 2)), width);
      int panelH = Math.min(Math.max(PANEL_MIN_H, height - OUTER * 2), height);
      int panelX = (width - panelW) / 2;
      int panelY = (height - panelH) / 2;

      int contentX = panelX + PAD;
      int contentW = panelW - PAD * 2;

      // ── Top strip ────────────────────────────────────────────────────────────────────────────
      int titleY = panelY + PAD;
      // The mode button, and a SECOND slot immediately left of it for the control that belongs to
      // whatever mode is on — the fast-distance cycler. That control used to exist only on the other
      // seed screen, which meant the distance was permanently stuck at its default for anyone who
      // came in through this one.
      // SIZED TO THEIR LABELS, not to a share of the panel. The Mode button reserved up to 150 pixels
      // for a 98-pixel label and the Within button up to 104 for 54, and every one of those spare
      // pixels came out of the title's line. layoutDiag measures both labels with the game's own font
      // against these numbers, so a longer label fails the build instead of overflowing the button.
      int modeW = Math.min(MODE_W, contentW / 2);
      int modeX = contentX + contentW - modeW;
      int modeY = titleY - 5;
      int subW = Math.min(SUB_W, contentW / 4);
      int subX = modeX - GAP - subW;
      int wishY = titleY + LINE + 6;
      int keyW = Math.min(80, contentW / 5);
      int aiW = Math.min(90, contentW / 4);
      int keyX = contentX + contentW - keyW;
      int aiX = keyX - GAP - aiW;
      int wishW = aiX - GAP - contentX;

      // ── Columns ──────────────────────────────────────────────────────────────────────────────
      int leftW = (contentW - GAP) * 55 / 100;
      int leftX = contentX;
      int rightX = contentX + leftW + GAP;
      int rightW = contentW - leftW - GAP;

      // ── Left column, down to where the list starts ────────────────────────────────────────────
      int filterY = wishY + BTN + GAP;
      int tabsY = filterY + 22;
      int listY = tabsY + 24;

      // ── Bottom band, measured UP from the panel floor, then everything above is fitted to what
      // is left. This is the half that used to be counted from the window instead of the panel,
      // which is how it ended up sitting on top of the last row of the list.
      int bottomY = panelY + panelH - PAD - BTN;
      // The band is simply as tall as it was asked for here. Whether the window can AFFORD that is
      // decided by the caller above, which builds this at several heights and keeps the tallest one
      // that leaves both columns usable.
      int feedbackY = bottomY - LINE * fbLines - 2;

      // The "1–10 of 43" line gets its own row of pixels UNDER the list rather than being squeezed
      // into the gap above the feedback line. That gap is where the old layout put things and is
      // exactly how the feedback ended up drawn over the last row of the list.
      int listCountY = feedbackY - LINE - 2;
      int listBottom = listCountY - 2;
      int visibleRows = Math.max(0, (listBottom - listY) / ROW_H);
      int scrollX = leftX + leftW - SCROLL_W;

      // ── Right column, also fitted between its header and the bottom band ─────────────────────
      // No live counter here. It used to sit above the Search button showing "0 · seeds searched",
      // which is the only value it can ever have on a screen you have not started a search from —
      // the search screen is a different phase with its own counter. Dropping it gave 30 pixels
      // back to the two lists, which is the difference between one "next to" pair fitting in a
      // 320x240 window and the panel declaring itself too small.
      // THE FEEDBACK BAND CROSSES BOTH COLUMNS, so everything in either column has to sit above it.
      //
      // This is the fix for "Stopped after 4,391,320 seeds…" being drawn through the Must-have
      // button. The band was positioned as though it belonged to the left column — and it was
      // CHECKED that way too, which is why layoutDiag passed: it compared bands within a column and
      // these two were filed under different ones. But the message is drawn from leftX across the
      // full panel width, because an AI error needs the room, so it ran straight under the right
      // column's toggle. Two true statements that were never compared.
      //
      // Deriving the toggle from feedbackY instead of from bottomY makes the relationship
      // structural: the feedback can grow to three lines and the toggle moves up with it.
      int toggleY = feedbackY - BTN - 4;
      int rightHeaderY = filterY;
      int rightListY = rightHeaderY + 18;

      // The chosen items and the pair rows SHARE one area, and how it is divided is the thing that
      // has to be worked out rather than assumed. The old version gave the pairs whatever they
      // asked for and let the list keep the rest, which is fine until three pairs on a short window
      // ask for more than exists — then linkY climbed past the header and out through the wish box.
      // Now the pairs are capped at what genuinely fits and the list gets the remainder, so neither
      // can push the other off the panel.
      int areaTop = rightListY;
      int areaBottom = toggleY - 6;
      int areaH = Math.max(0, areaBottom - areaTop);
      // THE PAIRS LEAVE ONE ROW FOR THE PICKS. Without the reservation they take the whole area on a
      // short window — three pair rows filled it exactly — and the chosen list was left with zero
      // height, which trips tooSmall and turns the panel into "make the window taller". The pairs
      // are already the half of this area that degrades gracefully: the ones that do not fit are
      // still SEARCHED and the line below says how many are hidden, whereas a picks list with no
      // rows shows nothing about a wish the player is actively building.
      int visibleLinks = linkCount == 0 ? 0
            : Math.min(linkCount, Math.max(0, (areaH - 8 - PICK_H) / PICK_H));
      int linkH = visibleLinks == 0 ? 0 : visibleLinks * PICK_H + 8;
      int linkY = areaBottom - linkH;
      int rightListBottom = Math.max(areaTop, linkY - 2);

      // Below this the bands really have run out of room and the honest thing is to say so rather
      // than draw a list with no rows in it and let the player wonder what broke.
      boolean tooSmall = visibleRows == 0 || rightListBottom - rightListY < PICK_H;

      return new WishLayout(width, height, panelX, panelY, panelW, panelH,
            titleY, modeX, modeW, modeY, subX, subW,
            contentX, wishY, wishW, aiX, aiW, keyX, keyW,
            leftX, leftW, rightX, rightW,
            filterY, tabsY, listY, listBottom, ROW_H, visibleRows, scrollX, listCountY,
            rightHeaderY, rightListY, rightListBottom, PICK_H,
            linkY, visibleLinks, toggleY, bottomY, feedbackY, fbLines, tooSmall);
   }

   /** How many chosen items fit before the pair rows claim the space below them. */
   public int visiblePicks() {
      return Math.max(0, (rightListBottom - rightListY) / pickH);
   }

   public int panelRight() {
      return panelX + panelW;
   }

   public int panelBottom() {
      return panelY + panelH;
   }
}
