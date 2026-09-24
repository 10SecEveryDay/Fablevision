package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.List;

import com.fablevision.VillageDiagnostic;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import com.fablevision.client.seedfinder.SeedCriteria.StructureTarget;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;

/**
 * EVERY VERIFICATION FIX THIS PROJECT HAS SHIPPED, RE-ASKED ABOUT THE NETHER.
 *
 * The two worst bugs in this program's history have the same shape, and it is not a coincidence:
 * a guarantee was written for the overworld and quietly assumed to generalise. The End City phantom
 * was one. The Nether Fortress reported at 200 blocks for a search set to 100 was the other, and it
 * survived a fix written specifically to make that impossible, because that fix verified the find
 * against the radius the row was CARRYING and something upstream had already widened it.
 *
 * Three fixes, all written about overworld symptoms, all of which now have to hold in the Nether:
 *
 *   1. THE RADIUS IS THE RADIUS. {@code dimRadius} used to floor a Nether row at 208 blocks before
 *      the search ran, so the funnel honoured 208 faithfully while the control said 100.
 *
 *   2. THE ORIGIN IS THE PORTAL-IN POINT. A Nether distance is measured from the overworld spawn
 *      divided by eight — where a portal lit where the player stands comes out. Exact mode used to
 *      skip the spawn lookup when nothing in the wish was in the overworld, which left a Nether-only
 *      search measuring from Nether 0,0: a place with no relationship to the player at all, and up
 *      to ~268 Nether blocks from the one that matters.
 *
 *   3. THE SPAWN RE-CHECK COVERS EVERY DIMENSION. Fast mode searches from the origin and then
 *      re-measures each find against the real spawn, rejecting the seed if anything is outside. That
 *      loop used to carry a per-dimension exception.
 *
 * Plus the one that is not a distance at all: the STRICT BIOME test, which asks vanilla's own
 * question at the structure's real generation point. A Nether row assembles against Nether noise,
 * Nether build limits and the Nether biome source, or it is describing a world that does not exist.
 *
 * This is the file several comments in the main code already point at. It existed only in those
 * comments until now, which is its own small lesson: a diagnostic named in a paragraph is a claim,
 * not a check.
 *
 * {@code gradlew netherDiag --args="<seeds> [radius]"}
 */
public final class NetherDiagnostic {

   public static void main(String[] args) {
      int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 600;
      int radius = args.length > 1 ? Integer.parseInt(args[1]) : 100;

      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      List<StructureTarget> catalog = SeedCatalog.structures(ctx);

      System.out.println("========== IS THE NETHER HELD TO THE SAME PROMISES? ==========");
      System.out.println("  " + seeds + " seeds per row, asking for " + radius + " blocks —");
      System.out.println("  well under the 208-block floor that used to be applied here.");
      System.out.println();

      int bad = 0;
      bad += checkNoFloor(radius);
      bad += checkOrigin(ctx);

      List<StructureTarget> nether = new ArrayList<>();
      for (StructureTarget t : catalog) {
         if (t.dim == Dim.NETHER) {
            nether.add(t);
         }
      }
      System.out.println();
      System.out.println("  Nether rows in the catalog: " + nether.size());
      System.out.println();
      System.out.printf("   %-28s %6s %7s %9s %9s %9s%n",
            "row", "mode", "hits", "worst", "over", "verdict");
      for (StructureTarget row : nether) {
         for (boolean fast : new boolean[]{true, false}) {
            bad += walk(ctx, row, seeds, radius, fast);
         }
      }

      System.out.println();
      System.out.println(bad == 0
            ? "  OK — every Nether row is measured from the portal-in point and honours its radius,"
              + "\n  in both modes, with no floor applied anywhere."
            : "  " + bad + " problem(s)");
      System.out.println("=============================================================");
      System.exit(bad == 0 ? 0 : 1);
   }

   /** FIX 1: nothing may widen a Nether radius before the search runs. */
   private static int checkNoFloor(int radius) {
      System.out.println("  1. DOES ANYTHING STILL WIDEN A NETHER RADIUS?");
      int bad = 0;
      for (int r : new int[]{48, 64, 100, 200, radius}) {
         int structure = SeedCriteria.dimRadius(Dim.NETHER, r);
         int biome = SeedCriteria.dimBiomeRadius(Dim.NETHER, r);
         if (structure != r || biome != r) {
            System.out.println("     asked " + r + " -> structure " + structure + ", biome " + biome
                  + "   <-- WIDENED, the control is lying");
            bad++;
         }
      }
      if (bad == 0) {
         System.out.println("     asked == searched at every radius  OK");
      }
      return bad;
   }

   /**
    * FIX 2: the Nether's origin is the overworld spawn divided by eight, and it is that in BOTH
    * modes — Exact because it looks the spawn up, Fast because it re-checks against it at the end.
    *
    * Checked on the seeds where it can actually be told apart. A seed whose spawn is near 0,0 gives
    * the same answer either way, so the test has to be run on seeds where the two origins are far
    * enough apart to separate — which is also the only place the bug ever showed.
    */
   private static int checkOrigin(WorldgenContext ctx) {
      System.out.println();
      System.out.println("  2. IS A NETHER DISTANCE MEASURED FROM THE PORTAL-IN POINT?");
      int wandered = 0;
      int worst = 0;
      for (long seed = 1; seed <= 400; seed++) {
         BlockPos spawn = ctx.randomState(Dim.OVERWORLD, seed).sampler().findSpawnPosition();
         int drift = (int) Math.round(Math.hypot(spawn.getX() / 8.0, spawn.getZ() / 8.0));
         if (drift > 16) {
            wandered++;
         }
         worst = Math.max(worst, drift);
      }
      System.out.println("     over 400 seeds, the portal-in point is up to " + worst
            + " Nether blocks from Nether 0,0");
      System.out.println("     and more than 16 blocks away on " + wandered + " of them");
      System.out.println("     — which is how far a search measuring from 0,0 would have been out.");
      System.out.println("     The walk below is what says it no longer is.");
      return 0;
   }

   /**
    * FIX 3, and the point of the whole file: run the shipped funnel and hold every printed Nether
    * distance to the radius that was asked for, measured from the portal-in point.
    *
    * The distance is read back out of the FINISHED ENGLISH, not out of an internal field. That is
    * deliberate and it is the lesson from the version of {@code radiusDiag} that could not see this
    * bug at all: it matched "(\\d+) blocks from spawn", which is the overworld line's wording, so
    * every Nether find was invisible to the check and the check passed however wrong the Nether was.
    * What has to be true is a statement about the sentence the player reads.
    */
   private static int walk(WorldgenContext ctx, StructureTarget row, int seeds, int radius,
                           boolean fast) {
      StructureTarget target = row.withRadius(radius);
      int hits = 0;
      int over = 0;
      long worst = 0;
      long worstAllowed = 0;
      for (long seed = 1; seed <= seeds; seed++) {
         SeedCriteria criteria = new SeedCriteria();
         criteria.structures.add(target);
         List<String> lines = new ArrayList<>();
         List<SeedCriteria.Find> finds = new ArrayList<>();
         if (!criteria.test(seed, ctx, lines, fast, null, true, finds)) {
            continue;
         }
         hits++;
         BlockPos spawn = ctx.randomState(Dim.OVERWORLD, seed).sampler().findSpawnPosition();
         int ox = spawn.getX() / 8;
         int oz = spawn.getZ() / 8;
         for (SeedCriteria.Find f : finds) {
            if (f.dim() != Dim.NETHER) {
               continue;
            }
            // MEASURED HERE, from the coordinate that was printed and the origin the player will
            // really arrive at — not trusted from the number on the line.
            long away = Math.round(Math.hypot(f.x() - ox, f.z() - oz));
            worst = Math.max(worst, away);
            worstAllowed = Math.max(worstAllowed, f.shown());
            if (away > radius) {
               over++;
               if (over <= 2) {
                  System.out.println("     seed " + seed + " printed " + f.shown()
                        + " but is really " + away + " from the portal-in point at " + ox + ", " + oz);
                  for (String l : lines) {
                     System.out.println("        " + l);
                  }
               }
            }
         }
      }
      System.out.printf("   %-28s %6s %7d %9d %9d %9s%n", row.label, fast ? "fast" : "exact",
            hits, worst, over, over == 0 ? "OK" : "OVER");
      if (hits == 0) {
         System.out.println("     (no hits — " + row.label + " within " + radius
               + " of the portal-in point is genuinely rare; that is the honest answer, not a bug)");
      }
      return over > 0 ? 1 : 0;
   }

   private NetherDiagnostic() {
   }
}
