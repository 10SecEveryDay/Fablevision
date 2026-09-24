package com.fablevision;

import com.fablevision.client.seedfinder.SeedCatalog;
import com.fablevision.client.seedfinder.SeedCriteria;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import com.fablevision.client.seedfinder.WorldgenContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Dev-only: measures WHERE the seed finder's per-seed milliseconds actually go and what the
 * real hit rates are for common wishes, over the REAL world-gen code with no game running.
 * Run with {@code gradlew speedDiag}. Prints:
 *  - isolated cost of RandomState.create and findSpawnPosition (the two suspects),
 *  - the real-spawn distance-from-0,0 distribution (how far spawns actually wander),
 *  - single-thread end-to-end throughput + seeds-per-hit for canonical wishes at the new
 *    Exact radius (64) vs the old one (128), plus a Fast-mode contrast,
 *  - a found seed + match lines per wish, so the numbers can be spot-checked in-game
 *    with /locate on that exact seed (ground truth, not just a plausible distribution).
 */
public final class SpeedDiagnostic {
   public static void main(String[] args) {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      List<SeedCriteria.StructureTarget> catalog = SeedCatalog.structures(ctx);

      // ── A) isolated per-seed costs ────────────────────────────────────────
      // Warmup so the JIT doesn't bill its compile time to the first measurement.
      for (long s = 1; s <= 30; s++) {
         ctx.randomState(Dim.OVERWORLD, s).sampler().findSpawnPosition();
      }

      // GROUND TRUTH for the slim (climate-only) RandomState: the game's own spawn pick must
      // be IDENTICAL on slim vs full for every seed — if the slim router changed climate in
      // any way, this catches it (a distribution alone would not — the 1.24.1 lesson).
      int mismatches = 0;
      for (long s = 100; s < 140; s++) {
         BlockPos slim = ctx.randomState(Dim.OVERWORLD, s).sampler().findSpawnPosition();
         BlockPos full = ctx.fullRandomState(Dim.OVERWORLD, s).sampler().findSpawnPosition();
         if (!slim.equals(full)) {
            mismatches++;
            System.out.println("SLIM/FULL MISMATCH seed " + s + ": slim=" + slim + " full=" + full);
         }
      }

      int n = 200;
      RandomState[] states = new RandomState[n];
      long t0 = System.nanoTime();
      for (int i = 0; i < n; i++) {
         states[i] = ctx.randomState(Dim.OVERWORLD, 1_000_000L + i);
      }
      double msSlim = (System.nanoTime() - t0) / 1e6 / n;

      t0 = System.nanoTime();
      for (int i = 0; i < 60; i++) {
         ctx.fullRandomState(Dim.OVERWORLD, 2_000_000L + i);
      }
      double msFull = (System.nanoTime() - t0) / 1e6 / 60;

      long[] dist = new long[n];
      t0 = System.nanoTime();
      for (int i = 0; i < n; i++) {
         BlockPos sp = states[i].sampler().findSpawnPosition();
         dist[i] = Math.round(Math.hypot(sp.getX(), sp.getZ()));
      }
      double msSpawn = (System.nanoTime() - t0) / 1e6 / n;
      Arrays.sort(dist);

      System.out.println("========== SPEED DIAGNOSTIC ==========");
      System.out.println(mismatches == 0 ? "slim == full spawn for 40 seeds : OK (vanilla-exact)"
            : "!!! slim RandomState DIVERGES on " + mismatches + "/40 seeds — DO NOT SHIP !!!");
      System.out.printf("RandomState.create SLIM : %.3f ms/seed  (FULL: %.3f — the old per-seed cost)%n", msSlim, msFull);
      System.out.printf("findSpawnPosition       : %.3f ms/seed%n", msSpawn);
      System.out.printf("spawn distance from 0,0 : min %d · p50 %d · p90 %d · p99 %d · max %d (n=%d)%n",
            dist[0], dist[n / 2], dist[(int) (n * 0.9)], dist[(int) (n * 0.99)], dist[n - 1], n);

      // ── B) end-to-end wishes (single thread; multiply by cores for real rate) ──
      // Every wish here is one the picker actually offers. Nether and End rows came back on
      // 2026-08-02 and are exercised at the bottom, at their honest per-dimension radii.
      SeedCriteria.StructureTarget village = byLabel(catalog, "Surface Village");
      SeedCriteria.StructureTarget portal = byLabel(catalog, "Ruined Portal");
      SeedCriteria.StructureTarget mansion = byLabel(catalog, "Woodland Mansion");

      run("Village (Exact 64)", ctx, c -> c.structures.add(village.withRadius(64)), false, 11_000L);
      // The layout-predicate cost, measured against the plain village wish directly above it:
      // the difference between the two IS the price of assembling villages, which is the number
      // to look at before blaming the search.
      SeedCriteria.StructureTarget armorer = byLabel(catalog, "Village with Armorer");
      run("Village + ARMORER (Exact 64)", ctx, c -> c.structures.add(armorer.withRadius(64)), false, 11_000L);
      run("Village + jungle (Exact 64)", ctx, c -> {
         c.structures.add(village.withRadius(64));
         c.biomes.add(biome("jungle", 64));
      }, false, 13_000L);
      run("Village + Ruined Portal (Exact 64)", ctx, c -> {
         c.structures.add(village.withRadius(64));
         c.structures.add(portal.withRadius(64));
      }, false, 14_000L);
      run("Mansion (Exact 64)", ctx, c -> c.structures.add(mansion.withRadius(64)), false, 16_000L);

      // Phase 3 predicates, each printed next to its plain row so the layout cost is the
      // DIFFERENCE between the pair rather than a number with nothing to compare it to. The
      // radius is wide (1500) because these are ocean/edge-of-biome structures — at 64 blocks
      // most seeds fail on distance long before any assembly happens, which would measure the
      // placement funnel and tell us nothing about the predicate.
      SeedCriteria.StructureTarget ruins = byLabel(catalog, "Ocean Ruins");
      SeedCriteria.StructureTarget bigRuin = byLabel(catalog, "Large Ocean Ruin");
      SeedCriteria.StructureTarget outpost = byLabel(catalog, "Pillager Outpost");
      SeedCriteria.StructureTarget cages = byLabel(catalog, "Outpost with Cages");
      SeedCriteria.StructureTarget trail = byLabel(catalog, "Trail Ruins");
      SeedCriteria.StructureTarget group = byLabel(catalog, "Trail Ruins with Building Group");

      int many = 100_000;   // effectively "run the full budget" — these wishes are common
      run("Ocean Ruins (1500)", ctx, c -> c.structures.add(ruins.withRadius(1500)), false, 21_000L, many);
      run("Ocean Ruins + LARGE (1500)", ctx, c -> c.structures.add(bigRuin.withRadius(1500)), false, 21_000L, many);
      run("Outpost (1500)", ctx, c -> c.structures.add(outpost.withRadius(1500)), false, 23_000L, many);
      run("Outpost + CAGES (1500)", ctx, c -> c.structures.add(cages.withRadius(1500)), false, 23_000L, many);
      run("Trail Ruins (1500)", ctx, c -> c.structures.add(trail.withRadius(1500)), false, 27_000L, many);
      run("Trail Ruins + GROUP (1500)", ctx, c -> c.structures.add(group.withRadius(1500)), false, 27_000L, many);
      // Phase 4: the deep structures, each printed next to its plain row so the layout cost is a
      // DIFFERENCE and not a number floating on its own.
      //
      // Two of these plain rows are NOT free any more, and that is deliberate rather than a
      // regression: End City and Nether Fossil now carry an existence token, so their "plain" rows
      // assemble too. They have to — villageDiag measures only 21% of confirmed End slots and 50%
      // of confirmed fossil slots actually containing one, so the cheaper version of those rows
      // was reporting coordinates with nothing at them.
      SeedCriteria.StructureTarget chambers = byLabel(catalog, "Trial Chambers");
      SeedCriteria.StructureTarget eruption = byLabel(catalog, "Trial Chamber with Eruption Room");
      SeedCriteria.StructureTarget encounter = byLabel(catalog, "Trial Chamber with Encounter Hall");
      SeedCriteria.StructureTarget city = byLabel(catalog, "Ancient City");
      SeedCriteria.StructureTarget sauna = byLabel(catalog, "Ancient City with Sauna");
      SeedCriteria.StructureTarget bastion = byLabel(catalog, "Bastion Remnant");
      SeedCriteria.StructureTarget treasure = byLabel(catalog, "Treasure Bastion");
      SeedCriteria.StructureTarget bridge = byLabel(catalog, "Bridge Bastion");
      SeedCriteria.StructureTarget fortress = byLabel(catalog, "Nether Fortress");
      SeedCriteria.StructureTarget fossil = byLabel(catalog, "Nether Fossil");
      SeedCriteria.StructureTarget endCity = byLabel(catalog, "End City");
      SeedCriteria.StructureTarget ship = byLabel(catalog, "End City with Ship");

      run("Trial Chambers (1500)", ctx, c -> c.structures.add(chambers.withRadius(1500)), false, 31_000L, many);
      run("Trial Chambers + ERUPTION (1500)", ctx, c -> c.structures.add(eruption.withRadius(1500)), false, 31_000L, many);
      run("Trial Chambers + ENCOUNTER (1500)", ctx, c -> c.structures.add(encounter.withRadius(1500)), false, 31_000L, many);
      run("Ancient City (1500)", ctx, c -> c.structures.add(city.withRadius(1500)), false, 33_000L, many);
      run("Ancient City + SAUNA (1500)", ctx, c -> c.structures.add(sauna.withRadius(1500)), false, 33_000L, many);
      // Nether radii are in NETHER blocks and floor at 208 (fortress and bastion share one set).
      run("Nether Fortress (400)", ctx, c -> c.structures.add(fortress.withRadius(400)), false, 35_000L, many);
      run("Bastion (400)", ctx, c -> c.structures.add(bastion.withRadius(400)), false, 35_000L, many);
      run("Bastion + TREASURE type (400)", ctx, c -> c.structures.add(treasure.withRadius(400)), false, 35_000L, many);
      run("Bastion + BRIDGE type (400)", ctx, c -> c.structures.add(bridge.withRadius(400)), false, 35_000L, many);
      run("Nether Fossil (400, existence token)", ctx, c -> c.structures.add(fossil.withRadius(400)), false, 37_000L, many);
      // End radii floor at 3072 — cities only exist on the outer islands, so a small one can never
      // match and pretending otherwise would just search forever.
      run("End City (existence token)", ctx, c -> c.structures.add(endCity.withRadius(3072)), false, 39_000L, many);
      run("End City + SHIP", ctx, c -> c.structures.add(ship.withRadius(3072)), false, 39_000L, many);

      run("Village + jungle (Fast 100)", ctx, c -> {
         c.structures.add(village.withRadius(100));
         c.biomes.add(biome("jungle", 100));
      }, true, 17_000L);

      System.out.println("======================================");
   }

   private interface Fill {
      void fill(SeedCriteria c);
   }

   /** Sequential seeds from a fixed base so runs are reproducible; stops at 8 hits or 25s. */
   private static void run(String name, WorldgenContext ctx, Fill fill, boolean fast, long seedBase) {
      run(name, ctx, fill, fast, seedBase, 8);
   }

   /**
    * Same, with the hit target raised.
    *
    * The default of 8 exists for RARE wishes, where the 25-second budget is what ends the run and
    * the rate is measured over thousands of seeds. A COMMON wish hits 8 in a tenth of a second and
    * reports a throughput measured over eight seeds, which is noise — and every Phase 3 predicate
    * is common at a wide radius. Raising the target for those lets the budget end the run instead,
    * so the seeds/sec is measured over a real sample.
    */
   private static void run(String name, WorldgenContext ctx, Fill fill, boolean fast, long seedBase,
                           int wantHits) {
      SeedCriteria c = new SeedCriteria();
      fill.fill(c);
      List<String> lines = new ArrayList<>(4);
      int hits = 0;
      long firstHitSeed = 0;
      List<String> firstHitLines = List.of();
      long checked = 0;
      long start = System.nanoTime();
      long budget = start + 25_000_000_000L;
      long seed = seedBase;
      while (hits < wantHits && System.nanoTime() < budget) {
         lines.clear();
         if (c.test(seed, ctx, lines, fast)) {
            hits++;
            if (hits == 1) {
               firstHitSeed = seed;
               firstHitLines = List.copyOf(lines);
            }
         }
         checked++;
         seed++;
      }
      double secs = (System.nanoTime() - start) / 1e9;
      System.out.printf("%n--- %s ---%n", name);
      System.out.printf("checked %,d seeds in %.1fs  →  %,.0f seeds/sec/thread · %d hits (1 in %,d)%n",
            checked, secs, checked / secs, hits, hits == 0 ? checked : checked / hits);
      if (hits > 0) {
         System.out.println("verify in-game with seed " + firstHitSeed + " :");
         for (String l : firstHitLines) {
            System.out.println("   " + l);
         }
      } else {
         System.out.println("NO HITS — if this wish is common, something is wrong with the search.");
      }
   }

   private static SeedCriteria.StructureTarget byLabel(List<SeedCriteria.StructureTarget> catalog, String label) {
      for (SeedCriteria.StructureTarget t : catalog) {
         if (t.label.equals(label)) {
            return t;
         }
      }
      throw new IllegalStateException("catalog is missing: " + label);
   }

   private static SeedCriteria.BiomeTarget biome(String path, int radius) {
      Identifier id = Identifier.withDefaultNamespace(path);
      return new SeedCriteria.BiomeTarget(
            ResourceKey.create(Registries.BIOME, id), path, SeedCatalog.biomeDim(id), radius, 0);
   }

   /** Loads the real vanilla worldgen registries (with tags) headlessly, like the dedicated
    *  server — same recipe as {@link VillageDiagnostic#loadFullRegistries}. */
   private static RegistryAccess.Frozen loadFullRegistries() {
      PackRepository repo = ServerPacksSource.createVanillaTrustedRepository();
      repo.reload();
      List<PackResources> packs = repo.getAvailablePacks().stream().map(Pack::open).toList();
      MultiPackResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs);
      LayeredRegistryAccess<RegistryLayer> layered = RegistryLayer.createRegistryAccess();
      RegistryAccess.Frozen forLoading = layered.getAccessForLoading(RegistryLayer.WORLDGEN);
      List<Registry.PendingTags<?>> pending = TagLoader.loadTagsForExistingRegistries(resources, forLoading);
      List<HolderLookup.RegistryLookup<?>> base = TagLoader.buildUpdatedLookups(forLoading, pending);
      RegistryAccess.Frozen worldgen = RegistryDataLoader.load(resources, base, RegistryDataLoader.WORLDGEN_REGISTRIES, Runnable::run).join();
      pending.forEach(Registry.PendingTags::apply);
      return layered.replaceFrom(RegistryLayer.WORLDGEN, worldgen).compositeAccess();
   }

   private SpeedDiagnostic() {
   }
}
