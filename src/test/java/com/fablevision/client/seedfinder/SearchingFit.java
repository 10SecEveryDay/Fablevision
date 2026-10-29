package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.List;

/**
 * layoutDiag's searching-screen section (1.46.2): the text of the single search and of the found-worlds
 * views (searching and results), laid out by {@link SearchingText} — what the screen draws — with the
 * longest things each line can say, at every window size and feedback height.
 *
 *   - every line inside the column, on the one content edge (one alignment);
 *   - no paragraph ends on a stranded word (the lone "64)"): its last line at least
 *     {@link #MIN_LAST_LINE} of its widest;
 *   - on every window the game makes, the subtitle in full, and the lines being waited on never dropped:
 *     the wish and the progress line of a single search, "Found n of N" and the results' head;
 *   - the Stop button on the same edge, its label inside it, the empty-list line fitting.
 *
 * THE CONTROLS: the greedy wrap that produced the lone "64)" must be caught by the stranded-word check
 * (and the even wrap must pass the same text at the same width), a centred line must be caught by the
 * alignment check, and an unwrapped line by the width check.
 */
final class SearchingFit {

   /** A paragraph's last line must be at least this share of its widest line. */
   static final double MIN_LAST_LINE = 0.30;
   private static final int REAL_MIN_H = 240;

   /** The wish from the screenshot, then longer: a summary as SeedCriteria.summary() writes it. */
   static final String SCREENSHOT_SUMMARY = "Village with Weaponsmith within 64 + Cherry Grove next to Village (within 64)";
   static final String WORST_SUMMARY = "Trial Chamber with Eruption Room within 64 + Village with Weaponsmith within 64"
         + " + Larger Cherry Grove within 64 + Cherry Grove next to Village (within 1024) + no Desert + no Swamp";

   private SearchingFit() {
   }

   private static int w(String s) {
      return RealFont.get().width(s);
   }

   /** The longest status each view can have. */
   static SearchingText.Status worst(String eta) {
      return new SearchingText.Status(WORST_SUMMARY, 9_999_999_999L, 999_999, 31, eta, false, false,
            "§a✓ found one 64 blocks out — few more seconds hunting one RIGHT at spawn…",
            "This is taking far longer than its estimate. Details are in logs/latest.log — please include that file if you report it.",
            99_999, WishParser.PLAIN_NOTE + "Nether structures are spread about one per 432 blocks, so 64 blocks from the"
                  + " portal-in point is a rare ask — this may run a long time. It is searched exactly as asked.",
            "the weaponsmith's chest loot, which is rolled when the chest is opened", true,
            "Trial Chamber with Eruption Room (about 1 in 40,000 seeds)");
   }

   /** The longest estimate lines: a far-past lower bound for ten worlds, and a long wait. */
   static List<String> worstEtas() {
      SeedFinder.Estimate atMost = new SeedFinder.Estimate(1 / 210_632.0, true, true, "x");
      SeedFinder.Estimate rare = new SeedFinder.Estimate(1 / 9_000_000.0, false, true, "x");
      return List.of(
            SpawnWishScreen.etaLine(atMost, 999_999, 105_000_000_000L, 10, 0),
            SpawnWishScreen.etaLine(atMost, 1, 0, 10, 0),
            SpawnWishScreen.etaLine(rare, 999, 9_999_999_999L, 10, 3),
            SpawnWishScreen.etaLine(rare, 999, 0, 1, 0));
   }

   static int run(int[] widths, int[] heights) {
      System.out.println();
      System.out.println("  -- the searching screens: one alignment, no stranded words, nothing waited-on dropped --");
      int failed = 0;
      int checked = 0;
      List<String> etas = worstEtas();
      for (int width : widths) {
         for (int height : heights) {
            for (int fb = 1; fb <= WishLayout.MAX_FEEDBACK_LINES; fb++) {
               for (String eta : etas) {
                  checked++;
                  List<String> bad = check(width, height, fb, eta);
                  if (!bad.isEmpty()) {
                     failed++;
                     if (failed <= 12) {
                        System.out.println("  FAIL searching " + width + "x" + height + " feedback=" + fb);
                        bad.forEach(b -> System.out.println("        " + b));
                     }
                  }
               }
            }
         }
      }
      System.out.println("  searching-screen layouts checked: " + checked + " (single search, several searching, results;"
            + " every size, feedback height and worst estimate line), with a problem: " + failed);
      boolean control = controls();
      System.out.println("  controls: the greedy wrap's lone \"64)\", a centred line and an unwrapped line are "
            + (control ? "all caught, and the even wrap passes" : "NOT ALL CAUGHT — the searching checks are blind"));
      return failed + (control ? 0 : 1);
   }

   static List<String> check(int width, int height, int fb, String eta) {
      List<String> bad = new ArrayList<>();
      boolean real = height >= REAL_MIN_H;
      SearchingText.Status s = worst(eta);

      // ── The single search (WishLayout) ──
      WishLayout L = WishLayout.of(width, height, 0, fb);
      int x = L.panelX() + 10;
      int cw = L.panelW() - 20;
      if (w("Custom spawn?") > cw) {
         bad.add("the title does not fit");
      }
      SearchingText.Flow sub = SearchingText.flow(List.of(SearchingText.Para.of(ResultsLayout.SUBTITLE)), x,
            L.panelY() + 24, cw, L.panelY() + SearchingText.STATUS_OFFSET, RealFont.get()::width);
      lines(bad, "subtitle", sub, x, cw);
      if (real && sub.dropped() > 0) {
         bad.add("the subtitle loses " + sub.dropped() + " line(s)");
      }
      int limit = L.feedbackY() - 4;
      SearchingText.Flow single = SearchingText.flow(SearchingText.single(s), x, L.panelY() + SearchingText.STATUS_OFFSET,
            cw, limit, RealFont.get()::width);
      lines(bad, "single search", single, x, cw);
      paragraphs(bad, "single search", SearchingText.single(s), cw);
      if (real && single.parasShown() < 2) {
         bad.add("single search: only " + single.parasShown() + " paragraph(s) shown — the wish and the progress line must be");
      } else if (real && countLines(SearchingText.single(s).subList(0, 2), cw) > single.lines().size()) {
         bad.add("single search: the wish or the progress line is cut");
      }
      int[] stop = ResultsLayout.of(width, height, fb, ResultsLayout.SEARCHING_STATUS).stopButton();
      if (stop[0] != x || stop[1] > cw || w("■ Stop searching") > stop[1] - 4) {
         bad.add("the Stop button [" + stop[0] + "+" + stop[1] + "] is not on the content edge " + x + " or its label does not fit");
      }

      // ── Several worlds, searching and results (ResultsLayout) ──
      for (int maxStatus : new int[]{ResultsLayout.SEARCHING_STATUS, ResultsLayout.RESULTS_STATUS}) {
         ResultsLayout R = ResultsLayout.of(width, height, fb, maxStatus);
         boolean searching = maxStatus == ResultsLayout.SEARCHING_STATUS;
         String view = searching ? "several (searching)" : "several (results)";
         List<SearchingText.Para> paras = searching
               ? SearchingText.several(s, 9, 10, " §7(you asked for 50 — 10 is the most at once)")
               : SearchingText.results(false, true, 9, 10, " §7(you asked for 50 — 10 is the most at once)", WORST_SUMMARY,
                     9_999_999_999L, "1440m 00s", false, "the weaponsmith's chest loot, which is rolled when the chest is opened");
         SearchingText.Flow f = SearchingText.flow(paras, R.contentX(), R.statusY(), R.contentW(), R.statusBottom(),
               RealFont.get()::width);
         lines(bad, view, f, R.contentX(), R.contentW());
         paragraphs(bad, view, paras, R.contentW());
         if (real && (f.lines().isEmpty() || !f.lines().get(0).text().contains(searching ? "Found 9" : "9 of 10"))) {
            bad.add(view + ": the first status line is not the count (" + (f.lines().isEmpty() ? "nothing shown" : f.lines().get(0).text()) + ")");
         }
         if (searching && w(LayoutDiagnosticPick.widest(R.contentW(), SearchingText.NONE_YET)) > R.contentW()) {
            bad.add(view + ": the empty-list line does not fit");
         }
      }
      return bad;
   }

   private static int countLines(List<SearchingText.Para> paras, int cw) {
      int n = 0;
      for (SearchingText.Para p : paras) {
         n += SearchingText.wrap(p.text(), cw, RealFont.get()::width).size();
      }
      return n;
   }

   /** Every placed line: on the content edge, inside the column. */
   static void lines(List<String> bad, String view, SearchingText.Flow f, int x, int cw) {
      for (SearchingText.Line line : f.lines()) {
         if (line.x() != x) {
            bad.add(view + ": a line starts at " + line.x() + ", not the content edge " + x + " — \"" + line.text() + "\"");
            return;
         }
         if (w(line.text()) > cw) {
            bad.add(view + ": a line is " + w(line.text()) + " px in a " + cw + " px column — \"" + line.text() + "\"");
            return;
         }
      }
   }

   /** No paragraph ends on a stranded word. */
   static void paragraphs(List<String> bad, String view, List<SearchingText.Para> paras, int cw) {
      for (SearchingText.Para p : paras) {
         String why = stranded(SearchingText.wrap(p.text(), cw, RealFont.get()::width));
         if (why != null) {
            bad.add(view + ": " + why);
            return;
         }
      }
   }

   /** Why these lines end on a stranded word, or null when they do not. */
   static String stranded(List<String> lines) {
      if (lines.size() < 2) {
         return null;
      }
      int widest = 0;
      for (String l : lines) {
         widest = Math.max(widest, w(l));
      }
      String last = lines.get(lines.size() - 1);
      if (w(last) < widest * MIN_LAST_LINE) {
         return "a paragraph ends on \"" + last.replaceAll("§.", "") + "\" (" + w(last) + " px after a " + widest + " px line)";
      }
      return null;
   }

   /** The three controls. */
   static boolean controls() {
      String text = "§e⏳ Searching: §f" + SCREENSHOT_SUMMARY;
      // A width where greedy wrapping leaves a stranded last line — the screenshot's "64)".
      int at = -1;
      for (int px = 140; px <= 640 && at < 0; px++) {
         if (stranded(ResultsLayout.wrap(text, px, RealFont.get()::width)) != null) {
            at = px;
         }
      }
      boolean greedyCaught = at > 0;
      boolean evenPasses = at > 0 && stranded(SearchingText.wrap(text, at, RealFont.get()::width)) == null;
      if (at > 0) {
         List<String> g = ResultsLayout.wrap(text, at, RealFont.get()::width);
         System.out.println("     greedy at " + at + " px ends on \"" + g.get(g.size() - 1).replaceAll("§.", "")
               + "\"; the even wrap's lines: " + SearchingText.wrap(text, at, RealFont.get()::width).size());
      }
      List<String> bad = new ArrayList<>();
      lines(bad, "control", new SearchingText.Flow(List.of(new SearchingText.Line("§7centred", 120, 60)), 72, 0, 1), 20, 300);
      boolean centredCaught = !bad.isEmpty();
      bad.clear();
      lines(bad, "control", new SearchingText.Flow(List.of(new SearchingText.Line(WORST_SUMMARY, 20, 60)), 72, 0, 1), 20, 300);
      boolean wideCaught = !bad.isEmpty();
      return greedyCaught && evenPasses && centredCaught && wideCaught;
   }

   /** SpawnWishScreen.widest, with the game's font. */
   static final class LayoutDiagnosticPick {
      static String widest(int px, String... wordings) {
         for (String s : wordings) {
            if (w(s) <= px) {
               return s;
            }
         }
         return wordings[wordings.length - 1];
      }
   }
}
