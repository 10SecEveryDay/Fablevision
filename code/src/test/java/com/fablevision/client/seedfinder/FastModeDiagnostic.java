package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fablevision.VillageDiagnostic;
import com.fablevision.client.seedfinder.SeedCriteria.Cand;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import com.fablevision.client.seedfinder.SeedCriteria.StructureTarget;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Dev-only: answers ONE question, per seed, with no interpretation left to the reader —
 * <b>when Fast mode reports a content match, is the content really there?</b>
 *
 * Why this exists:
 *   A content predicate ("Village with Armorer") is only true if the structure was ASSEMBLED and
 *   its piece list really contains the building. If Fast mode skipped that assembly and matched on
 *   placement alone, every content row would be handing back seeds nobody checked. That claim is
 *   cheap to make and cheap to disprove, so this disproves or proves it by measurement rather than
 *   by reading the code.
 *
 * What each seed prints, in order:
 *   1. EXACTLY the match lines the player sees, from the shipped {@link SeedCriteria#test}.
 *   2. The number of structure ASSEMBLIES that run happened to pay for. Zero here, on a seed that
 *      matched a content row, would BE the bug — nothing else could produce a match.
 *   3. An independent re-assembly of the structure at the reported coordinates, listing the piece
 *      that satisfied the token. This is the verification, done a second time, by different code.
 *   4. What {@code /locate} would have sent a player to instead — the nearest structure to the
 *      REAL spawn, and whether it has the feature.
 *
 * Point 4 is the one that matters when a walk "fails". Fast mode measures from 0,0, but a player
 * spawns at {@code findSpawnPosition}, which wanders up to ~2144 blocks away. So the verified
 * structure and the one /locate finds are routinely different structures, and checking the second
 * one says nothing about the first. This table shows both so the two can never be confused again.
 */
public final class FastModeDiagnostic {

   /** One structure that really generates: where it is, and whether it has the token. */
   private record Found(ChunkPos chunk, BlockPos pos, Holder<Structure> winner, boolean hasToken,
                        Identifier match, int pieces) {}

   public static void main(String[] args) {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);

      if (!VillageLayout.available()) {
         System.out.println("FAILED: no structure templates - nothing here can be trusted.");
         return;
      }

      // The row under test, and the shipped Fast-mode distance. Both are arguments so any content
      // row can be checked, but the defaults are the exact combination that was reported wrong.
      String label = args.length > 0 ? args[0].replace('_', ' ') : "Village with Armorer";
      int radius = args.length > 1 ? Integer.parseInt(args[1]) : 100;
      long limit = args.length > 2 ? Long.parseLong(args[2]) : 3000L;
      int want = args.length > 3 ? Integer.parseInt(args[3]) : 5;

      List<StructureTarget> catalog = SeedCatalog.structures(ctx);
      StructureTarget row = catalog.stream()
            .filter(t -> t.label.equalsIgnoreCase(label))
            .findFirst()
            .orElse(null);
      if (row == null) {
         System.out.println("FAILED: no catalog row called \"" + label + "\".");
         System.out.println("rows with a content token:");
         catalog.stream().filter(t -> t.building != null)
               .forEach(t -> System.out.println("   " + t.label));
         return;
      }
      // Read from the row rather than retyped, so this tests the shipped predicate.
      String token = row.building;
      if (token == null) {
         System.out.println("FAILED: \"" + row.label + "\" is not a content row - it demands no piece.");
         return;
      }
      // The same structure kind with NO feature demanded. This is what /locate would find, and
      // what the "which village did the player actually walk to" half of the table needs.
      StructureTarget plain = catalog.stream()
            .filter(t -> t.building == null && t.dim == row.dim && t.zombie == row.zombie
                  && sameKind(t, row))
            .findFirst()
            .orElse(row);

      System.out.println("========== FAST MODE: IS THE CONTENT REALLY THERE? ==========");
      System.out.println("row under test : " + row.label);
      System.out.println("token          : " + token + "   (read from the catalog row, not retyped)");
      System.out.println("plain row       : " + plain.label + "   (used for the /locate simulation)");
      System.out.println("fast radius    : " + radius + " blocks, measured from 0,0");
      System.out.println();

      StructureTarget target = row.withRadius(radius);
      StructureTarget plainTarget = plain.withRadius(radius);

      int hits = 0;
      int verified = 0;
      int falseMatches = 0;
      int locateWouldMislead = 0;
      long assembliesTotal = 0;

      SeedFunnel.ENABLED = true;
      for (long seed = 1; seed <= limit && hits < want; seed++) {
         SeedCriteria criteria = new SeedCriteria();
         criteria.structures.add(target);
         List<String> lines = new ArrayList<>();
         SeedFunnel.reset();
         // describe=true: the lines a player is actually shown, which is what this checks.
         boolean hit = criteria.test(seed, ctx, lines, true, null, true);
         long assemblies = SeedFunnel.assemblies;
         if (!hit) {
            continue;
         }
         hits++;
         assembliesTotal += assemblies;

         System.out.println("SEED " + seed);
         System.out.println("  1. WHAT THE SEARCH REPORTED (the lines the player sees):");
         lines.forEach(l -> System.out.println("       " + l));

         System.out.println("  2. ASSEMBLIES THIS SEED PAID FOR: " + assemblies
               + (assemblies == 0
                     ? "   <-- ZERO. Assembly was SKIPPED; the match is unverified."
                     : "   (non-zero: the structure was built and its piece list read)"));

         BlockPos claimed = parsePos(lines, row.label);
         RandomState rs = ctx.randomState(row.dim, seed);
         ChunkGeneratorStructureState st = ctx.structureState(row.dim, seed, rs);
         RandomState full = ctx.fullRandomState(row.dim, seed);

         // Every structure of this kind Fast mode could have chosen, re-derived and re-assembled
         // by this file rather than trusted from the search.
         List<Found> nearOrigin = confirmedNear(ctx, plainTarget, seed, new BlockPos(0, 64, 0),
               radius, token, full, rs, st, Integer.MAX_VALUE);
         System.out.println("  3. INDEPENDENT RE-CHECK of every " + plain.label.toLowerCase(java.util.Locale.ROOT)
               + " within " + radius + " of 0,0:");
         for (Found f : nearOrigin) {
            boolean isClaimed = claimed != null && f.pos().getX() == claimed.getX()
                  && f.pos().getZ() == claimed.getZ();
            System.out.println("       " + pad(f.pos().getX() + ", " + f.pos().getZ(), 16)
                  + pad(f.pieces() + " pieces", 12)
                  + pad(f.hasToken() ? "HAS " + token : "no " + token, 34)
                  + (f.match() == null ? "" : f.match().getPath())
                  + (isClaimed ? "   <== the one the search reported" : ""));
         }
         Found claimedFound = nearOrigin.stream()
               .filter(f -> claimed != null && f.pos().getX() == claimed.getX()
                     && f.pos().getZ() == claimed.getZ())
               .findFirst()
               .orElse(null);
         if (claimedFound == null) {
            System.out.println("       VERDICT: could not re-derive the reported structure - INVESTIGATE.");
         } else if (claimedFound.hasToken()) {
            verified++;
            System.out.println("       VERDICT: VERIFIED - the reported structure really has it.");
         } else {
            falseMatches++;
            System.out.println("       VERDICT: FALSE MATCH - the reported structure does NOT have it.");
         }

         // What a player standing at spawn and typing /locate would have been sent to.
         BlockPos spawn = ctx.randomState(Dim.OVERWORLD, seed).sampler().findSpawnPosition();
         long spawnOut = Math.round(Math.hypot(spawn.getX(), spawn.getZ()));
         // Only the nearest one needs assembling - that is the only structure /locate would send
         // anyone to, and assembling every village inside 3000 blocks costs half a minute a seed.
         List<Found> nearSpawn = confirmedNear(ctx, plain.withRadius(3000), seed, spawn, 3000,
               token, full, rs, st, 1);
         System.out.println("  4. WHAT /locate WOULD HAVE SENT YOU TO INSTEAD:");
         System.out.println("       real spawn @ " + spawn.getX() + ", " + spawn.getZ()
               + "   (" + spawnOut + " blocks from 0,0, which is what Fast mode measured from)");
         if (nearSpawn.isEmpty()) {
            System.out.println("       no " + plain.label.toLowerCase(java.util.Locale.ROOT) + " within 3000 of spawn");
         } else {
            Found nearest = nearSpawn.get(0);
            long d = Math.round(Math.hypot(nearest.pos().getX() - spawn.getX(),
                  nearest.pos().getZ() - spawn.getZ()));
            boolean same = claimedFound != null && nearest.pos().equals(claimedFound.pos());
            if (!same) {
               locateWouldMislead++;
            }
            System.out.println("       nearest to spawn @ " + nearest.pos().getX() + ", "
                  + nearest.pos().getZ() + "   (" + d + " blocks away)   "
                  + (nearest.hasToken() ? "HAS " + token : "no " + token));
            System.out.println("       is that the structure the search verified? "
                  + (same ? "YES - /locate happens to agree here"
                        : "NO  - /locate sends you to a DIFFERENT structure, which the search never claimed"));
         }
         System.out.println();
      }
      SeedFunnel.ENABLED = false;

      System.out.println("========================= SUMMARY =========================");
      System.out.println("  seeds that matched      : " + hits);
      System.out.println("  independently VERIFIED  : " + verified);
      System.out.println("  FALSE MATCHES           : " + falseMatches
            + (falseMatches == 0 ? "   (none - the predicate is sound in Fast mode)"
                  : "   <-- REAL BUG"));
      System.out.println("  assemblies per match    : "
            + (hits == 0 ? "n/a" : String.format("%.1f", assembliesTotal / (double) hits))
            + "   (0.0 would mean assembly never ran)");
      System.out.println("  seeds where /locate goes somewhere else : " + locateWouldMislead
            + " of " + hits);
      System.out.println("  On those seeds, checking the /locate structure tests a structure the");
      System.out.println("  search made no claim about. Teleport to the reported coordinates instead.");
      System.out.println("===========================================================");
   }

   /** Two rows are about the same structure kind when their id sets overlap - derived from the
    *  live catalog rather than a table of names, so it follows whatever this version ships. */
   private static boolean sameKind(StructureTarget a, StructureTarget b) {
      for (Identifier id : a.wanted) {
         if (b.wanted.contains(id)) {
            return true;
         }
      }
      return false;
   }

   /**
    * Every structure of {@code target} that really generates within {@code reach} of {@code from},
    * nearest first, with the nearest {@code maxAssemble} of them assembled and tested for
    * {@code token}.
    *
    * Placement is confirmed for ALL of them and the sort happens before any assembly, so the cap
    * only limits how many piece lists are built - it can never change which structure comes first.
    * That matters because assembly is by far the expensive half: a 3000-block sweep can hold
    * dozens of villages and building every one of them costs about half a minute per seed.
    */
   private static List<Found> confirmedNear(WorldgenContext ctx, StructureTarget target, long seed,
                                            BlockPos from, int reach, String token, RandomState full,
                                            RandomState rs, ChunkGeneratorStructureState st,
                                            int maxAssemble) {
      record Site(ChunkPos chunk, BlockPos pos, Holder<Structure> winner) {}
      List<Site> sites = new ArrayList<>();
      for (Cand cand : target.stage0(seed, false)) {
         @SuppressWarnings("unchecked")
         Holder<Structure>[] winnerOut = new Holder[1];
         BlockPos pos = target.confirm(seed, ctx, rs, st, cand, winnerOut);
         if (pos == null) {
            continue;
         }
         if (Math.hypot(pos.getX() - from.getX(), pos.getZ() - from.getZ()) > reach) {
            continue;
         }
         sites.add(new Site(cand.chunk(), pos, winnerOut[0]));
      }
      sites.sort(Comparator.comparingDouble(
            s -> Math.hypot(s.pos().getX() - from.getX(), s.pos().getZ() - from.getZ())));

      List<Found> out = new ArrayList<>();
      for (Site s : sites) {
         if (out.size() >= maxAssemble) {
            break;
         }
         List<Identifier> pieces = VillageLayout.pieceTemplates(
               ctx.source, ctx, seed, s.chunk(), s.winner(), full, target.dim);
         Identifier match = null;
         for (Identifier id : pieces) {
            if (VillageLayout.tokenMatches(id.getPath(), token)) {
               match = id;
               break;
            }
         }
         out.add(new Found(s.chunk(), s.pos(), s.winner(), match != null, match, pieces.size()));
      }
      return out;
   }

   /** Pulls the coordinates back out of the match line the player is shown, so this checks the
    *  claim as WRITTEN rather than a re-derivation of what it probably meant. */
   private static BlockPos parsePos(List<String> lines, String label) {
      for (String line : lines) {
         if (!line.startsWith(label + " ")) {
            continue;
         }
         java.util.regex.Matcher m = COORDS.matcher(line);
         if (m.find()) {
            return new BlockPos(Integer.parseInt(m.group(1)), 64, Integer.parseInt(m.group(2)));
         }
      }
      return null;
   }

   /**
    * The FIRST coordinate pair on the line, whatever words come before it.
    *
    * This used to look for the literal prefix {@code "<label> @ "}, and that quietly broke the whole
    * diagnostic when 1.36.0 rewrote the match lines to name their dimension — every line became
    * "<label> — Overworld x, z · N blocks from spawn", the prefix never matched, and the tool
    * reported "could not re-derive the reported structure" for structures it had just re-derived
    * correctly two lines above. A verification tool that fails silently into "INVESTIGATE" is worse
    * than no tool: it cries wolf until someone stops reading it.
    *
    * Matching by SHAPE fixes it for good. Every line SeedCriteria.findLine produces carries exactly
    * one coordinate pair before the distance, in all three dimensions, and the trailing Fast-mode
    * "(really N from your spawn)" contains no pair to be confused with — so the first match is
    * always the position being claimed, whatever the surrounding wording becomes next.
    */
   private static final java.util.regex.Pattern COORDS =
         java.util.regex.Pattern.compile("(-?\\d+), (-?\\d+)");

   private static String pad(String s, int width) {
      StringBuilder sb = new StringBuilder(s);
      while (sb.length() < width) {
         sb.append(' ');
      }
      return sb.toString();
   }

   private FastModeDiagnostic() {
   }
}
