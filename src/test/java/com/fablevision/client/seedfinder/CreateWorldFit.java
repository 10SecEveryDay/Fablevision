package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * layoutDiag's Create World section (1.46.2): the finder's buttons against vanilla's real layout, at every
 * window width from 320 to 1920 and every height layoutDiag checks, with and without a found seed's count
 * line. See {@link VanillaCreateWorld} for where vanilla's rectangles come from.
 *
 * Checked INDEPENDENTLY of CreateWorldColumn's own test: every placed box against every vanilla box for a
 * real overlap, on screen, ours apart from each other, every label inside its button (the game's font),
 * and at every size the game can produce (320x240 up) both buttons present. THE CONTROL is the 1.46.1
 * placement — the fixed column that drew over Game Mode — run through the same check: it must fail at the
 * reported window and the new one must pass there.
 */
final class CreateWorldFit {

   /** The longest count line a found seed can have: AppliedSeed.shortCount + " seeds · " + prettyMs. */
   static final String WORST_COUNT = "§a✓ §7" + AppliedSeed.shortCount(1_400_000_000_000L) + " seeds · 1440m 00s";
   private static final int REAL_MIN_W = 320;
   private static final int REAL_MIN_H = 240;

   private CreateWorldFit() {
   }

   /** Runs the section; returns how many failures it found. */
   static int run(int[] heights) {
      System.out.println();
      System.out.println("  -- the Create World screen: our buttons against vanilla's real layout --");
      int failures = 0;
      List<String> trip = VanillaCreateWorld.tripwires();
      if (!trip.isEmpty()) {
         failures++;
         System.out.println("  FAIL vanilla's Create World layout is not the one the model copies:");
         trip.forEach(t -> System.out.println("        " + t));
      } else {
         System.out.println("  vanilla's constants match the model (210 / 310 / 44 / 24 / 33 …), Experiments button: "
               + (VanillaCreateWorld.unstable() ? "shown (unstable build)" : "not shown (release)"));
      }
      int checked = 0;
      int failedSizes = 0;
      int fewest = Integer.MAX_VALUE;
      Map<String, Integer> where = new LinkedHashMap<>();
      for (int w = REAL_MIN_W; w <= 1920; w++) {
         for (int h : heights) {
            List<CreateWorldColumn.Box> vanilla = VanillaCreateWorld.bounds(w, h);
            fewest = Math.min(fewest, vanilla.size());
            int header = VanillaCreateWorld.headerBottom(w);
            for (String count : new String[]{null, WORST_COUNT}) {
               checked++;
               CreateWorldColumn.Placement p = CreateWorldColumn.place(w, h, header, h - VanillaCreateWorld.FOOTER_H,
                     vanilla, count, RealFont.get()::width);
               where.merge(p.tier() + " · " + p.where() + (p.map() == null && p.spawn() != null ? " · spawn only" : "")
                     + (count != null ? (p.stats() != null ? " · count shown" : " · count in tooltip") : ""), 1, Integer::sum);
               List<String> bad = check(w, h, vanilla, p, count);
               if (!bad.isEmpty()) {
                  failedSizes++;
                  if (failedSizes <= 12) {
                     System.out.println("  FAIL Create World " + w + "x" + h + (count != null ? " (found seed)" : ""));
                     bad.forEach(b -> System.out.println("        " + b));
                  }
               }
            }
         }
      }
      if (fewest < 20) {
         failures++;
         System.out.println("  FAIL the vanilla model gave only " + fewest + " rectangles somewhere — it is not modelling the screen");
      }
      failures += failedSizes;
      System.out.println("  Create World placements checked: " + checked + " (every width 320–1920 × " + heights.length
            + " heights × with/without a count line), with a problem: " + failedSizes);
      System.out.println("  where they went:");
      where.forEach((k, v) -> System.out.println("     " + String.format("%6d", v) + "  " + k));
      for (int[] s : new int[][]{{427, 240}, {480, 270}, {640, 360}, {854, 480}, {320, 240}}) {
         CreateWorldColumn.Placement p = CreateWorldColumn.place(s[0], s[1], VanillaCreateWorld.headerBottom(s[0]),
               s[1] - VanillaCreateWorld.FOOTER_H, VanillaCreateWorld.bounds(s[0], s[1]), WORST_COUNT, RealFont.get()::width);
         System.out.println("     " + s[0] + "x" + s[1] + ": " + p.tier() + ", " + p.where() + ", spawn " + p.spawn()
               + ", map " + p.map() + ", count " + (p.stats() == null ? "in the map button's tooltip" : p.stats()));
      }
      boolean control = controlFails();
      System.out.println("  control: the 1.46.1 fixed column at 427x240 (a found seed in the box) is "
            + (control ? "caught (it overlaps vanilla), and the new placement there passes"
                  : "NOT CAUGHT — the Create World check is blind"));
      if (!control) {
         failures++;
      }
      return failures;
   }

   /** Problems with one placement, checked from scratch. */
   static List<String> check(int w, int h, List<CreateWorldColumn.Box> vanilla, CreateWorldColumn.Placement p, String count) {
      List<String> bad = new ArrayList<>();
      List<CreateWorldColumn.Box> ours = CreateWorldColumn.placed(p);
      String[] names = {"Custom spawn", "Seed map", "count line"};
      CreateWorldColumn.Box[] boxes = {p.spawn(), p.map(), p.stats()};
      for (int i = 0; i < boxes.length; i++) {
         CreateWorldColumn.Box b = boxes[i];
         if (b == null) {
            continue;
         }
         if (b.x() < 0 || b.y() < 0 || b.right() > w || b.bottom() > h) {
            bad.add(names[i] + " " + b + " is off the screen");
         }
         for (CreateWorldColumn.Box v : vanilla) {
            if (b.near(v, 0)) {
               bad.add(names[i] + " " + b + " is drawn over vanilla's " + v);
            }
         }
         for (int j = i + 1; j < boxes.length; j++) {
            if (boxes[j] != null && b.near(boxes[j], 0)) {
               bad.add(names[i] + " and " + names[j] + " overlap");
            }
         }
      }
      if (p.spawn() != null && RealFont.get().width(p.spawnLabel()) > p.spawn().w() - 4) {
         bad.add("\"" + p.spawnLabel() + "\" is " + RealFont.get().width(p.spawnLabel()) + " px in a " + p.spawn().w() + " px button");
      }
      if (p.map() != null && RealFont.get().width(p.mapLabel()) > p.map().w() - 4) {
         bad.add("\"" + p.mapLabel() + "\" is " + RealFont.get().width(p.mapLabel()) + " px in a " + p.map().w() + " px button");
      }
      if (p.stats() != null && count != null && RealFont.get().width(count) > p.stats().w()) {
         bad.add("the count line is wider than its box");
      }
      if (w >= REAL_MIN_W && h >= REAL_MIN_H && (p.spawn() == null || p.map() == null)) {
         bad.add("a window the game really makes, and " + (p.spawn() == null ? "neither button" : "only Custom spawn")
               + " found room");
      }
      if (ours.isEmpty() && p.spawn() != null) {
         bad.add("placed() lost the buttons");
      }
      return bad;
   }

   /** The 1.46.1 column must fail where it was reported; the new placement must pass there. */
   private static boolean controlFails() {
      int w = 427;
      int h = 240;
      List<CreateWorldColumn.Box> vanilla = VanillaCreateWorld.bounds(w, h);
      CreateWorldColumn.Placement old = new CreateWorldColumn.Placement(CreateWorldColumn.Tier.FULL, "1.46.1",
            new CreateWorldColumn.Box(w - 108, 6, 102, 20), new CreateWorldColumn.Box(w - 108, 28, 102, 20),
            new CreateWorldColumn.Box(w - 158, 72, 152, 20), "✨ Custom spawn", "🗺 Seed map");
      List<String> oldBad = check(w, h, vanilla, old, WORST_COUNT);
      CreateWorldColumn.Placement now = CreateWorldColumn.place(w, h, VanillaCreateWorld.headerBottom(w),
            h - VanillaCreateWorld.FOOTER_H, vanilla, WORST_COUNT, RealFont.get()::width);
      List<String> newBad = check(w, h, vanilla, now, WORST_COUNT);
      if (!oldBad.isEmpty()) {
         System.out.println("     1.46.1 at 427x240: " + oldBad.get(0) + (oldBad.size() > 1 ? " (+" + (oldBad.size() - 1) + " more)" : ""));
      }
      return !oldBad.isEmpty() && newBad.isEmpty();
   }
}
