package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.List;

import com.fablevision.VillageDiagnostic;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Dev-only probe for radiusDiag's "GAINED a hit" line: prints each seed where the exact-radius test
 * turns a miss into a hit, with both runs' lines, so the cause can be read rather than guessed.
 * {@code gradlew radiusProbe --args="<row label, underscores> <radius> <seeds> fast|exact"}
 */
public final class RadiusProbe {
   public static void main(String[] args) {
      String label = args[0].replace('_', ' ');
      int radius = Integer.parseInt(args[1]);
      int seeds = Integer.parseInt(args[2]);
      boolean fast = args.length < 4 || args[3].equals("fast");
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      WorldgenContext ctx = WorldgenContext.get(VillageDiagnostic.loadFullRegistries());
      SeedCriteria.StructureTarget row = null;
      for (SeedCriteria.StructureTarget t : SeedCatalog.structures(ctx)) {
         if (t.label.equals(label)) {
            row = t;
         }
      }
      for (long seed = 1; seed <= seeds; seed++) {
         List<String> before = new ArrayList<>();
         List<String> after = new ArrayList<>();
         SeedFunnel.EXACT_RADIUS = false;
         SeedCriteria a = new SeedCriteria();
         a.structures.add(row.withRadius(radius));
         boolean hitBefore = a.test(seed, ctx, before, fast, null, true, new ArrayList<>());
         SeedFunnel.EXACT_RADIUS = true;
         SeedCriteria b = new SeedCriteria();
         b.structures.add(row.withRadius(radius));
         boolean hitAfter = b.test(seed, ctx, after, fast, null, true, new ArrayList<>());
         if (!hitBefore && hitAfter) {
            System.out.println("GAINED seed " + seed);
            System.out.println("  before (no hit): " + before);
            System.out.println("  after  (hit)   : " + after);
         }
      }
      System.out.println("done");
      System.exit(0);
   }

   private RadiusProbe() {
   }
}
