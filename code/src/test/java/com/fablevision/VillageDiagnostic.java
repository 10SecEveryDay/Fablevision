package com.fablevision;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import com.fablevision.client.seedfinder.VillageLayout;
import com.fablevision.client.seedfinder.WorldgenContext;

import com.mojang.datafixers.DataFixer;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * Dev-only: proves the village LAYOUT reader actually assembles a real piece list, with no game
 * running. Run with {@code gradlew villageDiag}.
 *
 * What a healthy run looks like:
 *   - most seeds return a non-empty piece list (empty everywhere = assembly failed outright),
 *   - the town centre appears first and buildings follow (a list of length 1 means the jigsaw
 *     never expanded past the start piece, which is the exact bug this replaces),
 *   - the building histogram is spread across many types rather than concentrated on one.
 *
 * DO NOT try to verify these seeds with /locate. Everything here assembles at ChunkPos(0,0) — a
 * hypothetical ("if a structure started at 0,0, what would it build"), which is the right question
 * for testing assembly and the wrong one for walking anywhere. Placement rarely puts a structure
 * at 0,0, and /locate sends you to a real one elsewhere whose own chunk seeds its own dice, so the
 * two agree only by luck. For a prediction you can actually walk to, see
 * {@link com.fablevision.client.seedfinder.IglooWalkDiagnostic} ({@code gradlew iglooWalk}), which
 * runs the real placement funnel against the seed's spawn and reports coordinates.
 */
public final class VillageDiagnostic {

   public static void main(String[] args) throws Exception {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);

      // Exercise the EXACT path the mod takes on the Create World screen — pack repository
      // reload included — before any override is injected. This is the number that decides
      // whether a layout search is safe to offer there.
      long wall = System.currentTimeMillis();
      boolean builtItself = VillageLayout.templates() != null;
      System.out.println("== production path (what Create World would pay) ==");
      System.out.println("built from client resources : " + builtItself);
      System.out.println("total wall time             : " + (System.currentTimeMillis() - wall) + "ms");
      System.out.println("reported by VillageLayout   : " + VillageLayout.buildMillis() + "ms");
      System.out.println("(paid once, on a worker thread, never on the render thread)");

      // Assembly needs real templates. In game these come from the running server; headless we
      // build a manager over the vanilla data packs and a throwaway world folder.
      Path tmp = Files.createTempDirectory("fv-village-diag");
      LevelStorageSource storage = LevelStorageSource.createDefault(tmp);
      try (LevelStorageSource.LevelStorageAccess access = storage.createAccess("diag")) {
         PackRepository repo = ServerPacksSource.createVanillaTrustedRepository();
         repo.reload();
         List<PackResources> packs = repo.getAvailablePacks().stream().map(Pack::open).toList();
         MultiPackResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs);
         // Measure the SAME construction the mod does on the Create World screen, so the cost
         // the screen would pay is a measured number rather than a guess.
         long t0 = System.nanoTime();
         DataFixer fixer = net.minecraft.util.datafix.DataFixers.getDataFixer();
         var manager = new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager(
               resources, access, fixer, reg.lookupOrThrow(Registries.BLOCK));
         long ctorMs = (System.nanoTime() - t0) / 1_000_000L;

         // Constructing it is lazy; the real cost lands on the first template actually loaded.
         long t1 = System.nanoTime();
         manager.get(Identifier.withDefaultNamespace("village/plains/town_centers/plains_fountain_01"));
         long firstLoadMs = (System.nanoTime() - t1) / 1_000_000L;

         VillageLayout.setTemplateManager(manager);
         System.out.println("template manager construct: " + ctorMs + "ms");
         System.out.println("first template load       : " + firstLoadMs + "ms");

         if (!VillageLayout.available()) {
            System.out.println("FAILED: no template manager — nothing else can be trusted.");
            return;
         }

         Holder<Structure> village = reg.lookupOrThrow(Registries.STRUCTURE)
               .getOrThrow(BuiltinStructures.VILLAGE_PLAINS);

         int sample = args.length > 0 ? Integer.parseInt(args[0]) : 40;
         int empty = 0;
         int startOnly = 0;
         int totalPieces = 0;
         Map<String, Integer> buildings = new TreeMap<>();
         Map<Long, List<Identifier>> shown = new LinkedHashMap<>();
         // Keep every piece list: the predicate spot-check below must NOT re-assemble, or the
         // diagnostic spends minutes redoing work it already did once per token.
         List<List<Identifier>> all = new java.util.ArrayList<>();

         // Chunk 0,0 is not necessarily a village slot for a given seed; assembly does not care —
         // it answers "if a village started here, what would it build". That is exactly the
         // question confirm() asks, and it always asks it about a chunk placement already chose.
         for (long seed = 1; seed <= sample; seed++) {
            RandomState full = ctx.fullRandomState(Dim.OVERWORLD, seed);
            List<Identifier> pieces = VillageLayout.pieceTemplates(
                  reg, ctx, seed, new ChunkPos(0, 0), village, full);
            all.add(pieces);
            if (pieces.isEmpty()) {
               empty++;
               continue;
            }
            if (pieces.size() == 1) {
               startOnly++;
            }
            totalPieces += pieces.size();
            for (Identifier id : pieces) {
               String path = id.getPath();
               int slash = path.lastIndexOf('/');
               buildings.merge(slash < 0 ? path : path.substring(slash + 1), 1, Integer::sum);
            }
            if (shown.size() < 3) {
               shown.put(seed, pieces);
            }
         }

         int built = sample - empty;
         System.out.println("========== VILLAGE LAYOUT DIAGNOSTIC over " + sample + " seeds ==========");
         System.out.println("assembled a piece list   : " + built + " / " + sample);
         System.out.println("returned nothing         : " + empty + "   (all = assembly is broken)");
         System.out.println("start piece only         : " + startOnly + "   (all = jigsaw never expanded)");
         System.out.println("average pieces per village: "
               + (built == 0 ? "n/a" : String.format("%.1f", totalPieces / (double) built)));
         System.out.println("distinct templates seen  : " + buildings.size());

         System.out.println("-- sample villages (verify these in game with /locate) --");
         shown.forEach((seed, pieces) -> {
            System.out.println("  seed " + seed + "  (" + pieces.size() + " pieces)");
            pieces.stream().limit(12).forEach(p -> System.out.println("      " + p));
            if (pieces.size() > 12) {
               System.out.println("      … " + (pieces.size() - 12) + " more");
            }
         });

         // The REAL house template names, straight from what assembled. This is what the token
         // list must be built from — guessing "toolsmith" when the asset is "tool_smith" gives a
         // predicate that silently never matches.
         System.out.println("-- house templates actually seen (build the picker from THIS) --");
         buildings.entrySet().stream()
               .filter(e -> e.getKey().contains("house") || e.getKey().contains("smith")
                     || e.getKey().contains("cottage") || e.getKey().contains("temple")
                     || e.getKey().contains("stable") || e.getKey().contains("butcher")
                     || e.getKey().contains("farm") || e.getKey().contains("library")
                     || e.getKey().contains("masons") || e.getKey().contains("tannery")
                     || e.getKey().contains("cartographer") || e.getKey().contains("armorer")
                     || e.getKey().contains("fletcher") || e.getKey().contains("shepherd")
                     || e.getKey().contains("fisher"))
               .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
               .forEach(e -> System.out.println("  " + pad(e.getKey(), 34) + e.getValue()));

         System.out.println("-- predicate spot-check (reusing the lists above) --");
         for (String token : List.of("armorer", "butcher", "cartographer", "fletcher",
               "shepherd", "mason", "tannery", "fisher", "library", "weaponsmith",
               "tool_smith", "temple", "farm", "stable")) {
            int hits = 0;
            for (List<Identifier> pieces : all) {
               if (VillageLayout.hasBuilding(pieces, token)) {
                  hits++;
               }
            }
            System.out.println("  " + pad(token, 14) + hits + " / " + sample + " villages");
         }
         System.out.println("=====================================================================");
         mansionCheck(reg, ctx, sample);
         iglooCheck(reg, ctx, sample);
         portalCheck(reg, ctx, sample);
         armorerCheck(reg, ctx, sample);
         shipwreckCheck(reg, ctx, sample);
         oceanRuinCheck(reg, ctx, sample, manager);
         outpostCheck(reg, ctx, sample, manager);
         trailRuinsCheck(reg, ctx, sample, manager);
         deepChecks(reg, ctx, sample, manager);
      } finally {
         // Best effort: the temp world is only scaffolding for the template manager.
         try (var walk = Files.walk(tmp)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
         } catch (Exception ignored) {
         }
      }
   }

   /** Same full-registry bootstrap the eye diagnostic uses — tags bound, worldgen loaded.
    *  Public so the igloo walk diagnostic can share it instead of keeping a third copy. */
   public static RegistryAccess.Frozen loadFullRegistries() {
      PackRepository repo = ServerPacksSource.createVanillaTrustedRepository();
      repo.reload();
      List<PackResources> packs = repo.getAvailablePacks().stream().map(Pack::open).toList();
      MultiPackResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs);
      LayeredRegistryAccess<RegistryLayer> layered = RegistryLayer.createRegistryAccess();
      RegistryAccess.Frozen forLoading = layered.getAccessForLoading(RegistryLayer.WORLDGEN);
      List<Registry.PendingTags<?>> pending = TagLoader.loadTagsForExistingRegistries(resources, forLoading);
      List<HolderLookup.RegistryLookup<?>> base = TagLoader.buildUpdatedLookups(forLoading, pending);
      RegistryAccess.Frozen worldgen =
            RegistryDataLoader.load(resources, base, RegistryDataLoader.WORLDGEN_REGISTRIES, Runnable::run).join();
      pending.forEach(Registry.PendingTags::apply);
      return layered.replaceFrom(RegistryLayer.WORLDGEN, worldgen).compositeAccess();
   }

   /**
    * Woodland Mansion: the non-jigsaw path. Its pieces are TemplateStructurePieces, so the room
    * names come from the widened {@code templateName} rather than a pool element — a different
    * code path from villages, and the one every fixed-layout structure in Phase 2 will use.
    *
    * Room layout is pure RNG and terrain-independent, so unlike villages nothing here depends on
    * the heightmap; a mansion's room set is fixed the moment the seed is.
    */
   private static void mansionCheck(RegistryAccess.Frozen reg, WorldgenContext ctx, int sample) {
      Holder<Structure> mansion = reg.lookupOrThrow(Registries.STRUCTURE)
            .getOrThrow(BuiltinStructures.WOODLAND_MANSION);

      int empty = 0;
      int totalPieces = 0;
      Map<String, Integer> rooms = new TreeMap<>();
      List<List<Identifier>> all = new java.util.ArrayList<>();
      Long firstSeed = null;
      List<Identifier> firstPieces = List.of();

      for (long seed = 1; seed <= sample; seed++) {
         RandomState full = ctx.fullRandomState(Dim.OVERWORLD, seed);
         List<Identifier> pieces = VillageLayout.pieceTemplates(
               reg, ctx, seed, new ChunkPos(0, 0), mansion, full);
         all.add(pieces);
         if (pieces.isEmpty()) {
            empty++;
            continue;
         }
         totalPieces += pieces.size();
         for (Identifier id : pieces) {
            String path = id.getPath();
            int slash = path.lastIndexOf('/');
            rooms.merge(slash < 0 ? path : path.substring(slash + 1), 1, Integer::sum);
         }
         if (firstSeed == null) {
            firstSeed = seed;
            firstPieces = pieces;
         }
      }

      int built = sample - empty;
      System.out.println("========== WOODLAND MANSION LAYOUT over " + sample + " seeds ==========");
      System.out.println("assembled a piece list   : " + built + " / " + sample);
      System.out.println("returned nothing         : " + empty + "   (all = widener or assembly failed)");
      System.out.println("average rooms per mansion: "
            + (built == 0 ? "n/a" : String.format("%.1f", totalPieces / (double) built)));
      System.out.println("distinct room templates  : " + rooms.size());
      if (firstSeed != null) {
         System.out.println("-- sample mansion, seed " + firstSeed + " (" + firstPieces.size() + " pieces) --");
         firstPieces.stream().limit(10).forEach(p -> System.out.println("      " + p));
      }

      // RAREST first. Every mansion has hundreds of walls, roofs and carpets, so "contains a
      // 1x1 room" is true of all of them and worthless as a search. The rooms worth searching
      // for are the ones that appear in only some mansions — those are the real predicates.
      System.out.println("-- RAREST room templates (these are the useful predicates) --");
      rooms.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .limit(24)
            .forEach(e -> System.out.println("  " + pad(e.getKey(), 26) + e.getValue()
                  + (e.getValue() <= sample ? "   <- in some mansions only" : "")));

      System.out.println("-- predicate spot-check --");
      for (String token : List.of("1x1", "1x2", "2x2", "carpet", "corridor", "entrance",
            "roof", "stairs", "wall")) {
         int hits = 0;
         for (List<Identifier> pieces : all) {
            if (VillageLayout.hasBuilding(pieces, token)) {
               hits++;
            }
         }
         System.out.println("  " + pad(token, 12) + hits + " / " + sample + " mansions");
      }
      System.out.println("=====================================================================");
   }

   /**
    * Igloo: the smallest fixed-layout structure, and the one whose whole search value is a single
    * yes/no — does it have the basement (the ladder shaft down to the zombie-villager lab) or is
    * it just the one-room snow hut?
    *
    * Same TemplateStructurePiece path as the mansion, but where a mansion has hundreds of rooms
    * an igloo has a handful of templates total, so the rarest-first list IS the answer: whichever
    * name appears in only some igloos is the basement marker. That name is DERIVED here, not
    * assumed — the token the predicate ships with must be the one the game actually assembled.
    *
    * Like the mansion, layout is pure RNG: the basement coin-flip is decided the moment the seed
    * and chunk are, with no terrain input.
    */
   private static void iglooCheck(RegistryAccess.Frozen reg, WorldgenContext ctx, int sample) {
      Holder<Structure> igloo = reg.lookupOrThrow(Registries.STRUCTURE)
            .getOrThrow(BuiltinStructures.IGLOO);

      int empty = 0;
      int totalPieces = 0;
      Map<String, Integer> parts = new TreeMap<>();
      List<List<Identifier>> all = new java.util.ArrayList<>();
      Long firstSeed = null;
      List<Identifier> firstPieces = List.of();

      for (long seed = 1; seed <= sample; seed++) {
         RandomState full = ctx.fullRandomState(Dim.OVERWORLD, seed);
         List<Identifier> pieces = VillageLayout.pieceTemplates(
               reg, ctx, seed, new ChunkPos(0, 0), igloo, full);
         all.add(pieces);
         if (pieces.isEmpty()) {
            empty++;
            continue;
         }
         totalPieces += pieces.size();
         for (Identifier id : pieces) {
            String path = id.getPath();
            int slash = path.lastIndexOf('/');
            parts.merge(slash < 0 ? path : path.substring(slash + 1), 1, Integer::sum);
         }
         if (firstSeed == null) {
            firstSeed = seed;
            firstPieces = pieces;
         }
      }

      int built = sample - empty;
      System.out.println("========== IGLOO LAYOUT over " + sample + " seeds ==========");
      System.out.println("assembled a piece list   : " + built + " / " + sample);
      System.out.println("returned nothing         : " + empty + "   (all = widener or assembly failed)");
      System.out.println("average pieces per igloo : "
            + (built == 0 ? "n/a" : String.format("%.1f", totalPieces / (double) built)));
      System.out.println("distinct templates       : " + parts.size());
      if (firstSeed != null) {
         System.out.println("-- sample igloo, seed " + firstSeed + " (" + firstPieces.size() + " pieces) --");
         firstPieces.forEach(p -> System.out.println("      " + p));
      }

      // RAREST first, exactly as for the mansion. Here it doubles as the derivation: a template
      // present in EVERY igloo (the hut itself) is worthless as a predicate; the one present in
      // only some is the basement. Counting igloos-containing, not piece occurrences, because the
      // shaft repeats within a single igloo and would otherwise outrank the room it leads to.
      Map<String, Integer> iglooCount = new TreeMap<>();
      for (List<Identifier> pieces : all) {
         pieces.stream()
               .map(id -> {
                  String path = id.getPath();
                  int slash = path.lastIndexOf('/');
                  return slash < 0 ? path : path.substring(slash + 1);
               })
               .distinct()
               .forEach(name -> iglooCount.merge(name, 1, Integer::sum));
      }
      System.out.println("-- templates by IGLOOS CONTAINING (rarest first) --");
      iglooCount.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .forEach(e -> System.out.println("  " + pad(e.getKey(), 16) + e.getValue() + " / " + built
                  + (e.getValue() < built ? "   <- in some igloos only" : "   <- in every igloo, useless as a search")));

      // The predicate token, derived: rarest template that is not in every igloo. If this prints
      // "none", the basement either always or never generates here and the search is not real.
      String derived = iglooCount.entrySet().stream()
            .filter(e -> e.getValue() < built)
            .min(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
      System.out.println("-- derived basement token: " + (derived == null ? "none" : derived) + " --");

      // Now check the token the mod ACTUALLY SHIPS, pulled from the catalog rather than retyped
      // here. A derived name that matches nothing the picker uses would prove nothing — this is
      // the line that fails loudly if the catalog row and the real assets ever drift apart.
      String basement = com.fablevision.client.seedfinder.SeedCatalog.structures(ctx).stream()
            .filter(t -> "Igloo with Basement".equals(t.label))
            .map(t -> t.building)
            .findFirst()
            .orElse(null);
      System.out.println("-- catalog token in use  : " + (basement == null ? "MISSING ROW" : basement) + " --");
      if (basement == null) {
         System.out.println("  FAILED: the picker has no Igloo with Basement row to verify.");
      } else if (derived != null && !basement.endsWith(derived)) {
         System.out.println("  FAILED: catalog ships '" + basement + "' but assembly's rare piece is '"
               + derived + "' — the predicate would never match.");
      }

      if (basement != null) {
         int with = 0;
         for (List<Identifier> pieces : all) {
            if (VillageLayout.hasBuilding(pieces, basement)) {
               with++;
            }
         }
         System.out.println("  with basement  : " + with + " / " + built);
         System.out.println("  without        : " + (built - with) + " / " + built);
         System.out.println("  (vanilla flips a coin per igloo, so expect roughly half and half)");

         // No seed list here on purpose. These splits describe igloos hypothetically started at
         // chunk 0,0, which is not where /locate will send anyone. `gradlew iglooWalk` is the
         // one that names real igloos at real coordinates.
         System.out.println("  (these are chunk-0,0 hypotheticals — NOT walkable."
               + " Use `gradlew iglooWalk` for seeds you can verify in game.)");
      }
      System.out.println("=====================================================================");
   }

   /**
    * Ruined Portal: like the igloo it is a TemplateStructurePiece, but unlike the igloo it is
    * ONE piece per structure, and it is spread across several registry entries (standard, desert,
    * jungle, mountain, ocean, swamp) rather than one.
    *
    * So the rarest-first list here is a straight distribution over portal templates, and the
    * question is which of those names is worth searching for. Reported per registry entry,
    * because the catalog row "Ruined Portal" covers all of them and a token that only ever
    * appears in the ocean variant would be a search that almost never fires.
    */
   private static void portalCheck(RegistryAccess.Frozen reg, WorldgenContext ctx, int sample) {
      List<ResourceKey<Structure>> variants = List.of(
            BuiltinStructures.RUINED_PORTAL_STANDARD, BuiltinStructures.RUINED_PORTAL_DESERT,
            BuiltinStructures.RUINED_PORTAL_JUNGLE, BuiltinStructures.RUINED_PORTAL_MOUNTAIN,
            BuiltinStructures.RUINED_PORTAL_OCEAN, BuiltinStructures.RUINED_PORTAL_SWAMP);

      System.out.println("========== RUINED PORTAL LAYOUT over " + sample + " seeds ==========");
      Map<String, Integer> everywhere = new TreeMap<>();
      for (ResourceKey<Structure> key : variants) {
         Holder<Structure> portal;
         try {
            portal = reg.lookupOrThrow(Registries.STRUCTURE).getOrThrow(key);
         } catch (Exception missing) {
            System.out.println("  " + key.identifier().getPath() + " : not in this version");
            continue;
         }
         Map<String, Integer> seen = new TreeMap<>();
         int empty = 0;
         int pieces = 0;
         for (long seed = 1; seed <= sample; seed++) {
            RandomState full = ctx.fullRandomState(Dim.OVERWORLD, seed);
            List<Identifier> list = VillageLayout.pieceTemplates(
                  reg, ctx, seed, new ChunkPos(0, 0), portal, full);
            if (list.isEmpty()) {
               empty++;
               continue;
            }
            pieces += list.size();
            for (Identifier id : list) {
               String path = id.getPath();
               int slash = path.lastIndexOf('/');
               String name = slash < 0 ? path : path.substring(slash + 1);
               seen.merge(name, 1, Integer::sum);
               everywhere.merge(name, 1, Integer::sum);
            }
         }
         int built = sample - empty;
         System.out.println("  " + pad(key.identifier().getPath(), 24) + "assembled " + built + "/" + sample
               + "   avg pieces " + (built == 0 ? "n/a" : String.format("%.1f", pieces / (double) built))
               + "   distinct " + seen.size());
      }

      System.out.println("-- portal templates across ALL variants (rarest first) --");
      everywhere.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .forEach(e -> System.out.println("  " + pad(e.getKey(), 24) + e.getValue()));
      System.out.println("  (build the predicate token from THIS list — nothing else)");
      System.out.println("=====================================================================");
   }

   /**
    * The armorer token, checked against EVERY village biome rather than just plains.
    *
    * "Surface Village" in the picker spans all five village types, and each biome names its
    * buildings differently — plains has {@code plains_armorer_house_1}, desert has
    * {@code desert_armorer_1}. A token that only matched the plains spelling would look fine in
    * the plains-only histogram above and then silently never fire on a desert or savanna village,
    * which is the exact failure the token approach exists to avoid. So this prints the real
    * armorer template name PER BIOME and the token's hit rate against each.
    */
   private static void armorerCheck(RegistryAccess.Frozen reg, WorldgenContext ctx, int sample) {
      List<ResourceKey<Structure>> types = List.of(
            BuiltinStructures.VILLAGE_PLAINS, BuiltinStructures.VILLAGE_DESERT,
            BuiltinStructures.VILLAGE_SAVANNA, BuiltinStructures.VILLAGE_SNOWY,
            BuiltinStructures.VILLAGE_TAIGA);

      System.out.println("========== ARMORER TOKEN over " + sample + " seeds per village type ==========");
      System.out.println("  token: \"armorer\"");
      boolean allOk = true;
      for (ResourceKey<Structure> key : types) {
         Holder<Structure> village;
         try {
            village = reg.lookupOrThrow(Registries.STRUCTURE).getOrThrow(key);
         } catch (Exception missing) {
            System.out.println("  " + key.identifier().getPath() + " : not in this version");
            continue;
         }
         int hits = 0;
         int built = 0;
         Map<String, Integer> names = new TreeMap<>();
         for (long seed = 1; seed <= sample; seed++) {
            RandomState full = ctx.fullRandomState(Dim.OVERWORLD, seed);
            List<Identifier> pieces = VillageLayout.pieceTemplates(
                  reg, ctx, seed, new ChunkPos(0, 0), village, full);
            if (pieces.isEmpty()) {
               continue;
            }
            built++;
            if (VillageLayout.hasBuilding(pieces, "armorer")) {
               hits++;
            }
            for (Identifier id : pieces) {
               String path = id.getPath();
               if (path.contains("armorer")) {
                  names.merge(path.substring(path.lastIndexOf('/') + 1), 1, Integer::sum);
               }
            }
         }
         String seen = names.isEmpty() ? "(none seen)" : String.join(", ", names.keySet());
         System.out.println("  " + pad(key.identifier().getPath(), 18) + pad(hits + "/" + built, 10) + seen);
         if (hits == 0) {
            allOk = false;
         }
      }
      System.out.println(allOk
            ? "  OK — the token fires in every village type."
            : "  !!! the token NEVER fires for some village type — that search would silently fail there.");
      System.out.println("=====================================================================");
   }

   /**
    * Shipwreck: one TemplateStructurePiece per structure, like the ruined portal, and the thing
    * worth searching for is the ORIENTATION — a wreck sitting upright on the sea floor versus one
    * lying on its side or run aground on a beach.
    *
    * Two registry entries share the slots (beached and normal), so both are read here. The token
    * has to come out of this list: guessing "sideways" when the asset says something else gives a
    * predicate that silently never fires, which is the failure the token approach exists to stop.
    */
   private static void shipwreckCheck(RegistryAccess.Frozen reg, WorldgenContext ctx, int sample) {
      List<ResourceKey<Structure>> variants = List.of(
            BuiltinStructures.SHIPWRECK, BuiltinStructures.SHIPWRECK_BEACHED);

      System.out.println("========== SHIPWRECK LAYOUT over " + sample + " seeds ==========");
      Map<String, Integer> everywhere = new TreeMap<>();
      for (ResourceKey<Structure> key : variants) {
         Holder<Structure> wreck;
         try {
            wreck = reg.lookupOrThrow(Registries.STRUCTURE).getOrThrow(key);
         } catch (Exception missing) {
            System.out.println("  " + key.identifier().getPath() + " : not in this version");
            continue;
         }
         Map<String, Integer> seen = new TreeMap<>();
         int empty = 0;
         int pieces = 0;
         for (long seed = 1; seed <= sample; seed++) {
            RandomState full = ctx.fullRandomState(Dim.OVERWORLD, seed);
            List<Identifier> list = VillageLayout.pieceTemplates(
                  reg, ctx, seed, new ChunkPos(0, 0), wreck, full);
            if (list.isEmpty()) {
               empty++;
               continue;
            }
            pieces += list.size();
            for (Identifier id : list) {
               String path = id.getPath();
               int slash = path.lastIndexOf('/');
               String name = slash < 0 ? path : path.substring(slash + 1);
               seen.merge(name, 1, Integer::sum);
               everywhere.merge(name, 1, Integer::sum);
            }
         }
         int built = sample - empty;
         System.out.println("  " + pad(key.identifier().getPath(), 20) + "assembled " + built + "/" + sample
               + "   avg pieces " + (built == 0 ? "n/a" : String.format("%.1f", pieces / (double) built))
               + "   distinct " + seen.size());
      }

      System.out.println("-- shipwreck templates, RAREST FIRST (build the token from THIS) --");
      everywhere.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .forEach(e -> System.out.println("  " + pad(e.getKey(), 28) + e.getValue()));

      // Orientation lives in the NAME, so group by the suffix the names actually use rather than
      // by any orientation vocabulary invented here.
      System.out.println("-- grouped by name ending --");
      Map<String, Integer> byEnding = new TreeMap<>();
      everywhere.forEach((name, n) -> {
         int us = name.lastIndexOf('_');
         byEnding.merge(us < 0 ? name : name.substring(us + 1), n, Integer::sum);
      });
      byEnding.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .forEach(e -> System.out.println("  " + pad(e.getKey(), 28) + e.getValue()));
      System.out.println("=====================================================================");
   }

   // ── Phase 3: the shared derivation machinery ──────────────────────────────
   //
   // Ocean Ruins, Pillager Outpost and Trail Ruins all ask the same question the igloo asked —
   // "which template is in only SOME of them" — so they share one census instead of three
   // near-copies. Two things it does that the earlier per-structure checks each re-invented:
   //
   //   1. counts STRUCTURES CONTAINING a template, not piece occurrences. An ocean ruin cluster
   //      repeats the same small ruin several times and a trail-ruins road repeats road_section_1
   //      a dozen times; counting pieces would rank the repeats above the thing worth searching for.
   //   2. keeps the FULL path ("trail_ruins/tower/one_room_1"), never the leaf name. Trail Ruins
   //      has one_room_N under BOTH buildings/ and tower/, and hall_N under tower/ while
   //      group_hall_N is under buildings/ — a leaf-name token would quietly match the wrong folder.
   //      This is the same trap "beached" was: a name that reads like the thing you want and isn't.

   /** One structure assembled across many seeds: every piece list, plus who-contains-what. */
   private record Census(int built, int empty, int totalPieces,
                         Map<String, Integer> containing, List<List<Identifier>> all) {}

   private static Census census(RegistryAccess.Frozen reg, WorldgenContext ctx,
                                Holder<Structure> structure, int sample) {
      Map<String, Integer> containing = new TreeMap<>();
      List<List<Identifier>> all = new java.util.ArrayList<>();
      int empty = 0;
      int totalPieces = 0;
      for (long seed = 1; seed <= sample; seed++) {
         RandomState full = ctx.fullRandomState(Dim.OVERWORLD, seed);
         List<Identifier> pieces = VillageLayout.pieceTemplates(
               reg, ctx, seed, new ChunkPos(0, 0), structure, full);
         all.add(pieces);
         if (pieces.isEmpty()) {
            empty++;
            continue;
         }
         totalPieces += pieces.size();
         pieces.stream().map(Identifier::getPath).distinct()
               .forEach(path -> containing.merge(path, 1, Integer::sum));
      }
      return new Census(sample - empty, empty, totalPieces, containing, all);
   }

   /** Merge several variants' censuses so the rarest-first list spans the whole picker row. */
   private static void mergeInto(Map<String, Integer> into, Census c) {
      c.containing().forEach((k, v) -> into.merge(k, v, Integer::sum));
   }

   /**
    * The derivation itself: templates ordered rarest first, with the ones present in EVERY
    * structure called out as useless. A predicate is only worth shipping if its template is in
    * the "some only" group — that is the whole point of searching for it.
    */
   private static void printRarest(Map<String, Integer> containing, int built, int limit) {
      System.out.println("-- templates by STRUCTURES CONTAINING (rarest first) --");
      containing.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .limit(limit)
            .forEach(e -> System.out.println("  " + pad(e.getKey(), 40) + e.getValue() + " / " + built
                  + (e.getValue() < built ? "   <- in some only" : "   <- in EVERY one, useless as a search")));
   }

   /** Hit rate of candidate tokens, reusing the piece lists the census already built. */
   private static void spotCheck(List<List<Identifier>> all, int built, List<String> tokens) {
      System.out.println("-- token spot-check (reusing the lists above) --");
      for (String token : tokens) {
         int hits = 0;
         for (List<Identifier> pieces : all) {
            if (!pieces.isEmpty() && VillageLayout.hasBuilding(pieces, token)) {
               hits++;
            }
         }
         System.out.println("  " + pad(token, 30) + hits + " / " + built
               + (hits == 0 ? "   <- NEVER FIRES — do not ship this token" : "")
               + (hits == built && built > 0 ? "   <- fires on all — not a search" : ""));
      }
   }

   /**
    * Confirms a token against the LIVE data of this version rather than against 40 lucky rolls.
    *
    * Two questions, and both have burned this project before:
    *   - WHICH TEMPLATE FILES does the token match? {@code listTemplates()} is every .nbt the
    *     running data packs expose, so a token matching zero files is a search that can never
    *     fire ("tool_smith"), and a token matching more files than intended is a search that
    *     quietly answers a different question ("cage" also catching the allay cage).
    *   - Are those files REACHABLE? For a jigsaw structure a template only assembles if some
    *     template pool lists it, so the pool registry is scanned too. A file that exists in no
    *     pool is dead weight and must not be counted as evidence.
    * Non-jigsaw structures (ocean ruins) have no pools; their pieces are chosen in code, so the
    * file list is the whole answer there and the pool line is expected to be empty.
    */
   private static void confirmTokenLive(String token, StructureTemplateManager manager,
                                        RegistryAccess.Frozen reg, String folderPrefix) {
      String want = token.toLowerCase(java.util.Locale.ROOT);
      List<String> files = manager.listTemplates()
            .map(Identifier::getPath)
            .filter(p -> com.fablevision.client.seedfinder.VillageLayout.tokenMatches(p, want))
            .sorted()
            .toList();
      System.out.println("  token \"" + token + "\"");
      System.out.println("    template files matching : " + files.size()
            + (files.isEmpty() ? "   !!! MATCHES NOTHING — this predicate can never fire" : ""));
      files.forEach(f -> System.out.println("       " + f
            + (f.startsWith(folderPrefix) ? "" : "   <- OUTSIDE " + folderPrefix + ", token leaks")));

      // Which pools can actually place them. Empty for code-placed structures, by design.
      Map<String, List<String>> pools = new TreeMap<>();
      reg.lookupOrThrow(Registries.TEMPLATE_POOL).listElements().forEach(poolRef -> {
         for (var pair : poolRef.value().getTemplates()) {
            if (pair.getFirst() instanceof net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement single) {
               String path = single.getTemplateLocation().getPath();
               if (com.fablevision.client.seedfinder.VillageLayout.tokenMatches(path, want)) {
                  pools.computeIfAbsent(poolRef.key().identifier().getPath(), k -> new java.util.ArrayList<>())
                        .add(path);
               }
            }
         }
      });
      System.out.println("    live pools referencing  : " + (pools.isEmpty()
            ? "(none — code-placed structure, file list above is the answer)" : pools.size()));
      pools.forEach((pool, paths) -> System.out.println("       " + pad(pool, 40) + paths.size() + " element(s)"));
   }

   /** The token the picker actually ships, read back out of the catalog so the two cannot drift.
    *  Null both when the row is missing and when it ships no token — most rows do not, and asking
    *  is how the deep checks tell an existence row from an ordinary one. */
   private static String catalogToken(WorldgenContext ctx, String label) {
      for (com.fablevision.client.seedfinder.SeedCriteria.StructureTarget t
            : com.fablevision.client.seedfinder.SeedCatalog.structures(ctx)) {
         if (label.equals(t.label)) {
            return t.building;
         }
      }
      return null;
   }

   /** Prints the catalog row's token and fails loudly if it is missing or never fires. */
   private static void checkCatalogRow(WorldgenContext ctx, String label, List<List<Identifier>> all,
                                       int built, boolean expectToken) {
      List<com.fablevision.client.seedfinder.SeedCriteria.StructureTarget> rows =
            com.fablevision.client.seedfinder.SeedCatalog.structures(ctx).stream()
                  .filter(t -> label.equals(t.label))
                  .toList();
      if (rows.isEmpty()) {
         System.out.println("  FAILED: the picker has no \"" + label + "\" row.");
         return;
      }
      String token = rows.get(0).building;
      if (!expectToken) {
         System.out.println("  " + pad(label, 26) + "row present, no layout token"
               + " (this row is answered by the structure id alone)");
         return;
      }
      if (token == null) {
         System.out.println("  FAILED: \"" + label + "\" ships no token but one was expected.");
         return;
      }
      int hits = 0;
      for (List<Identifier> pieces : all) {
         if (!pieces.isEmpty() && VillageLayout.hasBuilding(pieces, token)) {
            hits++;
         }
      }
      System.out.println("  " + pad(label, 26) + pad("token \"" + token + "\"", 34)
            + hits + " / " + built
            + (hits == 0 ? "   !!! NEVER FIRES — the search would silently return nothing"
                  : hits == built ? "   !!! fires on every one — not a search" : "   OK"));
   }

   /**
    * Ocean Ruins: TWO registry entries sharing one structure set, warm and cold, picked by biome —
    * the same shape as the shipwreck row, and the same trap in a different costume.
    *
    * The trap: the folder is {@code underwater_ruin}, not "ocean_ruin", and the COLD ruins are
    * named brick_/cracked_/mossy_ — the word "cold" appears in NO template. A "cold" token would
    * be another tool_smith. Warm/cold is therefore answered by the STRUCTURE ID (which the finder's
    * confirm already enforces via the wanted-set), and only big-vs-small is a template question.
    *
    * A ruin is a CLUSTER — one big building plus a scatter of small ones — so "contains a big_
    * piece" is a real per-ruin coin-flip and the counts below are what decide whether it is worth
    * offering.
    */
   private static void oceanRuinCheck(RegistryAccess.Frozen reg, WorldgenContext ctx, int sample,
                                      StructureTemplateManager manager) {
      System.out.println("========== OCEAN RUINS LAYOUT over " + sample + " seeds ==========");
      Map<String, Integer> everywhere = new TreeMap<>();
      List<List<Identifier>> allWarm = List.of();
      List<List<Identifier>> allCold = List.of();
      List<List<Identifier>> both = new java.util.ArrayList<>();
      int builtWarm = 0;
      int builtCold = 0;

      for (ResourceKey<Structure> key : List.of(BuiltinStructures.OCEAN_RUIN_WARM,
            BuiltinStructures.OCEAN_RUIN_COLD)) {
         Holder<Structure> ruin;
         try {
            ruin = reg.lookupOrThrow(Registries.STRUCTURE).getOrThrow(key);
         } catch (Exception missing) {
            System.out.println("  " + key.identifier().getPath() + " : not in this version");
            continue;
         }
         Census c = census(reg, ctx, ruin, sample);
         mergeInto(everywhere, c);
         both.addAll(c.all());
         if (key == BuiltinStructures.OCEAN_RUIN_WARM) {
            allWarm = c.all();
            builtWarm = c.built();
         } else {
            allCold = c.all();
            builtCold = c.built();
         }
         System.out.println("  " + pad(key.identifier().getPath(), 20) + "assembled " + c.built() + "/" + sample
               + "   avg pieces " + (c.built() == 0 ? "n/a"
                     : String.format("%.1f", c.totalPieces() / (double) c.built()))
               + "   distinct " + c.containing().size());
      }

      printRarest(everywhere, builtWarm + builtCold, 40);
      System.out.println("-- WARM ruins only --");
      spotCheck(allWarm, builtWarm, List.of("warm", "big_", "big_warm", "brick", "cracked", "mossy", "cold"));
      System.out.println("-- COLD ruins only --");
      spotCheck(allCold, builtCold, List.of("warm", "big_", "big_brick", "brick", "cracked", "mossy", "cold"));

      // Both tokens, side by side, because the difference between them is the whole lesson: the
      // bare one matches the right ruins AND a village house AND a bastion piece, and only the
      // folder-qualified one says what it means.
      System.out.println("-- live-data confirmation --");
      confirmTokenLive("big_", manager, reg, "underwater_ruin/");
      confirmTokenLive("underwater_ruin/big_", manager, reg, "underwater_ruin/");
      System.out.println("-- catalog rows in use --");
      checkCatalogRow(ctx, "Warm Ocean Ruins", allWarm, builtWarm, false);
      checkCatalogRow(ctx, "Cold Ocean Ruins", allCold, builtCold, false);
      checkCatalogRow(ctx, "Large Ocean Ruin", both, builtWarm + builtCold, true);
      System.out.println("  LOOT: what is in a ruin's chest is rolled when the chest generates,"
            + " long after assembly — NOT verified here and never claimed.");
      System.out.println("=====================================================================");
   }

   /**
    * Pillager Outpost: a jigsaw structure, so its pieces come back through the SinglePoolElement
    * path (like villages) rather than the template path (like igloos).
    *
    * One tower is always there; the interest is the optional side pieces the jigsaw hangs around
    * it — cages, tents, targets, log piles. Those are what "which optional pieces assembled" means,
    * and which ones exist is derived below, not assumed.
    *
    * "cage" is the trap here: there is more than one cage template and one of them holds allays,
    * so a bare "cage" token silently answers a WIDER question than a player picking "with cages"
    * expects. Both are printed so the shipped token is a choice and not an accident.
    */
   private static void outpostCheck(RegistryAccess.Frozen reg, WorldgenContext ctx, int sample,
                                    StructureTemplateManager manager) {
      Holder<Structure> outpost;
      try {
         outpost = reg.lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.PILLAGER_OUTPOST);
      } catch (Exception missing) {
         System.out.println("PILLAGER OUTPOST : not in this version");
         return;
      }
      Census c = census(reg, ctx, outpost, sample);
      System.out.println("========== PILLAGER OUTPOST LAYOUT over " + sample + " seeds ==========");
      System.out.println("  assembled " + c.built() + "/" + sample + "   avg pieces "
            + (c.built() == 0 ? "n/a" : String.format("%.1f", c.totalPieces() / (double) c.built()))
            + "   distinct " + c.containing().size());
      printRarest(c.containing(), c.built(), 40);
      spotCheck(c.all(), c.built(), List.of("pillager_outpost/feature_cage",
            "pillager_outpost/feature_cage_with_allays", "pillager_outpost/feature_cage1",
            "pillager_outpost/feature_cage2", "pillager_outpost/feature_tent",
            "pillager_outpost/feature_targets", "pillager_outpost/feature_logs",
            "pillager_outpost/feature_plate", "watchtower"));
      System.out.println("-- live-data confirmation --");
      confirmTokenLive("pillager_outpost/feature_cage", manager, reg, "pillager_outpost/");
      System.out.println("-- catalog rows in use --");
      checkCatalogRow(ctx, "Outpost with Cages", c.all(), c.built(), true);
      checkCatalogRow(ctx, "Outpost with Allay Cage", c.all(), c.built(), true);
      System.out.println("  LOOT: the tower chest's contents are rolled later — NOT verified here.");
      // Known and deliberate: the watchtower reads 0/40 above and is NOT missing from the game.
      // Its pool entry is a list_pool_element (watchtower + watchtower_overgrown as one choice),
      // and templateIds() only unwraps SinglePoolElement, so it never reaches the piece list. It
      // costs nothing here because that pool has exactly one entry — every outpost has the tower,
      // which makes it useless as a search either way. Left alone rather than widening the shared
      // piece reader, which every other structure also depends on.
      System.out.println("  NOTE: watchtower is a list_pool_element, so it is invisible to the piece"
            + " reader. Harmless — it is unconditional, so no search could use it.");
      System.out.println("=====================================================================");
   }

   /**
    * Trail Ruins: a jigsaw structure that buries a small settlement, and the one where leaf names
    * are actively dangerous — {@code one_room_N} exists under BOTH buildings/ and tower/, and
    * {@code hall_N} under tower/ is a different building from {@code group_hall_N} under buildings/.
    * Every token here is therefore folder-qualified, exactly like the igloo's "igloo/bottom".
    *
    * The structure always lays roads and decor, so those are the useless-as-a-search group; the
    * building groups it picks are what varies per seed, and those are the predicates.
    */
   private static void trailRuinsCheck(RegistryAccess.Frozen reg, WorldgenContext ctx, int sample,
                                       StructureTemplateManager manager) {
      Holder<Structure> ruins;
      try {
         ruins = reg.lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.TRAIL_RUINS);
      } catch (Exception missing) {
         System.out.println("TRAIL RUINS : not in this version");
         return;
      }
      Census c = census(reg, ctx, ruins, sample);
      System.out.println("========== TRAIL RUINS LAYOUT over " + sample + " seeds ==========");
      System.out.println("  assembled " + c.built() + "/" + sample + "   avg pieces "
            + (c.built() == 0 ? "n/a" : String.format("%.1f", c.totalPieces() / (double) c.built()))
            + "   distinct " + c.containing().size());
      printRarest(c.containing(), c.built(), 60);

      // Grouped by FOLDER + family, because the useful predicate is "it built a tower" rather than
      // "it built tower_3" — the numbered variants are interchangeable to a player standing there.
      System.out.println("-- grouped by folder/family (structures containing) --");
      Map<String, Integer> families = new TreeMap<>();
      for (List<Identifier> pieces : c.all()) {
         pieces.stream().map(id -> {
            String p = id.getPath();
            int slash = p.lastIndexOf('/');
            String leaf = slash < 0 ? p : p.substring(slash + 1);
            String folder = slash < 0 ? "" : p.substring(0, slash + 1);
            return folder + leaf.replaceAll("_\\d+$", "");
         }).distinct().forEach(f -> families.merge(f, 1, Integer::sum));
      }
      families.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .forEach(e -> System.out.println("  " + pad(e.getKey(), 40) + e.getValue() + " / " + c.built()
                  + (e.getValue() < c.built() ? "   <- in some only" : "   <- in EVERY one")));

      // "trail_ruins/tower/tower_" and the two bare leaf names are in here as NEGATIVE controls:
      // the first is the obvious-sounding row that turns out to be in every trail ruins, and the
      // last two show what a leaf-name token silently sweeps up across folders.
      spotCheck(c.all(), c.built(), List.of("trail_ruins/buildings/group_",
            "trail_ruins/buildings/group_full", "trail_ruins/buildings/group_hall",
            "trail_ruins/buildings/group_lower", "trail_ruins/buildings/group_room",
            "trail_ruins/buildings/group_upper", "trail_ruins/tower/stable",
            "trail_ruins/tower/large_hall", "trail_ruins/buildings/large_room",
            "trail_ruins/tower/tower_", "one_room", "hall"));
      System.out.println("-- live-data confirmation --");
      confirmTokenLive("trail_ruins/buildings/group_", manager, reg, "trail_ruins/");
      confirmTokenLive("trail_ruins/tower/stable", manager, reg, "trail_ruins/");
      System.out.println("-- catalog rows in use --");
      checkCatalogRow(ctx, "Trail Ruins with Building Group", c.all(), c.built(), true);
      checkCatalogRow(ctx, "Trail Ruins with Stables", c.all(), c.built(), true);
      System.out.println("  LOOT: suspicious gravel and its dig-out items are rolled later —"
            + " NOT verified here.");
      System.out.println("=====================================================================");
   }

   // ── Phase 4: the deep structures, and the first ones that are not in the overworld ────────
   //
   // Trial Chamber, Ancient City, End City and Bastion Remnant ask the same "which template is in
   // only SOME of them" question as everything before them, with two differences that make the
   // chunk-0,0 census above unusable:
   //
   //   1. THEY ARE NOT ALL IN THE OVERWORLD. A bastion assembled against overworld noise between
   //      y=-64 and 320 is not a slightly-off answer, it is an answer about a world that does not
   //      exist. Assembly is now told its dimension and picks the noise settings, biome source,
   //      level key and build limits from it.
   //   2. CHUNK 0,0 IS NOT A FAIR SAMPLE FOR THEM. Every overworld seed has terrain at 0,0, so the
   //      hypothetical "if one started here" always had an answer. An End City needs an outer
   //      island with ground at y>=60, and chunk 0,0 in the End is the centre island — a census
   //      there returns nothing on every seed and looks exactly like broken assembly.
   //
   // So these run the REAL funnel ({@link Placed}, which is stage0 + confirm from the shipped
   // code) to find a structure that actually generates near the dimension's origin, and assemble
   // that one. Slower per seed, and the only honest way to ask the question.

   /**
    * One deep structure to derive tokens for.
    *
    * {@code probes} are candidate tokens under test — including deliberately obvious-looking ones,
    * because every structure so far had a naming trap and the only way to find it is to let live
    * data refute the guess ("cold" in ocean ruins, "tool_smith" in villages, and here "generic",
    * which is what everyone calls the fourth bastion type and which appears in no template).
    * {@code live} are the tokens actually shipped, confirmed against the template files and the
    * pool registry. {@code rows} are the catalog labels whose tokens are read back and re-tested.
    */
   private record Deep(String label, Dim dim, int reach, String folder, List<String> probes,
                       List<String> live, List<String> rows) {}

   private static void deepChecks(RegistryAccess.Frozen reg, WorldgenContext ctx, int sample,
                                  StructureTemplateManager manager) {
      List<Deep> deeps = List.of(
            // Overworld but underground; reach is wide because both are sparsely placed.
            new Deep("Trial Chambers", Dim.OVERWORLD, 3000, "trial_chambers/",
                  List.of("chamber", "corridor", "hallway", "atrium", "vault", "ominous",
                        "reward", "spawner", "entrance", "end", "intersection", "slanted"),
                  List.of("trial_chambers/chamber/eruption", "trial_chambers/chamber/slanted",
                        "trial_chambers/hallway/encounter"),
                  List.of("Trial Chamber with Eruption Room", "Trial Chamber with Slanted Room",
                        "Trial Chamber with Encounter Hall")),
            new Deep("Ancient City", Dim.OVERWORLD, 3000, "ancient_city/",
                  List.of("city_center", "ancient_city/structures/", "barracks", "camp",
                        "ice_box", "sculk", "walls", "tall_ruin", "small_ruin", "medium_ruin"),
                  List.of("ancient_city/structures/sauna"),
                  List.of("Ancient City with Sauna")),
            // Nether: measured from the portal-in point (spawn/8), not from overworld spawn.
            new Deep("Bastion Remnant", Dim.NETHER, 400, "bastion/",
                  List.of("bastion/treasure", "bastion/bridge", "bastion/hoglin_stable",
                        "bastion/units", "bastion/generic", "generic", "treasure", "stable"),
                  List.of("bastion/treasure/", "bastion/bridge/", "bastion/hoglin_stable/",
                        "bastion/units/"),
                  List.of("Treasure Bastion", "Bridge Bastion", "Hoglin Stable Bastion",
                        "Housing Units Bastion")),
            // A single buried piece, so there is nothing to search WITHIN it — it is here only to
            // derive the token that proves one is really there, which the honesty table below says
            // it needs (half its confirmed slots have no fossil).
            // "fossil/" is kept as a probe on purpose and must stay: it is the one wrong token in
            // this project that matches SIXTEEN real template files and is still about a different
            // structure entirely (the overworld fossil feature). The live-confirmation block prints
            // both so the difference is on the page rather than in someone's memory.
            new Deep("Nether Fossil", Dim.NETHER, 400, "nether_fossils/",
                  List.of("nether_fossils/fossil", "fossil/", "fossil/spine", "fossil/skull",
                        "nether_fossil", "bone"),
                  List.of("nether_fossils/fossil", "fossil/"),
                  List.of()));
            // The End City census was here and went with the dimension in 1.41.4 — SeedCriteria.Dim.

      for (Deep d : deeps) {
         deepCheck(reg, ctx, sample, manager, d);
      }
      placementHonesty(reg, ctx, sample);
   }

   /**
    * The question re-enabling the Nether and the End forced: when placement and biome BOTH say
    * yes, does the structure actually generate there?
    *
    * THIS TABLE WAS RIGHT AND ITS CONCLUSION WAS WRONG, which is worth leaving on the page. It
    * measured that three quarters of confirmed End City slots had no island high enough — that a
    * plain "End City" row would send people to empty sky — and the response was to give the row a
    * piece token, turning the deferred layout gate into an existence proof. In play, one still got
    * through. An existence proof built out of "the structure has this piece" only holds while the
    * assembly can fail for the same reasons the world does, and here it could not.
    *
    * The End was removed in 1.41.4 rather than patched again (SeedCriteria.Dim). What the table is
    * for now is the remaining rows: it is the check that says a Nether or Overworld row really can
    * be reported off {@link SeedCriteria.StructureTarget#confirm} alone, and it should be run
    * before any row is ever added for a structure whose generation has conditions of its own.
    *
    * A Nether Fortress reads 0 here and is NOT broken: it is built from code, not templates
    * (like stronghold corridors), so it has no piece list to assemble and the number is
    * meaningless for it. The column says so rather than leaving a scary zero.
    */
   private static void placementHonesty(RegistryAccess.Frozen reg, WorldgenContext ctx, int sample) {
      record Row(String label, Dim dim, int reach, boolean codeBuilt) {}
      List<Row> rows = List.of(
            new Row("Nether Fortress", Dim.NETHER, 400, true),
            new Row("Bastion Remnant", Dim.NETHER, 400, false),
            new Row("Nether Fossil", Dim.NETHER, 400, false),
            new Row("Ancient City", Dim.OVERWORLD, 3000, false),
            new Row("Trial Chambers", Dim.OVERWORLD, 3000, false));

      System.out.println("========== DOES A CONFIRMED SLOT REALLY HAVE ONE? over " + sample + " seeds ==========");
      System.out.println("  " + pad("ROW", 20) + pad("SLOTS CONFIRMED", 18) + pad("OF THOSE, ASSEMBLED", 22)
            + "VERDICT");
      for (Row r : rows) {
         com.fablevision.client.seedfinder.SeedCriteria.StructureTarget target =
               com.fablevision.client.seedfinder.SeedCatalog.structures(ctx).stream()
                     .filter(t -> r.label().equals(t.label))
                     .findFirst()
                     .orElse(null);
         if (target == null) {
            System.out.println("  " + pad(r.label(), 20) + "no such catalog row");
            continue;
         }
         target = target.withRadius(r.reach());
         int slots = 0;
         int assembled = 0;
         for (long seed = 1; seed <= sample; seed++) {
            int perSeed = 0;
            for (com.fablevision.client.seedfinder.Placed.Spot spot
                  : com.fablevision.client.seedfinder.Placed.near(ctx, target, seed, r.dim(), r.reach())) {
               // Capped per seed: the End at reach 4000 offers hundreds of slots and assembling
               // every one of them costs minutes for a ratio the nearest handful already shows.
               // These are the slots a player would actually be sent to anyway — the near ones.
               if (perSeed++ >= 6) {
                  break;
               }
               slots++;
               RandomState full = ctx.fullRandomState(r.dim(), seed);
               if (!VillageLayout.pieceTemplates(reg, ctx, seed, spot.chunk(), spot.winner(),
                     full, r.dim()).isEmpty()) {
                  assembled++;
               }
            }
         }
         String verdict = r.codeBuilt()
               ? "code-built — no templates, number means nothing"
               : slots == 0 ? "no slots in reach"
                     : assembled == slots ? "OK — confirm is enough"
                           : "!!! confirm is NOT enough — needs an existence token";
         System.out.println("  " + pad(r.label(), 20) + pad(String.valueOf(slots), 18)
               + pad(assembled + "  (" + (slots == 0 ? 0 : 100 * assembled / slots) + "%)", 22) + verdict);
      }
      System.out.println("=====================================================================");
   }

   private static void deepCheck(RegistryAccess.Frozen reg, WorldgenContext ctx, int sample,
                                 StructureTemplateManager manager, Deep d) {
      System.out.println("========== " + d.label().toUpperCase(java.util.Locale.ROOT)
            + " LAYOUT over " + sample + " seeds (" + d.dim() + ") ==========");
      com.fablevision.client.seedfinder.SeedCriteria.StructureTarget target =
            com.fablevision.client.seedfinder.SeedCatalog.structures(ctx).stream()
                  .filter(t -> d.label().equals(t.label))
                  .findFirst()
                  .orElse(null);
      if (target == null) {
         System.out.println("  FAILED: the picker has no \"" + d.label() + "\" row to derive from.");
         System.out.println("=====================================================================");
         return;
      }
      target = target.withRadius(d.reach());

      Map<String, Integer> containing = new TreeMap<>();
      List<List<Identifier>> all = new java.util.ArrayList<>();
      int noneNearby = 0;
      int deadSlots = 0;
      int totalPieces = 0;
      Long firstSeed = null;
      List<Identifier> firstPieces = List.of();
      String firstWhere = "";

      for (long seed = 1; seed <= sample; seed++) {
         // Walk the confirmed candidates nearest-first until one actually assembles, exactly as
         // the shipped funnel does when a layout token is required. Taking only the nearest would
         // sample End Cities four times more rarely than they exist — three quarters of confirmed
         // End slots have no island high enough, and stopping at the first would read as broken
         // assembly rather than as vanilla saying no.
         List<Identifier> pieces = List.of();
         com.fablevision.client.seedfinder.Placed.Spot used = null;
         RandomState full = ctx.fullRandomState(d.dim(), seed);
         for (com.fablevision.client.seedfinder.Placed.Spot spot
               : com.fablevision.client.seedfinder.Placed.near(ctx, target, seed, d.dim(), d.reach())) {
            List<Identifier> got = VillageLayout.pieceTemplates(
                  reg, ctx, seed, spot.chunk(), spot.winner(), full, d.dim());
            if (got.isEmpty()) {
               deadSlots++;
               continue;
            }
            pieces = got;
            used = spot;
            break;
         }
         if (used == null) {
            noneNearby++;
            continue;
         }
         all.add(pieces);
         totalPieces += pieces.size();
         pieces.stream().map(Identifier::getPath).distinct()
               .forEach(path -> containing.merge(path, 1, Integer::sum));
         if (firstSeed == null) {
            firstSeed = seed;
            firstPieces = pieces;
            firstWhere = used.pos().getX() + ", " + used.pos().getZ() + " (" + used.dist() + " blocks)";
         }
      }

      int built = all.size();
      System.out.println("  seeds with a real one    : " + built + " / " + sample
            + "   (reach " + d.reach() + " from the " + d.dim() + " origin)");
      System.out.println("  confirmed slots skipped  : " + deadSlots
            + "   (placement + biome said yes, the structure itself said no)");
      System.out.println("  average pieces           : "
            + (built == 0 ? "n/a" : String.format("%.1f", totalPieces / (double) built)));
      System.out.println("  distinct templates       : " + containing.size());
      if (built == 0) {
         System.out.println("  NOTHING TO DERIVE FROM — fix assembly before reading anything below.");
         System.out.println("=====================================================================");
         return;
      }
      System.out.println("-- sample, seed " + firstSeed + " @ " + firstWhere
            + " (" + firstPieces.size() + " pieces) --");
      firstPieces.stream().limit(14).forEach(p -> System.out.println("      " + p));
      if (firstPieces.size() > 14) {
         System.out.println("      … " + (firstPieces.size() - 14) + " more");
      }

      printRarest(containing, built, 60);

      // Grouped by FOLDER + family, because a player cares that it built "a stable", not that it
      // built "stable_3" — the numbered variants are interchangeable standing in front of them.
      System.out.println("-- grouped by folder/family (structures containing) --");
      Map<String, Integer> families = new TreeMap<>();
      for (List<Identifier> pieces : all) {
         pieces.stream().map(id -> {
            String p = id.getPath();
            int slash = p.lastIndexOf('/');
            String leaf = slash < 0 ? p : p.substring(slash + 1);
            String folder = slash < 0 ? "" : p.substring(0, slash + 1);
            return folder + leaf.replaceAll("_?\\d+$", "");
         }).distinct().forEach(f -> families.merge(f, 1, Integer::sum));
      }
      families.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .forEach(e -> System.out.println("  " + pad(e.getKey(), 46) + e.getValue() + " / " + built
                  + (e.getValue() < built ? "   <- in some only" : "   <- in EVERY one")));

      // Top-level folders: for a structure with a TYPE (bastion) this line IS the answer, because
      // the type is the folder every piece of that structure comes out of.
      System.out.println("-- top-level folder under " + d.folder() + " (structures containing) --");
      Map<String, Integer> tops = new TreeMap<>();
      for (List<Identifier> pieces : all) {
         pieces.stream()
               .map(Identifier::getPath)
               .filter(p -> p.startsWith(d.folder()))
               .map(p -> {
                  String rest = p.substring(d.folder().length());
                  int slash = rest.indexOf('/');
                  return slash < 0 ? "(no subfolder)" : d.folder() + rest.substring(0, slash) + "/";
               })
               .distinct()
               .forEach(f -> tops.merge(f, 1, Integer::sum));
      }
      tops.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .forEach(e -> System.out.println("  " + pad(e.getKey(), 46) + e.getValue() + " / " + built
                  + (e.getValue() < built ? "   <- in some only" : "   <- in EVERY one")));

      spotCheck(all, built, d.probes());
      System.out.println("-- live-data confirmation (files this version ships + pools that can place them) --");
      for (String token : d.live()) {
         confirmTokenLive(token, manager, reg, d.folder());
      }
      System.out.println("-- catalog rows in use --");
      // The PLAIN row carries a token only when confirm alone cannot prove the structure is there.
      // Its presence is the signal, so no separate list is needed: if the row has one, it is an
      // existence token and is checked as one.
      if (catalogToken(ctx, d.label()) != null) {
         checkExistenceRow(ctx, d.label(), all, built);
      }
      for (String row : d.rows()) {
         checkCatalogRow(ctx, row, all, built, true);
      }
      System.out.println("=====================================================================");
   }

   /**
    * The other kind of catalog token, and the only place "fires on every one" is the PASS.
    *
    * A search token has to split the population or it is not a search. An EXISTENCE token is the
    * opposite by design: it names a piece every one of these structures has, so that demanding it
    * turns the deferred layout gate into proof that the structure is really there. Two rows need
    * one — End City and Nether Fossil — because for them placement and biome both saying yes is
    * not enough, and the honesty table below measures exactly how far short it falls.
    */
   private static void checkExistenceRow(WorldgenContext ctx, String label,
                                         List<List<Identifier>> all, int built) {
      String token = catalogToken(ctx, label);
      if (token == null) {
         System.out.println("  FAILED: \"" + label + "\" ships no existence token, so the row can"
               + " report coordinates where nothing generates.");
         return;
      }
      int hits = 0;
      for (List<Identifier> pieces : all) {
         if (!pieces.isEmpty() && VillageLayout.hasBuilding(pieces, token)) {
            hits++;
         }
      }
      System.out.println("  " + pad(label, 26) + pad("existence token \"" + token + "\"", 40)
            + hits + " / " + built
            + (hits == built && built > 0
                  ? "   OK — in every one, which is the point"
                  : "   !!! MISSES " + (built - hits) + " — a real one would be reported as absent"));
   }

   /** printf is swallowed by the log wrapper this runs under; plain println with manual
    *  padding is the only formatting that survives. */
   private static String pad(String s, int width) {
      StringBuilder sb = new StringBuilder(s);
      while (sb.length() < width) {
         sb.append(' ');
      }
      return sb.toString();
   }

   private VillageDiagnostic() {
   }
}
