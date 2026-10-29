package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fablevision.VillageDiagnostic;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * DOES THE SEARCH'S VERDICT DEPEND ON WHAT WAS LOOKED UP JUST BEFORE IT?
 *
 * Vanilla's biome lookup (Climate.RTree) remembers each thread's last answer and starts the next
 * search from it, so at an exact tie between two biomes the answer depends on the previous lookup.
 * 1.44.3 found this on the MAP (two Nether fossils drawn on a nether_wastes / soul_sand_valley tie).
 * The search does the same thing in the same order: a cheap biome sample at the locate position,
 * then the real structure check on the same thread.
 *
 * This runs the search's own confirm and layout check for every candidate, twice — with the
 * previous lookup left as the search leaves it ("warm") and with it cleared ("cold", what ships) —
 * and wherever the two disagree asks the GAME, through its own structure step on a real chunk
 * ({@link MapTruthDiagnostic#truth}), which one was right.
 *
 * WHAT IT FOUND (1.44.4): clearing the hint makes the answer repeatable but not RIGHT — at an exact
 * tie the game itself goes either way (it built one tied fossil and not another). So the shipped rule
 * is VillageLayout.biomeHolds: at a tie, promise the structure only if every tied biome allows it.
 *
 * FAILS if any answer still depends on lookup history, or if a tie is promised where the game built
 * nothing. Counts the ties it met, so a run that met none says so instead of passing silently.
 *
 * {@code gradlew tieDiag --args="<seeds per row> [radius]"}
 */
public final class TieDiagnostic {

   public static void main(String[] args) {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      if (!VillageLayout.available()) {
         System.out.println("FAILED: no structure templates.");
         System.exit(2);
      }
      int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 20;
      int reach = args.length > 1 ? Integer.parseInt(args[1]) : 1200;
      if (!SeedFunnel.STRICT_BIOME) {
         System.out.println("FAILED: STRICT_BIOME is off, so plain rows never run the structure check.");
         System.exit(2);
      }

      System.out.println("========== DOES THE SEARCH'S STRUCTURE CHECK DEPEND ON LOOKUP HISTORY? ==========");
      System.out.println("  " + seeds + " seeds per row, candidates within " + reach + " blocks.");
      Map<String, SeedCriteria.StructureTarget> rows = new LinkedHashMap<>();
      for (SeedCriteria.StructureTarget t : SeedCatalog.structures(ctx)) {
         // Plain rows, plus Nether Fossil: its token is an existence proof, not a building search
         // (SeedCatalog), and fossils are where the map's two real ties were found.
         if (t.special == SeedCriteria.Special.NORMAL && (t.building == null || t.label.equals("Nether Fossil"))) {
            rows.putIfAbsent(t.label, t);
         }
      }
      java.util.Random pick = new java.util.Random(144_4L);
      // The first two are the seeds whose Nether fossils sat on a nether_wastes / soul_sand_valley tie
      // in 1.44.3's mapTruthDiag — known ties, so the sample is never tie-free by construction.
      long[] seedList = new long[seeds + 2];
      seedList[0] = 6923588595528762942L;
      seedList[1] = -6574359089728930275L;
      for (int i = 2; i < seedList.length; i++) {
         seedList[i] = pick.nextLong();
      }

      int checks = 0;
      int historyDependent = 0;
      int ties = 0;
      int falsePromises = 0;
      int conservative = 0;
      List<String> lines = new ArrayList<>();
      for (SeedCriteria.StructureTarget row : rows.values()) {
         SeedCriteria.StructureTarget wide = row.withRadius(reach);
         SeedMapData.MapDim mapDim = SeedMapData.MapDim.of(row.dim);
         int rowChecks = 0;
         int rowTies = 0;
         for (long seed : seedList) {
            var rs = ctx.randomState(row.dim, seed);
            var state = ctx.structureState(row.dim, seed, rs);
            for (SeedCriteria.Cand c : wide.stage0(seed, true)) {
               @SuppressWarnings("unchecked")
               Holder<Structure>[] winner = new Holder[1];
               BlockPos at = wide.confirm(seed, ctx, rs, state, c, winner);
               if (at == null || winner[0] == null) {
                  continue;
               }
               int tiesBefore = VillageLayout.TIES_SEEN.get();
               // WARM: exactly the order the search runs — confirm's cheap sample, then the check.
               VillageLayout.COLD_BIOME = false;
               boolean warm = wide.layoutMatches(seed, c.chunk(), winner[0], ctx, 0, 0, null);
               wide.confirm(seed, ctx, rs, state, c, winner);
               // COLD: the hint cleared first. The shipped answer must be the same either way.
               VillageLayout.COLD_BIOME = true;
               boolean cold = wide.layoutMatches(seed, c.chunk(), winner[0], ctx, 0, 0, null);
               boolean tie = VillageLayout.TIES_SEEN.get() > tiesBefore;
               checks++;
               rowChecks++;
               if (!tie && warm == cold) {
                  continue;
               }
               String id = winner[0].unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
               String there = MapTruthDiagnostic.truth(reg, seed, mapDim, c.chunk().getMiddleBlockX(),
                     c.chunk().getMiddleBlockZ(), 40).get(c.chunk().x() + "," + c.chunk().z());
               boolean real = there != null && java.util.Arrays.stream(there.split("\\|"))
                     .anyMatch(e -> e.equals(id) || e.startsWith(id + "#"));
               String verdict;
               if (warm != cold) {
                  historyDependent++;
                  verdict = "   <-- DEPENDS ON LOOKUP HISTORY";
               } else if (cold && !real) {
                  falsePromises++;
                  verdict = "   <-- PROMISED, GAME BUILT NOTHING";
               } else if (!cold && real) {
                  conservative++;
                  verdict = "   (not promised; the game happened to build it this time)";
               } else {
                  verdict = "";
               }
               if (tie) {
                  ties++;
                  rowTies++;
               }
               lines.add("    " + row.label + " seed " + seed + " chunk " + c.chunk().x() + "," + c.chunk().z()
                     + (tie ? " [biome tie]" : "") + ": search says " + (cold ? "yes" : "no")
                     + (warm != cold ? " cold / " + (warm ? "yes" : "no") + " warm" : "")
                     + ", the game builds " + (there == null ? "nothing" : there) + verdict);
            }
         }
         System.out.println(String.format("  %-26s %5d checks, %d on a biome tie", row.label, rowChecks, rowTies));
      }
      VillageLayout.COLD_BIOME = true;
      System.out.println();
      lines.forEach(System.out::println);
      System.out.println();
      System.out.println("  structure checks run            : " + checks);
      System.out.println("  start points on an exact tie    : " + ties);
      System.out.println("  answers that depend on history  : " + historyDependent);
      System.out.println("  promised where the game built nothing : " + falsePromises);
      System.out.println("  tie not promised, game built it : " + conservative + "   (conservative, by design)");
      int bad = historyDependent + falsePromises;
      if (checks == 0) {
         System.out.println("  FAILED: nothing was checked.");
         System.exit(1);
      }
      if (ties == 0) {
         System.out.println("  NOTE: no tie met in this sample, so the tie rule was not exercised.");
      }
      System.out.println(bad == 0 ? "  OK — every answer is the same whatever was looked up first, and no tie is promised"
            : "  " + bad + " WRONG");
      System.exit(bad == 0 ? 0 : 1);
   }

   private TieDiagnostic() {
   }
}
