package com.fablevision.client.seedfinder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import com.fablevision.VillageDiagnostic;
import com.fablevision.client.seedfinder.SeedMapData.MapDim;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * WHICH STRUCTURES CAN THE TEMPLATE-CACHE RACE HIDE, AND DOES THE WARM-UP STOP IT? (1.46.1)
 *
 * Every round starts from a FRESH template manager — cold caches, as at the start of a game session — and
 * rounds alternate between the warm-up off (the control) and on. Two arms:
 *
 *   MAP: a first wide view ({@link #REACH} blocks) of a new seed in each dimension, built by the map's own
 *        code with its own threads. Counts, per structure, the collisions asked again, the checks given
 *        up (a give-up is a hidden structure), and the views' "unverifiable" totals.
 *   SEARCH: content-row searches that assemble a structure — a village, a mansion, a trial chamber, an
 *        ancient city, a bastion — run through the engine's per-seed path on one thread per core less one
 *        ({@link WorldsRun}), for a few seconds each. A give-up there is a seed turned down for no reason.
 *
 * Verdict: with the warm-up ON nothing may be given up and nothing hidden, in either arm. The control
 * (warm-up OFF) is reported, and the map control must show at least one collision — a stress test that
 * cannot make the race happen proves nothing about preventing it.
 *
 * {@code gradlew coldDiag --args="<rounds per arm, default 4> [map|search|both]"}
 */
public final class ColdStartDiagnostic {

   private static final int REACH = 2_800;
   private static final long SEARCH_MS = 6_000;
   private static final String[] SEARCH_ROWS = {"Village with Armorer", "Mansion with Lava Vault",
      "Trial Chamber with Eruption Room", "Ancient City with Sauna", "Treasure Bastion"};

   private static MultiPackResourceManager resources;
   private static int failed;

   public static void main(String[] args) throws Exception {
      int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 4;
      String arms = args.length > 1 ? args[1] : "both";
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      PackRepository repo = ServerPacksSource.createVanillaTrustedRepository();
      repo.reload();
      List<PackResources> packs = repo.getAvailablePacks().stream().flatMap(Pack::open).toList();
      resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs);
      System.out.println("========== COLD TEMPLATE CACHES: WHAT RACES, AND DOES THE WARM-UP STOP IT ==========");
      System.out.println("  " + rounds + " rounds per arm and setting, a fresh template manager each round");
      if (!arms.equals("search")) {
         mapArm(ctx, rounds);
      }
      if (!arms.equals("map")) {
         searchArm(reg, ctx, rounds);
      }
      TemplateWarmup.SKIP = false;
      System.out.println();
      System.out.println(failed == 0 ? "OK — with the warm-up nothing was given up or hidden, and the control raced"
            : failed + " FAILED");
      System.exit(failed == 0 ? 0 : 1);
   }

   /** A new template manager over the vanilla packs: every template cold, exactly as at session start. */
   private static StructureTemplateManager freshManager() throws Exception {
      Path dir = Files.createTempDirectory("fablevision-cold");
      LevelStorageSource storage = LevelStorageSource.createDefault(dir);
      try (LevelStorageSource.LevelStorageAccess access = storage.createAccess("cold")) {
         return new StructureTemplateManager(resources, access, DataFixers.getDataFixer(),
               net.minecraft.core.registries.BuiltInRegistries.BLOCK);
      }
   }

   private static Map<String, Long> snapshot(Map<String, AtomicLong> m) {
      Map<String, Long> out = new TreeMap<>();
      m.forEach((k, v) -> out.put(k, v.get()));
      return out;
   }

   private static void addDelta(Map<String, Long> into, Map<String, Long> before, Map<String, AtomicLong> now) {
      now.forEach((k, v) -> {
         long d = v.get() - before.getOrDefault(k, 0L);
         if (d > 0) {
            into.merge(k, d, Long::sum);
         }
      });
   }

   // ── The map ──────────────────────────────────────────────────────────────────────────────────────

   private static void mapArm(WorldgenContext ctx, int rounds) throws Exception {
      System.out.println();
      System.out.println("-- MAP: a first wide view (" + REACH + " blocks) in each dimension, cold --");
      java.util.Random pick = new java.util.Random(4611L);
      Map<String, Long> retried0 = new TreeMap<>(), lost0 = new TreeMap<>(), retried1 = new TreeMap<>(), lost1 = new TreeMap<>();
      int hidden0 = 0;
      int hidden1 = 0;
      long warmMs = 0;
      int warmTemplates = 0;
      for (int r = 0; r < rounds * 2; r++) {
         boolean warm = r % 2 == 1;
         TemplateWarmup.SKIP = !warm;
         VillageLayout.setTemplateManager(freshManager());
         long seed = pick.nextLong();
         BlockPos spawn = SeedMapData.overworldSpawn(ctx, seed);
         Map<String, Long> rb = snapshot(VillageLayout.RACES_RETRIED);
         Map<String, Long> lb = snapshot(VillageLayout.RACES_LOST);
         StringBuilder line = new StringBuilder("  round " + (r / 2 + 1) + (warm ? " warm-up ON " : " warm-up OFF") + "  seed " + seed + ":");
         for (MapDim dim : MapDim.values()) {
            SeedMapData.View v = SeedMapData.buildAt(ctx, seed, dim, spawn, SeedMapData.originX(dim, spawn),
                  SeedMapData.originZ(dim, spawn), REACH);
            line.append("  ").append(dim).append(" ").append(v.nearby().size()).append(" drawn, ")
                  .append(v.unverifiable()).append(" hidden");
            if (warm) {
               hidden1 += v.unverifiable();
               TemplateWarmup.Report w = SeedMapData.lastWarm;
               warmMs += w.ms();
               warmTemplates += w.templates();
               if (!w.complete()) {
                  line.append(" (warm-up NOT complete)");
               }
               SeedMapData.lastWarm = TemplateWarmup.Report.NOTHING;
            } else {
               hidden0 += v.unverifiable();
            }
         }
         addDelta(warm ? retried1 : retried0, rb, VillageLayout.RACES_RETRIED);
         addDelta(warm ? lost1 : lost0, lb, VillageLayout.RACES_LOST);
         System.out.println(line);
      }
      System.out.println("  warm-up OFF: hidden " + hidden0 + "; collisions retried " + retried0 + "; given up " + lost0);
      System.out.println("  warm-up ON : hidden " + hidden1 + "; collisions retried " + retried1 + "; given up " + lost1
            + "; warm-up " + warmTemplates + " templates in " + warmMs + " ms over " + rounds + " cold starts");
      verdict("map, warm-up ON: nothing hidden", hidden1 == 0);
      verdict("map, warm-up ON: no check given up", lost1.isEmpty());
      verdict("map, warm-up OFF (control): the race happened (collisions > 0)", !retried0.isEmpty() || !lost0.isEmpty());
   }

   // ── The search ───────────────────────────────────────────────────────────────────────────────────

   private static void searchArm(RegistryAccess.Frozen reg, WorldgenContext ctx, int rounds) throws Exception {
      System.out.println();
      System.out.println("-- SEARCH: content rows through SeedFinder.consider, " + SEARCH_MS / 1000 + " s each, cold --");
      List<SeedCriteria.StructureTarget> catalog = SeedCatalog.structures(ctx);
      Map<String, Long> retried0 = new TreeMap<>(), lost0 = new TreeMap<>(), retried1 = new TreeMap<>(), lost1 = new TreeMap<>();
      long warmMs = 0;
      long checked0 = 0;
      long checked1 = 0;
      for (int r = 0; r < rounds * 2; r++) {
         boolean warm = r % 2 == 1;
         TemplateWarmup.SKIP = !warm;
         VillageLayout.setTemplateManager(freshManager());
         StringBuilder line = new StringBuilder("  round " + (r / 2 + 1) + (warm ? " warm-up ON " : " warm-up OFF") + ":");
         for (String label : SEARCH_ROWS) {
            SeedCriteria.StructureTarget row = null;
            for (SeedCriteria.StructureTarget t : catalog) {
               if (t.label.equals(label)) {
                  row = t;
               }
            }
            if (row == null) {
               line.append("  [no row ").append(label).append("]");
               continue;
            }
            SeedCriteria criteria = new SeedCriteria();
            criteria.structures.add(row.withRadius(row.dim == SeedCriteria.Dim.NETHER ? 600 : 800));
            Map<String, Long> rb = snapshot(VillageLayout.RACES_RETRIED);
            Map<String, Long> lb = snapshot(VillageLayout.RACES_LOST);
            if (warm) {
               // What the workers do first (SeedFinder.warmTemplates); WorldsRun then finds it done.
               TemplateWarmup.Report w = TemplateWarmup.forSearch(criteria, reg, VillageLayout.templates(),
                     TemplateWarmup.BUDGET_MS);
               warmMs += w.ms();
               line.append("  ").append(label).append(": ").append(w.templates()).append(" templates ")
                     .append(w.ms()).append(" ms").append(w.complete() ? "" : " NOT COMPLETE");
            }
            WorldsRun.Run run = WorldsRun.run(criteria, reg, ctx, 1_000, true, 0x1461_0000L + r * 16L + label.hashCode(),
                  SEARCH_MS);
            if (warm) {
               checked1 += run.checked();
            } else {
               checked0 += run.checked();
            }
            addDelta(warm ? retried1 : retried0, rb, VillageLayout.RACES_RETRIED);
            addDelta(warm ? lost1 : lost0, lb, VillageLayout.RACES_LOST);
         }
         System.out.println(line);
      }
      System.out.println("  warm-up OFF: " + checked0 + " seeds; collisions retried " + retried0 + "; given up (seeds turned down) " + lost0);
      System.out.println("  warm-up ON : " + checked1 + " seeds; collisions retried " + retried1 + "; given up " + lost1
            + "; warm-ups took " + warmMs + " ms in all");
      verdict("search, warm-up ON: no check given up", lost1.isEmpty());
   }

   private static void verdict(String what, boolean ok) {
      System.out.println("  " + (ok ? "ok   " : "FAIL ") + what);
      if (!ok) {
         failed++;
      }
   }

   private ColdStartDiagnostic() {
   }
}
