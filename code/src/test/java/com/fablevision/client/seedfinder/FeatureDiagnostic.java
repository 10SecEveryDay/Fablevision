package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fablevision.VillageDiagnostic;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import com.fablevision.client.seedfinder.SeedCriteria.StructureTarget;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * TWO QUESTIONS ABOUT EVERY CONTENT ROW, ASKED OF THE COORDINATE A PLAYER IS ACTUALLY SENT TO.
 *
 *   1. IS IT TRUE? Run the shipped funnel, take the coordinate it printed, re-derive the structure
 *      there with code that never touches the funnel, and look for the row's token in its real
 *      piece list. A disagreement here is the End City failure again: a claim made about a place
 *      that does not support it.
 *
 *   2. HOW FAR IS IT? The row promises a BUILDING and prints a STRUCTURE. The number every
 *      prediction prints is {@code getLocatePos}, derived from the structure's chunk; the
 *      weaponsmith is wherever the jigsaw hung it, and a village routinely spans 130 by 176 blocks.
 *      So a player can stand exactly where they were sent, inside a village that really does have
 *      the building, and not be able to see it.
 *
 * The second question is the one nothing in this project had ever asked, and it is why "the search
 * is correct" and "I went there and it wasn't there" were both true reports about the same seed.
 * Every existing check verifies the CLAIM. This one also measures the WALK.
 *
 * BOTH MODES, because they are different code paths and only Fast mode had ever been checked
 * end-to-end ({@code fastDiag}). Exact mode's content rows had no equivalent at all.
 *
 * {@code gradlew featureDiag --args="<hits wanted per row> [radius] [fast|exact|both] [label substring]"}
 */
public final class FeatureDiagnostic {

   /** One reported find, re-derived from scratch: was the claim true, and where is the thing? */
   private record Checked(boolean rederived, boolean hasToken, long gap, int pieces,
                          int spanX, int spanZ, String template) {}

   public static void main(String[] args) {
      int want = args.length > 0 ? Integer.parseInt(args[0]) : 8;
      int radius = args.length > 1 ? Integer.parseInt(args[1]) : 400;
      String modeArg = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "both";
      String only = args.length > 3 ? args[3].replace('_', ' ').toLowerCase(Locale.ROOT) : "";

      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      if (!VillageLayout.available()) {
         System.out.println("FAILED: no structure templates - nothing here can be trusted.");
         System.exit(1);
      }

      List<StructureTarget> catalog = SeedCatalog.structures(ctx);
      List<StructureTarget> rows = new ArrayList<>();
      for (StructureTarget t : catalog) {
         if (t.building != null && (only.isEmpty() || t.label.toLowerCase(Locale.ROOT).contains(only))) {
            rows.add(t);
         }
      }

      boolean[] modes = switch (modeArg) {
         case "fast" -> new boolean[]{true};
         case "exact" -> new boolean[]{false};
         default -> new boolean[]{true, false};
      };

      System.out.println("========== EVERY CONTENT ROW, END TO END ==========");
      System.out.println("  rows under test : " + rows.size());
      System.out.println("  hits wanted     : " + want + " per row per mode, within " + radius + " blocks");
      System.out.println();
      System.out.println("  \"false\" = the search reported a structure that does NOT have the feature.");
      System.out.println("  \"gap\"   = how far the feature is from the coordinate the player is given.");
      System.out.println();
      System.out.printf("  %-36s %5s %5s %6s %6s %6s %6s %6s%n",
            "row", "mode", "hits", "false", "median", "p90", "max", ">64");

      startSeedWatch();
      int stalledRows = 0;
      int falseTotal = 0;
      int unrederived = 0;
      long worstGap = 0;
      String worstRow = "";
      int rowsOver64 = 0;
      for (StructureTarget row : rows) {
         boolean rowOver64 = false;
         for (boolean fast : modes) {
            hitSeeds.clear();
            rowStalled = false;
            List<Checked> results = run(ctx, row, radius, want, fast);
            String mode = fast ? "fast" : "exact";
            if (rowStalled) {
               stalledRows++;
            }
            if (results.isEmpty()) {
               System.out.printf("  %-36s %5s %5s%n", row.label, mode, "none");
               continue;
            }
            List<Long> gaps = new ArrayList<>();
            int wrong = 0;
            int missing = 0;
            for (Checked c : results) {
               if (!c.rederived()) {
                  missing++;
                  continue;
               }
               if (!c.hasToken()) {
                  wrong++;
                  continue;
               }
               gaps.add(c.gap());
            }
            falseTotal += wrong;
            unrederived += missing;
            gaps.sort(null);
            long median = gaps.isEmpty() ? -1 : gaps.get(gaps.size() / 2);
            long p90 = gaps.isEmpty() ? -1 : gaps.get(Math.min(gaps.size() - 1, (int) (gaps.size() * 0.9)));
            long max = gaps.isEmpty() ? -1 : gaps.get(gaps.size() - 1);
            long over64 = gaps.stream().filter(g -> g > 64).count();
            if (over64 > 0) {
               rowOver64 = true;
            }
            if (max > worstGap) {
               worstGap = max;
               worstRow = row.label + " (" + mode + ")";
            }
            System.out.printf("  %-36s %5s %5d %6s %6d %6d %6d %6d%n",
                  row.label, mode, results.size(),
                  wrong == 0 && missing == 0 ? "-" : (wrong + (missing > 0 ? "+" + missing + "?" : "")),
                  median, p90, max, over64);
            // THE SEEDS, so two runs can be compared seed by seed (1.44.5): a row that comes out
            // differently shows the first seed where the search went another way.
            System.out.println("        hit seeds: " + hitSeeds);
         }
         if (rowOver64) {
            rowsOver64++;
         }
      }

      System.out.println();
      System.out.println("  FALSE MATCHES               : " + falseTotal
            + (falseTotal == 0 ? "   (every reported structure really has its feature)" : "   <-- REAL BUG"));
      System.out.println("  reported but not re-derivable: " + unrederived
            + (unrederived == 0 ? "" : "   <-- INVESTIGATE"));
      System.out.println("  rows whose feature is ever >64 blocks from the printed coordinate: "
            + rowsOver64 + " of " + rows.size());
      System.out.println("  worst gap seen              : " + worstGap + " blocks (" + worstRow + ")");
      System.out.println();
      System.out.println("  A gap is not a wrong search. It is how far a player walks looking for");
      System.out.println("  something that really is there, and it is why a row that verifies");
      System.out.println("  perfectly can still be reported as a phantom from inside the game.");
      System.out.println("==================================================");
      if (stalledRows > 0) {
         System.out.println("  STALLED ROWS                : " + stalledRows + "   (hit the " + ROW_LIMIT_MS / 60_000
               + "-minute limit — see the STALL lines above for how far they got)");
      }
      System.exit(falseTotal == 0 && unrederived == 0 && stalledRows == 0 ? 0 : 1);
   }

   /** A row that takes longer than this is reported as stalled. The slowest row in 1.44.4 took about 4. */
   private static final long ROW_LIMIT_MS = 15 * 60_000L;
   private static final List<Long> hitSeeds = new java.util.concurrent.CopyOnWriteArrayList<>();
   private static volatile boolean rowStalled;
   private static volatile long curSeed;
   private static volatile long curSeedStartNs;

   /**
    * ONE SEED TAKING OVER A MINUTE: the main thread's stack, printed, once per seed (1.44.5). A seed
    * normally takes milliseconds; this is what tells a hang inside one seed apart from a search that is
    * moving and not finding anything.
    */
   private static void startSeedWatch() {
      Thread main = Thread.currentThread();
      Thread t = new Thread(() -> {
         long reported = Long.MIN_VALUE;
         while (true) {
            try {
               Thread.sleep(5_000);
            } catch (InterruptedException stop) {
               return;
            }
            long start = curSeedStartNs;
            long seed = curSeed;
            if (start != 0 && seed != reported && System.nanoTime() - start > 60_000_000_000L) {
               reported = seed;
               System.out.println("  STALL: seed " + seed + " has taken " + (System.nanoTime() - start) / 1_000_000_000L
                     + " s so far");
               System.out.println(SearchWatchdog.threadReport(main));
            }
         }
      }, "featureDiag-seed-watch");
      t.setDaemon(true);
      t.start();
   }

   /** Run the shipped funnel until {@code want} hits, re-checking each one independently. */
   private static List<Checked> run(WorldgenContext ctx, StructureTarget row, int radius, int want,
                                    boolean fast) {
      List<Checked> out = new ArrayList<>();
      StructureTarget target = row.withRadius(radius);
      String token = row.building.toLowerCase(Locale.ROOT);
      // A generous ceiling: the rarest rows (the lava vault, the stable) are one structure in
      // fifty and the structure itself is not common either.
      long limit = 400_000L;
      long deadline = System.nanoTime() + ROW_LIMIT_MS * 1_000_000L;
      for (long seed = 1; seed <= limit && out.size() < want; seed++) {
         // A ROW TIME LIMIT (1.44.5). One run of this spent over two hours on a row that takes a minute
         // and a half, silently, and could not be reproduced. Now a row that runs this long says how far
         // it got and the run moves on, failed, instead of going quiet.
         if (System.nanoTime() > deadline) {
            rowStalled = true;
            System.out.println("  STALL: " + row.label + (fast ? " fast" : " exact") + " passed " + ROW_LIMIT_MS / 60_000
                  + " minutes at seed " + seed + " of " + limit + " with " + out.size() + "/" + want
                  + " hits (hit seeds " + hitSeeds + ")");
            break;
         }
         SeedCriteria criteria = new SeedCriteria();
         criteria.structures.add(target);
         List<String> lines = new ArrayList<>();
         curSeed = seed;
         curSeedStartNs = System.nanoTime();
         boolean hit = criteria.test(seed, ctx, lines, fast, null, true);
         curSeedStartNs = 0;
         if (!hit) {
            continue;
         }
         hitSeeds.add(seed);
         BlockPos claimed = parsePos(lines, row.label);
         if (claimed == null) {
            out.add(new Checked(false, false, 0, 0, 0, 0, ""));
            continue;
         }
         // THE WIDENED TARGET, not the catalog row. stage0 enumerates candidates out to the row's
         // OWN radius, so re-deriving through the untouched row missed any find further out than
         // its default 500 — which read as "reported but not re-derivable" and looked like the mod
         // inventing a structure. It was this file searching a smaller circle than the search did.
         out.add(recheck(ctx, target, seed, claimed, token));
      }
      return out;
   }

   /**
    * Re-derive the structure at the coordinate the player was handed, with no help from the funnel's
    * own answer, and report whether the feature is really there and where.
    */
   private static Checked recheck(WorldgenContext ctx, StructureTarget row, long seed,
                                  BlockPos claimed, String token) {
      Dim dim = row.dim;
      RandomState rs = ctx.randomState(dim, seed);
      ChunkGeneratorStructureState st = ctx.structureState(dim, seed, rs);
      // EVERY candidate this row has, re-derived from scratch and matched on the POSITION that was
      // printed. Going back through stage0 rather than trusting the funnel's own candidate is the
      // whole point of a re-check: a structure the funnel invented would have nothing to match.
      Holder<Structure> winner = null;
      ChunkPos chunk = null;
      for (SeedCriteria.Cand cand : row.stage0(seed, false)) {
         @SuppressWarnings("unchecked")
         Holder<Structure>[] out = new Holder[1];
         BlockPos pos = row.confirm(seed, ctx, rs, st, cand, out);
         if (pos != null && pos.getX() == claimed.getX() && pos.getZ() == claimed.getZ()) {
            winner = out[0];
            chunk = cand.chunk();
            break;
         }
      }
      if (winner == null) {
         return new Checked(false, false, 0, 0, 0, 0, "");
      }
      RandomState full = ctx.fullRandomState(dim, seed);
      StructureStart start = assemble(ctx, seed, chunk, winner, full, dim);
      if (start == null || !start.isValid()) {
         return new Checked(false, false, 0, 0, 0, 0, "");
      }
      BoundingBox best = null;
      String bestId = "";
      long bestGap = Long.MAX_VALUE;
      for (VillageLayout.PieceAt piece : VillageLayout.piecesAt(start.getPieces())) {
         if (!VillageLayout.tokenMatches(piece.id().getPath(), token)) {
            continue;
         }
         BoundingBox b = piece.box();
         long gap = Math.round(Math.hypot((b.minX() + b.maxX()) / 2.0 - claimed.getX(),
               (b.minZ() + b.maxZ()) / 2.0 - claimed.getZ()));
         if (gap < bestGap) {
            bestGap = gap;
            best = b;
            bestId = piece.id().getPath();
         }
      }
      BoundingBox whole = start.getBoundingBox();
      return new Checked(true, best != null, best == null ? 0 : bestGap, start.getPieces().size(),
            whole.maxX() - whole.minX(), whole.maxZ() - whole.minZ(), bestId);
   }

   /** The coordinate the match line printed, read back the way a player reads it. */
   private static BlockPos parsePos(List<String> lines, String label) {
      for (String line : lines) {
         if (!line.startsWith(label + " ")) {
            continue;
         }
         java.util.regex.Matcher m =
               java.util.regex.Pattern.compile("(-?\\d+), (-?\\d+)").matcher(line);
         if (m.find()) {
            return new BlockPos(Integer.parseInt(m.group(1)), 64, Integer.parseInt(m.group(2)));
         }
      }
      return null;
   }

   /** The shipped assembly, kept as a StructureStart so the piece boxes can be read. */
   private static StructureStart assemble(WorldgenContext ctx, long seed, ChunkPos chunk,
                                          Holder<Structure> winner, RandomState full, Dim dim) {
      try {
         WorldgenContext.Scaffold scaffold = ctx.scaffold(dim);
         return winner.value().generate(winner,
               dim == Dim.NETHER ? net.minecraft.world.level.Level.NETHER
                     : net.minecraft.world.level.Level.OVERWORLD,
               (RegistryAccess) ctx.source, scaffold.generator(), ctx.biomeSource(dim), full,
               VillageLayout.templates(), seed, chunk, 0, scaffold.height(), h -> true);
      } catch (Throwable t) {
         return null;
      }
   }

   private FeatureDiagnostic() {
   }
}
