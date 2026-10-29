package com.fablevision.client.seedfinder;

import java.util.List;
import com.fablevision.VillageDiagnostic;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;

/**
 * THE PLAYER'S MOVES, PLAYED AGAINST THE REAL MAP LOGIC, WITH WHAT WOULD BE ON SCREEN MEASURED.
 *
 * Written in 1.44.5 after two rounds of "the black map is fixed" were verified with numbers about
 * builds (how long one takes, how fast one stops) while the bug was in what the SCREEN did with them.
 * This drives {@link SeedMapModel} — the exact object the screen uses — frame by frame in real time,
 * with real builds on the real worker: open the map, drag far, zoom all the way out, drag far again,
 * let go. After every frame it measures how much of the map has a picture under it; at the end it asks
 * an independent full build, cell by cell, whether the game has structures where the map shows none.
 *
 * FAILS when black is still on screen {@link #SETTLE_MS} after any move ends ("stays black"), when a
 * view never finishes its structures, or when any cell has real structures and the map shows none.
 * Black DURING a drag is reported but not failed: a drag at full zoom-out covers tens of thousands of
 * blocks a second, faster than any picture of new ground can be made.
 *
 * {@code gradlew mapReplay --args="[seed]"}
 */
public final class MapReplayDiagnostic {

   private static final int MAP_PX = 300;
   private static final long FRAME_MS = 16;
   /** How long after a move ends the map must be fully covered again. */
   private static final long SETTLE_MS = 1000;

   private static SeedMapModel model;
   private static long frames;
   private static long blackSince = -1;
   private static long longestBlack;
   private static String longestBlackWhere = "";
   private static String phase = "";
   /** The most map check threads seen actually running at once, sampled every few frames. */
   private static int peakRunning;
   private static String peakWho = "";
   /** How long the last settle took until the view was complete (structures too), or -1 if never. */
   private static long upToDateMs = -1;

   public static void main(String[] args) throws Exception {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      VillageLayout.templates();
      long seed = args.length > 0 ? Long.parseLong(args[0]) : -4172144997902289642L;

      System.out.println("========== THE MAP, PLAYED LIKE A PLAYER ==========");
      System.out.println("  seed " + seed + ", map " + MAP_PX + " px, a frame every " + FRAME_MS + " ms, a tick every 50 ms");
      model = new SeedMapModel(reg, List.of(), null, null, null);
      model.setSeed(seed);

      phase = "open";
      model.requestView();   // the screen's init()
      long openMs = settle(20_000);
      report("open the map", openMs);
      long worstSettle = openMs;

      phase = "drag far";
      for (int i = 0; i < 60; i++) {
         model.drag(-MAP_PX * 3.0 / 60, 0, MAP_PX);
         frame();
      }
      model.release();
      long dragMs = settle(20_000);
      report("drag 3 map-widths east, let go", dragMs);
      worstSettle = worse(worstSettle, dragMs);

      phase = "zoom out";
      for (int i = 0; i < 14; i++) {
         model.zoomBy(1.25, MAP_PX / 2.0, MAP_PX / 2.0, MAP_PX);
         for (int f = 0; f < 5; f++) {
            frame();
         }
      }
      long zoomMs = settle(30_000);
      report("scroll all the way out (" + model.cam()[2] * 2 + " blocks across)", zoomMs);
      worstSettle = worse(worstSettle, zoomMs);
      boolean allFinished = upToDateMs >= 0;

      phase = "drag far, zoomed out";
      for (int i = 0; i < 60; i++) {
         model.drag(-MAP_PX * 2.0 / 60, -MAP_PX * 1.0 / 60, MAP_PX);
         frame();
      }
      model.release();
      long lastMs = settle(30_000);
      report("drag 2 map-widths at full zoom-out, let go", lastMs);
      worstSettle = worse(worstSettle, lastMs);
      allFinished &= upToDateMs >= 0;

      // STRUCTURES: every cell of the final view, against an independent build of just that cell.
      int[] c = model.cam();
      SeedMapData.View last = model.view();
      int[] perCell = new int[SeedMapData.CELLS * SeedMapData.CELLS];
      for (SeedMapData.Nearby n : last.nearby()) {
         perCell[last.cellOf(n.x(), n.z())]++;
      }
      System.out.println("  final view: centre " + last.centreX() + ", " + last.centreZ() + " reach " + last.reach()
            + ", camera " + c[0] + ", " + c[1] + " reach " + c[2] + "; " + last.nearby().size()
            + " structures, per cell " + java.util.Arrays.toString(perCell)
            + (last.unverifiable() > 0 ? ", " + last.unverifiable() + " UNCHECKED" : ""));
      int cells = 4;
      int cellHalf = c[2] / cells;
      int missing = 0;
      int withStructures = 0;
      BlockPos spawn = model.spawn();
      System.out.println();
      System.out.println("  structures in the final view, " + cells + "x" + cells + " cells of "
            + cellHalf * 2 + " blocks (map shows / the game has):");
      for (int row = 0; row < cells; row++) {
         StringBuilder line = new StringBuilder("    ");
         for (int col = 0; col < cells; col++) {
            int cx = c[0] - c[2] + cellHalf + col * cellHalf * 2;
            int cz = c[1] - c[2] + cellHalf + row * cellHalf * 2;
            SeedMapData.View ref = SeedMapData.buildAt(ctx, seed, SeedMapData.MapDim.OVERWORLD, spawn, cx, cz, cellHalf);
            long real = ref.nearby().stream().filter(n -> Math.abs(n.x() - cx) <= cellHalf
                  && Math.abs(n.z() - cz) <= cellHalf).count();
            int shown = model.knownIn(cx - cellHalf, cz - cellHalf, cx + cellHalf, cz + cellHalf).size();
            if (real > 0) {
               withStructures++;
            }
            boolean miss = real > 0 && shown == 0;
            if (miss) {
               missing++;
            }
            line.append(String.format("%5d/%-4d%s", shown, real, miss ? "!" : " "));
         }
         System.out.println(line);
      }

      System.out.println();
      System.out.println("  longest black while moving   : " + longestBlack + " ms (" + longestBlackWhere + ")");
      System.out.println("  longest black after a move ended: " + (worstSettle < 0 ? "NEVER COVERED" : worstSettle + " ms"));
      System.out.println("  cells with structures, none shown: " + missing + " of " + withStructures);
      int cores = Runtime.getRuntime().availableProcessors();
      int cap = SeedMapData.cpuPermits();
      System.out.println("  map threads running at once, most seen: " + peakRunning + " (cap " + cap + ", "
            + cores + " cores — one kept free)");
      System.out.println("    at that moment: " + peakWho);

      // THE IN-WORLD MAP: the same seed opened the way the N key opens it, then every map thread that
      // did work is asked its priority.
      SeedMapModel world = new SeedMapModel(reg, List.of(), null, spawn, () -> null);
      world.setSeed(seed);
      world.requestView();
      long until = System.nanoTime() + 20_000_000_000L;
      while ((world.loading() || world.view() == null || !world.view().structuresDone()) && System.nanoTime() < until) {
         world.absorb();
         world.tick();
         Thread.sleep(FRAME_MS);
      }
      int notLow = 0;
      int seen = 0;
      for (Thread t : Thread.getAllStackTraces().keySet()) {
         if (t.getName().startsWith("FableVision-SeedMap")) {
            seen++;
            if (t.getPriority() != Thread.MIN_PRIORITY) {
               notLow++;
               System.out.println("    in world, not lowest priority: " + t.getName() + " = " + t.getPriority());
            }
         }
      }
      System.out.println("  in-world map threads at lowest priority: " + (seen - notLow) + " of " + seen);
      int problems = 0;
      if (peakRunning > cap) {
         System.out.println("  <-- more map threads ran at once than the cap (cores - 1)");
         problems++;
      }
      if (seen == 0 || notLow > 0) {
         System.out.println("  <-- the in-world map's threads were not all at the lowest priority");
         problems++;
      }
      if (worstSettle < 0 || worstSettle > SETTLE_MS) {
         System.out.println("  <-- black stayed on screen more than " + SETTLE_MS + " ms after a move ended");
         problems++;
      }
      if (!allFinished) {
         System.out.println("  <-- the last view never finished checking its structures");
         problems++;
      }
      if (missing > 0) {
         System.out.println("  <-- the game has structures where the map shows none");
         problems++;
      }
      System.out.println(problems == 0 ? "  OK — no lasting black, covered promptly, structures everywhere they exist"
            : "  " + problems + " problem(s)");
      System.exit(problems == 0 ? 0 : 1);
   }

   /**
    * Map threads DOING MAP WORK right now, read from the threads themselves rather than from the cap's
    * own counter: RUNNABLE, with the map's work on the stack (biome sampling, a structure check or its
    * cache lookup). A thread parked for a permit is WAITING; one between two items is only in loop
    * bookkeeping and is not counted.
    */
   private static int mapThreadsRunning() {
      int n = 0;
      StringBuilder who = new StringBuilder();
      for (var e : Thread.getAllStackTraces().entrySet()) {
         Thread t = e.getKey();
         if (!t.getName().startsWith("FableVision-SeedMap") || t.getState() != Thread.State.RUNNABLE) {
            continue;
         }
         for (StackTraceElement f : e.getValue()) {
            String m = f.getClassName() + "." + f.getMethodName();
            if (m.contains("SeedMapData.verify") || m.contains("SeedMapData.oldCheck") || m.contains("getNoiseBiome")
                  || m.contains("SeedMapData$Cache.get")) {
               n++;
               // Who, and in which of the map's own methods — so a thread working outside the cap is named.
               String own = "";
               for (StackTraceElement g : e.getValue()) {
                  if (g.getClassName().contains("SeedMapData") || g.getClassName().contains("SeedMapModel")) {
                     own = g.getClassName().substring(g.getClassName().lastIndexOf(46) + 1) + "." + g.getMethodName();
                     break;
                  }
               }
               who.append(t.getName()).append(" in ").append(own).append("; ");
               break;
            }
         }
      }
      if (n > peakRunning) {
         peakWho = who.toString();
      }
      return n;
   }

   private static long worse(long a, long b) {
      return a < 0 || b < 0 ? -1 : Math.max(a, b);
   }

   /** One rendered frame: what the screen does each frame, then the black measurement. */
   private static void frame() throws InterruptedException {
      model.absorb();
      if (frames % 3 == 0) {
         model.tick();   // ticks at 20 a second against 60 frames
      }
      if (frames % 4 == 0) {
         peakRunning = Math.max(peakRunning, mapThreadsRunning());
      }
      frames++;
      long now = System.nanoTime() / 1_000_000;
      if (model.coverage() < 0.999) {
         if (blackSince < 0) {
            blackSince = now;
         }
         long len = now - blackSince;
         if (len > longestBlack) {
            longestBlack = len;
            longestBlackWhere = phase;
         }
      } else {
         blackSince = -1;
      }
      Thread.sleep(FRAME_MS);
   }

   /** Frames until the map is fully covered and up to date; ms taken, or -1 if it never got there. */
   private static long settle(long limitMs) throws InterruptedException {
      long start = System.nanoTime() / 1_000_000;
      long coveredAt = -1;
      while (System.nanoTime() / 1_000_000 - start < limitMs) {
         frame();
         boolean covered = model.coverage() >= 0.999;
         if (covered && coveredAt < 0) {
            coveredAt = System.nanoTime() / 1_000_000 - start;
         }
         if (!covered) {
            coveredAt = -1;
         }
         if (covered && !model.loading() && !model.stale() && model.absorbed()) {
            upToDateMs = System.nanoTime() / 1_000_000 - start;
            return coveredAt;
         }
      }
      upToDateMs = -1;
      return coveredAt;
   }

   private static void report(String what, long ms) {
      System.out.println(String.format("  %-50s %s, %s   coverage %3.0f%%, %d structures known", what,
            ms < 0 ? "NEVER COVERED" : "covered after " + ms + " ms",
            upToDateMs < 0 ? "STRUCTURES NEVER FINISHED" : "complete after " + upToDateMs + " ms", model.coverage() * 100,
            model.known() == null ? 0 : model.known().size()));
   }

   private MapReplayDiagnostic() {
   }
}
