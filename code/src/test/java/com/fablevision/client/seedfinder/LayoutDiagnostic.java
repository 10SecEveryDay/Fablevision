package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fablevision.VillageDiagnostic;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

/**
 * Resizes the custom-spawn screen to every window size and checks that nothing lands on top of
 * anything else, and that every piece of text fits the box it is drawn in — without launching
 * Minecraft.
 *
 * This exists because the overlap that was reported ("line 2 of one row is covered by the row below,
 * and it showed up when the window was small") is not a rendering bug. It is arithmetic: the old
 * layout counted some bands down from the top of the window and others up from the bottom, and on a
 * short window the two sets of numbers walked through each other. Arithmetic can be checked by
 * arithmetic, and checking it by dragging a game window to twenty different shapes is both slow and
 * the kind of test nobody runs twice.
 *
 * WHAT IS NEW, AND WHY. The geometry half has passed at every size for several releases and text
 * kept overflowing anyway, because only a handful of strings were being measured. Three kinds of
 * slot were not:
 *
 *   BUTTONS DO NOT TRUNCATE. A vanilla button draws its label centred and lets it run past its own
 *   edge, so the four category tabs at a narrow width draw straight through each other. Nothing
 *   clipped, nothing warned, and no previous version of this file looked at a button at all.
 *
 *   TRUNCATION IS ALSO A FAILURE when there is nothing left. The rows and the chosen list call
 *   {@code fit}, which cuts to an ellipsis — fine at ten characters, useless at four, and the
 *   difference was never measured.
 *
 *   THE WORST STRINGS COME FROM THE CATALOG, so this now loads it. The longest row name and the
 *   longest biome sentence are data, not something to guess at and type in here; a row added next
 *   month is measured by this run without anybody remembering to update it.
 *
 * {@link WishLayout} made the geometry half possible by being a pure function of (width, height,
 * pairs). Every assertion below is about geometry and text metrics alone; nothing here draws.
 *
 * Run with {@code gradlew layoutDiag}. It exits non-zero if any size fails, so it can gate a build.
 */
public final class LayoutDiagnostic {

   /**
    * One horizontal band of the screen, with the column it lives in.
    *
    * "both" IS A REAL COLUMN and not a convenience. The feedback message is drawn from the left edge
    * across the full panel width, and filing it as a left-column band is what let it be drawn
    * through the Must-have button in the right column while this diagnostic reported no overlaps —
    * two true statements that were never compared, because the comparison was column-gated. A band
    * in "both" now conflicts with a band in either.
    */
   private record Band(String name, String column, int top, int bottom) {
      boolean overlaps(Band o) {
         boolean sameColumn = column.equals(o.column)
               || "both".equals(column) || "both".equals(o.column);
         return sameColumn && top < o.bottom && o.top < bottom;
      }
   }

   /** Text is 9 pixels of glyph plus a descender; 10 is the spacing the whole mod draws at. */
   private static final int LINE = 10;
   private static final int BTN = 20;

   /**
    * The sizes to check.
    *
    * These are {@code Screen.width}/{@code height} AFTER the GUI scale is applied, which is what the
    * layout actually sees — a 1920x1080 monitor at scale 4 hands the screen 480x270, so the small
    * end of this list is not hypothetical. It is what a maximised window on a normal laptop looks
    * like to this code at the GUI scale most people leave it on.
    */
   private static final int[] WIDTHS = {320, 360, 427, 480, 640, 854, 1024, 1280, 1920};
   private static final int[] HEIGHTS = {180, 200, 240, 270, 300, 360, 480, 540, 720, 1080};

   /**
    * The smallest window Minecraft will ever hand this screen.
    *
    * The game lowers the GUI scale until the screen is at least 320x240, so a 180- or 200-pixel-tall
    * screen is a shape the layout can be ASKED for — and must still not overlap itself at — but not
    * one a player can ever be looking at. Text is only REQUIRED to fit at or above this, because
    * writing for a window that cannot exist costs the wording on the windows people really use.
    */
   private static final int REAL_MIN_H = 240;

   // ── Text is measured with the GAME'S OWN FONT (1.43.2) ────────────────────────────────────────
   //
   // This diagnostic passed while the title, the Within button and the Mode button were drawn on top
   // of each other on a small window. Three things let that through, and all three are fixed here:
   //
   //   1. The top row was only ever compared VERTICALLY. Title and Mode button share a line on purpose,
   //      so their overlap was excused — and nothing then asked where the title ENDS. checkTopRow now
   //      lays the three out left to right and fails any that touch.
   //   2. The Within button was not in the list at all.
   //   3. Widths came from TextFit's "no glyph is wider than 6 px". The game's font disagrees: '@' and
   //      '~' are 7, '✨' is 8, '—' is 9. RealFont reads the font files and applies the game's own width
   //      rules, so a label that fits here fits in the game.

   private static final RealFont FONT = RealFont.get();

   private static int w(String s) {
      return FONT.width(s);
   }

   /** How many leading characters of {@code s} fit in {@code px}. */
   private static int charsThatFit(String s, int px) {
      int n = 0;
      for (int i = 1; i <= s.length(); i++) {
         if (w(s.substring(0, i)) > px) {
            break;
         }
         n = i;
      }
      return n;
   }

   public static void main(String[] args) {
      System.out.println("========== LAYOUT AND TEXT AT EVERY WINDOW SIZE ==========");
      System.out.println("  checking: no two bands overlap, nothing escapes the panel, the top row's");
      System.out.println("            title and buttons do not touch, a row is tall enough for both its");
      System.out.println("            lines, every button label fits its button, and no sentence is cut");
      System.out.println("            to nothing");
      if (!FONT.available()) {
         // A width check with guessed widths is the check that let the top-row overlap through. It is
         // not run on guesses; it fails and says why.
         System.out.println("  CANNOT MEASURE TEXT: the game's font files were not found (" + FONT.problem() + ")");
         System.exit(2);
      }
      System.out.println("  text measured with the game's own font (widest ASCII glyph " + FONT.widestAscii() + " px)");
      System.out.println();
      loadWorstStrings();

      int checked = 0;
      int failed = 0;
      int tooSmall = 0;
      for (int w : WIDTHS) {
         for (int h : HEIGHTS) {
            for (int pairs = 0; pairs <= 3; pairs++) {
               // EVERY FEEDBACK HEIGHT, not just the one-line case. The feedback band grew from a
               // fixed line to "as many as the wrapped message needs" so a provider error can be
               // read in full, and it takes that room from the browse list — which is exactly the
               // kind of two-bands-growing-into-each-other change this whole diagnostic exists to
               // catch. A four-line error on a 320x240 window is the case to worry about.
               for (int fb = 1; fb <= WishLayout.MAX_FEEDBACK_LINES; fb++) {
                  WishLayout L = WishLayout.of(w, h, pairs, fb);
                  checked++;
                  List<String> problems = check(L, pairs);
                  if (L.tooSmall()) {
                     tooSmall++;
                  }
                  // Showing an error must never be what empties the list. The band gives up its
                  // extra lines before the list gives up its last row — see WishLayout.of.
                  if (L.tooSmall() && !WishLayout.of(w, h, pairs, 1).tooSmall()) {
                     problems = new ArrayList<>(problems);
                     problems.add("a " + fb + "-line feedback message makes the panel declare itself"
                           + " too small, when the same window is fine with a one-line one");
                  }
                  if (!problems.isEmpty()) {
                     failed++;
                     System.out.println("  FAIL " + w + "x" + h + " pairs=" + pairs + " feedback=" + fb);
                     for (String p : problems) {
                        System.out.println("        " + p);
                     }
                  }
               }
            }
         }
      }

      System.out.println();
      System.out.println("  sizes checked          : " + checked);
      System.out.println("  sizes with a problem   : " + failed);
      System.out.println("  sizes flagged TOO SMALL: " + tooSmall
            + "   (these draw a 'make the window taller' message instead of a broken list)");
      reportClipping();

      System.out.println();
      System.out.println("  smallest workable height, by pair count:");
      for (int pairs = 0; pairs <= 3; pairs++) {
         int best = -1;
         for (int h : HEIGHTS) {
            if (!WishLayout.of(854, h, pairs).tooSmall()) {
               best = h;
               break;
            }
         }
         System.out.println("     " + pairs + " pair(s): " + (best < 0 ? "none of the tested heights" : best + " px")
               + (best >= 0 && best <= 240 ? "   (inside Minecraft's own 320x240 floor)" : ""));
      }
      System.out.println();
      sample(854, 480, 0);
      sample(480, 270, 1);
      sample(320, 240, 2);
      System.out.println("================================================");
      System.exit(failed == 0 ? 0 : 1);
   }

   // ── The worst strings each slot can be asked to hold, read from the live catalog ──────────

   private static String longestRowName = "";
   private static String longestRowLine2 = "";
   private static String longestPickLine = "";
   private static String longestTab = "";

   /**
    * The longest text each variable slot can hold, derived rather than typed.
    *
    * A hardcoded "the longest row is Underwater Ruined Portal" is a fact that stops being true the
    * next time a row is added, and stops silently. Reading the catalog makes this run measure
    * whatever the mod actually ships today.
    *
    * FAILS SOFT. If registries cannot be loaded the geometry half still runs and the text half
    * falls back to the longest strings known when this was written, with a line saying so — a
    * diagnostic that refuses to run at all is one that gets skipped.
    */
   private static void loadWorstStrings() {
      for (SpawnWishTab t : SpawnWishTab.values()) {
         if (t.title().length() > longestTab.length()) {
            longestTab = t.title();
         }
      }
      try {
         SharedConstants.tryDetectVersion();
         Bootstrap.bootStrap();
         RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
         WorldgenContext ctx = WorldgenContext.get(reg);
         for (SeedCriteria.StructureTarget t : SeedCatalog.structures(ctx)) {
            keepLongest(t.shortName(), n -> longestRowName = n, () -> longestRowName);
            if (t.rarityPct >= 0) {
               keepLongest(t.rarityPct + "% have it · slow search",
                     n -> longestRowLine2 = n, () -> longestRowLine2);
            }
         }
         for (Identifier b : SeedCatalog.biomes(ctx)) {
            String label = SeedCatalogCache.prettify(b.getPath());
            keepLongest(label, n -> longestRowName = n, () -> longestRowName);
            keepLongest("38% of seeds (within 2000) · size ✔ · slow search",
                  n -> longestRowLine2 = n, () -> longestRowLine2);
            BiomeShape.Shape s = BiomeShape.of(b);
            if (s != null && s.tiered()) {
               // The chosen-list second line, which is the longest sentence that slot ever holds.
               // "an" before a vowel, like the screen does it — the article is a character of
               // width and this is a width measurement.
               String low = label.toLowerCase(Locale.ROOT);
               String article = "aeiou".indexOf(low.charAt(0)) >= 0 ? "an " : "a ";
               keepLongest(s.largeMin() + " blocks across or more — large for " + article + low,
                     n -> longestPickLine = n, () -> longestPickLine);
            }
         }
         System.out.println("  worst strings, read from the live catalog:");
      } catch (Throwable t) {
         longestRowName = "Underwater Ruined Portal";
         longestRowLine2 = "38% of seeds (within 2000) · size ✔ · slow search";
         longestPickLine = "608 blocks across or more — large for a deep lukewarm ocean";
         System.out.println("  worst strings, FALLBACK (registries unavailable: "
               + t.getClass().getSimpleName() + "):");
      }
      System.out.println("     longest row name   : \"" + longestRowName + "\" ("
            + w(longestRowName) + "px)");
      System.out.println("     longest row line 2 : \"" + longestRowLine2 + "\" ("
            + w(longestRowLine2) + "px)");
      System.out.println("     longest pick line  : \"" + longestPickLine + "\" ("
            + w(longestPickLine) + "px)");
      System.out.println("     longest tab title  : \"" + longestTab + "\" ("
            + w(longestTab) + "px)");
      System.out.println();
   }

   private static void keepLongest(String candidate, java.util.function.Consumer<String> set,
                                   java.util.function.Supplier<String> get) {
      if (candidate != null && w(candidate) > w(get.get())) {
         set.accept(candidate);
      }
   }

   /**
    * The picker's category tabs, mirrored here because the screen's own enum is private.
    *
    * A COPY IS A RISK AND IT IS THE SMALLER ONE. Making the screen's enum public to test it would
    * widen an API for a diagnostic; leaving the tabs unchecked is what let four labels be drawn
    * through each other on a narrow window. The titles are asserted against the screen's own below,
    * so the copy cannot drift silently.
    */
   private enum SpawnWishTab {
      VILLAGE("Village", "Vill", "Vil"),
      STRUCTURE("Structures", "Struct", "Str"),
      BIOME("Biomes", "Biome", "Bio"),
      DEEP("Nether", "Neth", "Net");

      private final String[] wordings;

      SpawnWishTab(String... wordings) {
         this.wordings = wordings;
      }

      String title() {
         return wordings[0];
      }

      /** The wording the screen would pick for this width — longest that fits, exactly as
       *  {@code SpawnWishScreen.widest} does it. */
      String forWidth(int px) {
         for (String w : wordings) {
            if (LayoutDiagnostic.w(w) <= px) {
               return w;
            }
         }
         return wordings[wordings.length - 1];
      }
   }

   private static List<String> check(WishLayout L, int pairs) {
      List<String> bad = new ArrayList<>();

      // A row has to fit its icon and BOTH lines with a gap before the next row. The screen draws
      // line 1 at +4 and line 2 at +16, so the row must be at least 16 + LINE + 2 tall.
      if (L.rowH() < 16 + LINE + 2) {
         bad.add("row height " + L.rowH() + " cannot hold two lines with a gap");
      }
      // A chosen row draws 18-pixel buttons across its top and the detail line UNDER them, at +19,
      // so the row has to be at least 19 + LINE tall. The old assertion allowed 26, which is how
      // the detail line came to be drawn through the buttons beside it.
      if (L.pickH() < 19 + LINE) {
         bad.add("pick height " + L.pickH() + " cannot hold a button row and a line under it");
      }

      List<Band> bands = new ArrayList<>();
      bands.add(new Band("title", "left", L.titleY(), L.titleY() + LINE));
      bands.add(new Band("wish box", "left", L.wishY(), L.wishY() + BTN));
      bands.add(new Band("filter", "left", L.filterY(), L.filterY() + 18));
      bands.add(new Band("tabs", "left", L.tabsY(), L.tabsY() + 18));
      if (L.visibleRows() > 0) {
         bands.add(new Band("browse list", "left", L.listY(),
               L.listY() + L.visibleRows() * L.rowH()));
      }
      bands.add(new Band("list count", "left", L.listCountY(), L.listCountY() + LINE));
      bands.add(new Band("feedback", "both", L.feedbackY(), L.feedbackY() + LINE * L.feedbackLines()));
      bands.add(new Band("bottom buttons", "left", L.bottomY(), L.bottomY() + BTN));

      bands.add(new Band("title", "right", L.titleY(), L.titleY() + LINE));
      bands.add(new Band("mode toggle", "right", L.modeY(), L.modeY() + BTN));
      bands.add(new Band("wish box", "right", L.wishY(), L.wishY() + BTN));
      bands.add(new Band("searching-for header", "right", L.rightHeaderY(), L.rightListY()));
      if (L.visiblePicks() > 0) {
         bands.add(new Band("chosen list", "right", L.rightListY(),
               L.rightListY() + L.visiblePicks() * L.pickH()));
      }
      if (L.visibleLinks() > 0) {
         bands.add(new Band("pair rows", "right", L.linkY() + 8,
               L.linkY() + 8 + L.visibleLinks() * L.pickH()));
      }
      // HIDING PAIR ROWS IS ALLOWED NOW, AND SAYING SO IS NOT. This assertion used to be "if any
      // pair row does not fit and the panel has not declared itself too small, that is a bug",
      // which was right while the pairs took whatever they needed and the picks list lived on the
      // remainder. That priority is now the other way round — the picks list keeps at least one row
      // and the pairs give way — because a hidden pair is still SEARCHED and the line below the
      // rows says how many there are, while a picks list with no rows says nothing about the wish
      // the player is building. So what has to be true is not that every pair is drawn; it is that
      // the "+N more" line has somewhere to be drawn when they are not.
      if (pairs > 0 && L.visibleLinks() < pairs) {
         if (L.linkY() < L.panelY() || L.linkY() + LINE > L.bottomY()) {
            bad.add(L.visibleLinks() + " of " + pairs + " pair rows fit and the \"+N more\" line "
                  + "has nowhere to go (linkY " + L.linkY() + ", panel " + L.panelY() + ".."
                  + L.bottomY() + ")");
         }
      }
      bands.add(new Band("must-have toggle", "right", L.toggleY(), L.toggleY() + BTN));
      bands.add(new Band("search button", "right", L.bottomY(), L.bottomY() + BTN));

      for (int i = 0; i < bands.size(); i++) {
         for (int j = i + 1; j < bands.size(); j++) {
            Band a = bands.get(i);
            Band b = bands.get(j);
            // The title and the mode toggle deliberately share a line, one at each end of it. That is
            // NOT a free pass any more: checkTopRow lays the line out left to right and fails if the
            // title reaches the buttons. The excuse without that check is how the overlap shipped.
            if (a.name().equals("title") && b.name().equals("mode toggle")) {
               continue;
            }
            if (a.overlaps(b)) {
               bad.add("\"" + a.name() + "\" [" + a.top() + "," + a.bottom() + ") overlaps \""
                     + b.name() + "\" [" + b.top() + "," + b.bottom() + ") in the " + a.column()
                     + " column");
            }
         }
      }

      // Nothing may hang out of the panel. Everything is drawn against it, so an escape here means
      // a band was computed from the WINDOW by mistake — the exact bug this is guarding.
      for (Band b : bands) {
         if (b.top() < L.panelY() || b.bottom() > L.panelBottom()) {
            bad.add("\"" + b.name() + "\" [" + b.top() + "," + b.bottom() + ") is outside the panel ["
                  + L.panelY() + "," + L.panelBottom() + ")");
         }
      }
      if (L.wishX() < L.panelX() || L.keyX() + L.keyW() > L.panelRight()) {
         bad.add("the top row is wider than the panel");
      }
      if (L.rightX() + L.rightW() > L.panelRight()) {
         bad.add("the right column runs past the panel edge");
      }
      if (L.wishW() < 40) {
         bad.add("wish box has collapsed to " + L.wishW() + " px");
      }
      bad.addAll(checkTopRow(L, true));
      bad.addAll(checkTopRow(L, false));
      if (L.height() >= REAL_MIN_H) {
         bad.addAll(checkText(L, pairs));
      }
      return bad;
   }

   /**
    * THE TOP ROW, LEFT TO RIGHT: title, then (in Fast mode) Within, then Mode. The case that shipped.
    *
    * The title is chosen the way the screen chooses it — {@link WishLayout#pickTitle} with the game's
    * font widths — and then laid out with the two buttons as horizontal spans. Any two that touch is a
    * failure, at every window size, including the ones below the 240-pixel floor: overlapping text is
    * never acceptable, even on a shape nobody can make. What only has to hold at real sizes is that a
    * wording which says "NEW world" fits at all.
    */
   private static List<String> checkTopRow(WishLayout L, boolean fastMode) {
      List<String> bad = new ArrayList<>();
      String mode = fastMode ? "Fast" : "Exact";
      String title = WishLayout.pickTitle(LayoutDiagnostic::w, L.titleW(fastMode));
      int titleRight = L.wishX() + w(title);
      int firstButton = fastMode ? L.subX() : L.modeX();
      if (titleRight > firstButton) {
         bad.add("TOP ROW (" + mode + "): the title \"" + title.replaceAll("§.", "") + "\" ends at "
               + titleRight + " but the " + (fastMode ? "Within" : "Mode") + " button starts at " + firstButton
               + " — drawn on top of each other");
      }
      if (fastMode && L.subX() + L.subW() > L.modeX()) {
         bad.add("TOP ROW: the Within button [" + L.subX() + "," + (L.subX() + L.subW())
               + ") runs into the Mode button at " + L.modeX());
      }
      if (L.modeX() + L.modeW() > L.panelRight()) {
         bad.add("TOP ROW: the Mode button runs past the panel edge");
      }
      if (L.wishX() < L.panelX()) {
         bad.add("TOP ROW: the title starts outside the panel");
      }
      return bad;
   }

   /**
    * DOES THE TEXT FIT THE SPACE THE LAYOUT GIVES IT?
    *
    * The geometry checks above are about boxes, and every overflow this screen has shipped was about
    * what is INSIDE a box: "64 blocks is tight — most seeds will f…", a stopped-search message drawn
    * through a button, an AI error clipped mid-sentence, four tab labels running into each other.
    * The layout was verified at hundreds of sizes and the text inside it was verified at whatever
    * size the developer's window happened to be.
    *
    * It could not be checked before because measuring text needs {@code Font}, which needs the
    * game's resources loaded. {@link TextFit} is the way round that: it does not measure, it BOUNDS
    * — six pixels per glyph is wider than any glyph of the default font actually gets, so a string
    * that fits by that measure fits in the game at every scale. The bound is pessimistic by roughly
    * 15%, which costs an occasional shortened sentence and buys a check that runs headlessly on
    * every size, every time.
    *
    * THREE KINDS OF SLOT, and they fail in three different ways:
    *
    *   A BUTTON never truncates. Whatever does not fit is drawn past its edge and over whatever is
    *   next to it, so a button label that does not fit is an overlap and is failed outright.
    *
    *   A FITTED slot ({@code SpawnWishScreen.fit}) truncates to an ellipsis. Some truncation is
    *   acceptable — a row name is in full in the tooltip — but a slot cut to four characters says
    *   nothing, so there is a floor.
    *
    *   A WRAPPED slot (the feedback band) grows to as many lines as the window can afford. Here the
    *   question is not "does it fit one line" but "does this window grant the lines this message
    *   needs", and a message needing more than the ceiling is too long wherever it is shown.
    */
   private static List<String> checkText(WishLayout L, int pairs) {
      List<String> bad = new ArrayList<>();

      // ── Buttons. These do not truncate, so anything over is drawn on its neighbour ───────────
      // The four category tabs share the left column, each (leftW - 6) / 4 wide.
      int tabW = (L.leftW() - (SpawnWishTab.values().length - 1) * 2) / SpawnWishTab.values().length;
      for (SpawnWishTab t : SpawnWishTab.values()) {
         // The SHORTEST wording is what has to fit: the screen picks the longest that does, so the
         // only failure left is a tab whose shortest form still does not.
         button(bad, "tab \"" + t.title() + "\"", t.forWidth(tabW - 4), tabW);
      }
      // THE REAL LABELS, both states. This used to measure "Mode: ⚡Exact ▸ Fast", a label the screen
      // never draws, with a width model that did not know what ⚡ or 🎯 cost.
      button(bad, "mode toggle (Fast)", "Mode: ⚡Fast ▸ Exact", L.modeW());
      button(bad, "mode toggle (Exact)", "Mode: 🎯Exact ▸ Fast", L.modeW());
      button(bad, "fast distance", "Within 1000", L.subW());
      button(bad, "search wish", "✨ Search", L.aiW());
      button(bad, "AI key", "⚙ key", L.keyW());
      // The bottom-left band is three buttons since 1.44.3. The toggles draw the longest wording
      // that fits, so — as with the tabs — the one that has to fit is what the screen would choose.
      int[] band = L.bottomLeft();
      button(bad, "no thanks", "No thanks", band[1]);
      for (String[] ask : new String[][]{WishLayout.ASK_ON, WishLayout.ASK_OFF}) {
         button(bad, "ask me toggle", pick(ask, band[3] - 4), band[3]);
      }
      for (String[] coords : new String[][]{WishLayout.COORDS_SHOWN, WishLayout.COORDS_HIDDEN}) {
         button(bad, "coords toggle", pick(coords, band[5] - 4), band[5]);
      }
      button(bad, "stop searching", "■ Stop searching", 160);

      // ── Fitted slots. Truncation is allowed; being cut to nothing is not ─────────────────────
      // The browse row's text starts 26px in and keeps 4 clear on the right, with the scrollbar
      // gutter taken off first.
      int rowTextW = L.leftW() - (WishLayout.SCROLL_W + 3) - 30;
      readable(bad, "browse row name", longestRowName, rowTextW - 12);
      readable(bad, "browse row rate", longestRowLine2, rowTextW);
      // The chosen list keeps the last 62px for its three little buttons, and the second line is
      // indented another 10.
      // The screen drops the "Larger "/"Smaller " prefix when keeping it would leave the name with
      // nothing — SpawnWishScreen.NAME_FLOOR. Modelled rather than ignored, because the whole point
      // of this slot is how little room is left after the three little buttons take their 62.
      int nameW = L.rightW() - 80;
      readable(bad, "chosen row name", longestRowName, nameW - (nameW - 48 >= 48 ? 48 : 12));
      // The chosen row's detail line degrades through three wordings (SpawnWishScreen.secondLine),
      // so what has to fit is the SHORTEST of them — the longest is a bonus on a wide window.
      fit(bad, "chosen row detail (shortest)", "608+ across", L.rightW() - 12);
      fit(bad, "chosen row distance (shortest)", "within 1000", L.rightW() - 12);
      readable(bad, "chosen row detail", longestPickLine, L.rightW() - 12);
      readable(bad, "list count", "100–110 of 143  ·  scroll ▲▼", L.leftW() - 4);

      // ── The pair row's second line, the slot that produced "…most seeds will f…" ─────────────
      // Drawn at rightX + 10 with the column's full width less that indent (it now sits BELOW the
      // two buttons rather than beside them).
      int warnW = L.rightW() - 12;
      fit(bad, "pair warning (tight)", "§ctight", warnW);
      fit(bad, "pair warning (weak pair)", "§enarrows little", warnW);
      fit(bad, "pair warning (structures)", "§7direct", warnW);
      fit(bad, "pair distance", "within 1024", warnW);

      // ── The right column's own fixed lines ───────────────────────────────────────────────────
      fit(bad, "empty picks hint", "§7Nothing picked yet", L.rightW() - 2);
      fit(bad, "more pairs", "§7+3 more", L.rightW() - 2);
      fit(bad, "picks header", "§7Searching for", L.rightW() - 2);
      fit(bad, "picks overflow", "§7…more", L.rightW() - 2);

      // ── The left column's fixed lines ────────────────────────────────────────────────────────
      fit(bad, "empty list", "§7No matches.", L.leftW() - 4);
      fit(bad, "loading", "§7Loading…", L.leftW() - 4);

      // ── The feedback band: every fixed message the screen can put in it ──────────────────────
      //
      // MODELLED THE WAY THE SCREEN REALLY WORKS, which took one wrong version of this check to get
      // right. The band is not a fixed height that messages have to fit: the screen measures the
      // message, asks the layout for that many lines, and the layout grants what it can afford
      // (SpawnWishScreen.wantedFeedbackLines). So the question is not "does this message fit one
      // line" — that would fail every message and mean nothing — it is "does this window GRANT the
      // number of lines this message needs".
      for (String[] msg : FEEDBACK_MESSAGES) {
         wraps(bad, msg[0], msg[1], L, pairs);
      }

      return bad;
   }

   /** Every fixed message that can land in the feedback band, longest form first in each case. The
    *  variable parts are filled with their worst case: a seven-digit seed count, the longest pair
    *  of labels, and a provider excerpt at its cap. */
   private static final String[][] FEEDBACK_MESSAGES = {
      {"stopped search", "Stopped after 4,391,320 seeds. Pick something else, or start again."},
      {"same-rule pair refusal", "\"Treasure Bastion\" and \"Nether Fortress\" can't be neighbours "
            + "— the world places only one per region. Both near spawn does work."},
      {"different dimensions refusal", "\"Dark Forest\" and \"Nether Fortress\" are in different "
            + "worlds, so \"next to\" has no meaning between them."},
      {"next-to needs two", "\"Next to\" needs two things that must BE there — a must-not-have has "
            + "nothing to measure from."},
      {"not readable without a key", "§eTry plain names, like \"village by a jungle\" — or add a §f⚙ key§e."},
      // The longest spread WishParser.spreadInExact will name: two items and an ellipsis.
      {"Exact bundle refusal", "That spreads things out (Trial Chamber with Eruption Room 600, "
            + "Village with Leatherworker 400, …). Exact is at spawn — switch to ⚡Fast."},
      {"AI daily limit", "AI limit reached for today — pick below instead."},
      {"AI cancelled by closing", "Your AI request was cancelled when you closed this screen. Press Search to ask again."},
      // The excerpt is capped at 40 characters (WishParser.excerpt), so the message is at its
      // longest when the model returned 40 characters of something that is not a search.
      {"AI didn't answer", "The AI didn't answer with a search. It said: \"xxxxxxxxxxxxxxxxxxxx"
            + "xxxxxxxxxxxxxxxxxxxx\" — try simpler words, or pick below."},
      {"AI wrong shape", "The AI's answer was the wrong shape (IllegalStateException: "
            + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) — try again, or pick below."},
      {"AI cut off", "The AI ran out of room before it finished the search — its reply was cut "
            + "off. Try a shorter wish, or pick below."},
      {"AI never answered", "The AI never answered that one. Try a shorter wish, or pick below."},
      {"8 picks limit", "8 things at once is the limit — remove one first."},
      {"type something first", "Type what you want first — like \"village next to a jungle\"."},
      {"too small", "§cWindow too small — make it taller to see the list."},
      // READ FROM THE SHIPPED CONSTANT, not retyped. It is the longest message this band can hold,
      // so a word added to it without checking is exactly the change that would reintroduce the
      // overflow — and a copy here would agree with itself while the real one grew.
      {"wrong world type", SeedAccess.ONLY_DEFAULT_WORLD_TYPE}};

   /** A button label, which is never truncated and therefore must simply fit. */
   /** The longest wording that fits, the way the screen's own widest() picks it. */
   private static String pick(String[] wordings, int px) {
      for (String w : wordings) {
         if (w(w) <= px) {
            return w;
         }
      }
      return wordings[wordings.length - 1];
   }

   private static void button(List<String> bad, String slot, String text, int px) {
      // Vanilla insets a button's label by about 2px each side before it starts overflowing.
      if (w(text) > px - 4) {
         bad.add("BUTTON \"" + slot + "\" needs " + w(text) + "px, has "
               + (px - 4) + "px — a button does not truncate, so this is drawn over its neighbour:"
               + " \"" + text + "\"");
      }
   }

   /**
    * A slot that truncates: how short is too short to say anything.
    *
    * SIX, and the number is a floor rather than a target. On the smallest window Minecraft will
    * ever produce — 320x240, which only happens when the GUI scale has been forced down — the right
    * column is 132 pixels and four 18-pixel buttons take 72 of them, so a chosen row's NAME has
    * about seven characters however the rest is arranged. Six is what has to survive; a
    * half-screen window (480x270) gives that slot seventeen, which is the size the overflow was
    * reported at and the size this is really written for.
    */
   private static final int MIN_READABLE_CHARS = 6;

   /** A fitted slot. Truncation is allowed; being cut below {@link #MIN_READABLE_CHARS} is not. */
   private static void readable(List<String> bad, String slot, String text, int px) {
      if (charsThatFit(text, px) < MIN_READABLE_CHARS) {
         bad.add("TEXT \"" + slot + "\" has " + px + "px, which is "
               + charsThatFit(text, px) + " characters — nothing useful survives the ellipsis");
      }
   }

   private static void fit(List<String> bad, String slot, String text, int px) {
      if (w(text) > px) {
         bad.add("TEXT \"" + slot + "\" needs " + w(text) + "px, has " + px
               + "px — shorten to " + charsThatFit(text, px) + " chars: \"" + text + "\"");
      }
   }

   /**
    * A wrapping message: does this window grant enough lines to show all of it?
    *
    * The line count is worked out the way the screen works it out and then handed back to the
    * layout, so this is the real round trip and not a re-derivation of it. The quarter-line of slack
    * is because real wrapping breaks at spaces and therefore wastes a little of each line, which
    * {@link TextFit} does not model.
    */
   private static void wraps(List<String> bad, String slot, String text, WishLayout L, int pairs) {
      int lineW = L.panelW() - 20;
      int usableW = lineW - lineW / 4;
      int needed = Math.max(1, (w(text) + usableW - 1) / usableW);
      if (needed > WishLayout.MAX_FEEDBACK_LINES) {
         bad.add("TEXT \"" + slot + "\" needs " + needed + " lines of " + lineW + "px and the band "
               + "stops at " + WishLayout.MAX_FEEDBACK_LINES + " — shorten it");
         return;
      }
      WishLayout asked = WishLayout.of(L.width(), L.height(), pairs, needed);
      if (asked.feedbackLines() >= needed) {
         return;
      }
      // CLIPPED. Below the ceiling this is a trade the layout made rather than a bug in the
      // sentence: WishLayout.of grows the band greedily and stops at the last height that leaves
      // both columns usable, so every taller band would have made the panel declare itself too
      // small. Showing a readable error by switching the picker off is not a better outcome.
      //
      // BUT IT IS ONLY ACCEPTABLE ON A WINDOW NOBODY USES, and that is the change. The report used
      // to say "the largest window that clips is 1920x240", ranked by AREA, which is meaningless
      // for a constraint driven by HEIGHT — a 480x270 half-screen window is smaller in area than
      // 1920x240 and clips for a completely different reason. Both are now recorded per size, and
      // clipping on a window at least as tall as HALF_SCREEN_H is failed rather than counted.
      cramped++;
      crampedSizes.merge(L.width() + "x" + L.height(), 1, Integer::sum);
      if (L.height() >= HALF_SCREEN_H && L.width() >= HALF_SCREEN_W) {
         bad.add("TEXT \"" + slot + "\" needs " + needed + " lines and this window grants "
               + asked.feedbackLines() + " — it is cut off on a window people really use");
      }
   }

   /**
    * The smallest window this project treats as one a player is actually looking at.
    *
    * A half-maximised window on a 1080p monitor at the default GUI scale hands this screen roughly
    * 480x270, which is where the overflow was reported from. Anything at least this size has to show
    * every fixed message in full.
    */
   private static final int HALF_SCREEN_H = 270;
   private static final int HALF_SCREEN_W = 427;

   /** Messages clipped because the window could not afford the lines — see {@link #wraps}. */
   private static int cramped;
   private static final Map<String, Integer> crampedSizes = new LinkedHashMap<>();

   private static void reportClipping() {
      System.out.println("  messages clipped for room: " + cramped
            + "   (windows too short to grow the band without making the panel unusable)");
      if (crampedSizes.isEmpty()) {
         System.out.println("  clipped at: nowhere — every message fits at every usable size");
         return;
      }
      System.out.println("  clipped at these window sizes (all below " + HALF_SCREEN_W + "x"
            + HALF_SCREEN_H + ", which is the floor this has to hold at):");
      for (Map.Entry<String, Integer> e : crampedSizes.entrySet()) {
         System.out.println("     " + e.getKey() + "   " + e.getValue() + " message-heights");
      }
   }

   /** Prints one layout in full, so a change can be eyeballed as numbers rather than guessed at. */
   private static void sample(int w, int h, int pairs) {
      WishLayout L = WishLayout.of(w, h, pairs);
      System.out.println("  -- " + w + "x" + h + ", " + pairs + " pair(s) --");
      System.out.println("     panel        " + L.panelX() + "," + L.panelY() + "  "
            + L.panelW() + "x" + L.panelH());
      System.out.println("     columns      left " + L.leftX() + "+" + L.leftW()
            + "   right " + L.rightX() + "+" + L.rightW());
      System.out.println("     browse list  y " + L.listY() + "–" + L.listBottom()
            + "   " + L.visibleRows() + " rows of " + L.rowH());
      System.out.println("     chosen list  y " + L.rightListY() + "–" + L.rightListBottom()
            + "   " + L.visiblePicks() + " picks of " + L.pickH());
      System.out.println("     bottom       toggle " + L.toggleY()
            + "  buttons " + L.bottomY() + "  feedback " + L.feedbackY());
      System.out.println("     too small?   " + L.tooSmall());
   }

   private LayoutDiagnostic() {
   }
}
