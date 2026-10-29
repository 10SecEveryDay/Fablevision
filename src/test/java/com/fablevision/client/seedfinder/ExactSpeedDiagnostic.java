package com.fablevision.client.seedfinder;

import com.fablevision.VillageDiagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;

/**
 * EXACT-MODE SPEED, MEASURED (1.47.0): {@code gradlew exactDiag --args="[scale] [spawnSeeds]"}.
 *
 *  1. THE SPAWN LOOKUP'S SHARE of an Exact search's time, from SeedFunnel's own clock around it, for a plain
 *     row, a spawn-inside row and counted rows.
 *  2. HOW FAR THE SPAWN WANDERS from 0,0, over many seeds — against SeedCriteria.SPAWN_WANDER (2144), the
 *     margin every Exact prefilter adds. 26.3's NoiseSpawnFinder searches 2048 out and then up to 512 further
 *     from wherever that landed, so the bound is checked rather than assumed.
 *  3. THE COUNT CHECKS (SeedFunnel fixes 8a and 8b) A/B, single-threaded, on fixed seeds: every variant must
 *     give the same RESULT-CHECKSUM (every seed's verdict) and the same passing seeds with the same lines
 *     and finds, BYTE FOR BYTE, or the change does not ship. The last row re-runs the first so a machine that
 *     moved during the run shows up. Count-1 rows run with the toggles off and on to prove nothing leaked.
 *  4. SPEED AND RARITY APART: seeds per second (what the machine does) and seeds per match (what the wish
 *     costs by itself), for one and for two of the same thing.
 *
 * Exits non-zero if any checksum or pass list differs.
 */
public final class ExactSpeedDiagnostic {

   private record Wish(String label, SeedCriteria criteria, boolean fast, long base, int seeds, boolean counted) {}

   private record Run(String variant, long checksum, String passes, int hits, double secs, long paidSpawn,
                      double spawnMs, long assemblies, long rejectCount, int seeds) {
      double perSec() {
         return seeds / secs;
      }
   }

   private static int failures;

   public static void main(String[] args) throws Exception {
      boolean rarityOnly = args.length > 0 && args[0].equals("rarity");
      double scale = args.length > 0 && !rarityOnly ? Double.parseDouble(args[0]) : 1.0;
      int spawnSeeds = args.length > 1 && !rarityOnly ? Integer.parseInt(args[1]) : 20_000;
      int raritySeeds = rarityOnly && args.length > 1 ? Integer.parseInt(args[1]) : 0;
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      VillageLayout.templates();
      SeedCriteria.StructureTarget village = null;
      SeedCriteria.StructureTarget smith = null;
      for (SeedCriteria.StructureTarget t : SeedCatalog.structures(ctx)) {
         if (t.label.equals("Surface Village")) {
            village = t;
         }
         if (t.label.equals("Village with Weaponsmith")) {
            smith = t;
         }
      }
      if (village == null || smith == null) {
         System.out.println("FAIL the catalog is missing Surface Village or Village with Weaponsmith");
         System.exit(1);
      }
      if (rarityOnly) {
         rarity(ctx, smith, village, Math.max(1000, raritySeeds));
         System.exit(0);
      }
      System.out.println("========== EXACT-MODE SPEED (single-threaded, fixed seeds, warm-up excluded) ==========");
      spawnWander(ctx, spawnSeeds);

      List<Wish> wishes = List.of(
            wish("Surface Village <=64", false, 11_000_000L, (int) (800 * scale), false, village.withRadius(64)),
            wish("Spawn inside Surface Village", false, 12_000_000L, (int) (800 * scale), false,
                  village.withRadius(64).insideSpawn()),
            wish("2x Surface Village <=200", false, 13_000_000L, (int) (1500 * scale), true, village.withRadius(200).withCount(2)),
            wish("2x Surface Village <=200", true, 13_000_000L, (int) (3000 * scale), true, village.withRadius(200).withCount(2)),
            wish("Village with Weaponsmith <=100", false, 14_000_000L, (int) (300 * scale), false, smith.withRadius(100)),
            wish("2x Village with Weaponsmith <=100", false, 14_000_000L, (int) (300 * scale), true, smith.withRadius(100).withCount(2)),
            wish("2x Village with Weaponsmith <=100", true, 14_000_000L, (int) (600 * scale), true, smith.withRadius(100).withCount(2)),
            wish("2x Village with Weaponsmith <=300", false, 15_000_000L, (int) (150 * scale), true, smith.withRadius(300).withCount(2)));

      List<String> table = new ArrayList<>();
      for (Wish w : wishes) {
         abTest(w, ctx, table);
      }
      System.out.println();
      System.out.println("========== SPEED AND RARITY, APART ==========");
      System.out.println(String.format("  %-38s %-5s %12s %12s %14s %12s", "wish", "mode", "seeds/s now", "seeds/s new",
            "spawn share", "1 match in"));
      table.forEach(System.out::println);
      System.out.println();
      System.out.println(failures == 0 ? "OK — every count-check variant gave the same verdicts, seeds, lines and finds"
            : "FAIL — " + failures + " A/B difference(s); the count checks must not ship");
      System.exit(failures == 0 ? 0 : 1);
   }

   private static Wish wish(String label, boolean fast, long base, int seeds, boolean counted, SeedCriteria.StructureTarget t) {
      SeedCriteria c = new SeedCriteria();
      c.structures.add(t);
      return new Wish(label, c, fast, base, Math.max(20, seeds), counted);
   }

   /** Every variant over the same seeds; the checksums and pass lists must match the shipped behaviour. */
   private static void abTest(Wish w, WorldgenContext ctx, List<String> table) {
      String mode = w.fast() ? "Fast" : "Exact";
      System.out.println();
      System.out.println("--- " + w.label() + " (" + mode + "), " + w.seeds() + " seeds from " + w.base() + " ---");
      // Warm-up on other seeds, toggles on, so the JIT has compiled both paths before anything is timed.
      setToggles(true, true);
      List<String> lines = new ArrayList<>(4);
      for (int i = 0; i < Math.max(10, w.seeds() / 20); i++) {
         lines.clear();
         w.criteria().test(w.base() - 1_000_000L + i, ctx, lines, w.fast());
      }
      boolean[][] variants = w.counted()
            ? new boolean[][]{{false, false}, {true, false}, {false, true}, {true, true}, {false, false}}
            : new boolean[][]{{false, false}, {true, true}, {false, false}};
      String[] names = w.counted()
            ? new String[]{"shipped (8a off, 8b off)", "8a cluster only", "8b confirm only", "8a + 8b", "shipped, again"}
            : new String[]{"shipped (toggles off)", "toggles on", "shipped, again"};
      List<Run> runs = new ArrayList<>();
      for (int v = 0; v < variants.length; v++) {
         setToggles(variants[v][0], variants[v][1]);
         Run r = run(names[v], w, ctx);
         runs.add(r);
         System.out.println(String.format("  %-26s %8.2f s  %8.1f seeds/s  hits %3d  checksum %016x  spawn lookups %,d (%,.0f ms)"
                     + "  assemblies %,d  count-rejects %,d", r.variant(), r.secs(), r.perSec(), r.hits(), r.checksum(),
               r.paidSpawn(), r.spawnMs(), r.assemblies(), r.rejectCount()));
      }
      setToggles(true, true);
      Run base = runs.get(0);
      for (Run r : runs) {
         if (r.checksum() != base.checksum() || !r.passes().equals(base.passes())) {
            failures++;
            System.out.println("  FAIL \"" + r.variant() + "\" does not give the shipped answers"
                  + (r.checksum() != base.checksum() ? " (checksum)" : " (pass list / lines / finds)"));
         }
      }
      Run best = runs.get(variants.length - 2);
      Run again = runs.get(runs.size() - 1);
      double drift = Math.abs(again.perSec() - base.perSec()) / base.perSec();
      System.out.println(String.format("  identical: %s · speed-up %.2fx (%s) · the re-run of the first row is %.0f%% off it%s",
            runs.stream().allMatch(r -> r.checksum() == base.checksum() && r.passes().equals(base.passes())) ? "yes" : "NO",
            best.perSec() / base.perSec(), best.variant(), drift * 100,
            drift > 0.15 ? " — the machine moved; read the speed-up with care" : ""));
      table.add(String.format("  %-38s %-5s %12.1f %12.1f %13.0f%% %12s", w.label(), mode, base.perSec(), best.perSec(),
            base.secs() > 0 ? 100 * base.spawnMs() / (base.secs() * 1000) : 0,
            base.hits() == 0 ? "> " + String.format("%,d", base.seeds()) : String.format("%,d", base.seeds() / base.hits())));
   }

   private static void setToggles(boolean cluster, boolean check) {
      SeedFunnel.COUNT_CLUSTER = cluster;
      SeedFunnel.COUNT_CHECK = check;
   }

   private static Run run(String variant, Wish w, WorldgenContext ctx) {
      SeedFunnel.reset();
      SeedFunnel.ENABLED = true;
      List<String> lines = new ArrayList<>(4);
      StringBuilder passes = new StringBuilder();
      long checksum = 1469598103934665603L;
      int hits = 0;
      long t0 = System.nanoTime();
      for (int i = 0; i < w.seeds(); i++) {
         long seed = w.base() + i;
         lines.clear();
         boolean hit = w.criteria().test(seed, ctx, lines, w.fast());
         checksum = fnv(fnv(checksum, seed), hit ? 1 : 0);
         if (hit) {
            hits++;
            passes.append(seed).append('\t').append(String.join(" | ", lines)).append('\n');
         }
      }
      double secs = (System.nanoTime() - t0) / 1e9;
      long paid = SeedFunnel.paidSpawn;
      double spawnMs = SeedFunnel.spawnNanos / 1e6;
      long assemblies = SeedFunnel.assemblies;
      long rejectCount = SeedFunnel.rejectCount;
      SeedFunnel.ENABLED = false;
      // The passing seeds again, described as a result is (the player's lines and the structured finds), outside
      // the timing: these are what a player would be handed, so they must match too.
      for (String row : passes.toString().split("\n")) {
         if (row.isEmpty()) {
            continue;
         }
         long seed = Long.parseLong(row.substring(0, row.indexOf('\t')));
         List<String> full = new ArrayList<>();
         List<SeedCriteria.Find> finds = new ArrayList<>();
         boolean again = w.criteria().test(seed, ctx, full, w.fast(), null, true, finds);
         passes.append("described ").append(seed).append(again).append('\t').append(String.join(" | ", full))
               .append('\t').append(finds).append('\n');
      }
      return new Run(variant, checksum, passes.toString(), hits, secs, paid, spawnMs, assemblies, rejectCount, w.seeds());
   }

   /**
    * RARITY ON ITS OWN: how many seeds per match, measured on every core with the shipped funnel, for one and
    * for two of the same thing — next to what the screen's estimate says for the same wish.
    */
   private static void rarity(WorldgenContext ctx, SeedCriteria.StructureTarget smith,
                              SeedCriteria.StructureTarget village, int n) {
      System.out.println("========== RARITY, MEASURED ON ALL CORES (" + String.format("%,d", n) + " seeds each, Exact) ==========");
      setToggles(true, true);
      Object[][] wishes = {
            {"Village with Weaponsmith <=100", smith.withRadius(100)},
            {"2x Surface Village <=100", village.withRadius(100).withCount(2)},
            {"2x Village with Weaponsmith <=100", smith.withRadius(100).withCount(2)}};
      for (Object[] w : wishes) {
         SeedCriteria c = new SeedCriteria();
         c.structures.add((SeedCriteria.StructureTarget) w[1]);
         AtomicLong hits = new AtomicLong();
         long t0 = System.nanoTime();
         LongStream.range(0, n).parallel().forEach(i -> {
            List<String> lines = new ArrayList<>(4);
            if (c.test(30_000_000L + i, ctx, lines, false)) {
               hits.incrementAndGet();
            }
         });
         double secs = (System.nanoTime() - t0) / 1e9;
         SeedFinder.Estimate e = SeedFinder.estimate(c, ctx, false, 4_000);
         System.out.println(String.format("  %-36s measured: %s   estimate: %s   (%,.0f seeds/s on all cores)",
               w[0], hits.get() == 0 ? "none in " + String.format("%,d", n) : "1 in " + String.format("%,d", n / hits.get())
                     + " (" + hits.get() + " found)",
               e == null ? "none" : (e.atMost() ? "rarer than 1 in " : "1 in ") + String.format("%,d", e.seedsPerMatch())
                     + (e.rough() ? " (rough)" : ""), n / secs));
         if (e != null) {
            System.out.println("     the screen would say: " + SpawnWishScreen.etaLine(e, 6_000, 0).replaceAll("§.", ""));
         }
      }
   }

   /** How far the real spawn point lands from 0,0, against the margin the prefilters use. */
   private static void spawnWander(WorldgenContext ctx, int n) {
      System.out.println();
      System.out.println("========== HOW FAR THE SPAWN WANDERS (" + String.format("%,d", n) + " seeds, all cores) ==========");
      int bound = SeedCriteria.SPAWN_WANDER;
      AtomicLong past2048 = new AtomicLong();
      AtomicLong pastBound = new AtomicLong();
      AtomicLong past2400 = new AtomicLong();
      long t0 = System.nanoTime();
      long[] dists = LongStream.range(0, n).parallel().map(i -> {
         long seed = 20_000_000L + i;
         BlockPos p = ctx.spawnPoint(ctx.randomState(SeedCriteria.Dim.OVERWORLD, seed));
         long d = Math.round(Math.hypot(p.getX(), p.getZ()));
         long cheb = Math.max(Math.abs(p.getX()), Math.abs(p.getZ()));
         if (d > 2048) {
            past2048.incrementAndGet();
         }
         if (d > bound) {
            pastBound.incrementAndGet();
         }
         if (d > 2400) {
            past2400.incrementAndGet();
         }
         return d * 100_000L + Math.min(99_999, cheb);
      }).sorted().toArray();
      double secs = (System.nanoTime() - t0) / 1e9;
      long max = dists[dists.length - 1] / 100_000L;
      long p999 = dists[(int) Math.min(dists.length - 1, Math.floor(dists.length * 0.999))] / 100_000L;
      long p99 = dists[(int) Math.min(dists.length - 1, Math.floor(dists.length * 0.99))] / 100_000L;
      long median = dists[dists.length / 2] / 100_000L;
      System.out.println(String.format("  %,d spawn points in %.1f s: median %,d blocks from 0,0, 99%% within %,d, 99.9%% within %,d, furthest %,d",
            n, secs, median, p99, p999, max));
      System.out.println(String.format("  past 2,048: %,d (%.3f%%)   past the prefilters' %,d: %,d (%.3f%%)   past 2,400: %,d",
            past2048.get(), 100.0 * past2048.get() / n, bound, pastBound.get(), 100.0 * pastBound.get() / n, past2400.get()));
      System.out.println(pastBound.get() == 0
            ? "  the spawn-wander margin covered every seed measured"
            : "  NOTE: the spawn landed outside the margin on " + pastBound.get() + " seed(s). An Exact search can only MISS such a"
                  + " seed's matches (a find is still measured from the real spawn before it is reported), never invent one.");
   }

   private static long fnv(long h, long v) {
      for (int i = 0; i < 8; i++) {
         h ^= (v >>> (i * 8)) & 0xff;
         h *= 1099511628211L;
      }
      return h;
   }

   private ExactSpeedDiagnostic() {
   }
}
