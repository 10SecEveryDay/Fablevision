package com.fablevision;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import com.fablevision.client.seedfinder.VillageLayout;
import com.fablevision.client.seedfinder.WorldgenContext;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * How often is a ruined portal actually ON THE SURFACE, where you can see it?
 *
 * The complaint that prompted this is that most portals the finder hands back are underground, and
 * the finder had no way to tell — a portal's template id is {@code ruined_portal/portal_1} whether it
 * stands in the open air or sits thirty blocks under a hill. The difference is a
 * {@code VerticalPlacement} enum decided during generation and kept on the piece with no getter.
 *
 * {@link VillageLayout#templateIds} now publishes that enum as a synthetic template id, so this
 * measures the real distribution the same way every other rate in this project was measured: by
 * assembling the structure and counting what came out. Every ruined-portal STRUCTURE is measured
 * separately, because the desert, jungle, swamp, mountain and ocean variants each ship their own
 * list of allowed placements and averaging them would describe none of them.
 *
 * {@code gradlew portalDiag --args="<portals to assemble per variant>"}
 */
public final class PortalPlacementDiagnostic {

   public static void main(String[] args) {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      if (!VillageLayout.available()) {
         System.out.println("FAILED: no templates — nothing below would mean anything.");
         return;
      }

      int want = args.length > 0 ? Integer.parseInt(args[0]) : 120;

      // Derived from the live registry rather than a typed list of seven names — the same rule the
      // rest of the project follows, and the reason a datapack that adds an eighth variant would
      // simply appear here.
      List<ResourceKey<Structure>> portals = new ArrayList<>();
      reg.lookupOrThrow(Registries.STRUCTURE).listElementIds()
            .filter(k -> k.identifier().getPath().startsWith("ruined_portal"))
            .sorted(java.util.Comparator.comparing(k -> k.identifier().getPath()))
            .forEach(portals::add);

      System.out.println("========== RUINED PORTAL: SURFACE OR BURIED? ==========");
      System.out.println("  " + portals.size() + " ruined-portal structures in this version");
      System.out.println("  " + want + " assembled per variant, at random seeds and random chunks");
      System.out.println();

      // RANDOM SEEDS AT RANDOM CHUNKS (1.45.0). This used to assemble at chunk 0,0 with seeds 1, 2, 3 …,
      // where the portal's first random draw — the setup pick — barely moves, so a variant configured
      // with two placements came out as one every time and this diagnostic concluded "each variant
      // ships exactly ONE setup". The standard and mountain portals ship two (see RateDiagnostic). It
      // now also compares what assembles with what is CONFIGURED, and fails when they disagree.
      java.util.SplittableRandom pick = new java.util.SplittableRandom(840);
      int problems = 0;
      Map<String, Integer> overall = new TreeMap<>();
      int overallTotal = 0;
      for (ResourceKey<Structure> key : portals) {
         Holder<Structure> holder = reg.lookupOrThrow(Registries.STRUCTURE).getOrThrow(key);
         Dim dim = key.identifier().getPath().contains("nether") ? Dim.NETHER : Dim.OVERWORLD;
         Map<String, Integer> counts = new TreeMap<>();
         int built = 0;
         for (long tries = 0; built < want && tries <= want * 60L; tries++) {
            long seed = pick.nextLong();
            ChunkPos chunk = new ChunkPos(pick.nextInt(-600, 600), pick.nextInt(-600, 600));
            RandomState full = ctx.fullRandomState(dim, seed);
            List<Identifier> pieces = VillageLayout.pieceTemplates(
                  reg, ctx, seed, chunk, holder, full, h -> true, dim);
            if (pieces.isEmpty()) {
               continue;   // no valid spot here for this seed — a terrain miss, not a bug
            }
            built++;
            boolean saw = false;
            for (Identifier id : pieces) {
               String p = id.getPath();
               if (p.startsWith(VillageLayout.PLACEMENT_PREFIX)) {
                  String placement = p.substring(VillageLayout.PLACEMENT_PREFIX.length());
                  counts.merge(placement, 1, Integer::sum);
                  saw = true;
               }
            }
            if (!saw) {
               counts.merge("(none reported)", 1, Integer::sum);
            }
         }
         java.util.Set<String> configured = new java.util.TreeSet<>();
         if (holder.value() instanceof net.minecraft.world.level.levelgen.structure.structures.RuinedPortalStructure rp) {
            for (var setup : rp.setups) {
               configured.add(setup.placement().getSerializedName());
            }
         }
         java.util.Set<String> seen = new java.util.TreeSet<>(counts.keySet());
         boolean agrees = seen.equals(configured);
         if (!agrees) {
            problems++;
         }
         System.out.println("  " + key.identifier().getPath() + "   (" + built + " assembled)   configured "
               + configured + (agrees ? "" : "   <-- ASSEMBLED " + seen + " — DOES NOT MATCH THE CONFIGURATION"));
         for (var e : counts.entrySet()) {
            System.out.println(String.format("     %-22s %4d   %5.1f%%", e.getKey(), e.getValue(),
                  built == 0 ? 0.0 : 100.0 * e.getValue() / built));
            if (dim == Dim.OVERWORLD) {
               overall.merge(e.getKey(), e.getValue(), Integer::sum);
            }
         }
         if (dim == Dim.OVERWORLD) {
            overallTotal += built;
         }
         System.out.println();
      }

      System.out.println("  ALL OVERWORLD VARIANTS TOGETHER (" + overallTotal + " portals):");
      for (var e : overall.entrySet()) {
         System.out.println(String.format("     %-22s %4d   %5.1f%%", e.getKey(), e.getValue(),
               overallTotal == 0 ? 0.0 : 100.0 * e.getValue() / overallTotal));
      }
      System.out.println();
      System.out.println("  \"(none reported)\" anywhere above would mean the access widener is not");
      System.out.println("  doing its job, and every number here would be meaningless.");
      System.out.println("  NOTE: this weights the variants equally. In a real search the standard");
      System.out.println("  portal is far more common than the desert or jungle one, so read the");
      System.out.println("  per-variant tables, not just the combined one.");
      System.out.println(problems == 0
            ? "  OK — every variant assembled exactly the placements it is configured with."
            : "  " + problems + " PROBLEM(S) — a variant's assembled placements differ from its configuration.");
      System.out.println("======================================================");
      System.exit(problems == 0 ? 0 : 1);
   }

   private PortalPlacementDiagnostic() {
   }
}
