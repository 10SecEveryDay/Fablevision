package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fablevision.VillageDiagnostic;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import com.fablevision.client.seedfinder.SeedCriteria.StructureTarget;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Dev-only: produces layout predictions a human can actually WALK TO. Run with
 * {@code gradlew iglooWalk}, or {@code --args="<subject> …"} for any other row in {@link #SUBJECTS}
 * (portal, wreck, ruin, outpost, trail, chamber, city, bastion). Igloo is the default
 * because it owns the only in-game-verified regression set.
 *
 * Not every subject is in the overworld any more. A Nether row is a NETHER coordinate measured
 * from spawn/8, and the teleport lines wrap
 * themselves in {@code /execute in} accordingly — the same numbers in the wrong world are a
 * different place, and walking there is exactly the kind of mistake this tool exists to prevent.
 *
 * Why this exists, and what was wrong with checking igloos in {@code villageDiag}:
 *   That diagnostic assembles at {@code ChunkPos(0,0)} for every seed. It asks a hypothetical —
 *   "IF an igloo started in the chunk at 0,0, what would it build" — which is the right question
 *   for proving the assembly code works, and the wrong question for walking anywhere. Placement
 *   almost never puts an igloo at 0,0, so the hut it describes usually does not exist.
 *
 * What this does instead:
 *   Runs the SAME two stages the shipped finder runs — {@link StructureTarget#stage0} for
 *   candidate chunks, then {@link StructureTarget#confirm} for the vanilla-exact placement and
 *   biome test — near the seed's spawn, and assembles what it finds.
 *
 * ===================================================================================
 * DO NOT USE /locate TO DECIDE WHICH STRUCTURE TO CHECK. TELEPORT TO THE COORDINATES.
 * ===================================================================================
 *   Every "failed" walk this project has recorded — igloo seeds 105 and 988, portal seed 793,
 *   and the first shipwreck attempt — was the same mistake, and it was never a wrong prediction:
 *   {@code /locate} answers "where is the NEAREST one", which is a different question from "what
 *   is at the coordinates in row 3". The tester was sent to a structure no row was about, found
 *   it lacked the feature, and recorded a fail against a prediction that had never been tested.
 *
 *   Over those same walks, every time the coordinates in a row were actually visited, the row was
 *   right — 12 for 12 on igloos and portals, and both predicted capsized wrecks on the shipwreck
 *   walk. So the instruction is now, and permanently: TELEPORT TO EACH ROW'S COORDINATES AND
 *   CHECK THAT ONE. This tool makes no claim about which structure is nearest and never will.
 *
 * The {@code /locate} line is printed only as a FALLBACK — for confirming a structure kind exists
 * in a world at all, never for choosing where to go. When it is used, it must be the TAG rather
 * than the plain id: {@code minecraft:ruined_portal} names only the standard variant out of seven
 * that share those slots, so it walks straight past desert, jungle, swamp, mountain and ocean
 * portals; {@code #minecraft:ruined_portal} finds them all. The VARIANT column exists so a row can
 * be matched to whatever the game reports.
 *
 * The STRICT column is vanilla's own biome test applied at the structure's real generation point,
 * versus the finder's cheaper single sample at the locate position. It is reported and never used
 * as a filter: it is a lead worth investigating, not established ground truth, and dropping rows
 * on an unproven test would hide exactly the igloo someone is standing in front of.
 */
public final class IglooWalkDiagnostic {

   /** How far out to look for igloos around spawn. Per-subject for the deep structures, which
    *  are far rarer than an igloo and, in the End, far away by nature. */
   private static final int SEARCH = 2000;

   private record Hit(ChunkPos chunk, BlockPos pos, long dist, boolean basement, boolean strict,
                      String variant) {}

   /** What the feature is called in the printout — set once in main, per structure. */
   private static String FEATURE = "BASEMENT";
   private static String NO_FEATURE = "no basement";
   private static String LOCATE_ID = "minecraft:igloo";
   /** Which world the rows are in, and how to arrive there. Overworld until a Nether
    *  subject sets them. */
   private static Dim DIM = Dim.OVERWORLD;
   private static int REACH = SEARCH;
   private static int TP_Y = 200;
   private static String HINT = "";

   /**
    * Every structure this tool can walk, as data rather than a chain of booleans.
    *
    * {@code plainLabel} is the catalog row for the structure with NO feature demanded — the walk
    * needs the runner-ups as much as the hits, because a table with only the winners in it cannot
    * be checked. {@code featureLabel} is the row whose token is under test; the token itself is
    * never typed here, it is read out of {@link SeedCatalog} at run time so this cannot drift
    * from what the picker ships.
    *
    * {@code key} is only a FALLBACK structure for assembly: where several variants share the
    * slots (portals, wrecks, ocean ruins) the winner that actually landed in a chunk is taken
    * from confirm(), and this is used only if that came back empty.
    *
    * {@code locateId} is for the fallback line at the bottom of a report — the TAG where one
    * exists, since a plain id names one variant out of several. Outpost and trail ruins have a
    * single registry entry each, so their plain ids are already complete.
    */
   /**
    * {@code dim} is which world the rows are in, and it decides three things at once: where
    * distances are measured from (spawn, spawn/8, or 0,0 — see {@link Placed#origin}), which noise
    * and build limits the structure is assembled against, and what the teleport line has to say.
    *
    * {@code tpY} is 200 everywhere it can be. The Nether is the exception and it is not a
    * preference: its bedrock roof is at y=127, so y=200 is outside the world and would drop you
    * onto the roof instead of into the structure. 120 puts you just under the roof, above almost
    * all Nether terrain.
    *
    * {@code hint} is for subjects where arriving in open air above the coordinates is not enough
    * to see anything — the two overworld structures on this list are underground.
    */
   private record Subject(String arg, String plainLabel, String featureLabel, String feature,
                          String noFeature, String locateId, ResourceKey<Structure> key,
                          Dim dim, int reach, int tpY, String hint) {}

   private static Subject overworld(String arg, String plainLabel, String featureLabel,
                                    String feature, String noFeature, String locateId,
                                    ResourceKey<Structure> key) {
      return new Subject(arg, plainLabel, featureLabel, feature, noFeature, locateId, key,
            Dim.OVERWORLD, SEARCH, 200, "");
   }

   private static final List<Subject> SUBJECTS = List.of(
         overworld("igloo", "Igloo", "Igloo with Basement", "BASEMENT", "no basement",
               "minecraft:igloo", BuiltinStructures.IGLOO),
         overworld("portal", "Ruined Portal", "Giant Ruined Portal", "GIANT", "ordinary",
               "#minecraft:ruined_portal", BuiltinStructures.RUINED_PORTAL_STANDARD),
         overworld("wreck", "Shipwreck", "Capsized Shipwreck", "CAPSIZED", "not capsized",
               "#minecraft:shipwreck", BuiltinStructures.SHIPWRECK),
         // Warm and cold ocean ruins are two structures in one set, so the winner per chunk
         // decides which templates assemble; cold is only the fallback for an empty winner.
         overworld("ruin", "Ocean Ruins", "Large Ocean Ruin", "LARGE", "small buildings only",
               "#minecraft:ocean_ruin", BuiltinStructures.OCEAN_RUIN_COLD),
         overworld("outpost", "Pillager Outpost", "Outpost with Cages", "CAGES", "no cages",
               "minecraft:pillager_outpost", BuiltinStructures.PILLAGER_OUTPOST),
         // Village professions. The plain row is "Surface Village" for all of them, so the table
         // shows the villages WITHOUT the building as well — which is the whole test, because a
         // village always has houses and "there are buildings here" proves nothing.
         //
         // The locate fallback has to be the TAG. Five separate village structures share these
         // slots and "minecraft:village_plains" finds only one of them, so the plain id would walk
         // straight past a desert or taiga village that a row is actually about.
         //
         // The fallback assembly key is VILLAGE_PLAINS, and it is only a fallback: confirm()
         // reports which of the five actually won each chunk, and that is what gets assembled.
         new Subject("butcher", "Surface Village", "Village with Butcher", "BUTCHER", "no butcher",
               "#minecraft:village", BuiltinStructures.VILLAGE_PLAINS, Dim.OVERWORLD, SEARCH, 200,
               "The butcher's shop is the one with a SMOKER and slabs of meat hanging on the "
               + "outside wall. Look for the smoker, not the villager - he may not have taken the "
               + "job yet, which is exactly what the row does not claim."),
         new Subject("librarian", "Surface Village", "Village with Librarian", "LIBRARY", "no library",
               "#minecraft:village", BuiltinStructures.VILLAGE_PLAINS, Dim.OVERWORLD, SEARCH, 200,
               "The library is the building with a LECTERN and bookshelves inside. Again, look for "
               + "the lectern rather than a librarian villager - the building is what was checked."),
         new Subject("cartographer", "Surface Village", "Village with Cartographer", "CARTOGRAPHER",
               "no cartographer", "#minecraft:village", BuiltinStructures.VILLAGE_PLAINS,
               Dim.OVERWORLD, SEARCH, 200,
               "The cartographer's house has a CARTOGRAPHY TABLE in it. In plains it is a small "
               + "house; in desert, taiga and snowy villages it is the wider 'cartographer_house'."),
         new Subject("mason", "Surface Village", "Village with Mason", "MASON", "no mason",
               "#minecraft:village", BuiltinStructures.VILLAGE_PLAINS, Dim.OVERWORLD, SEARCH, 200,
               "The mason's house has a STONECUTTER in it."),
         new Subject("leatherworker", "Surface Village", "Village with Leatherworker", "TANNERY",
               "no tannery", "#minecraft:village", BuiltinStructures.VILLAGE_PLAINS,
               Dim.OVERWORLD, SEARCH, 200,
               "The tannery is the leatherworker's building - CAULDRONS and leather on the walls."),
         new Subject("fletcher", "Surface Village", "Village with Fletcher", "FLETCHER", "no fletcher",
               "#minecraft:village", BuiltinStructures.VILLAGE_PLAINS, Dim.OVERWORLD, SEARCH, 200,
               "The fletcher's house has a FLETCHING TABLE in it."),
         new Subject("shepherd", "Surface Village", "Village with Shepherd", "SHEPHERD", "no shepherd",
               "#minecraft:village", BuiltinStructures.VILLAGE_PLAINS, Dim.OVERWORLD, SEARCH, 200,
               "The shepherd's house has a LOOM in it. This is one of the rarest village "
               + "buildings, so most villages in the table will not have one."),
         new Subject("fisherman", "Surface Village", "Village with Fisherman", "FISHER", "no fisher",
               "#minecraft:village", BuiltinStructures.VILLAGE_PLAINS, Dim.OVERWORLD, SEARCH, 200,
               "The fisher cottage has a BARREL in it and sits near the water's edge."),
         overworld("trail", "Trail Ruins", "Trail Ruins with Building Group", "BUILDING GROUP",
               "no group", "minecraft:trail_ruins", BuiltinStructures.TRAIL_RUINS),
         // Mansion rooms. Reach is 3000 rather than the usual 2000 because mansions are spaced 80
         // chunks apart — at 2000 blocks most seeds have none at all and the table comes back empty,
         // which reads like a broken row rather than a rare structure.
         //
         // These four are the hardest subjects on the list to check honestly, and the hint has to
         // say why: two of the rooms are SEALED, with no door and no corridor into them. Walking
         // the mansion and not finding a way in is the expected experience, not a failed
         // prediction, so the instruction is to fly it in spectator rather than to look for a door.
         new Subject("secret", "Woodland Mansion", "Mansion with Secret Room", "SECRET ROOM",
               "no secret room", "minecraft:mansion", BuiltinStructures.WOODLAND_MANSION,
               Dim.OVERWORLD, 3000, 200,
               "A small room walled in with OBSIDIAN and one DIAMOND BLOCK inside. There is no "
               + "door — use /gamemode spectator and fly through the walls, floor by floor. It is "
               + "usually on an upper floor. Do not conclude 'no' until you have flown all three."),
         new Subject("vault", "Woodland Mansion", "Mansion with Lava Vault", "LAVA VAULT",
               "no lava vault", "minecraft:mansion", BuiltinStructures.WOODLAND_MANSION,
               Dim.OVERWORLD, 3000, 200,
               "The BIG sealed room: an obsidian box with LAVA, a lot of GLASS and one diamond "
               + "block. Sealed like the small one, so fly it in spectator. This is the rarest "
               + "room in the mansion (1 in 50), so nearly every mansion in the table will be a "
               + "'no' — the few YES rows are the whole test."),
         new Subject("tnt", "Woodland Mansion", "Mansion with TNT Trap", "TNT TRAP",
               "no tnt trap", "minecraft:mansion", BuiltinStructures.WOODLAND_MANSION,
               Dim.OVERWORLD, 3000, 200,
               "The trap room: a TRAPPED CHEST wired to TNT, in a room whose cobblestone hides "
               + "silverfish. This one has a normal way in. Do NOT open the chest to confirm it — "
               + "the TNT is the point, and the chest's contents were never claimed anyway."),
         new Subject("cobweb", "Woodland Mansion", "Mansion with Spawner", "SPAWNER ROOM",
               "no spawner room", "minecraft:mansion", BuiltinStructures.WOODLAND_MANSION,
               Dim.OVERWORLD, 3000, 200,
               "A room packed with COBWEBS around a single MOB SPAWNER — the only spawner anywhere "
               + "in the mansion, so seeing any spawner at all settles it."),
         // Underground: you land in the sky at y=200 and the structure is hundreds of blocks
         // below, so these two need spectator mode to check rather than a look around.
         new Subject("chamber", "Trial Chambers", "Trial Chamber with Eruption Room",
               "ERUPTION ROOM", "no eruption room", "minecraft:trial_chambers",
               BuiltinStructures.TRIAL_CHAMBERS, Dim.OVERWORLD, 3000, 200,
               "Trial chambers sit around y=-20. Use /gamemode spectator and fly straight down "
               + "from the teleport — the eruption chamber is the round room with the sloped "
               + "floor and a breeze."),
         new Subject("city", "Ancient City", "Ancient City with Sauna", "SAUNA", "no sauna",
               "minecraft:ancient_city", BuiltinStructures.ANCIENT_CITY, Dim.OVERWORLD, 3000, 200,
               "Ancient cities sit around y=-50. Use /gamemode spectator and fly straight down — "
               + "the sauna is the small sealed room off to one side, not part of the centre."),
         // Nether: rows are NETHER coordinates, measured from spawn/8. y=120 because the bedrock
         // roof is at 127 and y=200 is outside the world here.
         // Reach 800 rather than the catalog's honest 400: this is a walk table, not a search, and
         // a table with one row in it cannot be a test. Wider means several bastions to compare.
         new Subject("bastion", "Bastion Remnant", "Treasure Bastion", "TREASURE TYPE",
               "another type", "minecraft:bastion_remnant",
               BuiltinStructures.BASTION_REMNANT, Dim.NETHER, 800, 120,
               "These are NETHER coordinates. The treasure bastion is the square one built round "
               + "a lava basin with gold blocks in the middle; the other three types are the "
               + "bridge, the hoglin stable and the stacked housing units."),
         // The "endcity" subject was here and went with the End in 1.41.4 — see SeedCriteria.Dim.
         // Worth noting what it was: this tool teleports you to the predicted spot and asks what is
         // actually there, so it is precisely the check that would have caught the phantom End City
         // before a player did. It was never run for that subject over enough seeds.
         new Subject("fortress", "Nether Fortress", "Nether Fortress", "FORTRESS", "not one",
               "minecraft:fortress", BuiltinStructures.FORTRESS, Dim.NETHER, 800, 120,
               "These are NETHER coordinates, measured from where a portal lit at your Overworld "
               + "spawn comes out (spawn divided by 8)."));

   public static void main(String[] args) {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);

      if (!VillageLayout.available()) {
         System.out.println("FAILED: no structure templates — nothing here can be trusted.");
         return;
      }

      // Which structure this run is about. Igloo stays the default because it owns the only
      // in-game-verified regression set; every other subject is the same machinery pointed at a
      // different catalog row.
      String first = args.length > 0 ? args[0] : "";
      Subject subject = SUBJECTS.stream()
            .filter(s -> s.arg().equals(first))
            .findFirst()
            .orElse(SUBJECTS.get(0));
      if (subject.arg().equals(first) && !"igloo".equals(first)) {
         args = java.util.Arrays.copyOfRange(args, 1, args.length);
      }
      String plainLabel = subject.plainLabel();
      String featureLabel = subject.featureLabel();
      FEATURE = subject.feature();
      NO_FEATURE = subject.noFeature();
      // Used ONLY on the fallback line, and the TAG where one exists: "minecraft:ruined_portal" is
      // only the standard variant, so it skips every desert, jungle, swamp, mountain and ocean
      // portal. This is never the instruction for choosing where to walk — see the class notes.
      LOCATE_ID = subject.locateId();
      DIM = subject.dim();
      REACH = subject.reach();
      TP_Y = subject.tpY();
      HINT = subject.hint();

      List<StructureTarget> catalog = SeedCatalog.structures(ctx);
      // The token under test is the one the picker ships, pulled from the catalog rather than
      // retyped, so this verifies the real predicate and not a lookalike.
      String token = catalog.stream()
            .filter(t -> featureLabel.equals(t.label))
            .map(t -> t.building)
            .findFirst()
            .orElse(null);
      if (token == null) {
         System.out.println("FAILED: the picker has no " + featureLabel + " row.");
         return;
      }
      // The PLAIN target: every structure of this kind, feature or not. Filtering here would hide
      // the runner-ups, and the runner-ups are exactly what makes a walk ambiguous.
      StructureTarget igloo = catalog.stream()
            .filter(t -> plainLabel.equals(t.label))
            .findFirst()
            .orElseThrow()
            .withRadius(REACH);
      // Fallback only: where several variants share the slots, the real winner per chunk comes
      // from confirm() below and this is used just when that came back empty.
      Holder<Structure> structure = reg.lookupOrThrow(Registries.STRUCTURE).getOrThrow(subject.key());

      if (args.length > 0 && "verify".equals(args[0])) {
         verify(ctx, igloo, structure, token);
         return;
      }
      if (args.length > 0 && "probe".equals(args[0])) {
         probe(reg, ctx);
         return;
      }
      if (args.length > 0 && "spawncheck".equals(args[0])) {
         spawnCheck(reg, ctx);
         return;
      }
      if (args.length > 1 && "show".equals(args[0])) {
         long s = Long.parseLong(args[1]);
         report(ctx, s, iglooesNearSpawn(ctx, igloo, structure, token, s));
         return;
      }

      long from = args.length > 0 ? Long.parseLong(args[0]) : 1L;
      int want = args.length > 1 ? Integer.parseInt(args[1]) : 3;
      long limit = args.length > 2 ? Long.parseLong(args[2]) : 400L;

      System.out.println("========== " + plainLabel.toUpperCase(java.util.Locale.ROOT) + " WALK TEST ==========");
      System.out.println("token under test : " + token + "   (read from the \"" + featureLabel
            + "\" catalog row, not retyped)");
      System.out.println();
      System.out.println("HOW TO RUN THIS TEST:");
      System.out.println("  Teleport to EVERY coordinate listed under a seed and check THAT");
      System.out.println("  structure against its row. The rows are the whole test.");
      System.out.println();
      System.out.println("  DO NOT run /locate to decide where to go. /locate goes to the NEAREST");
      System.out.println("  " + plainLabel.toLowerCase(java.util.Locale.ROOT)
            + ", which is usually not the one any row is about — every");
      System.out.println("  \"fail\" recorded so far (igloo, portal, shipwreck) was that mistake and");
      System.out.println("  not a wrong prediction. No ordering or nearness is claimed here.");
      System.out.println();
      System.out.println("  Fallback only, if you want to confirm the kind exists at all:");
      System.out.println("     " + locate());
      System.out.println();
      if (DIM != Dim.OVERWORLD) {
         System.out.println("  THESE ARE " + "NETHER"
               + " COORDINATES. The same numbers in the overworld are a different");
         System.out.println("  place — the teleport lines below carry their own /execute in, so paste them");
         System.out.println("  whole rather than retyping the numbers into a bare /tp.");
         System.out.println();
      }

      // Two kinds of seed, and the CONTROL kind is what makes the test able to fail.
      //   - feature seeds: at least one nearby structure has it. Whichever /locate picks, the
      //     table has a row for it, so the walk tests both a yes and the nos around it.
      //   - control seeds: NOT ONE nearby structure has it. These need no lookup at all — if the
      //     walk turns up the feature anywhere, the predicate is producing false positives.
      List<Long> withBasement = new ArrayList<>();
      List<Long> without = new ArrayList<>();
      java.util.Map<Long, List<Hit>> bySeed = new java.util.HashMap<>();

      long scanned = 0;
      for (long seed = from; scanned < limit; seed++, scanned++) {
         List<Hit> hits = iglooesNearSpawn(ctx, igloo, structure, token, seed);
         if (hits.isEmpty()) {
            continue;
         }
         bySeed.put(seed, hits);
         if (hits.stream().anyMatch(Hit::basement)) {
            withBasement.add(seed);
         } else {
            without.add(seed);
         }
      }

      System.out.println("### HAS AT LEAST ONE " + FEATURE
            + " NEARBY — teleport to every row and check each ###");
      withBasement.stream().limit(want).forEach(s -> report(ctx, s, bySeed.get(s)));
      System.out.println("### CONTROL — NOT ONE of these is " + FEATURE
            + ". Teleport to every row; all must be " + NO_FEATURE + " ###");
      without.stream().limit(want).forEach(s -> report(ctx, s, bySeed.get(s)));

      System.out.println("scanned " + scanned + " seeds -> " + withBasement.size()
            + " with a " + FEATURE + " nearby, " + without.size() + " clean control seeds");
      if (withBasement.isEmpty() || without.isEmpty()) {
         System.out.println("NOT ENOUGH SEEDS — widen the scan.");
      }
      System.out.println("====================================");
   }

   /**
    * The in-game truth from a real walk (2026-08-01): six seeds, /locate run at spawn, each
    * igloo entered and its floor checked. This is the only hard ground truth this project has
    * for "where is the igloo AND what is in it", so it is kept as a regression set — any change
    * to placement, biome or assembly that breaks one of these six is a real break.
    *
    * The first walk got all six BASEMENT calls right but sent the player to the wrong hut on
    * seeds 105 and 988; those two rows are what the strict existence test has to fix.
    */
   private record Truth(long seed, int x, int z, boolean basement) {}

   private static final List<Truth> WALKED = List.of(
         new Truth(833, 176, 16, true),
         new Truth(105, -928, -384, true),
         new Truth(988, -880, 176, true),
         new Truth(808, 192, 16, false),
         new Truth(584, -352, 144, false),
         new Truth(1166, 112, 112, false));

   /** Coordinates were read off a chat message and retyped, so allow a few chunks of slop. */
   private static final int TOLERANCE = 48;

   private static void verify(WorldgenContext ctx, StructureTarget igloo,
                              Holder<Structure> structure, String token) {
      System.out.println("========== IGLOO WALK: REGRESSION vs REAL IN-GAME WALK ==========");
      int pass = 0;
      for (Truth t : WALKED) {
         List<Hit> hits = iglooesNearSpawn(ctx, igloo, structure, token, t.seed());
         if (hits.isEmpty()) {
            System.out.println("  seed " + pad(String.valueOf(t.seed()), 6) + "FAIL — predicts no igloo at all");
            continue;
         }
         // The claim under test is the one the tool actually makes: "the igloo at THESE
         // coordinates has/hasn't a basement". Ranking is explicitly not claimed, so it is not
         // asserted here — the row just has to exist and be right.
         Hit match = hits.stream()
               .filter(h -> Math.abs(h.pos().getX() - t.x()) <= TOLERANCE
                     && Math.abs(h.pos().getZ() - t.z()) <= TOLERANCE)
               .findFirst()
               .orElse(null);
         if (match == null) {
            System.out.println("  seed " + pad(String.valueOf(t.seed()), 6)
                  + "FAIL — walked igloo at " + t.x() + ", " + t.z() + " is not in the table at all");
         } else if (match.basement() != t.basement()) {
            System.out.println("  seed " + pad(String.valueOf(t.seed()), 6) + "FAIL   at "
                  + pad(t.x() + ", " + t.z(), 16) + "predicted "
                  + pad(match.basement() ? FEATURE : NO_FEATURE, 13)
                  + " | walked " + (t.basement() ? FEATURE : NO_FEATURE));
         } else {
            pass++;
            int rank = hits.indexOf(match) + 1;
            System.out.println("  seed " + pad(String.valueOf(t.seed()), 6) + "PASS   "
                  + pad(t.x() + ", " + t.z(), 16)
                  + pad(match.basement() ? FEATURE : NO_FEATURE, 13)
                  + "(row " + rank + " of " + hits.size()
                  + ", strict=" + (match.strict() ? "yes" : "no") + ")");
         }
      }
      System.out.println("  " + pass + " / " + WALKED.size() + " match the real walk");
      System.out.println("================================================================");
   }

   /**
    * Side-by-side of the three structures the game does NOT have against three it definitely does
    * (someone stood in them). Whatever differs between the two groups is the bug.
    *
    * The finder clears a candidate on ONE biome sample, taken at the locate position at a fixed
    * y from the catalog (64 for both igloos and portals). The real thing sits on the ground, which
    * is nowhere near y=64 in snowy hills, and biomes are three-dimensional — so the row that
    * matters here is whether the biome at y=64 and the biome at ground level are the same biome.
    */
   private static void probe(RegistryAccess.Frozen reg, WorldgenContext ctx) {
      record Spot(long seed, int x, int z, boolean real, String what) {}
      List<Spot> spots = List.of(
            new Spot(105, -160, 208, false, "igloo  PHANTOM"),
            new Spot(988, -224, 176, false, "igloo  PHANTOM"),
            new Spot(793, 240, 160, false, "portal PHANTOM"),
            new Spot(833, 176, 16, true, "igloo  REAL"),
            new Spot(988, -880, 176, true, "igloo  REAL"),
            new Spot(793, 368, -496, true, "portal REAL"));

      Holder<net.minecraft.world.level.levelgen.NoiseGeneratorSettings> noise =
            reg.lookupOrThrow(Registries.NOISE_SETTINGS)
                  .getOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD);
      var gen = new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(ctx.overworldBiomes, noise);
      net.minecraft.world.level.LevelHeightAccessor height = new net.minecraft.world.level.LevelHeightAccessor() {
         public int getHeight() {
            return 384;
         }

         public int getMinY() {
            return -64;
         }
      };

      System.out.println("========== WHY DO THE PHANTOMS PASS? ==========");
      System.out.println("  " + pad("SEED", 7) + pad("WHAT", 16) + pad("COORDS", 14)
            + pad("GROUND", 8) + pad("BIOME @ y=64", 26) + "BIOME @ GROUND");
      for (Spot s : spots) {
         RandomState rs = ctx.randomState(Dim.OVERWORLD, s.seed());
         RandomState full = ctx.fullRandomState(Dim.OVERWORLD, s.seed());
         int ground = gen.getBaseHeight(s.x(), s.z(),
               net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, height, full);
         String at64 = biomeName(ctx, rs, s.x(), 64, s.z());
         String atGround = biomeName(ctx, rs, s.x(), ground, s.z());
         System.out.println("  " + pad(String.valueOf(s.seed()), 7) + pad(s.what(), 16)
               + pad(s.x() + "," + s.z(), 14) + pad("y=" + ground, 8)
               + pad(at64, 26) + atGround + (at64.equals(atGround) ? "" : "   <-- DIFFERENT"));
      }
      System.out.println("===============================================");
   }

   private static String biomeName(WorldgenContext ctx, RandomState rs, int x, int y, int z) {
      Holder<net.minecraft.world.level.biome.Biome> h = ctx.overworldBiomes.getNoiseBiome(
            net.minecraft.core.QuartPos.fromBlock(x), net.minecraft.core.QuartPos.fromBlock(y),
            net.minecraft.core.QuartPos.fromBlock(z), rs.sampler());
      return h.unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
   }

   /**
    * Tests the one hypothesis that explains every ranking failure so far, without needing anyone
    * in game.
    *
    * {@code findSpawnPosition()} returns a CLIMATE target — a point with the right temperature and
    * humidity. It says nothing about whether there is ground there. Vanilla then looks for a
    * standable block, and when the climate target is over ocean it has to search outward, landing
    * the player somewhere else entirely.
    *
    * So: if the seeds whose ranking was wrong in game have a climate target UNDER WATER, and the
    * seeds that ranked correctly have one on dry land, that is the bug, confirmed from code alone.
    */
   private static void spawnCheck(RegistryAccess.Frozen reg, WorldgenContext ctx) {
      record Case(long seed, String verdict) {}
      List<Case> cases = List.of(
            new Case(833, "igloo   OK"),
            new Case(808, "igloo   OK"),
            new Case(584, "igloo   OK"),
            new Case(1166, "igloo   OK"),
            new Case(146, "portal  OK"),
            new Case(180, "portal  OK"),
            new Case(105, "igloo   RANKING WRONG"),
            new Case(988, "igloo   RANKING WRONG"),
            new Case(793, "portal  RANKING WRONG"));

      Holder<net.minecraft.world.level.levelgen.NoiseGeneratorSettings> noise =
            reg.lookupOrThrow(Registries.NOISE_SETTINGS)
                  .getOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD);
      var gen = new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(ctx.overworldBiomes, noise);
      net.minecraft.world.level.LevelHeightAccessor height = new net.minecraft.world.level.LevelHeightAccessor() {
         public int getHeight() {
            return 384;
         }

         public int getMinY() {
            return -64;
         }
      };
      int seaLevel = noise.value().seaLevel();

      System.out.println("========== SPAWN POINT: IS THE CLIMATE TARGET ON LAND? ==========");
      System.out.println("  sea level = " + seaLevel);
      System.out.println("  " + pad("SEED", 8) + pad("IN-GAME RESULT", 24) + pad("CLIMATE TARGET", 18)
            + pad("GROUND", 9) + "STANDABLE?");
      for (Case c : cases) {
         RandomState full = ctx.fullRandomState(Dim.OVERWORLD, c.seed());
         BlockPos spawn = ctx.randomState(Dim.OVERWORLD, c.seed()).sampler().findSpawnPosition();
         int ground = gen.getBaseHeight(spawn.getX(), spawn.getZ(),
               net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, height, full);
         boolean land = ground > seaLevel;
         System.out.println("  " + pad(String.valueOf(c.seed()), 8) + pad(c.verdict(), 24)
               + pad(spawn.getX() + ", " + spawn.getZ(), 18) + pad("y=" + ground, 9)
               + (land ? "yes — dry land" : "NO — UNDER WATER"));
      }
      System.out.println("  If every RANKING WRONG row is under water and every OK row is not,");
      System.out.println("  the spawn point is the bug and the contents predictions were never at fault.");
      System.out.println("=================================================================");
   }

   /** Every structure of this kind that really generates within {@link #REACH} of the dimension's
    *  origin, nearest first, each with its feature answer. Uses the shipped funnel (via
    *  {@link Placed}, which is stage0 + confirm), not a re-implementation. */
   private static List<Hit> iglooesNearSpawn(WorldgenContext ctx, StructureTarget igloo,
                                             Holder<Structure> structure, String token, long seed) {
      // The origin is the dimension's, not always spawn: spawn in the overworld, spawn/8 in the
      // Nether (where a portal lit at spawn comes out).
      List<Placed.Spot> candidates = Placed.near(ctx, igloo, seed, DIM, REACH);

      // Every candidate is reported, none are dropped. The distance ranking cannot be trusted
      // (see the class notes on spawn), so a candidate silently removed here is a row missing
      // from the lookup table — the one failure mode that would make this tool useless. The
      // stricter vanilla biome test still runs, but only as a COLUMN, never as a filter.
      List<Hit> out = new ArrayList<>();
      RandomState full = null;
      for (Placed.Spot h : candidates) {
         if (out.size() >= 6) {
            break;
         }
         if (full == null) {
            full = ctx.fullRandomState(DIM, seed);   // ~9ms, only if there's work
         }
         // The variant that actually won this chunk, not the one this run was launched for.
         Holder<Structure> here = h.winner() != null ? h.winner() : structure;
         List<Identifier> pieces = VillageLayout.pieceTemplates(
               ctx.source, ctx, seed, h.chunk(), here, full, DIM);
         // An empty list is not always a bug: a code-built structure (a fortress, a desert
         // pyramid) has no template names at all. It is dropped here because a row that cannot be
         // read cannot be checked, and this tool only prints coordinates it has verified.
         if (pieces.isEmpty()) {
            continue;
         }
         boolean strict = !VillageLayout.pieceTemplates(ctx.source, ctx, seed, h.chunk(), here,
               full, VillageLayout.ownBiomes(here), DIM).isEmpty();
         // WHICH variant sits here matters to the player, not just to the assembler: /locate takes
         // ONE structure id, and the plain id only finds its own variant. A row whose variant is
         // not the one being located is a row /locate will silently skip over.
         String variant = here.unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
         out.add(new Hit(h.chunk(), h.pos(), h.dist(),
               VillageLayout.hasBuilding(pieces, token), strict, variant));
      }
      return out;
   }

   private static void report(WorldgenContext ctx, long seed, List<Hit> hits) {
      BlockPos spawn = ctx.randomState(Dim.OVERWORLD, seed).sampler().findSpawnPosition();
      BlockPos from = Placed.origin(ctx, seed, DIM);
      System.out.println("SEED " + seed);
      System.out.println("  overworld spawn ~" + spawn.getX() + ", " + spawn.getZ());
      if (DIM != Dim.OVERWORLD) {
         // Naming the origin matters here: these coordinates are in another world and a reader
         // who assumes they are overworld ones will walk to the wrong place entirely.
         System.out.println("  rows below are " + "NETHER"
               + " coordinates, measured from " + from.getX() + ", " + from.getZ()
               + "  (spawn / 8 — where a portal lit at spawn comes out)");
      }
      // No distances, no ordering, no "nearest". That claim failed 3 times in 12 walks and its
      // cause was never found, so it is not made at all — the rows are sorted by coordinate so
      // they are easy to scan, and the only claim is what stands at each one.
      List<Hit> rows = hits.stream()
            .sorted(Comparator.comparingInt((Hit h) -> h.pos().getX()).thenComparingInt(h -> h.pos().getZ()))
            .toList();
      // pad() only ever adds spaces, so a value wider than its column silently runs into the next
      // one. 26 fits the longest prediction word any subject uses.
      // ONE BLOCK PER ROW, each carrying its own teleport.
      //
      // The old layout printed a table and then a separate list of teleport commands, and that
      // cost a real walk: seed 10 has TWO ancient cities, 1400 blocks apart, and both were rows
      // here. The tester teleported using one line and read the prediction off another, then
      // recorded a drift bug against coordinates that were accurate to 15 blocks. Nothing was
      // wrong with the numbers; the reader could simply cross between rows without noticing.
      //
      // Keeping each claim welded to the command that takes you to it removes the possibility.
      // A seed can have several of the same structure nearby, and the table must never let two of
      // them blur together.
      System.out.println("    " + rows.size() + " " + (rows.size() == 1 ? "structure" : "separate structures")
            + " near this seed's spawn — each block below is a SEPARATE one:");
      for (int i = 0; i < rows.size(); i++) {
         Hit h = rows.get(i);
         System.out.println();
         System.out.println("    [" + (i + 1) + " of " + rows.size() + "]  "
               + h.pos().getX() + ", " + h.pos().getZ()
               + "   ->  " + (h.basement() ? FEATURE : NO_FEATURE));
         System.out.println("           " + tp(h.pos().getX(), h.pos().getZ()));
         System.out.println("           variant " + h.variant() + ", strict=" + (h.strict() ? "yes" : "no"));
      }
      System.out.println();
      if (!HINT.isEmpty()) {
         System.out.println("     " + HINT);
      }
      System.out.println("     (do NOT use /locate to choose which one — it goes to the nearest,");
      System.out.println("      which is usually not any of the rows. Fallback only, to confirm the");
      System.out.println("      kind exists in this world: " + locate() + ")");
      System.out.println();
   }

   /** The fallback /locate, run in the right world — from the overworld it would find nothing at
    *  all for a Nether subject, which reads as "the prediction is wrong" and is not. */
   private static String locate() {
      // No leading slash on the inner command: "/execute … run /locate" is a syntax error, and a
      // command that does not run reads as a failed prediction.
      String cmd = "locate structure " + LOCATE_ID;
      return switch (DIM) {
         case NETHER -> "/execute in minecraft:the_nether run " + cmd;
         default -> "/" + cmd;
      };
   }

   /**
    * The ready-to-paste teleport for one row.
    *
    * y is 200 wherever it can be, so you arrive in open air above the spot rather than inside a
    * hillside. The Nether uses 120 instead, and that is not a style choice: its bedrock roof is at
    * y=127, so a teleport to 200 puts you outside the world and drops you onto the roof.
    *
    * Nether rows are wrapped in {@code /execute in} because the coordinates are in that
    * worlds — a bare /tp would take you to the same numbers in the overworld, which is a different
    * place and the exact kind of mistake this whole tool exists to stop.
    */
   private static String tp(int x, int z) {
      String at = x + " " + TP_Y + " " + z;
      return switch (DIM) {
         case NETHER -> "/execute in minecraft:the_nether run tp @s " + at;
         default -> "/tp @s " + at;
      };
   }

   private static String pad(String s, int width) {
      StringBuilder sb = new StringBuilder(s);
      while (sb.length() < width) {
         sb.append(' ');
      }
      return sb.toString();
   }

   private IglooWalkDiagnostic() {
   }
}
