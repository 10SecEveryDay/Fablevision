package com.fablevision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.fablevision.client.seedfinder.SeedCatalog;
import com.fablevision.client.seedfinder.SeedCriteria;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import com.fablevision.client.seedfinder.VillageLayout;
import com.fablevision.client.seedfinder.WorldgenContext;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Two "is this worth building at all" questions, answered by assembling real structures.
 *
 * VILLAGE SIZE. "A big village" needs a number, and the number has to be measured or the label is a
 * guess wearing a threshold's clothes — the same mistake the biome size tiers were built to avoid.
 * This prints the real piece-count distribution per village type so the cut can be set at that
 * structure's own top third rather than at a round number that sounded impressive.
 *
 * BLAZE SPAWNERS. Before any row is added, the question is whether a fortress's spawner count varies
 * between seeds AT ALL. If every fortress has exactly one, then "a fortress with a blaze spawner" is
 * not a search: it is true of every fortress, and shipping it would be a control that filters
 * nothing while looking like it filters a lot. A fortress is built from CODE, so its rooms are Java
 * classes rather than template files and the census below is by class name — which is why
 * {@link VillageLayout.Built} carries them.
 *
 * {@code gradlew statsDiag --args="[village|blaze|all] [samples]"}
 */
public final class StructureStatsDiagnostic {

   public static void main(String[] args) {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      if (!VillageLayout.available()) {
         System.out.println("FAILED: no templates — nothing below could be measured.");
         return;
      }
      String mode = args.length > 0 ? args[0] : "all";
      int samples = args.length > 1 ? Integer.parseInt(args[1]) : 120;

      List<SeedCriteria.StructureTarget> catalog = SeedCatalog.structures(ctx);
      if (mode.equals("village") || mode.equals("all")) {
         villageSizes(reg, ctx, catalog, samples);
      }
      if (mode.equals("blaze") || mode.equals("all")) {
         fortressRooms(reg, ctx, catalog, samples);
      }
   }

   /** How big is a village, really — per village type, because they are not the same shape. */
   private static void villageSizes(RegistryAccess.Frozen reg, WorldgenContext ctx,
                                    List<SeedCriteria.StructureTarget> catalog, int samples) {
      System.out.println();
      System.out.println("=========== HOW BIG IS A VILLAGE? ===========");
      System.out.println("  assembled piece counts, so \"big\" can mean a measured number");
      SeedCriteria.StructureTarget village = byLabel(catalog, "Surface Village");
      if (village == null) {
         System.out.println("  no Surface Village row in this world — skipped");
         return;
      }
      Map<String, List<Integer>> byType = new TreeMap<>();
      List<Integer> all = new ArrayList<>();
      // EVERY village type, not the first one that happens to build. The first version of this
      // stopped at the first id that assembled, and because the id set iterates in a stable order
      // that meant 150 taiga villages and a confident distribution for one fifth of the question.
      // A plains village and a desert village are different shapes and the threshold has to cover
      // all of them, so each seed contributes one sample per type.
      int seeds = Math.max(1, samples / Math.max(1, village.wanted.size()));
      for (long seed = 1; seed <= seeds; seed++) {
         RandomState full = ctx.fullRandomState(Dim.OVERWORLD, seed);
         for (Identifier id : village.wanted) {
            Holder<Structure> holder = holder(reg, id);
            if (holder == null) {
               continue;
            }
            // A fixed chunk per seed: the point is the size distribution, not where they are.
            VillageLayout.Built b = VillageLayout.builtAt(reg, ctx, seed, new ChunkPos(0, 0), holder,
                  full, h -> true, Dim.OVERWORLD);
            if (b == null || b.pieces() == 0) {
               continue;
            }
            byType.computeIfAbsent(id.getPath(), k -> new ArrayList<>()).add(b.pieces());
            all.add(b.pieces());
         }
      }
      System.out.printf("  %-22s %6s %6s %6s %6s %6s %6s%n",
            "VILLAGE TYPE", "n", "min", "p33", "med", "p67", "max");
      for (Map.Entry<String, List<Integer>> e : byType.entrySet()) {
         row(e.getKey(), e.getValue());
      }
      row("ALL TYPES", all);
      if (!all.isEmpty()) {
         List<Integer> sorted = new ArrayList<>(all);
         Collections.sort(sorted);
         int p67 = sorted.get(Math.min(sorted.size() - 1, sorted.size() * 2 / 3));
         System.out.println();
         System.out.println("  => \"big village\" should mean >= " + p67 + " pieces (this run's p67).");
         System.out.println("     That is the top third, so it rejects about two villages in three —");
         System.out.println("     a real filter. A threshold above max would be an impossible search.");
      }
      System.out.println("=============================================");
   }

   /**
    * Does a fortress's room mix vary between seeds? If not, there is nothing here to search for.
    */
   private static void fortressRooms(RegistryAccess.Frozen reg, WorldgenContext ctx,
                                     List<SeedCriteria.StructureTarget> catalog, int samples) {
      System.out.println();
      System.out.println("=========== WHAT IS IN A NETHER FORTRESS? ===========");
      System.out.println("  by piece CLASS, because a fortress has no template files at all");
      SeedCriteria.StructureTarget fort = byLabel(catalog, "Nether Fortress");
      if (fort == null) {
         System.out.println("  no Nether Fortress row in this world — skipped");
         return;
      }
      // class name -> the per-fortress count seen in each fortress
      Map<String, List<Integer>> counts = new TreeMap<>();
      int built = 0;
      for (long seed = 1; built < samples && seed < samples * 500L; seed++) {
         for (Identifier id : fort.wanted) {
            Holder<Structure> holder = holder(reg, id);
            if (holder == null) {
               continue;
            }
            RandomState full = ctx.fullRandomState(Dim.NETHER, seed);
            VillageLayout.Built b = VillageLayout.builtAt(reg, ctx, seed, new ChunkPos(0, 0), holder,
                  full, h -> true, Dim.NETHER);
            if (b == null || b.pieces() == 0) {
               continue;
            }
            Map<String, Integer> here = new TreeMap<>();
            for (String c : b.classes()) {
               here.merge(c, 1, Integer::sum);
            }
            // Every class seen so far must get a value for THIS fortress, including zero — a room
            // that is absent is the interesting case and it does not announce itself.
            for (String known : counts.keySet()) {
               here.putIfAbsent(known, 0);
            }
            for (Map.Entry<String, Integer> e : here.entrySet()) {
               List<Integer> list = counts.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
               while (list.size() < built) {
                  list.add(0);   // back-fill fortresses built before this class was first seen
               }
               list.add(e.getValue());
            }
            built++;
            break;
         }
      }
      System.out.println("  fortresses assembled: " + built);
      System.out.printf("  %-34s %6s %6s %8s   %s%n", "PIECE CLASS", "min", "max", "avg", "VERDICT");
      for (Map.Entry<String, List<Integer>> e : counts.entrySet()) {
         List<Integer> v = e.getValue();
         while (v.size() < built) {
            v.add(0);
         }
         int min = Integer.MAX_VALUE;
         int max = 0;
         long sum = 0;
         for (int n : v) {
            min = Math.min(min, n);
            max = Math.max(max, n);
            sum += n;
         }
         String verdict = min == max
               ? "identical in every fortress — NOT searchable"
               : "varies " + min + ".." + max + " — a real search";
         System.out.printf("  %-34s %6d %6d %8.2f   %s%n",
               e.getKey(), min, max, (double) sum / Math.max(1, v.size()), verdict);
      }
      System.out.println();
      System.out.println("  A row is only worth adding for a class whose verdict says it VARIES.");
      System.out.println("  'identical in every fortress' means asking for it filters nothing.");
      System.out.println("====================================================");
   }

   private static void row(String name, List<Integer> values) {
      if (values.isEmpty()) {
         System.out.printf("  %-22s %6d%n", name, 0);
         return;
      }
      List<Integer> s = new ArrayList<>(values);
      Collections.sort(s);
      System.out.printf("  %-22s %6d %6d %6d %6d %6d %6d%n", name, s.size(), s.get(0),
            s.get(s.size() / 3), s.get(s.size() / 2), s.get(Math.min(s.size() - 1, s.size() * 2 / 3)),
            s.get(s.size() - 1));
   }

   private static Holder<Structure> holder(RegistryAccess.Frozen reg, Identifier id) {
      return reg.lookupOrThrow(Registries.STRUCTURE)
            .get(ResourceKey.create(Registries.STRUCTURE, id)).map(h -> (Holder<Structure>) h)
            .orElse(null);
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

   private StructureStatsDiagnostic() {
   }
}
