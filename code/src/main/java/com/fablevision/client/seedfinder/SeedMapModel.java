package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import com.fablevision.client.FableVisionClient;
import com.fablevision.client.seedfinder.SeedMapData.MapDim;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;

/**
 * Everything the seed map DOES, without drawing it: the camera, which view to ask for and when, the
 * pictures kept on screen, and the structures remembered.
 *
 * SPLIT OUT OF SeedMapScreen IN 1.44.5 so it can be driven without a game. Two rounds of "fixed" map
 * staleness were fixed against numbers about the BUILD (how long, how fast it stops) while the bug was
 * in what the SCREEN did with builds, which no headless check could see. {@code mapReplay} now plays
 * a player's exact moves against this class — real builds, real threads, real time — and measures
 * what would be on screen after every frame. The screen only paints what this says.
 *
 * Threading: the screen (or the replay) calls everything here from one thread; builds run on
 * {@link #WORKER} and hand results back through the volatile/concurrent fields.
 */
public final class SeedMapModel {

   public static final int MIN_REACH = 64;
   private static final double ZOOM_MARGIN = 1.18;
   /** How many earlier pictures stay under the newest. */
   static final int KEEP_LAYERS = 6;
   /** How often a drag asks for fresh ground, in nanoseconds. */
   private static final long DRAG_REQUEST_NS = 120_000_000L;
   /** Known structures kept per tab (all verified; this only bounds memory). */
   static final int KNOWN_LIMIT = 3000;
   /** Views are built this much wider than the screen, so a drag slides over painted ground. */
   static final double OVERSCAN = 1.5;
   /** A view this much coarser than the zoom needs is rebuilt for detail. */
   private static final double TOO_COARSE = 2.2;

   /** Builds the biome pictures, on its own so they never wait behind structure checks. */
   static final java.util.concurrent.ExecutorService GROUND =
         java.util.concurrent.Executors.newSingleThreadExecutor(r -> SeedMapData.mapThread(r, "FableVision-SeedMap-Ground-Build"));

   static final java.util.concurrent.ExecutorService WORKER =
         java.util.concurrent.Executors.newSingleThreadExecutor(r -> SeedMapData.mapThread(r, "FableVision-SeedMap"));

   private final HolderLookup.Provider registries;
   private final List<SeedCriteria.Find> finds;
   private final String problem;
   /** The player's block position when the map should follow them on this tab, else null. */
   private final Supplier<int[]> follow;
   private final boolean keepSpawn;
   /** The map is open in a world being played (the N key), not on the Create World screen. */
   private final boolean inWorld;

   private Long seed;
   private MapDim tab = MapDim.OVERWORLD;
   private volatile BlockPos spawn;

   private final Map<MapDim, int[]> camera = new ConcurrentHashMap<>();
   private final Map<MapDim, SeedMapData.View> views = new ConcurrentHashMap<>();
   private final AtomicLong wanted = new AtomicLong();
   private volatile boolean loading;
   /** The build now running: dim ordinal, seed, target centre x, z, built reach, camera reach. */
   private volatile long[] inFlight;
   /** The build now running has handed over its ground (biomes) and is checking structures. */
   private volatile boolean inFlightGround;
   private volatile long retryAtNs;
   private volatile int failures;
   private volatile String loadError;
   private long lastDragRequest;
   /** The mouse is down on the map. */
   private boolean dragging;

   private final Map<MapDim, List<SeedMapData.View>> layers = new EnumMap<>(MapDim.class);
   private final Map<MapDim, LinkedHashMap<String, SeedMapData.Nearby>> known = new EnumMap<>(MapDim.class);
   private final Map<MapDim, SeedMapData.View> seen = new EnumMap<>(MapDim.class);
   private int knownVersion;

   public SeedMapModel(HolderLookup.Provider registries, List<SeedCriteria.Find> finds, String problem,
                       BlockPos knownSpawn, Supplier<int[]> follow) {
      this.registries = registries;
      this.finds = finds == null ? List.of() : finds;
      this.problem = problem;
      this.spawn = knownSpawn;
      this.keepSpawn = knownSpawn != null;
      this.follow = follow;
      this.inWorld = follow != null;   // only the in-world map follows the player
      SeedMapData.setInWorld(inWorld);
   }

   // ── State ────────────────────────────────────────────────────────────────

   public void setSeed(Long value) {
      seed = value;
      wanted.incrementAndGet();
      views.clear();
      camera.clear();
      known.clear();
      seen.clear();
      layers.clear();
      knownVersion++;
      if (!keepSpawn) {
         spawn = null;
      }
   }

   public Long seed() {
      return seed;
   }

   public MapDim tab() {
      return tab;
   }

   public void setTab(MapDim d) {
      tab = d;
   }

   public BlockPos spawn() {
      return spawn;
   }

   public boolean loading() {
      return loading;
   }

   public String loadError() {
      return loadError;
   }

   public SeedMapData.View view() {
      return views.get(tab);
   }

   /** The pictures on screen for this tab, oldest first. */
   public List<SeedMapData.View> layers() {
      return layers.getOrDefault(tab, List.of());
   }

   public LinkedHashMap<String, SeedMapData.Nearby> known() {
      return known.get(tab);
   }

   /** Bumped whenever the remembered structures change, so a cached marker list knows it is stale. */
   public int knownVersion() {
      return knownVersion;
   }

   private boolean canRead() {
      return registries != null && seed != null && problem == null;
   }

   // ── Camera ───────────────────────────────────────────────────────────────

   /** The camera for the current tab: {centre x, centre z, half-width in blocks}. */
   public int[] cam() {
      int[] c = camera.get(tab);
      if (c != null) {
         return c;
      }
      int[] here = follow == null ? null : follow.get();
      if (spawn == null && here == null) {
         return defaultCamera(tab, null);
      }
      c = here != null ? new int[]{here[0], here[1], 512} : defaultCamera(tab, spawn);
      camera.put(tab, c);
      return c;
   }

   /** Centred on the dimension's origin, wide enough for every find with a margin. */
   int[] defaultCamera(MapDim d, BlockPos at) {
      int ox = at == null ? 0 : SeedMapData.originX(d, at);
      int oz = at == null ? 0 : SeedMapData.originZ(d, at);
      int furthest = 0;
      for (SeedCriteria.Find f : finds) {
         if (MapDim.of(f.dim()) == d && d != MapDim.END) {
            furthest = Math.max(furthest, Math.max(Math.abs(f.x() - ox), Math.abs(f.z() - oz)));
         }
      }
      return new int[]{ox, oz, Math.max(SeedMapData.MIN_REACH, (int) Math.ceil(furthest * ZOOM_MARGIN))};
   }

   public double blocksPerPixel(int mapSize) {
      return cam()[2] * 2.0 / mapSize;
   }

   /** Zoom about a point on the map, given in pixels from the map's top-left corner. */
   public void zoomBy(double factor, double px, double pz, int mapSize) {
      int[] c = cam();
      double bpp = blocksPerPixel(mapSize);
      double wx = c[0] + (px - mapSize / 2.0) * bpp;
      double wz = c[1] + (pz - mapSize / 2.0) * bpp;
      int reach = (int) Math.max(MIN_REACH, Math.min(SeedMapData.MAX_REACH, c[2] * factor));
      double nbpp = reach * 2.0 / mapSize;
      c[0] = (int) Math.round(wx - (px - mapSize / 2.0) * nbpp);
      c[1] = (int) Math.round(wz - (pz - mapSize / 2.0) * nbpp);
      c[2] = reach;
      requestView();
   }

   /** A drag of the map by this many pixels, while the button is held. */
   public void drag(double dx, double dy, int mapSize) {
      dragging = true;
      int[] c = cam();
      double bpp = blocksPerPixel(mapSize);
      c[0] -= (int) Math.round(dx * bpp);
      c[1] -= (int) Math.round(dy * bpp);
      long now = System.nanoTime();
      if (now - lastDragRequest > DRAG_REQUEST_NS) {
         lastDragRequest = now;
         requestView();   // kept if the build in flight already covers where the map is now
      }
   }

   public void release() {
      dragging = false;
      requestView();
   }

   public void recentre() {
      camera.remove(tab);
      requestView();
   }

   /** The screen is going away: whatever is building stops at its next check. */
   public void stop() {
      wanted.incrementAndGet();
   }

   // ── Asking for views ─────────────────────────────────────────────────────

   /**
    * Called every client tick: the map catches up by itself. While nothing is building, a stale picture
    * is asked for again; while something IS building, it is replaced as soon as the map has moved off
    * the ground that build will cover (1.44.5) — it would only land somewhere the player has left.
    */
   public void tick() {
      if (System.nanoTime() < retryAtNs) {
         return;
      }
      if (loading ? !inFlightCovers() : stale()) {
         requestView();
      }
   }

   /** Will the build now running cover what the map shows, at about the right zoom? */
   private boolean inFlightCovers() {
      long[] t = inFlight;
      int[] c = camera.get(tab);
      if (t == null || c == null || t[0] != tab.ordinal() || seed == null || t[1] != seed) {
         return t != null && c == null;   // the first build picks the camera itself
      }
      if (t[4] <= 0) {
         return true;
      }
      boolean zoomOk = Math.max(t[5], c[2]) <= 2L * Math.min(t[5], c[2]);
      // STILL MAKING ITS GROUND, WHILE THE MOUSE IS DOWN: let it land, even if the map has moved on. It
      // is a fraction of a second away and still covers part of what is on screen; replacing it on every
      // move of a fast drag is how no ground at all would arrive until the mouse stopped. Once the mouse
      // is up, the place that matters is where it stopped, and waiting for ground somewhere else first
      // doubled the wait (mapReplay: 1.25 s of black after a drag at full zoom-out).
      if (dragging && !inFlightGround && zoomOk) {
         return true;
      }
      return Math.abs(c[0] - t[2]) + c[2] <= t[4] && Math.abs(c[1] - t[3]) + c[2] <= t[4]
            && Math.max(t[5], c[2]) <= 2L * Math.min(t[5], c[2]);
   }

   /** Does the newest picture for this tab fail to cover what the map shows, or need asking again? */
   boolean stale() {
      if (!canRead()) {
         return false;
      }
      SeedMapData.View v = views.get(tab);
      int[] c = camera.get(tab);
      if (v == null) {
         return true;
      }
      if (c == null) {
         return false;
      }
      boolean covers = Math.abs(c[0] - v.centreX()) + c[2] <= v.reach() && Math.abs(c[1] - v.centreZ()) + c[2] <= v.reach();
      return !covers || !v.structuresDone() || v.reach() > TOO_COARSE * OVERSCAN * c[2] || v.unverifiable() > 0
            || loadError != null;
   }

   public void requestView() {
      if (!canRead()) {
         return;
      }
      // A BUILD IN FLIGHT THAT WILL COVER WHERE THE MAP IS NOW IS LEFT TO FINISH (1.44.4); one that
      // will not is replaced (1.44.5) — see tick().
      if (loading && inFlightCovers()) {
         return;
      }
      final long token = wanted.incrementAndGet();
      final long s = seed;
      final MapDim dim = tab;
      final int[] asked = camera.containsKey(dim) ? camera.get(dim).clone() : null;
      inFlightGround = false;
      inFlight = asked == null ? new long[]{dim.ordinal(), s, 0, 0, -1, -1}
            : new long[]{dim.ordinal(), s, asked[0], asked[1], buildReachFor(asked[2]), asked[2]};
      loading = true;
      // GROUND ON ITS OWN THREAD, then structures on WORKER (1.44.6). With both on one thread, new
      // ground queued behind the last structure checks of a cancelled build — see
      // SeedMapData.buildGround.
      GROUND.execute(() -> {
         if (wanted.get() != token) {
            return;
         }
         try {
            WorldgenContext ctx = WorldgenContext.get(registries);
            BlockPos at = spawn;
            if (at == null) {
               at = SeedMapData.overworldSpawn(ctx, s);
               spawn = at;
            }
            int[] c = asked;
            if (c == null) {
               c = defaultCamera(dim, at);
               camera.putIfAbsent(dim, c.clone());
            }
            int buildReach = buildReachFor(c[2]);
            if (asked == null && wanted.get() == token) {
               inFlight = new long[]{dim.ordinal(), s, c[0], c[1], buildReach, c[2]};
            }
            // THE GROUND AS SOON AS IT IS READY, the structures all together at the end (1.44.5).
            SeedMapData.View ground = SeedMapData.buildGround(ctx, s, dim, at, c[0], c[1], buildReach,
                  () -> wanted.get() != token);
            if (ground == null) {
               finish(token, null);   // superseded while sampling
               return;
            }
            if (wanted.get() == token) {
               views.put(dim, ground);
               inFlightGround = true;
            }
            if (ground.structuresDone()) {
               finish(token, ground);
               return;
            }
            WORKER.execute(() -> {
               if (wanted.get() != token) {
                  return;
               }
                     try {
                  finish(token, SeedMapData.addStructures(ctx, s, ground, () -> wanted.get() != token));
               } catch (Throwable failed) {
                  fail(token, failed);
               }
            });
         } catch (Throwable failed) {
            fail(token, failed);
         }
      });
   }

   /** A build ended: its view (null if it was superseded) goes up, and loading ends if it was the newest. */
   private void finish(long token, SeedMapData.View done) {
      if (done != null && wanted.get() == token) {
         views.put(done.dim(), done);
         failures = 0;
         retryAtNs = done.unverifiable() > 0 ? System.nanoTime() + 3_000_000_000L : 0;
      }
      loadError = null;
      if (wanted.get() == token) {
         loading = false;
      }
   }

   private void fail(long token, Throwable failed) {
      FableVisionClient.LOGGER.warn("Seed map could not read the world", failed);
      loadError = failed.getClass().getSimpleName();
      failures++;
      retryAtNs = System.nanoTime() + Math.min(10, 2L * failures) * 1_000_000_000L;
      if (wanted.get() == token) {
         loading = false;
      }
   }

   private static int buildReachFor(int camReach) {
      return (int) Math.min(SeedMapData.MAX_BUILD_REACH, Math.round(camReach * OVERSCAN));
   }

   // ── Taking views in ──────────────────────────────────────────────────────

   /**
    * Takes in the view that last arrived, if it is new: it goes on top of the pictures (unless it is
    * the same ground as the top one, see below) and its structures are remembered. Called once a frame.
    */
   public void absorb() {
      SeedMapData.View view = views.get(tab);
      if (view == null || seen.get(tab) == view) {
         return;
      }
      seen.put(tab, view);
      List<SeedMapData.View> list = layers.computeIfAbsent(tab, d -> new ArrayList<>());
      SeedMapData.View top = list.isEmpty() ? null : list.get(list.size() - 1);
      // COMPARED BY IDENTITY ON PURPOSE: a view that only adds structures to ground already drawn
      // carries the very same colour array, so the picture is replaced rather than stacked.
      if (top != null && top.tint() == view.tint()) {
         list.set(list.size() - 1, view);
      } else {
         list.add(view);
         while (list.size() > KEEP_LAYERS) {
            list.remove(0);
         }
      }
      LinkedHashMap<String, SeedMapData.Nearby> k = known.computeIfAbsent(tab, d -> new LinkedHashMap<>());
      boolean added = false;
      for (SeedMapData.Nearby n : view.nearby()) {
         added |= k.put(n.id() + "@" + n.chunkX() + "," + n.chunkZ(), n) == null;
      }
      while (k.size() > KNOWN_LIMIT) {
         k.remove(k.keySet().iterator().next());
      }
      if (added) {
         knownVersion++;
      }
   }

   // ── What is on screen (for the replay, and anything else that wants to ask) ─

   /**
    * How much of what the map shows is covered by a picture: 1.0 means no black anywhere. Sampled on a
    * 64 x 64 grid over the camera's square.
    */
   public double coverage() {
      int[] c = cam();
      List<SeedMapData.View> list = layers();
      int covered = 0;
      int n = 64;
      for (int i = 0; i < n; i++) {
         for (int j = 0; j < n; j++) {
            double x = c[0] - c[2] + (i + 0.5) * 2.0 * c[2] / n;
            double z = c[1] - c[2] + (j + 0.5) * 2.0 * c[2] / n;
            for (SeedMapData.View v : list) {
               if (Math.abs(x - v.centreX()) <= v.reach() && Math.abs(z - v.centreZ()) <= v.reach()) {
                  covered++;
                  break;
               }
            }
         }
      }
      return covered / (double) (n * n);
   }

   /**
    * Whether a map shows coordinates: always for a typed seed or your own world (the N-key map); for a
    * search result, only with the Coords setting on. The join message's hint names only maps where
    * this is true — joinDiag holds it to that.
    */
   public static boolean coordsShown(boolean searchResult, boolean coordsSetting) {
      return !searchResult || coordsSetting;
   }

   /** The newest view has been taken in (its picture is on screen and its structures remembered). */
   public boolean absorbed() {
      SeedMapData.View v = views.get(tab);
      return v != null && seen.get(tab) == v;
   }

   /** The remembered structures inside a square of the world. */
   public List<SeedMapData.Nearby> knownIn(int minX, int minZ, int maxX, int maxZ) {
      List<SeedMapData.Nearby> out = new ArrayList<>();
      LinkedHashMap<String, SeedMapData.Nearby> k = known.get(tab);
      if (k != null) {
         for (SeedMapData.Nearby n : k.values()) {
            if (n.x() >= minX && n.x() <= maxX && n.z() >= minZ && n.z() <= maxZ) {
               out.add(n);
            }
         }
      }
      return out;
   }
}
