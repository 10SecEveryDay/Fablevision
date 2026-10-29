package com.fablevision.client.seedfinder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSpawnFinder;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.AbstractSpreadingStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.DimensionOriginStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;

/**
 * The world-gen data every seed check shares, built from REAL registries that exist at
 * runtime (the shipped client jar does NOT contain the data-gen bootstrap class, so this
 * must come from a live source):
 *  - on the Create World screen: that screen's own worldgen registries (datapack-aware),
 *  - in your singleplayer world: the integrated server's registries.
 * Everything here is immutable and safe to share across worker threads; only
 * {@link RandomState} (via {@link #randomState}) is per-seed.
 *
 * Covers the two dimensions this finder searches. THE END BIOME SOURCE IS STILL BUILT and is
 * still a field, but nothing searches it any more: it exists so {@link SeedCatalog#dimensionOf} can
 * RECOGNISE an End structure set and skip it. See {@link SeedCriteria.Dim} for why the End was
 * removed. Keeping the source is what makes the exclusion a decision the code can act on rather
 * than a list of structure names somebody has to maintain.
 */
public final class WorldgenContext {
   private static volatile WorldgenContext instance;

   final HolderLookup.Provider source;
   public final HolderLookup.RegistryLookup<StructureSet> structureSets;
   public final HolderLookup.RegistryLookup<Biome> biomes;
   public final BiomeSource overworldBiomes;
   public final BiomeSource netherBiomes;
   /** NOT SEARCHED. Built only so an End structure set can be recognised and skipped — see the
    *  class note and {@link SeedCatalog#dimensionOf}. There is no {@code Dim} that selects it. */
   public final BiomeSource endBiomes;
   public final List<Holder.Reference<StructureSet>> allSets;
   /** Kept so the CLIMATE PARAMETERS behind the biome sources stay reachable - the multi-noise
    *  preset is the only place a biome's temperature range is written down. */
   private final Holder<MultiNoiseBiomeSourceParameterList> overworldPreset;
   private final Holder<MultiNoiseBiomeSourceParameterList> netherPreset;
   private final Map<String, long[]> tempBands = new ConcurrentHashMap<>();
   private static final long[] NO_BAND = new long[0];

   private final HolderGetter<NormalNoise> noiseParams;
   // Sized from the enum, not from a remembered 3. The array outliving the dimension it was
   // sized for is exactly the kind of leftover that keeps a removed feature half-alive.
   private final NoiseGeneratorSettings[] fullSettings = new NoiseGeneratorSettings[Dim.values().length];
   /** Whether any structure set is placed at the dimension origin (26.3's "dimension_origin"). No
    *  vanilla set is; a datapack's could be. Decides whether {@link #structureState} pays for the real
    *  origin — see there. */
   private final boolean anyDimensionOrigin;

   /**
    * Where to get registries from, and there is now exactly one answer: the Create World screen.
    *
    * THIS USED TO FALL BACK TO THE RUNNING WORLD. With no screen it returned
    * {@code getSingleplayerServer().registryAccess()}, which is how the finder was able to work
    * inside a loaded world at all. Registries are not a seed and the fallback was single-player
    * only, so nothing it did was unsafe — but it was the one line that made "this only builds new
    * worlds" a statement about how the UI happened to be wired rather than about what the code can
    * do. It is gone in 1.40.0 and must not come back: {@link SeedAccess} is the only door.
    *
    * Returns null anywhere that is not the Create World screen, which every caller already handles
    * (the finder reports that it needs the Create World screen and does nothing).
    */
   public static HolderLookup.Provider findSource(CreateWorldScreen createScreen) {
      return SeedAccess.sourceFor(createScreen);
   }

   public static WorldgenContext get(HolderLookup.Provider source) {
      WorldgenContext local = instance;
      if (local == null || local.source != source) {
         synchronized (WorldgenContext.class) {
            local = instance;
            if (local == null || local.source != source) {
               instance = local = new WorldgenContext(source);
            }
         }
      }
      return local;
   }

   private WorldgenContext(HolderLookup.Provider source) {
      this.source = source;
      this.structureSets = source.lookupOrThrow(Registries.STRUCTURE_SET);
      this.biomes = source.lookupOrThrow(Registries.BIOME);
      var presets = source.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
      this.overworldPreset = presets.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
      this.netherPreset = presets.getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER);
      this.overworldBiomes = MultiNoiseBiomeSource.createFromPreset(this.overworldPreset);
      this.netherBiomes = MultiNoiseBiomeSource.createFromPreset(this.netherPreset);
      this.endBiomes = TheEndBiomeSource.create(this.biomes);
      this.allSets = structureSets.listElements().toList();
      this.noiseParams = source.lookupOrThrow(Registries.NOISE);
      var noiseSettings = source.lookupOrThrow(Registries.NOISE_SETTINGS);
      for (Dim dim : Dim.values()) {
         fullSettings[dim.ordinal()] = noiseSettings.getOrThrow(switch (dim) {
            case NETHER -> NoiseGeneratorSettings.NETHER;
            default -> NoiseGeneratorSettings.OVERWORLD;
         }).value();
      }
      this.anyDimensionOrigin = allSets.stream()
            .anyMatch(s -> s.value().placement() instanceof DimensionOriginStructurePlacement);
   }

   // THE CLIMATE-ONLY SETTINGS ARE GONE (1.45.0, Minecraft 26.3).
   //
   // Up to 26.1.2, RandomState.create instantiated EVERY noise the router referenced (~9 ms a seed),
   // so this class handed the finder a copy of the settings whose router kept only the six climate
   // functions. In 26.3 RandomState compiles a density function the first time it is sampled and
   // creates a noise the first time a compiled function needs it, so a state that is only ever asked
   // about climate only ever builds climate. The copy would buy nothing — and it could no longer be
   // built faithfully either, since 26.3's NoiseGeneratorSettings and NoiseRouter changed shape. Every
   // state below is built from the REAL settings, exactly as vanilla builds its own.

   public BiomeSource biomeSource(Dim dim) {
      return switch (dim) {
         case NETHER -> netherBiomes;
         default -> overworldBiomes;
      };
   }

   /**
    * The per-seed noise state the finder samples climate from. Since 26.3 this is the same object as
    * {@link #fullRandomState} — see the note above the constructor's end for why the climate-only copy
    * went. Kept as its own name so the call sites still say what they use the state for.
    */
   public RandomState randomState(Dim dim, long seed) {
      return fullRandomState(dim, seed);
   }

   /** One slot per worker thread PER DIMENSION: the last state built, and what it was built for. */
   private record FullState(WorldgenContext owner, long seed, RandomState state) {}

   private static final ThreadLocal<FullState[]> LAST_FULL =
         ThreadLocal.withInitial(() -> new FullState[Dim.values().length]);

   /**
    * The per-seed noise state, cached per thread and per dimension.
    *
    * Cached because a seed with several candidate villages used to rebuild this identical object once
    * PER CANDIDATE — the layout check asks for it every time it runs. In 26.3 the cache matters more:
    * a RandomState compiles each density function on first use, so a fresh state per question would
    * recompile the climate functions per question. A worker finishes one seed before starting the next,
    * so one slot per dimension hits every repeat (an Overworld-and-Nether wish no longer evicts its own
    * Overworld state) and memory never grows past one state per dimension per worker.
    *
    * Safe to share within a seed and across threads: compiled samplers and noises are immutable, and
    * the only mutable sampling state (caches) lives in the SamplerContext each caller makes.
    */
   public RandomState fullRandomState(Dim dim, long seed) {
      FullState[] slots = LAST_FULL.get();
      FullState last = slots[dim.ordinal()];
      if (last != null && last.owner() == this && last.seed() == seed) {
         return last.state();
      }
      RandomState fresh = newFullRandomState(dim, seed);
      slots[dim.ordinal()] = new FullState(this, seed, fresh);
      return fresh;
   }

   // ── Sampling, the way 26.3's vanilla does it ─────────────────────────────────────────────
   //
   // In 26.1.2 a RandomState carried one thread-safe Climate.Sampler (rs.sampler()). In 26.3 a sampler
   // is made per use from a SamplerContext, and a context with caches is NOT safe to share between
   // threads. These helpers are the only places the finder makes one, so each rule lives once.

   /**
    * A climate sampler for POINT lookups (biome confirms, biome rings, the map's biome grid). Uncached:
    * it allocates nothing shared and is safe on any thread. A point sample is the same number whether
    * or not a cache is on — a cache only remembers it.
    */
   public static Climate.Sampler climate(RandomState rs) {
      return rs.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
   }

   /**
    * A climate sampler for ONE structure check, made exactly as the game's structure step makes its
    * own (ChunkGenerator.createStructures: caches on, a fresh context). Never share it between threads.
    */
   public static Climate.Sampler structureClimate(RandomState rs) {
      return rs.createClimateSampler(SamplerContext.builder().enableCaches().build());
   }

   /** The biome at a quart position — 26.1.2's {@code BiomeSource.getNoiseBiome(x, y, z, sampler)}. */
   public static Holder<Biome> biomeAt(BiomeSource source, int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
      return source.createResolver(sampler).getNoiseBiome(quartX, quartY, quartZ);
   }

   /**
    * The world spawn POINT the game starts from — {@code NoiseBasedChunkGenerator.getOrigin} before it
    * is rounded to a chunk, with the same spawn target and the same kind of context. 26.1.2 asked
    * {@code Climate.Sampler.findSpawnPosition()} for the same thing; 26.3 moved it to NoiseSpawnFinder.
    * The player then spawns on a valid block within about a chunk of it, exactly as before.
    */
   public BlockPos spawnPoint(RandomState overworld) {
      return NoiseSpawnFinder.findSpawnPosition(fullSettings[Dim.OVERWORLD.ordinal()].spawnTarget(),
            overworld.samplersWithContext(SamplerContext.builder().enableCaches().build()));
   }

   /**
    * Whether another structure set's exclusion zone forbids this slot. 26.1.2 had this on every
    * placement; 26.3 moved it (unchanged) to AbstractSpreadingStructurePlacement. A placement that is
    * not one (26.3's dimension_origin) has no exclusion zone, which is what vanilla's own
    * isStructureChunk says for it.
    */
   public static boolean interactionsAllow(StructurePlacement placement, ChunkGeneratorStructureState state,
                                           int chunkX, int chunkZ) {
      return !(placement instanceof AbstractSpreadingStructurePlacement spreading)
            || spreading.applyInteractionsWithOtherStructures(state, chunkX, chunkZ);
   }

   /**
    * Clears this thread's biome-lookup hint for one dimension, so the next lookup answers as if it were
    * the first. See the access widener: at an exact tie between two biomes the answer otherwise depends
    * on what this thread looked up just before.
    */
   public void forgetBiomeHint(Dim dim) {
      (dim == Dim.NETHER ? netherPreset : overworldPreset).value().parameters().index.lastResult.remove();
   }

   /**
    * EVERY biome tied for nearest at this climate point — one biome almost everywhere, two or more on
    * an exact tie (1.44.4).
    *
    * WHY THIS EXISTS. Vanilla picks a biome by the nearest climate point, and on an exact tie its
    * lookup returns whichever of the tied points it met first — which depends on what that thread
    * looked up before (see {@link #forgetBiomeHint}). So at a tie the game ITSELF has no fixed answer:
    * whether a structure there generates depends on which chunk its worker thread happened to
    * generate just before. tieDiag measured it: clearing the hint made the answer repeatable but not
    * right — on one tie the game built the fossil, on another it did not. The only honest verdict is
    * the one that holds either way, and {@link VillageLayout#biomeHolds} uses this to give it.
    *
    * Same arithmetic as vanilla's {@code Climate.RTree}: the sum of squared per-parameter distances
    * over the six climate values and the point's offset. Only run on a structure that already said
    * yes, so its cost (one pass over the preset's points) is not on the search's hot path.
    */
   public java.util.Set<Holder<Biome>> nearestBiomes(Dim dim, net.minecraft.world.level.biome.Climate.TargetPoint t) {
      var values = (dim == Dim.NETHER ? netherPreset : overworldPreset).value().parameters().values();
      long best = Long.MAX_VALUE;
      java.util.Set<Holder<Biome>> tied = new java.util.HashSet<>();
      for (var pair : values) {
         var p = pair.getFirst();
         long d = sq(p.temperature().distance(t.temperature())) + sq(p.humidity().distance(t.humidity()))
               + sq(p.continentalness().distance(t.continentalness())) + sq(p.erosion().distance(t.erosion()))
               + sq(p.depth().distance(t.depth())) + sq(p.weirdness().distance(t.weirdness()))
               + sq(p.offset());
         if (d < best) {
            best = d;
            tied.clear();
            tied.add(pair.getSecond());
         } else if (d == best) {
            tied.add(pair.getSecond());
         }
      }
      return tied;
   }

   private static long sq(long v) {
      return v * v;
   }

   /** A fresh full noise state, uncached — for a caller that shares one across its own threads (the map). */
   public RandomState newFullRandomState(Dim dim, long seed) {
      return RandomState.create(noiseParams, seed, fullSettings[dim.ordinal()]);
   }

   /**
    * The temperature range this biome is allowed to occupy, in Climate's quantised units, or an
    * EMPTY array when the question cannot be answered (a biome absent from the preset). Unioned
    * across every parameter point that maps to the biome.
    *
    * Used ONLY by the approximate prefilter. It is a NECESSARY condition, not a sufficient one:
    * temperature is one of six climate dimensions, so being in band does not make the biome
    * present. It is also not strictly necessary in the vanilla sense - the parameter list picks
    * the NEAREST point rather than a containing one, so a biome can win slightly outside its
    * declared band. Both facts are why the prefilter has a measurable false-skip rate.
    */
   public long[] temperatureBand(Dim dim, ResourceKey<Biome> want) {
      return tempBands.computeIfAbsent(dim.name() + "|" + want.identifier(), k -> {
         var list = (dim == Dim.NETHER ? netherPreset : overworldPreset).value().parameters();
         long min = Long.MAX_VALUE;
         long max = Long.MIN_VALUE;
         for (var pair : list.values()) {
            if (pair.getSecond().is(want)) {
               min = Math.min(min, pair.getFirst().temperature().min());
               max = Math.max(max, pair.getFirst().temperature().max());
            }
         }
         return min > max ? NO_BAND : new long[]{min, max};
      });
   }

   /**
    * The seed-INDEPENDENT half of a layout check: the chunk generator the pieces are placed
    * against, the settings holder behind it, and the dimension's build limits.
    *
    * None of it varies by seed - only the {@link RandomState} handed to {@code generate} does -
    * so rebuilding it per layout check was re-deriving a constant. Vanilla itself keeps one
    * chunk generator per dimension for the entire world and shares it across worker threads,
    * which is why caching it here is safe rather than merely convenient.
    */
   public record Scaffold(NoiseBasedChunkGenerator generator, LevelHeightAccessor height) {}

   private final Scaffold[] scaffolds = new Scaffold[Dim.values().length];

   /** Cached per dimension, built on first use. Safe to share across seeds and threads. */
   public synchronized Scaffold scaffold(Dim dim) {
      int i = dim.ordinal();
      if (scaffolds[i] == null) {
         Holder<NoiseGeneratorSettings> noise = source.lookupOrThrow(Registries.NOISE_SETTINGS)
               .getOrThrow(switch (dim) {
                  case NETHER -> NoiseGeneratorSettings.NETHER;
                  default -> NoiseGeneratorSettings.OVERWORLD;
               });
         scaffolds[i] = new Scaffold(new NoiseBasedChunkGenerator(biomeSource(dim), noise), heightFor(dim));
      }
      return scaffolds[i];
   }

   /**
    * The dimension's build limits, read from the live registry rather than typed here - the
    * numbers are data this version ships, not constants this code is entitled to remember.
    * Fallbacks are the vanilla values, for a stripped datapack set.
    */
   private LevelHeightAccessor heightFor(Dim dim) {
      return dim == Dim.NETHER ? heightFor(BuiltinDimensionTypes.NETHER, 0, 128)
            : heightFor(BuiltinDimensionTypes.OVERWORLD, -64, 384);
   }

   private LevelHeightAccessor heightFor(ResourceKey<DimensionType> key, int fallbackMinY, int fallbackHeight) {
      int minY;
      int height;
      try {
         DimensionType type = source.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(key).value();
         minY = type.minY();
         height = type.height();
      } catch (Throwable missing) {
         minY = fallbackMinY;
         height = fallbackHeight;
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

   // ── The End, for the MAP only ─────────────────────────────────────────────
   //
   // Nothing here is reachable from a search: there is still no Dim for the End, so no row, wish or
   // funnel can name it. These exist so the map can DRAW the End with the same vanilla parts a new
   // Default world uses: the End noise settings, the End biome source above, and the End's build
   // limits. Whether its structures are drawn is decided by mapTruthDiag (SeedMapData.END_STRUCTURES).

   private Scaffold endScaffold;

   private record EndState(WorldgenContext owner, long seed, RandomState state) {}

   private static final ThreadLocal<EndState> LAST_END = new ThreadLocal<>();

   /** The End's chunk generator and build limits. Cached, safe to share across seeds and threads. */
   public synchronized Scaffold endScaffold() {
      if (endScaffold == null) {
         Holder<NoiseGeneratorSettings> noise = source.lookupOrThrow(Registries.NOISE_SETTINGS)
               .getOrThrow(NoiseGeneratorSettings.END);
         endScaffold = new Scaffold(new NoiseBasedChunkGenerator(endBiomes, noise),
               heightFor(BuiltinDimensionTypes.END, 0, 256));
      }
      return endScaffold;
   }

   /** The End's FULL per-seed noise state. There is no cheap climate-only half worth having: the End's
    *  biome source reads the island function, which is most of its router. One slot per thread. */
   public RandomState endRandomState(long seed) {
      EndState last = LAST_END.get();
      if (last != null && last.owner() == this && last.seed() == seed) {
         return last.state();
      }
      RandomState fresh = RandomState.create(noiseParams, seed, endScaffold().generator().generatorSettings().value());
      LAST_END.set(new EndState(this, seed, fresh));
      return fresh;
   }

   /** The End's structure bookkeeping, for the map's scan. */
   public ChunkGeneratorStructureState endStructureState(long seed, RandomState randomState) {
      return ChunkGeneratorStructureState.createForNormal(randomState, seed,
            origin(endScaffold(), randomState), endBiomes, structureSets);
   }

   /** Vanilla's per-seed structure bookkeeping (exclusion zones, stronghold rings). */
   public ChunkGeneratorStructureState structureState(Dim dim, long seed, RandomState randomState) {
      return ChunkGeneratorStructureState.createForNormal(randomState, seed,
            origin(scaffold(dim), randomState), biomeSource(dim), structureSets);
   }

   /**
    * The dimension origin 26.3 hands every ChunkGeneratorStructureState — vanilla passes
    * {@code generator.getOrigin(randomState)}, which for the Overworld is a full spawn search.
    *
    * The ONLY reader of it is DimensionOriginStructurePlacement (checked against the 26.3 source:
    * ChunkGeneratorStructureState.getDimensionOrigin has no other caller). No vanilla structure set uses
    * that placement, so for vanilla data the value is never read and a spawn search per seed would buy
    * nothing. If a datapack adds such a set, the real origin is computed, so the answer never depends
    * on this shortcut. The catalog and the map only place random-spread and ring sets in any case.
    */
   private ChunkPos origin(Scaffold scaffold, RandomState randomState) {
      return anyDimensionOrigin ? scaffold.generator().getOrigin(randomState) : ChunkPos.ZERO;
   }
}
