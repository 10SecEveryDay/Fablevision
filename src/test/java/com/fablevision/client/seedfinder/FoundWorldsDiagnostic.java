package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fablevision.VillageDiagnostic;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;

/**
 * FIND N WORLDS, CHECKED WITHOUT A GAME (1.46.0). Every check prints ok/FAIL and the run ends with a count;
 * the exit code follows it, but the verdict lines are what to read.
 *
 *   1. THE LIST ({@link FoundWorlds}): add, the same seed twice, full, Stop, the owner check, clear,
 *      Copy all, the Worlds button's steps.
 *   2. "USE THIS SEED" stores THAT world: result 2 of 3 is applied and the stored finds, lines, seed and
 *      stats are result 2's and nobody else's — first on made-up worlds, then on three real ones.
 *   3. THE ENGINE, through its own per-seed path ({@link WorldsRun}): a search for 3 stops on 3 different
 *      seeds; the same seed offered twice is accepted once; a search for 1 is unchanged; and pregen's
 *      unbounded stream accepts exactly as it did before.
 *   4. THE ESTIMATE LINE: for one world, word for word what 1.45.1 printed; for N, "for N worlds", the
 *      long-wait warning, and the per-world allowance before "taking longer".
 *   5. THE LINES under each world are the join message's short lines, Coords hidden or shown alike.
 *
 * {@code gradlew worldsDiag}
 */
public final class FoundWorldsDiagnostic {

   private static int failed;
   private static int passed;

   public static void main(String[] args) throws Exception {
      // The applied stats are formatted with the default locale, as in the game; pinned so the expected
      // strings below mean one thing.
      Locale.setDefault(Locale.US);
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      System.out.println("========== FIND N WORLDS ==========");

      modelChecks();
      etaChecks();
      lineChecks();
      engineChecks();

      System.out.println();
      System.out.println(failed == 0 ? "ALL " + passed + " CHECKS PASS" : failed + " FAILED, " + passed + " passed");
      System.exit(failed == 0 ? 0 : 1);
   }

   // ── 1 and 2 on made-up worlds ────────────────────────────────────────────────────────────────────

   private static SeedFinder.Result fake(long seed, String label, int x, int z) {
      SeedCriteria.Find f = new SeedCriteria.Find(label, SeedCriteria.Dim.OVERWORLD, x, z, 100, 90, "");
      return new SeedFinder.Result(seed, List.of("Your Overworld spawn @ 0, 0", label + " @ " + x + ", " + z), List.of(f));
   }

   private static void modelChecks() {
      System.out.println();
      System.out.println("-- the list --");
      FoundWorlds list = new FoundWorlds();
      Object screen = new Object();
      Object registries = new Object();
      list.begin(null, 3, 0, "Surface Village within 200", "ℹ a note", screen, registries);
      SeedFinder.Result a = fake(111L, "Surface Village", 10, 20);
      SeedFinder.Result b = fake(222L, "Surface Village", -30, 40);
      SeedFinder.Result c = fake(333L, "Surface Village", 50, -60);
      check("an empty list, wanting 3", list.isEmpty() && list.want() == 3 && !list.complete());
      check("add the first", list.add(a, 1000, 2000) && list.size() == 1);
      check("add the second", list.add(b, 5000, 9000) && list.size() == 2);
      check("the SAME seed again is turned away", !list.add(fake(222L, "Other", 1, 1), 7000, 9500) && list.size() == 2);
      check("  and the one kept is the first one", list.get(1).finds().get(0).x() == -30);
      list.stopPressed();
      check("Stop keeps what was found", list.stopped() && list.size() == 2);
      check("add the third", list.add(c, 9000, 15000) && list.size() == 3 && list.complete());
      check("a fourth is past what was asked and is turned away", !list.add(fake(444L, "X", 0, 0), 1, 1) && list.size() == 3);
      check("Copy all is one seed per line, in order", list.copyAll().equals("111\n222\n333"));
      check("the worlds belong to the screen and registries they were found for", list.belongsTo(screen, registries));
      check("  not to another Create World screen", !list.belongsTo(new Object(), registries));
      check("  nor to the same screen with other registries (data packs)", !list.belongsTo(screen, new Object()));
      check("  nor to nothing", !list.belongsTo(null, null));

      // ── "Use this seed" on result 2 of 3 ──
      AppliedSeed.Applied applied = list.use(1);
      check("Use on result 2 of 3 stores seed 222", applied != null && "222".equals(AppliedSeed.seed));
      check("  with result 2's finds — and not 1's or 3's", AppliedSeed.finds.equals(b.finds())
            && !AppliedSeed.finds.equals(a.finds()) && !AppliedSeed.finds.equals(c.finds()));
      check("  with result 2's lines", AppliedSeed.matches.equals(b.matches()));
      check("  with the seeds checked and time when result 2 was found",
            AppliedSeed.checked == 5000 && AppliedSeed.took.equals(SeedFinder.prettyMs(9000)));
      check("  in the single search's wording",
            AppliedSeed.stats.equals("searched 5,000 seeds in 9s") && AppliedSeed.shortStats.equals("5,000 seeds · 9s"));
      check("  with the search's note", AppliedSeed.note.equals("ℹ a note"));
      check("  and the join message armed for that seed only",
            !AppliedSeed.consumeAnnounce("111") && AppliedSeed.consumeAnnounce("222") && !AppliedSeed.consumeAnnounce("222"));
      check("the list is still there after Use", list.size() == 3);
      check("Use on an index that is not there stores nothing", list.use(7) == null && "222".equals(AppliedSeed.seed));

      // The single search builds its Applied the same way — and the strings are the ones 1.45.1 stored.
      AppliedSeed.Applied one = AppliedSeed.Applied.of(42L, List.of("x"), List.of(), 1_234_567, 125_000, "");
      check("a single search's stored stats read as before (\"searched 1,234,567 seeds in 2m 05s\")",
            one.stats().equals("searched 1,234,567 seeds in 2m 05s") && one.shortStats().equals("1.2M seeds · 2m 05s")
                  && one.took().equals("2m 05s") && one.seed().equals("42"));

      AppliedSeed.forget();
      check("a new search forgets the applied seed and its finds, as before",
            AppliedSeed.seed == null && AppliedSeed.finds.isEmpty());

      list.clear();
      check("clear empties it and it belongs to nothing", list.isEmpty() && list.want() == 0 && !list.belongsTo(screen, registries));
      // 1.46.2: every number 1 to 10 (it skipped 4 and 6–9), and back the other way.
      boolean up = true;
      boolean down = true;
      for (int n = 1; n < 10; n++) {
         up &= FoundWorlds.next(n) == n + 1;
         down &= FoundWorlds.previous(n + 1) == n;
      }
      check("the Worlds button steps 1, 2, 3 … 10, then back to 1", up && FoundWorlds.next(10) == 1);
      check("back (right-click / shift-click) steps 10, 9 … 1, then round to 10", down && FoundWorlds.previous(1) == 10);
      check("an unknown value goes to 1 forwards and 10 back", FoundWorlds.next(0) == 1 && FoundWorlds.next(11) == 1
            && FoundWorlds.previous(0) == 10 && FoundWorlds.previous(11) == 10);
      check("the button offers every number 1 to 10 and the cap is 10", FoundWorlds.MAX == 10
            && java.util.Arrays.equals(FoundWorlds.CHOICES, new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}));
      list.begin(null, 50, 50, "", "", screen, registries);
      check("asked for 50, the list holds at most 10", list.want() == 10 && list.asked() == 50);
   }

   // ── 4. the estimate line ────────────────────────────────────────────────────────────────────────

   private static void etaChecks() {
      System.out.println();
      System.out.println("-- the time estimate --");
      SeedFinder.Estimate common = new SeedFinder.Estimate(1 / 40_000.0, false, false, "x");
      SeedFinder.Estimate rough = new SeedFinder.Estimate(1 / 40_000.0, false, true, "x");
      SeedFinder.Estimate atMost = new SeedFinder.Estimate(1 / 2001.0, true, false, "x");
      SeedFinder.Estimate rare = new SeedFinder.Estimate(1 / 1_000_000.0, false, false, "x");
      // What 1.45.1 printed for these, written out — the one-world line must not have moved.
      check("one world: unchanged", "§7Expect about 1 in 40,000 seeds — about 40 s at this speed"
            .equals(SpawnWishScreen.etaLine(common, 1000, 0)));
      check("one world, rough: unchanged", "§7Expect about 1 in 40,000 seeds — about 40 s at this speed §8(rough)"
            .equals(SpawnWishScreen.etaLine(rough, 1000, 0)));
      check("one world, past 4x: unchanged", ("§7Expect about 1 in 40,000 seeds — about 40 s at this speed"
            + " §8· taking longer than the estimate, which is a rough guide").equals(SpawnWishScreen.etaLine(common, 1000, 160_001)));
      // 1.46.2: A LOWER BOUND IS NOT A TIME. "at least 2 s" read as a promise about a wish only known to be
      // rarer than measured; it was on screen beside 105 million seeds checked and none found.
      check("one world, \"rarer than\": no time promise", "§eRarer than 1 in 2,001 seeds — could take much longer than 2 s"
            .equals(SpawnWishScreen.etaLine(atMost, 1000, 0)));
      check("\"rarer than\", under a second: \"much longer than a second\", never \"at least under a second\"",
            "§eRarer than 1 in 2,001 seeds — could take much longer than a second for 10 worlds"
                  .equals(SpawnWishScreen.etaLine(atMost, 1_000_000, 0, 10, 0)));
      String farPast = SpawnWishScreen.etaLine(new SeedFinder.Estimate(1 / 210_632.0, true, false, "x"), 3_000_000,
            105_000_000L, 10, 0);
      check("the screenshot: 105M seeds past a 1-in-210,632 bound, none found, says so and gives no time",
            farPast.contains("none found in 105.0M seeds") && !farPast.contains("under a second") && !farPast.contains("at least"));
      check("  …and with some found it says how many",
            SpawnWishScreen.etaLine(atMost, 1000, 9_000_000, 10, 2).contains("only 2 found in"));
      check("  …and within 4x per world still wanted it is still the bound",
            !SpawnWishScreen.etaLine(atMost, 1000, 8_000, 1, 0).contains("found in"));
      check("one world, a long wait: no warning, as before", "§7Expect about 1 in 1,000,000 seeds — about 17 min at this speed"
            .equals(SpawnWishScreen.etaLine(rare, 1000, 0)));
      check("the N-world form with N = 1 is the one-world line",
            SpawnWishScreen.etaLine(rough, 1000, 160_001).equals(SpawnWishScreen.etaLine(rough, 1000, 160_001, 1, 0)));
      check("3 worlds: \"about 2 min for 3 worlds\"", "§7Expect about 1 in 40,000 seeds — about 2 min for 3 worlds at this speed"
            .equals(SpawnWishScreen.etaLine(common, 1000, 0, 3, 0)));
      String longOne = SpawnWishScreen.etaLine(rare, 1000, 0, 3, 0);
      check("3 worlds of a rare wish: 50 min, and it says that is long",
            longOne.contains("about 50 min for 3 worlds") && longOne.contains("a long wait — fewer worlds finish sooner"));
      check("3 worlds, 160,001 seeds, none found: past the first world's allowance",
            SpawnWishScreen.etaLine(common, 1000, 160_001, 3, 0).contains("taking longer"));
      check("3 worlds, 160,001 seeds, 2 found: within the allowance for the third",
            !SpawnWishScreen.etaLine(common, 1000, 160_001, 3, 2).contains("taking longer"));
   }

   // ── 5. the lines under each world ───────────────────────────────────────────────────────────────

   private static void lineChecks() {
      System.out.println();
      System.out.println("-- the lines under each world --");
      SeedCriteria.Find v = new SeedCriteria.Find("Surface Village", SeedCriteria.Dim.OVERWORLD, 120, -80, 150, 140, "");
      SeedCriteria.Find f = new SeedCriteria.Find("Bastion Remnant", SeedCriteria.Dim.NETHER, -12, 30, 40, -1, "treasure");
      List<String> shown = JoinForm.shortLines(List.of(v), List.of(), true, true);
      check("Coords shown: the join message's line", shown.equals(List.of("§7· §fSurface Village §7- §f120, -80 §7(140 blocks)")));
      List<String> hidden = JoinForm.shortLines(List.of(v), List.of(), false, true);
      check("Coords hidden: the numbers go, the distance stays",
            hidden.equals(List.of("§7· §fSurface Village §7- §f?, ? §7(140 blocks)")));
      check("a Nether find is tagged in the list", JoinForm.shortLines(List.of(f), List.of(), true, true).get(0).endsWith(" §8(Nether)"));
      check("  and not in the join message", !JoinForm.shortLines(List.of(f), List.of(), true, false).get(0).contains("(Nether)"));
      List<String> slime = JoinForm.shortLines(List.of(), List.of("Your Overworld spawn @ 1, 2","4 slime chunks within 64 blocks"), true, true);
      check("nothing to point at: what was verified, after the spawn line", slime.equals(List.of("§7· §f4 slime chunks within 64 blocks")));
   }

   // ── 2 and 3 on real worlds ──────────────────────────────────────────────────────────────────────

   private static void engineChecks() throws Exception {
      System.out.println();
      System.out.println("-- the engine, through SeedFinder.consider (real world-gen) --");
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      SeedCriteria.StructureTarget village = null;
      for (SeedCriteria.StructureTarget t : SeedCatalog.structures(ctx)) {
         if (t.label.equals("Surface Village")) {
            village = t;
         }
      }
      if (village == null) {
         check("the catalog has Surface Village", false);
         return;
      }
      SeedCriteria criteria = new SeedCriteria();
      criteria.structures.add(village.withRadius(200));

      // 1.46.2: WHAT FORCES EXACT. Spawn-inside does (and the picker's Mode button now locks to Exact for it);
      // a count above 1 does not — it runs in Fast like any row, only slower and rarer, so the button is not
      // locked for it and the row says "slower" instead.
      SeedCriteria counted = new SeedCriteria();
      counted.structures.add(village.withRadius(200).withCount(2));
      SeedCriteria inside = new SeedCriteria();
      inside.structures.add(village.withRadius(200).insideSpawn());
      check("a count of 2 does NOT force Exact: requiresExact is false and a Fast job stays Fast",
            !counted.requiresExact() && new SeedFinder.Job(counted, reg, 10L, 1, true).fast);
      check("spawn-inside DOES force Exact: requiresExact, and a job asked for Fast runs Exact",
            inside.requiresExact() && !new SeedFinder.Job(inside, reg, 11L, 1, true).fast);

      WorldsRun.Run three = WorldsRun.run(criteria, reg, ctx, 3, true, 0x46_0001L, 120_000);
      System.out.println("  3 worlds, Fast: " + three.results().size() + " in " + three.ms() + " ms, "
            + three.checked() + " seeds checked");
      Set<Long> seeds = new HashSet<>();
      three.results().forEach(r -> seeds.add(r.seed()));
      check("a search for 3 stops by itself with exactly 3", !three.timedOut() && three.results().size() == 3);
      check("  3 DIFFERENT seeds", seeds.size() == 3);
      check("  counted 3 found", three.job().found.get() == 3);
      check("  each with the seeds-checked and time it was found at",
            three.results().stream().allMatch(r -> three.job().foundAt(r.seed()) != null));
      check("  each with its own finds", three.results().stream().allMatch(r -> !r.finds().isEmpty()));

      // The list fed from that job, then result 2 of 3 used: the stored finds are result 2's.
      FoundWorlds list = new FoundWorlds();
      Object screen = new Object();
      list.begin(three.job(), 3, 0, three.job().summary, "", screen, reg);
      int added = list.sync();
      check("the list takes all 3 from the job", added == 3 && list.size() == 3 && list.complete() && list.ended());
      check("  in the order found, with the same finds",
            list.get(0).seed() == three.results().get(0).seed() && list.get(1).finds().equals(three.results().get(1).finds()));
      check("syncing again adds nothing", list.sync() == 0 && list.size() == 3);
      list.use(1);
      SeedFinder.Result second = three.results().get(1);
      long[] at = three.job().foundAt(second.seed());
      check("USE RESULT 2 OF 3: the stored seed is result 2's", Long.toString(second.seed()).equals(AppliedSeed.seed));
      check("  the stored finds are result 2's", AppliedSeed.finds.equals(second.finds()));
      check("  and differ from results 1 and 3",
            !AppliedSeed.finds.equals(three.results().get(0).finds()) && !AppliedSeed.finds.equals(three.results().get(2).finds()));
      check("  the stored lines are result 2's", AppliedSeed.matches.equals(second.matches()));
      check("  the stored count is when result 2 was found", at != null && AppliedSeed.checked == at[0]);

      // The same seed offered twice to a real job: accepted once, and the search goes on.
      SeedFinder.Job twice = new SeedFinder.Job(criteria, reg, 7L, 2, true);
      List<String> lines = new ArrayList<>(4);
      int[] worst = new int[1];
      long s1 = three.results().get(0).seed();
      long s2 = three.results().get(2).seed();
      SeedFinder.consider(twice, ctx, 0, s1, lines, worst);
      SeedFinder.consider(twice, ctx, 0, s1, lines, worst);
      check("the same seed through the real path twice: one result, still searching",
            twice.results.size() == 1 && twice.found.get() == 1 && twice.running);
      SeedFinder.consider(twice, ctx, 0, s2, lines, worst);
      check("  a different one makes 2 of 2 and stops it", twice.results.size() == 2 && !twice.running);

      // One world: the first find ends it, as always.
      SeedFinder.Job single = new SeedFinder.Job(criteria, reg, 8L, 1, true);
      SeedFinder.consider(single, ctx, 0, s1, lines, worst);
      check("a search for 1 stops on its first find", single.results.size() == 1 && !single.running);

      // Pregen's shape (SeedSearchApi asks for Integer.MAX_VALUE and drains): accepted as before 1.46.0.
      SeedFinder.Job pregen = new SeedFinder.Job(criteria, reg, 9L, Integer.MAX_VALUE, true);
      SeedFinder.consider(pregen, ctx, 0, s1, lines, worst);
      SeedFinder.consider(pregen, ctx, 0, s1, lines, worst);
      check("pregen's unbounded stream is unchanged: both accepted, nothing recorded per seed",
            pregen.results.size() == 2 && pregen.found.get() == 2 && pregen.foundAt(s1) == null && pregen.running);
   }

   private static void check(String what, boolean ok) {
      System.out.println("  " + (ok ? "ok   " : "FAIL ") + what);
      if (ok) {
         passed++;
      } else {
         failed++;
      }
   }

   private FoundWorldsDiagnostic() {
   }
}
