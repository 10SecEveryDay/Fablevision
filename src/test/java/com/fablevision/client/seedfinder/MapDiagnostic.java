package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fablevision.VillageDiagnostic;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;

/**
 * The spawn map's two new layers, checked headlessly on real seeds.
 *
 * The map draws two things the search did not find for it — the biome underneath and the other
 * structures nearby — and both are the kind of thing that looks fine in a screenshot and is wrong.
 * Three questions, and each one is a bug this could otherwise ship:
 *
 *   1. IS EVERY BIOME COLOURED? {@link BiomeTint} is a hand table and the live registry is the
 *      authority. A biome with no entry is drawn grey, which is honest but useless, and — exactly
 *      like the picker icons before {@code iconDiag} — nothing on screen says so. This walks the
 *      whole registry the way iconDiag walks the catalog.
 *
 *   2. ARE THE COLOURS DISTINCT? Two biomes that meet on a map and share a colour are one blob. The
 *      check is for exact duplicates, which is the part a machine can settle; "close enough to look
 *      the same" is left to the eye and to the rules written in BiomeTint.
 *
 *   3. DOES THE SCAN AGREE WITH THE SEARCH? This is the one that matters. The map's structure scan
 *      and the finder's own search are supposed to be the same code answering the same question, so
 *      for every structure the map claims is near spawn, the CATALOG row for that structure is
 *      asked to confirm the same seed independently. A disagreement means the map is drawing
 *      markers the search would not, which is the whole thing the "the map generates nothing of its
 *      own" rule existed to prevent.
 *
 * It also prints the timing, because the map builds this on a background thread while the player
 * looks at a "reading the world" line, and how long that line is up is a design fact.
 *
 * {@code gradlew mapDiag}
 */
public final class MapDiagnostic {

   private static final long[] SEEDS = {1L, -4172144997902289642L, 987654321L, 42L, 7777777L};

   public static void main(String[] args) {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);

      int problems = 0;
      problems += checkPalette(ctx);
      problems += checkCancel(ctx);
      problems += checkMaps(ctx);

      System.out.println();
      System.out.println("================================================");
      System.out.println(problems == 0 ? "  OK — nothing to fix" : "  " + problems + " problem(s)");
      System.exit(problems == 0 ? 0 : 1);
   }

   // ── 1 + 2: the palette ───────────────────────────────────────────────────

   private static int checkPalette(WorldgenContext ctx) {
      System.out.println("=========== BIOME COLOURS ===========");
      Set<String> paths = new LinkedHashSet<>();
      for (Holder.Reference<Biome> b : ctx.biomes.listElements().toList()) {
         b.unwrapKey().map(k -> k.identifier().getPath()).ifPresent(paths::add);
      }
      List<String> missing = new ArrayList<>();
      Map<Integer, String> byColour = new LinkedHashMap<>();
      List<String> clashes = new ArrayList<>();
      for (String p : paths) {
         if (!BiomeTint.known(p)) {
            missing.add(p);
            continue;
         }
         int c = BiomeTint.of(p);
         String other = byColour.put(c, p);
         if (other != null) {
            clashes.add(other + " and " + p + " are both #" + String.format("%06X", c & 0xFFFFFF));
         }
      }
      System.out.println("  biomes in this world : " + paths.size());
      System.out.println("  with a colour        : " + (paths.size() - missing.size()));
      for (String m : missing) {
         System.out.println("  MISSING  " + m + "  (drawn as the 'unknown' grey)");
      }
      for (String c : clashes) {
         System.out.println("  SHARED   " + c);
      }
      return missing.size() + clashes.size();
   }

   /**
    * A BUILD THE MAP HAS ALREADY MOVED ON FROM MUST STOP, not run to the end.
    *
    * Dragging asks for new ground several times a second, and each build is 16,384 climate samples
    * plus a structure scan. Until 1.43.1 only the structure half was abandoned, so every stale pan
    * still finished its biome grid before the one the player was waiting for could start. Measured
    * rather than asserted in the code: a cancelled build must come back with nothing, fast.
    */
   private static int checkCancel(WorldgenContext ctx) {
      System.out.println();
      System.out.println("=========== A CANCELLED BUILD STOPS ===========");
      long t0 = System.nanoTime();
      SeedMapData.View full = SeedMapData.buildAt(ctx, 1L, SeedMapData.MapDim.OVERWORLD,
            SeedMapData.overworldSpawn(ctx, 1L), 0, 0, SeedMapData.MIN_REACH);
      long fullMs = (System.nanoTime() - t0) / 1_000_000;
      t0 = System.nanoTime();
      SeedMapData.View cancelled = SeedMapData.buildAt(ctx, 1L, SeedMapData.MapDim.OVERWORLD,
            SeedMapData.overworldSpawn(ctx, 1L), 0, 0, SeedMapData.MIN_REACH, () -> true);
      long cancelledMs = (System.nanoTime() - t0) / 1_000_000;
      System.out.println("  whole build   : " + fullMs + "ms (" + (full == null ? "null" : full.nearby().size()
            + " structures") + ")");
      System.out.println("  cancelled     : " + cancelledMs + "ms, returned " + (cancelled == null ? "nothing" : "A VIEW"));
      // FULL ZOOM-OUT (1.44.4): the build a drag at the widest zoom waits for, at the widest a view
      // is built (the zoom plus the overscan margin), and how fast one cancelled half-way stops.
      t0 = System.nanoTime();
      SeedMapData.View widest = SeedMapData.buildAt(ctx, 2L, SeedMapData.MapDim.OVERWORLD,
            SeedMapData.overworldSpawn(ctx, 2L), 0, 0, SeedMapData.MAX_BUILD_REACH);
      long widestMs = (System.nanoTime() - t0) / 1_000_000;
      long stopAt = System.nanoTime() + Math.max(50, widestMs / 2) * 1_000_000L;
      long[] stoppedAt = new long[1];
      SeedMapData.View half = SeedMapData.buildAt(ctx, 3L, SeedMapData.MapDim.OVERWORLD,
            SeedMapData.overworldSpawn(ctx, 3L), 0, 0, SeedMapData.MAX_BUILD_REACH, () -> {
               boolean stop = System.nanoTime() >= stopAt;
               if (stop && stoppedAt[0] == 0) {
                  stoppedAt[0] = System.nanoTime();
               }
               return stop;
            });
      long lagMs = stoppedAt[0] == 0 ? -1 : (System.nanoTime() - stoppedAt[0]) / 1_000_000;
      System.out.println("  widest build  : " + widestMs + "ms (" + (widest == null ? "null" : widest.nearby().size()
            + " structures, " + SeedMapData.GRID + "x" + SeedMapData.GRID + " biomes, "
            + SeedMapData.MAX_BUILD_REACH * 2 + " blocks across)") + ")");
      System.out.println("  cancelled half-way through a widest build: stopped " + lagMs + "ms after being told, returned "
            + (half == null ? "nothing" : "A VIEW"));
      int problems = 0;
      if (widest == null || widest.nearby().isEmpty()) {
         System.out.println("  <-- the widest view came back empty");
         problems++;
      }
      if (half != null || lagMs < 0 || lagMs > 1000) {
         System.out.println("  <-- a widest build did not stop promptly when cancelled");
         problems++;
      }
      if (cancelled != null) {
         System.out.println("  <-- a cancelled build still produced a view");
         problems++;
      }
      if (cancelledMs > Math.max(20, fullMs / 4)) {
         System.out.println("  <-- cancelling did not actually save the work");
         problems++;
      }
      return problems;
   }

   // ── 3: the scan, against the search ──────────────────────────────────────

   private static int checkMaps(WorldgenContext ctx) {
      System.out.println();
      System.out.println("=========== MAP BUILD, AND THE SCAN vs THE SEARCH ===========");
      // One catalog row per structure kind, to re-ask the question the map answered. Only plain
      // rows: a row that names a building inside would assemble the structure, which is not what
      // the map claimed and would take a minute per seed.
      Map<String, SeedCriteria.StructureTarget> plain = new LinkedHashMap<>();
      for (SeedCriteria.StructureTarget t : SeedCatalog.structures(ctx)) {
         if (t.special == SeedCriteria.Special.NORMAL && t.building == null) {
            plain.putIfAbsent(t.label, t);
         }
      }

      // Every kind of structure the scan ever names, so the icon check below is about what the map
      // really draws rather than about a list someone typed.
      Set<String> kinds = new LinkedHashSet<>();

      int problems = 0;
      for (long seed : SEEDS) {
         long t0 = System.nanoTime();
         BlockPos spawn = SeedMapData.overworldSpawn(ctx, seed);
         long spawnMs = (System.nanoTime() - t0) / 1_000_000;

         t0 = System.nanoTime();
         SeedMapData.View view = SeedMapData.build(ctx, seed, SeedMapData.MapDim.OVERWORLD, spawn,
               SeedMapData.MIN_REACH);
         long buildMs = (System.nanoTime() - t0) / 1_000_000;

         Set<Integer> colours = new LinkedHashSet<>();
         for (int c : view.tint()) {
            colours.add(c);
         }
         System.out.println();
         System.out.println("  seed " + seed);
         System.out.println("    spawn            : " + spawn.getX() + ", " + spawn.getZ()
               + "   (found in " + spawnMs + "ms)");
         System.out.println("    map built in     : " + buildMs + "ms   ("
               + SeedMapData.GRID + "x" + SeedMapData.GRID + " biome samples + a structure scan)");
         System.out.println("    distinct biomes  : " + colours.size()
               + (colours.size() < 2 ? "   <-- a one-colour map is not a map" : ""));
         System.out.println("    structures found : " + view.nearby().size());

         if (colours.size() < 2) {
            problems++;
         }
         // A map with nothing on it is possible (a mid-ocean spawn) but it should be rare, and a
         // run where EVERY seed comes back empty means the scan is broken rather than the seeds
         // being unlucky — which is what the summary at the end is for.
         int crossChecked = 0;
         for (SeedMapData.Nearby n : view.nearby()) {
            kinds.add(n.label() + "\t" + n.iconLabel());
            String label = catalogLabelFor(n.iconLabel(), plain);
            if (label == null) {
               continue;   // no plain catalog row for this kind; nothing to cross-check against
            }
            crossChecked++;
            if (!searchAgrees(ctx, seed, plain.get(label), n)) {
               // SINCE 1.43.1 THE MAP IS THE STRICTER OF THE TWO: it replays the game's own re-roll
               // with the real generation check, and the search's first pass does not. So a
               // disagreement is settled by asking the GAME. If the game builds it, the map is right
               // and the search merely would have skipped a real one (conservative, not a lie).
               var real = MapTruthDiagnostic.truth((RegistryAccess.Frozen) ctx.source, seed,
                     SeedMapData.MapDim.OVERWORLD, n.x(), n.z(), 40).get(n.chunkX() + "," + n.chunkZ());
               if (real != null && real.contains(n.id())) {
                  System.out.println("    search would skip: " + n.label() + " at " + n.x() + ", " + n.z()
                        + " — the game DOES build " + n.id() + " there, so the map is right");
               } else {
                  System.out.println("    DISAGREES: map says " + n.label() + " at " + n.x() + ", "
                        + n.z() + " — and the game builds " + (real == null ? "nothing" : real) + " there");
                  problems++;
               }
            }
         }
         // PRINTED, because a cross-check that silently checked nothing passes just as loudly as
         // one that checked everything, and this project has been caught by a vacuous check before.
         System.out.println("    cross-checked    : " + crossChecked + " of " + view.nearby().size()
               + " against the search's own rows"
               + (crossChecked == 0 ? "   <-- nothing was actually verified" : ""));
         if (crossChecked == 0 && !view.nearby().isEmpty()) {
            problems++;
         }
      }

      // THE SAME CHECK ICONDIAG DOES, for the same reason. A structure the map draws with the
      // filled-map fallback is a picture of nothing next to a name — and it is silent, which is how
      // four picker rows shipped that way before iconDiag existed.
      System.out.println();
      System.out.println("  structure kinds the map can draw:");
      for (String k : kinds) {
         String[] parts = k.split("\t", 2);
         boolean iconed = SeedIcons.hasIcon(parts[1]);
         System.out.println("    " + (iconed ? "  " : "NO") + "  " + parts[0]
               + (iconed ? "" : "   <-- no icon; drawn as the blank fallback"));
         if (!iconed) {
            problems++;
         }
      }
      return problems;
   }

   /** The plain catalog row that covers what the map called this, or null when there isn't one. */
   private static String catalogLabelFor(String iconLabel, Map<String, SeedCriteria.StructureTarget> plain) {
      return plain.containsKey(iconLabel) ? iconLabel : null;
   }

   /**
    * Does the SEARCH agree there is one of these at that chunk?
    *
    * Re-derived through the catalog row's own stage0 + confirm rather than by calling the map's
    * scan again, which would only prove the scan agrees with itself. A row whose radius is widened
    * to cover the map, then asked whether any of its candidates confirms at that exact position.
    */
   private static boolean searchAgrees(WorldgenContext ctx, long seed, SeedCriteria.StructureTarget row,
                                       SeedMapData.Nearby n) {
      SeedCriteria.StructureTarget wide = row.withRadius(SeedMapData.MIN_REACH * 3);
      var rs = ctx.randomState(Dim.OVERWORLD, seed);
      var state = ctx.structureState(Dim.OVERWORLD, seed, rs);
      for (SeedCriteria.Cand c : wide.stage0(seed, true)) {
         BlockPos at = wide.confirm(seed, ctx, rs, state, c);
         if (at != null && at.getX() == n.x() && at.getZ() == n.z()) {
            return true;
         }
      }
      return false;
   }

   private MapDiagnostic() {
   }
}
