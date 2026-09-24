package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.WorldgenRandom;

/**
 * Which chunks spawn slimes, straight from the seed and nothing else.
 *
 * This is the cheapest thing in the whole finder by a wide margin. Every other search has to build
 * some part of the world — sample a biome, place a structure, assemble a jigsaw — because the answer
 * genuinely depends on terrain. A slime chunk does not: it is one hash of the world seed and the
 * chunk coordinates, decided before any block exists. No terrain, no biome, no structure, no
 * dimension scaffold. A chunk can be tested in well under a microsecond, which is why a radius that
 * would be punishing for anything else is free here.
 *
 * DERIVED, NOT REIMPLEMENTED. The hash itself is vanilla's own
 * {@code WorldgenRandom.seedSlimeChunk} — it is public and static, so there is no reason to copy the
 * magic constants into this file and every reason not to: a reimplementation is a second copy of the
 * rule that can drift from the game silently, and a slime-chunk map that is subtly wrong is worse
 * than none, because it is only discovered after a player has built a farm.
 *
 * The one value that is NOT readable from a field is the salt, which vanilla writes as a literal
 * inside {@code Slime.checkSlimeSpawnRules}:
 * <pre>
 *   164: ldc2_w        // long 987234911l
 *   167: invokestatic  // WorldgenRandom.seedSlimeChunk:(IIJJ)LRandomSource;
 *   170: bipush        10
 *   172: invokeinterface // RandomSource.nextInt:(I)I
 *   177: ifne          184          &lt;- so "== 0" is the slime case
 * </pre>
 * That sequence is the whole rule and it is reproduced below in the same order. {@code slimeDiag}
 * measures the resulting rate, which is the check that matters: the predicate has to land on about
 * one chunk in ten, and a wrong salt would not.
 *
 * SWAMPS ARE DELIBERATELY NOT INCLUDED. Slimes also spawn in swamps at night between certain moon
 * phases, which is a biome-and-time rule rather than a seed rule — it would make the count depend on
 * when a player happens to look, and this row promises a fact about the world instead.
 */
public final class SlimeChunks {

   /**
    * Vanilla's salt, from the bytecode quoted above. It is a literal at its only use site, so there
    * is no field to read it from — but it is also the only number here that could be wrong, and
    * {@code slimeDiag}'s measured rate is what proves it is not.
    */
   private static final long SALT = 987234911L;

   /** One chunk in how many, by vanilla's own {@code nextInt} bound. */
   public static final int ONE_IN = 10;

   /** The picker row's name, shared so the screen and the search agree on one spelling. */
   public static final String ROW_LABEL = "Slime Chunks";

   /**
    * The reach the picker row uses — FIXED, and not the screen's search distance.
    *
    * Every other row gets wider and rarer as the distance goes up. This one gets wider and MORE
    * CERTAIN, because slime chunks are everywhere and a bigger circle just contains more of them.
    * Handing it the usual distance control would produce a row that reads like a search and rejects
    * nothing at any setting a player is likely to choose. {@code slimeDiag} over 2000 seeds:
    * <pre>
    *   % of seeds with at least n slime chunks within r
    *   r      1+    2+    3+    5+    8+
    *   32    73%   35%    9%    0%    0%
    *   48    98%   87%   65%   20%    1%
    *   64   100%   98%   94%   62%   11%
    *   96   100%  100%  100%   99%   89%
    *   128  100%  100%  100%  100%  100%
    *   200  100%  100%  100%  100%  100%
    * </pre>
    * 64 is the largest reach where the count still separates seeds across the range the picker
    * offers, so that is what the row uses and what its line says. Past about 96 there is no
    * question left to ask.
    */
   public static final int PICKER_WITHIN = 64;

   /** How many the picker offers, cycled by its count button. */
   public static final int[] COUNT_CHOICES = {1, 2, 3, 4, 6, 8};

   /** Is this chunk a slime chunk in this world? */
   public static boolean isSlimeChunk(long seed, int chunkX, int chunkZ) {
      RandomSource r = WorldgenRandom.seedSlimeChunk(chunkX, chunkZ, seed, SALT);
      return r.nextInt(ONE_IN) == 0;
   }

   /**
    * Every slime chunk whose centre is within {@code radius} blocks of {@code (ax, az)}, nearest
    * first, stopping once {@code limit} have been found.
    *
    * Measured from the chunk CENTRE rather than its corner, because that is the point a player is
    * told to walk to and the point every other row in this finder reports.
    *
    * Searched in rings from the middle outwards so "the nearest three" really are the nearest three:
    * scanning the bounding square row by row would return the three with the smallest z, which look
    * identical in the output and are not the same answer.
    */
   public static List<ChunkPos> near(long seed, int ax, int az, int radius, int limit) {
      List<ChunkPos> found = new ArrayList<>(Math.min(limit, 16));
      int cx = ax >> 4;
      int cz = az >> 4;
      // +1 so a chunk whose centre is just inside the radius is not cut off by integer division.
      int chunkReach = (radius >> 4) + 1;
      long r2 = (long) radius * radius;
      for (int ring = 0; ring <= chunkReach && found.size() < limit; ring++) {
         List<ChunkPos> thisRing = new ArrayList<>(8);
         for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
               // Only the edge of the ring — the inside was covered by every smaller ring.
               if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                  continue;
               }
               int x = cx + dx;
               int z = cz + dz;
               long ddx = (long) ((x << 4) + 8) - ax;
               long ddz = (long) ((z << 4) + 8) - az;
               if (ddx * ddx + ddz * ddz > r2) {
                  continue;
               }
               if (isSlimeChunk(seed, x, z)) {
                  thisRing.add(new ChunkPos(x, z));
               }
            }
         }
         // Within one ring the corners are further away than the edge midpoints, so a ring is not
         // itself sorted. Sorting each ring keeps "nearest first" true at every prefix.
         thisRing.sort((p, q) -> Long.compare(d2(p, ax, az), d2(q, ax, az)));
         for (ChunkPos p : thisRing) {
            if (found.size() >= limit) {
               break;
            }
            found.add(p);
         }
      }
      return found;
   }

   /**
    * Would asking for {@code count} of them within {@code radius} actually reject any seeds?
    *
    * Worth asking before every slime search, because the honest answer is usually no. One chunk in
    * ten is a slime chunk and the chunks inside a radius grow with its SQUARE, so "3 slime chunks
    * within 200" sounds demanding and is a certainty: {@code slimeDiag} measured an average of 48.4
    * slime chunks within 200 blocks and never fewer than 28 in 2000 seeds. Three is not a filter.
    *
    * The rule is derived rather than tabulated. The count is binomial — {@code n} chunks each with
    * probability 1/{@link #ONE_IN} — so it clusters tightly around its mean, and an ask sitting more
    * than about 1.645 standard deviations BELOW that mean is satisfied by ~95% of seeds or more.
    * {@code slimeDiag} prints this prediction next to the measured rate for every radius it tests,
    * so the approximation is checked against the real distribution instead of being trusted.
    */
   public static boolean narrows(int count, int radius) {
      double chunks = Math.PI * radius * radius / 256.0;
      double p = 1.0 / ONE_IN;
      double mean = chunks * p;
      double sd = Math.sqrt(chunks * p * (1.0 - p));
      return count > mean - 1.645 * sd;
   }

   /** How many slime chunks are within {@code radius}, counted up to {@code limit} and no further. */
   public static int count(long seed, int ax, int az, int radius, int limit) {
      return near(seed, ax, az, radius, limit).size();
   }

   /** Distance from the chunk's CENTRE, which vanilla will name for us rather than us shifting. */
   private static long d2(ChunkPos p, int ax, int az) {
      long dx = (long) p.getMiddleBlockX() - ax;
      long dz = (long) p.getMiddleBlockZ() - az;
      return dx * dx + dz * dz;
   }

   private SlimeChunks() {
   }
}
