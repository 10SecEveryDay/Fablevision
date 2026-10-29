package com.fablevision.client.seedfinder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fablevision.VillageDiagnostic;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * How often each bastion TYPE turns up — the measurement four catalog rows were missing.
 *
 * The Bridge Bastion row said "the rarest of the four types, so expect a longer search" and no
 * measurement existed anywhere in the project. All four bastion rows carried rarityPct = -1, so the
 * picker showed them with a blank rate while one of their notes asserted a ranking. That is exactly
 * what {@link SeedCatalog}'s own header forbids — a guessed rarity is the number a player uses to
 * decide whether a search is worth starting — and it is the same mistake corrected in 1.37 for the
 * butcher, which prose called "the rarest of the six" and the census put at a middling 24%.
 *
 * A bastion's type IS the top-level folder every one of its pieces comes out of, so this assembles
 * real bastions and reads the folder. The four are bastion/treasure, bastion/bridge,
 * bastion/hoglin_stable and bastion/units — the last being the one everybody calls "generic", a word
 * that appears in no bastion template at all.
 *
 * {@code gradlew bastionDiag --args="<samples>"}
 */
public final class BastionTypeDiagnostic {

   private static final String[] TYPES = {"treasure", "bridge", "hoglin_stable", "units"};

   public static void main(String[] args) {
      int want = args.length > 0 ? Integer.parseInt(args[0]) : 200;
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);

      SeedCriteria.StructureTarget bastion = null;
      for (SeedCriteria.StructureTarget t : SeedCatalog.structures(ctx)) {
         if (t.label.equals("Bastion Remnant")) {
            bastion = t;
         }
      }
      if (bastion == null) {
         System.out.println("Bastion Remnant row missing — cannot measure.");
         return;
      }

      System.out.println("=========== BASTION TYPE MIX (assembling real bastions) ===========");
      Map<String, Integer> counts = new LinkedHashMap<>();
      for (String t : TYPES) {
         counts.put(t, 0);
      }
      int assembled = 0;
      int unknown = 0;
      long started = System.currentTimeMillis();
      SeedCriteria.StructureTarget wide = bastion.withRadius(1200);

      for (long seed = 1; assembled < want && seed < 100_000; seed++) {
         Placed.Spot spot = Placed.nearest(ctx, wide, seed, SeedCriteria.Dim.NETHER, 1200);
         if (spot == null) {
            continue;
         }
         RandomState full = ctx.fullRandomState(SeedCriteria.Dim.NETHER, seed);
         VillageLayout.Built built = VillageLayout.builtAt(ctx.source, ctx, seed, spot.chunk(),
               spot.winner(), full, h -> true, SeedCriteria.Dim.NETHER);
         if (built == null || built.templates().isEmpty()) {
            continue;
         }
         String type = classify(built.templates());
         if (type == null) {
            unknown++;
            continue;
         }
         counts.merge(type, 1, Integer::sum);
         assembled++;
      }

      System.out.printf("  assembled %d bastions in %.1fs%n", assembled,
            (System.currentTimeMillis() - started) / 1000.0);
      if (unknown > 0) {
         System.out.println("  unclassified: " + unknown + "  <-- a fifth type, or a token that moved");
      }
      System.out.println();
      System.out.println("  TYPE            COUNT   RATE   -> .rate() for SeedCatalog");
      String rarest = null;
      int rarestN = Integer.MAX_VALUE;
      for (String t : TYPES) {
         int n = counts.get(t);
         int pct = assembled == 0 ? 0 : (int) Math.round(100.0 * n / assembled);
         System.out.printf("  %-14s %5d   %3d%%   .rate(%d)%n", t, n, pct, pct);
         if (n < rarestN) {
            rarestN = n;
            rarest = t;
         }
      }
      System.out.println();
      System.out.println("  rarest of the four: " + rarest + " (" + rarestN + "/" + assembled + ")");
      System.out.println("  -> the Bridge Bastion note may only claim 'rarest' if that says bridge.");
   }

   /** Which top-level bastion folder these pieces came out of. */
   private static String classify(List<Identifier> templates) {
      for (Identifier id : templates) {
         String path = id.getPath();
         for (String t : TYPES) {
            if (path.contains("bastion/" + t + "/")) {
               return t;
            }
         }
      }
      return null;
   }

   private BastionTypeDiagnostic() {
   }
}
