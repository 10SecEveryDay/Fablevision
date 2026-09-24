package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.List;
import com.fablevision.VillageDiagnostic;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;

/**
 * Reported bugs, reproduced headlessly before anything is changed.
 *
 * Every case here came from someone playing the mod, and each one is written as the shortest thing
 * that either fails or does not. The point is to see the failure with the real code rather than
 * reason about it from the source and fix the wrong thing — this project has already spent two
 * releases discovering that a confident diagnosis of the finder was actually a bug in the tool
 * checking it.
 *
 * {@code gradlew repro}
 */
public final class ReproDiagnostic {

   public static void main(String[] args) {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      List<SeedCriteria.StructureTarget> catalog = SeedCatalog.structures(ctx);

      System.out.println("=========== BUG 3: armorer with an outpost within 200 ===========");
      armorerOutpost(ctx, catalog);

      System.out.println();
      System.out.println("=========== BUG 4: seven biomes at once ===========");
      manyBiomes(ctx, catalog);

      System.out.println();
      System.out.println("=========== BUG 5: does a plain wish mention slime? ===========");
      slimeLeak(ctx, catalog);

      System.out.println();
      System.out.println("=========== BUG 6: spoilers OFF still prints coordinates ===========");
      spoilerLeak(ctx, catalog);

      System.out.println();
      System.out.println("=========== BUG 8: does the SHORT join message hide coordinates? ===========");
      shortFormSpoilers(ctx, catalog);

      System.out.println();
      System.out.println("=========== BUG 7: \"Fast, 100 blocks\" returned 139 from spawn ===========");
      for (int distance : new int[]{100, 400}) {
         fastDistanceHonesty(ctx, catalog, distance);
      }
      // Every "<-- BUG" above used to be printed and the run still exited 0 (fixed 1.44.2).
      System.out.println(problems == 0 ? "ALL REPRO CHECKS PASS" : problems + " PROBLEM(S)");
      System.exit(problems == 0 ? 0 : 1);
   }

   private static int problems;

   /**
    * The SHORT join message must hide coordinates too when spoilers are off.
    *
    * The short form is built in FableVisionClient, which needs a running client — but what has to
    * be true is a property of the STRING, and that can be checked here. The lines are built in the
    * same shape the announcer builds them, from real finds produced by the real funnel, so a change
    * to the format shows up as a failure rather than as a quietly leaking message.
    *
    * This is the check the original spoiler filter did not have, which is why the slime row was
    * able to print exact chunk coordinates to somebody who had turned spoilers off: the filter was
    * tested against the patterns it knew about, not against what the app actually printed.
    */
   private static void shortFormSpoilers(WorldgenContext ctx, List<SeedCriteria.StructureTarget> catalog) {
      SeedCriteria.StructureTarget village = byLabel(catalog, "Surface Village");
      SeedCriteria.StructureTarget mansion = byLabel(catalog, "Woodland Mansion");
      if (village == null || mansion == null) {
         System.out.println("  rows missing — cannot reproduce");
         return;
      }
      SeedCriteria c = new SeedCriteria();
      c.structures.add(village.withRadius(500));
      c.biomes.add(new SeedCriteria.BiomeTarget(
            net.minecraft.resources.ResourceKey.create(
                  net.minecraft.core.registries.Registries.BIOME,
                  net.minecraft.resources.Identifier.withDefaultNamespace("jungle")),
            "Jungle", SeedCriteria.Dim.OVERWORLD, 800, 0, 0));

      java.util.regex.Pattern position = java.util.regex.Pattern.compile("-?\\d+, -?\\d+");
      int checked = 0;
      int leaks = 0;
      for (long seed = 1; seed < 4000 && checked < 6; seed++) {
         for (boolean fast : new boolean[]{false, true}) {
            List<String> lines = new ArrayList<>();
            List<SeedCriteria.Find> finds = new ArrayList<>();
            if (!c.test(seed, ctx, lines, fast, null, true, finds) || finds.isEmpty()) {
               continue;
            }
            checked++;
            for (SeedCriteria.Find f : finds) {
               // EXACTLY the shape FableVisionClient.announceShort builds.
               String line = "§7· §f" + f.label() + " §7- §f" + f.x() + ", " + f.z()
                     + " §7(" + f.shown() + " blocks)"
                     + (f.extra() == null || f.extra().isEmpty() ? "" : " §8" + f.extra());
               String hidden = SeedCriteria.withoutCoords(line);
               boolean leaked = position.matcher(hidden).find();
               if (leaked) {
                  leaks++;
                  System.out.println("  LEAK (" + (fast ? "fast" : "exact") + ", seed " + seed + ")");
                  System.out.println("        raw:    " + line);
                  System.out.println("        hidden: " + hidden);
               }
               // The distance has to SURVIVE. A line with neither a position nor a distance says
               // nothing at all, and the setting is "don't tell me where", not "don't tell me".
               if (!hidden.contains(" blocks)")) {
                  leaks++;
                  System.out.println("  DISTANCE LOST on seed " + seed + ": " + hidden);
               }
            }
            break;
         }
      }
      System.out.println("  short-form lines from " + checked + " matching seeds -> " + leaks
            + (leaks == 0 ? " leaks, distances kept  ✓" : " PROBLEMS  <-- BUG"));
      problems += leaks;
   }

   /**
    * Fast mode set to N must never hand back a find further than N from the real spawn.
    *
    * The reported failure: Fast set to 100, the world loads, the chat says the village is 139
    * blocks away. Fast measures from the world origin and the player spawns somewhere else, so the
    * two numbers answered different questions and the app printed the wrong one confidently.
    *
    * This asks the shipped code the player's question directly — it reads the distance off the
    * finished match line, the same string the chat prints, rather than recomputing it from the
    * inside where a bug in the check would cancel out a bug in the measurement.
    */
   private static void fastDistanceHonesty(WorldgenContext ctx,
                                           List<SeedCriteria.StructureTarget> catalog, int distance) {
      SeedCriteria.StructureTarget village = byLabel(catalog, "Surface Village");
      if (village == null) {
         return;
      }
      SeedCriteria c = new SeedCriteria();
      c.structures.add(village.withRadius(distance));
      java.util.regex.Pattern checked =
            java.util.regex.Pattern.compile("\\((\\d+) from your spawn\\)");
      int accepted = 0;
      int rejected = 0;
      int overshoot = 0;
      int worst = 0;
      long started = System.currentTimeMillis();
      List<String> lines = new ArrayList<>();
      for (long seed = 1; accepted < 150 && seed < 200_000; seed++) {
         lines.clear();
         // The exact call SeedFinder makes on a hit: fast, describing, which is now also verifying.
         if (!c.test(seed, ctx, lines, true, null, true)) {
            // Only count it as a rejection if the plain fast pass DID match — otherwise this is
            // just a seed that never had a village, which says nothing about the re-check.
            List<String> plain = new ArrayList<>();
            if (c.test(seed, ctx, plain, true)) {
               rejected++;
            }
            continue;
         }
         accepted++;
         for (String line : lines) {
            java.util.regex.Matcher m = checked.matcher(line);
            while (m.find()) {
               int away = Integer.parseInt(m.group(1));
               worst = Math.max(worst, away);
               if (away > distance) {
                  overshoot++;
                  System.out.println("  OVERSHOOT seed " + seed + ": " + line);
               }
            }
         }
      }
      System.out.printf("  ≤%-4d  accepted %d, turned down %d (%.1f%% of hits)  worst kept %d  "
                  + "overshoots %d%s   %.1fs%n",
            distance, accepted, rejected,
            accepted + rejected == 0 ? 0.0 : 100.0 * rejected / (accepted + rejected),
            worst, overshoot, overshoot == 0 ? "  ✓" : "  <-- BUG",
            (System.currentTimeMillis() - started) / 1000.0);
      problems += overshoot;
   }

   /**
    * With "Seed spoilers" OFF, no line the chat prints may still carry a position.
    *
    * Deliberately checks the OUTPUT rather than the patterns withoutCoords knows about — that is
    * what let the slime row through. Any pair of numbers separated by a comma is treated as a
    * position here, which is the same rule the fix applies, so the two cannot drift apart.
    */
   private static void spoilerLeak(WorldgenContext ctx, List<SeedCriteria.StructureTarget> catalog) {
      SeedCriteria.StructureTarget village = byLabel(catalog, "Surface Village");
      if (village == null) {
         System.out.println("  village row missing — cannot reproduce");
         return;
      }
      // The combination the bug needs: something that goes through findLine (the village) AND the
      // slime row, which writes its own line and was therefore never stripped.
      SeedCriteria c = new SeedCriteria();
      c.structures.add(village.withRadius(400));
      c.slimes.add(new SeedCriteria.SlimeTarget(3, 64));
      java.util.regex.Pattern position = java.util.regex.Pattern.compile("-?\\d+, -?\\d+");
      int checked = 0;
      int leaks = 0;
      List<String> lines = new ArrayList<>();
      for (long seed = 1; seed < 4000 && checked < 5; seed++) {
         lines.clear();
         // Both modes, because Fast mode adds two lines of its own that Exact never writes.
         for (boolean fast : new boolean[]{false, true}) {
            lines.clear();
            if (!c.test(seed, ctx, lines, fast, null, fast)) {
               continue;
            }
            checked++;
            for (String raw : lines) {
               String hidden = SeedCriteria.withoutCoords(raw);
               if (position.matcher(hidden).find()) {
                  leaks++;
                  System.out.println("  LEAK (" + (fast ? "fast" : "exact") + ", seed " + seed + ")");
                  System.out.println("        raw:    " + raw);
                  System.out.println("        hidden: " + hidden);
               }
            }
            break;
         }
      }
      System.out.println("  checked " + checked + " matching seeds -> " + leaks
            + (leaks == 0 ? " leaks  ✓" : " LEAKED LINES  <-- BUG"));
      problems += leaks;
   }

   /** "a village with an armorer and a pillager outpost within 200 blocks" — reported as failing. */
   private static void armorerOutpost(WorldgenContext ctx, List<SeedCriteria.StructureTarget> catalog) {
      SeedCriteria.StructureTarget armorer = byLabel(catalog, "Village with Armorer");
      SeedCriteria.StructureTarget outpost = byLabel(catalog, "Pillager Outpost");
      if (armorer == null || outpost == null) {
         System.out.println("  rows missing — cannot reproduce");
         return;
      }
      SeedCriteria c = new SeedCriteria();
      SeedCriteria.StructureTarget a = armorer.withRadius(200);
      SeedCriteria.StructureTarget o = outpost.withRadius(200);
      c.structures.add(a);
      c.structures.add(o);
      String err = c.addStructurePair(a, o, 200);
      System.out.println("  addStructurePair -> " + (err == null ? "accepted" : "REFUSED: " + err));
      System.out.println("  summary: " + c.summary());
      System.out.println("  targets after wiring:");
      for (SeedCriteria.StructureTarget t : c.structures) {
         System.out.println("     " + t.label + "  radius " + t.radius
               + (t.building != null ? "  piece=" + t.building : ""));
      }
      for (SeedCriteria.Adjacency adj : c.adjacencies) {
         System.out.println("     PAIR " + adj.anchorLabel() + " -> " + adj.otherLabel()
               + " within " + adj.within);
      }
      scan(c, ctx, 30_000, false);
      scan(c, ctx, 30_000, true);
   }

   /**
    * "7 wood types near spawn" — how many biomes does a wish actually keep?
    *
    * READS THE SHIPPED CAP instead of restating it. This hardcoded 3 until the 1.41.0 audit, while
    * WishParser.MAX_BIOMES had been 6 since 1.39.0 — so the line it printed, "asked for 7, kept 3",
    * looked like a report of the app's behaviour and was a report of this file's own constant. That
    * is the same failure as a test that reimplements what it is testing: it agrees with itself
    * whatever the real code does, and it had been printing a wrong number into the regression log
    * for two releases.
    */
   private static void manyBiomes(WorldgenContext ctx, List<SeedCriteria.StructureTarget> catalog) {
      String[] want = {"forest", "birch_forest", "dark_forest", "taiga", "jungle", "savanna",
            "cherry_grove"};
      System.out.println("  cap under test: WishParser.MAX_BIOMES = " + WishParser.MAX_BIOMES);
      SeedCriteria c = new SeedCriteria();
      for (String b : want) {
         if (c.biomes.size() >= WishParser.MAX_BIOMES) {
            System.out.println("  DROPPED at the cap: " + b);
            continue;
         }
         c.biomes.add(new SeedCriteria.BiomeTarget(
               net.minecraft.resources.ResourceKey.create(
                     net.minecraft.core.registries.Registries.BIOME,
                     net.minecraft.resources.Identifier.withDefaultNamespace(b)),
               b, SeedCriteria.Dim.OVERWORLD, 1000, 0, 0));
      }
      System.out.println("  asked for " + want.length + ", kept " + c.biomes.size());
      System.out.println("  summary: " + c.summary());
      scan(c, ctx, 4_000, false);
   }

   /** A wish with nothing to do with slime must not produce a slime line. */
   private static void slimeLeak(WorldgenContext ctx, List<SeedCriteria.StructureTarget> catalog) {
      SeedCriteria.StructureTarget village = byLabel(catalog, "Surface Village");
      if (village == null) {
         return;
      }
      SeedCriteria c = new SeedCriteria();
      c.structures.add(village.withRadius(300));
      List<String> lines = new ArrayList<>();
      for (long seed = 1; seed < 400; seed++) {
         lines.clear();
         if (c.test(seed, ctx, lines, false)) {
            boolean slime = lines.stream().anyMatch(l -> l.toLowerCase().contains("slime"));
            System.out.println("  seed " + seed + " -> " + lines.size() + " lines, slime line? "
                  + (slime ? "YES  <-- LEAK" : "no"));
            if (slime) {
               problems++;
            }
            for (String l : lines) {
               System.out.println("        " + l);
            }
            return;
         }
      }
   }

   /** Run the wish and report whether it can be satisfied at all. */
   private static void scan(SeedCriteria c, WorldgenContext ctx, int seeds, boolean fast) {
      long started = System.currentTimeMillis();
      int hits = 0;
      List<String> lines = new ArrayList<>();
      List<String> firstHit = null;
      for (long seed = 1; seed <= seeds; seed++) {
         lines.clear();
         if (c.test(seed, ctx, lines, fast)) {
            hits++;
            if (firstHit == null) {
               firstHit = List.copyOf(lines);
            }
         }
      }
      System.out.printf("  %-6s %,d seeds in %.1fs -> %d hits%s%n", fast ? "FAST" : "EXACT", seeds,
            (System.currentTimeMillis() - started) / 1000.0, hits,
            hits == 0 ? "   <-- CANNOT BE SATISFIED" : "");
      if (firstHit != null) {
         for (String l : firstHit) {
            System.out.println("        " + l);
         }
      }
   }

   private static SeedCriteria.StructureTarget byLabel(List<SeedCriteria.StructureTarget> catalog,
                                                       String label) {
      for (SeedCriteria.StructureTarget t : catalog) {
         if (t.label.equals(label) && t.special == SeedCriteria.Special.NORMAL) {
            return t;
         }
      }
      return null;
   }

   private ReproDiagnostic() {
   }
}
