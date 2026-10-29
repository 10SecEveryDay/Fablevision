package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * THE SEARCHING SCREENS' TEXT, as lines at positions (1.46.2) — built here for the screen to draw and for
 * layoutDiag to check, so the two cannot disagree.
 *
 * What it fixes, from one screenshot:
 *   - MIXED ALIGNMENT. The title and status lines were centred while "Worlds appear here…", the list and
 *     the feedback line were left-aligned. Everything is LEFT-aligned on the content edge now, because
 *     the list of worlds (with its buttons on the right) can only be left-aligned.
 *   - A LONE "64)". Greedy wrapping filled each line and left whatever was over on the last one, so a
 *     wish summary ended in a line holding "64)". {@link #wrap} keeps the line count greedy wrapping
 *     needs and then evens the lines out, so no paragraph ends on a stranded word.
 *   - The stale "Stopped after…" line is the screen's (cleared when a search starts); this only lays out
 *     what it is given.
 */
public final class SearchingText {

   public static final int LINE = 12;
   /** Space before a paragraph that starts a new group (a note after the progress lines). */
   public static final int GROUP_GAP = 4;
   /** Where the status lines start, below the panel's top: the title is at +10, the subtitle at +24. */
   public static final int STATUS_OFFSET = 60;

   /** One paragraph; {@code gapBefore} pixels go above it. */
   public record Para(String text, int gapBefore) {
      public static Para of(String text) {
         return new Para(text, 0);
      }

      public static Para group(String text) {
         return new Para(text, GROUP_GAP);
      }
   }

   /** One drawn line. */
   public record Line(String text, int x, int y) {}

   /**
    * The lines placed, where the next text would start, and how many lines did not fit above
    * {@code limit} (they are not drawn; layoutDiag fails a real window size that drops a progress line).
    */
   public record Flow(List<Line> lines, int endY, int dropped, int parasShown) {}

   private SearchingText() {
   }

   /**
    * Lays the paragraphs out from {@code y}, left-aligned at {@code x}, each wrapped to {@code w}, never
    * past {@code limit} (a line needs {@link #LINE} pixels). Paragraphs are placed in order; once one line
    * does not fit, nothing after it is placed, so what is shown is always a top part of the list.
    */
   public static Flow flow(List<Para> paras, int x, int y, int w, int limit, ToIntFunction<String> width) {
      List<Line> out = new ArrayList<>();
      int dropped = 0;
      int shown = 0;
      boolean full = false;
      for (Para p : paras) {
         if (p.text() == null || p.text().isEmpty()) {
            continue;
         }
         List<String> lines = wrap(p.text(), w, width);
         if (full) {
            dropped += lines.size();
            continue;
         }
         int py = y + (out.isEmpty() ? 0 : p.gapBefore());
         int placed = 0;
         for (String line : lines) {
            if (py + LINE > limit) {
               full = true;
               break;
            }
            out.add(new Line(line, x, py));
            py += LINE;
            placed++;
         }
         dropped += lines.size() - placed;
         if (placed > 0) {
            y = py;
            shown++;
         }
      }
      return new Flow(out, y, dropped, shown);
   }

   /**
    * Word-wrapped to {@code px} with the colour carried (see {@link ResultsLayout#wrap}), then EVENED OUT:
    * the narrowest width that still gives the same number of lines, so the words spread across all of
    * them instead of leaving one or two on the last. A paragraph that fits one line is untouched.
    */
   public static List<String> wrap(String text, int px, ToIntFunction<String> width) {
      List<String> greedy = ResultsLayout.wrap(text, px, width);
      if (greedy.size() < 2) {
         return greedy;
      }
      int n = greedy.size();
      int lo = 1;
      int hi = px;
      while (lo < hi) {
         int mid = (lo + hi) >>> 1;
         if (ResultsLayout.wrap(text, mid, width).size() <= n) {
            hi = mid;
         } else {
            lo = mid + 1;
         }
      }
      List<String> even = ResultsLayout.wrap(text, lo, width);
      return even.size() == n ? even : greedy;
   }

   // ── What the searching screens say ─────────────────────────────────────────────────────────────────

   /** Everything the status part of a searching screen is built from. Null/empty/0 means "not shown". */
   public record Status(String summary, long checked, long rate, int threads, String eta, boolean estimating,
                        boolean estimateFailed, String parked, String slow, long nearMisses, String warning,
                        String note, boolean longRunning, String bottleneck) {}

   public static final String ESTIMATING = "§8working out how rare this is…";
   public static final String NO_ESTIMATE =
         "§8no time estimate for this wish — it couldn't be measured. The search itself is unaffected.";
   public static final String ONE_PUT_IN = "§7the moment one is found, it's put into your world settings";
   public static final String SEVERAL_LISTED =
         "§7each world is listed as it passes — none goes into your world settings until you pick it";
   /** The empty list while a search for several runs, longest first. */
   public static final String[] NONE_YET = {"§7Worlds appear here as they are found.", "§7None found yet."};

   /** The single search's lines: the wish, progress, the estimate, what happens on a find, then the notes. */
   public static List<Para> single(Status s) {
      List<Para> out = new ArrayList<>();
      out.add(Para.of("§e⏳ Searching: §f" + s.summary()));
      out.add(Para.of(progress(s)));
      estimate(out, s);
      out.add(Para.group(s.parked() != null ? s.parked() : ONE_PUT_IN));
      notes(out, s);
      return out;
   }

   /** A search for several worlds: "Found 2 of 3" first (the line waited on), then as the single search. */
   public static List<Para> several(Status s, int found, int want, String capped) {
      List<Para> out = new ArrayList<>();
      out.add(Para.of(String.format("§aFound %d of %d worlds §7· checked %,d · %,d/sec · %d bots", found, want,
            s.checked(), s.rate(), s.threads()) + stutter(s) + (capped == null ? "" : capped)));
      out.add(Para.of("§e⏳ Searching: §f" + s.summary()));
      estimate(out, s);
      out.add(Para.of(SEVERAL_LISTED));
      notes(out, s);
      return out;
   }

   /** The found-worlds list once the search has stopped: what was found, how long it took, and the note. */
   public static List<Para> results(boolean complete, boolean failed, int found, int want, String capped,
                                    String summary, long checked, String took, boolean oneInSettings, String note) {
      List<Para> out = new ArrayList<>();
      String head = complete ? "§aFound " + found + " of " + want + " worlds"
            : failed ? "§e" + found + " of " + want + " worlds found before the search had to stop"
            : "§eStopped with " + found + " of " + want + " worlds";
      out.add(Para.of(head + (capped == null ? "" : capped) + " §7for: §f" + summary));
      out.add(Para.of("§7Searched " + AppliedSeed.shortCount(checked) + " seeds in " + took + ". "
            + (oneInSettings ? "The ✓ one is in your world settings." : "None is in your world settings until you pick one.")));
      if (note != null && !note.isEmpty()) {
         out.add(Para.of(note.startsWith(WishParser.STRONGHOLD_CANT) || note.startsWith(WishParser.PLAIN_NOTE)
               ? "§e" + note : "§6Can't check: §f" + note));
      }
      return out;
   }

   private static String progress(Status s) {
      return String.format("§7checked %,d · %,d/sec · %d bots working", s.checked(), s.rate(), s.threads()) + stutter(s);
   }

   /** More bots than auto (the Bots setting, 1.47.0): said where the count is, every time it is shown. */
   private static String stutter(Status s) {
      return SeedFinder.busy(s.threads()) ? " §e(more than auto: the game may stutter)§7" : "";
   }

   private static void estimate(List<Para> out, Status s) {
      if (s.eta() != null) {
         out.add(Para.of(s.eta()));
      } else if (s.estimating()) {
         out.add(Para.of(ESTIMATING));
      } else if (s.estimateFailed()) {
         out.add(Para.of(NO_ESTIMATE));
      }
   }

   /** The watchdog's line, Fast mode's near-misses, the wait warning, the note, and the hardest part. */
   private static void notes(List<Para> out, Status s) {
      if (s.slow() != null) {
         out.add(Para.of("§6" + s.slow()));
      }
      if (s.nearMisses() > 0) {
         out.add(Para.of("§8" + s.nearMisses() + " near-miss" + (s.nearMisses() == 1 ? "" : "es")
               + " turned down — looked right from the world origin, too far from the real spawn"));
      }
      if (s.warning() != null && !s.warning().isEmpty()) {
         out.add(Para.group("§e" + WishParser.stripPlainNote(s.warning())));
      }
      if (s.note() != null && !s.note().isEmpty()) {
         String note = s.note();
         out.add(Para.group(note.startsWith(WishParser.STRONGHOLD_CANT) || note.startsWith(WishParser.PLAIN_NOTE)
               ? "§e" + note : "§6Can't check: §f" + note + " §6— searching the rest."));
      }
      if (s.longRunning()) {
         if (s.bottleneck() == null) {
            out.add(Para.group("§7Taking long — working out which part is the rare one…"));
         } else {
            out.add(Para.group("§eHardest part: §f" + s.bottleneck()));
            out.add(Para.of("§7Stop and drop that one, or give it more distance."));
         }
      }
   }
}
