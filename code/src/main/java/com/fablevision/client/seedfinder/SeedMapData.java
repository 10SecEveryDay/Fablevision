package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.fablevision.client.FableVisionClient;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;

/**
 * Everything the seed map draws that the SEARCH did not already find: the biome underneath, and the
 * other structures that are really there.
 *
 * WHAT "REALLY THERE" MEANS, AND WHY IT CHANGED IN 1.43.1. Up to 1.43.0 a structure went on the map
 * when its placement said "this chunk" and the biome at the SURFACE of that spot was on its list. That
 * is the same cheap test that produced the End City that was not there (1.41.4), and it drew a Trial
 * Chamber where there was nothing: a trial chamber starts forty-odd blocks underground, and when the
 * ground down there is Deep Dark — which the surface sample cannot see — the game does not build it.
 * The search had stopped trusting that test long before (its strict check asks the game where the
 * structure really starts and tests the biome THERE); the map never got the same check.
 *
 * Now every marker is decided the way the game decides it. For each slot, vanilla's own weighted pick
 * is replayed, and each variant it rolls is asked {@code Structure.findValidGenerationPoint} — the real
 * start position with real terrain height, and the biome at that point — exactly what the game's
 * structure step asks before it builds anything. If that check cannot run (no structure templates),
 * the structure is NOT drawn and the map says how many it could not check. {@code mapTruthDiag} holds
 * the result against the game's own structure step on real chunks, in every dimension.
 *
 * ALL OF IT IS OFF THE RENDER THREAD, and since 1.44.3 it arrives in ONE step: biomes and every
 * structure together. It used to hand over the biomes first and then structures in dribbles as each
 * was checked, one at a time on one low-priority thread, so a map could sit for seconds with markers
 * popping in. Now the biome grid and the structure checks are split across {@link #POOL}, the per-seed
 * world-gen parts are built once per seed rather than once per view ({@link Gen#of}), and only the
 * finished view is handed over. Checks are remembered per seed, so panning back over ground already
 * looked at costs nothing.
 */
public final class SeedMapData {

   /** The map's tabs. NOT {@link Dim}: the End is here and is still not something a search can name. */
   public enum MapDim {
      OVERWORLD, NETHER, END;

      /** The search's dimension, or null for the End. */
      public Dim search() {
         return this == OVERWORLD ? Dim.OVERWORLD : this == NETHER ? Dim.NETHER : null;
      }

      public static MapDim of(Dim d) {
         return d == Dim.NETHER ? NETHER : OVERWORLD;
      }

      public String title() {
         return this == OVERWORLD ? "Overworld" : this == NETHER ? "Nether" : "End";
      }

      /** Which world a structure assembled for this tab believes it is in (the game passes this in). */
      public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> levelKey() {
         return this == OVERWORLD ? net.minecraft.world.level.Level.OVERWORLD
               : this == NETHER ? net.minecraft.world.level.Level.NETHER : net.minecraft.world.level.Level.END;
      }
   }

   /**
    * Whether the End tab draws End Cities, or biomes only.
    *
    * ON, BECAUSE IT WAS MEASURED, not because it looked right. The End was pulled from the search
    * because a cheap test drew a city on an island too low to hold one. The generation-point check
    * below is the game's own test for exactly that (an End City's start point is refused when the
    * ground there is under y=60), and {@code mapTruthDiag} compared every city the map drew against
    * the game's structure step on the same chunks: see SHIPPED-1.43.1.md for the numbers. If that
    * check ever finds a phantom, this goes to false and the tab says "biomes only".
    */
   public static final boolean END_STRUCTURES = true;

   /** Test switch: false reproduces the 1.43.0 map (surface-biome test only) so mapTruthDiag can show
    *  the difference. Never false in the game. */
   static volatile boolean VERIFY = !Boolean.getBoolean("fablevision.map.oldcheck");

   /** How many cells across the biome grid is. 192 since 1.44.4: views are built 1.5x wider than
    *  the screen (see SeedMapScreen.OVERSCAN), and this keeps the picture as sharp as 128 was. */
   public static final int GRID = 192;

   /** The smallest map the screen will draw by default, in blocks from the centre. */
   public static final int MIN_REACH = 512;

   /** How many structures one map may carry, nearest to its middle first. Was 160; with the checks in
    *  parallel a wide view no longer has to stop short to stay quick. */
   public static final int MAX_NEARBY = 400;

   /**
    * A view is split into CELLS x CELLS squares and each first gets an equal share of
    * {@link #MAX_NEARBY}, nearest to ITS OWN middle; whatever budget is left then goes to the rest,
    * nearest first (1.44.5).
    *
    * It used to be the 400 nearest the view's middle, which on a wide view is a disc about 2,800 blocks
    * across in the centre and nothing anywhere else: mapReplay showed 12 of 16 squares of a zoomed-out
    * view empty where the game had 244-400 structures each. That is the "no structures in view"
    * screenshot, and why they only appeared after zooming in and back out. A 4 x 4 split was tried
    * first and was not enough — 25 nearest a square's middle is a small cluster in a big square — so
    * it is 10 x 10 with 4 each. The second pass is what keeps a close-up view showing everything,
    * a cluster of mineshafts in one square included, exactly as before.
    */
   public static final int CELLS = 10;
   private static final int CELL_QUOTA = MAX_NEARBY / (CELLS * CELLS);

   /** The widest the map may be ZOOMED OUT, in blocks from its centre. */
   public static final int MAX_REACH = 10_000;

   /**
    * The widest a view may be BUILT, in blocks from its centre: the widest zoom plus the screen's
    * overscan margin (1.44.4). They used to be the same number, so at full zoom-out a view could never
    * be wider than the screen and every drag, however small, uncovered ground no picture had ever
    * covered — the black map.
    */
   public static final int MAX_BUILD_REACH = 15_000;

   /** Structure sets placed this densely (mineshafts, buried treasure: one slot per chunk) are left
    *  off maps wider than {@link #DENSE_SET_REACH}: over a million slots for icons that would cover
    *  the map like noise. */
   private static final int DENSE_SPACING = 4;
   private static final int DENSE_SET_REACH = 1_500;

   /** Y used for every biome sample. */
   private static final int SAMPLE_Y = 64;

   /**
    * AT MOST THIS MANY MAP THREADS WORK AT ONCE, across both pools: every core but one, at most six
    * (1.44.7). The two pools used to be capped separately, so just after a move at full zoom-out —
    * new ground sampling while the last structure checks wound down — the map could briefly want
    * twelve threads, more than the machine had, with the game running behind the N-key map. Each biome
    * row and each structure check now holds one of these permits while it runs; a thread waiting for
    * one uses no CPU. Fair, so new ground waits only for the checks already running to finish their
    * current one, never for a whole batch. The same "one core stays free" rule the search keeps.
    */
   private static final java.util.concurrent.Semaphore CPU = new java.util.concurrent.Semaphore(
         Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() - 1)), true);

   /**
    * True while the map is open in a world you are playing (the N key): map threads then run at the
    * lowest priority, so the game gets the contested cores first. On the Create World screen there is
    * no game to yield to and they run just below normal. Set by the map screen.
    */
   private static volatile boolean inWorld;

   /** Every map thread, so a change of place changes all their priorities at once, idle ones too. */
   private static final java.util.List<Thread> MAP_THREADS = new java.util.concurrent.CopyOnWriteArrayList<>();

   /** Registers a map thread (the pools here, and the model's two build threads) at the current priority. */
   static Thread mapThread(Runnable r, String name) {
      Thread t = new Thread(r, name);
      t.setDaemon(true);
      t.setPriority(priorityNow());
      MAP_THREADS.add(t);
      return t;
   }

   private static int priorityNow() {
      return inWorld ? Thread.MIN_PRIORITY : Thread.NORM_PRIORITY - 1;
   }

   public static void setInWorld(boolean playing) {
      inWorld = playing;
      for (Thread t : MAP_THREADS) {
         t.setPriority(priorityNow());
      }
   }

   private static void cpuTake() {
      CPU.acquireUninterruptibly();
   }

   private static void cpuGive() {
      CPU.release();
   }

   /** How many map threads may work at once (for mapReplay's check of the cap). */
   static int cpuPermits() {
      return Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() - 1));
   }

   /** Permits in use right now: how many map threads are doing work at this instant. */
   static int cpuBusy() {
      return cpuPermits() - CPU.availablePermits();
   }

   /**
    * Threads for the biome grid only, so new ground is never queued behind structure checks that are
    * still winding down after a cancel (1.44.6). Structure checks keep {@link #POOL}. Both draw on
    * {@link #CPU}.
    */
   private static final java.util.concurrent.ExecutorService GROUND_POOL = java.util.concurrent.Executors.newFixedThreadPool(
         Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() - 1)),
         r -> mapThread(r, "FableVision-SeedMap-Ground"));

   /** Threads for the map's biome grid and structure checks: every core but one, at most six. */
   static final int THREADS = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() - 1));

   /**
    * The map's check threads. Everything they touch is read-only world-gen machinery that vanilla
    * itself shares across its worldgen threads (and the search shares across its workers): the chunk
    * generator, the noise state, the biome source, and the template manager, whose cache is a
    * ConcurrentHashMap. Verdicts go into the per-seed {@link Cache}, also concurrent.
    */
   private static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newFixedThreadPool(
         THREADS, new java.util.concurrent.ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
               return mapThread(r, "FableVision-SeedMap-Check-" + n.incrementAndGet());
            }
         });

   /** One structure that is really there. {@code id} is the structure's registry path; {@code detail}
    *  is what an assembled End City or bastion turned out to be (see {@link #detailOf}), else null. */
   public record Nearby(String label, String iconLabel, int x, int z, long distance, String id, int chunkX,
                        int chunkZ, String detail) {}

   /**
    * One dimension's map data. {@code tint} is GRID x GRID colours, row 0 the NORTH edge.
    * {@code structuresDone} is false on the ground-only view handed over before structures are checked;
    * {@code unverifiable} counts structures that could not be checked and were therefore left off.
    * {@code cellFull[i]} is, for a cell (see {@link #CELLS}) that reached its share, the squared distance
    * from the cell's middle of the furthest structure kept there; -1 for a cell that holds all of its own.
    */
   public record View(MapDim dim, int originX, int originZ, int centreX, int centreZ, int reach, int[] tint,
                      short[] cellBiome, String[] biomeNames, List<Nearby> nearby, boolean structuresDone,
                      int unverifiable, boolean capped, long[] cellFull) {
      /** Which of the CELLS x CELLS squares a point is in (clamped to the edge ones). */
      public int cellOf(int x, int z) {
         double size = reach * 2.0 / CELLS;
         int col = (int) Math.max(0, Math.min(CELLS - 1, Math.floor((x - (centreX - reach)) / size)));
         int row = (int) Math.max(0, Math.min(CELLS - 1, Math.floor((z - (centreZ - reach)) / size)));
         return row * CELLS + col;
      }

      public int cellMiddleX(int cell) {
         return (int) Math.round(centreX - reach + (cell % CELLS + 0.5) * reach * 2.0 / CELLS);
      }

      public int cellMiddleZ(int cell) {
         return (int) Math.round(centreZ - reach + (cell / CELLS + 0.5) * reach * 2.0 / CELLS);
      }

      public int cells() {
         return GRID;
      }

      public String biomeAt(int col, int row) {
         if (col < 0 || row < 0 || col >= GRID || row >= GRID) {
            return "";
         }
         return biomeNames[cellBiome[row * GRID + col]];
      }

      View withStructures(List<Nearby> list, boolean done, int unverifiable, boolean capped, long[] full) {
         return new View(dim, originX, originZ, centreX, centreZ, reach, tint, cellBiome, biomeNames, list, done,
               unverifiable, capped, full);
      }
   }

   private static long[] emptyCells() {
      long[] c = new long[CELLS * CELLS];
      java.util.Arrays.fill(c, -1);
      return c;
   }

   /** Builds this seed's Overworld world-gen parts ahead of the first view. */
   public static void warm(WorldgenContext ctx, long seed) {
      Gen.of(ctx, MapDim.OVERWORLD, seed).fullState(ctx, seed);
   }

   /** The game's own spawn pick for this seed. About 11ms; never call on the render thread. */
   public static BlockPos overworldSpawn(WorldgenContext ctx, long seed) {
      cpuTake();   // a spawn search is biome sampling too, so it counts against the map's cap
      try {
         return ctx.randomState(Dim.OVERWORLD, seed).sampler().findSpawnPosition();
      } finally {
         cpuGive();
      }
   }

   /** The middle of this dimension's map: the spawn, where a portal at spawn comes out, or 0, 0. */
   public static int originX(MapDim dim, BlockPos spawn) {
      return dim == MapDim.OVERWORLD ? spawn.getX() : dim == MapDim.NETHER ? spawn.getX() / 8 : 0;
   }

   public static int originZ(MapDim dim, BlockPos spawn) {
      return dim == MapDim.OVERWORLD ? spawn.getZ() : dim == MapDim.NETHER ? spawn.getZ() / 8 : 0;
   }

   public static View build(WorldgenContext ctx, long seed, MapDim dim, BlockPos spawn, int reach) {
      return buildAt(ctx, seed, dim, spawn, originX(dim, spawn), originZ(dim, spawn), reach, () -> false);
   }

   public static View buildAt(WorldgenContext ctx, long seed, MapDim dim, BlockPos spawn, int centreX, int centreZ,
                              int reach) {
      return buildAt(ctx, seed, dim, spawn, centreX, centreZ, reach, () -> false);
   }

   /**
    * Builds one dimension's map centred anywhere: biomes and every structure, finished, in one view.
    * NULL when {@code cancelled} went true at any point — a half-checked view is exactly the
    * "structures trickle in" this replaced, so the caller keeps the whole view it already has and
    * waits for the next whole one. Distances are measured from the dimension's ORIGIN.
    */
   public static View buildAt(WorldgenContext ctx, long seed, MapDim dim, BlockPos spawn, int centreX, int centreZ,
                              int reach, java.util.function.BooleanSupplier cancelled) {
      return buildAt(ctx, seed, dim, spawn, centreX, centreZ, reach, null, cancelled);
   }

   /**
    * As above, and {@code ground}, when given, is handed the biome picture the moment it is ready —
    * before any structure is checked (1.44.5). The whole view still comes back at the end, with every
    * structure at once.
    *
    * WHY THE GROUND GOES FIRST AGAIN. 1.44.3 held the biomes back until the structures were done, so
    * they arrived together. At full zoom-out that is several seconds of checking, and for all of it the
    * ground the player had just dragged onto was black — mapReplay measured 5-6 s of black on screen.
    * The biome picture is ready in about a tenth of a second. What the player asked for was structures
    * that do not trickle in one by one; they still don't.
    */
   public static View buildAt(WorldgenContext ctx, long seed, MapDim dim, BlockPos spawn, int centreX, int centreZ,
                              int reach, java.util.function.Consumer<View> ground,
                              java.util.function.BooleanSupplier cancelled) {
      View base = buildGround(ctx, seed, dim, spawn, centreX, centreZ, reach, cancelled);
      if (base == null || base.structuresDone()) {
         return base;
      }
      if (ground != null) {
         ground.accept(base);
      }
      return addStructures(ctx, seed, base, cancelled);
   }

   /**
    * The GROUND half of a view: the biome picture, no structures. Sampled on its own threads
    * ({@link #GROUND_POOL}), never behind structure checks (1.44.6): a cancelled build stops only
    * between structure checks and one check can take a good part of a second, so new ground that
    * waited for the old checks to wind down was black for that long — mapReplay measured 1.1 s.
    * Null if cancelled. For the End with {@link #END_STRUCTURES} off it comes back already complete.
    */
   public static View buildGround(WorldgenContext ctx, long seed, MapDim dim, BlockPos spawn, int centreX, int centreZ,
                                  int reach, java.util.function.BooleanSupplier cancelled) {
      int ox = originX(dim, spawn);
      int oz = originZ(dim, spawn);
      int r = Math.max(32, Math.min(MAX_BUILD_REACH, reach));
      Gen gen = Gen.of(ctx, dim, seed);
      Map<String, Short> palette = new java.util.LinkedHashMap<>();
      short[] cellBiome = new short[GRID * GRID];
      int[] tint = biomeGrid(gen, centreX, centreZ, r, cellBiome, palette, cancelled);
      if (tint == null) {
         return null;   // the map moved on mid-grid; the caller draws what it already has
      }
      View base = new View(dim, ox, oz, centreX, centreZ, r, tint, cellBiome,
            palette.keySet().stream().map(SeedCatalogCache::prettify).toArray(String[]::new),
            List.of(), false, 0, false, emptyCells());
      if (dim == MapDim.END && !END_STRUCTURES) {
         return base.withStructures(List.of(), true, 0, false, emptyCells());
      }
      return base;
   }

   /** The STRUCTURE half: every structure for a ground view, all at once. Null if cancelled. */
   public static View addStructures(WorldgenContext ctx, long seed, View base, java.util.function.BooleanSupplier cancelled) {
      Gen gen = Gen.of(ctx, base.dim(), seed);
      return nearby(ctx, gen, seed, base, base.originX(), base.originZ(), base.centreX(), base.centreZ(), base.reach(),
            cancelled);
   }

   // ── Per-dimension world-gen parts ─────────────────────────────────────────

   /**
    * Everything one dimension's scan needs, from the same parts the search and a new world use.
    *
    * BUILT ONCE PER SEED AND DIMENSION (1.44.3), not once per view. Every pan and zoom used to rebuild
    * the noise state and vanilla's structure bookkeeping for a seed that had not changed.
    */
   private static final class Gen {
      private static final Map<MapDim, Gen> LAST = new ConcurrentHashMap<>();

      private final WorldgenContext owner;
      private final long seed;
      private final MapDim dim;
      private final BiomeSource biomes;
      private final RandomState climate;
      private final WorldgenContext.Scaffold scaffold;
      private final ChunkGeneratorStructureState state;
      private volatile RandomState full;
      /** Ring positions already snapped to their biome, by set index and position in the ring list. */
      private final Map<Long, ChunkPos> snapped = new ConcurrentHashMap<>();

      private Gen(WorldgenContext owner, long seed, MapDim dim, BiomeSource biomes, RandomState climate,
                  RandomState full, WorldgenContext.Scaffold scaffold, ChunkGeneratorStructureState state) {
         this.owner = owner;
         this.seed = seed;
         this.dim = dim;
         this.biomes = biomes;
         this.climate = climate;
         this.full = full;
         this.scaffold = scaffold;
         this.state = state;
      }

      static Gen of(WorldgenContext ctx, MapDim dim, long seed) {
         Gen last = LAST.get(dim);
         if (last != null && last.owner == ctx && last.seed == seed) {
            return last;
         }
         Gen made;
         if (dim == MapDim.END) {
            RandomState rs = ctx.endRandomState(seed);
            made = new Gen(ctx, seed, dim, ctx.endBiomes, rs, rs, ctx.endScaffold(), ctx.endStructureState(seed, rs));
         } else {
            Dim d = dim.search();
            RandomState climate = ctx.randomState(d, seed);
            made = new Gen(ctx, seed, dim, ctx.biomeSource(d), climate, null, ctx.scaffold(d),
                  ctx.structureState(d, seed, climate));
         }
         LAST.put(dim, made);
         return made;
      }

      MapDim dim() {
         return dim;
      }

      BiomeSource biomes() {
         return biomes;
      }

      RandomState climate() {
         return climate;
      }

      WorldgenContext.Scaffold scaffold() {
         return scaffold;
      }

      ChunkGeneratorStructureState state() {
         return state;
      }

      /** The full noise state, built on first need and then shared by every check thread. */
      RandomState fullState(WorldgenContext ctx, long seed) {
         RandomState f = full;
         if (f == null) {
            synchronized (this) {
               f = full;
               if (f == null) {
                  f = ctx.newFullRandomState(dim.search(), seed);
                  full = f;
               }
            }
         }
         return f;
      }

      /**
       * The ring positions (strongholds) that can fall inside this square, exactly where vanilla puts
       * them — without paying for all 128.
       *
       * VANILLA'S OWN STEPS, replayed ({@code ChunkGeneratorStructureState.generateRingPositions}): one
       * random stream seeded with the world seed gives every position's rough spot in chunks, and each
       * position then moves to a preferred biome up to 112 blocks away, using a random FORKED off that
       * stream. The rough spots are nearly free; the biome search is the expensive part, and vanilla does
       * it for all 128 at once — about a second, which made any map near the first ring slow. Here every
       * rough spot is still worked out, so the stream stays in step, but only the ones that could land in
       * view are searched. The fork is still taken for every position, because taking it advances the
       * stream. mapTruthDiag compares the result with the game's own ring list.
       */
      List<ChunkPos> ringsNear(ConcentricRingsStructurePlacement ring, int setIndex, int cx, int cz, int reach) {
         int count = ring.count();
         List<ChunkPos> out = new ArrayList<>();
         if (count == 0) {
            return out;
         }
         int distance = ring.distance();
         int spread = ring.spread();
         net.minecraft.core.HolderSet<Biome> preferred = ring.preferredBiomes();
         net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create();
         random.setSeed(seed);
         double angle = random.nextDouble() * Math.PI * 2.0;
         int inCircle = 0;
         int circle = 0;
         int slack = reach + 112 + 16;
         for (int i = 0; i < count; i++) {
            double dist = (double) (4 * distance + distance * circle * 6) + (random.nextDouble() - 0.5) * (double) distance * 2.5;
            int initialX = (int) Math.round(Math.cos(angle) * dist);
            int initialZ = (int) Math.round(Math.sin(angle) * dist);
            net.minecraft.util.RandomSource biomeRandom = random.fork();
            int bx = net.minecraft.core.SectionPos.sectionToBlockCoord(initialX, 8);
            int bz = net.minecraft.core.SectionPos.sectionToBlockCoord(initialZ, 8);
            if (Math.abs(bx - cx) <= slack && Math.abs(bz - cz) <= slack) {
               long key = (long) setIndex << 32 | i;
               ChunkPos at = snapped.get(key);
               if (at == null) {
                  // Under the cap like every other piece of map work: mapReplay found this biome search
                  // running on the build thread outside it, one thread over cores - 1.
                  cpuTake();
                  net.minecraft.world.level.biome.Climate.Sampler s2 = climate.sampler();
                  com.mojang.datafixers.util.Pair<BlockPos, Holder<Biome>> found;
                  try {
                     found = biomes.findBiomeHorizontal(bx, 0, bz, 112, preferred::contains, biomeRandom, s2);
                  } finally {
                     cpuGive();
                  }
                  at = found != null
                        ? new ChunkPos(net.minecraft.core.SectionPos.blockToSectionCoord(found.getFirst().getX()),
                              net.minecraft.core.SectionPos.blockToSectionCoord(found.getFirst().getZ()))
                        : new ChunkPos(initialX, initialZ);
                  snapped.put(key, at);
               }
               out.add(at);
            }
            angle += Math.PI * 2.0 / (double) spread;
            if (++inCircle == spread) {
               circle++;
               inCircle = 0;
               spread += 2 * spread / (circle + 1);
               spread = Math.min(spread, count - i);
               angle += random.nextDouble() * Math.PI * 2.0;
            }
         }
         return out;
      }
   }

   // ── The ground ───────────────────────────────────────────────────────────

   /** The biome colour of every cell, sampled at the middle of the cell. */
   private static int[] biomeGrid(Gen gen, int ox, int oz, int reach, short[] cellBiome, Map<String, Short> palette,
                                  java.util.function.BooleanSupplier cancelled) {
      Climate.Sampler sampler = gen.climate().sampler();
      int[] out = new int[GRID * GRID];
      Map<Holder<Biome>, Integer> seen = new IdentityHashMap<>();
      double step = (reach * 2.0) / GRID;
      int quartY = QuartPos.fromBlock(SAMPLE_Y);
      // SAMPLED IN PARALLEL (1.44.3): rows are dealt out across the check threads, then coloured in
      // order below, so the palette comes out exactly as it did from the one-thread loop.
      @SuppressWarnings("unchecked")
      Holder<Biome>[] cells = new Holder[GRID * GRID];
      java.util.concurrent.atomic.AtomicInteger nextRow = new java.util.concurrent.atomic.AtomicInteger();
      boolean finished = runOnPool(GROUND_POOL, () -> {
         for (int row = nextRow.getAndIncrement(); row < GRID; row = nextRow.getAndIncrement()) {
            // CHECKED PER ROW, because a drag asks for new ground several times a second and each grid
            // is 16,384 climate samples. Without this, every abandoned pan still ran to the end before
            // the one the player is waiting for could start.
            if (cancelled.getAsBoolean()) {
               return;
            }
            int bz = (int) Math.round(oz - reach + (row + 0.5) * step);
            int qz = QuartPos.fromBlock(bz);
            cpuTake();
            try {
               for (int col = 0; col < GRID; col++) {
                  int bx = (int) Math.round(ox - reach + (col + 0.5) * step);
                  cells[row * GRID + col] = gen.biomes().getNoiseBiome(QuartPos.fromBlock(bx), quartY, qz, sampler);
               }
            } finally {
               cpuGive();
            }
         }
      });
      if (cancelled.getAsBoolean()) {
         return null;
      }
      if (!finished) {
         throw new IllegalStateException("a map check thread failed while sampling biomes");
      }
      for (int row = 0; row < GRID; row++) {
         for (int col = 0; col < GRID; col++) {
            Holder<Biome> here = cells[row * GRID + col];
            Integer colour = seen.get(here);
            if (colour == null) {
               colour = BiomeTint.of(pathOf(here));
               seen.put(here, colour);
            }
            out[row * GRID + col] = colour;
            String path = pathOf(here);
            Short index = palette.get(path);
            if (index == null) {
               index = (short) palette.size();
               palette.put(path, index);
            }
            cellBiome[row * GRID + col] = index;
         }
      }
      return out;
   }

   /**
    * Runs {@code work} on every check thread at once and waits for all of them. The work pulls its own
    * items off a shared counter, so one slow item never leaves the other threads idle. False if a
    * thread failed or the wait was interrupted.
    */
   private static boolean runOnPool(Runnable work) {
      return runOnPool(POOL, work);
   }

   private static boolean runOnPool(java.util.concurrent.ExecutorService pool, Runnable work) {
      List<java.util.concurrent.Future<?>> running = new ArrayList<>(THREADS);
      for (int i = 0; i < THREADS; i++) {
         running.add(pool.submit(work));
      }
      boolean ok = true;
      for (java.util.concurrent.Future<?> f : running) {
         try {
            f.get();
         } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
            ok = false;
         } catch (java.util.concurrent.ExecutionException failed) {
            FableVisionClient.LOGGER.warn("Seed map check thread failed", failed.getCause());
            ok = false;
         }
      }
      return ok;
   }

   /** The biome name at one point, for the "you spawn in a …" line. */
   public static String biomeNameAt(WorldgenContext ctx, Dim dim, long seed, int x, int z) {
      Holder<Biome> here = ctx.biomeSource(dim).getNoiseBiome(QuartPos.fromBlock(x),
            QuartPos.fromBlock(SAMPLE_Y), QuartPos.fromBlock(z), ctx.randomState(dim, seed).sampler());
      return SeedCatalogCache.prettify(pathOf(here));
   }

   private static String pathOf(Holder<Biome> b) {
      return b.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
   }

   // ── What else is around ──────────────────────────────────────────────────

   /** One slot to check: a chunk some set's placement picked, and which set. */
   private record Slot(Holder.Reference<StructureSet> set, int setIndex, ChunkPos chunk, BlockPos locate,
                       long d2) {}

   /**
    * Whether a structure set can build anything in this map tab: true when ANY of its structures uses
    * one of the tab's biomes.
    *
    * "Any", not "the first", and that is a fix. The ruined-portal set holds six overworld variants and
    * one Nether one; the catalog's rule files a set under the first dimension it matches, which put
    * every Nether ruined portal on the overworld's list and left the Nether tab without them.
    * mapTruthDiag found it as a steady handful of "missed" Nether portals. The replay in
    * {@link #verify} is what keeps the other dimension's variants out: their biome lists cannot match.
    */
   static boolean builtIn(WorldgenContext ctx, Holder.Reference<StructureSet> setRef, MapDim dim, BiomeSource biomes) {
      java.util.Set<Holder<Biome>> possible = biomes.possibleBiomes();
      for (StructureSet.StructureSelectionEntry e : setRef.value().structures()) {
         for (Holder<Biome> b : e.structure().value().biomes()) {
            if (possible.contains(b)) {
               return true;
            }
         }
      }
      return false;
   }

   /** Heights the cheap biome gate samples, per tab: the whole column, not just the surface. */
   private static int[] gateHeights(MapDim dim) {
      return switch (dim) {
         case OVERWORLD -> new int[]{-52, -24, 8, 40, 64, 120};
         case NETHER -> new int[]{12, 36, 64, 96, 116};
         case END -> new int[]{64};
      };
   }

   /**
    * True for structures the map leaves off on purpose at this width, so a check can tell a deliberate
    * omission from a miss: the one-per-chunk sets when the map is wide.
    *
    * DERIVED FROM THE SAME RULE THE SCAN USES, not listed by name. It used to name mineshafts and
    * buried treasure, and the scan's actual rule is "any set spaced {@link #DENSE_SPACING} chunks or
    * closer" — which also covers nether fossils. A wide mapTruthDiag run then reported sixteen thousand
    * fossils as "missed" that the map had never been meant to draw.
    */
   public static boolean skippedOnPurpose(WorldgenContext ctx, String id, int reach) {
      // STRONGHOLDS ARE DRAWN SINCE 1.44.3, from vanilla's own ring positions, so they are not an
      // omission any more and mapTruthDiag holds them to the game like everything else.
      if (reach <= DENSE_SET_REACH) {
         return false;
      }
      for (Holder.Reference<StructureSet> setRef : ctx.allSets) {
         if (setRef.value().placement() instanceof RandomSpreadStructurePlacement spread
               && spread.spacing() <= DENSE_SPACING) {
            for (StructureSet.StructureSelectionEntry e : setRef.value().structures()) {
               if (e.structure().unwrapKey().map(k -> k.identifier().getPath()).orElse("").equals(id)) {
                  return true;
               }
            }
         }
      }
      return false;
   }

   /**
    * Every structure this dimension really generates in the square the map shows, up to
    * {@link #MAX_NEARBY} — an equal share per cell ({@link #CELLS}), nearest each cell's middle first.
    *
    * Walks the structure SETS the world has (so a datapack's structure appears as itself), placement
    * arithmetic first, then the verdict from {@link #verify}.
    */
   private static View nearby(WorldgenContext ctx, Gen gen, long seed, View base, int ox, int oz, int cx, int cz,
                              int reach, java.util.function.BooleanSupplier cancelled) {
      int chunkR = Math.max(1, reach >> 4);
      int centreChunkX = cx >> 4;
      int centreChunkZ = cz >> 4;

      // 1. Every slot in the square — arithmetic only.
      List<Slot> slots = new ArrayList<>();
      for (int si = 0; si < ctx.allSets.size(); si++) {
         Holder.Reference<StructureSet> setRef = ctx.allSets.get(si);
         StructureSet set = setRef.value();
         if (!builtIn(ctx, setRef, gen.dim(), gen.biomes())) {
            continue;
         }
         // RING-PLACED SETS (strongholds): vanilla's own ring positions, the list /locate and world-gen
         // read — only the ones that can land near this square are worked out (Gen.ringsNear).
         if (set.placement() instanceof ConcentricRingsStructurePlacement ring) {
            for (ChunkPos chunk : gen.ringsNear(ring, si, cx, cz, reach)) {
               if (Math.abs(chunk.getMiddleBlockX() - cx) > reach || Math.abs(chunk.getMiddleBlockZ() - cz) > reach) {
                  continue;
               }
               BlockPos locate = set.placement().getLocatePos(chunk);
               long ldx = locate.getX() - cx;
               long ldz = locate.getZ() - cz;
               slots.add(new Slot(setRef, si, chunk, locate, ldx * ldx + ldz * ldz));
            }
            continue;
         }
         if (!(set.placement() instanceof RandomSpreadStructurePlacement spread)) {
            continue;
         }
         int spacing = spread.spacing();
         if (spacing <= DENSE_SPACING && reach > DENSE_SET_REACH) {
            continue;
         }
         for (int rx = Math.floorDiv(centreChunkX - chunkR, spacing); rx <= Math.floorDiv(centreChunkX + chunkR, spacing); rx++) {
            for (int rz = Math.floorDiv(centreChunkZ - chunkR, spacing); rz <= Math.floorDiv(centreChunkZ + chunkR, spacing); rz++) {
               ChunkPos chunk = spread.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
               long dx = chunk.getMiddleBlockX() - cx;
               long dz = chunk.getMiddleBlockZ() - cz;
               if (Math.abs(dx) > reach || Math.abs(dz) > reach) {
                  continue;
               }
               if (!spread.applyAdditionalChunkRestrictions(chunk.x(), chunk.z(), seed)) {
                  continue;
               }
               BlockPos locate = set.placement().getLocatePos(chunk);
               long ldx = locate.getX() - cx;
               long ldz = locate.getZ() - cz;
               slots.add(new Slot(setRef, si, chunk, locate, ldx * ldx + ldz * ldz));
            }
         }
      }
      // Each slot's cell, and its distance from THAT cell's middle. Sorting on that distance interleaves
      // the cells by itself: every cell's nearest slot comes before any cell's tenth.
      int nSlots = slots.size();
      int[] cellOf = new int[nSlots];
      long[] cellD2 = new long[nSlots];
      for (int i = 0; i < nSlots; i++) {
         Slot sl = slots.get(i);
         int cell = base.cellOf(sl.locate().getX(), sl.locate().getZ());
         long dx = sl.locate().getX() - base.cellMiddleX(cell);
         long dz = sl.locate().getZ() - base.cellMiddleZ(cell);
         cellOf[i] = cell;
         cellD2[i] = dx * dx + dz * dz;
      }
      Integer[] order = new Integer[nSlots];
      for (int i = 0; i < nSlots; i++) {
         order[i] = i;
      }
      java.util.Arrays.sort(order, Comparator.comparingLong((Integer i) -> cellD2[i]));
      List<Slot> sorted = new ArrayList<>(nSlots);
      int[] sortedCell = new int[nSlots];
      long[] sortedD2 = new long[nSlots];
      for (int i = 0; i < nSlots; i++) {
         sorted.add(slots.get(order[i]));
         sortedCell[i] = cellOf[order[i]];
         sortedD2[i] = cellD2[order[i]];
      }
      slots = sorted;

      // 2. The verdicts, nearest first per cell, IN PARALLEL a batch at a time. A slot in a cell that
      //    already has its share is not checked at all.
      Cache cache = Cache.forSeed(ctx, seed, gen.dim());
      StructureTemplateManager templates = VERIFY ? VillageLayout.templates() : null;
      final List<Slot> work = slots;
      Object[] verdicts = new Object[nSlots];
      List<Nearby> out = new ArrayList<>();
      int unverifiable = 0;
      int[] perCell = new int[CELLS * CELLS];
      boolean[] taken = new boolean[nSlots];   // looked at in pass 1 (kept, empty, or not there)
      long[] full = emptyCells();
      int cellsFull = 0;
      int batch = Math.max(64, THREADS * 32);
      for (int from = 0; from < nSlots && cellsFull < CELLS * CELLS; from += batch) {
         final int start = from;
         final int end = Math.min(nSlots, from + batch);
         final int[] countNow = perCell.clone();
         java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger(start);
         boolean finished = runOnPool(() -> {
            for (int i = next.getAndIncrement(); i < end; i = next.getAndIncrement()) {
               if (cancelled.getAsBoolean()) {
                  return;
               }
               if (countNow[sortedCell[i]] >= CELL_QUOTA) {
                  continue;   // that cell already has its share
               }
               // THE WHOLE ITEM HOLDS A PERMIT, the cache lookup too: ground already checked is a
               // tight loop of lookups, and outside the cap that loop ran on every thread at once.
               cpuTake();
               try {
                  Slot slot = work.get(i);
                  Object verdict = cache.get(slot);
                  if (verdict == null) {
                     verdict = VERIFY ? verify(ctx, gen, seed, slot, templates) : oldCheck(gen, seed, slot);
                     // "Could not ask" is not an answer, so it is not remembered (1.44.4). It used to be,
                     // and a slot met while the structure files were still loading stayed unchecked —
                     // undrawn — for that seed for the rest of the session.
                     if (verdict != UNKNOWN) {
                        cache.put(slot, verdict);
                     }
                  }
                  verdicts[i] = verdict;
               } finally {
                  cpuGive();
               }
            }
         });
         if (cancelled.getAsBoolean()) {
            return null;   // the map moved on; the caller keeps the whole view it already has
         }
         // A FAILED THREAD IS NOT A CANCEL (1.44.4). Both used to return null, and null means "the
         // map moved on, keep what you have" — so a failure left the screen stale and silent, with
         // nothing to ask again. Thrown, it is logged, shown, and retried by the screen.
         if (!finished) {
            throw new IllegalStateException("a map check thread failed while checking structures");
         }
         for (int i = start; i < end; i++) {
            int cell = sortedCell[i];
            if (perCell[cell] >= CELL_QUOTA || verdicts[i] == null) {
               continue;
            }
            taken[i] = true;
            Object verdict = verdicts[i];
            if (verdict == UNKNOWN) {
               unverifiable++;
               continue;
            }
            Nearby found = toNearby(work.get(i), verdict, ox, oz);
            if (found == null) {
               continue;
            }
            out.add(found);
            if (++perCell[cell] == CELL_QUOTA) {
               full[cell] = sortedD2[i];
               cellsFull++;
            }
         }
      }
      // PASS 2: budget left over (a close-up view, or a mostly empty ocean) goes to the slots pass 1
      // skipped for being over their square's share, nearest their square's middle first. If this
      // uses up every slot, nothing was really left off, and the view says so.
      boolean capped = false;
      if (cellsFull > 0) {
         List<Integer> skipped = new ArrayList<>();
         for (int i = 0; i < nSlots; i++) {
            if (!taken[i]) {
               skipped.add(i);
            }
         }
         for (int from = 0; from < skipped.size(); from += batch) {
            if (out.size() >= MAX_NEARBY) {
               capped = true;
               break;
            }
            final int end = Math.min(skipped.size(), from + batch);
            final int first = from;
            java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger(first);
            boolean finished = runOnPool(() -> {
               for (int k = next.getAndIncrement(); k < end; k = next.getAndIncrement()) {
                  if (cancelled.getAsBoolean()) {
                     return;
                  }
                  int i = skipped.get(k);
                  if (verdicts[i] != null) {
                     continue;
                  }
                  cpuTake();
                  try {
                     Slot slot = work.get(i);
                     Object verdict = cache.get(slot);
                     if (verdict == null) {
                        verdict = VERIFY ? verify(ctx, gen, seed, slot, templates) : oldCheck(gen, seed, slot);
                        if (verdict != UNKNOWN) {
                           cache.put(slot, verdict);
                        }
                     }
                     verdicts[i] = verdict;
                  } finally {
                     cpuGive();
                  }
               }
            });
            if (cancelled.getAsBoolean()) {
               return null;
            }
            if (!finished) {
               throw new IllegalStateException("a map check thread failed while checking structures");
            }
            for (int k = first; k < end; k++) {
               if (out.size() >= MAX_NEARBY) {
                  capped = true;
                  break;
               }
               int i = skipped.get(k);
               Nearby n = toNearby(work.get(i), verdicts[i], ox, oz);
               if (verdicts[i] == UNKNOWN) {
                  unverifiable++;
               }
               if (n != null) {
                  out.add(n);
               }
            }
         }
      }
      if (!capped) {
         full = emptyCells();   // every structure in the square is on the map
      }
      return base.withStructures(List.copyOf(out), true, unverifiable, capped, full);
   }

   /** The marker for a slot the game really builds on, or null for an empty, unknown or unnamed one. */
   private static Nearby toNearby(Slot slot, Object verdict, int ox, int oz) {
      if (!(verdict instanceof Won won)) {
         return null;
      }
      Identifier id = won.structure().unwrapKey().map(ResourceKey::identifier).orElse(null);
      if (id == null) {
         return null;
      }
      long ax = slot.locate().getX() - (long) ox;
      long az = slot.locate().getZ() - (long) oz;
      Kind kind = kindOf(id.getPath(), won.detail());
      return new Nearby(kind.display(), kind.iconLabel(), slot.locate().getX(), slot.locate().getZ(),
            Math.round(Math.sqrt(ax * ax + az * az)), id.getPath(), slot.chunk().x(), slot.chunk().z(),
            won.detail());
   }

   private static final Object NONE = new Object();
   private static final Object UNKNOWN = new Object();

   /**
    * A structure that is really there, and — for the two kinds whose name depends on what was built —
    * what it built. {@code detail} is null for everything else.
    *
    *   end_city        -> "ship" or "noship"
    *   bastion_remnant -> "treasure", "bridge", "hoglin_stable" or "units"
    */
   private record Won(Holder<Structure> structure, String detail) {}

   /** Structures whose map label needs the ASSEMBLED building, not just "is it here". */
   private static boolean needsAssembly(Holder<Structure> s) {
      String path = s.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
      return path.equals("end_city") || path.equals("bastion_remnant");
   }

   /**
    * WHAT THE GAME BUILDS IN THIS SLOT: a {@link Won}, {@link #NONE}, or {@link #UNKNOWN} when the
    * question could not be asked (and then nothing is drawn).
    *
    * This is vanilla's structure step for one set, replayed: the same exclusion zones, the same seeded
    * weighted roll, and for each rolled variant the same question the game asks before building —
    * {@code findValidGenerationPoint} with the structure's own biome list, at the real start point.
    * A variant that fails is dropped and the roll repeats, exactly as the game does.
    *
    * One cheap gate first ({@link #anyCould}); it can hide a structure but never draw one.
    */
   private static Object verify(WorldgenContext ctx, Gen gen, long seed, Slot slot, StructureTemplateManager templates) {
      StructureSet set = slot.set().value();
      StructurePlacement placement = set.placement();
      if (!placement.applyInteractionsWithOtherStructures(gen.state(), slot.chunk().x(), slot.chunk().z())) {
         return NONE;
      }
      List<StructureSet.StructureSelectionEntry> entries = new ArrayList<>(set.structures());
      if (!anyCould(gen, slot, entries)) {
         return NONE;
      }
      if (templates == null) {
         return UNKNOWN;
      }
      RandomState full = gen.fullState(ctx, seed);
      if (entries.size() == 1) {
         return tryStructure(ctx, gen, full, templates, seed, slot.chunk(), entries.get(0).structure());
      }
      WorldgenRandom rand = new WorldgenRandom(new LegacyRandomSource(0L));
      rand.setLargeFeatureSeed(seed, slot.chunk().x(), slot.chunk().z());
      int total = 0;
      for (StructureSet.StructureSelectionEntry e : entries) {
         total += e.weight();
      }
      while (!entries.isEmpty() && total > 0) {
         int roll = rand.nextInt(total);
         int idx = 0;
         for (StructureSet.StructureSelectionEntry e : entries) {
            roll -= e.weight();
            if (roll < 0) {
               break;
            }
            idx++;
         }
         StructureSet.StructureSelectionEntry chosen = entries.get(idx);
         Object here = tryStructure(ctx, gen, full, templates, seed, slot.chunk(), chosen.structure());
         if (here != NONE) {
            return here;   // a Won, or UNKNOWN
         }
         total -= chosen.weight();
         entries.remove(idx);
      }
      return NONE;
   }

   /**
    * THE CHEAP GATE: could any variant in this set possibly start here? Samples the biome down the
    * whole column, at the slot's locate point, its middle and four points {@link #GATE_REACH} out, and
    * says yes if any sample is on any variant's list. A no skips the expensive check.
    *
    * It can only ever HIDE a structure, never draw one, and it cannot change which variant wins: it
    * decides only whether the replay runs at all. Up to the first cut of 1.43.1 it sampled the surface
    * only, and mapTruthDiag counted what that cost: underground and Nether structures (a fossil sitting
    * in a different biome from the one at y=64) went missing. Sampling the column fixed those at a
    * fraction of the cost of dropping the gate.
    */
   /**
    * How far from the chunk's middle the gate also samples: 24 blocks each way (1.44.3). It sampled the
    * middle only, and a jigsaw structure's biome is checked at its start piece's centre, which can sit
    * past the chunk edge — mapTruthDiag found villages, outposts and trail ruins missed that way.
    */
   private static final int GATE_REACH = 24;

   private static boolean anyCould(Gen gen, Slot slot, List<StructureSet.StructureSelectionEntry> entries) {
      Climate.Sampler sampler = gen.climate().sampler();
      int mx = slot.chunk().getMiddleBlockX();
      int mz = slot.chunk().getMiddleBlockZ();
      int[] xs = {slot.locate().getX(), mx, mx - GATE_REACH, mx + GATE_REACH, mx - GATE_REACH, mx + GATE_REACH};
      int[] zs = {slot.locate().getZ(), mz, mz - GATE_REACH, mz - GATE_REACH, mz + GATE_REACH, mz + GATE_REACH};
      for (int i = 0; i < xs.length; i++) {
         for (int y : gateHeights(gen.dim())) {
            Holder<Biome> b = gen.biomes().getNoiseBiome(QuartPos.fromBlock(xs[i]), QuartPos.fromBlock(y),
                  QuartPos.fromBlock(zs[i]), sampler);
            for (StructureSet.StructureSelectionEntry e : entries) {
               if (e.structure().value().biomes().contains(b)) {
                  return true;
               }
            }
         }
      }
      return false;
   }

   /**
    * The game's own answer for one structure in one chunk: a {@link Won}, {@link #NONE}, or
    * {@link #UNKNOWN} when the question could not be asked.
    *
    * For most structures this is {@code findValidGenerationPoint} — the real start point and the
    * biome there — which is the part of the game's structure step that decides whether anything is
    * built. For End Cities and bastions the map's LABEL depends on what was built (a ship or not; which
    * of the four bastion types), so those two are assembled with the game's own {@code generate}. That
    * is the same test plus the pieces, not a different test: generate runs findValidGenerationPoint
    * first and builds only if it passes, so the yes/no answer cannot differ.
    */
   private static Object tryStructure(WorldgenContext ctx, Gen gen, RandomState full, StructureTemplateManager templates,
                                      long seed, ChunkPos chunk, Holder<Structure> structure) {
      try {
         // A COLD BIOME LOOKUP, whatever the gate just sampled on this thread (see forgetBiomeHint).
         if (gen.dim().search() != null) {
            ctx.forgetBiomeHint(gen.dim().search());
         }
         java.util.function.Predicate<Holder<Biome>> ownBiomes = h -> structure.value().biomes().contains(h);
         Structure.GenerationContext context = new Structure.GenerationContext(
               (RegistryAccess) ctx.source, gen.scaffold().generator(), gen.biomes(), full, templates, seed, chunk,
               gen.scaffold().height(), ownBiomes);
         var stub = structure.value().findValidGenerationPoint(context);
         // THE TIE RULE (1.44.4), shared with the search: drawn only if the biome test holds whichever
         // way an exact biome tie at the start point falls — the game itself can go either way there.
         // The End's biome source has no climate points, so no ties.
         if (stub.isEmpty() || gen.dim().search() != null
               && !VillageLayout.biomeHolds(ctx, gen.dim().search(), stub.get().position(), full, ownBiomes)) {
            return NONE;
         }
         if (!needsAssembly(structure)) {
            return new Won(structure, null);
         }
         net.minecraft.world.level.levelgen.structure.StructureStart start = structure.value().generate(
               structure, gen.dim().levelKey(), (RegistryAccess) ctx.source, gen.scaffold().generator(),
               gen.biomes(), full, templates, seed, chunk, 0, gen.scaffold().height(), ownBiomes);
         if (start == null || !start.isValid()) {
            return NONE;
         }
         return new Won(structure, detailOf(structure, start));
      } catch (Throwable t) {
         FableVisionClient.LOGGER.debug("Map: generation check failed at {}", chunk, t);
         return UNKNOWN;
      }
   }

   /**
    * What an assembled End City or bastion turned out to be, read from its own piece list.
    *
    * An End City's ship is the piece named {@code end_city/ship}; a bastion's type is the folder every
    * one of its pieces comes out of (bastion/treasure, /bridge, /hoglin_stable, /units — the last is
    * the one people call "generic"). The same readings the search's own rows use, so the map and a
    * search can never disagree about which kind a building is. Null when the pieces carry no names,
    * in which case the plain label is shown rather than a guessed one.
    */
   static String detailOf(Holder<Structure> structure, net.minecraft.world.level.levelgen.structure.StructureStart start) {
      String path = structure.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
      List<Identifier> ids = VillageLayout.templateIds(start.getPieces());
      if (ids.isEmpty()) {
         return null;
      }
      if (path.equals("end_city")) {
         for (Identifier id : ids) {
            if (id.getPath().equals("end_city/ship")) {
               return "ship";
            }
         }
         return "noship";
      }
      if (path.equals("bastion_remnant")) {
         for (Identifier id : ids) {
            for (String type : new String[]{"treasure", "bridge", "hoglin_stable", "units"}) {
               if (id.getPath().startsWith("bastion/" + type + "/")) {
                  return type;
               }
            }
         }
      }
      return null;
   }

   /** The 1.43.0 test (placement plus the surface biome), kept only so mapTruthDiag can show the
    *  difference. */
   private static Object oldCheck(Gen gen, long seed, Slot slot) {
      StructureSet set = slot.set().value();
      if (!set.placement().applyInteractionsWithOtherStructures(gen.state(), slot.chunk().x(), slot.chunk().z())) {
         return NONE;
      }
      StructureSet.StructureSelectionEntry won = SeedCriteria.pickWinner(seed, slot.chunk(), set, gen.biomes(),
            gen.climate().sampler(), slot.locate(), SAMPLE_Y);
      return won == null ? NONE : new Won(won.structure(), null);
   }

   /**
    * Verdicts already worked out for one seed and dimension, so panning back over checked ground is
    * free. One seed at a time per dimension; bounded so a long session cannot grow it without limit.
    */
   private static final class Cache {
      private static final Map<MapDim, Cache> BY_DIM = new ConcurrentHashMap<>();
      private static final int LIMIT = 200_000;

      private final WorldgenContext owner;
      private final long seed;
      private final Map<SlotKey, Object> verdicts = new ConcurrentHashMap<>();
      private final boolean verified = VERIFY;

      private Cache(WorldgenContext owner, long seed) {
         this.owner = owner;
         this.seed = seed;
      }

      static Cache forSeed(WorldgenContext ctx, long seed, MapDim dim) {
         Cache c = BY_DIM.get(dim);
         if (c == null || c.owner != ctx || c.seed != seed || c.verified != VERIFY || c.verdicts.size() > LIMIT) {
            c = new Cache(ctx, seed);
            BY_DIM.put(dim, c);
         }
         return c;
      }

      /**
       * The set and the chunk, kept apart. The key used to be {@code setIndex << 48 ^ pack(x, z)},
       * and pack puts z in the top 32 bits — so the set index overwrote z's high bits, and two slots of
       * different sets about a million blocks apart could share one key and one verdict. Unreachable
       * in normal play, but a map should not be able to name the wrong structure at all.
       */
      private record SlotKey(int set, int x, int z) {}

      private static SlotKey key(Slot s) {
         return new SlotKey(s.setIndex(), s.chunk().x(), s.chunk().z());
      }

      Object get(Slot s) {
         return verdicts.get(key(s));
      }

      void put(Slot s, Object verdict) {
         verdicts.put(key(s), verdict);
      }
   }

   // ── Names and pictures ───────────────────────────────────────────────────

   /** What to call a structure on the map, and which catalog row to borrow its icon from. */
   private record Kind(String display, String iconLabel) {}

   private static Kind kindOf(String path, String detail) {
      String p = path.toLowerCase(Locale.ROOT);
      // THE TWO WHOSE NAME IS WHAT WAS BUILT. Both read from the assembled piece list (detailOf), and
      // named with the picker's own row labels so each gets that row's icon. A null detail (pieces with
      // no names) keeps the plain label below rather than a guess.
      if (p.equals("end_city") && detail != null) {
         return detail.equals("ship")
               ? new Kind("End City with Ship", "End City with Ship")
               : new Kind("End City (no ship)", "End City");
      }
      if (p.equals("bastion_remnant") && detail != null) {
         String label = switch (detail) {
            case "treasure" -> "Treasure Bastion";
            case "bridge" -> "Bridge Bastion";
            case "hoglin_stable" -> "Hoglin Stable Bastion";
            case "units" -> "Housing Units Bastion";
            default -> "Bastion Remnant";
         };
         return new Kind(label, label);
      }
      if (p.startsWith("village")) {
         return new Kind("Village", "Surface Village");
      }
      if (p.startsWith("ruined_portal")) {
         return new Kind("Ruined Portal", "Ruined Portal");
      }
      if (p.startsWith("mineshaft")) {
         return new Kind("Mineshaft", "Mineshaft");
      }
      return switch (p) {
         case "pillager_outpost" -> new Kind("Pillager Outpost", "Pillager Outpost");
         case "desert_pyramid" -> new Kind("Desert Pyramid", "Desert Pyramid");
         case "jungle_pyramid" -> new Kind("Jungle Temple", "Jungle Pyramid");
         case "mansion" -> new Kind("Woodland Mansion", "Woodland Mansion");
         case "igloo" -> new Kind("Igloo", "Igloo");
         case "swamp_hut" -> new Kind("Witch Hut", "Swamp Hut");
         case "monument" -> new Kind("Ocean Monument", "Ocean Monument");
         case "shipwreck" -> new Kind("Shipwreck", "Shipwreck");
         // The plain boat: a beached wreck is not capsized, and borrowing that row's icon said it was.
         case "shipwreck_beached" -> new Kind("Beached Shipwreck", "Shipwreck");
         case "ocean_ruin_cold" -> new Kind("Ocean Ruins", "Cold Ocean Ruins");
         case "ocean_ruin_warm" -> new Kind("Ocean Ruins", "Warm Ocean Ruins");
         case "buried_treasure" -> new Kind("Buried Treasure", "Buried Treasure");
         case "trail_ruins" -> new Kind("Trail Ruins", "Trail Ruins");
         case "trial_chambers" -> new Kind("Trial Chamber", "Trial Chambers");
         case "ancient_city" -> new Kind("Ancient City", "Ancient City");
         case "desert_well" -> new Kind("Desert Well", "Desert Well");
         case "fortress" -> new Kind("Nether Fortress", "Nether Fortress");
         case "bastion_remnant" -> new Kind("Bastion Remnant", "Bastion Remnant");
         case "nether_fossil" -> new Kind("Nether Fossil", "Nether Fossil");
         case "stronghold" -> new Kind("Stronghold", "Stronghold");
         case "end_city" -> new Kind("End City", "End City");
         default -> {
            String pretty = SeedCatalogCache.prettify(p);
            yield new Kind(pretty, pretty);
         }
      };
   }

   private SeedMapData() {
   }
}
