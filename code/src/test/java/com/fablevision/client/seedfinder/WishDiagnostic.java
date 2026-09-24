package com.fablevision.client.seedfinder;

import java.util.List;

import com.fablevision.VillageDiagnostic;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * Dev-only: shows what the AI wish layer is TAUGHT and what it RESOLVES TO, without spending an
 * AI request or launching the game.
 *
 * The wish flow has two halves and only one of them involves a model:
 *   1. the prompt, which lists the rows this world really built and the player words that map to
 *      them — printed here in full, so what the AI is told can be read rather than assumed;
 *   2. the validator, which turns a JSON reply into a real {@link SeedCriteria} — exercised here
 *      against hand-written replies, including the wrong-but-plausible ones a model actually
 *      produces (both the plain row and its feature row, two rows about the same structure, a
 *      name that exists in no catalog).
 *
 * Nothing here decides whether a seed matches. That is still {@link SeedCriteria#test}, which
 * assembles the structure and reads its piece list for every candidate. A bad translation costs
 * the player the wrong SEARCH; it can never produce a wrong ANSWER.
 */
public final class WishDiagnostic {

   private record Case(String wish, String reply, String expect) {}

   /**
    * Replies are written the way a model really answers, not the way we would like it to. Half of
    * these are deliberately imperfect — that is the point, because the validator is the thing
    * standing between an imperfect reply and a search that quietly means something else.
    */
   private static final List<Case> CASES = List.of(
         new Case("big portal, basement, secret room",
               "{\"structures\":[{\"name\":\"Giant Ruined Portal\"},{\"name\":\"Igloo with Basement\"},"
               + "{\"name\":\"Mansion with Secret Room\"}],\"biomes\":[],\"exclude\":[],\"stronghold_mentioned\":false,"
               + "\"cant\":\"\"}",
               "all three searched — the mansion room is a REAL search now, not a caveat"),
         // The reply a model trained on the old prompt still produces: name the plain mansion and
         // apologise for the room. The plain row must survive (a mansion is what was asked for)
         // and nothing here may silently upgrade it — the AI chose the row, and it is allowed to.
         new Case("a mansion with a secret room",
               "{\"structures\":[{\"name\":\"Woodland Mansion\"}],\"biomes\":[],\"exclude\":[],"
               + "\"stronghold_mentioned\":false,\"cant\":\"secret rooms can't be searched for\"}",
               "the plain row is honoured as given; its stale note is passed through, not corrected"),
         new Case("mansion with the big lava secret room",
               "{\"structures\":[{\"name\":\"Mansion with Lava Vault\"}],\"biomes\":[],\"exclude\":[],"
               + "\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "the rare room row, whose own note warns it is a long search"),
         new Case("igloo with a basement",
               "{\"structures\":[{\"name\":\"Igloo\"},{\"name\":\"Igloo with Basement\"}],\"biomes\":[],"
               + "\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "the plain Igloo row dropped - the feature row already includes it"),
         new Case("a big warm ocean ruin",
               "{\"structures\":[{\"name\":\"Warm Ocean Ruins\"},{\"name\":\"Large Ocean Ruin\"}],\"biomes\":[],"
               + "\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "ONE ruin row kept - two would be two different ruins"),
         new Case("trial chamber with the eruption room and the encounter hall",
               "{\"structures\":[{\"name\":\"Trial Chamber with Eruption Room\"},"
               + "{\"name\":\"Trial Chamber with Encounter Hall\"}],\"biomes\":[],\"exclude\":[],"
               + "\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "one chamber row kept, the other named - both rooms in ONE chamber isn't checkable"),
         new Case("igloo basement please",
               "{\"structures\":[{\"name\":\"Igloo Basement\"}],\"biomes\":[],\"exclude\":[],"
               + "\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "loose name still resolves to the right row"),
         // The blacksmith goes to the WEAPONSMITH, not the armorer. blacksmithDiag censused all 483
         // village templates: exactly four contain lava and all four are weaponsmith houses, while
         // the armorer's house is a blast furnace and no lava at all. The old mapping sent a player
         // asking for the lava-pit-and-chest hut to the wrong building.
         new Case("a cave full of diamonds and a village with a blacksmith",
               "{\"structures\":[{\"name\":\"Diamond Cave\"},{\"name\":\"Village with Weaponsmith\"}],\"biomes\":[],"
               + "\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"ores can't be searched for; there's no "
               + "blacksmith building any more — the weaponsmith's house is the one with the lava and the chest\"}",
               "blacksmith -> WEAPONSMITH (the lava-and-chest house); the invented name is dropped and named"),
         // ── Bug fixes from play-testing, each pinned so it cannot come back ────────────────────
         new Case("a village with a blacksmith",
               "{\"structures\":[{\"name\":\"Village with Weaponsmith\"}],\"biomes\":[],"
               + "\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"there's no blacksmith building any more\"}",
               "row renamed to what they TYPED, and the model's apology is dropped — this search works"),
         // The wish text must NOT contain the word, because that is exactly what the gate tests.
         new Case("a village and a jungle please",
               "{\"structures\":[{\"name\":\"Surface Village\"}],\"biomes\":[{\"name\":\"jungle\"}],"
               + "\"slime\":{\"count\":3,\"within\":128},\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "the model volunteered a slime search — IGNORED, because the player never mentioned it"),
         new Case("7 wood types near spawn",
               "{\"structures\":[],\"biomes\":[{\"name\":\"forest\"},{\"name\":\"birch_forest\"},"
               + "{\"name\":\"dark_forest\"},{\"name\":\"taiga\"},{\"name\":\"jungle\"},"
               + "{\"name\":\"savanna\"},{\"name\":\"cherry_grove\"}],"
               + "\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "six are kept and the seventh is NAMED — it used to keep three and say nothing"),
         new Case("two shipwrecks near spawn",
               "{\"structures\":[{\"name\":\"Shipwreck\",\"count\":2}],\"biomes\":[],"
               + "\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "the number is HONOURED now — it used to be dropped and one was searched for"),
         new Case("four villages each with an armorer",
               "{\"structures\":[{\"name\":\"Village with Armorer\",\"count\":4}],\"biomes\":[],"
               + "\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "capped: four assemblies per seed is a hang, so it says what it did instead"),
         new Case("I want to open my eyes standing in a village",
               "{\"structures\":[{\"name\":\"Surface Village\",\"spawn_inside\":true}],\"biomes\":[],"
               + "\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "the footprint test, not a distance — and it forces Exact mode"),
         new Case("spawn inside a nether fortress",
               "{\"structures\":[{\"name\":\"Nether Fortress\",\"spawn_inside\":true}],\"biomes\":[],"
               + "\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "refused with the real reason — you do not spawn in the Nether — and still searched"),
         new Case("a fortress next to a bastion",
               "{\"structures\":[{\"name\":\"Nether Fortress\"},{\"name\":\"Bastion Remnant\"}],\"biomes\":[],"
               + "\"adjacent\":[{\"a\":\"Nether Fortress\",\"b\":\"Bastion Remnant\"}],"
               + "\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "two STRUCTURES pair now — but these two share a structure set, so it must refuse"),
         new Case("a village next to a pillager outpost",
               "{\"structures\":[{\"name\":\"Surface Village\"},{\"name\":\"Pillager Outpost\"}],\"biomes\":[],"
               + "\"adjacent\":[{\"a\":\"Surface Village\",\"b\":\"Pillager Outpost\"}],"
               + "\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "a structure pair that IS possible — different sets, so it wires up"),
         new Case("somewhere to build a slime farm",
               "{\"structures\":[],\"biomes\":[],\"slime\":{\"count\":3,\"within\":64},"
               + "\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "no worldgen at all — and at 64 blocks it is a real filter"),
         new Case("3 slime chunks within 400 blocks",
               "{\"structures\":[],\"biomes\":[],\"slime\":{\"count\":3,\"within\":400},"
               + "\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "honoured, but told plainly that at 400 blocks it narrows nothing"),
         new Case("a big village",
               "{\"structures\":[{\"name\":\"Surface Village\",\"size\":\"big\"}],\"biomes\":[],"
               + "\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "size on a STRUCTURE, using the piece count statsDiag measured"),
         new Case("a big igloo",
               "{\"structures\":[{\"name\":\"Igloo\",\"size\":\"big\"}],\"biomes\":[],"
               + "\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "an igloo is the same size every time, so the size is dropped and named"),
         new Case("a ruined portal that isn't buried",
               "{\"structures\":[{\"name\":\"Surface Ruined Portal\"}],\"biomes\":[],"
               + "\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "the surface variant is its own row now, so \"not buried\" is a real search"),
         new Case("an underwater portal",
               "{\"structures\":[{\"name\":\"Underwater Ruined Portal\"}],\"biomes\":[],"
               + "\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "placement is read off the piece, so ocean-floor portals are searchable too"),
         new Case("village with an armorer that's also abandoned",
               "{\"structures\":[{\"name\":\"Village with Armorer\"},{\"name\":\"Abandoned Village\"}],"
               + "\"biomes\":[],\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "one village row kept - a zombie village AND an armorer can't be demanded together"),
         new Case("outpost with allays and a treasure bastion and the end ship",
               "{\"structures\":[{\"name\":\"Outpost with Allay Cage\"},{\"name\":\"Treasure Bastion\"},"
               + "{\"name\":\"End City with Ship\"}],\"biomes\":[],\"exclude\":[],\"stronghold_mentioned\":false,"
               + "\"cant\":\"\"}",
               "three dimensions at once, all kept - different structures don't clash"),
         // ── The "without" schema ──────────────────────────────────────────────────────
         new Case("an igloo but not the kind with the basement",
               "{\"structures\":[],\"biomes\":[],\"exclude\":[],"
               + "\"without\":[{\"name\":\"Igloo with Basement\"}],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "a real wish with an EMPTY structures list - the without array carries it"),
         new Case("a village with a library but no zombies",
               "{\"structures\":[{\"name\":\"Village with Librarian\"}],\"biomes\":[],\"exclude\":[],"
               + "\"without\":[{\"name\":\"Abandoned Village\"}],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "the two halves MERGE - one village row carrying both the library and \"not abandoned\""),
         // The mirror of the case above, and it must NOT merge: zombie villages build from their
         // own small house set and no zombie village in this version has an armorer at all.
         new Case("an abandoned village with an armorer",
               "{\"structures\":[{\"name\":\"Abandoned Village\"},{\"name\":\"Village with Armorer\"}],"
               + "\"biomes\":[],\"exclude\":[],\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "refused as impossible, with the real reason - not \"one feature at a time\""),
         new Case("a shipwreck that isn't capsized",
               "{\"structures\":[],\"biomes\":[],\"exclude\":[],"
               + "\"without\":[{\"name\":\"Capsized Shipwreck\"}],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "no honest negative for this row - it is NAMED, never flipped to the positive"),
         new Case("a village and definitely no pillager outpost anywhere near",
               "{\"structures\":[{\"name\":\"Surface Village\"}],\"biomes\":[],"
               + "\"exclude\":[{\"name\":\"Pillager Outpost\"}],\"without\":[],"
               + "\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "exclude is the WHOLE structure gone - not the same thing as without"),
         new Case("village in a huge jungle, no ocean nearby",
               "{\"structures\":[{\"name\":\"Surface Village\"}],\"biomes\":[{\"name\":\"jungle\",\"size\":\"huge\"}],"
               + "\"exclude\":[{\"name\":\"ocean\"}],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "structure + sized biome + an exclusion"),
         new Case("loads of diamonds in the vault",
               "{\"structures\":[{\"name\":\"Trial Chambers\"}],\"biomes\":[],\"exclude\":[],"
               + "\"stronghold_mentioned\":false,\"cant\":\"what's in the vaults - vault loot is decided when you spend a key\"}",
               "chamber searched, vault loot named exactly"),
         // ── Composed bundles: the vague-wish path ───────────────────────────────────────
         new Case("a good survival seed",
               "{\"structures\":[{\"name\":\"Surface Village\",\"distance\":250},"
               + "{\"name\":\"Ruined Portal\",\"distance\":350},{\"name\":\"Shipwreck\",\"distance\":500}],"
               + "\"biomes\":[{\"name\":\"forest\",\"size\":\"any\",\"distance\":200},"
               + "{\"name\":\"plains\",\"size\":\"any\",\"distance\":200}],"
               + "\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "5 items, each with its OWN reach - not everything at 64"),
         new Case("a cozy woodland base",
               "{\"structures\":[{\"name\":\"Surface Village\",\"distance\":400},"
               + "{\"name\":\"Woodland Mansion\",\"distance\":600}],"
               + "\"biomes\":[{\"name\":\"dark_forest\",\"size\":\"big\",\"distance\":200},"
               + "{\"name\":\"river\",\"size\":\"any\",\"distance\":250}],"
               + "\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "a DIFFERENT bundle from the survival one - composed, not a preset"),
         new Case("best speedrun seed",
               "{\"structures\":[{\"name\":\"Surface Village\",\"distance\":200},"
               + "{\"name\":\"Ruined Portal\",\"distance\":300},{\"name\":\"Nether Fortress\",\"distance\":300},"
               + "{\"name\":\"Bastion Remnant\",\"distance\":300}],"
               + "\"biomes\":[{\"name\":\"plains\",\"size\":\"any\",\"distance\":200}],"
               + "\"exclude\":[],\"stronghold_mentioned\":true,\"cant\":\"\"}",
               "no stronghold in the search; the mention earns an honest note instead"),
         new Case("a seed with an armorer village, a capsized wreck and an eruption chamber",
               "{\"structures\":[{\"name\":\"Village with Armorer\",\"distance\":300},"
               + "{\"name\":\"Capsized Shipwreck\",\"distance\":500},"
               + "{\"name\":\"Trial Chamber with Eruption Room\",\"distance\":400}],"
               + "\"biomes\":[],\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "THREE content rows - the third must be dropped and named (cap is 2)"),
         new Case("village right on top of me and a mansion miles away",
               "{\"structures\":[{\"name\":\"Surface Village\",\"distance\":5},"
               + "{\"name\":\"Woodland Mansion\",\"distance\":99999}],"
               + "\"biomes\":[],\"exclude\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "distances clamped to 64..2000 - 5 is impossible, 99999 isn't 'near spawn'"),
         new Case("a world where I spawn on a diamond block",
               "{\"structures\":[{\"name\":\"Diamond Block\"}],\"biomes\":[],\"exclude\":[],"
               + "\"stronghold_mentioned\":false,\"cant\":\"blocks at spawn can't be searched\"}",
               "nothing searchable at all - must be an ERROR, never an empty search"),
         // ── SIZE TIERS and "NEXT TO" ────────────────────────────────────────────────────
         // The whole point of this block is that NONE of these may fall through to "can't
         // check". Every one is a capability the engine now really has, so a wish that lands
         // in the note instead of the search is a bug in the prompt, not an honest refusal.
         new Case("a big pale garden next to dark oak",
               "{\"structures\":[],\"biomes\":[{\"name\":\"pale_garden\",\"size\":\"big\"},"
               + "{\"name\":\"dark_forest\",\"size\":\"any\"}],"
               + "\"adjacent\":[{\"a\":\"pale_garden\",\"b\":\"dark_forest\"}],\"exclude\":[],"
               + "\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "LARGE tier on the pale garden (>=64, its own measured p67) + a 256-block pair"),
         new Case("cherry blossom next to a village",
               "{\"structures\":[{\"name\":\"Surface Village\"}],"
               + "\"biomes\":[{\"name\":\"cherry_grove\",\"size\":\"any\"}],"
               + "\"adjacent\":[{\"a\":\"cherry_grove\",\"b\":\"Surface Village\"}],\"exclude\":[],"
               + "\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "biome-to-STRUCTURE, defaulting to the wider 512 - structures are sparser"),
         new Case("plains next to plains",
               "{\"structures\":[],\"biomes\":[{\"name\":\"plains\",\"size\":\"any\"}],"
               + "\"adjacent\":[{\"a\":\"plains\",\"b\":\"plains\"}],\"exclude\":[],\"without\":[],"
               + "\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "REFUSED with the real reason - a biome is always next to itself; plains still searched"),
         new Case("blossom next to a ruined portal",
               "{\"structures\":[{\"name\":\"Ruined Portal\"}],"
               + "\"biomes\":[{\"name\":\"cherry_grove\",\"size\":\"any\"}],"
               + "\"adjacent\":[{\"a\":\"cherry_grove\",\"b\":\"Ruined Portal\"}],\"exclude\":[],"
               + "\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "the player's word \"blossom\" reaches cherry_grove through the biome glossary"),
         new Case("a small pale garden",
               "{\"structures\":[],\"biomes\":[{\"name\":\"pale_garden\",\"size\":\"small\"}],"
               + "\"adjacent\":[],\"exclude\":[],\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "SMALL is a real search now - a span CEILING, not a shrug"),
         new Case("a tiny cherry grove right beside a mangrove swamp",
               "{\"structures\":[],\"biomes\":[{\"name\":\"cherry_grove\",\"size\":\"tiny\"},"
               + "{\"name\":\"mangrove_swamp\",\"size\":\"any\"}],"
               + "\"adjacent\":[{\"a\":\"cherry_grove\",\"b\":\"mangrove_swamp\",\"within\":128}],"
               + "\"exclude\":[],\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "\"tiny\" maps to SMALL; 128 is under the tight threshold so it is warned about"),
         new Case("a huge deep ocean with a shipwreck in it",
               "{\"structures\":[{\"name\":\"Shipwreck\"}],"
               + "\"biomes\":[{\"name\":\"deep_ocean\",\"size\":\"huge\"}],"
               + "\"adjacent\":[{\"a\":\"deep_ocean\",\"b\":\"Shipwreck\"}],\"exclude\":[],"
               + "\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "the SAME word \"huge\" means >=512 here and >=64 for a pale garden - per biome"),
         new Case("a big beach",
               "{\"structures\":[],\"biomes\":[{\"name\":\"beach\",\"size\":\"big\"}],"
               + "\"adjacent\":[],\"exclude\":[],\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "beaches measured all one size - the beach is searched, the SIZE is dropped and named"),
         new Case("mesa next to bamboo",
               "{\"structures\":[],\"biomes\":[{\"name\":\"badlands\",\"size\":\"any\"},"
               + "{\"name\":\"bamboo_jungle\",\"size\":\"any\"}],"
               + "\"adjacent\":[{\"a\":\"badlands\",\"b\":\"bamboo_jungle\"}],\"exclude\":[],"
               + "\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "two nicknames, neither of which is the id, both resolved by the biome glossary"),
         new Case("a village within 100 blocks of a jungle",
               "{\"structures\":[{\"name\":\"Surface Village\"}],"
               + "\"biomes\":[{\"name\":\"jungle\",\"size\":\"any\"}],"
               + "\"adjacent\":[{\"a\":\"jungle\",\"b\":\"Surface Village\",\"within\":100}],"
               + "\"exclude\":[],\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "an explicit tight distance is HONOURED, and warned about rather than quietly widened"),
         new Case("the sculk biome next to a mansion",
               "{\"structures\":[{\"name\":\"Woodland Mansion\"}],"
               + "\"biomes\":[{\"name\":\"deep_dark\",\"size\":\"any\"}],"
               + "\"adjacent\":[{\"a\":\"deep_dark\",\"b\":\"Woodland Mansion\"}],\"exclude\":[],"
               + "\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "\"sculk\" -> deep_dark, searched underground (it was 5% at Y=64, and 0% found, before 1.44.2)"),
         new Case("plains next to forest",
               "{\"structures\":[],\"biomes\":[{\"name\":\"plains\",\"size\":\"any\"},"
               + "{\"name\":\"forest\",\"size\":\"any\"}],"
               + "\"adjacent\":[{\"a\":\"plains\",\"b\":\"forest\"}],\"exclude\":[],\"without\":[],"
               + "\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "searched exactly as asked - both are near spawn in 60/60 seeds, so it narrows little"),
         // The model writes the pair backwards more often than it writes it wrong: people say
         // "a village next to a cherry grove", so the structure lands in "a". Swapping is safe
         // and silent, because only one of the two ends can ever be the biome anchor.
         new Case("a village next to a cherry grove (reply written backwards)",
               "{\"structures\":[{\"name\":\"Surface Village\"}],"
               + "\"biomes\":[{\"name\":\"cherry_grove\",\"size\":\"any\"}],"
               + "\"adjacent\":[{\"a\":\"Surface Village\",\"b\":\"cherry_grove\"}],\"exclude\":[],"
               + "\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "ends swapped back automatically - the biome has to be the anchor"),
         new Case("a jungle next to a floating castle",
               "{\"structures\":[],\"biomes\":[{\"name\":\"jungle\",\"size\":\"any\"}],"
               + "\"adjacent\":[{\"a\":\"jungle\",\"b\":\"Floating Castle\"}],\"exclude\":[],"
               + "\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "the invented half is NAMED and dropped; the jungle is still searched"),
         new Case("dark oak next to a nether fortress",
               "{\"structures\":[{\"name\":\"Nether Fortress\"}],"
               + "\"biomes\":[{\"name\":\"dark_forest\",\"size\":\"any\"}],"
               + "\"adjacent\":[{\"a\":\"dark_forest\",\"b\":\"Nether Fortress\"}],\"exclude\":[],"
               + "\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "different dimensions - the PAIR is refused with the reason, both still searched"),
         new Case("a big jungle next to a village, no desert anywhere",
               "{\"structures\":[{\"name\":\"Surface Village\",\"distance\":400}],"
               + "\"biomes\":[{\"name\":\"jungle\",\"size\":\"big\",\"distance\":300}],"
               + "\"adjacent\":[{\"a\":\"jungle\",\"b\":\"Surface Village\",\"within\":384}],"
               + "\"exclude\":[{\"name\":\"desert\"}],\"without\":[],"
               + "\"stronghold_mentioned\":false,\"cant\":\"\"}",
               "size + pair + exclusion + per-item distances, all in one wish"));

   public static void main(String[] args) throws Exception {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();

      // The wish layer reads the same catalog the picker shows, so it has to be built the same
      // way. Anything else would be testing a lookalike.
      SeedCatalogCache.ensure(reg);
      for (int waited = 0; !SeedCatalogCache.ready() && waited < 60_000; waited += 100) {
         Thread.sleep(100);
      }
      if (!SeedCatalogCache.ready()) {
         System.out.println("FAILED: catalog never finished building. " + SeedCatalogCache.error());
         return;
      }

      if (args.length > 0 && "map".equals(args[0])) {
         coverageMap();
         registryCoverage(WorldgenContext.get(reg));
         System.exit(0);
      }

      if (args.length == 0 || "prompt".equals(args[0])) {
         System.out.println("========== WHAT THE AI IS TAUGHT ==========");
         System.out.println(WishParser.buildPrompt("big portal, basement, secret room"));
         System.out.println("===========================================");
         System.out.println();
      }

      System.out.println("========== WHAT EACH WISH RESOLVES TO ==========");
      int ok = 0;
      for (Case c : CASES) {
         System.out.println("WISH  \"" + c.wish() + "\"");
         System.out.println("  expected: " + c.expect());
         WishParser.Outcome out = WishParser.parse(c.reply(), c.wish());
         if (out.criteria() == null) {
            System.out.println("  -> NO SEARCH: " + out.error());
         } else {
            System.out.println("  -> searches: " + out.criteria().summary());
            for (SeedCriteria.StructureTarget t : out.criteria().structures) {
               // The extras are appended rather than replacing the description, because a row can
               // carry several at once — "2 big villages you spawn inside" is one row with three.
               StringBuilder extra = new StringBuilder();
               if (t.count > 1) {
                  extra.append(" · ").append(t.count).append(" of them");
               }
               if (t.spawnInside) {
                  extra.append(" · spawn INSIDE it");
               }
               if (t.minPieces > 0) {
                  extra.append(" · at least ").append(t.minPieces).append(" pieces");
               }
               System.out.println("       " + pad(t.label, 34)
                     + (t.building == null
                           ? t.zombie == SeedCriteria.ZombieMode.ANY ? "(whole structure)"
                                 : "start piece must be " + (t.zombie == SeedCriteria.ZombieMode.ABANDONED
                                       ? "a zombie one" : "a NORMAL one")
                           : (t.without ? "must NOT contain: " : "piece token: ") + t.building)
                     + extra);
            }
            for (SeedCriteria.SlimeTarget s : out.criteria().slimes) {
               System.out.println("       " + pad(s.label(), 34)
                     + (SlimeChunks.narrows(s.count, s.radius) ? "a real filter"
                           : "<-- NARROWS NOTHING, nearly every seed has this"));
            }
            for (SeedCriteria.BiomeTarget b : out.criteria().biomes) {
               System.out.println("       " + pad(b.label, 34)
                     + (b.minSpan > 0 ? "must span >= " + b.minSpan + " blocks (its own p67)"
                           : b.maxSpan > 0 ? "must span <= " + b.maxSpan + " blocks (its own p33)"
                           : "any size"));
            }
            for (SeedCriteria.Adjacency a : out.criteria().adjacencies) {
               System.out.println("       " + pad("next to: " + a.otherLabel(), 34)
                     + "within " + a.within + " of the " + a.anchorLabel()
                     + (a.within < BiomeShape.TIGHT ? "   <-- tight" : ""));
            }
            for (SeedCriteria.StructureTarget t : out.criteria().excludeStructures) {
               System.out.println("       " + pad("NOT " + t.label, 34) + "must not be nearby");
            }
            for (SeedCriteria.BiomeTarget b : out.criteria().excludeBiomes) {
               System.out.println("       " + pad("NOT " + b.label, 34) + "must not be nearby");
            }
            // The app distinguishes an EXPLANATION from an apology, so this has to as well —
            // printing the blacksmith note under "can't check" here would misreport a fix as a
            // limitation and hide whether the real screen would get it right.
            String n = out.note();
            if (n == null || n.isEmpty()) {
               System.out.println("  -> can't check: (nothing)");
            } else if (n.startsWith(WishParser.PLAIN_NOTE) || n.startsWith(WishParser.STRONGHOLD_CANT)) {
               System.out.println("  -> explains: " + n.replace(WishParser.PLAIN_NOTE, ""));
            } else {
               System.out.println("  -> can't check: " + n);
            }
         }
         ok++;
         System.out.println();
      }
      System.out.println("ran " + ok + " wishes");
      System.out.println("Every row above is only a SEARCH SETTING. Whether a seed really has the");
      System.out.println("piece is decided by SeedCriteria.test, which assembles the structure.");
      System.out.println("===============================================");
      int shapeFails = checkReplyShapes();
      int vocabFails = checkVocabulary();
      int agreeFails = checkExamplesAgreeWithGlossary();
      int localFails = checkLocalWishes();
      System.exit(shapeFails + vocabFails + agreeFails + localFails == 0 ? 0 : 1);
   }

   /**
    * THE COMMON WISHES, READ WITH NO AI. Each case says which rows the search must contain, which it
    * must not, and whether the reader should claim to have understood every word. "Complete" is the
    * dangerous claim — it is what lets a wish skip the AI — so a wish with a word the reader cannot
    * place (a bundle like "best survival seed") must come back incomplete.
    */
   private static int checkLocalWishes() {
      record Local(String wish, List<String> mustHave, List<String> mustNotHave, boolean complete, int adjacencies, int excludes) {}
      List<Local> cases = List.of(
            new Local("village next to a cherry grove", List.of("Surface Village", "Cherry Grove"), List.of(), true, 1, 0),
            new Local("mansion + dark forest", List.of("Woodland Mansion", "Dark Forest"), List.of(), true, 0, 0),
            new Local("a mansion in a dark forest", List.of("Woodland Mansion", "Dark Forest"), List.of(), true, 1, 0),
            new Local("igloo with a basement", List.of("Igloo with Basement"), List.of("Igloo"), true, 0, 0),
            new Local("village with a blacksmith", List.of("Blacksmith"), List.of("Village with Armorer"), true, 0, 0),
            new Local("two shipwrecks near spawn", List.of("Shipwreck"), List.of(), true, 0, 0),
            new Local("pale garden not next to a forest", List.of("Pale Garden"), List.of(), true, 0, 1),
            new Local("a big pale garden next to dark oak", List.of("Pale Garden", "Dark Forest"), List.of(), true, 1, 0),
            new Local("no ocean near spawn", List.of(), List.of(), true, 0, 1),
            new Local("slime chunks and a village", List.of("Surface Village"), List.of(), true, 0, 0),
            new Local("a giant portal and a trial chamber", List.of("Giant Ruined Portal", "Trial Chambers"), List.of(), true, 0, 0),
            new Local("best survival seed", List.of(), List.of(), false, 0, 0),
            new Local("a village where my friends can build a castle", List.of("Surface Village"), List.of(), false, 0, 0));
      System.out.println();
      System.out.println("========== COMMON WISHES WITH NO AI ==========");
      int bad = 0;
      for (Local c : cases) {
         LocalWish.Result r = LocalWish.parse(c.wish());
         StringBuilder why = new StringBuilder();
         if (r.complete() != c.complete()) {
            why.append(" complete=").append(r.complete()).append(" unknown=").append(r.unknown());
         }
         java.util.Set<String> labels = new java.util.HashSet<>();
         int adj = 0;
         int ex = 0;
         if (r.found()) {
            WishParser.Outcome out = WishParser.parse(r.json().toString(), c.wish());
            if (out.criteria() != null) {
               out.criteria().structures.forEach(t -> labels.add(t.label));
               out.criteria().biomes.forEach(b -> labels.add(b.label));
               adj = out.criteria().adjacencies.size();
               ex = out.criteria().excludeBiomes.size() + out.criteria().excludeStructures.size();
            } else if (!c.mustHave().isEmpty()) {
               why.append(" no search: ").append(out.error());
            }
         }
         for (String m : c.mustHave()) {
            // A counted row is labelled "2× Shipwreck"; the row is still the row.
            if (labels.stream().noneMatch(l -> l.equals(m) || l.endsWith("× " + m))) {
               why.append(" missing ").append(m);
            }
         }
         for (String m : c.mustNotHave()) {
            if (labels.contains(m)) {
               why.append(" should not have ").append(m);
            }
         }
         if (c.complete() && adj != c.adjacencies()) {
            why.append(" next-to pairs=").append(adj);
         }
         if (c.complete() && ex != c.excludes()) {
            why.append(" exclusions=").append(ex);
         }
         boolean ok = why.length() == 0;
         System.out.println("  " + (ok ? "ok   " : "FAIL ") + "\"" + c.wish() + "\" -> " + labels
               + (r.unknown().isEmpty() ? "" : "  (not understood: " + r.unknown() + ")") + why);
         if (!ok) {
            bad++;
         }
      }
      System.out.println(bad == 0 ? "  OK — every common wish reads the same with no AI" : "  " + bad + " FAILED");
      return bad;
   }

   /**
    * DO THE WORKED EXAMPLES AGREE WITH THE PHRASE BOOK?
    *
    * Until 1.43.0 the prompt taught "blacksmith -> Village with Weaponsmith" in its phrase book and
    * its rules, and then showed a worked example answering "a village with a blacksmith" with
    * "Village with Armorer". A model copies examples more readily than it follows rules, so the same
    * wish came back as either house on different days — reported from the game as "I asked for a
    * weaponsmith and sometimes got the blast-furnace house".
    *
    * Read off the FINISHED prompt: every phrase-book word that appears in an example's wish must
    * resolve, in that example's answer, to the row the phrase book names. Then the parser's own
    * backstop is checked directly: a reply that still says Armorer for a plain "blacksmith" wish is
    * corrected to the weaponsmith.
    */
   private static int checkExamplesAgreeWithGlossary() {
      System.out.println();
      System.out.println("========== DO THE EXAMPLES CONTRADICT THE PHRASE BOOK? ==========");
      String prompt = WishParser.buildPrompt("x");
      java.util.Map<String, String> words = new java.util.LinkedHashMap<>();
      String feature = prompt.substring(prompt.indexOf("FEATURE WORDS"), prompt.indexOf("BIOME WORDS"));
      for (String line : feature.split("\n")) {
         int arrow = line.indexOf("  ->  " + Q);
         if (arrow < 0) {
            continue;
         }
         String label = line.substring(arrow + 7, line.lastIndexOf(Q));
         for (String w : line.substring(0, arrow).replaceAll("[(][^)]*[)]", "").split(",")) {
            String word = w.trim().toLowerCase(java.util.Locale.ROOT);
            if (word.length() > 2) {
               words.put(word, label);
            }
         }
      }
      int bad = 0;
      int checked = 0;
      String examples = prompt.substring(prompt.indexOf("Examples:"), prompt.indexOf("Allowed structure names:"));
      for (String line : examples.split("\n")) {
         int arrow = line.indexOf(" -> {");
         if (arrow < 0) {
            continue;
         }
         String wish = line.substring(0, arrow).toLowerCase(java.util.Locale.ROOT);
         String answer = line.substring(arrow);
         for (var e : words.entrySet()) {
            if (!java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(e.getKey()) + "\\b")
                  .matcher(wish).find()) {
               continue;
            }
            checked++;
            if (!answer.contains(Q + e.getValue() + Q)) {
               bad++;
               System.out.println("  FAIL " + wish.trim() + "  says \"" + e.getKey() + "\" but does not answer "
                     + e.getValue());
            }
         }
      }
      String armorerReply = "{" + Q + "structures" + Q + ":[{" + Q + "name" + Q + ":" + Q + "Village with Armorer"
            + Q + "}]," + Q + "biomes" + Q + ":[]}";
      WishParser.Outcome out = WishParser.parse(armorerReply, "a village with a blacksmith");
      boolean corrected = out.criteria() != null && out.criteria().structures.size() == 1
            && out.criteria().structures.get(0).building != null
            && VillageLayout.tokenMatches("weaponsmith", out.criteria().structures.get(0).building);
      System.out.println("  " + (corrected ? "ok   " : "FAIL ")
            + "a reply of Armorer for \"a village with a blacksmith\" is corrected to the weaponsmith");
      if (!corrected) {
         bad++;
      }
      // EXACT MODE AND A BUNDLE. Two items spread past the at-spawn radius is a refusal that names
      // the spread; one stray distance is still searched (clamped, with a note).
      boolean wasFast = com.fablevision.client.FableVisionConfig.seedFastMode;
      com.fablevision.client.FableVisionConfig.seedFastMode = false;
      try {
         String bundle = "{" + Q + "structures" + Q + ":[{" + Q + "name" + Q + ":" + Q + "Surface Village" + Q
               + "," + Q + "distance" + Q + ":250}]," + Q + "biomes" + Q + ":[{" + Q + "name" + Q + ":" + Q
               + "forest" + Q + "," + Q + "distance" + Q + ":200}]}";
         WishParser.Outcome refused = WishParser.parse(bundle, "a good survival seed");
         boolean ok1 = refused.criteria() == null && refused.error() != null && refused.error().contains("Fast")
               && refused.error().contains("Surface Village 250");
         System.out.println("  " + (ok1 ? "ok   " : "FAIL ") + "Exact mode refuses a spread-out bundle and names the spread");
         String single = "{" + Q + "structures" + Q + ":[{" + Q + "name" + Q + ":" + Q + "Surface Village" + Q
               + "," + Q + "distance" + Q + ":250}]}";
         WishParser.Outcome clamped = WishParser.parse(single, "a village");
         boolean ok2 = clamped.criteria() != null && clamped.note() != null && clamped.note().contains("Exact mode");
         System.out.println("  " + (ok2 ? "ok   " : "FAIL ") + "one stray distance is searched at spawn, and says so");
         bad += (ok1 ? 0 : 1) + (ok2 ? 0 : 1);
      } finally {
         com.fablevision.client.FableVisionConfig.seedFastMode = wasFast;
      }
      WishParser.Outcome kept = WishParser.parse(armorerReply, "a blacksmith, the armorer one");
      boolean keptArmorer = kept.criteria() != null && kept.criteria().structures.size() == 1
            && "armorer".equals(kept.criteria().structures.get(0).building);
      System.out.println("  " + (keptArmorer ? "ok   " : "FAIL ")
            + "but a wish that names the armorer keeps the armorer");
      if (!keptArmorer) {
         bad++;
      }
      System.out.println(bad == 0 ? "  OK — " + checked + " example phrases agree with the phrase book"
            : "  " + bad + " FAILED");
      return bad;
   }

   /** A double quote, built rather than escaped, because every string below is about quoted JSON
    *  and a source line of backslashes is unreadable for no gain. */
   private static final String Q = String.valueOf((char) 34);

   /**
    * DOES THE PROMPT TEACH ANY NAME THE VALIDATOR WOULD THEN REJECT?
    *
    * The prompt is assembled from a dozen pieces - the schema, the rules, the impossible list, the
    * phrase book, the biome glossary, eight worked examples, and the two allowed-name lists. Most
    * of those are filtered against the live catalog. "Most" is the problem: a name taught anywhere
    * in that text is a name the model will use, and a name outside the allowed lists comes back
    * through findRow as null and reaches the player as "that isn't something the search can look
    * for" - about a row the prompt itself had just demonstrated.
    *
    * That is the difference between a removed row being unlisted and being unreachable, and it is
    * not a difference any amount of care about one file can guarantee. So this reads the FINISHED
    * prompt, pulls out every name written where a structure name goes, and holds all of them to the
    * live allowed lists.
    *
    * The End is the standing example and gets its own check: the dimension is gone from the
    * catalog, so no End name may appear in the prompt at all.
    */
   private static int checkVocabulary() {
      System.out.println();
      System.out.println("========== DOES THE PROMPT TEACH ANYTHING WE DO NOT HAVE? ==========");
      String prompt = WishParser.buildPrompt("a good survival seed");
      java.util.Set<String> allowed = new java.util.HashSet<>();
      for (SeedCriteria.StructureTarget t : SeedCatalogCache.list()) {
         allowed.add(t.label);
      }
      java.util.Set<String> allowedBiomes = new java.util.HashSet<>();
      for (net.minecraft.resources.Identifier b : SeedCatalogCache.biomeList()) {
         allowedBiomes.add(b.getPath());
      }

      int bad = 0;
      // Every  "name":"X"  in the worked examples - the one place the prompt puts a name into the
      // model's mouth in the exact shape of an answer, and therefore the most imitable.
      String needle = Q + "name" + Q + ":" + Q;
      java.util.Set<String> taught = new java.util.LinkedHashSet<>();
      for (int i = prompt.indexOf(needle); i >= 0; i = prompt.indexOf(needle, i + 1)) {
         int from = i + needle.length();
         int to = prompt.indexOf(Q, from);
         if (to > from) {
            taught.add(prompt.substring(from, to));
         }
      }
      for (String name : taught) {
         // The schema itself is written in the same shape ("name":"<allowed structure name>"), and
         // a placeholder is not a name being taught - it is the slot the name goes in.
         if (name.startsWith("<")) {
            continue;
         }
         if (!allowed.contains(name) && !allowedBiomes.contains(name)) {
            System.out.println("  an example teaches " + Q + name + Q + ", which is in neither"
                  + " allowed list  <-- the validator would reject that answer");
            bad++;
         }
      }
      System.out.println("  names written into worked examples : " + taught.size()
            + (bad == 0 ? "   every one allowed  OK" : ""));

      // The phrase book maps player words onto rows. It is gated already; this is the assertion
      // that says so rather than trusting the gate.
      for (String line : WishParser.phraseBook()) {
         String label = line.substring(0, line.indexOf('\t'));
         if (!allowed.contains(label)) {
            System.out.println("  the phrase book teaches " + Q + label + Q
                  + ", which is not a catalog row  <-- BUG");
            bad++;
         }
      }

      // THE END: UNREACHABLE, NOT MERELY UNLISTED.
      //
      // The prompt DOES still say the words "End City" and "End Ship" - in the one paragraph whose
      // job is to tell the model it cannot have them, and in the impossible list that explains why.
      // That text has to stay: without it a player who asks for an elytra is told "that isn't
      // something the search can look for", which is true, useless, and indistinguishable from a
      // bug. Grepping the prompt for the word would therefore fail on exactly the sentence that
      // makes the refusal honest.
      //
      // What must be true is stronger and is checked directly: no End name can RESOLVE. Every
      // spelling a model might invent is put through the same validator a real reply goes through,
      // and none of them may come back as a row.
      String[] invented = {"End City", "End City with Ship", "End Ship", "The End", "End Highlands",
            "Outer End Island", "end_highlands", "end_barrens", "the_end"};
      for (String name : invented) {
         String reply = "{" + Q + "structures" + Q + ":[{" + Q + "name" + Q + ":" + Q + name + Q
               + "}]," + Q + "biomes" + Q + ":[{" + Q + "name" + Q + ":" + Q + name + Q + "}]}";
         WishParser.Outcome out = WishParser.parse(reply, "find me " + name);
         boolean resolved = out.criteria() != null
               && (!out.criteria().structures.isEmpty() || !out.criteria().biomes.isEmpty());
         if (resolved) {
            System.out.println("  " + Q + name + Q + " RESOLVED to a search  <-- the End is reachable");
            bad++;
         }
      }
      System.out.println("  invented End names put through the validator : " + invented.length
            + (bad == 0 ? "   none resolved  OK" : ""));
      for (String label : allowed) {
         String low = label.toLowerCase(java.util.Locale.ROOT);
         if (low.contains("end city") || low.startsWith("end ")) {
            System.out.println("  catalog row " + Q + label + Q + " is an End row  <-- BUG");
            bad++;
         }
      }
      for (String path : allowedBiomes) {
         if (path.startsWith("end_") || path.equals("the_end") || path.equals("small_end_islands")) {
            System.out.println("  allowed biome " + Q + path + Q + " is an End biome  <-- BUG");
            bad++;
         }
      }

      // HOW BIG THE PROMPT IS, printed rather than assumed. Every wish carries the whole of it, so
      // it is the floor under every request the feature makes and the number to look at first when
      // a provider starts refusing or truncating.
      System.out.println("  prompt size : " + prompt.length() + " characters ("
            + (prompt.length() / 4) + " tokens, roughly) before the player's own words");
      System.out.println(bad == 0
            ? "  nothing in the prompt names anything the search cannot do  OK"
            : "  " + bad + " problem(s)");
      System.out.println("===================================================================");
      return bad;
   }

   /**
    * REPLIES THAT ARE NOT ONLY JSON — the half of the wish path that had never been checked.
    *
    * Every case above is a well-formed object, because that is what the validator was written
    * against. What broke in 1.41.2 was upstream of all of it: the default model then (Groq's) reasoned out
    * loud, so "BEST survival seed" came back as several paragraphs of working out with the object
    * somewhere inside, and the extractor — first "{" to last "}" — spanned from a brace in the
    * prose into the answer and produced "Couldn't read the AI's answer". Not one case here could
    * have caught that, because not one of them was a reply a real model sends.
    *
    * So this checks the SHAPES, including the three that actually happen: thinking then answer, a
    * discarded draft then the real one, and a reply cut off mid-object. It also checks what the
    * failures SAY, because "couldn't read it" for four different problems was the second half of
    * the bug — a player who cannot see whether the model refused, rambled or ran out of tokens has
    * nothing to act on.
    */
   private static int checkReplyShapes() {
      // The sample reply is a spread-out bundle, which is a FAST-mode search: Exact mode refuses
      // bundles outright (checked separately below), and this section is about reading replies.
      boolean wasFast = com.fablevision.client.FableVisionConfig.seedFastMode;
      com.fablevision.client.FableVisionConfig.seedFastMode = true;
      try {
         return checkReplyShapesFast();
      } finally {
         com.fablevision.client.FableVisionConfig.seedFastMode = wasFast;
      }
   }

   private static int checkReplyShapesFast() {
      record Shape(String what, String reply, boolean expectSearch, String mustSay) {}
      String wish = "{\"structures\":[{\"name\":\"Surface Village\",\"distance\":250},"
            + "{\"name\":\"Ruined Portal\",\"distance\":350}],"
            + "\"biomes\":[{\"name\":\"plains\",\"size\":\"any\",\"distance\":200}],"
            + "\"adjacent\":[],\"exclude\":[],\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}";

      List<Shape> shapes = List.of(
            new Shape("the plain case: nothing but the object", wish, true, null),
            // THE 1.41.2 BUG, exactly as it arrived.
            new Shape("a reasoning model: <think> … </think> then the answer",
                  "<think>\nOkay, they want the BEST survival seed. A good survival spawn needs wood, "
                  + "food and early iron. I should put {village, portal} in the structures array and a "
                  + "plains biome. Wait — let me reconsider the distances.\n</think>\n" + wish,
                  true, null),
            // Some providers strip the opening tag on the way out and leave the closing one.
            new Shape("reasoning with the opening tag already stripped by the provider",
                  "The player asked for a survival seed, so I want a village {and} a portal.</think>" + wish,
                  true, null),
            // A draft the model then thought better of. "First object that parses" would take the
            // empty one and report the wish as unsearchable.
            new Shape("a discarded draft object before the real one",
                  "Maybe just {\"structures\":[],\"biomes\":[]} — no, that is too thin for a survival "
                  + "spawn. Better:\n" + wish,
                  true, null),
            new Shape("fenced in markdown, as models often do",
                  "Here is the search:\n```json\n" + wish + "\n```", true, null),
            // Cut off before the object closed: the reasoning ate the token budget.
            new Shape("cut off mid-object (ran out of output tokens)",
                  "{\"structures\":[{\"name\":\"Surface Village\",\"dist", false, "didn't answer with a search"),
            // THE 1.41.3 REPORT: "best survival seed" still failed, saying "It said: json". This is
            // that reply. The model opened a markdown fence, started the object, and was cut off by
            // the token limit — so the first line of the reply is "```json" and quoting it back
            // describes nothing. AiVision marks a truncated answer and the parser now says so.
            new Shape("a fenced reply cut off by the token limit",
                  "```json\n{\"structures\":[{\"name\":\"Surface Village\",\"distance\":250},"
                  + "{\"name\":\"Ruined Por"
                  + "\n⚠ (GPT hit its output limit — this answer is cut off.)",
                  false, "cut off"),
            // The same truncation without the marker, which is what a provider that does not report
            // finish_reason would give: it still must not claim the reply was not a search attempt.
            new Shape("fenced and truncated, provider gave no finish_reason",
                  "```json\n{\"structures\":[{\"name\":\"Surface Village\",\"distance\":250},",
                  false, "didn't answer with a search"),
            new Shape("prose, no JSON at all",
                  "I'd be happy to help you find a great survival seed! What biome do you prefer?",
                  false, "I'd be happy to help"),
            // A provider error must reach the player as the provider's own words.
            new Shape("a provider error passed through",
                  "⚠ GPT 400: json_validate_failed", false, "GPT 400"),
            new Shape("the token-limit report AiVision now writes",
                  "⚠ GPT ran out of output tokens before writing an answer (it spent them on "
                  + "internal reasoning) — finish_reason: length.",
                  false, "finish_reason: length"),
            // THE SAME TRUNCATION FROM THE OTHER TWO PROVIDERS, because until now only the
            // OpenAI-shaped ones could report it. Claude spells the ceiling "max_tokens" and Gemini
            // "MAX_TOKENS", and neither was detected at all — a Claude reply cut off mid-object
            // arrived here indistinguishable from a model that had answered in prose, and Claude's
            // budget was hardcoded at 1024 so it was cut off often. Three providers, three
            // spellings, one marker; these two cases are what keeps them in step.
            new Shape("a fenced reply cut off by Claude's token limit",
                  "```json\n{\"structures\":[{\"name\":\"Surface Village\",\"distance\":250},"
                  + "{\"name\":\"Ruined Por"
                  + "\n⚠ (Claude hit its output limit — this answer is cut off.)",
                  false, "cut off"),
            new Shape("a fenced reply cut off by Gemini's token limit",
                  "```json\n{\"structures\":[{\"name\":\"Surface Village\",\"distance\":250},"
                  + "\n⚠ (Gemini hit its output limit — this answer is cut off.)",
                  false, "cut off"));

      System.out.println();
      System.out.println("========== REPLIES THAT ARE NOT ONLY JSON ==========");
      int failed = 0;
      for (Shape s : shapes) {
         WishParser.Outcome out = WishParser.parse(s.reply(), "best survival seed");
         boolean gotSearch = out.criteria() != null;
         String said = gotSearch ? out.criteria().summary() : out.error();
         boolean ok = gotSearch == s.expectSearch()
               && (s.mustSay() == null || (said != null && said.contains(s.mustSay())));
         if (!ok) {
            failed++;
         }
         System.out.println("  " + (ok ? "ok   " : "FAIL ") + s.what());
         System.out.println("        -> " + (gotSearch ? "searches: " : "no search: ") + said);
         if (!ok) {
            System.out.println("        expected " + (s.expectSearch() ? "a search" : "no search")
                  + (s.mustSay() == null ? "" : " mentioning \"" + s.mustSay() + "\""));
         }
      }

      // ── THE END IS NOT SEARCHABLE, checked from the PLAYER'S words ────────────────────────────
      // The catalog has no End rows any more, so a model asked for one can only invent a name and
      // have it rejected as unrecognised — which reports "that isn't something the search can look
      // for" and tells somebody asking about the elytra nothing. This is the check that the wish
      // path says WHY, and that it still searches everything else in the same breath.
      System.out.println();
      System.out.println("========== A WISH THAT MENTIONS THE END ==========");
      record EndCase(String wish, String reply, boolean expectSearch) {}
      List<EndCase> endCases = List.of(
            new EndCase("an end city with the elytra ship and a village near spawn",
                  "{\"structures\":[{\"name\":\"Surface Village\"}],\"biomes\":[],\"adjacent\":[],"
                  + "\"exclude\":[],\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}", true),
            // The model inventing the row anyway: it is rejected as unrecognised AND the End note
            // still leads, so the player learns the real reason rather than a generic one.
            new EndCase("find me an End City",
                  "{\"structures\":[{\"name\":\"End City with Ship\"}],\"biomes\":[],\"adjacent\":[],"
                  + "\"exclude\":[],\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}", false),
            new EndCase("a seed with a good elytra spot and a jungle",
                  "{\"structures\":[],\"biomes\":[{\"name\":\"jungle\",\"size\":\"any\"}],"
                  + "\"adjacent\":[],\"exclude\":[],\"without\":[],\"stronghold_mentioned\":false,"
                  + "\"cant\":\"\"}", true));
      for (EndCase c : endCases) {
         WishParser.Outcome out = WishParser.parse(c.reply(), c.wish());
         boolean gotSearch = out.criteria() != null;
         String note = gotSearch ? out.note() : out.error();
         boolean saysWhy = note != null && note.contains("End isn't searchable");
         boolean ok = gotSearch == c.expectSearch() && saysWhy;
         if (!ok) {
            failed++;
         }
         System.out.println("  " + (ok ? "ok   " : "FAIL ") + "\"" + c.wish() + "\"");
         System.out.println("        -> " + (gotSearch ? "searches: " + out.criteria().summary()
               : "no search") + "\n        -> says: " + note);
         if (!ok) {
            System.out.println("        expected " + (c.expectSearch() ? "a search" : "no search")
                  + " and an explanation naming the End");
         }
      }

      System.out.println();
      System.out.println("========== THE REASONING STRIPPER (AiVision) ==========");
      record Strip(String what, String in, String expect) {}
      List<Strip> strips = List.of(
            new Strip("a matched pair is removed", "<think>hmm, maybe not</think>The answer.", "The answer."),
            new Strip("a dangling close takes everything before it with it",
                  "let me reconsider</think>The answer.", "The answer."),
            new Strip("a dangling open means there IS no answer yet",
                  "The answer is<think>wait, actually", "The answer is"),
            new Strip("nothing but reasoning leaves nothing", "<think>still thinking", ""),
            new Strip("a reply with no tags is untouched", "Just an answer.", "Just an answer."));
      for (Strip s : strips) {
         String got = com.tensec.aiscreen.AiVision.stripReasoning(s.in());
         boolean ok = got.equals(s.expect());
         if (!ok) {
            failed++;
         }
         System.out.println("  " + (ok ? "ok   " : "FAIL ") + s.what());
         if (!ok) {
            System.out.println("        got \"" + got + "\", expected \"" + s.expect() + "\"");
         }
      }
      System.out.println();
      System.out.println(failed == 0 ? "  OK — every reply shape handled" : "  " + failed + " FAILED");
      System.out.println("====================================================");
      return failed;
   }

   /**
    * The coverage map: every search that exists, every phrase mapped to it, and — the part that
    * actually matters — every search row that has NO phrase pointing at it.
    *
    * A row with no phrase is not necessarily a gap. "Desert Pyramid" needs no phrase book entry
    * because the words a player types ARE the label. A row like "Outpost with Allay Cage" does,
    * because nobody types that. So the third column names which kind each row is, and the rule is
    * mechanical rather than a judgement: a row that demands a PIECE is a feature nobody can be
    * expected to name exactly, and a row that demands nothing is its own plain English.
    */
   private static void coverageMap() {
      List<SeedCriteria.StructureTarget> catalog = SeedCatalogCache.list();
      List<String> book = WishParser.phraseBook();

      System.out.println("========== 1. THE FULL PHRASE BOOK (what the AI is taught) ==========");
      System.out.println("  " + book.size() + " entries, all checked against the live catalog");
      System.out.println();
      for (String line : book) {
         String[] parts = line.split("\t", 2);
         System.out.println("  " + parts[0]);
         System.out.println("      " + parts[1]);
      }

      System.out.println();
      System.out.println("========== 2. EVERY SEARCH THAT EXISTS ==========");
      System.out.println("  " + pad("ROW", 36) + pad("DEMANDS", 40) + "PHRASE?");
      int withPiece = 0;
      int piecesUnphrased = 0;
      for (SeedCriteria.StructureTarget t : catalog) {
         boolean phrased = book.stream().anyMatch(l -> l.startsWith(t.label + "\t"));
         // A token is not always a FEATURE. End City and Nether Fossil carry one purely to prove
         // the structure really generated — measured, only about a quarter of confirmed End slots
         // and half of confirmed fossil slots actually contain one — so their label already is the
         // player's word for them and there is nothing extra to teach. Flagging those two as
         // "feature row with no phrase" every run trains whoever reads this to ignore the line,
         // which is the only thing that would make it useless when a real gap appears.
         // Derived, not listed: an existence proof is a row that carries a token and has no
         // tokenless sibling covering the same structures. "Capsized Shipwreck" has plain
         // "Shipwreck" beside it, so its token really is a feature; "End City" has no plain row at
         // all, because without the token it would send people to empty sky.
         boolean existenceProof = t.building != null && catalog.stream()
               .noneMatch(o -> o.building == null && o.wanted.equals(t.wanted));
         String demands = t.building != null ? "piece: " + t.building
               : t.zombie != SeedCriteria.ZombieMode.ANY ? "a zombie/abandoned start"
               : t.special != SeedCriteria.Special.NORMAL ? "(" + t.special + ")"
               : "the whole structure";
         if (t.building != null && !existenceProof) {
            withPiece++;
            if (!phrased) {
               piecesUnphrased++;
            }
         }
         System.out.println("  " + pad(t.label, 36) + pad(demands, 40)
               + (phrased ? "yes"
                     : existenceProof ? "not needed - the token only PROVES it generated"
                     : t.building == null ? "not needed - the label IS the words"
                     : "NO  <-- feature row with no phrase"));
      }
      System.out.println();
      System.out.println("  rows total                    : " + catalog.size());
      System.out.println("  rows that demand a piece      : " + withPiece);
      System.out.println("  of those, with no phrase      : " + piecesUnphrased);

      // The negatives are DERIVED from each row's own label rather than typed anywhere, which is
      // cheap and correct right up until a row is named something the rule reads badly. Printing
      // every one is how a sentence like "Capsized without Shipwreck" gets caught before a player
      // reads it in the picker.
      System.out.println();
      System.out.println("========== 3. WHAT \"MUST NOT HAVE\" TURNS EACH ROW INTO ==========");
      System.out.println("  " + pad("ROW", 36) + pad("BECOMES", 40) + "HOW");
      int flips = 0;
      int excludes = 0;
      for (SeedCriteria.StructureTarget t : catalog) {
         if (t.special != SeedCriteria.Special.NORMAL) {
            continue;
         }
         SeedCriteria.StructureTarget neg = t.negated();
         if (neg == null) {
            System.out.println("  " + pad(t.label, 36) + pad("no " + t.label + " near spawn", 40)
                  + (t.building != null ? "exclusion, piece test included"
                        : "whole-structure exclusion"));
            excludes++;
            continue;
         }
         flips++;
         System.out.println("  " + pad(t.label, 36) + pad(neg.label, 40)
               + (neg.building != null ? "piece test flipped" : "start-piece test flipped"));
      }
      System.out.println();
      System.out.println("  rows with a real \"without\"    : " + flips);
      System.out.println("  rows that exclude instead     : " + excludes);
      System.out.println("====================================================");
   }

   /**
    * Every structure THE GAME has, against every structure the picker searches.
    *
    * The catalog is a hand-written list resolved against the registry, and a spec whose structure
    * it cannot find is skipped in silence — which is right (a datapack world should not show rows
    * it cannot deliver) and dangerous (a row can disappear and nobody notices). Reading the
    * registry directly is the only way to tell "we chose not to search this" from "we meant to and
    * it quietly fell out".
    */
   private static void registryCoverage(WorldgenContext ctx) {
      List<SeedCriteria.StructureTarget> catalog = SeedCatalogCache.list();
      java.util.Set<Identifier> covered = new java.util.TreeSet<>(
            java.util.Comparator.comparing(Identifier::toString));
      for (SeedCriteria.StructureTarget t : catalog) {
         covered.addAll(t.wanted);
      }

      java.util.Map<Identifier, String> all = new java.util.TreeMap<>(
            java.util.Comparator.comparing(Identifier::toString));
      for (Holder.Reference<StructureSet> setRef : ctx.allSets) {
         String placement = setRef.value().placement().getClass().getSimpleName();
         for (StructureSet.StructureSelectionEntry e : setRef.value().structures()) {
            e.structure().unwrapKey().ifPresent(k -> all.put(k.identifier(), placement));
         }
      }

      System.out.println();
      System.out.println("========== 3. EVERY STRUCTURE THE GAME HAS vs WHAT WE SEARCH ==========");
      System.out.println("  " + pad("STRUCTURE ID", 42) + pad("PLACEMENT", 36) + "SEARCHED?");
      int missing = 0;
      for (var e : all.entrySet()) {
         boolean has = covered.contains(e.getKey());
         if (!has) {
            missing++;
         }
         System.out.println("  " + pad(e.getKey().toString(), 42) + pad(e.getValue(), 36)
               + (has ? "yes" : "NO  <-- no row searches this"));
      }
      System.out.println();
      System.out.println("  structures in the registry : " + all.size());
      System.out.println("  searched by some row       : " + (all.size() - missing));
      System.out.println("  NOT searched by any row    : " + missing);
      System.out.println("=======================================================================");
   }

   private static String pad(String s, int width) {
      StringBuilder sb = new StringBuilder(s);
      while (sb.length() < width) {
         sb.append(' ');
      }
      return sb.toString();
   }

   private WishDiagnostic() {
   }
}
