package com.fablevision.client.seedfinder;

import java.util.List;
import java.util.Locale;

import com.fablevision.VillageDiagnostic;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import com.fablevision.client.seedfinder.SeedCriteria.StructureTarget;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Dev-only: opens up ONE structure at ONE coordinate and prints everything about it.
 *
 * Two questions it exists to answer, and they turn out to be the same question:
 *
 *   1. "The tool said this village has a fisher cottage and the game says it doesn't." Either the
 *      piece really is in the assembled list (and the token or the walk is at fault) or it is not
 *      (and the census is). Printing the whole list settles it in one run.
 *
 *   2. "The predicted coordinates were off." The number every tool here reports is
 *      {@code getLocatePos}, which is derived from the structure's CHUNK — not from where its
 *      pieces ended up. For a compact structure those are nearly the same point. For a sprawling
 *      jigsaw one they need not be, and nothing has ever measured the gap. This prints both, plus
 *      the real bounding box, so the drift is a number rather than an impression.
 */
public final class InspectDiagnostic {

   public static void main(String[] args) {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      if (!VillageLayout.available()) {
         System.out.println("FAILED: no structure templates.");
         return;
      }

      long seed = args.length > 0 ? Long.parseLong(args[0]) : 1L;
      int wantX = args.length > 1 ? Integer.parseInt(args[1]) : -240;
      int wantZ = args.length > 2 ? Integer.parseInt(args[2]) : -912;
      String label = args.length > 3 ? args[3].replace('_', ' ') : "Surface Village";
      String token = args.length > 4 ? args[4] : "fisher";

      List<StructureTarget> catalog = SeedCatalog.structures(ctx);
      StructureTarget plain = catalog.stream()
            .filter(t -> label.equalsIgnoreCase(t.label))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no catalog row \"" + label + "\""));
      Dim dim = plain.dim;

      System.out.println("========== INSPECT " + label + " @ " + wantX + ", " + wantZ
            + "  (seed " + seed + ") ==========");

      // Every structure of this kind anywhere near the asked-for point, using the shipped funnel.
      List<Placed.Spot> spots = Placed.near(ctx, plain.withRadius(4000), seed, dim, 4000);
      Placed.Spot best = null;
      double bestD = Double.MAX_VALUE;
      for (Placed.Spot s : spots) {
         double d = Math.hypot(s.pos().getX() - wantX, s.pos().getZ() - wantZ);
         if (d < bestD) {
            bestD = d;
            best = s;
         }
      }
      if (best == null) {
         System.out.println("  no " + label + " found within 4000 blocks at all.");
         return;
      }
      System.out.println("  nearest one the finder knows about:");
      System.out.println("    chunk        : " + best.chunk());
      System.out.println("    locate pos   : " + best.pos().getX() + ", " + best.pos().getZ()
            + "   (this is the number every prediction prints)");
      System.out.println("    asked about  : " + wantX + ", " + wantZ
            + "   -> " + Math.round(bestD) + " blocks away");
      System.out.println("    variant      : "
            + best.winner().unwrapKey().map(k -> k.identifier().toString()).orElse("?"));

      RandomState full = ctx.fullRandomState(dim, seed);
      Holder<Structure> winner = best.winner();
      StructureStart start = assemble(ctx, seed, best, winner, full, dim);
      if (start == null || !start.isValid()) {
         System.out.println("  ASSEMBLY RETURNED NOTHING — this structure does not really generate here.");
         return;
      }

      List<Identifier> pieces = VillageLayout.templateIds(start.getPieces());
      System.out.println();
      System.out.println("  assembled " + pieces.size() + " pieces");

      // THE DRIFT MEASUREMENT. The locate pos comes from the chunk; the box comes from where the
      // pieces actually landed. Any difference between them is the error in every coordinate this
      // project has ever printed.
      BoundingBox box = start.getBoundingBox();
      int cx = (box.minX() + box.maxX()) / 2;
      int cz = (box.minZ() + box.maxZ()) / 2;
      System.out.println("  real bounding box : x " + box.minX() + ".." + box.maxX()
            + "   z " + box.minZ() + ".." + box.maxZ());
      System.out.println("  real centre       : " + cx + ", " + cz);
      System.out.println("  DRIFT             : locate pos is "
            + Math.round(Math.hypot(cx - best.pos().getX(), cz - best.pos().getZ()))
            + " blocks from the real centre"
            + "   (box is " + (box.maxX() - box.minX()) + " x " + (box.maxZ() - box.minZ()) + " blocks)");
      boolean inside = wantX >= box.minX() && wantX <= box.maxX()
            && wantZ >= box.minZ() && wantZ <= box.maxZ();
      System.out.println("  the coordinate you walked to is "
            + (inside ? "INSIDE this structure's box" : "OUTSIDE this structure's box"));

      System.out.println();
      System.out.println("  does it contain \"" + token + "\"? "
            + (VillageLayout.hasBuilding(pieces, token) ? "YES" : "NO"));
      System.out.println();
      System.out.println("  every piece, with where it actually sits:");
      // PAIRED, NOT ZIPPED BY INDEX. This used to walk getPieces() and index into templateIds(),
      // which is a list of DIFFERENT length: a code-built piece contributes nothing to it and a
      // ruined portal contributes two entries. Every name after the first such piece was therefore
      // attached to the wrong box — and it looked right, because in a village the unnamed pieces
      // happen to come last. VillageLayout.piecesAt keeps the id and the box together so the
      // mistake cannot be made.
      for (VillageLayout.PieceAt at : VillageLayout.piecesAt(start.getPieces())) {
         BoundingBox b = at.box();
         String id = at.id().getPath();
         boolean hit = VillageLayout.tokenMatches(id, token);
         System.out.println("    " + pad((b.minX() + b.maxX()) / 2 + ", " + (b.minZ() + b.maxZ()) / 2, 16)
               + pad("y=" + (b.minY() + b.maxY()) / 2, 8) + id + (hit ? "   <== THE PIECE UNDER TEST" : ""));
      }
      int named = VillageLayout.piecesAt(start.getPieces()).size();
      if (named < start.getPieces().size()) {
         System.out.println("    (" + (start.getPieces().size() - named)
               + " more pieces are built from code and carry no template name)");
      }
      System.out.println("=====================================================================");
   }

   /** Same assembly the finder uses, but keeping the StructureStart so the boxes can be read. */
   private static StructureStart assemble(WorldgenContext ctx, long seed, Placed.Spot spot,
                                          Holder<Structure> structure, RandomState full, Dim dim) {
      try {
         WorldgenContext.Scaffold scaffold = ctx.scaffold(dim);
         return structure.value().generate(
               structure,
               dim == Dim.NETHER ? net.minecraft.world.level.Level.NETHER
                     : net.minecraft.world.level.Level.OVERWORLD,
               (RegistryAccess) ctx.source, scaffold.generator(), ctx.biomeSource(dim), full,
               VillageLayout.templates(), seed, spot.chunk(), 0, scaffold.height(), h -> true);
      } catch (Throwable t) {
         System.out.println("  assembly threw " + t);
         return null;
      }
   }

   private static String pad(String s, int width) {
      StringBuilder sb = new StringBuilder(s);
      while (sb.length() < width) {
         sb.append(' ');
      }
      return sb.toString();
   }

   private InspectDiagnostic() {
   }
}
