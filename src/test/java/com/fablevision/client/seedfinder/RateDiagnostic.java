package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import com.fablevision.VillageDiagnostic;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * EVERY RATE THE CATALOG QUOTES, MEASURED THE WAY A WORLD PRODUCES IT — and a failure when one drifts.
 *
 * Found porting to 26.3 (1.45.0). The rates on the content rows came from diagnostics that assemble
 * every sample at chunk 0,0 with seeds 1, 2, 3 … At chunk 0,0 a structure's random is seeded with the
 * world seed itself, and Java's random gives nearly the same FIRST number for small consecutive seeds —
 * so whatever a structure decides with its first draw was decided almost identically in every sample.
 * The giant ruined portal showed it most: its roll is the first draw for four of the six variants, which
 * measured 0 giants in 1,000 each at chunk 0,0 against about 1 in 20 everywhere else, and the row
 * shipped "about 1 portal in 50". Village rotations were constant too, which moved several profession
 * rates by a few points.
 *
 * This assembles each structure variant at RANDOM seeds and RANDOM chunks, reads every content row's
 * token from the same piece list with the search's own {@link VillageLayout#hasBuilding}, averages a
 * row's variants with equal weight (the convention the shipped rates use), and compares with the row's
 * {@code .rate()}. A row FAILS when its quoted rate is off by more than sampling can explain (half a
 * point of rounding plus four standard errors) AND by enough to matter (2 points, or a third of the
 * rate — 2% quoted for something at 4% is wrong even though it is only 2 points). The quoted rates were
 * set from a separate 4,000-per-variant run on a different random stream, so this run is a fresh sample,
 * not a re-read of the numbers it is checking. Rare rows need more samples to be checked tightly.
 *
 * Its own control: the same rows at chunk 0,0, seeds 1..40 ({@code --args="40 origin"}) reproduce the
 * old diagnostics' numbers exactly, so a difference is the sampling, not this code.
 *
 * {@code gradlew rateDiag --args="[samples per variant, default 2000] [scattered|origin] [stream, default 0]"}
 */
public final class RateDiagnostic {

   public static void main(String[] args) throws Exception {
      int n = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
      boolean scattered = args.length < 2 || !args[1].equals("origin");
      // Optional third argument (1.45.1): a different random stream, so a rate set from one run can be
      // checked on samples that run never saw. 0, the default, is the stream every earlier run used.
      long stream = args.length > 2 ? Long.parseLong(args[2]) : 0L;
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      if (!VillageLayout.available()) {
         System.out.println("NO TEMPLATES — nothing can be measured");
         System.exit(2);
      }

      List<SeedCriteria.StructureTarget> rows = new ArrayList<>();
      Map<Identifier, SeedCriteria.Dim> variants = new TreeMap<>();
      for (SeedCriteria.StructureTarget t : SeedCatalog.structures(ctx)) {
         if (t.building == null || t.building.isEmpty()) {
            continue;
         }
         rows.add(t);
         for (Identifier id : t.wanted) {
            variants.put(id, t.dim);
         }
      }
      Map<Identifier, List<List<Identifier>>> lists = new ConcurrentHashMap<>();
      ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(8,
            Runtime.getRuntime().availableProcessors() - 1)));
      List<Future<?>> running = new ArrayList<>();
      for (var e : variants.entrySet()) {
         running.add(pool.submit(() -> {
            Holder<Structure> s = reg.lookupOrThrow(Registries.STRUCTURE)
                  .getOrThrow(ResourceKey.create(Registries.STRUCTURE, e.getKey()));
            SplittableRandom r = new SplittableRandom(e.getKey().toString().hashCode() + stream * 0x9E3779B97F4A7C15L);
            List<List<Identifier>> out = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
               long seed = scattered ? r.nextLong() : i;
               ChunkPos chunk = scattered ? new ChunkPos(r.nextInt(-600, 600), r.nextInt(-600, 600)) : new ChunkPos(0, 0);
               List<Identifier> pieces = VillageLayout.pieceTemplates(reg, ctx, seed, chunk, s,
                     ctx.fullRandomState(e.getValue(), seed), e.getValue());
               if (!pieces.isEmpty()) {
                  out.add(pieces);
               }
            }
            lists.put(e.getKey(), out);
         }));
      }
      for (Future<?> f : running) {
         f.get();
      }
      pool.shutdown();

      System.out.println("=========== CONTENT-ROW RATES, " + (scattered
            ? "RANDOM SEEDS AND RANDOM CHUNKS" : "CHUNK 0,0, SEEDS 1.." + n + " (the old method — control only)")
            + ", " + n + " per variant ===========");
      System.out.printf("  %-36s %8s %9s %7s   %s%n", "row", "quoted", "measured", "+/-", "per variant (has it / built)");
      int problems = 0;
      for (SeedCriteria.StructureTarget t : rows) {
         double sum = 0;
         double var = 0;
         int k = 0;
         StringBuilder per = new StringBuilder();
         for (Identifier id : new TreeSet<>(t.wanted)) {
            List<List<Identifier>> l = lists.get(id);
            if (l == null || l.isEmpty()) {
               continue;
            }
            int hits = 0;
            for (List<Identifier> p : l) {
               if (VillageLayout.hasBuilding(p, t.building)) {
                  hits++;
               }
            }
            double p = hits / (double) l.size();
            sum += p;
            var += p * (1 - p) / l.size();
            k++;
            per.append(id.getPath()).append(' ').append(hits).append('/').append(l.size()).append("  ");
         }
         if (k == 0) {
            problems++;
            System.out.println("  " + t.label + ": NOTHING ASSEMBLED — the rate cannot be checked");
            continue;
         }
         double measured = 100 * sum / k;
         double se = 100 * Math.sqrt(var) / k;
         double off = Math.abs(measured - t.rarityPct);
         String flag = "";
         if (t.rarityPct >= 0 && scattered && off > 0.5 + 4 * se && (off >= 2.0 || off >= measured / 3)) {
            problems++;
            flag = "   <-- QUOTED RATE IS WRONG";
         }
         System.out.printf("  %-36s %7s%% %8.1f%% %6.1f   %s%s%n", t.label,
               t.rarityPct < 0 ? "-" : String.valueOf(t.rarityPct), measured, se, per, flag);
      }
      System.out.println(problems == 0 ? "OK — every quoted rate is within sampling error of the world"
            : problems + " PROBLEM(S) — a quoted rate is not what the world produces");
      System.exit(problems == 0 || !scattered ? 0 : 1);
   }

   private RateDiagnostic() {
   }
}
