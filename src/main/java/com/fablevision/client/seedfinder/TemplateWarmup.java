package com.fablevision.client.seedfinder;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

import com.fablevision.client.FableVisionClient;
import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * FILLS VANILLA'S TEMPLATE CACHES ON ONE THREAD, BEFORE SEVERAL THREADS READ THEM (1.46.1).
 *
 * The race. {@code StructureTemplate.Palette} works out a template's jigsaw blocks the first time they are
 * asked for and keeps them in a plain HashMap. Two threads filling the same cold template at once make one
 * of them throw ConcurrentModificationException. {@link VillageLayout#retryingCacheRace} asks the check
 * again, up to three times, and then gives up — and a give-up is a structure the map HIDES ("couldn't be
 * checked") or a seed the search turns down. A bastion is built from up to 167 templates, so on the first
 * wide Nether view of a session six map threads starting bastions together could lose three times in a few
 * milliseconds: mapTruthDiag at 2,800 blocks hid 5 real bastions on its first seed.
 *
 * The fix is not to retry harder but to have nothing to race over: walk the structure's jigsaw pools on one
 * thread and ask each template for its jigsaw blocks once — the very call assembly makes — so the caches are
 * full before the parallel checks begin. Public API only: {@link JigsawStructure#getStartPool()} and
 * {@link JigsawStructure#getPoolAliases()}, each pool's templates and fallback, each element's
 * {@code getShuffledJigsawBlocks}, and each jigsaw's {@code pool()}.
 *
 * IT CANNOT CHANGE WHAT IS DRAWN OR FOUND. It stores exactly what vanilla's lazy path would store — the same
 * filter over the same template — only earlier, on one thread. No verdict reads anything from here. A
 * template the walk missed (out of time, a palette it did not reach) is still covered by the retry and, past
 * that, by hide-if-unsure. If the walk throws, it logs at debug and the caller carries on as before.
 *
 * WHAT IS WALKED. A structure that is ASSEMBLED (the map's bastions and End Cities; a search row that names
 * a building) gets every pool it can reach. Any other jigsaw structure gets its start pool only: the
 * existence check ({@code findValidGenerationPoint}) reads jigsaw blocks only on the start piece, when the
 * structure names a start jigsaw (ancient cities, trial chambers). Non-jigsaw structures have nothing to
 * walk. Measured by {@code coldDiag}, which counts collisions per structure on cold template managers.
 *
 * Once per template manager per structure, remembered weakly so a manager that goes away is forgotten. Only a
 * walk that finished is remembered; one cut short by the time budget carries on next time.
 */
public final class TemplateWarmup {

   /** Test switch: skip the warm-up, so mapTruthDiag and coldDiag can show what it prevents. Never set in the game. */
   static volatile boolean SKIP = Boolean.getBoolean("fablevision.map.nowarm");

   /** The most one warm-up may take. The full bastion walk took about 200 ms cold (bastion diagnosis). */
   public static final long BUDGET_MS = 2_000;

   /**
    * A template with several palettes picks one by position, so each element is asked at a few positions.
    * Jigsaw templates have one palette as far as anyone has checked (every bastion's has); this is cheap
    * insurance — after the first call each is a cache read.
    */
   private static final int POSITIONS = 4;

   /** Per manager: the structures ("bastion_remnant", "village_plains@start") whose walk finished. */
   private static final Map<StructureTemplateManager, Set<String>> DONE =
         Collections.synchronizedMap(new WeakHashMap<>());

   /** What one call did: structures walked, templates asked, how long, whether every walk finished. */
   public record Report(int structures, int templates, long ms, boolean complete, String failure) {
      static final Report NOTHING = new Report(0, 0, 0, true, null);
   }

   /**
    * Warms {@code structures}' templates in {@code manager}. {@code whole} says which are assembled (every
    * reachable pool) rather than only checked (start pool). One caller at a time: a second waits, then finds
    * the work done. Never call it on the render thread.
    */
   public static synchronized Report warm(HolderLookup.Provider registries, StructureTemplateManager manager,
                                          Collection<Holder<Structure>> structures,
                                          Predicate<Holder<Structure>> whole, long budgetMs) {
      if (SKIP || manager == null || registries == null || structures.isEmpty()) {
         return Report.NOTHING;
      }
      long started = System.nanoTime();
      long deadline = started + budgetMs * 1_000_000L;
      Set<String> done = DONE.computeIfAbsent(manager, m -> Collections.synchronizedSet(new HashSet<>()));
      int walked = 0;
      int templates = 0;
      boolean complete = true;
      String failure = null;
      try {
         HolderLookup.RegistryLookup<StructureTemplatePool> pools = registries.lookupOrThrow(Registries.TEMPLATE_POOL);
         for (Holder<Structure> structure : structures) {
            if (!(structure.value() instanceof JigsawStructure jigsaw)) {
               continue;   // built from fixed templates or code: no jigsaw cache to fill
            }
            boolean all = whole.test(structure);
            String key = VillageLayout.pathOf(structure) + (all ? "" : "@start");
            if (done.contains(key) || !all && done.contains(VillageLayout.pathOf(structure))) {
               continue;
            }
            int[] count = new int[1];
            boolean finished = walk(jigsaw, pools, manager, all, deadline, count);
            templates += count[0];
            walked++;
            if (finished) {
               done.add(key);
            } else {
               complete = false;
               break;   // out of time: the rest stay cold, and the retry covers them as before
            }
         }
      } catch (Throwable t) {
         complete = false;
         failure = t.getClass().getSimpleName();
         FableVisionClient.LOGGER.debug("Template warm-up failed; the checks carry on without it", t);
      }
      return new Report(walked, templates, (System.nanoTime() - started) / 1_000_000L, complete, failure);
   }

   /**
    * The same for a SEARCH: every structure a wish's rows can check, walked whole when the row assembles it
    * (a named building, "spawn inside", a big village) and start-only otherwise.
    */
   public static Report forSearch(SeedCriteria criteria, HolderLookup.Provider registries,
                                  StructureTemplateManager manager, long budgetMs) {
      if (SKIP || criteria == null || registries == null || manager == null) {
         return Report.NOTHING;
      }
      Map<Holder<Structure>, Boolean> wholeOf = new java.util.LinkedHashMap<>();
      try {
         HolderLookup.RegistryLookup<Structure> all = registries.lookupOrThrow(Registries.STRUCTURE);
         java.util.List<SeedCriteria.StructureTarget> targets = new java.util.ArrayList<>(criteria.structures);
         targets.addAll(criteria.excludeStructures);
         for (SeedCriteria.StructureTarget t : targets) {
            for (net.minecraft.resources.Identifier id : t.wanted) {
               all.get(ResourceKey.create(Registries.STRUCTURE, id))
                     .ifPresent(h -> wholeOf.merge(h, t.needsAssembly(), Boolean::logicalOr));
            }
         }
      } catch (Throwable t) {
         FableVisionClient.LOGGER.debug("Template warm-up could not list a search's structures", t);
         return new Report(0, 0, 0, false, t.getClass().getSimpleName());
      }
      return warm(registries, manager, wholeOf.keySet(), h -> wholeOf.getOrDefault(h, false), budgetMs);
   }

   /** Has every one of these structures been warmed in {@code manager}, as {@code whole} asks? For tests. */
   static boolean warmed(StructureTemplateManager manager, Collection<Holder<Structure>> structures,
                         Predicate<Holder<Structure>> whole) {
      Set<String> done = DONE.get(manager);
      if (done == null) {
         return false;
      }
      for (Holder<Structure> s : structures) {
         if (s.value() instanceof JigsawStructure) {
            String path = VillageLayout.pathOf(s);
            if (!done.contains(path) && (whole.test(s) || !done.contains(path + "@start"))) {
               return false;
            }
         }
      }
      return true;
   }

   /** One structure's pools, breadth first. False if the deadline came first. */
   private static boolean walk(JigsawStructure jigsaw, HolderLookup.RegistryLookup<StructureTemplatePool> pools,
                               StructureTemplateManager manager, boolean all, long deadline, int[] count) {
      ArrayDeque<StructureTemplatePool> queue = new ArrayDeque<>();
      Set<Object> seenPools = Collections.newSetFromMap(new IdentityHashMap<>());
      Set<ResourceKey<StructureTemplatePool>> seenKeys = new HashSet<>();
      Set<StructurePoolElement> seenElements = Collections.newSetFromMap(new IdentityHashMap<>());
      enqueue(queue, seenPools, jigsaw.getStartPool().value());
      // Aliased pools (trial chambers) are named through their aliases, so every target an alias can pick
      // is walked too: the start pool's own alias included.
      jigsaw.getPoolAliases().forEach(binding -> binding.allTargets().forEach(k -> {
         if (seenKeys.add(k)) {
            pools.get(k).ifPresent(h -> {
               if (all) {
                  enqueue(queue, seenPools, h.value());
               }
            });
         }
      }));
      boolean first = true;
      RandomSource random = RandomSource.create(0L);
      while (!queue.isEmpty()) {
         StructureTemplatePool pool = queue.poll();
         for (Pair<StructurePoolElement, Integer> entry : pool.getTemplates()) {
            if (System.nanoTime() > deadline) {
               return false;
            }
            for (StructureTemplate.JigsawBlockInfo jigsawBlock : ask(entry.getFirst(), manager, random, seenElements, count)) {
               if (all && seenKeys.add(jigsawBlock.pool())) {
                  pools.get(jigsawBlock.pool()).ifPresent(h -> enqueue(queue, seenPools, h.value()));
               }
            }
         }
         if (all) {
            enqueue(queue, seenPools, pool.getFallback().value());
         } else if (first) {
            break;   // start pool only
         }
         first = false;
      }
      return true;
   }

   private static void enqueue(ArrayDeque<StructureTemplatePool> queue, Set<Object> seen, StructureTemplatePool pool) {
      if (pool != null && seen.add(pool)) {
         queue.add(pool);
      }
   }

   /**
    * Asks one element for its jigsaw blocks — the call that fills the cache — at a few positions, and
    * returns them so the walk can follow their pools. A list element is its parts.
    */
   private static java.util.List<StructureTemplate.JigsawBlockInfo> ask(StructurePoolElement element,
                                                                      StructureTemplateManager manager, RandomSource random,
                                                                      Set<StructurePoolElement> seen, int[] count) {
      if (!seen.add(element)) {
         return java.util.List.of();
      }
      if (element instanceof ListPoolElement list) {
         java.util.List<StructureTemplate.JigsawBlockInfo> out = new java.util.ArrayList<>();
         for (StructurePoolElement part : list.getElements()) {
            out.addAll(ask(part, manager, random, seen, count));
         }
         return out;
      }
      count[0]++;
      // Every position's answer, not only the first: a second palette could point at other pools.
      java.util.List<StructureTemplate.JigsawBlockInfo> out = new java.util.ArrayList<>();
      for (int i = 0; i < POSITIONS; i++) {
         out.addAll(element.getShuffledJigsawBlocks(manager, new BlockPos(i * 31, 0, i * 17), Rotation.NONE, random));
      }
      return out;
   }

   private TemplateWarmup() {
   }
}
