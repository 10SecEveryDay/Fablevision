package com.fablevision.client.seedfinder;

/**
 * Dev-only funnel counters: how many seeds die at each stage of {@link SeedCriteria#test}.
 *
 * Off by default and only switched on by the headless benchmark ({@code gradlew bench}), so the
 * shipped mod pays one predictable-branch test per counted event and nothing else. Deliberately
 * NOT thread-safe — the benchmark is single-threaded, and a racy count in a diagnostic is not
 * worth an atomic on a path that runs millions of times.
 *
 * The point of these is to answer "did a fix speed things up because it made the expensive stage
 * cheaper, or because it stopped reaching that stage at all?" — a change in the funnel shape is
 * the difference between an optimisation and a behaviour change.
 */
public final class SeedFunnel {
   public static volatile boolean ENABLED = false;

   // ── Optimisation toggles ────────────────────────────────────────────────────
   // These exist so the benchmark can measure baseline and each fix inside ONE process, under
   // one set of machine conditions. Measuring them as separate runs made every number move by
   // roughly the same factor, including searches a fix provably cannot touch - the machine, not
   // the code, was being measured. Every variant carries the same (perfectly predicted) boolean
   // reads, so the comparison between them stays fair.
   //
   // Production ships with all of these at the defaults below — which is ON for every one except
   // BIOME_PREFILTER, whose own note explains why it is off. They are only ever changed by a
   // diagnostic.
   //
   // TWO KINDS OF TOGGLE LIVE HERE and they must not be confused, because one of them was.
   // Most are OPTIMISATIONS: they change how long a search takes and not which seeds pass, so
   // switching them off gives a slower baseline that still produces correct answers. EXACT_RADIUS
   // is not one of those — it is a BUG FIX, and switching it off reinstates the bug. See allOff().

   /** Fix 1: drop the spawn-wander padding from stage0 when the caller is in Fast mode. */
   public static volatile boolean FAST_STAGE0 = true;
   /** Fix 2a: reuse the per-dimension assembly scaffolding instead of rebuilding it per check. */
   public static volatile boolean CACHE_SCAFFOLD = true;
   /** Fix 2b: sample the locate-position biome once per candidate, not once per variant. */
   public static volatile boolean HOIST_BIOME = true;
   /**
    * Fix 3: temperature-only climate prefilter ahead of the real spawn search. APPROXIMATE.
    *
    * OFF by default, and it must stay that way unless someone opts in. Measured cost: it silently
    * drops about 5% of seeds that genuinely matched (4.99% of true hits over the benchmark set),
    * in exchange for 1.54x on Fast-mode biome searches only. It buys nothing at all in Exact mode
    * - it skipped 0.6% of seeds there and the speed was unchanged - because the spawn wander
    * forces it to probe a 6288-block-wide area where almost every seed has some in-band spot.
    *
    * A search that quietly misses valid seeds is a worse default than a slower one that doesn't.
    */
   public static volatile boolean BIOME_PREFILTER = false;
   /**
    * Fix 4: ask VANILLA'S biome question instead of the finder's cheaper one. EXACT, not approximate.
    *
    * The finder's confirm() samples the biome once, at the structure's locate position, at a fixed
    * height. Vanilla works out where the structure really starts — terrain height, the jigsaw's own
    * offset, an ancient city's configured depth — and samples there, and that is the test that
    * decides whether the chunk gets a structure at all.
    *
    * They disagree, and {@code gradlew strictDiag} put numbers on it: 4% of confirmed hits overall,
    * and 48% for the Ancient City, whose deep_dark only reaches about y=-40 at the locate column
    * while the city starts at -27. Every one of those is a coordinate the finder would print and a
    * player would find empty.
    *
    * It is affordable because {@code Structure.findValidGenerationPoint} defers the piece placement
    * into a lambda — it decides the start position and tests the biome, and only {@code generate}
    * runs the placement. Measured against a full assembly it returns the identical verdict on every
    * structure and is 91x cheaper for a village, 298x for a trial chamber. Rows that already
    * assemble pay nothing at all: they just pass the strict predicate into the assembly they were
    * doing anyway.
    */
   public static volatile boolean STRICT_BIOME = true;
   /**
    * Fix 4b: run the strict check LAST, after every cheap test in the whole wish has passed.
    *
    * Same move as the spawn-search fix. A plain row's strict test is the only expensive thing it
    * does, and where it sat — inside the per-target loop — a wish like "village + jungle + ruined
    * portal" paid for the village's test on every seed whose jungle was about to reject it. The
    * work was real and the seed was doomed before it started.
    *
    * Deferring cannot change any verdict, and that is worth being precise about, because the
    * obvious way to defer WOULD change one: if the first confirmed candidate fails the strict test
    * and the deferred phase gives up there, a seed whose SECOND village was perfectly good is
    * thrown away. So the deferred phase re-walks the same candidate list in the same order and
    * takes the first that passes — exactly what the inline loop did, just later. The match line is
    * rewritten if a different candidate wins.
    *
    * Content rows are not deferred BY THIS toggle, and for the strict test there is nothing to gain:
    * it is one argument to an assembly the row was going to run. Deferring the assembly itself is a
    * different and much larger win — see {@link #LATE_LAYOUT}.
    */
   public static volatile boolean STRICT_DEFERRED = true;
   /**
    * Fix 6: defer the ASSEMBLY of a content row until every cheap test in the whole wish has passed.
    *
    * Fix 4b moved a plain row's strict test to the end and left the one genuinely expensive thing in
    * the funnel exactly where it was. A content row assembles the structure and reads its piece list
    * — about a second for a village, more for a trial chamber — and it did that from INSIDE the
    * per-target loop, which walks the catalog in its own order. So "village with an armorer + igloo
    * with a basement + ruined portal + desert pyramid" assembled the armorer village in full before
    * the portal's distance filter had been asked whether this seed was worth anything at all. On a
    * seed the portal was about to reject, the whole assembly was work done for a verdict already
    * decided.
    *
    * The reasoning about correctness is the same as fix 4b's, and so is the trap. Deferring must not
    * mean "test the first confirmed candidate and give up": the inline loop walked the in-range
    * candidates in distance order and took the first whose layout matched, so the deferred phase
    * re-walks the same list in the same order and takes the first that passes. A seed whose SECOND
    * village had the armorer stays a hit. When a later candidate wins, the match line is rewritten,
    * along with the Fast-mode true-distance entry and the coordinates the "next to" pass reads.
    *
    * This changes WHEN work happens, never WHICH seeds pass, so the result checksums must be
    * identical with it on and off. That is the whole test.
    */
   public static volatile boolean LATE_LAYOUT = true;
   /**
    * Fix 5: ask {@code findBiomeHorizontal} for the NEAREST matching patch instead of a random one.
    *
    * This is a bug fix wearing an optimisation's clothes. The finder passed {@code findClosest =
    * false}, which does not mean "any will do, be quick" — it means vanilla reservoir-samples a
    * uniformly random match out of the whole radius, so it must scan all of it, and then reports a
    * patch that is very often not the closest one. Both halves of that are wrong for a search whose
    * entire output is a coordinate and a distance: "jungle within 500" could report one at 480 with
    * one sitting at 90.
    *
    * Measured per call over 60 seeds at radius 2000 ({@code biomeShape --args="60 cost"}):
    * plains 14,211µs -> 899µs, forest 12,952 -> 665, jungle 15,419 -> 4,484, cherry grove
    * 16,273 -> 6,898, pale garden 15,684 -> 11,940. Common biomes gain most, because asking for the
    * closest lets the search stop as soon as it can prove nothing nearer exists, while the random
    * pick can never stop early.
    *
    * It DOES change results — better answers are still different answers — so it has its own toggle
    * and its own benchmark row rather than riding along with anything else.
    */
   public static volatile boolean NEAREST_BIOME = true;
   /**
    * Fix 7: hold every find to the radius that was ASKED FOR, measured to the point that gets
    * REPORTED. Exact, and a bug fix rather than an optimisation.
    *
    * A "within 500" search could report a structure at 508. Two separate off-by-a-bit sources, both
    * of the same shape — the thing being filtered was not the thing being printed:
    *
    *   STRUCTURES. The radius filter measures the candidate CHUNK'S MIDDLE
    *   ({@code ChunkPos.getMiddleBlockX}, i.e. min + 8), and the coordinate a player is given is
    *   {@code StructurePlacement.getLocatePos}, which is the chunk's MIN CORNER plus the placement's
    *   own offset. Those differ by up to 11.3 blocks on the diagonal, so a chunk whose middle sits
    *   at 499 reports a structure at up to 510.
    *
    *   BIOMES. {@code findBiomeHorizontal} scans a SQUARE and takes the nearest hit in ring order,
    *   with no distance test at all — so a patch found on the diagonal of a 500-block scan is
    *   reported at up to 707. The same applies to a "next to" pair's biome end, which had no upper
    *   check on the answer either, only on the structure end.
    *
    * The fix is the same in both places: test the point that will be printed, against the number
    * that was asked for, and skip the candidate if it does not hold. The cheap prefilters keep
    * working on the chunk middle but are widened by {@link SeedCriteria#RADIUS_SLACK} first, so
    * nothing that WOULD qualify is thrown away before the exact test can see it.
    *
    * Toggleable so `gradlew radiusDiag` can run both halves in one process and prove that only
    * boundary cases move.
    */
   public static volatile boolean EXACT_RADIUS = true;
   /** Rejected because the reported position was outside the asked radius even though the cheap
    *  chunk-middle prefilter let it through. This is the whole population fix 7 changes. */
   public static long rejectOverRadius;

   /**
    * Every OPTIMISATION off — the slow baseline the benchmark measures speedups against.
    *
    * EXACT_RADIUS IS DELIBERATELY NOT INCLUDED, and it was until this was caught in the 1.41.0
    * audit. It is the fix that stopped a "within 500" search reporting a structure at 508, so
    * turning it off does not produce a slower version of the same search — it produces a search
    * that accepts seeds the shipped code rejects. With it in here, `bench`'s baseline column was
    * quietly measuring a different, wrong search and calling the difference a speedup.
    *
    * The rule this encodes: a toggle belongs in allOff() only if flipping it cannot change which
    * seeds pass. Anything that changes the ANSWER gets flipped explicitly by the one diagnostic
    * that is A/B-ing it, which for EXACT_RADIUS is {@code radiusDiag}.
    */
   public static void allOff() {
      FAST_STAGE0 = false;
      CACHE_SCAFFOLD = false;
      HOIST_BIOME = false;
      BIOME_PREFILTER = false;
      STRICT_BIOME = false;
      STRICT_DEFERRED = false;
      NEAREST_BIOME = false;
      LATE_LAYOUT = false;
   }

   /** Seeds that entered {@link SeedCriteria#test}. */
   public static long seeds;
   /** Rejected because a structure target produced no placement candidates at all. */
   public static long rejectStage0;
   /** Rejected because a wanted structure never actually generated anywhere in reach. */
   public static long rejectGate;
   /** Seeds that paid for the real spawn search ({@code findSpawnPosition}). */
   public static long paidSpawn;
   /** Rejected because every confirmed structure fell outside the radius. */
   public static long rejectDistance;
   /** Rejected because the in-range candidates all failed the layout predicate. */
   public static long rejectLayout;
   /** Rejected on a wanted biome (not found in radius, or too small). */
   public static long rejectBiome;
   /** Rejected by an exclusion ("must NOT have"). */
   public static long rejectExclude;
   /** Rejected by a "next to" pair — counted apart from rejectBiome because adjacency is the last
    *  and most expensive test in the funnel, so how often it is the one doing the rejecting is the
    *  number that says whether it is placed correctly. */
   public static long rejectAdjacent;
   /** Targeted "nearest B to A" searches actually run. */
   public static long adjacentSearches;
   /** Rejected by a slime-chunk count. Its own counter because this is the ONLY stage that can
    *  reject a seed without building anything, so how much it rejects is how much of the funnel it
    *  is saving — and a slime row that rejects nothing is a row that should not have shipped. */
   public static long rejectSlime;
   /**
    * Rejected by the Fast-mode spawn re-check: the wish matched measured from the origin, and one
    * of its finds turned out to be further than that from the real spawn.
    *
    * Counted apart from rejectDistance because it is the only rejection that happens AFTER a seed
    * has passed everything — it is the price of the mode being honest, and the number that says
    * whether that price is affordable. Measured at 2.5%–9.7% of hits when it shipped.
    */
   public static long rejectSpawnDistance;
   /** Seeds that matched everything. */
   public static long passed;

   /** Structure assemblies actually run — the expensive half, counted separately because one
    *  seed can assemble several candidates. */
   public static long assemblies;
   /** Generation-point tests run (Fix 4) — the cheap exact biome check, counted apart from
    *  assemblies because it is the one that plain rows newly pay for. */
   public static long genPoints;
   /** Biome prefilter (Fix 3) verdicts: how often it ran and how often it skipped. */
   public static long prefilterRuns;
   public static long prefilterSkips;

   public static void reset() {
      genPoints = 0;
      seeds = 0;
      rejectStage0 = 0;
      rejectGate = 0;
      paidSpawn = 0;
      rejectDistance = 0;
      rejectLayout = 0;
      rejectBiome = 0;
      rejectExclude = 0;
      rejectAdjacent = 0;
      adjacentSearches = 0;
      rejectSlime = 0;
      rejectSpawnDistance = 0;
      rejectOverRadius = 0;
      passed = 0;
      assemblies = 0;
      prefilterRuns = 0;
      prefilterSkips = 0;
   }

   /** One-line-per-stage dump for the benchmark report. */
   public static String report() {
      StringBuilder sb = new StringBuilder();
      sb.append(String.format("  seeds in                : %,d%n", seeds));
      sb.append(String.format("  rejected @ stage0 (cheap placement math) : %,d (%s)%n",
            rejectStage0, pct(rejectStage0, seeds)));
      sb.append(String.format("  rejected @ gate   (structure never generates) : %,d (%s)%n",
            rejectGate, pct(rejectGate, seeds)));
      sb.append(String.format("  PAID real spawn search  : %,d (%s)%n", paidSpawn, pct(paidSpawn, seeds)));
      sb.append(String.format("  rejected @ distance     : %,d (%s)%n", rejectDistance, pct(rejectDistance, seeds)));
      sb.append(String.format("  rejected @ layout       : %,d (%s)%n", rejectLayout, pct(rejectLayout, seeds)));
      sb.append(String.format("  rejected @ biome        : %,d (%s)%n", rejectBiome, pct(rejectBiome, seeds)));
      sb.append(String.format("  rejected @ exclude      : %,d (%s)%n", rejectExclude, pct(rejectExclude, seeds)));
      sb.append(String.format("  rejected @ next-to      : %,d (%s)%n", rejectAdjacent, pct(rejectAdjacent, seeds)));
      sb.append(String.format("  rejected @ slime chunks : %,d (%s)%n", rejectSlime, pct(rejectSlime, seeds)));
      sb.append(String.format("  rejected @ spawn re-check (fast mode) : %,d (%s)%n",
            rejectSpawnDistance, pct(rejectSpawnDistance, seeds)));
      sb.append(String.format("  rejected @ exact radius (fix 7)       : %,d (%s)%n",
            rejectOverRadius, pct(rejectOverRadius, seeds)));
      sb.append(String.format("  PASSED                  : %,d (%s)%n", passed, pct(passed, seeds)));
      sb.append(String.format("  structure assemblies run: %,d%n", assemblies));
      sb.append(String.format("  generation-point tests  : %,d%n", genPoints));
      sb.append(String.format("  next-to searches run    : %,d%n", adjacentSearches));
      if (prefilterRuns > 0) {
         sb.append(String.format("  biome prefilter         : ran %,d · skipped %,d (%s)%n",
               prefilterRuns, prefilterSkips, pct(prefilterSkips, prefilterRuns)));
      }
      return sb.toString();
   }

   private static String pct(long a, long b) {
      return b == 0 ? "-" : String.format("%.1f%%", 100.0 * a / b);
   }

   private SeedFunnel() {
   }
}
