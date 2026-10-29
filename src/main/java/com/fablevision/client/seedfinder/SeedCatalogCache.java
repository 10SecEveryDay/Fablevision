package com.fablevision.client.seedfinder;

import java.util.List;
import com.fablevision.client.FableVisionClient;
import com.fablevision.client.FableVisionConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;

/**
 * The structure/biome catalog for the Create World screen, built once off-thread.
 *
 * SPLIT OUT OF SeedFinderScreen IN 1.40.0, when that screen was deleted. The screen was a
 * standalone in-world seed finder reached by {@code /seedfind}; the command had already gone, but
 * the screen itself was still a complete, working GUI sitting in the jar, and its own class
 * documentation still described it as the {@code /seedfind} panel. A reviewer decompiling the mod
 * would have found a seed-search UI that reads "the standalone Seed Finder" and searches using
 * whatever registries are to hand — which is exactly the thing this mod is being accused of and
 * exactly the thing it does not do.
 *
 * So the GUI was deleted outright and the half that was never about the GUI — this cache, which
 * {@link SpawnWishScreen} and {@link WishParser} both read on every frame and every wish — moved
 * here. Nothing in this file can start a search or show a screen.
 *
 * The registries it is built from can now only come from a Create World screen: see
 * {@link SeedAccess}.
 */
public final class SeedCatalogCache {

   private static volatile List<SeedCriteria.StructureTarget> catalog;
   private static volatile List<Identifier> biomes;
   private static volatile String error;
   private static volatile HolderLookup.Provider source;
   // The two below are read and written only under the class lock.
   private static boolean building;
   /**
    * THE BUILD WHOSE RESULT MAY BE PUBLISHED (1.47.3). Every build that starts takes the next number, and
    * {@link #forget} moves it on too; a build publishes only if its number is still the current one.
    *
    * Builds used to write straight into the fields from their own threads. Two could overlap — new data
    * packs on the same Create World screen start a second build while the first is still going — and the
    * LAST TO FINISH won, so the older registries' catalog could land over the newer one's; the older build
    * also cleared {@code building} while the newer one ran, so a third could start. And a build still running
    * when a world began to load refilled the cache {@link #forget} had just emptied.
    */
   private static long generation;

   /**
    * (Re)builds the catalog off-thread from the Create World screen's registries.
    *
    * A null source means we are not at the Create World screen, which is the only place a search
    * may happen — so it leaves the explanation rather than quietly reusing whatever was cached from
    * last time. The wording is the one sentence {@link SeedAccess#ONLY_ON_CREATE_WORLD} holds, so
    * the block and the explanation of the block cannot drift apart.
    */
   public static synchronized void ensure(HolderLookup.Provider from) {
      if (from == null) {
         if (!ready()) {
            error = SeedAccess.ONLY_ON_CREATE_WORLD;
         }
         return;
      }
      if (source == from && (ready() || building)) {
         return;
      }
      long build = begin(from);
      Thread t = new Thread(() -> build(build, from), "FableVision-SeedFinder-Bootstrap");
      t.setDaemon(true);
      t.start();
   }

   /** Starts a build for {@code from}: empties the cache and makes every build before it stale. Returns its number. */
   static synchronized long begin(HolderLookup.Provider from) {
      source = from;
      building = true;
      error = null;
      catalog = null;
      biomes = null;
      return ++generation;
   }

   private static void build(long build, HolderLookup.Provider from) {
      List<Identifier> builtBiomes = null;
      List<SeedCriteria.StructureTarget> builtCatalog = null;
      String failed = null;
      try {
         WorldgenContext ctx = WorldgenContext.get(from);
         builtBiomes = SeedCatalog.biomes(ctx);
         builtCatalog = SeedCriteria.catalog(ctx);
         // THE STRUCTURE FILES TOO, here and now (1.44.2), on this background thread: the first
         // building search then does not pay for them, and if they cannot be loaded that is known
         // before anyone presses Search. Used to happen lazily inside the first search's worker.
         VillageLayout.templates();
      } catch (Throwable e) {
         FableVisionClient.LOGGER.error("Seed finder catalog bootstrap failed", e);
         failed = "World-gen data failed to load: " + e.getClass().getSimpleName();
      }
      finish(build, builtBiomes, builtCatalog, failed);
   }

   /**
    * Publishes what build {@code build} made — both lists together, or its error — if it is still the newest
    * build and nothing was forgotten since it started. False when it was stale and has been dropped.
    */
   static synchronized boolean finish(long build, List<Identifier> builtBiomes,
                                      List<SeedCriteria.StructureTarget> builtCatalog, String failed) {
      if (build != generation) {
         return false;
      }
      building = false;
      if (failed != null) {
         error = failed;
      } else {
         biomes = builtBiomes;
         catalog = builtCatalog;
      }
      return true;
   }

   /**
    * Drops everything, so nothing built for one world's data packs survives into another.
    *
    * Called when a world starts loading. Before 1.40.0 the cache simply persisted, which was
    * harmless while it lived — but a catalog that outlives the screen that made it is a catalog
    * that is available in a context where searching is not, and the point of this round is that
    * such a thing should not be reachable at all.
    */
   public static void forget() {
      // Called every client tick while a world is loaded. Nothing to drop is the usual case, so it
      // returns without taking the lock; the work below happens once, on the first tick in a world.
      if (catalog == null && biomes == null && source == null && error == null) {
         return;
      }
      forgetNow();
   }

   private static synchronized void forgetNow() {
      catalog = null;
      biomes = null;
      source = null;
      error = null;
      building = false;
      generation++;   // a build still running is now stale: it cannot refill what was just emptied
   }

   public static boolean ready() {
      return catalog != null && biomes != null;
   }

   /** Whether the current build is still running. Package-private for catalogDiag. */
   static synchronized boolean building() {
      return building;
   }

   public static String error() {
      return error;
   }

   public static List<SeedCriteria.StructureTarget> list() {
      return catalog;
   }

   public static List<Identifier> biomeList() {
      return biomes;
   }

   /** "old_growth_pine_taiga" -> "Old Growth Pine Taiga". */
   public static String prettify(String path) {
      String[] words = path.split("_");
      StringBuilder sb = new StringBuilder();
      for (String w : words) {
         if (!w.isEmpty()) {
            if (sb.length() > 0) {
               sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
         }
      }
      return sb.toString();
   }

   /** The next Fast-mode distance in the cycle. Lives here because it is shared state about the
    *  search rather than about any one screen. */
   public static int nextFastDistance() {
      int[] opts = FableVisionConfig.SEED_FAST_DISTANCES;
      for (int i = 0; i < opts.length; i++) {
         if (opts[i] == FableVisionConfig.seedFastDistance) {
            return opts[(i + 1) % opts.length];
         }
      }
      return opts[0];
   }

   private SeedCatalogCache() {
   }
}
