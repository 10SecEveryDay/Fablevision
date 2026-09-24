package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;

/**
 * What the user wants a seed to have, plus the per-seed evaluation.
 *
 * EVERY DISTANCE THIS REPORTS IS FROM THE PLAYER'S REAL SPAWN POINT
 * ({@link Climate.Sampler#findSpawnPosition}, the game's own pick, which wanders from 0,0 and
 * avoids some climates). That is true in both modes, and the difference between them is only how
 * the work is ordered:
 *
 *   EXACT measures from the real spawn as it goes, paying ~10.6ms per seed for the spawn lookup.
 *   FAST  filters from the world origin first, which is much quicker because it skips that lookup,
 *         and then does ONE spawn lookup on a seed that has already matched and re-measures every
 *         find against the distance that was actually asked for — rejecting the seed and carrying
 *         on if anything is outside.
 *
 * So Fast mode is a cheaper route to the same promise, not a looser one. This paragraph used to say
 * Fast measured from 0,0 and that "the target may be a short walk from where you spawn", which was
 * an accurate description of a real bug: a search set to 100 blocks returned a village 139 blocks
 * from spawn. The re-check exists to make that impossible and the wording is no longer true of the
 * code — see the verification block at the end of {@link #test}.
 *
 * NO mode ever moves your spawn; only the SEED is chosen, and you spawn exactly as vanilla would.
 *
 * Evaluation is a funnel so seeds fly by: cheap placement math first, a structure GATE (each
 * wanted structure must truly generate somewhere in reach) before the expensive spawn search,
 * then exact confirms only for survivors.
 */
public final class SeedCriteria {
   /**
    * The /locate warning, ONE short line (1.44.3). It was two lines of capitals on every join; the
    * join message now prints it only the first time per session (FableVisionClient), so it is one
    * constant that code can recognise rather than a sentence to match.
    */
   public static final String LOCATE_TIP = "Use these coords, not /locate (it finds the nearest one).";

   /**
    * The dimensions this finder will search. THE END IS NOT ONE OF THEM, and it is absent from the
    * enum rather than merely unused, because that is the difference between a decision and a habit.
    *
    * WHY IT WAS REMOVED IN 1.41.4. A search returned an "End City with Ship" at End coordinates; the
    * player went there and there was nothing. That is the worst thing this program can do — every
    * other complaint in its history has been about a search being slow, wide or badly explained, and
    * this one was a structure reported at a place it does not exist.
    *
    * The End is genuinely different from the other two, and the difference is why the guarantee this
    * finder rests on does not hold there. Everywhere else, a structure generates wherever placement
    * and biome both say yes, so {@code confirm} — placement plus biome plus the weighted pick — is
    * the whole question, and the coordinate it returns is a coordinate the world will have something
    * at. An End City ALSO needs an outer island whose ground actually reaches the height it needs,
    * and that test lives inside the structure's own generation, after everything this code checks.
    * The row carried a piece token to stand in for that test; it was not enough.
    *
    * The fix chosen was not to patch that row. A guarantee that holds in two dimensions and is
    * approximated in a third is not a guarantee, and there was no way to be sure which other End
    * claim had the same hole. So the whole dimension is gone: no End rows in the picker, no End
    * names in the AI's vocabulary, no End tab on the map, and no {@code Dim} value that could carry
    * an End find through any of it. {@link SeedCatalog#dimensionOf} returns null for an End
    * structure set, which drops it out of the catalog and out of the map's scan at the same point.
    *
    * The Nether stays, and it stays because it does not have this problem: a fortress or a bastion
    * generates wherever placement and biome agree, exactly like an overworld structure.
    */
   public enum Dim { OVERWORLD, NETHER }
   /**
    * Non-searchable-by-placement kinds kept in the list for completeness.
    *
    * STRONGHOLD was removed in 1.36.0 and should not come back. Strongholds are ring-placed
    * ~1280-2816 blocks out, so "a stronghold near spawn" is not a rare seed, it is an impossible
    * one — and the row implemented that by asking for a stronghold within 8000 blocks, which EVERY
    * seed satisfies. The search returned on the first seed it tested and reported a portal a
    * thousand blocks away as if it had found something. A filter that rejects nothing is worse
    * than no filter: it costs the player the whole search and hands back an arbitrary seed.
    */
   public enum Special { NORMAL, DUNGEON }
   /** Village abandoned/zombie requirement. */
   public enum ZombieMode { ANY, NORMAL, ABANDONED }

   /** Structures the seed must have, ANDed. */
   public final List<StructureTarget> structures = new ArrayList<>();
   /** Biomes that must appear near spawn, ANDed (max 3 — usually just one). */
   public final List<BiomeTarget> biomes = new ArrayList<>();
   /** Things that must NOT be near spawn — the "X not next to Y" wish. */
   public final List<StructureTarget> excludeStructures = new ArrayList<>();
   public final List<BiomeTarget> excludeBiomes = new ArrayList<>();
   /** "X next to Y" pairs. Each names a biome already in {@link #biomes} as its anchor. */
   public final List<Adjacency> adjacencies = new ArrayList<>();
   /** "n slime chunks within r" — the one search that needs no world at all. */
   public final List<SlimeTarget> slimes = new ArrayList<>();
   /**
    * True when this is a COMPOSED bundle — several things, each with its own reach, worked out
    * from a vague wish rather than picked one at a time.
    *
    * It turns off Exact mode's "aim right at spawn first" tier. That tier parks a good-enough hit
    * and spends a few more seconds hunting one where everything is within 32 blocks, which is
    * sensible for a single structure and meaningless for a bundle whose own items deliberately
    * reach 200-600 blocks. Left on, every bundle would pay the extra wait and then settle for the
    * backup it already had.
    */
   public boolean composed = false;

   /** How far the game's climate spawn search can wander from 0,0 (radius 2048) plus the
    *  ±5-chunk ground search around the chosen chunk. Stage-0 prefilters must cover it. */
   private static final int SPAWN_WANDER = 2144;
   /** Same wander in Nether coordinates (portal math: overworld /8) plus a chunk margin. */
   private static final int SPAWN_WANDER_NETHER = SPAWN_WANDER / 8 + 16;

   // SPAN_BIG (224) and SPAN_HUGE (384) were removed in 1.37.0 and should not come back. They were
   // ONE threshold for every biome, and the measurement that replaced them is the reason: the
   // median pale garden spans 32 blocks and the median warm ocean spans 480, so 224 rejected every
   // pale garden that ever existed and accepted almost every ocean. Size now comes from
   // BiomeShape, which holds each biome's own p33 and p67.

   /**
    * THE RADIUS IS THE RADIUS, IN EVERY DIMENSION. These two methods used to raise it.
    *
    * They enforced "honest per-dimension floors": a Nether row was silently widened to at least 208
    * blocks, an End row to 3072. The reasoning was real — the Nether puts fortress and bastion in
    * ONE structure set, roughly one winner per 432-block region, so 57,000 seeds produced not a
    * single pair within 64 blocks of the portal-in point, and a search asking for that would run
    * forever. The floor made those wishes finish.
    *
    * It made them finish by answering a different question. A player who set the distance control to
    * 100 and asked for a Nether Fortress got one at about 200, and every line and number in the app
    * agreed with itself about it, because the search really had been run at 208. The control said
    * 100. This is the same class of failure as the village at 139 that the Fast-mode re-check was
    * written for, and it survived that fix because it happens BEFORE the search rather than during
    * it: the re-check faithfully verified the find against the radius the row was carrying, and the
    * row was carrying the wrong one.
    *
    * So the floors are gone and the number on the control is the number searched, everywhere. A
    * tight Nether wish is now simply a rare one — which is a true thing the app can say, and does:
    * the picker warns on the row, and after 90 seconds the bottleneck report names it. A search that
    * takes a long time is a fair outcome. A search that quietly moves the goalposts is not.
    *
    * Kept as a method rather than deleted so the rule has one place to be read, and so
    * {@code radiusDiag} can assert it is the identity for every dimension.
    */
   static int dimRadius(Dim dim, int r) {
      return r;
   }

   /** Same rule for biomes, and for the same reason — see {@link #dimRadius}. */
   static int dimBiomeRadius(Dim dim, int r) {
      return r;
   }

   /**
    * Below this, a Nether structure near the portal-in point is rare enough to be worth warning
    * about before the player starts waiting.
    *
    * Measured, not chosen: fortress and bastion share one structure set with a 27-chunk spacing, so
    * there is about one of them per 432-block region and the portal-in point lands anywhere in it.
    * Within ~100 Nether blocks a given kind turns up in a small minority of seeds, and a PAIR
    * essentially never does. The number is a warning threshold only — nothing widens a search any
    * more.
    */
   public static final int NETHER_TIGHT = 208;

   public boolean isEmpty() {
      // Excludes count as a real search too — "no deserts near spawn" alone is a valid wish.
      return searchableStructures().isEmpty() && biomes.isEmpty()
            && excludeStructures.isEmpty() && excludeBiomes.isEmpty() && slimes.isEmpty();
   }

   /**
    * True when this wish CANNOT be answered in Fast mode, whatever the player picked.
    *
    * Fast mode measures from 0, 0 because finding the real spawn costs ~15ms a seed, and for a
    * distance that is a fair trade: the answer is a bit off and the match line says so. For "do I
    * spawn INSIDE it" there is no such trade. The question is about the spawn point itself, so
    * answering it from 0, 0 is not a rougher answer, it is an answer to a different question — and
    * one that would look exactly as confident. Fast is refused rather than approximated.
    */
   public boolean requiresExact() {
      for (StructureTarget t : structures) {
         if (t.spawnInside) {
            return true;
         }
      }
      return false;
   }

   /** True when something is actually measured from the overworld spawn — the only case
    *  where Exact mode's "aim RIGHT at spawn first" tier means anything (Nether/End floors
    *  and stronghold rings are far by nature). */
   public boolean hasOverworldTarget() {
      for (StructureTarget t : searchableStructures()) {
         if (t.dim == Dim.OVERWORLD) {
            return true;
         }
      }
      for (BiomeTarget b : biomes) {
         if (b.dim == Dim.OVERWORLD) {
            return true;
         }
      }
      return false;
   }

   private List<StructureTarget> searchableStructures() {
      List<StructureTarget> out = new ArrayList<>();
      for (StructureTarget t : structures) {
         if (t.special == Special.NORMAL) {
            out.add(t);
         }
      }
      return out;
   }

   /**
    * One-line human summary, e.g. "Village within 300 + Ruined Portal within 200 + jungle within 500".
    *
    * WORDS, NOT MATHS. Every one of these used to read "Village ≤300", which is a notation a
    * programmer reads without thinking and a player reads as an error message — and this string is
    * not an internal one: it is the line under the search, the line in the chat message after the
    * world loads, and the line the bottleneck report quotes. "≤" is also the one character here
    * that some fonts and some chat filters render as a box.
    */
   public String summary() {
      List<String> parts = new ArrayList<>();
      for (StructureTarget t : structures) {
         if (t.special == Special.NORMAL) {
            parts.add(t.label + " within " + t.radius);
         }
      }
      for (BiomeTarget b : biomes) {
         parts.add(b.sizeWord() + b.label + " within " + b.radius);
      }
      for (Adjacency a : adjacencies) {
         parts.add(a.anchorLabel() + " next to " + a.otherLabel() + " (within " + a.within + ")");
      }
      for (SlimeTarget s : slimes) {
         parts.add(s.label());
      }
      for (StructureTarget t : excludeStructures) {
         parts.add("no " + t.label);
      }
      for (BiomeTarget b : excludeBiomes) {
         parts.add("no " + b.label);
      }
      return String.join(" + ", parts);
   }

   /**
    * Adds a "X next to Y" pair, or explains in plain words why it is not a search.
    *
    * ONE place decides this, because the picker and the AI both have to give the same answer and
    * the refusals are the interesting part. Returns null when the pair was added, otherwise a
    * sentence a player can read.
    *
    * Both ends are also registered as ordinary targets if they are not already, since "next to" is
    * an EXTRA condition rather than a replacement: "cherry grove next to a village" still wants the
    * grove near spawn and the village within its own radius, and then the two within reach of each
    * other. Two constraints ANDed is both easier to explain and easier to predict than a single
    * clever one.
    */
   public String addAdjacency(BiomeTarget anchor, BiomeTarget otherBiome,
                              StructureTarget otherStructure, int within) {
      if (anchor == null || (otherBiome == null) == (otherStructure == null)) {
         return "That pair isn't something the search can look for.";
      }
      // "Plains next to plains" — not rare, not hard, simply not a question. Refused with the real
      // reason rather than accepted as a filter that would pass every seed in the game.
      if (otherBiome != null && otherBiome.biome.equals(anchor.biome)) {
         return "\"" + anchor.label + " next to " + anchor.label + "\" isn't a search — a biome is "
               + "always next to itself, so nothing would be narrowed down.";
      }
      Dim other = otherBiome != null ? otherBiome.dim : otherStructure.dim;
      if (other != anchor.dim) {
         return "\"" + anchor.label + "\" and \"" + (otherBiome != null ? otherBiome.label
               : otherStructure.label) + "\" are in different worlds, so \"next to\" has no "
               + "meaning between them.";
      }
      int reach = BiomeShape.clampNextTo(within);
      if (!biomes.contains(anchor)) {
         biomes.add(anchor);
      }
      // THE FAR END NEEDS ROOM TO BE FAR, and forgetting this made the whole feature a no-op in the
      // mode most people use. Exact mode gives every target a 64-block radius from spawn, so
      // "cherry grove next to a village" would have demanded a village within 64 of SPAWN and then
      // asked, pointlessly, whether it was also within 512 of a grove that was itself within 64 of
      // spawn. The answer is always yes, and the wish quietly became "both at spawn".
      //
      // The anchor keeps its own reach — it is the thing the player wants near them. The other end
      // is allowed anchor.radius + within, which is exactly as far as it can be while still
      // satisfying the pair, so nothing is loosened beyond what was asked for.
      int farReach = anchor.radius + reach;
      if (otherBiome != null) {
         if (otherBiome.radius < farReach) {
            otherBiome.radius = dimBiomeRadius(otherBiome.dim, farReach);
         }
         if (!biomes.contains(otherBiome)) {
            biomes.add(otherBiome);
         }
      }
      StructureTarget far = otherStructure;
      if (far != null) {
         if (far.radius < farReach) {
            // withRadius returns a COPY, so whatever list already holds the old object has to be
            // updated with it — the funnel matches an adjacency's structure end by identity.
            StructureTarget widened = far.withRadius(farReach);
            int at = structures.indexOf(far);
            if (at >= 0) {
               structures.set(at, widened);
            } else {
               structures.add(widened);
            }
            far = widened;
         } else if (!structures.contains(far)) {
            structures.add(far);
         }
      }
      adjacencies.add(new Adjacency(anchor, otherBiome, far, reach));
      return null;
   }

   /**
    * Why these two structures cannot be paired, or null when they can.
    *
    * SPLIT OUT OF {@link #addStructurePair} so the PICKER can ask the question before it draws a
    * link, rather than letting the player wire up a pair and only discover at Search time that it
    * was never possible. Same reasoning, one copy — the two paths cannot give different answers.
    *
    * The refusals matter more here than for a biome pair, because two structures can be impossible
    * to pair for a reason no player can see: a STRUCTURE SET. Minecraft places at most one winner
    * per region per set, so two structures sharing a set compete for the same slots and can never
    * both be near the same point. Fortress and bastion are exactly this case, and it is the pair
    * people ask for most.
    *
    * Set membership is read from the live placements rather than remembered, so a datapack that
    * splits them apart makes this stop refusing on its own.
    */
   public static String whyNotStructurePair(StructureTarget anchor, StructureTarget other) {
      if (anchor == null || other == null) {
         return "That pair isn't something the search can look for.";
      }
      if (anchor == other || anchor.label.equals(other.label)) {
         return "\"" + anchor.label + " next to " + anchor.label + "\" isn't a search — ask for two "
               + "of them instead and it will find two.";
      }
      if (anchor.dim != other.dim) {
         return "\"" + anchor.label + "\" and \"" + other.label + "\" are in different worlds, "
               + "so \"next to\" has no meaning between them.";
      }
      if (sharesStructureSet(anchor, other)) {
         // SHORTENED IN 1.41.4 to fit the feedback band. The old wording ran past 190 characters
         // with the two labels in it, which on a half-screen window was more than the band can hold
         // even at four lines — so the sentence explaining why the pair is impossible was itself cut
         // off. Same three facts, two thirds the length: same rule, one per region, ask for both
         // near spawn instead.
         return "\"" + anchor.label + "\" and \"" + other.label + "\" can't be neighbours — the "
               + "world places only one per region. Both near spawn does work.";
      }
      return null;
   }

   /**
    * "Structure A next to structure B" — the pair both ends of which are buildings.
    *
    * Returns null on success, or a plain-English refusal. The refusals matter more here than for a
    * biome pair, because two structures can be impossible to pair for a reason no player can see:
    * a STRUCTURE SET. Minecraft places at most one winner per region per set, so two structures
    * sharing a set are competing for the same slots and can never both be near the same point.
    * Fortress and bastion are exactly this case, and it is the pair people ask for most.
    *
    * The set membership is read from the live placements rather than remembered, so a datapack that
    * splits them apart makes this stop refusing on its own.
    */
   public String addStructurePair(StructureTarget anchor, StructureTarget other, int within) {
      String refusal = whyNotStructurePair(anchor, other);
      if (refusal != null) {
         return refusal;
      }
      int reach = BiomeShape.clampNextTo(within);
      if (!structures.contains(anchor)) {
         structures.add(anchor);
      }
      // Same widening as a biome anchor: the far end needs room to BE far, or Exact mode's tight
      // per-target radius quietly turns the pair into "both at spawn".
      int farReach = anchor.radius + reach;
      StructureTarget far = other;
      if (far.radius < farReach) {
         StructureTarget widened = far.withRadius(farReach);
         int at = structures.indexOf(far);
         if (at >= 0) {
            structures.set(at, widened);
         } else {
            structures.add(widened);
         }
         far = widened;
      } else if (!structures.contains(far)) {
         structures.add(far);
      }
      adjacencies.add(new Adjacency(null, anchor, null, far, reach));
      return null;
   }

   /** Do these two rows draw from a common structure set — i.e. compete for the same slots? */
   private static boolean sharesStructureSet(StructureTarget a, StructureTarget b) {
      for (Placement pa : a.placements) {
         for (Placement pb : b.placements) {
            if (pa.set().key().equals(pb.set().key())) {
               return true;
            }
         }
      }
      return false;
   }

   // notes() and shortNotes() were REMOVED in 1.37.2 and should not come back as a way to build a
   // banner. They gathered every chosen row's loot warning and handed the lot to the UI, which is
   // how picking "Village with Armorer" produced "can't check: what the armorer villager ends up
   // trading" — an answer to a question the player never asked, attached to a search that had
   // worked perfectly. A row's `cant` is still there, for the one place it belongs: describing that
   // ONE row to someone reading it. What a whole wish cannot promise is a different thing, and it
   // is written by the AI in response to the player's own words.

   /**
    * Full check of one seed. On a hit, human-readable match lines are appended to
    * {@code matchLines}. Thread-safe: only touches shared immutable data.
    */
   public boolean test(long seed, WorldgenContext ctx, List<String> matchLines, boolean fast) {
      return test(seed, ctx, matchLines, fast, null);
   }

   /** Same, also reporting HOW tight the hit is: on a hit, {@code worstOut[0]} gets the
    *  largest distance among the things measured from the overworld spawn — Exact mode's
    *  "aim RIGHT at spawn first" tier sorts backups by it. Only valid when true is returned. */
   public boolean test(long seed, WorldgenContext ctx, List<String> matchLines, boolean fast, int[] worstOut) {
      return test(seed, ctx, matchLines, fast, worstOut, false);
   }

   /**
    * Same, with {@code describe} asking for the Fast-mode match lines a PLAYER should read rather
    * than the ones a search needs.
    *
    * The difference is one call to {@code findSpawnPosition}, and it is not a small one: that
    * search costs ~15ms, which is the whole reason Fast mode runs 33x faster than Exact — Exact
    * pays it on every seed, Fast pays it on none. Doing it for every seed that MATCHED looks
    * harmless (only the winner is ever shown) but a common wish matches one seed in five, and
    * measuring that put Fast mode 5.8x behind its own benchmark.
    *
    * So the hot path leaves it out, and {@link SeedFinder} asks for it once, on the single seed it
    * decided to keep. The undescribed lines are still complete and honest — they just say the
    * finds are measured from 0,0 instead of naming the spawn they are measured against.
    */
   public boolean test(long seed, WorldgenContext ctx, List<String> matchLines, boolean fast, int[] worstOut,
                       boolean describe) {
      return test(seed, ctx, matchLines, fast, worstOut, describe, null);
   }

   /**
    * Same, also collecting every find as structured data in {@code findsOut} when it is non-null.
    *
    * Only ever asked for on the ONE seed being kept, so the extra objects cost nothing on the hot
    * path. See {@link Find} for why the finished sentences alone were not enough.
    */
   public boolean test(long seed, WorldgenContext ctx, List<String> matchLines, boolean fast, int[] worstOut,
                       boolean describe, List<Find> findsOut) {
      List<StructureTarget> targets = searchableStructures();
      int worst = 0;
      if (SeedFunnel.ENABLED) {
         SeedFunnel.seeds++;
      }

      // Stage 0 for every structure target — the cheap mass rejection.
      List<List<Cand>> candidates = new ArrayList<>(targets.size());
      for (StructureTarget t : targets) {
         List<Cand> c = t.stage0(seed, fast);
         if (c.isEmpty()) {
            if (SeedFunnel.ENABLED) {
               SeedFunnel.rejectStage0++;
            }
            return false;
         }
         candidates.add(c);
      }

      // RandomState is the expensive per-seed object (~0.5-2ms) — create it lazily via rsFor
      // only for dimensions actually used, so e.g. a Nether-only wish ("fortress + bastion")
      // never pays for an overworld one it never touches.
      RandomState[] rs = new RandomState[3];
      ChunkGeneratorStructureState[] st = new ChunkGeneratorStructureState[3];

      // GATE: require every structure to truly generate somewhere in reach BEFORE paying
      // for the (expensive) real-spawn search. Rare targets reject most seeds cheaply.
      // Confirms are cached and reused by the exact pass below.
      //
      // Placement only — no assembly. This reaches ~2200 blocks (wherever spawn might land), so a
      // layout check here would assemble structures across an area the radius filter is about to
      // discard. Seeds whose only nearby village lacks the requested building now survive the gate
      // and get rejected a few microseconds later by the exact pass; that is far cheaper than the
      // assembly it avoids.
      List<Map<Cand, BlockPos>> caches = new ArrayList<>(targets.size());
      // The winning variant per candidate, remembered alongside the confirm so the deferred layout
      // check assembles the structure that actually landed there rather than re-deriving it.
      List<Map<Cand, Holder<Structure>>> winners = new ArrayList<>(targets.size());
      for (int i = 0; i < targets.size(); i++) {
         caches.add(new HashMap<>());
         winners.add(new HashMap<>());
      }
      Integer[] order = new Integer[targets.size()];
      for (int i = 0; i < order.length; i++) {
         order[i] = i;
      }
      java.util.Arrays.sort(order, Comparator.comparingInt(i -> candidates.get(i).size()));
      for (int idx : order) {
         StructureTarget t = targets.get(idx);
         List<Cand> cands = candidates.get(idx);
         cands.sort(Comparator.comparingLong(c -> dist2(c.chunk(), 0, 0)));
         Map<Cand, BlockPos> cache = caches.get(idx);
         Map<Cand, Holder<Structure>> won = winners.get(idx);
         boolean any = false;
         for (Cand c : cands) {
            @SuppressWarnings("unchecked")
            Holder<Structure>[] out = new Holder[1];
            BlockPos hit = t.confirm(seed, ctx, rsFor(ctx, rs, t.dim, seed), stFor(ctx, st, rs, t.dim, seed), c, out);
            cache.put(c, hit);
            won.put(c, out[0]);
            if (hit != null) {
               any = true;
               break;
            }
         }
         if (!any) {
            if (SeedFunnel.ENABLED) {
               SeedFunnel.rejectGate++;
            }
            return false;
         }
      }

      // The seed's REAL spawn point — the pricey part, only reached by survivors. Fast mode
      // skips it entirely and measures from 0,0 (classic seed-tool behaviour).
      //
      // EXACT MODE NEEDS IT FOR EVERY DIMENSION, and the version of this line that did not is a bug
      // that shipped. It used to ask whether anything in the wish was in the OVERWORLD, and skip the
      // lookup if not — reasonable-looking, and wrong, because the Nether's origin is DERIVED from
      // the overworld spawn: a portal lit where the player stands comes out at spawn/8. With the
      // lookup skipped, spawn stayed (0,0) and every Nether distance in a Nether-only wish was
      // measured from Nether 0,0, which is a place the player has no particular reason to ever
      // stand. The overworld spawn wanders up to ~2144 blocks, so the portal-in point is up to ~268
      // NETHER blocks from where the search was measuring — a "fortress within 100" could honestly
      // report 100 and be 300 blocks from the portal.
      //
      // Fast mode hid how bad this was, because the Fast re-check at the end of this method measures
      // every find from the real spawn and rejects it if it is outside. So the mode that admits it
      // approximates was verifying the Nether correctly, and the mode that promises to measure from
      // your spawn was not measuring from your spawn at all. That is the same shape as the End
      // phantom: a guarantee written for the overworld and quietly assumed to generalise.
      //
      // Now: in Exact mode, if there is anything to measure, the real spawn is looked up. There is
      // no dimension left whose origin is independent of it — the End was the only one, and it is
      // gone (see the Dim enum). The cost is one findSpawnPosition per surviving seed on a
      // Nether-only Exact wish, which is what every overworld Exact wish has always paid.
      boolean needOverworldSpawn = !fast
            && (!targets.isEmpty() || !biomes.isEmpty() || anyExclude() || !slimes.isEmpty());
      // Fix 3 (APPROXIMATE): a cheap temperature-only look at the search area before paying for
      // the real spawn search. Wanted biomes only - exclusions are handled further down and keep
      // using the exact check, because a prefilter that skips a seed is claiming "the biome is not
      // here", and answering an "X must NOT be near" wish with a guess would hand back seeds that
      // were never actually checked.
      if (SeedFunnel.BIOME_PREFILTER && !biomes.isEmpty() && climatePrefilterRejects(ctx, rs, seed, fast)) {
         if (SeedFunnel.ENABLED) {
            SeedFunnel.rejectBiome++;
         }
         matchLines.clear();
         return false;
      }

      if (SeedFunnel.ENABLED && needOverworldSpawn) {
         SeedFunnel.paidSpawn++;
      }
      BlockPos spawn = needOverworldSpawn ? rsFor(ctx, rs, Dim.OVERWORLD, seed).sampler().findSpawnPosition() : new BlockPos(0, 64, 0);
      // The Fast-mode line this replaces was wrong in a way that cost a real walk. It said
      // "Spawn ~ 0, 0 - the find may be a short walk away". Fast mode measures from 0,0, but the
      // player spawns wherever findSpawnPosition puts them, up to ~2144 blocks off; at the
      // 800-block Fast setting the find can be the better part of a thousand blocks from where
      // they stand. Someone who reads "short walk", loads in and runs /locate is sent to the
      // NEAREST structure, which is a different one from the one this method verified - and its
      // missing armorer reads as a false match by the search.
      //
      // The honest version costs a spawn search, so it is only written when the caller asked to
      // describe this seed. Undescribed, the line still tells the truth about what the numbers
      // below are measured from; it just cannot name where the player will stand.
      int spawnLine = matchLines.size();
      matchLines.add(!fast ? "Your Overworld spawn @ " + spawn.getX() + ", " + spawn.getZ()
            : "Finds below are measured from the Overworld origin (fast mode), not from your spawn");
      // Where each find landed, so it can be RE-MEASURED from the real spawn once that spawn is
      // known — and rejected if it turns out not to be where the player asked for it. Only Fast
      // mode needs this; Exact already measures from the real spawn, so its distances are the
      // honest ones already. See the block at the end of this method.
      // ALLOCATED WHENEVER A RESULT IS BEING BUILT, not only in Fast mode. It began life as
      // Fast-mode-only bookkeeping for the spawn re-check; it is now also where the structured
      // {@link Find} list comes from, and Exact mode needs that just as much.
      List<FastFind> fastFinds = describe ? new ArrayList<>() : null;

      // SLIME CHUNKS GO FIRST, because they are the only thing in this method that costs nothing.
      // Every other test builds some part of the world; this one is a hash of the seed and a chunk
      // coordinate, so a whole radius of them is decided in microseconds. Anything that can reject a
      // seed for free belongs ahead of everything that cannot — the same reasoning that put the
      // assembly last.
      int firstSlimeLine = matchLines.size();
      for (SlimeTarget s : slimes) {
         List<ChunkPos> found = SlimeChunks.near(seed, spawn.getX(), spawn.getZ(), s.radius, s.count);
         if (found.size() < s.count) {
            if (SeedFunnel.ENABLED) {
               SeedFunnel.rejectSlime++;
            }
            matchLines.clear();
            return false;
         }
         matchLines.add(slimeLine(s, found));
      }
      // Plain rows whose strict biome check has been put off until the whole wish has passed.
      // See SeedFunnel.STRICT_DEFERRED: the point is that a seed the jungle is about to reject
      // should never have paid for the village's generation-point test.
      List<Pending> pending = null;
      // Where each structure target ended up, kept per target index so the two passes that can
      // MOVE a find — the deferred strict check and the "next to" search — can rewrite the same
      // line rather than leaving it pointing at a structure they rejected. Only allocated when a
      // "next to" is in play, since nothing else needs the in-range list after the loop.
      boolean anyAdjacent = !adjacencies.isEmpty();
      @SuppressWarnings("unchecked")
      List<Cand>[] inRangeOf = anyAdjacent ? new List[targets.size()] : null;
      int[] lineOf = anyAdjacent ? new int[targets.size()] : null;
      int[] fastOf = anyAdjacent ? new int[targets.size()] : null;
      int[] hitXOf = anyAdjacent ? new int[targets.size()] : null;
      int[] hitZOf = anyAdjacent ? new int[targets.size()] : null;

      for (int i = 0; i < targets.size(); i++) {
         StructureTarget t = targets.get(i);
         int ax = originX(t.dim, spawn);
         int az = originZ(t.dim, spawn);
         // WIDENED BY RADIUS_SLACK. This filter reads the chunk's MIDDLE and the structure is
         // reported at its locate position, so filtering at exactly the radius drops chunks whose
         // real structure position is inside the circle. The exact test below re-tests the reported
         // point and culls the rest — see SeedFunnel.EXACT_RADIUS.
         long r2 = (long) (t.radius + RADIUS_SLACK) * (t.radius + RADIUS_SLACK);
         List<Cand> inRange = new ArrayList<>(4);
         for (Cand c : candidates.get(i)) {
            if (dist2(c.chunk(), ax, az) <= r2) {
               inRange.add(c);
            }
         }
         if (inRange.isEmpty()) {
            if (SeedFunnel.ENABLED) {
               SeedFunnel.rejectDistance++;
            }
            matchLines.clear();
            return false;
         }
         inRange.sort(Comparator.comparingLong(c -> dist2(c.chunk(), ax, az)));
         Map<Cand, BlockPos> cache = caches.get(i);
         Map<Cand, Holder<Structure>> won = winners.get(i);
         // WAIT UNTIL THE WHOLE WISH HAS PASSED ITS CHEAP TESTS. For a plain row the expensive
         // thing is the strict biome test (fix 4b); for a content row it is the assembly itself
         // (fix 6), which is far bigger and sat in exactly the same wrong place. Both are deferred
         // by the same mechanism below, because both are "re-walk the candidates and take the first
         // that passes" — just later, once no other row can still reject this seed for free.
         // A COUNTED ROW IS NEVER DEFERRED. The deferred pass re-walks the candidates and takes the
         // first that passes, which is exactly right for "one of these" and wrong for "three of
         // these" — it would find one and call the row satisfied. Rather than teach the deferred
         // pass to count, a row asking for several does its work inline, where the loop below
         // already knows how to keep going. Counts above one are rare; silently dropping two of the
         // three would not be.
         boolean defer = t.count == 1
               && (!t.needsAssembly()
                     ? SeedFunnel.STRICT_BIOME && SeedFunnel.STRICT_DEFERRED
                     : SeedFunnel.LATE_LAYOUT);
         List<BlockPos> hits = new ArrayList<>(t.count);
         // Where the row's demanded building landed, for the hit at the same index. Null for a row
         // that demands none, and for a deferred row until the deferred pass fills it in.
         List<BlockPos> features = new ArrayList<>(t.count);
         boolean confirmedInRange = false;
         for (Cand c : inRange) {
            BlockPos h;
            if (cache.containsKey(c)) {
               h = cache.get(c);
            } else {
               @SuppressWarnings("unchecked")
               Holder<Structure>[] out = new Holder[1];
               h = t.confirm(seed, ctx, rsFor(ctx, rs, t.dim, seed), stFor(ctx, st, rs, t.dim, seed), c, out);
               cache.put(c, h);
               won.put(c, out[0]);
            }
            if (h == null) {
               continue;
            }
            // THE EXACT RADIUS TEST, on the position that will be printed. This is what stopped a
            // "within 500" search reporting a village at 508.
            if (!withinAsked(h.getX(), h.getZ(), ax, az, t.radius)) {
               if (SeedFunnel.ENABLED) {
                  SeedFunnel.rejectOverRadius++;
               }
               continue;
            }
            confirmedInRange = true;
            // THE EXPENSIVE HALF, and the last thing in the whole funnel: this candidate has
            // already passed placement, biome, the variant pick, the zombie test AND the distance
            // filter, so at most a couple of structures per seed are ever assembled.
            BlockPos[] featureOut = new BlockPos[1];
            if (!defer && !t.layoutMatches(seed, c.chunk(), won.get(c), ctx, ax, az, featureOut)) {
               continue;
            }
            hits.add(h);
            features.add(featureOut[0]);
            if (hits.size() >= t.count) {
               break;
            }
         }
         BlockPos hit = hits.isEmpty() ? null : hits.get(0);
         if (hits.size() < t.count) {
            if (SeedFunnel.ENABLED) {
               // Three different failures, kept apart because they say different things about where
               // the funnel is spending itself. "Nothing confirmed" is a placement miss; "something
               // confirmed but the layout was wrong" is the assembly stage doing its job; and a
               // counted row that found SOME is neither — there were simply not enough in range.
               if (!hits.isEmpty() || !confirmedInRange) {
                  SeedFunnel.rejectDistance++;
               } else {
                  SeedFunnel.rejectLayout++;
               }
            }
            matchLines.clear();
            return false;
         }
         long d = Math.round(Math.hypot(hit.getX() - ax, hit.getZ() - az));
         if (t.dim == Dim.OVERWORLD) {
            worst = (int) Math.max(worst, d);
         }
         String extra = featureNote(t, hits.isEmpty() ? null : features.get(0), hit);
         int fastIndex = -1;
         if (fastFinds != null) {
            // The index this line is ABOUT to take, so the real distance can be checked and
            // appended later. EVERY dimension is recorded now, because this list is also the
            // structured output — but only the ones that can DRIFT are re-measured against the real
            // spawn (see the verification block), and the End cannot: its origin is the arrival
            // platform at 0,0 in both modes.
            fastIndex = fastFinds.size();
            fastFinds.add(new FastFind(matchLines.size(), hit.getX(), hit.getZ(), t.dim, t.radius,
                  t.label, d, extra));
         }
         if (defer) {
            if (pending == null) {
               pending = new ArrayList<>(2);
            }
            pending.add(new Pending(i, t, inRange, cache, won, matchLines.size(), fastIndex, ax, az,
                  hit.getX(), hit.getZ()));
         }
         if (anyAdjacent) {
            inRangeOf[i] = inRange;
            lineOf[i] = matchLines.size();
            fastOf[i] = fastIndex;
            hitXOf[i] = hit.getX();
            hitZOf[i] = hit.getZ();
         }
         matchLines.add(findLine(t.label, t.dim, hit.getX(), hit.getZ(), d, extra, fast));
         // EVERY find of a counted row gets its own line. One line saying "3 Shipwrecks" and a
         // single coordinate would be the same silent lie the count was added to fix — the player
         // asked for three and has to be told where all three are. Numbered from the second,
         // because the first already has the row's own line above.
         for (int k = 1; k < hits.size(); k++) {
            BlockPos more = hits.get(k);
            long dk = Math.round(Math.hypot(more.getX() - ax, more.getZ() - az));
            if (t.dim == Dim.OVERWORLD) {
               worst = (int) Math.max(worst, dk);
            }
            String extraK = featureNote(t, k < features.size() ? features.get(k) : null, more);
            if (fastFinds != null) {
               fastFinds.add(new FastFind(matchLines.size(), more.getX(), more.getZ(), t.dim,
                     t.radius, t.label + " #" + (k + 1), dk, extraK));
            }
            matchLines.add(findLine(t.label + " #" + (k + 1), t.dim, more.getX(), more.getZ(),
                  dk, extraK, fast));
         }
      }

      // Where each wanted biome's patch actually landed. A "next to" is measured from THIS point,
      // not from spawn, which is the whole finding that made adjacency need its own search.
      Map<BiomeTarget, BlockPos> biomeCenters = adjacencies.isEmpty() ? null : new HashMap<>(4);
      for (BiomeTarget bt : biomes) {
         BiomeSource bs = ctx.biomeSource(bt.dim);
         Climate.Sampler sampler = rsFor(ctx, rs, bt.dim, seed).sampler();
         int ox = originX(bt.dim, spawn);
         int oz = originZ(bt.dim, spawn);
         ResourceKey<Biome> want = bt.biome;
         // The coarse-grid shortcut is for a MINIMUM span only: a patch smaller than the step cannot
         // pass a floor anyway, so skipping over it costs nothing. A "small X" request is the
         // opposite question and must keep the fine grid, or the search would step straight past
         // the very patches it was asked to find.
         int step = bt.minSpan > 0 && bt.maxSpan == 0
               ? Math.max(48, Math.min(128, bt.minSpan / 3))
               : 32;
         // At every height this biome occupies — one (Y=64) for a surface biome, the measured
         // underground ones for a cave biome. See biomeHeights.
         BlockPos center = locateBiome(bs, sampler, want, ox, oz, bt.radius, step, seed, SeedFunnel.NEAREST_BIOME);
         if (center == null) {
            if (SeedFunnel.ENABLED) {
               SeedFunnel.rejectBiome++;
            }
            matchLines.clear();
            return false;
         }
         // Zero when the patch is right at this dimension's origin — which is not 0,0 in the Nether:
         // there it is where a portal lit at spawn comes out (see findLine).
         long dist = Math.round(Math.hypot(center.getX() - ox, center.getZ() - oz));
         if (dist > 0) {
            // A SQUARE SCAN, ASKED A CIRCULAR QUESTION. findBiomeHorizontal walks rings of a square
            // and applies no distance test of its own, so a patch found on the diagonal of a
            // 500-block scan sits up to 707 blocks away and was being reported as a match for
            // "within 500". findClosest is on, so anything inside the circle would have been found
            // first — reaching here means there is nothing inside it.
            if (!withinAsked(center.getX(), center.getZ(), ox, oz, bt.radius)) {
               if (SeedFunnel.ENABLED) {
                  SeedFunnel.rejectOverRadius++;
                  SeedFunnel.rejectBiome++;
               }
               matchLines.clear();
               return false;
            }
            if (bt.dim == Dim.OVERWORLD) {
               worst = (int) Math.max(worst, dist);
            }
         }
         String where = "";
         // A CAVE BIOME'S COORDINATES NEED A DEPTH: x and z alone send a player to the surface above it.
         if (underground(want)) {
            where += " — underground, around Y " + center.getY();
         }
         if (bt.sized()) {
            int[] span = biomeSpan(bs, sampler, want, center);
            // The narrow direction for a floor, the WIDE one for a ceiling. A "small" patch that is
            // 32 blocks across and 600 long is not small, and testing only the short side would
            // have called it one.
            int narrow = Math.min(span[0], span[1]);
            int wide = Math.max(span[0], span[1]);
            if ((bt.minSpan > 0 && narrow < bt.minSpan) || (bt.maxSpan > 0 && wide > bt.maxSpan)) {
               if (SeedFunnel.ENABLED) {
                  SeedFunnel.rejectBiome++;
               }
               matchLines.clear();
               return false;
            }
            where += " — spans ~" + span[0] + "×" + span[1];
         }
         if (biomeCenters != null) {
            biomeCenters.put(bt, center);
         }
         // A BIOME DRIFTS EXACTLY LIKE A STRUCTURE and was not being re-measured at all. Fast mode
         // finds the patch nearest 0,0; the player stands somewhere else, so "cherry grove within
         // 100" had the same lie in it as the village did — it was simply never noticed, because
         // only structure lines ever got the "really N from your spawn" suffix.
         if (fastFinds != null) {
            fastFinds.add(new FastFind(matchLines.size(), center.getX(), center.getZ(),
                  bt.dim, bt.radius, bt.label, dist, where.isEmpty() ? null : where.trim()));
         }
         matchLines.add(findLine(bt.label, bt.dim, center.getX(), center.getZ(), dist, where, fast));
      }

      // Exclusions ("not next to"): reject if any forbidden thing is actually near the origin.
      // A pass gets its own ✓ line, so an exclude-ONLY wish still counts as a real match (the final
      // size check needs more than just the spawn line).
      //
      // In Fast mode this pass measures from the world origin like everything else, and is
      // therefore a PREFILTER rather than the answer — the spawn re-check at the end of this method
      // runs it again against the real spawn. See checkExclusions.
      //
      // Being a prefilter, it can reject a seed whose excluded thing is near the origin but NOT
      // near the player, and that is a real cost: some perfectly good seeds are skipped. It is the
      // same trade Fast mode already makes on wanted structures and biomes, which are also filtered
      // by origin distance first and only verified against the real spawn once. Nothing wrong is
      // ever REPORTED either way — the verification pass has the last word — and dropping the
      // prefilter would make an exclude-only wish pay a spawn lookup on literally every seed.
      if (!checkExclusions(seed, ctx, rs, st, spawn, fast, matchLines)) {
         matchLines.clear();
         return false;
      }


      // EVERY CHEAP TEST HAS NOW PASSED. This is the first moment the strict biome check is worth
      // paying for on a plain row: the seed already has all its structures in range, its biomes,
      // and nothing it was told to avoid. Anything rejected here would have been rejected before,
      // just after more wasted work.
      if (pending != null) {
         for (Pending p : pending) {
            BlockPos better = null;
            BlockPos betterFeature = null;
            for (Cand c : p.candidates()) {
               // CONFIRM LAZILY HERE TOO. Reading only the cache looks right and is not: the
               // per-target loop stops at the FIRST candidate that confirms, so every candidate
               // after it is absent from the cache, and treating absent as "confirm said no"
               // silently reduced the deferred pass to testing one candidate. Measured — it
               // changed the result checksum, which is the whole reason that comparison exists.
               StructureTarget pt = p.target();
               BlockPos h;
               if (p.cache().containsKey(c)) {
                  h = p.cache().get(c);
               } else {
                  @SuppressWarnings("unchecked")
                  Holder<Structure>[] out = new Holder[1];
                  h = pt.confirm(seed, ctx, rsFor(ctx, rs, pt.dim, seed),
                        stFor(ctx, st, rs, pt.dim, seed), c, out);
                  p.cache().put(c, h);
                  p.winners().put(c, out[0]);
               }
               if (h == null) {
                  continue;
               }
               // The deferred pass re-walks the candidates itself, so it needs the same exact
               // radius test — otherwise a row whose layout check was deferred could still settle
               // on a structure outside the circle.
               if (!withinAsked(h.getX(), h.getZ(), p.ax(), p.az(), pt.radius)) {
                  if (SeedFunnel.ENABLED) {
                     SeedFunnel.rejectOverRadius++;
                  }
                  continue;
               }
               // p.ax()/p.az() is this row's origin, which a spawn-inside row needs and every other
               // row ignores — the deferred pass would otherwise test the footprint against 0, 0.
               BlockPos[] featureOut = new BlockPos[1];
               if (pt.layoutMatches(seed, c.chunk(), p.winners().get(c), ctx, p.ax(), p.az(), featureOut)) {
                  better = h;
                  betterFeature = featureOut[0];
                  break;
               }
            }
            if (better == null) {
               if (SeedFunnel.ENABLED) {
                  SeedFunnel.rejectLayout++;
               }
               matchLines.clear();
               return false;
            }
            // THE LINE IS REWRITTEN EVEN WHEN THE STRUCTURE DID NOT MOVE, because the deferred pass
            // is where a deferred content row first LEARNS where its building is. The main loop
            // wrote that line before the assembly had run, so it could only name the structure; if
            // this only rewrote on a change of position, every deferred row — which is every
            // single-count content row, i.e. almost all of them — would print without the one fact
            // this whole change is about.
            StructureTarget pt = p.target();
            String extra = featureNote(pt, betterFeature, better);
            long bd = Math.round(Math.hypot(better.getX() - p.ax(), better.getZ() - p.az()));
            if (pt.dim == Dim.OVERWORLD) {
               worst = (int) Math.max(worst, bd);
            }
            matchLines.set(p.lineIndex(),
                  findLine(pt.label, pt.dim, better.getX(), better.getZ(), bd, extra, fast));
            // Usually the same structure the line already names. When it is not, the FIRST
            // candidate was a place vanilla builds nothing at and a later one is real — so
            // everything that remembers a position has to move with it.
            if (fastFinds != null && p.fastIndex() >= 0) {
               // The Fast-mode spawn re-check reads these coordinates; leaving the old ones
               // here would verify a structure the funnel had already discarded — and then
               // print its distance against the one it kept.
               FastFind entry = fastFinds.get(p.fastIndex());
               entry.x = better.getX();
               entry.z = better.getZ();
               entry.distance = bd;
               entry.extra = extra;
            }
            if (better.getX() != p.x() || better.getZ() != p.z()) {
               if (anyAdjacent) {
                  hitXOf[p.index()] = better.getX();
                  hitZOf[p.index()] = better.getZ();
               }
            }
         }
      }

      // ── "NEXT TO", and it is last for a measured reason ──────────────────────────────────────
      // Every other test in this method reuses work the funnel was already doing. This one starts a
      // NEW search per pair, anchored on the biome patch rather than on spawn, because the cheap
      // version — subtracting two positions the funnel already held — was 2x to 8x too far on every
      // pair measured. So it is the only test here that can be described as extra work, and it runs
      // on the seeds that have already satisfied everything else, which is a tiny fraction.
      if (anyAdjacent) {
         for (Adjacency adj : adjacencies) {
            BlockPos from;
            if (adj.anchorStructure != null) {
               // A STRUCTURE ANCHOR measures from the structure the funnel already settled on, so
               // it costs nothing to establish — unlike a biome anchor, which had to be located.
               int ai = targets.indexOf(adj.anchorStructure);
               if (ai < 0 || inRangeOf == null || inRangeOf[ai] == null) {
                  matchLines.clear();
                  return false;
               }
               from = new BlockPos(hitXOf[ai], 64, hitZOf[ai]);
            } else {
               from = biomeCenters == null ? null : biomeCenters.get(adj.anchor);
            }
            if (from == null) {
               // The anchor is put into `biomes` (or `structures`) by every path that builds an
               // Adjacency, so this means a caller assembled one by hand and forgot. Fail rather
               // than silently drop the requirement: a "next to" that quietly does nothing is the
               // failure mode this whole feature exists to avoid.
               matchLines.clear();
               return false;
            }
            long d;
            String what;
            if (adj.otherBiome != null) {
               BiomeTarget ob = adj.otherBiome;
               BiomeSource bs = ctx.biomeSource(ob.dim);
               Climate.Sampler sampler = rsFor(ctx, rs, ob.dim, seed).sampler();
               if (SeedFunnel.ENABLED) {
                  SeedFunnel.adjacentSearches++;
               }
               // findClosest TRUE, and here it is not an optimisation but the question itself: "is
               // there one of these near the pale garden" is answered by the nearest one and by
               // nothing else. At every height the other biome occupies (biomeHeights).
               BlockPos near = locateBiome(bs, sampler, ob.biome, from.getX(), from.getZ(), adj.within,
                     BiomeShape.stepFor(adj.within), seed, true);
               if (near == null) {
                  if (SeedFunnel.ENABLED) {
                     SeedFunnel.rejectAdjacent++;
                  }
                  matchLines.clear();
                  return false;
               }
               d = Math.round(Math.hypot(near.getX() - from.getX(), near.getZ() - from.getZ()));
               if (d > 0) {
                  // THE BIOME END OF A PAIR HAD NO UPPER CHECK. The structure end below has always
                  // had one ("nothing nearer exists, so stop looking"); this branch simply reported
                  // whatever the square scan came back with, so a pair asked for "within 512" could
                  // print "is 690 blocks from that pale garden (asked for 512 or less)" — a line
                  // that contradicts itself.
                  if (d > adj.within) {
                     if (SeedFunnel.ENABLED) {
                        SeedFunnel.rejectOverRadius++;
                        SeedFunnel.rejectAdjacent++;
                     }
                     matchLines.clear();
                     return false;
                  }
               }
               what = ob.label;
            } else {
               // A structure is not searched again — the funnel already enumerated every candidate
               // in range and knows which confirmed. All that changes is the ORDER they are tried
               // in: nearest to the biome instead of nearest to spawn. That re-sort is free, and it
               // matters, because the village nearest the player is routinely not the one nearest
               // the cherry grove they asked it to be beside.
               int idx = targets.indexOf(adj.otherStructure);
               if (idx < 0 || inRangeOf[idx] == null) {
                  matchLines.clear();
                  return false;
               }
               StructureTarget t = targets.get(idx);
               List<Cand> cands = new ArrayList<>(inRangeOf[idx]);
               final int fx = from.getX();
               final int fz = from.getZ();
               cands.sort(Comparator.comparingLong(c -> dist2(c.chunk(), fx, fz)));
               Map<Cand, BlockPos> cache = caches.get(idx);
               Map<Cand, Holder<Structure>> won = winners.get(idx);
               BlockPos best = null;
               for (Cand c : cands) {
                  BlockPos h;
                  if (cache.containsKey(c)) {
                     h = cache.get(c);
                  } else {
                     @SuppressWarnings("unchecked")
                     Holder<Structure>[] out = new Holder[1];
                     h = t.confirm(seed, ctx, rsFor(ctx, rs, t.dim, seed),
                           stFor(ctx, st, rs, t.dim, seed), c, out);
                     cache.put(c, h);
                     won.put(c, out[0]);
                  }
                  if (h == null || !t.layoutMatches(seed, c.chunk(), won.get(c), ctx,
                        originX(t.dim, spawn), originZ(t.dim, spawn))) {
                     continue;
                  }
                  best = h;
                  break;   // sorted by distance to the anchor, so the first survivor is the nearest
               }
               if (best == null) {
                  if (SeedFunnel.ENABLED) {
                     SeedFunnel.rejectAdjacent++;
                  }
                  matchLines.clear();
                  return false;
               }
               d = Math.round(Math.hypot(best.getX() - fx, best.getZ() - fz));
               if (d > adj.within) {
                  // Nothing nearer exists, so there is no point looking further down the list.
                  if (SeedFunnel.ENABLED) {
                     SeedFunnel.rejectAdjacent++;
                  }
                  matchLines.clear();
                  return false;
               }
               // The structure's own line still names whichever one was nearest SPAWN. If the
               // adjacency settled on a different one, the line has to follow — otherwise the wish
               // says "village next to the grove" and the coordinates send the player to a village
               // that is not the one that passed.
               if (best.getX() != hitXOf[idx] || best.getZ() != hitZOf[idx]) {
                  int ax = originX(t.dim, spawn);
                  int az = originZ(t.dim, spawn);
                  long fromSpawn = Math.round(Math.hypot(best.getX() - ax, best.getZ() - az));
                  if (t.dim == Dim.OVERWORLD) {
                     worst = (int) Math.max(worst, fromSpawn);
                  }
                  matchLines.set(lineOf[idx],
                        findLine(t.label, t.dim, best.getX(), best.getZ(), fromSpawn, null, fast));
                  hitXOf[idx] = best.getX();
                  hitZOf[idx] = best.getZ();
                  if (fastFinds != null && fastOf[idx] >= 0) {
                     FastFind entry = fastFinds.get(fastOf[idx]);
                     entry.x = best.getX();
                     entry.z = best.getZ();
                     entry.distance = fromSpawn;
                  }
               }
               what = t.label;
            }
            matchLines.add("↔ " + what + " is " + d + " blocks from that " + adj.anchorLabel()
                  + " (asked for " + adj.within + " or less)");
         }
      }

      // ── THE ONE SPAWN LOOKUP, AND WHAT IT IS NOW FOR ────────────────────────────────────────
      //
      // This block used to only DESCRIBE. It fetched the real spawn so the match lines could say
      // how far away things really were, and then reported whatever number came out. That is how
      // "Fast mode: within 100 blocks" produced a village 139 blocks from spawn and said so on its
      // own confirmation line: the app measured the right thing, printed the right thing, and
      // still handed back a different number from the one that was asked for.
      //
      // It now VERIFIES as well. Every find is re-measured from the real spawn against the reach
      // it was searched with, and a seed with anything outside that is rejected — the search just
      // carries on. The number on the control is now the number the result honours.
      //
      // The cost is one findSpawnPosition per HIT, never per seed, which is the whole reason Fast
      // mode is fast. Measured by `gradlew spawnDrift`: the lookup is 10.6ms, and 2.5%–9.7% of
      // hits are rejected depending on the distance setting, so a result costs about one extra
      // lookup on average. The reason that rate is so low is the other half of the measurement —
      // the real spawn is a MEDIAN OF 40 BLOCKS from 0,0 (p75 167, p95 608), so most of the time
      // the two origins are nearly the same point and the fast pass was already right. It simply
      // had no way to know that, and no way to catch the times it was not.
      //
      // Rejecting on the find the fast pass settled on is slightly pessimistic: a DIFFERENT
      // structure of the same kind might be sitting next to the player. Re-running the whole wish
      // from the real spawn would catch those and was measured too — it keeps 91.8%–99.3% against
      // this check's 90.3%–97.5%, a difference inside the noise, for 35% more time per hit.
      if (fastFinds != null && fast) {
         BlockPos real = rsFor(ctx, rs, Dim.OVERWORLD, seed).sampler().findSpawnPosition();
         // EVERY find, in EVERY dimension, with no exceptions left in this loop. The one that used
         // to be here skipped the End, on the correct reasoning that its origin is the arrival
         // platform at 0,0 in both modes and there was nothing to re-measure; the End is gone, and
         // with it the only case where a find was not re-measured. A Nether find IS re-measured, in
         // Nether coordinates, against the portal-in point derived from the real spawn — see
         // originX/originZ. `gradlew netherDiag` measures it rather than asserting it here: every
         // Nether row, both modes, every printed distance re-measured from the portal-in point.
         // (That task did not exist when this comment was first written, which is a lesson of its
         // own — a diagnostic named in a paragraph is a claim, not a check.)
         for (FastFind f : fastFinds) {
            f.away = Math.round(Math.hypot(f.x - originX(f.dim, real), f.z - originZ(f.dim, real)));
            if (f.away > f.radius) {
               if (SeedFunnel.ENABLED) {
                  SeedFunnel.rejectSpawnDistance++;
               }
               matchLines.clear();
               return false;
            }
         }
         // SLIME CHUNKS ARE RE-COUNTED rather than re-measured, because that row is a count and
         // not a distance: "3 within 64 blocks of spawn" is false when only two of them are near
         // the player, however close the third is to the origin. It costs microseconds — it is a
         // hash of the seed, which is why it was cheap enough to be worth checking twice.
         for (int i = 0; i < slimes.size(); i++) {
            SlimeTarget s = slimes.get(i);
            List<ChunkPos> found = SlimeChunks.near(seed, real.getX(), real.getZ(), s.radius, s.count);
            if (found.size() < s.count) {
               if (SeedFunnel.ENABLED) {
                  SeedFunnel.rejectSpawnDistance++;
               }
               matchLines.clear();
               return false;
            }
            // Rewritten rather than annotated: the coordinates on that line are the chunks near
            // the origin, and the ones worth walking to are the chunks near the player.
            matchLines.set(firstSlimeLine + i, slimeLine(s, found));
         }
         // EXCLUSIONS, RE-ASKED FROM THE REAL SPAWN — the last row type that was still answering
         // about the world origin. "No desert near spawn" checked around 0,0, so a seed with a
         // desert 80 blocks from where the player actually lands could pass it. Same shape as every
         // other Fast-mode re-check and the same budget: at most once per hit, on a seed that has
         // already matched everything else.
         //
         // Lines are NOT passed, on purpose: the prefilter pass above already added one "No X
         // nearby ✓" per row, and this run is deciding the verdict, not describing it.
         if (!checkExclusions(seed, ctx, rs, st, real, false, null)) {
            if (SeedFunnel.ENABLED) {
               SeedFunnel.rejectSpawnDistance++;
            }
            matchLines.clear();
            return false;
         }
         // Only now, with every find confirmed to be inside the reach that was asked for, are the
         // lines worth writing. Appending keeps every recorded index valid.
         for (FastFind f : fastFinds) {
            if (f.away >= 0) {
               // "— checked" was dropped in 1.40.0. It announced that a check had passed, on every
               // line, forever — which is the app talking about itself rather than about the world.
               // A find that failed the check is not in this list at all, so its absence is the
               // only evidence anyone needs.
               matchLines.set(f.lineIndex, matchLines.get(f.lineIndex)
                     + " (" + f.away + " from your spawn)");
            }
         }
         // Both facts, because each find now carries two distances: the one on the line is from
         // the origin (what Fast mode aimed at) and the trailing one is from where the player will
         // actually stand. Naming only the spawn would leave the first number looking wrong.
         //
         // "the world origin" rather than "0, 0" on purpose: withoutCoords hides ANY pair of
         // numbers, so spelling the origin in digits here would have come out as "aimed at ?, ?"
         // for anyone with spoilers off — hiding the one number on the line that is not a secret.
         // ONLY WHEN THERE ARE DISTANCES TO EXPLAIN (1.44.2). A slime-chunk or "no desert" wish has
         // no find with two distances and no coordinates to go to, so this paragraph and the /locate
         // instruction were explaining numbers that were not on the screen.
         if (!fastFinds.isEmpty()) {
            matchLines.set(spawnLine, "Your Overworld spawn @ " + real.getX() + ", " + real.getZ()
                  + " - fast mode aimed at the world origin, so the first distance on each line is"
                  + " measured from there and the second one from where you will be standing");
            // The instruction, not a hint. Every walk this project has recorded as a "wrong
            // prediction" was /locate being asked a different question and answering it correctly.
            matchLines.add(LOCATE_TIP);
         } else {
            matchLines.set(spawnLine, "Your Overworld spawn @ " + real.getX() + ", " + real.getZ());
         }
      }
      // The structured output, handed over only once every check has passed — so a caller can never
      // be holding finds for a seed that was then rejected.
      if (findsOut != null && fastFinds != null) {
         for (FastFind f : fastFinds) {
            findsOut.add(f.toFind());
         }
      }
      if (worstOut != null && worstOut.length > 0) {
         worstOut[0] = worst;
      }
      if (SeedFunnel.ENABLED && matchLines.size() > 1) {
         SeedFunnel.passed++;
      }
      return matchLines.size() > 1;// spawn line is always present; need a real match too
   }

   /**
    * Every "must NOT be nearby" row, tested against one origin.
    *
    * PULLED OUT OF {@link #test} IN 1.40.0 so it can be run TWICE. Fast mode measures from the
    * world origin, and until now that was where an exclusion's answer came from and stayed — so
    * "no desert near spawn" in Fast mode was a promise about the area around 0,0, not about the
    * area around the player. Every other kind of row had already been fixed to re-check against the
    * real spawn; this was the last one still answering a different question from the one asked.
    *
    * Running it twice is affordable for the same reason the rest of the re-check is: the first run
    * is a cheap prefilter on most seeds, and the second only ever happens on a seed that has
    * already matched everything, at most once per hit.
    *
    * {@code lines} may be null, which is what the re-check passes: the second run must decide the
    * verdict without adding a duplicate set of "No X nearby ✓" lines to a list that already has them.
    */
   private boolean checkExclusions(long seed, WorldgenContext ctx, RandomState[] rs,
                                   ChunkGeneratorStructureState[] st, BlockPos origin, boolean fast,
                                   List<String> lines) {
      for (StructureTarget ex : excludeStructures) {
         int ax = originX(ex.dim, origin);
         int az = originZ(ex.dim, origin);
         // WIDENED, then exact-tested, exactly like a wanted row — and getting this wrong is worse
         // on an exclusion than on a want. The prefilter reads the chunk middle and the structure
         // really sits at its locate position, so filtering at exactly the radius could skip a
         // structure that IS inside the circle and then print "No X nearby ✓" about it.
         long r2 = (long) (ex.radius + RADIUS_SLACK) * (ex.radius + RADIUS_SLACK);
         for (Cand c : ex.stage0(seed, fast)) {
            if (dist2(c.chunk(), ax, az) > r2) {
               continue;
            }
            @SuppressWarnings("unchecked")
            Holder<Structure>[] out = new Holder[1];
            BlockPos hit = ex.confirm(seed, ctx, rsFor(ctx, rs, ex.dim, seed),
                  stFor(ctx, st, rs, ex.dim, seed), c, out);
            if (hit == null) {
               continue;
            }
            if (!withinAsked(hit.getX(), hit.getZ(), ax, az, ex.radius)) {
               continue;
            }
            // The SAME final test the wanted rows get, and an exclusion needs it for two separate
            // reasons. A row carrying a piece token means something narrower than its structure —
            // "no Capsized Shipwreck nearby" is about upside-down wrecks, and rejecting every
            // shipwreck would quietly answer a question nobody asked. And for a plain row it is
            // the strict biome check: without it, a village vanilla will never actually build
            // still throws away a seed that was fine.
            if (!ex.layoutMatches(seed, c.chunk(), out[0], ctx)) {
               continue;
            }
            if (SeedFunnel.ENABLED) {
               SeedFunnel.rejectExclude++;
            }
            return false;
         }
         if (lines != null) {
            lines.add("No " + ex.label + " nearby ✓");
         }
      }
      for (BiomeTarget ex : excludeBiomes) {
         BiomeSource bs = ctx.biomeSource(ex.dim);
         Climate.Sampler sampler = rsFor(ctx, rs, ex.dim, seed).sampler();
         int ox = originX(ex.dim, origin);
         int oz = originZ(ex.dim, origin);
         // An exclusion only asks WHETHER one exists, so which patch comes back cannot change the
         // verdict. At every height the biome occupies: "no deep dark near spawn" used to look at
         // Y=64 only, where deep dark never is, so it passed every seed and rejected nothing.
         BlockPos found = locateBiome(bs, sampler, ex.biome, ox, oz, ex.radius, 32, seed, SeedFunnel.NEAREST_BIOME);
         // The square-scan correction again, and on an exclusion it cuts the other way: a patch found
         // on the diagonal beyond the asked radius is NOT "nearby", so counting it would reject a
         // seed that was fine.
         boolean near = found != null && withinAsked(found.getX(), found.getZ(), ox, oz, ex.radius);
         if (near) {
            if (SeedFunnel.ENABLED) {
               SeedFunnel.rejectExclude++;
            }
            return false;
         }
         if (lines != null) {
            lines.add("No " + ex.label + " nearby ✓");
         }
      }
      return true;
   }

   // ── How a find is written for a player ────────────────────────────────────

   /**
    * ONE place builds every "here is what I found" line, and it exists because the old one-liner
    * cost a real play session.
    *
    * The old format put the dimension in a bracket only when it was not the overworld —
    * "Bastion Remnant @ 210, -48 (240 blocks, Nether)" — and a player read that as a distance from
    * spawn, built a portal at spawn, and found nothing. Both halves were misleading at once: 240
    * is a NETHER distance, and the coordinates are NETHER coordinates, and nothing on the line
    * said which of the two numbers you were supposed to walk.
    *
    * So the dimension is now stated on EVERY line including the overworld's — the point is not to
    * flag the unusual case, it is to remove the ambiguity — and the Nether and End lines spell out
    * the journey rather than assuming the reader already knows the model. They are longer, and
    * that is the correct trade for the one line a player acts on.
    */
   static String findLine(String label, Dim dim, int x, int z, long distance, String extra) {
      return findLine(label, dim, x, z, distance, extra, false);
   }

   /**
    * Same, told whether the distance was measured from the WORLD ORIGIN rather than from the
    * player's spawn — which is what Fast mode does and what this line used to hide.
    *
    * The old wording was the smaller half of the same bug the spawn re-check fixes. Fast mode
    * writes a header saying "finds below are measured from the Overworld origin, not from your
    * spawn" and then wrote every find underneath it as "138 blocks from spawn". One of those two
    * sentences had to be wrong, and it was the one attached to the number people read.
    *
    * Fast mode's lines get a second distance appended once the seed has been checked against the
    * real spawn, so the reader ends up with both facts and neither of them mislabelled.
    */
   static String findLine(String label, Dim dim, int x, int z, long distance, String extra,
                          boolean fromOrigin) {
      String tail = extra == null ? "" : extra;
      return switch (dim) {
         case NETHER -> label + " — NETHER coords " + x + ", " + z + " · " + distance
               + (fromOrigin
                     ? " blocks from the Nether origin"
                     : " blocks from where you arrive if you build a portal at your Overworld spawn") + tail;
         // "Right at spawn" is only ever said about the overworld, and only when it is true. A
         // Nether row is never right at spawn: its origin is a portal exit, and calling that
         // "spawn" is the confusion this whole method is fixing — and in Fast mode the overworld
         // row is not at spawn either, it is at the origin.
         default -> fromOrigin
               ? label + " — Overworld " + x + ", " + z + " · " + distance
                     + " blocks from the world origin" + tail
               : distance <= AT_SPAWN
                     ? label + " — Overworld " + x + ", " + z + " · right at spawn" + tail
                     : label + " — Overworld " + x + ", " + z + " · " + distance + " blocks from spawn" + tail;
      };
   }

   /** Close enough that "right at spawn" is a fair description rather than a flourish. */
   private static final int AT_SPAWN = 48;

   /**
    * THE TAIL THAT SAYS WHERE THE BUILDING IS, which is the fact a content row exists to deliver
    * and the one it never used to print.
    *
    * Every line names the STRUCTURE's locate position, because that is the point the radius is
    * measured against and it has to stay that way — "a village within 100" is a promise about the
    * village. But the row promised a weaponsmith, and for a 150-piece village the weaponsmith is
    * somewhere else in it. {@code featureDiag} put the gap at a median of 28 blocks and up to 114
    * for the ancient city's sauna, with zero false claims, which is the exact shape of a report
    * that reads as "the search lied" and is not: the player was standing in the right village,
    * facing the wrong way, with nothing on screen telling them how far to look.
    *
    * ONLY WHEN IT IS WORTH SAYING. Under {@link #SAME_SPOT} blocks the building is where the player
    * lands and a second coordinate is noise on a line that is already long — the bastion types come
    * out at 1 to 14 blocks, and printing "the treasure wing is at 1 block away" would be the app
    * padding its own answer.
    */
   static String featureNote(StructureTarget t, BlockPos feature, BlockPos structure) {
      if (feature == null || structure == null || t.building == null || t.without) {
         return null;
      }
      long away = Math.round(Math.hypot(feature.getX() - structure.getX(),
            feature.getZ() - structure.getZ()));
      // A VILLAGE HOUSE IS SMALLER THAN SAME_SPOT. smithDiag caught it in 1.43.1: the weaponsmith 8-11
      // blocks from the village coordinate had no second line, so the player was sent to a spot
      // NEXT TO the house rather than on it. The rule is for pieces the player lands inside anyway
      // (a bastion wing); a village building always gets its own coordinate.
      boolean village = t.label.toLowerCase(java.util.Locale.ROOT).contains("village");
      if (away < SAME_SPOT && !village) {
         return null;
      }
      return " — " + t.featureWord() + " " + away + " blocks further on, at "
            + feature.getX() + ", " + feature.getZ();
   }

   /** Below this the building is effectively where the player lands. See {@link #featureNote}. */
   private static final int SAME_SPOT = 12;

   /**
    * How much wider than the asked radius the CHEAP prefilters reach, so the exact test can see
    * every candidate that might still qualify.
    *
    * A candidate is filtered on its chunk's middle block and reported at its locate position, which
    * is the chunk's min corner plus the placement's offset — up to 11.3 blocks apart on the
    * diagonal. Filtering at exactly {@code radius} therefore throws away chunks whose real structure
    * position IS inside the circle. 16 covers the worst case with room to spare, and the exact test
    * immediately below culls whatever the widening let through, so the only cost is a few more
    * candidates considered per seed.
    */
   static final int RADIUS_SLACK = 16;

   /**
    * Is the point that will be PRINTED really within the radius that was ASKED FOR?
    *
    * ROUNDED THE SAME WAY findLine ROUNDS, and that detail is the whole difference between a fix
    * and a slightly different bug. Comparing exact squared distances instead dropped a seed whose
    * village sat 500.4 blocks away on a "500 or less" search — correct to the millimetre, and the
    * line for that seed said "500 blocks from spawn", so from the player's side a find that read as
    * exactly on the limit was being thrown away for being over it.
    *
    * The promise is about the NUMBER SHOWN. Measuring the same quantity the line prints makes it
    * airtight in the only terms anybody can check: no match line can ever claim a distance greater
    * than the one that was asked for.
    */
   private static boolean withinAsked(int x, int z, int ax, int az, int radius) {
      if (!SeedFunnel.EXACT_RADIUS) {
         return true;
      }
      return Math.round(Math.hypot((double) x - ax, (double) z - az)) <= radius;
   }

   /**
    * ONE THING THE SEARCH FOUND, as data rather than as a sentence.
    *
    * Added in 1.40.0 because two separate features needed the same thing and neither could have it:
    * the join message wanted a SHORT form ("Woodland Mansion - 80, 16 (82 blocks)") and the in-game
    * map wants a dot to draw. Both were impossible while the only output was finished English
    * sentences, and both would otherwise have ended up parsing those sentences back apart with a
    * regex — which is how the spoiler filter came to be written, and how it came to miss the slime
    * row and leak coordinates to somebody who had asked not to be told.
    *
    * {@code fromSpawn} is -1 unless Fast mode verified it against the real spawn, in which case it
    * is the true distance from where the player will stand and {@code distance} is the one measured
    * from the world origin. In Exact mode those are the same question, so only distance is filled.
    */
   public record Find(String label, Dim dim, int x, int z, long distance, long fromSpawn,
                      String extra) {
      /** The distance a player should be told: the one measured from where they will stand. */
      public long shown() {
         return fromSpawn >= 0 ? fromSpawn : distance;
      }
   }

   /**
    * One Fast-mode find, held so it can be re-measured from the real spawn at the end of
    * {@link #test} and the seed thrown away if it turns out not to be where it was asked to be.
    *
    * MUTABLE, and it has to be: two later passes can decide a different structure is the one this
    * row really matched — the deferred layout check and the "next to" search — and the coordinates
    * that get verified must be the coordinates that get printed. When these were a plain
    * {@code int[]} that coupling was implicit and easy to miss; naming the fields makes the two
    * places that rewrite them obvious.
    *
    * {@code radius} is the reach THIS find was searched with rather than one global number,
    * because a composed bundle gives each item its own — the whole point of the check is holding
    * each find to the distance the player was actually promised for it.
    */
   private static final class FastFind {
      final int lineIndex;
      final Dim dim;
      final int radius;
      final String label;
      /** Mutable for the same reason x and z are: the deferred layout pass is where a deferred
       *  content row learns where its building is, and that is after this was created. */
      String extra;
      int x;
      int z;
      long distance;
      long away = -1;

      FastFind(int lineIndex, int x, int z, Dim dim, int radius, String label, long distance,
               String extra) {
         this.lineIndex = lineIndex;
         this.x = x;
         this.z = z;
         this.dim = dim;
         this.radius = radius;
         this.label = label;
         this.distance = distance;
         this.extra = extra;
      }

      Find toFind() {
         return new Find(label, dim, x, z, distance, away, extra);
      }
   }

   /**
    * The slime row's line, built in ONE place because it is written twice.
    *
    * Fast mode writes it against the origin and then rewrites it against the real spawn once that
    * is known, and two copies of this formatting would be two chances for the two lines to differ
    * in a way nobody would notice until a player compared them.
    */
   private static String slimeLine(SlimeTarget s, List<ChunkPos> found) {
      StringBuilder where = new StringBuilder();
      for (ChunkPos p : found) {
         if (where.length() > 0) {
            where.append(" · ");
         }
         // The chunk CENTRE, because that is what a player can walk to. A chunk coordinate on its
         // own ("chunk 3, -7") is not something anyone can type into anything.
         where.append(p.getMiddleBlockX()).append(", ").append(p.getMiddleBlockZ());
      }
      return s.label() + " — " + where;
   }

   /**
    * The same line with the coordinates removed — what the spoiler-free setting prints.
    *
    * Distances are deliberately KEPT: the setting is about not being told WHERE things are, and
    * "300 blocks away" is the part that makes the confirmation worth reading at all.
    *
    * IT MATCHES ANY PAIR OF NUMBERS NOW, and the reason is a bug this shipped with. It used to
    * match three specific shapes — "coords X, Z", "Overworld X, Z" and "@ X, Z" — on the reasoning
    * that every line is built by {@link #findLine} and therefore has one of them. That reasoning
    * was wrong the day it was written: the SLIME row does not go through findLine. It writes its
    * own line ("3 slime chunks ≤64 — 24, -40 · -8, 56"), matched none of the three patterns, and
    * printed exact chunk coordinates into the chat of someone who had turned spoilers OFF.
    *
    * A list of allowed shapes is the wrong design for this. Every new line is a new chance to
    * forget, the forgetting is silent, and the cost lands on the one person who asked not to be
    * told. So the rule is inverted: a pair of numbers separated by a comma is treated as a
    * location and hidden, whatever line it is on and whoever wrote it. A future line that prints
    * coordinates is covered before anybody thinks about it.
    *
    * The one line that legitimately said "0, 0" (Fast mode explaining what it measured from) was
    * reworded to say "the world origin" rather than being excepted here — an exception is the
    * same allowlist by another name.
    */
   public static String withoutCoords(String line) {
      return line == null ? null : line.replaceAll("-?\\d+, -?\\d+", "?, ?");
   }

   // ── per-dimension helpers ─────────────────────────────────────────────────

   private static int originX(Dim dim, BlockPos spawn) {
      return dim == Dim.OVERWORLD ? spawn.getX() : dim == Dim.NETHER ? spawn.getX() / 8 : 0;
   }

   private static int originZ(Dim dim, BlockPos spawn) {
      return dim == Dim.OVERWORLD ? spawn.getZ() : dim == Dim.NETHER ? spawn.getZ() / 8 : 0;
   }

   private static long dist2(ChunkPos c, int ax, int az) {
      long dx = c.getMiddleBlockX() - ax;
      long dz = c.getMiddleBlockZ() - az;
      return dx * dx + dz * dz;
   }

   // anyDim() and anyBiomeDim() were removed with their only caller in 1.41.4. They existed to ask
   // "is anything here in the OVERWORLD", which was the question that let a Nether-only Exact search
   // skip the spawn lookup and measure from the wrong origin. Left out rather than kept: a helper
   // whose whole purpose was to single out one dimension is the tool that builds the next version of
   // that bug.

   /**
    * Any "must NOT be nearby" row at all, in any dimension.
    *
    * It used to ask only about the OVERWORLD ones, for the same reason and with the same fault as
    * the spawn lookup it feeds — an exclusion is measured from its dimension's origin, and every
    * dimension's origin now comes from the overworld spawn. "No bastion near where my portal comes
    * out" checked around Nether 0,0.
    */
   private boolean anyExclude() {
      return !excludeStructures.isEmpty() || !excludeBiomes.isEmpty();
   }

   /** How far apart the prefilter's temperature probes sit. Coarse on purpose: the whole point is
    *  to cost far less than the spawn search it guards. Finer means fewer false skips and less
    *  saving; this is the accuracy/speed dial. */
   private static final int PREFILTER_STEP = 512;

   /**
    * True when NO probe in the search area lands in the wanted biome's temperature band, which
    * means the biome is very unlikely to be there and the seed can be dropped before the
    * expensive work. Approximate in both directions described on
    * {@link WorldgenContext#temperatureBand}, so it is only ever used to skip WANTED biomes.
    */
   private boolean climatePrefilterRejects(WorldgenContext ctx, RandomState[] rs, long seed, boolean fast) {
      for (BiomeTarget bt : biomes) {
         long[] band = ctx.temperatureBand(bt.dim, bt.biome);
         if (band.length == 0) {
            continue; // no band available (the End) - leave this target to the exact check
         }
         // Same area the real search will cover: centred on 0,0 and widened by the spawn wander,
         // because in Exact mode the spawn this will be measured from is not known yet.
         int wander = fast ? 0 : bt.dim == Dim.OVERWORLD ? SPAWN_WANDER
               : bt.dim == Dim.NETHER ? SPAWN_WANDER_NETHER : 0;
         int reach = bt.radius + wander;
         if (SeedFunnel.ENABLED) {
            SeedFunnel.prefilterRuns++;
         }
         DensityFunction temp = rsFor(ctx, rs, bt.dim, seed).sampler().temperature();
         boolean any = false;
         for (int x = -reach; x <= reach && !any; x += PREFILTER_STEP) {
            for (int z = -reach; z <= reach; z += PREFILTER_STEP) {
               // y=64, and the height does not matter here: the overworld's temperature noise does not
               // vary with height, so this band test is the same for a cave biome searched underground.
               long t = Climate.quantizeCoord(
                     (float) temp.compute(new DensityFunction.SinglePointContext(x, 64, z)));
               if (t >= band[0] && t <= band[1]) {
                  any = true;
                  break;
               }
            }
         }
         if (!any) {
            if (SeedFunnel.ENABLED) {
               SeedFunnel.prefilterSkips++;
            }
            return true;
         }
      }
      return false;
   }

   private static RandomState rsFor(WorldgenContext ctx, RandomState[] rs, Dim dim, long seed) {
      int i = dim.ordinal();
      if (rs[i] == null) {
         rs[i] = ctx.randomState(dim, seed);
      }
      return rs[i];
   }

   private static ChunkGeneratorStructureState stFor(WorldgenContext ctx, ChunkGeneratorStructureState[] st, RandomState[] rs, Dim dim, long seed) {
      int i = dim.ordinal();
      if (st[i] == null) {
         st[i] = ctx.structureState(dim, seed, rsFor(ctx, rs, dim, seed));
      }
      return st[i];
   }

   /** Rough size of the biome patch containing {@code center}: ray-walk ±x/±z, then re-measure
    *  once from the patch middle so an edge hit doesn't understate the size. Returns {xSpan, zSpan}. */
   /** Package-private so a diagnostic can measure THIS code rather than a lookalike — the same
    *  reason the walk tool reads its token out of the catalog instead of retyping it. Size tiers
    *  are only honest if the thresholds were derived from the measurement the search will run. */
   static int[] biomeSpan(BiomeSource bs, Climate.Sampler sampler, ResourceKey<Biome> want, BlockPos center) {
      // Measured at the patch's own height: Y=64 for every surface biome, as before, and underground
      // for a cave biome (see biomeHeights) — whose patch at Y=64 is usually not there at all.
      int y = center.getY();
      int[] r = rays(bs, sampler, want, center.getX(), y, center.getZ());
      int midX = center.getX() + (r[0] - r[1]) / 2;
      int midZ = center.getZ() + (r[2] - r[3]) / 2;
      if (Math.abs(midX - center.getX()) >= 32 || Math.abs(midZ - center.getZ()) >= 32) {
         Holder<Biome> mid = bs.getNoiseBiome(QuartPos.fromBlock(midX), QuartPos.fromBlock(y), QuartPos.fromBlock(midZ), sampler);
         if (mid.is(want)) {
            int[] second = rays(bs, sampler, want, midX, y, midZ);
            if (Math.min(second[0] + second[1], second[2] + second[3]) > Math.min(r[0] + r[1], r[2] + r[3])) {
               r = second;
            }
         }
      }
      return new int[]{32 + r[0] + r[1], 32 + r[2] + r[3]};
   }

   private static int[] rays(BiomeSource bs, Climate.Sampler sampler, ResourceKey<Biome> want, int x, int y, int z) {
      int step = 32;
      int cap = 1024;
      int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
      int[] out = new int[4];
      for (int i = 0; i < 4; i++) {
         int reach = 0;
         for (int d = step; d <= cap; d += step) {
            Holder<Biome> h = bs.getNoiseBiome(
               QuartPos.fromBlock(x + dirs[i][0] * d), QuartPos.fromBlock(y), QuartPos.fromBlock(z + dirs[i][1] * d), sampler);
            if (!h.is(want)) {
               break;
            }
            reach = d;
         }
         out[i] = reach;
      }
      return out;
   }

   // ── Which height a biome is looked for at ────────────────────────────────

   /** Every surface biome: the one height the search has always used. */
   private static final int[] SURFACE = {64};

   /**
    * CAVE BIOMES ARE UNDERGROUND (1.44.2). Every biome search used to sample Y=64. Deep Dark, Lush
    * Caves and Dripstone Caves are placed by the climate's "depth", which is high only well below the
    * surface, so at Y=64 they appear only under high ground. caveDiag measured it over 200 seeds, within
    * 200 blocks of spawn, asking the search's own question (32-block grid, inside the circle):
    *
    *                     Y=64   Y=32   Y=0   Y=-24  Y=-48   search before   search now
    *   deep_dark           0%     1%    5%    15%    28%        0%             28%
    *   lush_caves          5%    12%   23%    14%     2%        5%             27%
    *   dripstone_caves    16%    24%   29%    16%     8%       16%             33%
    *
    * Deep Dark could never be found before. Each is now looked for at its measured heights; the
    * nearest patch at any of them wins, which is why two of them beat their best single height.
    * caveDiag fails if the shipped search ever does worse than a cave biome's best single height.
    */
   private static final Map<String, int[]> CAVE_HEIGHTS = Map.of(
         "deep_dark", new int[]{-48, -24},
         "lush_caves", new int[]{0, -24, 32},
         "dripstone_caves", new int[]{0, 32, -24});

   static int[] biomeHeights(ResourceKey<Biome> biome) {
      return CAVE_HEIGHTS.getOrDefault(biome.identifier().getPath(), SURFACE);
   }

   static boolean underground(ResourceKey<Biome> biome) {
      return CAVE_HEIGHTS.containsKey(biome.identifier().getPath());
   }

   /**
    * The nearest patch of {@code want} within {@code radius} of (ox, oz), searched at every height
    * that biome occupies, or null. The returned position carries the height it was found at. For a
    * surface biome this is exactly the old search: one height, the origin first, then the scan.
    */
   static BlockPos locateBiome(BiomeSource bs, Climate.Sampler sampler, ResourceKey<Biome> want,
                                       int ox, int oz, int radius, int step, long seed, boolean nearest) {
      BlockPos best = null;
      long bestD = Long.MAX_VALUE;
      for (int y : biomeHeights(want)) {
         Holder<Biome> at = bs.getNoiseBiome(QuartPos.fromBlock(ox), QuartPos.fromBlock(y), QuartPos.fromBlock(oz), sampler);
         if (at.is(want)) {
            return new BlockPos(ox, y, oz);   // right here: nothing can be nearer
         }
         Pair<BlockPos, Holder<Biome>> found = bs.findBiomeHorizontal(ox, y, oz, radius, step, h -> h.is(want),
               RandomSource.create(seed), nearest, sampler);
         if (found != null) {
            long dx = found.getFirst().getX() - (long) ox;
            long dz = found.getFirst().getZ() - (long) oz;
            if (dx * dx + dz * dz < bestD) {
               bestD = dx * dx + dz * dz;
               best = found.getFirst();
            }
         }
      }
      return best;
   }

   // ── Candidate + placement ────────────────────────────────────────────────

   record Placement(Holder.Reference<StructureSet> set, RandomSpreadStructurePlacement spread) {}

   record Cand(ChunkPos chunk, Placement placement) {}

   /**
    * One plain-row hit whose strict biome check has been put off to the end of {@link #test}.
    *
    * It carries everything the deferred pass needs to finish the job the per-target loop started:
    * the same candidate list in the same order (so the answer is identical, not merely similar),
    * the confirm and winner caches so nothing is recomputed, and where the match line sits so it
    * can be corrected if a later candidate turns out to be the real one.
    */
   private record Pending(int index, StructureTarget target, List<Cand> candidates,
                          Map<Cand, BlockPos> cache, Map<Cand, Holder<Structure>> winners,
                          int lineIndex, int fastIndex, int ax, int az, int x, int z) {}

   // ── Slime chunks ─────────────────────────────────────────────────────────

   /**
    * "At least {@code count} slime chunks within {@code radius} of spawn."
    *
    * Deliberately not a {@link StructureTarget}. A slime chunk has no structure, no placement, no
    * pieces and no biome requirement — sharing the structure machinery would mean carrying a dozen
    * fields that are all meaningless here, and it would put a free test through a funnel built to
    * make expensive tests rare.
    *
    * BE HONEST ABOUT WHAT THIS FILTERS. One chunk in ten is a slime chunk and the number of chunks
    * inside a radius grows with its square, so the counts that sound demanding usually are not:
    * {@code slimeDiag} measures the exact figure for every radius this offers, and any combination
    * that comes out at 100% is labelled in the picker as rejecting nothing rather than quietly sold
    * as a search.
    */
   public static final class SlimeTarget {
      public final int count;
      public final int radius;

      public SlimeTarget(int count, int radius) {
         this.count = Math.max(1, count);
         this.radius = Math.max(16, radius);
      }

      public String label() {
         return count == 1 ? "A slime chunk within " + radius
               : count + " slime chunks within " + radius;
      }
   }

   // ── Structure targets ────────────────────────────────────────────────────

   /** "A Woodland Mansion within 500 blocks of spawn" — may span several structure sets
    *  (villages) and several structures in a set (shipwreck). Immutable except radius. */
   public static final class StructureTarget {
      public final String label;
      public final Dim dim;
      public final int sampleY;
      public int radius;
      public final Special special;
      public final ZombieMode zombie;
      public final String note;
      /**
       * Template-id token the structure's assembled piece list must contain, or null for no
       * requirement. A village building ("armorer"), or for a fixed-layout structure the room
       * itself ("igloo/bottom"). Same check either way — see {@link VillageLayout#hasBuilding}.
       */
      public final String building;
      /**
       * Flips {@link #building} from "must contain" to "must NOT contain" — the igloo with no
       * basement, the village with no armorer.
       *
       * It is one boolean because the two questions are the same question. Both assemble the
       * structure and read its real piece list; only the sign of the answer differs, so a separate
       * mechanism would be a second copy of the expensive half of the finder with one character
       * changed.
       *
       * The one place the sign is NOT symmetric is failure. When assembly returns nothing —
       * templates unavailable, or the structure declining to generate — a positive token honestly
       * answers "no" and the seed is skipped. A negative token must ALSO answer no, because "I
       * could not read the piece list" is not evidence that the piece is absent. See
       * {@link #layoutMatches}.
       */
      public final boolean without;
      /**
       * The SHORT form of {@link #note}: a few words naming the one thing this row cannot
       * promise ("what's in the basement chest"), or "" when there is nothing to warn about.
       *
       * Both exist because they are read in different places. The picker shows ONE row at a
       * time, where the full paragraph fits and is worth reading. An AI wish can pick three
       * content rows at once, and joining three paragraphs into a one-line banner produces a
       * sentence cut off at 60 characters -- so the wish path joins these instead.
       */
      public final String cant;
      /**
       * How often this row's feature really turns up, as a percentage, or -1 when it has never
       * been measured.
       *
       * -1 is a real value and the GUI must print NOTHING for it rather than a plausible-looking
       * number. Every figure here came out of a diagnostic in this project — mansion rooms from
       * {@code mansionDiag} over 600 assembled mansions, professions from {@code professionDiag}
       * over 200 seeds per village type, the rest from {@code villageDiag}. A guessed rarity is
       * worse than a blank one: it is the number a player uses to decide whether a search is worth
       * starting, and being wrong about it costs them the wait.
       */
      public final int rarityPct;
      final List<Placement> placements;
      /**
       * Every structure id this row accepts. Readable from outside the package so a diagnostic can
       * re-derive a claim WITHOUT going through the funnel — the multi-row check builds each of
       * these at the reported coordinate itself, which is only an independent test if it can see
       * the same list the row was searching for.
       */
      public final Set<Identifier> wanted;
      /**
       * How many of this row must be within {@link #radius}. 1 for every row in the catalog.
       *
       * "Two shipwrecks near spawn" used to be unanswerable and, worse, silently answered wrong: the
       * number was dropped and the search ran for one. The candidates were always there — stage 0
       * enumerates every placement slot in range and the loop simply stopped at the first that
       * confirmed — so this asks it to keep going instead.
       *
       * Counting is not free the way it looks. The first find is the cheap one: it is the one the
       * funnel was already looking for, and every extra find is another confirm and, for a content
       * row, another assembly. A count of 4 on a content row is four assemblies on every seed that
       * gets that far, which is why the wish layer caps it - {@link WishParser#MAX_COUNT_CONTENT}
       * for a row that names a building inside, {@link WishParser#MAX_COUNT_PLAIN} otherwise.
       *
       * This paragraph used to end "which is why countDiag measures the real rates before any
       * row ships", and there is no countDiag. There never was. A diagnostic named in a comment
       * is a claim rather than a check - the second one found in this file, alongside netherDiag,
       * which was named for two releases before anybody wrote it.
       */
      public final int count;
      /**
       * The player must SPAWN INSIDE this structure, not merely near it.
       *
       * Distance is not the same question and cannot answer it. A village whose locate position is
       * 30 blocks from spawn may not reach spawn at all, and one 80 blocks away may sprawl right
       * over it — village size varies by a factor of three. So this tests the assembled structure's
       * real bounding box against the real spawn point, which means it needs the assembly AND it
       * needs Exact mode: Fast mode measures from 0, 0, and 0, 0 is not where anybody spawns.
       */
      public final boolean spawnInside;
      /**
       * The assembled structure must have at least this many pieces, or 0 for no size requirement.
       *
       * PIECES, not blocks and not span. A village's piece count is what the jigsaw actually
       * decided and it is already sitting there once the structure is built, so this costs nothing
       * on a row that assembles anyway. {@code statsDiag} measures the real distribution per
       * village type and the threshold comes from that — a "big village" has to mean a number this
       * project measured, not a number that sounded large.
       *
       * Deliberately not offered in the picker. It only means anything for structures whose size
       * really varies (villages do, by roughly a factor of three; an igloo is always an igloo), and
       * a control that does nothing on most rows is the same mistake as a size tier on a beach.
       */
      public final int minPieces;

      private StructureTarget(String label, Dim dim, int sampleY, int radius, Special special,
                              ZombieMode zombie, String note, List<Placement> placements, Set<Identifier> wanted) {
         this(label, dim, sampleY, radius, special, zombie, note, placements, wanted, null, "", false);
      }

      private StructureTarget(String label, Dim dim, int sampleY, int radius, Special special,
                              ZombieMode zombie, String note, List<Placement> placements,
                              Set<Identifier> wanted, String building, String cant) {
         this(label, dim, sampleY, radius, special, zombie, note, placements, wanted, building, cant, false);
      }

      private StructureTarget(String label, Dim dim, int sampleY, int radius, Special special,
                              ZombieMode zombie, String note, List<Placement> placements,
                              Set<Identifier> wanted, String building, String cant, boolean without) {
         this(label, dim, sampleY, radius, special, zombie, note, placements, wanted, building,
               cant, without, -1);
      }

      private StructureTarget(String label, Dim dim, int sampleY, int radius, Special special,
                              ZombieMode zombie, String note, List<Placement> placements,
                              Set<Identifier> wanted, String building, String cant, boolean without,
                              int rarityPct) {
         this(label, dim, sampleY, radius, special, zombie, note, placements, wanted, building,
               cant, without, rarityPct, 1, false, 0);
      }

      private StructureTarget(String label, Dim dim, int sampleY, int radius, Special special,
                              ZombieMode zombie, String note, List<Placement> placements,
                              Set<Identifier> wanted, String building, String cant, boolean without,
                              int rarityPct, int count, boolean spawnInside, int minPieces) {
         this.count = Math.max(1, count);
         this.spawnInside = spawnInside;
         this.minPieces = minPieces;
         this.building = building;
         this.without = without;
         this.rarityPct = rarityPct;
         this.cant = cant == null ? "" : cant;
         this.label = label;
         this.dim = dim;
         this.sampleY = sampleY;
         this.radius = radius;
         this.special = special;
         this.zombie = zombie;
         this.note = note;
         this.placements = placements;
         this.wanted = wanted;
      }

      /** A normal placement-searchable target built by {@link SeedCatalog}. */
      static StructureTarget build(String label, Dim dim, int sampleY, int radius,
                                   List<Holder.Reference<StructureSet>> sets, List<RandomSpreadStructurePlacement> spreads,
                                   Set<Identifier> wanted, ZombieMode zombie, String note, String building,
                                   String cant, int rarityPct) {
         List<Placement> pl = new ArrayList<>();
         for (int i = 0; i < sets.size(); i++) {
            pl.add(new Placement(sets.get(i), spreads.get(i)));
         }
         return new StructureTarget(label, dim, sampleY, radius, Special.NORMAL, zombie, note, pl,
               new HashSet<>(wanted), building, cant, false, rarityPct);
      }

      /** A list-only entry that can't be placement-searched — only the Dungeon now, which is
       *  scattered per-chunk as terrain generates and has no seed-level position at all. */
      static StructureTarget special(String label, Special special, String note) {
         return new StructureTarget(label, Dim.OVERWORLD, 64, 500, special, ZombieMode.ANY, note, List.of(), Set.of());
      }

      /** Copy with its own radius so a shared catalog entry is never mutated by the GUI.
       *  The per-dimension floor is applied here so EVERY path (menu, AI wish, excludes)
       *  gets honest Nether/End distances automatically. */
      public StructureTarget withRadius(int r) {
         return new StructureTarget(label, dim, sampleY, dimRadius(dim, r), special, zombie, note,
               placements, wanted, building, cant, without, rarityPct, count, spawnInside, minPieces);
      }

      /** Copy that demands {@code n} of them in range rather than one. */
      public StructureTarget withCount(int n) {
         if (n <= 1) {
            return this;
         }
         return new StructureTarget(plural(label, n), dim, sampleY, radius, special, zombie, note,
               placements, wanted, building, cant, without, rarityPct, n, spawnInside, minPieces);
      }

      /** Copy that demands the player's spawn point land inside it. */
      public StructureTarget insideSpawn() {
         return new StructureTarget("Spawn inside " + label, dim, sampleY, radius, special, zombie,
               note + "  This row does not measure a distance: it assembles the structure and asks "
               + "whether your spawn point is inside its real footprint. That needs Exact mode, "
               + "because Fast mode measures from 0, 0 and nobody spawns there.",
               placements, wanted, building, cant, without, rarityPct, count, true, minPieces);
      }

      /**
       * Copy that also demands a minimum assembled piece count — "a big village".
       *
       * AI-only by design. See {@link #minPieces} for why it is not a picker row.
       */
      public StructureTarget withMinPieces(int pieces) {
         if (pieces <= 0) {
            return this;
         }
         return new StructureTarget("Big " + label, dim, sampleY, radius, special, zombie,
               note + "  \"Big\" here means at least " + pieces + " assembled pieces, which is the "
               + "figure statsDiag measured as the top third for this structure — not a guess at "
               + "what big ought to mean.",
               placements, wanted, building, cant, without, rarityPct, count, spawnInside, pieces);
      }

      /** Does this row need the structure BUILT before it can be judged? */
      public boolean needsAssembly() {
         return building != null || spawnInside || minPieces > 0;
      }

      /**
       * "Shipwreck" + 2 -> "2× Shipwreck". Only the label; nothing downstream parses it.
       *
       * NOT pluralised. Sticking an "s" on the end works for "Shipwreck" and produces
       * "2 Village with Armorers" for a row whose noun is not the last word — which is most of the
       * interesting ones. A multiplier reads correctly in front of any label there is.
       */
      private static String plural(String label, int n) {
         return n + "× " + label;
      }

      /**
       * Copy that also demands a particular kind of village start.
       *
       * Only ever used to ADD "and not the abandoned kind" to a row that already wants a building,
       * and the asymmetry is measured rather than stylistic. A zombie village does not draw from
       * the ordinary house pools — every village type ships a separate, much smaller
       * {@code village/<biome>/zombie/houses/} set — and most professions have no zombie template
       * at all: there is no armorer, mason or leatherworker in any zombie village in this version,
       * and desert, savanna and snowy zombie villages have no profession buildings whatsoever. So
       * "abandoned village with an armorer" is not a rare seed, it is an impossible one, and
       * combining those two demands would build a search that can never finish. Combining a
       * building with NORMAL is safe, because a normal village has the full house set.
       */
      public StructureTarget alsoNotAbandoned() {
         return new StructureTarget(label + " (not abandoned)", dim, sampleY, radius, special,
               ZombieMode.NORMAL,
               note + "  This row also demands an ordinary, inhabited village rather than a zombie "
               + "one — which costs nothing to check and rejects about 1 village in 50.",
               placements, wanted, building, cant, without, rarityPct, count, spawnInside, minPieces);
      }

      /**
       * Copy under a different name, for when the player's word is better than the catalog's.
       *
       * Only the label changes — the same structure ids, the same piece token, the same search.
       * It exists because "Village with Weaponsmith" is the correct name for the row and
       * "Blacksmith" is what the person typed and what they will recognise in the chat line that
       * says what was found. Nothing downstream parses the label.
       */
      public StructureTarget renamed(String newLabel) {
         return new StructureTarget(newLabel, dim, sampleY, radius, special, zombie, note,
               placements, wanted, building, cant, without, rarityPct, count, spawnInside, minPieces);
      }

      /** Copy that also demands a specific building. Null clears the requirement. */
      public StructureTarget withBuilding(String token) {
         return new StructureTarget(label, dim, sampleY, radius, special, zombie, note,
               placements, wanted, token, cant, without, rarityPct, count, spawnInside, minPieces);
      }

      /**
       * The row's name with the context the picker already supplies stripped out.
       *
       * Derived rather than typed, from the same " with " split the negative form uses: the picker
       * groups rows by category and puts the category above them, so "Village with Weaponsmith"
       * inside the Village group only needs to say "Weaponsmith". Repeating the structure on every
       * row is what made forty rows unreadable — every line began with the same word.
       *
       * Rows that do not read "<structure> with <feature>" keep their label, because for those the
       * label IS the distinguishing part: "Capsized Shipwreck" and "Giant Ruined Portal" say what
       * they are and nothing can be taken out of them without losing it.
       */
      /**
       * The feature this row is about, in the fewest words that still name it — "weaponsmith",
       * "basement", "eruption room".
       *
       * Derived from the label rather than typed beside the token, for the reason every name in
       * this project is derived: the label is what the player already read on the row they picked,
       * so the sentence under the find uses the same word they chose. A row with no " with " in it
       * ("Capsized Shipwreck", "Treasure Bastion") is about its own shape rather than a part
       * inside it, and for those the label IS the word.
       */
      public String featureWord() {
         int at = label.indexOf(" with ");
         String feature = at >= 0 ? label.substring(at + 6) : label;
         // The witherers append their own qualifier — "(not abandoned)", "#2" — and those belong to
         // the ROW, not to the building standing in front of the player. "the weaponsmith (not
         // abandoned) is 48 blocks further on" describes a house that does not exist.
         int bracket = feature.indexOf(" (");
         if (bracket > 0) {
            feature = feature.substring(0, bracket);
         }
         int hash = feature.indexOf(" #");
         if (hash > 0) {
            feature = feature.substring(0, hash);
         }
         return feature.toLowerCase(java.util.Locale.ROOT);
      }

      public String shortName() {
         int at = label.indexOf(" with ");
         if (at >= 0) {
            String feature = label.substring(at + 6);
            return Character.toUpperCase(feature.charAt(0))
                  + feature.substring(1).toLowerCase(java.util.Locale.ROOT);
         }
         int without = label.indexOf(" without ");
         if (without >= 0) {
            String feature = label.substring(without + 9);
            return "No " + feature.toLowerCase(java.util.Locale.ROOT);
         }
         // "Surface Village" is the odd one out: inside the Village category it would read as the
         // single word "Surface", which names nothing. It is the ANY-village row, so it says so.
         return "Surface Village".equals(label) ? "Any village" : label;
      }

      /**
       * The "without" form of this row, or null when it does not have one.
       *
       * Three shapes of row and three answers, and which one applies is read off the row itself
       * rather than listed anywhere:
       *
       *   - a row demanding a PIECE ("Igloo with Basement") flips the piece test. Its label is
       *     already "<structure> with <feature>", so the negative name is that sentence with one
       *     word changed, and it stays true even if the row is renamed.
       *   - the Abandoned Village row demands a zombie START, which has its own flag, so the
       *     negative is {@link ZombieMode#NORMAL} — an ordinary, inhabited village.
       *   - a plain structure row has nothing to be without. "No Igloo nearby" is a different
       *     wish and the criteria already has {@link SeedCriteria#excludeStructures} for it, so
       *     this returns null and the caller routes it there.
       *
       * A row whose label does not read "<structure> with <feature>" — "Capsized Shipwreck",
       * "Treasure Bastion" — also returns null, deliberately. There is no honest short name for
       * its negative, and the measured rates say there is no search in it either: a wreck that is
       * not capsized is 6 wrecks in 7, which filters nothing.
       */
      public StructureTarget negated() {
         if (special != Special.NORMAL) {
            return null;
         }
         if (building != null && !without) {
            int at = label.indexOf(" with ");
            if (at < 0) {
               return null;
            }
            String structure = label.substring(0, at);
            String feature = label.substring(at + 6);
            // The positive row's note and its "cant" are BOTH about the feature being there — "the
            // basement is the ladder shaft under the carpet", "what's in the basement chest". On a
            // row that demands the feature is absent, every word of that is wrong, so the negative
            // writes its own rather than inheriting. The cant is empty on purpose: this row claims
            // nothing about any contents, so it has nothing to disclaim.
            return new StructureTarget(structure + " without " + feature,
                  dim, sampleY, radius, special, zombie,
                  "A " + structure.toLowerCase(java.util.Locale.ROOT) + " that does NOT have the "
                  + feature.toLowerCase(java.util.Locale.ROOT) + ". Read from the structure's real "
                  + "assembled piece list, the same check as the positive row, so the ABSENCE is "
                  + "exactly as certain as a presence would be — it is not \"we didn't find one\".",
                  // Rarity is deliberately dropped: "13% of mansions have a secret room" says
                  // nothing about how many DON'T, and 100-minus-it is only right when the row is
                  // about one structure at a time. Blank beats a number that reads plausibly and
                  // is wrong.
                  placements, wanted, building, "", true, -1);
         }
         if (zombie == ZombieMode.ABANDONED) {
            return new StructureTarget("Village, not abandoned", dim, sampleY, radius, special,
                  ZombieMode.NORMAL,
                  "An ordinary, inhabited village — the zombie/abandoned kind is excluded. Read from "
                  + "the same dice the game uses to pick the town centre, so it is certain. Note this "
                  + "rejects only about 1 village in 50, so on its own it barely narrows a search; it "
                  + "is worth adding to a wish that wanted villagers, not to one hunting a rare seed.",
                  placements, wanted, building, "", false, -1);
         }
         return null;
      }

      /** Stage 0: candidate chunks from pure placement math, centered on 0,0 and widened by
       *  the spawn wander so the real spawn (found later) is always covered. */
      List<Cand> stage0(long seed) {
         return stage0(seed, false);
      }

      /**
       * Same, told whether the caller is in Fast mode.
       *
       * Fast mode never calls {@code findSpawnPosition} - it measures from 0,0 - so the spawn
       * wander is padding for a spawn point that will never move. Every candidate it adds is
       * beyond {@code radius} of 0,0 and gets thrown away by the distance filter a moment later.
       * Dropping it in Fast mode shrinks the scanned region from (radius + 2144) to radius, which
       * is the whole area for a typical wish, and cannot change any verdict: the filter Fast mode
       * applies is exactly the circle this now scans.
       */
      List<Cand> stage0(long seed, boolean fast) {
         List<Cand> out = new ArrayList<>(4);
         boolean skipWander = fast && SeedFunnel.FAST_STAGE0;
         int wander = skipWander ? 0 : dim == Dim.OVERWORLD ? SPAWN_WANDER : dim == Dim.NETHER ? SPAWN_WANDER_NETHER : 0;
         int reach = radius + wander;
         long r2 = (long) reach * reach;
         for (Placement pl : placements) {
            int spacing = pl.spread().spacing();
            int chunkR = Math.max(1, reach >> 4);
            int rMin = Math.floorDiv(-chunkR, spacing);
            int rMax = Math.floorDiv(chunkR, spacing);
            for (int rx = rMin; rx <= rMax; rx++) {
               for (int rz = rMin; rz <= rMax; rz++) {
                  ChunkPos c = pl.spread().getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                  long bx = c.getMiddleBlockX();
                  long bz = c.getMiddleBlockZ();
                  if (bx * bx + bz * bz <= r2 && pl.spread().applyAdditionalChunkRestrictions(c.x(), c.z(), seed)) {
                     out.add(new Cand(c, pl));
                  }
               }
            }
         }
         return out;
      }

      /**
       * Stage 1: vanilla-exact confirm at one candidate — exclusion zones, the seeded weighted
       * winner pick, biome membership, and (for villages) the abandoned/zombie start-piece
       * prediction. Returns the locate pos or null.
       *
       * Deliberately does NOT run the layout check. Everything here is arithmetic and a couple of
       * biome samples; assembling a structure to see what it contains costs about a SECOND for a
       * village, and this method runs across the whole ~2200-block area the spawn point might turn
       * out to be in. Mixing the two made a "village within 64 blocks" wish assemble every village
       * in that area and then throw nearly all of them away — 200x slower than the same wish
       * without a building. {@link #layoutMatches} is the deferred half, run by the caller only on
       * the handful of candidates that survive the distance filter.
       */
      BlockPos confirm(long seed, WorldgenContext ctx, RandomState rs, ChunkGeneratorStructureState st, Cand cand) {
         return confirm(seed, ctx, rs, st, cand, null);
      }

      /**
       * Same, but also reporting WHICH structure won the set's weighted pick, when {@code winnerOut}
       * is non-null.
       *
       * One structure set can hold several structures — ruined portals are six variants in one set,
       * and which one lands here is decided by the seeded roll below. Anything that goes on to
       * ASSEMBLE the structure has to assemble the winner; assuming the standard variant would read
       * the wrong templates on every desert, jungle, swamp, mountain or ocean portal.
       */
      BlockPos confirm(long seed, WorldgenContext ctx, RandomState rs, ChunkGeneratorStructureState st, Cand cand,
                       Holder<Structure>[] winnerOut) {
         StructureSet set = cand.placement().set().value();
         ChunkPos chunk = cand.chunk();
         StructurePlacement placement = set.placement();
         if (!placement.applyInteractionsWithOtherStructures(st, chunk.x(), chunk.z())) {
            return null;
         }
         BlockPos locate = placement.getLocatePos(chunk);
         BiomeSource biomes = ctx.biomeSource(dim);
         Climate.Sampler sampler = rs.sampler();

         StructureSet.StructureSelectionEntry won =
               pickWinner(seed, chunk, set, biomes, sampler, locate, sampleY);

         if (won == null) {
            return null;
         }
         Identifier winner = idOf(won);
         if (!wanted.isEmpty() && !wanted.contains(winner)) {
            return null;
         }
         if (zombie != ZombieMode.ANY && !zombieMatches(seed, chunk, won)) {
            return null;
         }
         if (winnerOut != null) {
            winnerOut[0] = won.structure();
         }
         return locate;
      }

      /** Predicts whether the village at this chunk is a zombie (abandoned) village by
       *  replaying the jigsaw start dice: seed the structure random exactly as the game does
       *  ({@code setLargeFeatureSeed}), consume the rotation draw, then the start-template
       *  pick, and check if that template is a zombie variant. */
      private boolean zombieMatches(long seed, ChunkPos chunk, StructureSet.StructureSelectionEntry won) {
         if (!(won.structure().value() instanceof JigsawStructure)) {
            return zombie == ZombieMode.NORMAL; // no jigsaw start = never zombie
         }
         boolean isZombie = isZombieStart(seed, chunk, won.structure());
         return zombie == ZombieMode.ABANDONED ? isZombie : !isZombie;
      }

      /**
       * Does this structure assemble the requested piece — a village building, or an igloo's
       * basement? Reads the real piece list via {@link VillageLayout} — assembly places no
       * blocks, so unlike the eye counter this cannot drift by reading a stubbed level.
       *
       * With {@link #without} set the same list answers the opposite question: an igloo with NO
       * basement, a village with NO armorer.
       *
       * Returns false when templates are unavailable (no world open). Deliberate: a predicate
       * we cannot verify must never report a match, or the finder would hand back seeds it
       * never actually checked. Note this is false for BOTH signs, and the negative one is the
       * case worth being careful about — "the piece list is empty" tempts a lazy negative test
       * into answering "then the piece is certainly not in it", which would turn every failure to
       * read a structure into a confident match. An unread structure is not a structure without
       * an armorer; it is a structure nobody looked at.
       */
      boolean layoutMatches(long seed, ChunkPos chunk, Holder<Structure> winner, WorldgenContext ctx) {
         return layoutMatches(seed, chunk, winner, ctx, 0, 0);
      }

      /**
       * As above, told where the player will stand, which only a {@link #spawnInside} row needs.
       *
       * {@code ax, az} is the origin this row is measured from. For the only case that reads it —
       * an overworld row in Exact mode — that origin IS the real spawn point, so nothing extra has
       * to be threaded through the funnel to get it here.
       */
      boolean layoutMatches(long seed, ChunkPos chunk, Holder<Structure> winner, WorldgenContext ctx,
                            int ax, int az) {
         return layoutMatches(seed, chunk, winner, ctx, ax, az, null);
      }

      /**
       * As above, also reporting WHERE the demanded building landed.
       *
       * THE ASSEMBLY ALREADY KNOWS THIS AND USED TO THROW IT AWAY, which is the whole of the
       * "weaponsmith isn't there" report. A content row promises a BUILDING and every line this
       * program prints names the STRUCTURE's locate position, derived from its chunk. For a
       * fixed-layout structure those are nearly the same point; for a village they are not — 150
       * pieces over 130 by 176 blocks — so a player could stand exactly where they were sent,
       * inside a village that really does have the building, and have no idea which way to look.
       * {@code featureDiag} measures the gap: a median of 28 blocks for the weaponsmith, up to 114
       * for the ancient city's sauna, and zero false claims in either mode. The search was right
       * and the walk still failed.
       *
       * {@code featureOut} is filled only when the row demands a building AND one was found, so a
       * spawn-inside or size-only row leaves it null and costs nothing.
       */
      boolean layoutMatches(long seed, ChunkPos chunk, Holder<Structure> winner, WorldgenContext ctx,
                            int ax, int az, BlockPos[] featureOut) {
         boolean strict = SeedFunnel.STRICT_BIOME;
         boolean needsBuild = building != null || spawnInside || minPieces > 0;
         if (!needsBuild && !strict) {
            return true;   // nothing to read inside, and not asking vanilla's question either
         }
         if (!VillageLayout.available()) {
            // No templates means no check can run, and nothing claims what it did not check — a plain
            // row included since 1.44.2 (it used to keep its pre-strict "yes"). The search refuses up
            // front in this case (SeedFinder.worker), so this line is the guard, not the experience.
            return false;
         }
         // Assembly asks the generator for terrain height, so it needs the FULL noise router —
         // the finder's climate-only state reports flat ground and mis-places pieces. The state is
         // cached per seed by WorldgenContext, so several candidates in one seed share one build.
         RandomState full = ctx.fullRandomState(dim, seed);
         // VANILLA'S BIOME TEST, or the loose one the finder used to use everywhere. The strict
         // one samples where the structure REALLY starts rather than at the locate position, which
         // is a different point and, for an ancient city, a different biome about half the time.
         java.util.function.Predicate<Holder<Biome>> biomeTest =
               strict ? VillageLayout.ownBiomes(winner) : h -> true;
         if (!needsBuild) {
            // Nothing to read, so only the cheap half is needed — no pieces are placed.
            return VillageLayout.generatesHere(ctx.source, ctx, seed, chunk, winner, full, biomeTest, dim);
         }
         // ONE ASSEMBLY, all three questions. The dimension goes with it: assembly picks the noise
         // settings, biome source, level key and build limits from it, and a bastion assembled
         // against overworld terrain between y=-64 and 320 is describing a world that does not
         // exist.
         //
         // The strict biome test comes FREE here: it is one argument to an assembly that was going
         // to happen anyway, and an assembly that fails it returns nothing, which lands in the null
         // branch below.
         VillageLayout.Built built = VillageLayout.builtAt(
               ctx.source, ctx, seed, chunk, winner, full, biomeTest, dim);
         if (built == null) {
            return false;   // nothing was read — see the note above, this is not evidence either way
         }
         if (spawnInside) {
            // The FOOTPRINT, not the distance. A structure's locate position is a corner of one
            // piece; whether the spawn point is inside the thing is a question about the whole
            // assembled box, and for a village the two answers differ often.
            //
            // X and Z only. Spawn's y is the surface the player lands on and a structure's box
            // spans whatever depth it happens to occupy; testing y would reject standing on a
            // village's roads because the box starts a block below them.
            BoundingBox box = built.box();
            if (ax < box.minX() || ax > box.maxX() || az < box.minZ() || az > box.maxZ()) {
               return false;
            }
         }
         if (minPieces > 0 && built.pieces() < minPieces) {
            return false;
         }
         if (building == null) {
            return true;
         }
         if (built.templates().isEmpty()) {
            // Built, but from code rather than templates, so no piece can be NAMED. Not evidence
            // either way about a building, and answering "no" is the only safe reading — see the
            // note above about what an unread piece list is not.
            return false;
         }
         boolean has = VillageLayout.hasBuilding(built.templates(), building);
         if (has && !without && featureOut != null && featureOut.length > 0) {
            // Nearest to the locate position, because that is where the player will be standing
            // when they start looking.
            featureOut[0] = VillageLayout.buildingAt(built.placed(), building,
                  new BlockPos(chunk.getMiddleBlockX(), 64, chunk.getMiddleBlockZ()));
         }
         return has != without;
      }

      static Identifier idOf(StructureSet.StructureSelectionEntry entry) {
         return entry.structure().unwrapKey().map(ResourceKey::identifier).orElse(null);
      }
   }

   private static Holder<Biome> sampleBiome(BiomeSource biomes, Climate.Sampler sampler, BlockPos at,
                                            int sampleY) {
      return biomes.getNoiseBiome(
         QuartPos.fromBlock(at.getX()), QuartPos.fromBlock(sampleY), QuartPos.fromBlock(at.getZ()), sampler);
   }

   /** {@code pre} is the hoisted sample when Fix 2b is on, null when it is off (then this
    *  samples per call, exactly as before). Either way the Holder compared is the same one. */
   private static boolean biomeOk(StructureSet.StructureSelectionEntry entry, BiomeSource biomes,
                                  Climate.Sampler sampler, BlockPos at, Holder<Biome> pre, int sampleY) {
      Holder<Biome> here = pre != null ? pre : sampleBiome(biomes, sampler, at, sampleY);
      HolderSet<Biome> allowed = entry.structure().value().biomes();
      return allowed.contains(here);
   }

   /**
    * WHICH structure of a set really generates at this chunk — the seeded weighted pick, with each
    * candidate held to its own biome list — or null when none of them can.
    *
    * Lifted out of {@link StructureTarget#confirm} in 1.41.3 so the in-game map can ask the same
    * question about EVERY structure set, not only the ones the player searched for. It has to be
    * the same code and not a copy: the map draws dots next to the search's dots, and two
    * implementations of "does a village generate here" that disagree by one chunk would put a
    * marker on the map for a structure the search says is somewhere else. There is one weighted
    * pick in this project and this is it.
    *
    * The pick is vanilla's: seed a WorldgenRandom with the chunk, roll against the total weight,
    * and if the winner's biome list does not accept the biome at the locate position, drop it from
    * the pool and roll again with the remaining weight.
    */
   static StructureSet.StructureSelectionEntry pickWinner(long seed, ChunkPos chunk, StructureSet set,
                                                         BiomeSource biomes, Climate.Sampler sampler,
                                                         BlockPos locate, int sampleY) {
      List<StructureSet.StructureSelectionEntry> entries = new ArrayList<>(set.structures());
      StructureSet.StructureSelectionEntry won = null;
      // Fix 2b: every variant in the set is tested against the biome at the SAME spot - same x,
      // same sampleY, same z - so sampling inside the loop re-asks an identical question once
      // per variant (up to six times for ruined portals, five for villages). The sample cannot
      // change between iterations, so hoisting it is exact, not an approximation.
      Holder<Biome> hoisted = SeedFunnel.HOIST_BIOME && !entries.isEmpty()
            ? sampleBiome(biomes, sampler, locate, sampleY) : null;
      if (entries.size() == 1) {
         if (biomeOk(entries.get(0), biomes, sampler, locate, hoisted, sampleY)) {
            won = entries.get(0);
         }
      } else {
         WorldgenRandom rand = new WorldgenRandom(new LegacyRandomSource(0L));
         rand.setLargeFeatureSeed(seed, chunk.x(), chunk.z());
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
            if (biomeOk(chosen, biomes, sampler, locate, hoisted, sampleY)) {
               won = chosen;
               break;
            }
            total -= chosen.weight();
            entries.remove(idx);
         }
      }
      return won;
   }

   /**
    * Will the village at this chunk be a zombie (abandoned) one?
    *
    * Replays the jigsaw start dice exactly as vanilla does: seed the structure random with
    * {@code setLargeFeatureSeed}, consume the rotation draw vanilla takes first, then the
    * start-template pick, and read whether that template is a zombie variant.
    *
    * Public because it is worth PROVING rather than trusting. This is a re-derivation of a dice
    * roll rather than a reading of a finished building, and the only way to know a re-derivation
    * is faithful is to check it against the building — which {@code gradlew professionDiag
    * --args="zombie"} does, comparing this against the assembled piece list.
    */
   public static boolean isZombieStart(long seed, ChunkPos chunk, Holder<Structure> structure) {
      if (!(structure.value() instanceof JigsawStructure jig)) {
         return false;
      }
      StructureTemplatePool pool = jig.getStartPool().value();
      WorldgenRandom rand = new WorldgenRandom(new LegacyRandomSource(0L));
      rand.setLargeFeatureSeed(seed, chunk.x(), chunk.z());
      Rotation.getRandom(rand); // consumed before the start piece, same as vanilla
      StructurePoolElement start = pool.getRandomTemplate(rand);
      return start instanceof SinglePoolElement sp
            && sp.getTemplateLocation().getPath().contains("zombie");
   }

   // ── Biome targets ────────────────────────────────────────────────────────

   /**
    * "A (big) Pale Garden within 500 blocks of spawn." minSpan 0 = no floor, maxSpan 0 = no ceiling.
    *
    * Both bounds exist because "small" is a real request and used to be answered with a shrug. The
    * thresholds are never chosen here — they come from {@link BiomeShape}, which holds this biome's
    * own measured p33 and p67, so LARGE means the top third of pale gardens and the top third of
    * warm oceans even though those are 64 and 608 blocks respectively.
    */
   public static final class BiomeTarget {
      public final ResourceKey<Biome> biome;
      public final String label;
      public final Dim dim;
      public int radius;
      public int minSpan;
      /** Upper bound on the span, for a "small X" request. 0 = no ceiling. */
      public int maxSpan;

      public BiomeTarget(ResourceKey<Biome> biome, String label, Dim dim, int radius, int minSpan) {
         this(biome, label, dim, radius, minSpan, 0);
      }

      public BiomeTarget(ResourceKey<Biome> biome, String label, Dim dim, int radius, int minSpan,
                         int maxSpan) {
         this.biome = biome;
         this.label = label;
         this.dim = dim;
         this.radius = dimBiomeRadius(dim, radius); // honest floors for Nether/End (see above)
         this.minSpan = minSpan;
         this.maxSpan = maxSpan;
      }

      /** True when this row asks anything at all about the patch's size. */
      boolean sized() {
         return minSpan > 0 || maxSpan > 0;
      }

      /** The size word for a summary line, or "" when any size will do. There is no separate
       *  "huge": with a per-biome threshold, "huge" and "big" are the same request — the top third
       *  of THAT biome — and printing two words for one filter only implied a distinction. */
      String sizeWord() {
         return maxSpan > 0 ? "small " : minSpan > 0 ? "big " : "";
      }
   }

   /**
    * "X next to Y" — one biome, and something that has to be near THAT rather than near spawn.
    *
    * The anchor is always a biome, and always one that is also in {@link #biomes}: the whole point
    * is to measure from the patch the search found, so the patch has to be found first. The other
    * end is either a second biome or a structure row, and exactly one of the two fields is set.
    *
    * The obvious cheap implementation does not work, which is worth recording because it looks
    * right. Both things are already located by the funnel, so subtracting their positions costs
    * nothing — but each was found by searching outward from SPAWN, and the patch nearest the player
    * is not the patch nearest the other thing. Measured over seven pairs, that free check was 2x to
    * 8x too far EVERY time (plains ↔ forest: 1583 blocks apart by that reckoning, 181 in reality).
    * So adjacency runs its own search from the anchor, and it runs last, after every cheap test in
    * the wish has already passed.
    */
   public static final class Adjacency {
      /** The biome end this pair is measured FROM, or null when the anchor is a structure. */
      public final BiomeTarget anchor;
      /**
       * The structure end this pair is measured FROM, or null when the anchor is a biome.
       *
       * Exactly one of {@link #anchor} and this is set. A structure anchor is the cheaper of the
       * two by a wide margin: the funnel has already located every candidate, so the pair only
       * re-sorts a list it is holding, while a biome anchor has to run a fresh
       * {@code findBiomeHorizontal} from the patch it found.
       */
      public final StructureTarget anchorStructure;
      public final BiomeTarget otherBiome;
      public final StructureTarget otherStructure;
      public int within;

      public Adjacency(BiomeTarget anchor, BiomeTarget otherBiome, StructureTarget otherStructure,
                       int within) {
         this(anchor, null, otherBiome, otherStructure, within);
      }

      public Adjacency(BiomeTarget anchor, StructureTarget anchorStructure, BiomeTarget otherBiome,
                       StructureTarget otherStructure, int within) {
         this.anchor = anchor;
         this.anchorStructure = anchorStructure;
         this.otherBiome = otherBiome;
         this.otherStructure = otherStructure;
         this.within = within;
      }

      public String anchorLabel() {
         return anchor != null ? anchor.label : anchorStructure.label;
      }

      public String otherLabel() {
         return otherBiome != null ? otherBiome.label : otherStructure.label;
      }
   }

   // ── Catalog (delegates to the fixed SeedCatalog) ─────────────────────────

   public static List<StructureTarget> catalog(WorldgenContext ctx) {
      return SeedCatalog.structures(ctx);
   }
}
