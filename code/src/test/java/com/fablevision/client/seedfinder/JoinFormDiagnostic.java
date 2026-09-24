package com.fablevision.client.seedfinder;

import java.util.List;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import com.fablevision.client.seedfinder.SeedCriteria.Find;

/**
 * THE JOIN MESSAGE IS SHORT UNLESS ONE OF THE THREE AGREED CASES APPLIES.
 *
 * The long form came back twice after being made the exception, both times because a rule quietly
 * widened: a 32-block "gap" that every Fast search crosses, and "any note" including notes that only
 * explain. This pins the rule to the list — another dimension, a big spawn-vs-origin gap, something
 * dropped — with the ordinary Fast result (a 100–300 block spawn offset) as the case that must stay short.
 *
 * {@code gradlew joinDiag}
 */
public final class JoinFormDiagnostic {
   private static int failed;

   public static void main(String[] args) {
      System.out.println("========== WHICH JOIN MESSAGE ==========");
      Find nearOverworld = new Find("Village", Dim.OVERWORLD, 120, -40, 90, -1, null);
      // The ordinary Fast result: aimed at 0,0, found 80 from there, 140 from a spawn 190 blocks out.
      Find typicalFast = new Find("Village", Dim.OVERWORLD, 60, 50, 80, 140, null);
      Find bigGap = new Find("Village", Dim.OVERWORLD, 400, 0, 400, 20, null);
      Find nether = new Find("Nether Fortress", Dim.NETHER, 60, 20, 63, -1, null);

      expect("Exact mode, Overworld only", false, List.of(nearOverworld), "");
      expect("ordinary Fast result (60-block gap)", false, List.of(typicalFast), "");
      expect("an explanation note (ℹ)", false, List.of(typicalFast),
            WishParser.PLAIN_NOTE + "Nether structures are spread about one per 432 blocks.");
      expect("the stronghold explanation", false, List.of(typicalFast), WishParser.STRONGHOLD_CANT);
      expect("a find in the Nether", true, List.of(typicalFast, nether), "");
      expect("a 380-block spawn-vs-origin gap", true, List.of(bigGap), "");
      expect("something dropped", true, List.of(typicalFast), "the armorer inside the village");
      // 1.44.2: a slime-chunk or "no desert" search has nothing to point at. That is not one of the
      // three cases, and treating it as one is what joinRepro caught on real searches.
      expect("no structured finds (slime / exclusion search)", false, List.of(), "");
      // Notes are joined with "; ". Each part is judged on its own, in either order.
      expect("explanation, then something dropped", true, List.of(typicalFast),
            WishParser.PLAIN_NOTE + "Nether structures are spread out.; the armorer inside the village");
      expect("something dropped, then an explanation", true, List.of(typicalFast),
            "the armorer inside the village; " + WishParser.PLAIN_NOTE + "Nether structures are spread out.");
      expect("two explanations", false, List.of(typicalFast),
            WishParser.PLAIN_NOTE + "one thing.; " + WishParser.STRONGHOLD_CANT);

      // 1.44.4: the slime explanation once per session. The two spellings the app writes today — the
      // picker's (SpawnWishScreen) and the wish parser's (WishParser) — and a note carrying other parts.
      String picker = WishParser.PLAIN_NOTE + "Slime chunks are 1 in 10, so 1 within 64 blocks is true of nearly every"
            + " seed — ask for more of them if you want this to narrow anything.";
      String parsed = "3 slime chunks within 200 blocks is true of virtually every seed, so it didn't narrow anything"
            + " — ask for them closer together if you want it to mean something";
      String other = "the armorer inside the village";
      check("first slime search: the note is shown", JoinForm.slimeNoteOnce(picker).equals(picker));
      check("second slime search: it is not", JoinForm.slimeNoteOnce(picker).isEmpty());
      check("nor in the wish parser's spelling", JoinForm.slimeNoteOnce(parsed).isEmpty());
      check("and only the slime part of a combined note is dropped",
            JoinForm.slimeNoteOnce(other + "; " + parsed).equals(other));
      check("a note with no slime part is untouched", JoinForm.slimeNoteOnce(other).equals(other));

      // 1.44.5: the hidden-coordinates hint must send the player somewhere coordinates really show,
      // from inside the world. It named "the map screen", whose Coords toggle is only reachable from
      // Create World. The own-world map shows coordinates whatever the setting; the search-result map
      // does not unless it is on.
      String hint = JoinForm.coordsHiddenHint("N");
      check("hint names the map key", hint.contains("press N"));
      check("the map that key opens (your own world) shows coordinates with the setting OFF",
            SeedMapModel.coordsShown(false, false));
      check("the search-result map does NOT with the setting off, so the hint must not send people there",
            !SeedMapModel.coordsShown(true, false) && !hint.contains("map screen"));
      check("hint names where the setting is for next time", hint.contains("Custom spawn"));
      check("hint has no digits, so the spoiler filter cannot mangle it", !hint.matches(".*[0-9].*"));

      System.out.println(failed == 0 ? "ALL CHECKS PASS" : failed + " FAILED");
      System.exit(failed == 0 ? 0 : 1);
   }

   private static void expect(String what, boolean longForm, List<Find> finds, String note) {
      boolean got = JoinForm.needsLongForm(finds, note);
      boolean ok = got == longForm;
      System.out.println("  " + (ok ? "ok   " : "FAIL ") + what + " -> " + (got ? "LONG" : "short")
            + (ok ? "" : "   (expected " + (longForm ? "LONG" : "short") + ")"));
      if (!ok) {
         failed++;
      }
   }

   private static void check(String what, boolean ok) {
      System.out.println("  " + (ok ? "ok   " : "FAIL ") + what);
      if (!ok) {
         failed++;
      }
   }

   private JoinFormDiagnostic() {
   }
}
