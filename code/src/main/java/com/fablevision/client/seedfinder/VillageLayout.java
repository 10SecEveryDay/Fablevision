package com.fablevision.client.seedfinder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.fablevision.client.FableVisionClient;

import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.LevelStorageSource;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalPiece;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Reads WHICH BUILDINGS a village assembles for a seed — the full jigsaw piece list, not just the
 * town-centre start piece the abandoned-village check already reads.
 *
 * How, and why this is safe:
 *   Jigsaw assembly is pure bookkeeping. {@code Structure.generate} walks the template pools,
 *   picks pieces, and records their bounding boxes — it does not write a single block. So unlike
 *   the stronghold eye counter (which must actually PLACE blocks and therefore needs
 *   a stubbed level to read blocks through, where reading air caused RNG drift), this cannot
 *   diverge from the real
 *   world by reading a stubbed level. There is no level involved.
 *
 * What it is NOT:
 *   Chest CONTENTS are rolled when the container generates, long after assembly, and are not
 *   visible here. A building's PRESENCE is verifiable; its loot is not. Never claim otherwise.
 *
 * Cost:
 *   This is the expensive check — a full noise state plus template loading — so it must only ever
 *   run on candidates that already passed placement and biome, exactly like the eye counter.
 */
public final class VillageLayout {

   private VillageLayout() {
   }

   /**
    * The build limits assembly is allowed to place pieces between — and they are NOT the same in
    * every dimension. The overworld is -64..320, the Nether 0..128, the End 0..256, and handing a
    * Nether structure the overworld's accessor lets the jigsaw hang pieces above the bedrock roof,
    * where no bastion or fortress can actually be.
    *
    * Read from the live DIMENSION_TYPE registry rather than typed here, for the same reason every
    * token in this project is derived: the numbers are data this version ships, not constants this
    * code is entitled to remember. The fallbacks below are only for the case where the registry is
    * absent (a stripped datapack set), and they are the vanilla values.
    */
   private static LevelHeightAccessor heightFor(HolderLookup.Provider source, Dim dim) {
      ResourceKey<DimensionType> key = switch (dim) {
         case NETHER -> BuiltinDimensionTypes.NETHER;
         default -> BuiltinDimensionTypes.OVERWORLD;
      };
      int minY;
      int height;
      try {
         DimensionType type = source.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(key).value();
         minY = type.minY();
         height = type.height();
      } catch (Throwable missing) {
         minY = dim == Dim.OVERWORLD ? -64 : 0;
         height = dim == Dim.OVERWORLD ? 384 : 128;
      }
      final int y = minY;
      final int h = height;
      return new LevelHeightAccessor() {
         public int getHeight() {
            return h;
         }

         public int getMinY() {
            return y;
         }
      };
   }

   /** The noise settings whose terrain the pieces are placed against. */
   private static ResourceKey<NoiseGeneratorSettings> noiseFor(Dim dim) {
      return switch (dim) {
         case NETHER -> NoiseGeneratorSettings.NETHER;
         default -> NoiseGeneratorSettings.OVERWORLD;
      };
   }

   /** Which world the structure thinks it is generating in. Vanilla passes this into the
    *  generation context, so a Nether structure told "overworld" is answering a different
    *  question from the one the game asks. */
   private static ResourceKey<Level> levelFor(Dim dim) {
      return switch (dim) {
         case NETHER -> Level.NETHER;
         default -> Level.OVERWORLD;
      };
   }

   /**
    * Templates come from the running singleplayer server. Assembly needs real .nbt sizes and
    * jigsaw blocks, so there is no way to answer a building question without them.
    *
    * Overridable because the headless diagnostic has no game to ask — it builds its own manager
    * over a temp world folder and injects it here.
    */
   private static volatile StructureTemplateManager override;
   private static volatile StructureTemplateManager clientBuilt;
   private static volatile boolean clientBuildFailed;
   private static volatile long buildMs = -1;

   public static void setTemplateManager(StructureTemplateManager manager) {
      override = manager;
   }

   /** How long building the client-side manager took, or -1 if it was never built. */
   public static long buildMillis() {
      return buildMs;
   }

   /**
    * The template manager, or null if one cannot be obtained.
    *
    * Three sources, in order: an injected one (headless diagnostics), the running singleplayer
    * server's, or one built here from the vanilla data packs.
    *
    * That last case is the important one — the seed finder is normally opened from the Create
    * World screen, where no server exists yet, and without it every layout predicate would be
    * permanently unanswerable in the place people actually use it.
    *
    * MUST be called from a seed-finder worker thread, never the render thread: the first call
    * reads and indexes the vanilla structure templates. It is built once and cached, so only one
    * worker ever pays, and the Create World screen never touches this at all.
    */
   public static StructureTemplateManager templates() {
      StructureTemplateManager local = override;
      if (local != null) {
         return local;
      }
      try {
         var server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
         if (server != null) {
            return server.getStructureManager();   // in a world: use the real one
         }
      } catch (Throwable ignored) {
         // headless, or no client — fall through to building our own
      }
      return buildFromClientResources();
   }

   /**
    * Builds a template manager from the vanilla data packs, the same way the headless diagnostic
    * does. Synchronized and cached: the cost is paid once, by whichever worker asks first.
    *
    * The LevelStorageAccess is scaffolding the constructor demands — a world-local template
    * folder that stays empty. Nothing is ever written to it; only the packs are read.
    */
   private static synchronized StructureTemplateManager buildFromClientResources() {
      // A FAILURE IS NOT FOREVER (1.44.2). It used to be: one failed build set a flag that lasted the
      // whole session, and every building search after it quietly matched nothing. Now a failed build
      // may be retried after a short pause, and the search refuses up front with a sentence instead of
      // spinning (see problem()).
      if (clientBuilt != null) {
         return clientBuilt;
      }
      if (clientBuildFailed && System.currentTimeMillis() - failedAtMs < RETRY_AFTER_MS) {
         return null;
      }
      long t0 = System.currentTimeMillis();
      removeStaleTempFolders();
      Path dir = null;
      try {
         PackRepository repo = ServerPacksSource.createVanillaTrustedRepository();
         repo.reload();
         List<PackResources> packs = repo.getAvailablePacks().stream().map(Pack::open).toList();
         MultiPackResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs);

         // THE FOLDER IS SCAFFOLDING and is now cleaned up. The constructor wants a world storage
         // handle but keeps only a PATH from it (checked in the game's code), so the handle — which
         // holds a session.lock file — is closed straight after. Before 1.44.2 it was never closed,
         // so the lock stayed and deleteOnExit could not remove a non-empty folder: 271 of them had
         // piled up in %TEMP% on the machine this was found on, one per game session.
         dir = Files.createTempDirectory(TEMP_PREFIX);
         LevelStorageSource storage = LevelStorageSource.createDefault(dir);
         StructureTemplateManager built;
         try (LevelStorageSource.LevelStorageAccess access = storage.createAccess("seedfinder")) {
            built = new StructureTemplateManager(resources, access, DataFixers.getDataFixer(),
                  net.minecraft.core.registries.BuiltInRegistries.BLOCK);
         }
         deleteQuietly(dir);
         clientBuilt = built;
         clientBuildFailed = false;
         lastError = null;
         buildMs = System.currentTimeMillis() - t0;
         FableVisionClient.LOGGER.info("Seed finder: built structure templates in {}ms", buildMs);
         return clientBuilt;
      } catch (Throwable t) {
         clientBuildFailed = true;
         failedAtMs = System.currentTimeMillis();
         lastError = t.getClass().getSimpleName();
         if (dir != null) {
            deleteQuietly(dir);
         }
         FableVisionClient.LOGGER.warn("Seed finder: could not build structure templates —"
               + " searches that read buildings cannot run until this succeeds", t);
         return null;
      }
   }

   private static final String TEMP_PREFIX = "fablevision-templates";
   private static final long RETRY_AFTER_MS = 30_000L;
   private static volatile long failedAtMs;
   private static volatile String lastError;

   /**
    * Why building-dependent checks cannot run right now, in words for the search screen — or null
    * when they can. Worker-thread only (it may build the manager).
    */
   public static String problem() {
      if (templates() != null) {
         return null;
      }
      return "Couldn't load the game's structure files" + (lastError == null ? "" : " (" + lastError + ")")
            + ", so this search can't check buildings. Try again in a moment.";
   }

   /** Folders left by versions before 1.44.2, and by any session that crashed mid-build. */
   private static void removeStaleTempFolders() {
      try {
         Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
         try (var list = Files.newDirectoryStream(tmp, TEMP_PREFIX + "*")) {
            for (Path p : list) {
               deleteQuietly(p);   // a folder still locked by another running game simply stays
            }
         }
      } catch (Throwable ignored) {
         // housekeeping only
      }
   }

   private static void deleteQuietly(Path dir) {
      try (var walk = Files.walk(dir)) {
         walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try {
               Files.deleteIfExists(p);
            } catch (Throwable ignored) {
               // in use by another game instance, or already gone
            }
         });
      } catch (Throwable ignored) {
         // nothing to delete
      }
   }

   /**
    * True when building predicates can actually be answered. Worker-thread only — the first call
    * may build the template manager. Never call this from the render thread.
    */
   public static boolean available() {
      return templates() != null;
   }

   /**
    * Every template id the village at {@code chunk} assembles, in piece order — the town centre
    * first, then streets and buildings. Empty if the structure did not generate or templates are
    * unavailable.
    *
    * @param fullRs MUST be the full noise state, not the finder's climate-only one. Village
    *               placement asks the generator for terrain height per piece; a climate-only
    *               router reports flat ground and the piece list comes out wrong.
    */
   public static List<Identifier> pieceTemplates(HolderLookup.Provider source, WorldgenContext ctx,
                                                 long seed, ChunkPos chunk,
                                                 Holder<Structure> structure, RandomState fullRs) {
      // Biome was already confirmed by the caller's stage-1 check; re-testing it here would
      // just repeat work, and a false here would silently drop a valid structure.
      return pieceTemplates(source, ctx, seed, chunk, structure, fullRs, h -> true, Dim.OVERWORLD);
   }

   /** As above, in a named dimension. */
   public static List<Identifier> pieceTemplates(HolderLookup.Provider source, WorldgenContext ctx,
                                                 long seed, ChunkPos chunk,
                                                 Holder<Structure> structure, RandomState fullRs,
                                                 Dim dim) {
      return pieceTemplates(source, ctx, seed, chunk, structure, fullRs, h -> true, dim);
   }

   /**
    * The structure's OWN biome requirement, as vanilla applies it.
    *
    * Pass this as {@code validBiome} to ask the stricter question "does this structure really
    * generate here", rather than "what would it build if it did". Vanilla samples the biome at
    * the generation point it picks — real terrain height included — so this rejects chunks the
    * finder's cheaper stage-1 check (one sample at the locate position, y=64) lets through.
    */
   public static Predicate<Holder<Biome>> ownBiomes(Holder<Structure> structure) {
      return h -> structure.value().biomes().contains(h);
   }

   /**
    * As above, but with the caller's choice of biome test. {@code h -> true} answers "what would
    * it build here"; {@link #ownBiomes} answers "is it actually here", which is what anything
    * reporting COORDINATES to a player must ask.
    */
   public static List<Identifier> pieceTemplates(HolderLookup.Provider source, WorldgenContext ctx,
                                                 long seed, ChunkPos chunk,
                                                 Holder<Structure> structure, RandomState fullRs,
                                                 Predicate<Holder<Biome>> validBiome) {
      return pieceTemplates(source, ctx, seed, chunk, structure, fullRs, validBiome, Dim.OVERWORLD);
   }

   /**
    * The full form: caller's biome test, in a named dimension.
    *
    * {@code dim} must be the dimension the TARGET lives in, and everything derived from it has to
    * agree — the noise settings, the biome source, the level key and the build limits. A bastion
    * assembled against overworld terrain is not a wrong-ish answer, it is an answer about a world
    * that does not exist.
    *
    * {@code fullRs} must have been built for the SAME dimension
    * ({@code ctx.fullRandomState(dim, seed)}); a Nether structure sampled through overworld noise
    * would read heights from the wrong world.
    */
   public static List<Identifier> pieceTemplates(HolderLookup.Provider source, WorldgenContext ctx,
                                                 long seed, ChunkPos chunk,
                                                 Holder<Structure> structure, RandomState fullRs,
                                                 Predicate<Holder<Biome>> validBiome, Dim dim) {
      StructureStart start = assemble(source, ctx, seed, chunk, structure, fullRs, validBiome, dim);
      return start == null ? List.of() : templateIds(start.getPieces());
   }

   /**
    * How many pieces really assembled here — including the ones that carry no template name.
    *
    * An empty {@link #pieceTemplates} does NOT mean "nothing is there". A nether fortress and a
    * desert pyramid are built from CODE rather than from template files, so every piece they make is
    * a plain {@code StructurePiece} with no template location for {@link #templateIds} to report,
    * and the list comes back empty for a structure that is standing right there. Anything asking
    * "is it really here" has to count pieces; only "what is inside it" can use the template list.
    *
    * This distinction is not hypothetical: it made {@code multiDiag} report five false matches
    * against a finder that was correct on all five.
    */
   public static int pieceCount(HolderLookup.Provider source, WorldgenContext ctx, long seed,
                                ChunkPos chunk, Holder<Structure> structure, RandomState fullRs,
                                Predicate<Holder<Biome>> validBiome, Dim dim) {
      StructureStart start = assemble(source, ctx, seed, chunk, structure, fullRs, validBiome, dim);
      return start == null ? 0 : start.getPieces().size();
   }

   /**
    * Everything a single assembly can answer, answered once.
    *
    * An assembly costs about 700ms (measured — the benchmark's layout row runs at 1.4 seeds/sec and
    * is almost entirely this). A row that wants two facts about the same structure must therefore
    * not ask two questions: "a big village with an armorer" is one build, one piece list, one
    * footprint. The separate {@link #pieceTemplates}, {@link #pieceCount} and {@link #boxAt} entry
    * points remain for callers that genuinely want only one of the three.
    *
    * @param templates the folder-qualified template id of every piece that has one — the list to
    *                  ask what is INSIDE the structure. Shorter than {@code pieces}, and for a
    *                  code-built structure it is empty however many pieces there really are.
    * @param pieces    how many pieces really assembled, template-named or not. The list to ask how
    *                  BIG the structure is.
    * @param box       the real footprint, for asking whether a point is inside it.
    * @param classes   the piece CLASS names, which is the only handle a code-built structure has.
    *                  A nether fortress and a desert pyramid have no template ids at all — their
    *                  pieces are hand-written Java, so "the blaze spawner room" is a class
    *                  ({@code MonsterThrone}) and not a file. Empty-template structures are exactly
    *                  the ones that need this, and it is the gap that made multiDiag report five
    *                  false matches against a finder that was right.
    */
   public record Built(List<Identifier> templates, int pieces, BoundingBox box, List<String> classes,
                       List<PieceAt> placed) {

      /** Every piece with a template id, WITH the box it occupies. {@link #templates()} is this
       *  list with the positions dropped, and dropping them is what made a content row able to say
       *  "this village has a weaponsmith" and unable to say where it is. */
      public List<PieceAt> placed() {
         return placed;
      }
   }

   /** One assembly, or null if the structure does not generate here. See {@link Built}. */
   public static Built builtAt(HolderLookup.Provider source, WorldgenContext ctx, long seed,
                               ChunkPos chunk, Holder<Structure> structure, RandomState fullRs,
                               Predicate<Holder<Biome>> validBiome, Dim dim) {
      StructureStart start = assemble(source, ctx, seed, chunk, structure, fullRs, validBiome, dim);
      if (start == null) {
         return null;
      }
      List<StructurePiece> pieces = start.getPieces();
      List<String> classes = new ArrayList<>(pieces.size());
      for (StructurePiece p : pieces) {
         classes.add(p.getClass().getSimpleName());
      }
      List<PieceAt> placed = piecesAt(pieces);
      List<Identifier> ids = new ArrayList<>(placed.size());
      for (PieceAt at : placed) {
         ids.add(at.id());
      }
      return new Built(ids, pieces.size(), start.getBoundingBox(), classes, placed);
   }

   /**
    * The structure's real footprint here, or null if it does not generate.
    *
    * This is the box vanilla itself uses, covering every assembled piece — not a radius, not the
    * locate position with a guess around it. It exists for the one question a distance cannot
    * answer: is a particular point INSIDE this structure. A village 40 blocks from spawn may not
    * reach spawn at all and one 90 blocks away may sprawl right over it, because the locate
    * position is a corner of one piece rather than the middle of anything.
    */
   public static BoundingBox boxAt(HolderLookup.Provider source, WorldgenContext ctx, long seed,
                                   ChunkPos chunk, Holder<Structure> structure, RandomState fullRs,
                                   Predicate<Holder<Biome>> validBiome, Dim dim) {
      StructureStart start = assemble(source, ctx, seed, chunk, structure, fullRs, validBiome, dim);
      return start == null ? null : start.getBoundingBox();
   }

   /**
    * Whether every structure check starts from a COLD biome lookup (1.44.4). Vanilla's biome lookup
    * remembers each thread's last answer and starts the next search from it, so at an exact tie
    * between two biomes the answer depends on what that thread looked up just before — and the
    * finder always looks up the biome at the locate position right before this check. On a tie that
    * decided the verdict. See WorldgenContext.forgetBiomeHint and the access widener; the map got the
    * same fix in 1.44.3. False only in tieDiag, which runs both ways and asks the game which is right.
    */
   static volatile boolean COLD_BIOME = true;

   /**
    * STRUCTURE CHECKS THAT THREW, counted and shown (1.44.5). Both checks fail CLOSED — a check that
    * threw has not said yes — and until now said so only at debug level. That is right for one odd
    * chunk and disastrous for a systematic failure: if every check throws (the structure files
    * becoming unreadable mid-session, say), every seed quietly fails and the search runs for ever
    * finding nothing, which is exactly what one featureDiag run was seen doing — it rejected seeds
    * that are hits. Now every failure is counted, the first and then one a minute are logged with a
    * stack, and SearchWatchdog stops a search whose checks are failing wholesale, with an error.
    */
   public static final java.util.concurrent.atomic.AtomicLong CHECK_FAILURES = new java.util.concurrent.atomic.AtomicLong();
   /** Every structure check started, so a failure RATE can be judged (most seeds never reach one). */
   public static final java.util.concurrent.atomic.AtomicLong CHECK_ATTEMPTS = new java.util.concurrent.atomic.AtomicLong();
   public static volatile Throwable lastCheckFailure;
   private static volatile long lastFailureLogMs;

   private static void checkFailed(String which, long seed, Throwable t) {
      CHECK_FAILURES.incrementAndGet();
      lastCheckFailure = t;
      long now = System.currentTimeMillis();
      if (now - lastFailureLogMs >= 60_000) {
         lastFailureLogMs = now;
         FableVisionClient.LOGGER.warn("Seed finder: a structure {} threw for seed {} ({} so far this session);"
               + " the seed was treated as not matching", which, seed, CHECK_FAILURES.get(), t);
      }
   }

   /** How many start points sat on an exact biome tie — counted so tieDiag can show it met some. */
   static final java.util.concurrent.atomic.AtomicInteger TIES_SEEN = new java.util.concurrent.atomic.AtomicInteger();

   /**
    * Does the structure's biome test pass at its real start point WHICHEVER WAY A TIE THERE FALLS?
    *
    * Vanilla tests one biome at that point, but on an exact tie between two biomes which one it gets
    * depends on lookup history, so the real game may build the structure or not ({@link
    * WorldgenContext#nearestBiomes}). tieDiag found ties in about 1 in 16,000 Nether fossil checks and
    * the game went both ways on them. A yes that only holds for one side of a tie is a coordinate the
    * player may walk to and find empty, so it is a no. Everywhere else there is one nearest biome and
    * this is exactly vanilla's test.
    */
   static boolean biomeHolds(WorldgenContext ctx, Dim dim, net.minecraft.core.BlockPos at, RandomState fullRs,
                             Predicate<Holder<Biome>> validBiome) {
      var target = fullRs.sampler().sample(net.minecraft.core.QuartPos.fromBlock(at.getX()),
            net.minecraft.core.QuartPos.fromBlock(at.getY()), net.minecraft.core.QuartPos.fromBlock(at.getZ()));
      java.util.Set<Holder<Biome>> nearest = ctx.nearestBiomes(dim, target);
      if (nearest.size() > 1) {
         TIES_SEEN.incrementAndGet();
      }
      for (Holder<Biome> b : nearest) {
         if (!validBiome.test(b)) {
            return false;
         }
      }
      return true;
   }

   /** The shared assembly both of the above need: vanilla's own generate, or null if it isn't here. */
   private static StructureStart assemble(HolderLookup.Provider source, WorldgenContext ctx,
                                          long seed, ChunkPos chunk, Holder<Structure> structure,
                                          RandomState fullRs, Predicate<Holder<Biome>> validBiome,
                                          Dim dim) {
      StructureTemplateManager manager = templates();
      if (manager == null) {
         return null;
      }
      CHECK_ATTEMPTS.incrementAndGet();   // before anything that can throw, so every failure is also an attempt
      try {
         RegistryAccess registries = (RegistryAccess) source;
         BiomeSource biomes = ctx.biomeSource(dim);
         NoiseBasedChunkGenerator gen;
         LevelHeightAccessor height;
         if (SeedFunnel.CACHE_SCAFFOLD) {
            WorldgenContext.Scaffold scaffold = ctx.scaffold(dim);
            gen = scaffold.generator();
            height = scaffold.height();
         } else {
            Holder<NoiseGeneratorSettings> noise =
                  source.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(noiseFor(dim));
            gen = new NoiseBasedChunkGenerator(biomes, noise);
            height = heightFor(source, dim);
         }

         if (SeedFunnel.ENABLED) {
            SeedFunnel.assemblies++;
         }
         if (COLD_BIOME) {
            ctx.forgetBiomeHint(dim);
         }
         // THE TIE RULE BEFORE THE ASSEMBLY (1.44.4): where the structure really starts, and whether
         // its biome test holds whichever way a tie there falls. See biomeHolds.
         var stub = structure.value().findValidGenerationPoint(new Structure.GenerationContext(
               registries, gen, biomes, fullRs, manager, seed, chunk, height, validBiome));
         if (stub.isEmpty() || !biomeHolds(ctx, dim, stub.get().position(), fullRs, validBiome)) {
            return null;
         }
         StructureStart start = structure.value().generate(
               structure, levelFor(dim), registries, gen, biomes, fullRs,
               manager, seed, chunk, 0, height, validBiome);
         if (start == null || !start.isValid()) {
            return null;
         }

         return start;
      } catch (Throwable t) {
         checkFailed("assembly", seed, t);
         return null;
      }
   }

   /**
    * Does this structure REALLY generate here — vanilla's own question, without building anything?
    *
    * {@link #pieceTemplates} answers "what would it build", and pays for the building. This answers
    * only "would it be here at all", which is {@code Structure.findValidGenerationPoint}: work out
    * the real start position, then test the biome there. For a jigsaw structure the piece placement
    * lives in a lambda inside the returned stub and is never run, so a village costs about a
    * ninetieth of an assembly and a trial chamber a three-hundredth (measured, {@code strictDiag}).
    *
    * Used by rows with no piece requirement, which until now had no way to ask this at all — their
    * only biome test was the finder's single cheap sample at the locate position, and
    * {@code strictDiag} measured that disagreeing with vanilla on 4% of confirmed hits.
    *
    * FAILS CLOSED (since 1.44.2). It used to fail open — "if the check cannot run, give the answer
    * the finder gave before this existed" — on the reasoning that failing closed would cost every
    * result. That reasoning traded a known lie for a known cost: the looser answer is the one that
    * sent players to a Trial Chamber in Deep Dark, and this project's rule is that a search which
    * lies is worse than one that finds nothing. The cost is now handled where it belongs: a search
    * that needs this check refuses up front, with a sentence, when templates cannot be loaded
    * (SeedFinder.worker, {@link #problem()}), so "no answer" never turns into "no results, forever".
    */
   public static boolean generatesHere(HolderLookup.Provider source, WorldgenContext ctx, long seed,
                                       ChunkPos chunk, Holder<Structure> structure, RandomState fullRs,
                                       Predicate<Holder<Biome>> validBiome, Dim dim) {
      StructureTemplateManager manager = templates();
      if (manager == null) {
         return false;
      }
      CHECK_ATTEMPTS.incrementAndGet();   // before anything that can throw, so every failure is also an attempt
      try {
         BiomeSource biomes = ctx.biomeSource(dim);
         NoiseBasedChunkGenerator gen;
         LevelHeightAccessor height;
         if (SeedFunnel.CACHE_SCAFFOLD) {
            WorldgenContext.Scaffold scaffold = ctx.scaffold(dim);
            gen = scaffold.generator();
            height = scaffold.height();
         } else {
            Holder<NoiseGeneratorSettings> noise =
                  source.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(noiseFor(dim));
            gen = new NoiseBasedChunkGenerator(biomes, noise);
            height = heightFor(source, dim);
         }
         if (SeedFunnel.ENABLED) {
            SeedFunnel.genPoints++;
         }
         if (COLD_BIOME) {
            ctx.forgetBiomeHint(dim);
         }
         Structure.GenerationContext context = new Structure.GenerationContext(
               (RegistryAccess) source, gen, biomes, fullRs, manager, seed, chunk, height, validBiome);
         var stub = structure.value().findValidGenerationPoint(context);
         return stub.isPresent() && biomeHolds(ctx, dim, stub.get().position(), fullRs, validBiome);
      } catch (Throwable t) {
         // Closed: a check that threw has not said "yes". See the note above.
         checkFailed("generation-point check", seed, t);
         return false;
      }
   }

   /**
    * The template id of every piece, whichever way the structure names them.
    *
    * Two kinds exist and Phase 2 needs both:
    *   - jigsaw structures (village, outpost, trial chamber, city) hang a
    *     {@link SinglePoolElement} off each piece and that carries the id;
    *   - fixed-layout template structures (mansion, igloo, ruined portal, shipwreck, ocean
    *     ruins) are {@link TemplateStructurePiece}s whose {@code templateName} is the id, with
    *     no getter — hence the access widener.
    * Anything else is a code-built piece (stronghold corridors) with no template at all.
    */
   public static List<Identifier> templateIds(List<StructurePiece> pieces) {
      List<Identifier> out = new ArrayList<>();
      for (PieceAt at : piecesAt(pieces)) {
         out.add(at.id());
      }
      return out;
   }

   /**
    * ONE PIECE, AND WHERE IT REALLY IS.
    *
    * {@link #templateIds} answers "what did this structure build" and throws the positions away,
    * which was fine while the only question was whether a token appeared. It is not fine for the
    * question a player actually asks, which is where the thing is: a village is routinely 130 by
    * 176 blocks and the coordinate every prediction prints is the structure's locate position, so
    * "the village has a weaponsmith" and "there is no weaponsmith where you are standing" are both
    * true at once. The box is what closes that gap.
    *
    * INDICES DO NOT LINE UP WITH {@code getPieces()} and never did — a code-built piece contributes
    * nothing and a ruined portal contributes two entries — so a caller that needs a piece's id AND
    * its position has to get them together. Zipping the two lists by index is the bug this record
    * exists to make impossible; {@code inspect} was doing exactly that and getting away with it
    * only because the unnamed pieces happened to come last.
    */
   public record PieceAt(Identifier id, BoundingBox box) {}

   /** Every piece that has a template id, with the box it really occupies. See {@link PieceAt}. */
   public static List<PieceAt> piecesAt(List<StructurePiece> pieces) {
      List<PieceAt> out = new ArrayList<>();
      for (StructurePiece piece : pieces) {
         BoundingBox where = piece.getBoundingBox();
         if (piece instanceof PoolElementStructurePiece pool) {
            if (pool.getElement() instanceof SinglePoolElement single) {
               out.add(new PieceAt(single.getTemplateLocation(), where));
            }
         } else if (piece instanceof TemplateStructurePiece template) {
            // The real, folder-qualified location ("woodland_mansion/1x1_b3"), not the bare
            // templateName. Each subclass knows its own folder; asking it beats prefixing by hand —
            // and this comment used to say "mansion/1x1_b3", which is the guess a human makes and
            // is wrong. The mansion diagnostic hardcoded that guess and produced a clean, confident,
            // completely empty census before anyone noticed.
            Identifier id = template.makeTemplateLocation();
            if (id != null) {
               out.add(new PieceAt(id, where));
            }
            // A SYNTHETIC id for the one fact a ruined portal's template name cannot carry.
            // portal_1 is the same file whether it stands in the open or sits thirty blocks under a
            // hill; the difference is an enum on the piece. Emitting it as an extra "template id"
            // means the whole existing machine — the token predicate, the picker rows, the AI's
            // schema, exclusions, the "without" flip — works on it with no new mechanism at all.
            // The prefix is namespaced under a folder no real template uses, so it can never be
            // matched by accident: there is no "ruined_portal/placement/" directory in the assets.
            if (template instanceof RuinedPortalPiece portal && portal.verticalPlacement != null) {
               out.add(new PieceAt(Identifier.withDefaultNamespace(
                     PLACEMENT_PREFIX + portal.verticalPlacement.getSerializedName()), where));
            }
         }
      }
      return out;
   }

   /** Where a ruined portal's placement is published as a pseudo-template. See {@link #templateIds}. */
   public static final String PLACEMENT_PREFIX = "ruined_portal/placement/";

   /**
    * Does this village contain a building whose template id contains {@code token}?
    *
    * Matching on the id path rather than a hardcoded list is deliberate. Each biome names its
    * buildings differently — plains has {@code village/plains/houses/plains_armorer_house_1},
    * desert has {@code village/desert/houses/desert_armorer_1} — so one token ("armorer") spans
    * every variant, and the ids come from what the seed actually assembled rather than from a
    * table that could drift from the version's real assets.
    */
   /**
    * WHERE that building is, not merely whether it is there.
    *
    * The nearest matching piece to {@code near}, or null when the structure has none. "Nearest"
    * matters because a village can have two weaponsmiths (seeds do), and the one worth naming is
    * the one the player is standing closest to.
    *
    * This is the other half of {@link #hasBuilding}, and it exists because having only the first
    * half is what made a correct search read as a broken one: the row promises a BUILDING and the
    * line prints the STRUCTURE's locate position, which for a 150-piece village is a different
    * place. Measured by {@code featureDiag} — a median of 28 blocks for the weaponsmith and up to
    * 114 for the ancient city's sauna. Nobody walks to a coordinate and searches a 114-block
    * radius for a building they have not been told to look for.
    */
   public static BlockPos buildingAt(List<PieceAt> placed, String token, BlockPos near) {
      BlockPos best = null;
      double bestD = Double.MAX_VALUE;
      for (PieceAt piece : placed) {
         if (!tokenMatches(piece.id().getPath(), token)) {
            continue;
         }
         BoundingBox b = piece.box();
         BlockPos mid = new BlockPos((b.minX() + b.maxX()) / 2, (b.minY() + b.maxY()) / 2,
               (b.minZ() + b.maxZ()) / 2);
         double d = near == null ? 0
               : Math.hypot(mid.getX() - near.getX(), mid.getZ() - near.getZ());
         if (best == null || d < bestD) {
            best = mid;
            bestD = d;
         }
      }
      return best;
   }

   public static boolean hasBuilding(List<Identifier> pieces, String token) {
      for (Identifier id : pieces) {
         if (tokenMatches(id.getPath(), token)) {
            return true;
         }
      }
      return false;
   }

   /**
    * Does one template path carry the row's token? A token may list SPELLINGS separated by
    * {@code |}, and any of them counts.
    *
    * One biome spells a building differently from the other four, and a single substring cannot
    * cover both. The snowy weaponsmith is the case that forced this: its file is
    * {@code snowy_weapon_smith_1}, so the token "weaponsmith" never fired on a snowy village and the
    * catalog went on to state that snowy villages have no weaponsmith. They do — the pool references
    * it, and it is one of the four lava houses. {@code smithDiag} reads the template files and fails
    * on any smith house a row's token misses.
    */
   public static boolean tokenMatches(String templatePath, String token) {
      String path = templatePath.toLowerCase(java.util.Locale.ROOT);
      for (String spelling : token.toLowerCase(java.util.Locale.ROOT).split("[|]")) {
         if (!spelling.isEmpty() && path.contains(spelling)) {
            return true;
         }
      }
      return false;
   }
}
