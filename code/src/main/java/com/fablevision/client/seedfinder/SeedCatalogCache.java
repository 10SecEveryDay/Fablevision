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
   private static volatile boolean building;

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
      source = from;
      building = true;
      error = null;
      catalog = null;
      biomes = null;
      Thread t = new Thread(() -> {
         try {
            WorldgenContext ctx = WorldgenContext.get(from);
            biomes = SeedCatalog.biomes(ctx);
            catalog = SeedCriteria.catalog(ctx);
            // THE STRUCTURE FILES TOO, here and now (1.44.2), on this background thread: the first
            // building search then does not pay for them, and if they cannot be loaded that is known
            // before anyone presses Search. Used to happen lazily inside the first search's worker.
            VillageLayout.templates();
         } catch (Throwable e) {
            FableVisionClient.LOGGER.error("Seed finder catalog bootstrap failed", e);
            error = "World-gen data failed to load: " + e.getClass().getSimpleName();
         } finally {
            building = false;
         }
      }, "FableVision-SeedFinder-Bootstrap");
      t.setDaemon(true);
      t.start();
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
   }

   public static boolean ready() {
      return catalog != null && biomes != null;
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
