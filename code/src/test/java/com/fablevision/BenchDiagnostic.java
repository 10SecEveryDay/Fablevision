package com.fablevision;

import com.fablevision.client.seedfinder.SeedCatalog;
import com.fablevision.client.seedfinder.SeedCriteria;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import com.fablevision.client.seedfinder.SeedFunnel;
import com.fablevision.client.seedfinder.VillageLayout;
import com.fablevision.client.seedfinder.WorldgenContext;
import com.mojang.datafixers.DataFixer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
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
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * The REPEATABLE benchmark, as opposed to {@link SpeedDiagnostic}'s survey of many wishes.
 *
 * Three fixed searches (one structure, one biome, one layout), each run over a FIXED seed count
 * from a FIXED base, single-threaded, after a warmup. Fixed counts rather than a time budget
 * because the point is to compare runs against each other: the same seeds must be tested every
 * time or the pass/fail sets are not comparable.
 *
 * Every search runs in BOTH Exact and Fast mode. That is not padding — some optimisations only
 * touch one of the two paths, and a benchmark that covered only Exact would report a Fast-mode
 * fix as "no change" and blame the fix rather than the measurement.
 *
 * Writes {@code <variant>__<search>.txt} into the output directory: every PASSING seed with its
 * match lines, plus a checksum folded over the verdict of EVERY seed (pass and fail). Two runs
 * with the same pass list and the same checksum tested the same seeds and reached the same
 * answers — which is how "this fix changed nothing" gets proven instead of asserted.
 *
 * Run with {@code gradlew bench --args="<label> <outDir> [structN biomeN layoutN]"}.
 */
public final class BenchDiagnostic {

   /** Fixed seed bases. Never change these — every variant must test identical seeds. */
   private static final long BASE_STRUCT = 5_000_000L;
   private static final long BASE_BIOME = 6_000_000L;
   private static final long BASE_LAYOUT = 7_000_000L;
   private static final long BASE_ADJ = 8_000_000L;
   private static final long BASE_BUNDLE = 9_000_000L;

   /** Radii chosen so each search actually exercises its stage rather than dying earlier:
    *  the layout radius is wide enough that assembly is reached regularly, and the biome
    *  radius wide enough that the search is the cost rather than an instant miss. */
   private static final int R_STRUCT = 300;
   private static final int R_BIOME = 1000;
   private static final int R_LAYOUT = 500;

   /** One row of the matrix: a label and the toggle state it runs under. */
   private record Variant(String label, boolean fix1, boolean fix2, boolean fix3, boolean fix4,
                          boolean fix4d, boolean fix5, boolean fix6) {
      Variant(String label, boolean fix1, boolean fix2, boolean fix3, boolean fix4) {
         this(label, fix1, fix2, fix3, fix4, false, false, false);
      }

      Variant(String label, boolean fix1, boolean fix2, boolean fix3, boolean fix4, boolean fix4d) {
         this(label, fix1, fix2, fix3, fix4, fix4d, false, false);
      }

      Variant(String label, boolean fix1, boolean fix2, boolean fix3, boolean fix4, boolean fix4d,
              boolean fix5) {
         this(label, fix1, fix2, fix3, fix4, fix4d, fix5, false);
      }
   }

   /**
    * Every variant runs in ONE process, back to back, so they share machine conditions.
    *
    * Running them as separate invocations produced a "speedup" on searches the fix provably
    * cannot touch - background load between runs was being measured instead of the code. The
    * final row re-runs the baseline: if it does not land back on the first row, then ordering or
    * JIT drift is distorting the numbers and nothing in between should be believed.
    */
   private static final List<Variant> MATRIX = List.of(
         new Variant("baseline", false, false, false, false),
         new Variant("fix1", true, false, false, false),
         new Variant("fix12", true, true, false, false),
         new Variant("fix123", true, true, true, false),
         // fix4 is the one variant here that is EXPECTED to change the answer, not just the speed:
         // it rejects hits where vanilla's own biome test says nothing generates. So fix12 vs fix124
         // is the pair to read — same everything else, and the checksum difference between them is
         // the false positives being removed rather than a regression.
         new Variant("fix124", true, true, false, true),
         new Variant("baseline-recheck", false, false, false, false),
         // A second recheck row, for the A/B that actually matters: fix12 (what shipped before)
         // against fix124 (what ships now). Rechecking the BASELINE proves the machine was steady
         // across the whole matrix, which is the right guard for a full run; when only one pair is
         // being run, the pair's own first row is the thing that has to reproduce.
         new Variant("fix12-recheck", true, true, false, false),
         // fix124 with the strict check moved to the end of the wish. This one is run for its
         // CHECKSUM, not its clock: deferring is only allowed to change when the work happens, so
         // if this row does not match fix124 exactly then it is changing which seeds match and
         // must not ship, however fast it is.
         new Variant("fix124d", true, true, false, true, true),
         // THE PAIR THAT MATTERS FOR 1.37.0, and it is a bug fix rather than an optimisation, so
         // its checksum is EXPECTED to move on the biome searches. `shipped` is 1.36.0 exactly;
         // `nearest` is the same code asking findBiomeHorizontal for the closest patch instead of a
         // uniformly random one. Structure and layout rows should be byte-identical between them —
         // they never call it — and a difference there would mean the toggle leaked somewhere it
         // should not have.
         new Variant("shipped", true, true, false, true, true, false),
         new Variant("nearest", true, true, false, true, true, true),
         // Reproduces the first row of the pair after the second has run, for the same reason the
         // matrix has a baseline-recheck: if this does not land back on `shipped`, the machine
         // moved and the comparison is worthless.
         new Variant("shipped-recheck", true, true, false, true, true, false),
         // THE PAIR THAT MATTERS FOR 1.38.0. `v1372` is exactly what shipped as 1.37.2; `late` is
         // the same code with a content row's ASSEMBLY moved to the end of the wish. Unlike the
         // `nearest` pair above, this one is NOT allowed to change any answer: if any checksum
         // differs, the deferral is changing which seeds match and must not ship, however fast it
         // is. Read it on the `bundle` rows — the single-row searches have no second row to reject
         // a seed early, so the fix cannot help them and they are here only to prove it did no harm.
         new Variant("v1372", true, true, false, true, true, true, false),
         new Variant("late", true, true, false, true, true, true, true),
         new Variant("v1372-recheck", true, true, false, true, true, true, false));

   public static void main(String[] args) throws Exception {
      Path outDir = Paths.get(args.length > 0 ? args[0] : "bench-out");
      int nStruct = args.length > 1 ? Integer.parseInt(args[1]) : 15_000;
      int nBiome = args.length > 2 ? Integer.parseInt(args[2]) : 5_000;
      int nLayout = args.length > 3 ? Integer.parseInt(args[3]) : 120;
      // Comma-separated variant labels, so one pair can be re-measured without paying for the
      // whole matrix. Added because the first fix4 run was contaminated: other gradle tasks were
      // started while it ran, the baseline-recheck row came back 20% under the baseline, and every
      // number between them had to be thrown away. A shorter run is one that is easier to leave
      // alone, which is the only real defence against that.
      java.util.Set<String> only = args.length > 4
            ? new java.util.LinkedHashSet<>(java.util.Arrays.asList(args[4].split(",")))
            : java.util.Set.of();
      Files.createDirectories(outDir);

      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      installTemplates(reg);

      List<SeedCriteria.StructureTarget> catalog = SeedCatalog.structures(ctx);
      SeedCriteria.StructureTarget village = byLabel(catalog, "Surface Village");
      SeedCriteria.StructureTarget armorer = byLabel(catalog, "Village with Armorer");
      // The bundle rows below are the search the deferral is FOR: one content row and three plain
      // ones, with the content row FIRST, which is where the catalog puts it and therefore what a
      // player actually gets. Before fix 6 that ordering meant the village was assembled in full
      // before the portal had been asked whether the seed was worth anything.
      SeedCriteria.StructureTarget portal = byLabel(catalog, "Ruined Portal");
      SeedCriteria.StructureTarget wreck = byLabel(catalog, "Shipwreck");
      SeedCriteria.StructureTarget pyramid = byLabel(catalog, "Desert Pyramid");
      int nBundle = nLayout * 10;

      System.out.println("single-threaded, fixed seed counts, warmup excluded from timing");
      System.out.println("NOTE: this finder checks real structure LAYOUT (assembles jigsaw pieces).");
      System.out.println("      Placement-only tools do strictly less work per seed.");

      for (Variant v : MATRIX) {
         if (!only.isEmpty() && !only.contains(v.label())) {
            continue;
         }
         SeedFunnel.FAST_STAGE0 = v.fix1();
         SeedFunnel.CACHE_SCAFFOLD = v.fix2();
         SeedFunnel.HOIST_BIOME = v.fix2();
         SeedFunnel.BIOME_PREFILTER = v.fix3();
         SeedFunnel.STRICT_BIOME = v.fix4();
         SeedFunnel.STRICT_DEFERRED = v.fix4d();
         SeedFunnel.NEAREST_BIOME = v.fix5();
         SeedFunnel.LATE_LAYOUT = v.fix6();
         String variant = v.label();
         System.out.println();
         System.out.println("################ VARIANT: " + variant
               + "  (fix1=" + v.fix1() + " fix2=" + v.fix2() + " fix3=" + v.fix3()
               + " fix4=" + v.fix4() + " fix4d=" + v.fix4d() + " fix5=" + v.fix5()
               + " fix6=" + v.fix6() + ") ################");

         // Exact mode: measures from the seed's REAL spawn (findSpawnPosition).
         run(variant, outDir, "struct-exact", "STRUCTURE: Surface Village <=" + R_STRUCT + " (Exact)",
               ctx, c -> c.structures.add(village.withRadius(R_STRUCT)), false, BASE_STRUCT, nStruct);
         run(variant, outDir, "biome-exact", "BIOME: jungle <=" + R_BIOME + " (Exact)",
               ctx, c -> c.biomes.add(biome("jungle", R_BIOME)), false, BASE_BIOME, nBiome);
         run(variant, outDir, "layout-exact", "LAYOUT: Village with ARMORER <=" + R_LAYOUT + " (Exact)",
               ctx, c -> c.structures.add(armorer.withRadius(R_LAYOUT)), false, BASE_LAYOUT, nLayout);

         // Fast mode: measures from 0,0. Same three wishes, the other code path.
         run(variant, outDir, "struct-fast", "STRUCTURE: Surface Village <=" + R_STRUCT + " (Fast)",
               ctx, c -> c.structures.add(village.withRadius(R_STRUCT)), true, BASE_STRUCT, nStruct);
         run(variant, outDir, "biome-fast", "BIOME: jungle <=" + R_BIOME + " (Fast)",
               ctx, c -> c.biomes.add(biome("jungle", R_BIOME)), true, BASE_BIOME, nBiome);
         run(variant, outDir, "layout-fast", "LAYOUT: Village with ARMORER <=" + R_LAYOUT + " (Fast)",
               ctx, c -> c.structures.add(armorer.withRadius(R_LAYOUT)), true, BASE_LAYOUT, nLayout);

         // The 1.37.0 addition, measured rather than estimated. The pair is the headline wish —
         // "a big pale garden next to dark oak" — minus the size tier, so the number is the cost of
         // the ADJACENCY search on its own rather than of everything new at once. The targets are
         // built inside the lambda because the funnel matches an adjacency's anchor by identity:
         // one BiomeTarget instance, held by both the biomes list and the pair.
         run(variant, outDir, "adjacent-exact",
               "NEXT TO: pale_garden <=" + R_BIOME + " next to dark_forest <=256 (Exact)",
               ctx, c -> {
                  SeedCriteria.BiomeTarget garden = biome("pale_garden", R_BIOME);
                  c.biomes.add(garden);
                  c.addAdjacency(garden, biome("dark_forest", R_BIOME), null, 256);
               }, false, BASE_ADJ, nBiome);
         run(variant, outDir, "adjacent-fast",
               "NEXT TO: pale_garden <=" + R_BIOME + " next to dark_forest <=256 (Fast)",
               ctx, c -> {
                  SeedCriteria.BiomeTarget garden = biome("pale_garden", R_BIOME);
                  c.biomes.add(garden);
                  c.addAdjacency(garden, biome("dark_forest", R_BIOME), null, 256);
               }, true, BASE_ADJ, nBiome);

         // THE ROW FIX 6 IS ABOUT. Every other search here has exactly one thing to check, so there
         // is no "other row" that could have rejected the seed first and nothing for a deferral to
         // save. This is the multi-part search the fix targets, and the one a player waits on.
         Fill bundle = c -> {
            c.structures.add(armorer.withRadius(500));
            c.structures.add(portal.withRadius(300));
            c.structures.add(wreck.withRadius(300));
            c.structures.add(pyramid.withRadius(300));
         };
         run(variant, outDir, "bundle-exact",
               "BUNDLE: armorer<=500 + portal<=300 + wreck<=300 + pyramid<=300 (Exact)",
               ctx, bundle, false, BASE_BUNDLE, nBundle);
         run(variant, outDir, "bundle-fast",
               "BUNDLE: armorer<=500 + portal<=300 + wreck<=300 + pyramid<=300 (Fast)",
               ctx, bundle, true, BASE_BUNDLE, nBundle);
      }
      System.out.println("################ MATRIX COMPLETE ################");
   }

   private interface Fill {
      void fill(SeedCriteria c);
   }

   private static void run(String variant, Path outDir, String id, String name, WorldgenContext ctx,
                           Fill fill, boolean fast, long base, int count) throws IOException {
      SeedCriteria c = new SeedCriteria();
      fill.fill(c);
      List<String> lines = new ArrayList<>(4);

      // Warmup on a DIFFERENT seed range so the timed seeds are not pre-warmed in caches that
      // the real search would not have. 5% of the run, capped, purely to let the JIT compile.
      int warm = Math.max(20, Math.min(1500, count / 20));
      for (int i = 0; i < warm; i++) {
         lines.clear();
         c.test(base - 1_000_000L + i, ctx, lines, fast);
      }

      SeedFunnel.reset();
      SeedFunnel.ENABLED = true;
      StringBuilder passes = new StringBuilder();
      long checksum = 1469598103934665603L; // FNV-1a offset basis
      int hits = 0;

      long t0 = System.nanoTime();
      for (int i = 0; i < count; i++) {
         long seed = base + i;
         lines.clear();
         boolean hit = c.test(seed, ctx, lines, fast);
         if (hit) {
            hits++;
            passes.append(seed).append('\t').append(String.join(" | ", lines)).append('\n');
         }
         // Folded over EVERY seed, so a change that flips a FAIL to a different FAIL still shows.
         checksum = fnv(fnv(checksum, seed), hit ? 1 : 0);
      }
      double secs = (System.nanoTime() - t0) / 1e9;
      SeedFunnel.ENABLED = false;

      double rate = count / secs;
      System.out.printf("%n--- %s ---%n", name);
      System.out.printf("  %,d seeds in %.2fs  ->  %,.2f seeds/sec/thread%n", count, secs, rate);
      System.out.printf("  hits: %,d  (1 in %s)%n", hits, hits == 0 ? "-" : String.format("%,d", count / hits));
      System.out.printf("  RESULT-CHECKSUM: %016x%n", checksum);
      System.out.print(SeedFunnel.report());

      Path f = outDir.resolve(variant + "__" + id + ".txt");
      String header = "# variant=" + variant + " search=" + id + " seeds=" + count
            + " base=" + base + " fast=" + fast + "\n"
            + "# checksum=" + String.format("%016x", checksum) + " hits=" + hits + "\n"
            + String.format("# seeds_per_sec=%.2f secs=%.3f%n", rate, secs);
      Files.writeString(f, header + passes, StandardCharsets.UTF_8);
   }

   private static long fnv(long h, long v) {
      for (int i = 0; i < 8; i++) {
         h ^= (v >>> (i * 8)) & 0xff;
         h *= 1099511628211L;
      }
      return h;
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

   /** Layout searches need real .nbt templates; headless there is no server to ask. */
   private static void installTemplates(RegistryAccess.Frozen reg) throws IOException {
      Path tmp = Files.createTempDirectory("fv-bench");
      var storage = net.minecraft.world.level.storage.LevelStorageSource.createDefault(tmp);
      var access = storage.createAccess("bench");
      PackRepository repo = ServerPacksSource.createVanillaTrustedRepository();
      repo.reload();
      List<PackResources> packs = repo.getAvailablePacks().stream().map(Pack::open).toList();
      MultiPackResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs);
      DataFixer fixer = net.minecraft.util.datafix.DataFixers.getDataFixer();
      StructureTemplateManager manager = new StructureTemplateManager(
            resources, access, fixer, reg.lookupOrThrow(Registries.BLOCK));
      VillageLayout.setTemplateManager(manager);
      if (!VillageLayout.available()) {
         throw new IllegalStateException("no template manager - layout numbers would be meaningless");
      }
   }

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

   private BenchDiagnostic() {
   }
}
