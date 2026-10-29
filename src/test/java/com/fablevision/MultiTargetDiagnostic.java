package com.fablevision;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fablevision.client.seedfinder.SeedCatalog;
import com.fablevision.client.seedfinder.SeedCriteria;
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
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * When several rows are picked at once, does the seed really have ALL of them?
 *
 * The suspicion behind this is reasonable and worth settling with evidence rather than by reading
 * the funnel and pronouncing it correct. {@link SeedCriteria#test} ANDs its targets by returning
 * false the moment one fails, so a bug there would not look like a wrong answer — it would look like
 * a right answer with one item quietly unchecked, which is invisible from the outside.
 *
 * So this does not read the funnel at all. It takes the seeds the finder returned, takes the
 * coordinates the finder PRINTED for each row, and asks an independent question at that spot: is
 * there really one of these here, and does it really contain what the row demands? The check runs
 * through {@link VillageLayout#pieceTemplates}, which builds the structure and reads its actual
 * piece list — it never calls {@code confirm}, the placement math, or any of the caching the funnel
 * relies on. A row the funnel skipped would come back empty here.
 *
 * FIND N WORLDS (1.46.0). A second section runs searches for {@link #WORLDS} worlds through the engine's own
 * per-seed path ({@code WorldsRun}: {@code SeedFinder.consider} on one thread per core less one, the way the
 * workers run it) and holds every seed it returns to the same independent check — every row, at every
 * coordinate it printed, in the lines AND in the structured finds the list of worlds shows — and the N seeds
 * must all be different.
 *
 * {@code gradlew multiDiag --args="<seeds to find per case> [fast|exact|both] [rows|worlds|all]"}
 */
public final class MultiTargetDiagnostic {

   /** One row of a test case, with whatever the player would have set on it. */
   private record RowSpec(String label, int count, boolean inside) {
      static RowSpec of(String label) {
         return new RowSpec(label, 1, false);
      }

      static RowSpec times(String label, int n) {
         return new RowSpec(label, n, false);
      }

      static RowSpec inside(String label) {
         return new RowSpec(label, 1, true);
      }
   }

   /** One test: a name and the picker rows a player would have clicked. */
   private record Case(String name, List<RowSpec> rows) {}

   private static final List<Case> CASES = List.of(
         new Case("3 plain structures",
               List.of(RowSpec.of("Surface Village"), RowSpec.of("Ruined Portal"),
                     RowSpec.of("Shipwreck"))),
         new Case("4 rows, two of them content rows",
               List.of(RowSpec.of("Village with Armorer"), RowSpec.of("Igloo with Basement"),
                     RowSpec.of("Ruined Portal"), RowSpec.of("Desert Pyramid"))),
         new Case("5 plain structures",
               List.of(RowSpec.of("Surface Village"), RowSpec.of("Pillager Outpost"),
                     RowSpec.of("Trial Chambers"), RowSpec.of("Ocean Ruins"),
                     RowSpec.of("Ruined Portal"))),
         new Case("3 rows across two dimensions",
               List.of(RowSpec.of("Surface Village"), RowSpec.of("Nether Fortress"),
                     RowSpec.of("Bastion Remnant"))),
         new Case("content row + biome-ish spread",
               List.of(RowSpec.of("Village with Weaponsmith"), RowSpec.of("Woodland Mansion"),
                     RowSpec.of("Trail Ruins"))),
         // A COUNTED ROW. Every one of the three has to be real and they have to be three DIFFERENT
         // shipwrecks — the failure this is looking for is the same find reported three times.
         new Case("3 of one thing, plus two others",
               List.of(RowSpec.times("Shipwreck", 3), RowSpec.of("Surface Village"),
                     RowSpec.of("Ruined Portal"))),
         // SPAWN INSIDE, re-checked the only way that means anything: rebuild the structure and ask
         // whether the spawn point the finder printed is really within its footprint.
         new Case("spawn inside one, plus another",
               List.of(RowSpec.inside("Surface Village"), RowSpec.of("Ruined Portal"))));

   /** The coordinate pair on a match line, matched by shape — the same rule fastDiag now uses. */
   private static final Pattern COORDS = Pattern.compile("(-?\\d+), (-?\\d+)");

   public static void main(String[] args) throws InterruptedException {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      if (!VillageLayout.available()) {
         System.out.println("FAILED: no templates — every check below would be vacuous.");
         return;
      }

      if (args.length >= 4 && args[0].equals("seed")) {
         // REPLAY ONE WORLD: --args="seed <seed> <row label, underscores> <radius> [fast|exact]". The same
         // checks as the N-world section, on a seed a run reported.
         System.exit(replay(reg, ctx, Long.parseLong(args[1]), args[2].replace('_', ' '), Integer.parseInt(args[3]),
               args.length > 4 && args[4].equals("fast")) ? 0 : 1);
      }
      int want = args.length > 0 ? Integer.parseInt(args[0]) : 5;
      String modes = args.length > 1 ? args[1] : "both";
      String sections = args.length > 2 ? args[2] : "all";
      // 1.47.0, the Bots setting: "threads=1,auto,max" runs the N-world section on each of those worker counts.
      if (args.length > 3 && args[3].startsWith("threads=")) {
         THREADS.clear();
         for (String t : args[3].substring("threads=".length()).split(",")) {
            THREADS.add(t.equals("auto") ? com.fablevision.client.seedfinder.SeedFinder.autoWorkers()
                  : t.equals("max") ? com.fablevision.client.seedfinder.SeedFinder.maxWorkers() : Integer.parseInt(t));
         }
      }

      List<SeedCriteria.StructureTarget> catalog = SeedCatalog.structures(ctx);
      int totalSeeds = 0;
      int totalRowChecks = 0;
      int totalFalse = 0;
      int totalUnverifiable = 0;

      for (Case c : sections.equals("worlds") ? List.<Case>of() : CASES) {
         for (boolean fast : modes.equals("both") ? new boolean[]{false, true}
               : new boolean[]{"fast".equals(modes)}) {
            System.out.println();
            System.out.println("========== " + c.name() + "  (" + (fast ? "Fast" : "Exact") + ") ==========");
            List<SeedCriteria.StructureTarget> rows = new ArrayList<>();
            SeedCriteria criteria = new SeedCriteria();
            criteria.composed = true;
            boolean missing = false;
            for (RowSpec spec : c.rows()) {
               SeedCriteria.StructureTarget t = byLabel(catalog, spec.label());
               if (t == null) {
                  System.out.println("  SKIPPED: this world has no row called \"" + spec.label() + "\"");
                  missing = true;
                  break;
               }
               // A generous reach, because the point is to test whether ALL of them are satisfied,
               // not to find a rare seed. A tight radius would just make the search long.
               SeedCriteria.StructureTarget sized = t.withRadius(800).withCount(spec.count());
               if (spec.inside()) {
                  // Tighter, because a structure you SPAWN IN is at spawn by definition and 800
                  // blocks of candidates would be 800 blocks of wasted assemblies.
                  sized = sized.withRadius(128).insideSpawn();
               }
               rows.add(sized);
               criteria.structures.add(sized);
            }
            // Fast mode measures from 0,0, so "do I spawn inside it" has no meaning there — the
            // shipped code forces Exact for exactly this reason and the diagnostic must not go
            // round the back of it and then report the result as a false match.
            if (fast && criteria.requiresExact()) {
               System.out.println("  SKIPPED in Fast: this case asks where the player spawns, "
                     + "which Fast mode cannot answer — the app forces Exact for it.");
               continue;
            }
            if (missing) {
               continue;
            }
            System.out.println("  searching: " + criteria.summary());

            int found = 0;
            long seed = 1;
            long scanned = 0;
            long started = System.currentTimeMillis();
            while (found < want && scanned < 400_000L && System.currentTimeMillis() - started < 240_000L) {
               List<String> lines = new ArrayList<>();
               scanned++;
               if (!criteria.test(seed++, ctx, lines, fast)) {
                  continue;
               }
               found++;
               totalSeeds++;
               long thisSeed = seed - 1;
               System.out.println();
               System.out.println("  SEED " + thisSeed + "   (" + scanned + " scanned so far)");
               BlockPos spawn = spawnOf(lines);
               for (SeedCriteria.StructureTarget row : rows) {
                  // EVERY line the row produced, not just the first. A counted row reports one per
                  // find, and checking only the first would let "3 shipwrecks" pass on the strength
                  // of one real shipwreck and two coordinates nobody looked at.
                  List<String> mine = linesFor(lines, row.label);
                  if (mine.size() < row.count) {
                     totalRowChecks++;
                     totalFalse++;
                     System.out.println("     " + pad(row.label, 34) + "reported " + mine.size()
                           + " of " + row.count + " <-- FALSE MATCH");
                     continue;
                  }
                  java.util.Set<String> seenAt = new java.util.HashSet<>();
                  for (String line : mine) {
                     totalRowChecks++;
                     Matcher m = COORDS.matcher(line);
                     if (!m.find()) {
                        totalUnverifiable++;
                        System.out.println("     " + pad(row.label, 34) + "line has no coordinates: " + line);
                        continue;
                     }
                     int x = Integer.parseInt(m.group(1));
                     int z = Integer.parseInt(m.group(2));
                     String verdict;
                     if (!seenAt.add(x + "," + z)) {
                        // The same find reported twice is the specific way a count can lie.
                        verdict = "FALSE MATCH — this is the SAME one already reported above";
                     } else {
                        verdict = verify(reg, ctx, thisSeed, row, x, z, spawn);
                     }
                     if (verdict.startsWith("FALSE")) {
                        totalFalse++;
                     } else if (verdict.startsWith("?")) {
                        totalUnverifiable++;
                     }
                     System.out.println("     " + pad(row.label, 34) + pad(x + ", " + z, 16) + verdict);
                  }
               }
            }
            if (found < want) {
               System.out.println("  (only " + found + " of " + want + " seeds found in "
                     + scanned + " scanned — the bundle is rare, not broken)");
            }
         }
      }

      int[] worlds = sections.equals("rows") ? new int[4]
            : worldsSection(reg, ctx, catalog, modes);
      totalSeeds += worlds[0];
      totalRowChecks += worlds[1];
      totalFalse += worlds[2];
      totalUnverifiable += worlds[3];

      System.out.println();
      System.out.println("========== TOTAL ==========");
      System.out.println("  seeds returned by the finder : " + totalSeeds);
      System.out.println("  individual row claims checked: " + totalRowChecks);
      System.out.println("  FALSE MATCHES                : " + totalFalse);
      System.out.println("  could not verify either way  : " + totalUnverifiable);
      if (!sections.equals("rows")) {
         System.out.println("  N-world searches             : " + worldRuns + " run, " + worldProblems
               + " that did not return " + WORLDS + " different seeds");
      }
      System.out.println("  A false match is a row the finder reported and that is NOT really there.");
      System.out.println("===========================");
      System.exit(totalFalse == 0 && worldProblems == 0 ? 0 : 1);
   }

   // ── Find N worlds (1.46.0) ───────────────────────────────────────────────────────────────────────

   /** How many worlds each search in the N-world section asks for. */
   private static final int WORLDS = 3;
   /** The worker counts the N-world section runs on (auto unless "threads=" says otherwise). */
   private static final List<Integer> THREADS = new ArrayList<>(List.of(
         com.fablevision.client.seedfinder.SeedFinder.autoWorkers()));

   /** A content row on its own, a two-row combo, and a content row with a plain one. */
   private static final List<Case> WORLD_CASES = List.of(
         new Case("content row", List.of(RowSpec.of("Village with Weaponsmith"))),
         new Case("two-row combo", List.of(RowSpec.of("Surface Village"), RowSpec.of("Shipwreck"))),
         new Case("content row + plain row", List.of(RowSpec.of("Village with Armorer"), RowSpec.of("Ruined Portal"))));

   private static int worldRuns;
   private static int worldProblems;

   /**
    * Each case searched for {@link #WORLDS} worlds in each mode, and every seed returned checked like the
    * rows section checks its seeds — plus each structured find, which is what the list of worlds prints.
    * Returns {seeds, claims checked, false matches, unverifiable}.
    */
   private static int[] worldsSection(RegistryAccess.Frozen reg, WorldgenContext ctx,
                                      List<SeedCriteria.StructureTarget> catalog, String modes) throws InterruptedException {
      int[] totals = new int[4];
      for (int threads : THREADS) {
         int caseNo = 0;
         for (Case c : WORLD_CASES) {
            caseNo++;
            for (boolean fast : modes.equals("both") ? new boolean[]{false, true}
                  : new boolean[]{"fast".equals(modes)}) {
               System.out.println();
               System.out.println("========== " + WORLDS + " WORLDS: " + c.name() + "  (" + (fast ? "Fast" : "Exact") + ", "
                     + threads + " worker" + (threads == 1 ? "" : "s") + ") ==========");
               List<SeedCriteria.StructureTarget> rows = new ArrayList<>();
               SeedCriteria criteria = new SeedCriteria();
               criteria.composed = true;
               for (RowSpec spec : c.rows()) {
                  SeedCriteria.StructureTarget t = byLabel(catalog, spec.label());
                  if (t == null) {
                     System.out.println("  FAILED: this world has no row called \"" + spec.label() + "\"");
                     worldProblems++;
                     continue;
                  }
                  SeedCriteria.StructureTarget sized = t.withRadius(400);
                  rows.add(sized);
                  criteria.structures.add(sized);
               }
               if (rows.size() != c.rows().size()) {
                  continue;
               }
               System.out.println("  searching: " + criteria.summary());
               long stream = 0x46_0000L + caseNo * 2L + (fast ? 1 : 0);
               com.fablevision.client.seedfinder.WorldsRun.Run run = com.fablevision.client.seedfinder.WorldsRun.run(
                     criteria, reg, ctx, WORLDS, fast, stream, 10 * 60_000L, threads);
               worldRuns++;
               java.util.Set<Long> distinct = new java.util.HashSet<>();
               run.results().forEach(r -> distinct.add(r.seed()));
               String verdict = run.timedOut() ? "TIMED OUT with " + run.results().size()
                     : run.results().size() != WORLDS ? "returned " + run.results().size()
                     : distinct.size() != WORLDS ? "only " + distinct.size() + " DIFFERENT seeds"
                     : "ok, " + WORLDS + " different seeds";
               System.out.println("  " + verdict + " — " + run.checked() + " seeds checked in " + run.ms() / 1000
                     + " s, stream " + stream);
               if (!verdict.startsWith("ok")) {
                  worldProblems++;
               }
               for (com.fablevision.client.seedfinder.SeedFinder.Result r : run.results()) {
                  totals[0]++;
                  System.out.println();
                  BlockPos spawn = spawnOf(r.matches());
                  // THE SPAWN, RE-DERIVED with the game's own spawn search rather than read off the finder's
                  // line, and every find held to the distance asked for from it — the other half of the claim.
                  BlockPos realSpawn = ctx.spawnPoint(ctx.randomState(Dim.OVERWORLD, r.seed()));
                  boolean spawnAgrees = spawn != null && spawn.getX() == realSpawn.getX() && spawn.getZ() == realSpawn.getZ();
                  System.out.println("  SEED " + r.seed() + "   spawn " + realSpawn.getX() + ", " + realSpawn.getZ()
                        + (spawnAgrees ? " (the printed spawn agrees)" : " <-- the printed spawn says "
                        + (spawn == null ? "nothing" : spawn.getX() + ", " + spawn.getZ())));
                  totals[1]++;
                  if (!spawnAgrees) {
                     totals[2]++;
                  }
                  for (SeedCriteria.Find f : r.finds()) {
                     if (f.dim() != Dim.OVERWORLD) {
                        continue;
                     }
                     SeedCriteria.StructureTarget row = null;
                     for (SeedCriteria.StructureTarget t : rows) {
                        if (t.label.equals(f.label())) {
                           row = t;
                        }
                     }
                     if (row == null) {
                        continue;
                     }
                     totals[1]++;
                     long d = Math.round(Math.hypot(f.x() - realSpawn.getX(), f.z() - realSpawn.getZ()));
                     boolean within = d <= row.radius + 1;
                     if (!within) {
                        totals[2]++;
                     }
                     System.out.println("     dist  " + pad(row.label, 34) + pad(d + " of " + row.radius, 16)
                           + (within ? "OK — within the distance asked, from the real spawn"
                                 : "FALSE MATCH — further from the real spawn than asked"));
                  }
                  for (SeedCriteria.StructureTarget row : rows) {
                     List<String> mine = linesFor(r.matches(), row.label);
                     if (mine.isEmpty()) {
                        totals[1]++;
                        totals[2]++;
                        System.out.println("     " + pad(row.label, 34) + "no line for this row <-- FALSE MATCH");
                     }
                     for (String line : mine) {
                        totals[1]++;
                        Matcher m = COORDS.matcher(line);
                        if (!m.find()) {
                           totals[3]++;
                           System.out.println("     " + pad(row.label, 34) + "line has no coordinates: " + line);
                           continue;
                        }
                        int x = Integer.parseInt(m.group(1));
                        int z = Integer.parseInt(m.group(2));
                        String v = verify(reg, ctx, r.seed(), row, x, z, spawn);
                        count(totals, v);
                        System.out.println("     line  " + pad(row.label, 34) + pad(x + ", " + z, 16) + v);
                     }
                     // THE STRUCTURED FINDS, which are what the list of worlds and the short join message print.
                     List<SeedCriteria.Find> finds = new ArrayList<>();
                     for (SeedCriteria.Find f : r.finds()) {
                        if (f.label().equals(row.label)) {
                           finds.add(f);
                        }
                     }
                     if (finds.isEmpty()) {
                        totals[1]++;
                        totals[2]++;
                        System.out.println("     find  " + pad(row.label, 34) + "no find for this row <-- FALSE MATCH");
                     }
                     for (SeedCriteria.Find f : finds) {
                        totals[1]++;
                        String v = verify(reg, ctx, r.seed(), row, f.x(), f.z(), spawn);
                        count(totals, v);
                        System.out.println("     find  " + pad(row.label, 34) + pad(f.x() + ", " + f.z(), 16) + v);
                     }
                  }
               }
            }
         }
      }
      return totals;
   }

   /** One seed through the finder's describe pass, then every find re-checked: built there, and within reach of the real spawn. */
   private static boolean replay(RegistryAccess.Frozen reg, WorldgenContext ctx, long seed, String label, int radius, boolean fast) {
      SeedCriteria.StructureTarget t = byLabel(SeedCatalog.structures(ctx), label);
      if (t == null) {
         System.out.println("FAILED: no row called \"" + label + "\"");
         return false;
      }
      SeedCriteria.StructureTarget row = t.withRadius(radius);
      SeedCriteria criteria = new SeedCriteria();
      criteria.structures.add(row);
      List<String> lines = new ArrayList<>();
      List<SeedCriteria.Find> finds = new ArrayList<>();
      boolean hit = criteria.test(seed, ctx, lines, fast, null, true, finds);
      BlockPos realSpawn = ctx.spawnPoint(ctx.randomState(Dim.OVERWORLD, seed));
      System.out.println("SEED " + seed + " (" + (fast ? "Fast" : "Exact") + ", " + label + " within " + radius + "): finder says "
            + (hit ? "MATCH" : "no match") + "; real spawn " + realSpawn.getX() + ", " + realSpawn.getZ());
      lines.forEach(l -> System.out.println("   | " + l));
      boolean ok = hit;
      for (SeedCriteria.Find f : finds) {
         long d = Math.round(Math.hypot(f.x() - realSpawn.getX(), f.z() - realSpawn.getZ()));
         String v = verify(reg, ctx, seed, row, f.x(), f.z(), realSpawn);
         boolean within = d <= row.radius + 1;
         ok &= within && v.startsWith("OK");
         System.out.println("   " + f.label() + " at " + f.x() + ", " + f.z() + ": " + v + "; " + d + " blocks from the real spawn"
               + (within ? " (within " + row.radius + ")" : " <-- FURTHER THAN " + row.radius));
      }
      System.out.println(ok ? "OK" : "NOT CONFIRMED");
      return ok;
   }

   private static void count(int[] totals, String verdict) {
      if (verdict.startsWith("FALSE")) {
         totals[2]++;
      } else if (verdict.startsWith("?")) {
         totals[3]++;
      }
   }

   /**
    * Is one of {@code row}'s structures really at this coordinate, with whatever the row demands?
    *
    * INDEPENDENT ON PURPOSE. It does not ask the funnel; it builds the structure at that chunk and
    * reads the piece list. Every structure id the row accepts is tried, because a row can span
    * several (a village is five, a ruined portal six) and the funnel picked one of them.
    *
    * EXISTENCE IS COUNTED, NOT NAMED. The first version of this asked
    * {@link VillageLayout#pieceTemplates} whether anything was there and treated an empty list as
    * nothing — which condemned the nether fortress and the desert pyramid, both of which are built
    * from code and so have no template names at all. Five false matches, all of them this tool's
    * fault. Existence now goes through {@link VillageLayout#pieceCount}, and the template list is
    * only consulted for what a row demands INSIDE the structure.
    */
   private static String verify(RegistryAccess.Frozen reg, WorldgenContext ctx, long seed,
                                SeedCriteria.StructureTarget row, int x, int z, BlockPos spawn) {
      ChunkPos chunk = new ChunkPos(x >> 4, z >> 4);
      RandomState full = ctx.fullRandomState(row.dim, seed);
      List<String> tried = new ArrayList<>();
      for (Identifier id : row.wanted) {
         Holder<Structure> holder = reg.lookupOrThrow(Registries.STRUCTURE)
               .get(ResourceKey.create(Registries.STRUCTURE, id)).map(h -> (Holder<Structure>) h)
               .orElse(null);
         if (holder == null) {
            continue;
         }
         // VANILLA'S OWN BIOME TEST, not a loose one, and the difference is not academic. A village
         // row accepts five structure ids, and under `h -> true` ALL FIVE assemble anywhere — so
         // taking the first that builds picks a variant essentially at random. That is survivable
         // when the question is "is something here" and fatal when it is "is spawn inside THIS
         // box": at seed 244 the loose test built village_taiga with box x 17..97, and the village
         // really there is village_plains with box x -23..160. One contains spawn and one does not,
         // and the finder was right. Asking ownBiomes leaves exactly the variant that generates.
         VillageLayout.Built b = VillageLayout.builtAt(reg, ctx, seed, chunk, holder, full,
               VillageLayout.ownBiomes(holder), row.dim);
         if (b == null || b.pieces() == 0) {
            continue;
         }
         int count = b.pieces();
         tried.add(id.getPath() + "(" + count + " pieces)");
         if (row.spawnInside) {
            // THE FOOTPRINT, re-derived. The finder's claim is not "this is near spawn" but "spawn
            // is inside this", so re-checking a distance would not test the claim at all.
            if (spawn == null) {
               return "? — no spawn line to check the footprint against";
            }
            var box = b.box();
            boolean in = spawn.getX() >= box.minX() && spawn.getX() <= box.maxX()
                  && spawn.getZ() >= box.minZ() && spawn.getZ() <= box.maxZ();
            if (!in) {
               return "FALSE MATCH — spawn " + spawn.getX() + "," + spawn.getZ() + " is NOT inside "
                     + id.getPath() + " box x " + box.minX() + ".." + box.maxX()
                     + " z " + box.minZ() + ".." + box.maxZ();
            }
            if (row.building == null) {
               return "OK — spawn IS inside " + id.getPath() + " (box x " + box.minX() + ".."
                     + box.maxX() + " z " + box.minZ() + ".." + box.maxZ() + ")";
            }
         }
         if (row.building == null) {
            return "OK — " + id.getPath() + ", " + count + " pieces";
         }
         List<Identifier> pieces = b.templates();
         if (pieces.isEmpty()) {
            return "? — " + id.getPath() + " is really here (" + count + " pieces) but it is built "
                  + "from code, so no piece can be named and \"" + row.building
                  + "\" cannot be checked either way";
         }
         boolean has = VillageLayout.hasBuilding(pieces, row.building);
         if (has != row.without) {
            return "OK — " + id.getPath() + " " + (row.without ? "does NOT contain " : "contains ")
                  + row.building;
         }
      }
      if (tried.isEmpty()) {
         return "FALSE MATCH — nothing of this kind assembles at that spot";
      }
      return "FALSE MATCH — built " + String.join(", ", tried) + " but the piece requirement \""
            + row.building + "\" was " + (row.without ? "present" : "absent");
   }

   /** Every match line this row produced — one per find, so a counted row yields several. */
   private static List<String> linesFor(List<String> lines, String label) {
      List<String> out = new ArrayList<>();
      for (String l : lines) {
         if (l.startsWith(label + " ")) {
            out.add(l);
         }
      }
      return out;
   }

   /** The spawn point the finder printed, which a spawn-inside claim has to be checked against. */
   private static BlockPos spawnOf(List<String> lines) {
      for (String l : lines) {
         if (l.startsWith("Your Overworld spawn @")) {
            Matcher m = COORDS.matcher(l);
            if (m.find()) {
               return new BlockPos(Integer.parseInt(m.group(1)), 64, Integer.parseInt(m.group(2)));
            }
         }
      }
      return null;
   }

   private static String lineFor(List<String> lines, String label) {
      for (String l : lines) {
         if (l.startsWith(label + " ")) {
            return l;
         }
      }
      return null;
   }

   private static SeedCriteria.StructureTarget byLabel(List<SeedCriteria.StructureTarget> catalog,
                                                       String label) {
      for (SeedCriteria.StructureTarget t : catalog) {
         if (t.label.equals(label) && t.special == SeedCriteria.Special.NORMAL) {
            return t;
         }
      }
      return null;
   }

   private static String pad(String s, int width) {
      StringBuilder sb = new StringBuilder(s);
      while (sb.length() < width) {
         sb.append(' ');
      }
      return sb.toString();
   }

   private MultiTargetDiagnostic() {
   }
}
