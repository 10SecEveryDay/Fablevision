package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.fablevision.client.FableVisionConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/**
 * The AI side of the create-world wish flow — with a hard safety split:
 * the AI's ONLY job is translating the player's words into a JSON pick from lists WE
 * provide. Everything it returns is validated against the real catalog here; the seed
 * itself always comes from {@link SeedFinder}'s legit game-code search, never the AI.
 *
 * That split is the whole design and it does not bend for content predicates. The AI may say
 * "this wish means the Igloo with Basement row"; it may never say "seed 12345 has a basement".
 * Whether the basement is really there is decided by {@link SeedCriteria.StructureTarget#layoutMatches},
 * which assembles the structure and reads its piece list, on every candidate, in both Fast and
 * Exact mode. A wrong translation costs the player the wrong SEARCH; it can never cost them a
 * wrong ANSWER.
 */
public final class WishParser {
   /** Either a usable criteria or a human-readable error — never both. {@code note} is
    *  the AI's honest "this part can't be seed-searched" flag, shown while searching. */
   public record Outcome(SeedCriteria criteria, String error, String note) {}

   /**
    * The honest answer when a wish mentions a stronghold or its portal.
    *
    * Both used to be searchable-looking and neither was real. A stronghold is ring-placed
    * ~1280-2816 blocks out, so no seed has one near spawn and "find the nearest" describes every
    * seed equally — the old row asked for one within 8000 blocks, which nothing fails, so the
    * search stopped on the first seed it looked at. The eye count is a property of that same
    * unreachable portal. Neither is a thing a seed search can improve, so the wish now says so
    * plainly and searches whatever else was asked for.
    */
   public static final String STRONGHOLD_CANT =
         "🧱 Strongholds can't be optimised — every seed has one ~1300+ blocks out and none are ever "
         + "near spawn, so there's nothing to search for. Searching the rest of your wish.";

   /**
    * The marker that makes a note an EXPLANATION rather than an apology.
    *
    * Every note used to be printed under "couldn't check:", which is right for a real limitation and
    * actively wrong for a note that describes a search that worked. Asking for a blacksmith is the
    * case that forced this: the app finds the exact building the player means, and answering with
    * "couldn't check: there's no blacksmith building any more" reads as a refusal of a request that
    * in fact succeeded.
    *
    * A note starting with this is printed as a plain line. The marker is stripped before display.
    */
   public static final String PLAIN_NOTE = "ℹ ";

   /** {@code note} with the {@link #PLAIN_NOTE} marker taken off, for a caller that supplies its
    *  own styling. The marker is a routing flag, not something a player should ever read. */
   public static String stripPlainNote(String note) {
      return note != null && note.startsWith(PLAIN_NOTE) ? note.substring(PLAIN_NOTE.length()) : note;
   }

   /**
    * Said whenever a wish mentions the End, whatever the AI came back with.
    *
    * CHECKED AGAINST THE PLAYER'S OWN WORDS, not against the reply, and that is the point. The
    * prompt tells the model the End is out of scope and the catalog no longer contains a single End
    * row, so in the normal case the model simply leaves it alone and says so itself. This is for the
    * case where it does not: a model that invents "End City" gets the row rejected as unrecognised,
    * and the message for an unrecognised row is "that isn't something the search can look for",
    * which is true and tells a player asking about the elytra nothing about why.
    *
    * The wording is deliberately about what the app WILL NOT claim rather than what it cannot be
    * bothered to do — the reason the End was dropped is that its answers could not be trusted, and
    * a player who was previously sent to an empty island is owed that sentence.
    */
   public static final String END_CANT =
         "The End isn't searchable — cities, the ship, the elytra. A structure placed there doesn't "
         + "always really generate, so any coordinate would be a guess. The rest was searched.";

   /**
    * The phrase {@link com.tensec.aiscreen.AiVision} appends to an answer the provider cut short.
    *
    * A shared constant rather than two matching strings, because the two ends of it are in different
    * packages and the failure mode of them drifting apart is silent: the wish would go back to
    * reporting a truncated reply as "the AI didn't answer with a search", which is exactly the
    * misdiagnosis this exists to prevent.
    */
   public static final String CUT_OFF_MARKER = "hit its output limit";

   /** Words that mean the player is asking about the End. Kept broad on purpose: a false positive
    *  adds one honest sentence, a false negative sends somebody looking for an elytra. */
   private static final String[] END_WORDS = {
      "the end", "end city", "end cities", "endcity", "end ship", "endship", "elytra", "shulker",
      "purpur", "chorus", "ender dragon", "end island", "end islands", "outer island", "end biome",
      "end highlands", "end midlands", "end barrens", "end portal room", "the void"};

   /**
    * Said once when someone asks for a blacksmith, because the honest answer is good news.
    *
    * blacksmithDiag censused all 483 village templates this version ships: exactly four contain
    * lava and all four are weaponsmith houses. So the building people picture when they say
    * "blacksmith" — the lava pit with the loot chest — is the weaponsmith's, and that is what gets
    * searched for. The old wording led with what no longer exists; this leads with what was found.
    * (This comment sat above END_CANT's, documenting the wrong constant, until 1.44.2.)
    */
   public static final String BLACKSMITH_NOTE = PLAIN_NOTE
         + "\"Blacksmith\" is the weaponsmith's house in this version — the one with the lava and the "
         + "loot chest. That's what was searched for. (The armorer's blast furnace and the "
         + "toolsmith's smithing table are separate buildings you can ask for by name.)";

   /**
    * Player words → the catalog row that answers them.
    *
    * Why this is typed out rather than derived: every other name in this project comes from live
    * data because the question is "what does this version call that template", and guessing is how
    * you end up with a token like {@code tool_smith} that matches nothing. This table answers a
    * different question — "what might a person type" — and nothing in the registry knows that a
    * player says "upside down ship" for {@code upsidedown_*} or "blacksmith" for the armorer.
    *
    * It is still gated on live data at the one point that matters: {@link #buildPrompt} drops any
    * line whose {@code label} is not in the catalog this world actually built. So a row that is
    * renamed or removed silently stops being taught instead of teaching the AI a name the
    * validator will then reject.
    */
   private record Phrase(String label, String words) {}

   private static final List<Phrase> GLOSSARY = List.of(
         new Phrase("Igloo with Basement",
               "basement, secret basement, lab, the trapdoor under the carpet, hidden room in an igloo, igloo with a zombie villager"),
         // "blacksmith" is the one phrase here that names a building the game no longer has. The
         // pre-1.14 blacksmith — the hut with the lava pit — was split into the armorer, the
         // weaponsmith and the toolsmith, and only the armorer has a search built. Mapping the word
         // is right; letting someone walk off looking for the old building is not, so the rule
         // below makes the AI say which one it actually found.
         new Phrase("Village with Armorer",
               "armorer, armour smith, the blast furnace house"),
         // "blacksmith" moved here in 1.37.2, from the armorer, because the TEMPLATES say so.
         // blacksmithDiag censused all 483 village templates in this version: exactly four contain
         // lava, and all four are weaponsmith houses (plains_weaponsmith_1 is lava x2, chest x1,
         // furnace x2, grindstone x1). The armorer's house is a blast furnace and usually not even
         // a chest. Someone who types "blacksmith" is picturing the pre-1.14 hut with the lava pit
         // and the loot chest, and that building's descendant is the weaponsmith's.
         // The other five professions. "librarian" and "toolsmith" are the words a player uses and
         // neither is the asset spelling (library / tool_smith) — which is exactly why the phrase
         // book exists: it translates player language, and the catalog holds the derived token.
         new Phrase("Village with Toolsmith",
               "toolsmith, tool smith, smithing table, the tool guy"),
         new Phrase("Village with Weaponsmith",
               "weaponsmith, weapon smith, grindstone house, blacksmith, smithy, forge, "
               + "the lava pit house, the hut with the lava and the chest"),
         new Phrase("Village with Butcher",
               "butcher, butcher shop, meat shop, smoker house"),
         new Phrase("Village with Librarian",
               "librarian, library, bookshop, lectern house, enchanted book villager"),
         new Phrase("Village with Cartographer",
               "cartographer, map maker, mapmaker, cartography table, the map guy"),
         new Phrase("Village with Stables",
               "village stables, horse stable in a village, hay barn (PLAINS villages only — "
               + "not the hoglin stable bastion and not the trail-ruins stables)"),
         new Phrase("Village with Mason",
               "mason, stonemason, stonecutter house, the stone guy"),
         new Phrase("Village with Leatherworker",
               "leatherworker, leather worker, tannery, the leather guy"),
         new Phrase("Village with Fletcher",
               "fletcher, arrow maker, fletching table, the bow guy"),
         new Phrase("Village with Shepherd",
               "shepherd, loom house, the wool guy, sheep farmer"),
         new Phrase("Village with Fisherman",
               "fisherman, fisher, fisher cottage, the fish guy"),
         new Phrase("Abandoned Village",
               "zombie village, abandoned village, cobweb village, ruined village"),
         new Phrase("Giant Ruined Portal",
               "giant portal, big portal, huge portal, massive portal, large ruined portal"),
         // WHERE the portal sits, which is the complaint behind most "the portal was useless"
         // wishes: it was underwater or sunk in sand. Each is a plain structure filter, so unlike
         // the Giant row these cost nothing and can be combined freely.
         new Phrase("Surface Ruined Portal",
               "portal on the surface, above ground portal, a portal I can see, a portal I can walk "
               + "to, not a buried portal, portal on land, visible ruined portal"),
         new Phrase("Buried Ruined Portal",
               "buried portal, portal in the sand, half-buried portal, desert portal, sunken portal"),
         new Phrase("Underwater Ruined Portal",
               "underwater portal, portal in the ocean, portal on the sea bed, drowned portal, "
               + "swamp portal"),
         new Phrase("Outpost with Cages",
               "cages, pens, the cages by the outpost tower, caged mob at an outpost"),
         new Phrase("Outpost with Allay Cage",
               "allay, allays, allay cage, outpost with allays"),
         new Phrase("Capsized Shipwreck",
               "upside down ship, upside-down shipwreck, capsized, flipped ship, overturned wreck"),
         new Phrase("Large Ocean Ruin",
               "big ocean ruin, large underwater ruin, big underwater building"),
         new Phrase("Warm Ocean Ruins", "warm ocean ruins, sandstone underwater ruins"),
         new Phrase("Cold Ocean Ruins", "cold ocean ruins, stone-brick underwater ruins"),
         new Phrase("Trial Chamber with Eruption Room",
               "eruption room, eruption chamber, the room that erupts, breeze room"),
         new Phrase("Trial Chamber with Slanted Room",
               "slanted room, sloped room, ramp room, the big sloping chamber"),
         new Phrase("Trial Chamber with Encounter Hall",
               "encounter hall, encounter hallway, the wide fighting corridor"),
         // The mansion rooms. Every phrase here is a player's word for a room the game gives no
         // name to at all — the templates are called 1x1_as3 and 2x2_s1 — so this table is doing
         // more work for the mansion than for anything else in the catalog.
         new Phrase("Mansion with Secret Room",
               "secret room, hidden room, mansion secret room, the walled-off room, the room with "
               + "the diamond block, diamond room (the COMMON one, 1 mansion in 8 — use this unless "
               + "the wish clearly wants the big lava one)"),
         new Phrase("Mansion with Lava Vault",
               "big secret room, lava room, obsidian room, the glass and lava room, the huge hidden "
               + "room (RARE — 1 mansion in 50, a very long search)"),
         new Phrase("Mansion with TNT Trap",
               "tnt room, trap room, trapped chest room, the mansion trap, silverfish room"),
         new Phrase("Mansion with Spawner",
               "mansion spawner, cobweb room, the spider room, the room full of cobwebs"),
         new Phrase("Ancient City with Sauna",
               "sauna, steam room, the sealed side room in an ancient city"),
         new Phrase("Treasure Bastion",
               "treasure bastion, gold bastion, the lava-basin bastion, the one with the gold blocks"),
         new Phrase("Bridge Bastion", "bridge bastion, the bastion with the long walkway"),
         new Phrase("Hoglin Stable Bastion",
               "hoglin stable, hoglin bastion, the bastion with the crimson pens (NETHER — not trail ruins)"),
         new Phrase("Housing Units Bastion",
               "housing units bastion, generic bastion, the blocky stacked-rooms bastion"),
         new Phrase("Trail Ruins with Building Group",
               "building group, house group, the multi-part houses in trail ruins"),
         new Phrase("Trail Ruins with Stables",
               "stables in trail ruins, the stable buildings by the trail-ruins tower (OVERWORLD — not the bastion)"));

   /**
    * The same idea for BIOMES, and it became necessary the moment "next to" and "big" arrived.
    *
    * A structure row is named more or less the way a player says it, so the old prompt could get by
    * handing the model a list of labels. A biome is not: the id is {@code dark_forest} and people
    * say "dark oak", the id is {@code cherry_grove} and people say "cherry blossom", the id is
    * {@code badlands} and half the players still say "mesa". Those exact words are the ones in the
    * wishes this feature exists for — "a big pale garden next to dark oak" names two biomes and
    * neither by its id — so without this table the model has to guess, and a guess that misses ends
    * up in "cant", which is precisely the failure this round is meant to remove.
    *
    * Gated on the live biome list by {@link #buildPrompt}, exactly like the structure glossary: a
    * line for a biome this world does not have would teach a name the validator then rejects.
    */
   private record BiomeWords(String path, String words) {}

   private static final List<BiomeWords> BIOME_GLOSSARY = List.of(
         new BiomeWords("cherry_grove", "cherry blossom, blossom, cherry, cherry trees, pink trees, sakura, pink forest"),
         new BiomeWords("dark_forest", "dark oak, dark oak forest, roofed forest, the mansion forest, dark woods"),
         new BiomeWords("pale_garden", "pale garden, pale oak, pale forest, creaking forest, the grey forest, eyeblossom"),
         new BiomeWords("flower_forest", "flower forest, flowers, flowery woods, bee biome"),
         new BiomeWords("sunflower_plains", "sunflowers, sunflower field"),
         new BiomeWords("old_growth_birch_forest", "tall birch forest, big birch forest, old growth birch"),
         new BiomeWords("old_growth_pine_taiga", "mega taiga, giant pines, tall pine forest"),
         new BiomeWords("old_growth_spruce_taiga", "mega spruce taiga, giant spruce forest"),
         new BiomeWords("sparse_jungle", "jungle edge, sparse jungle, thin jungle"),
         new BiomeWords("bamboo_jungle", "bamboo, bamboo forest, panda biome"),
         new BiomeWords("mangrove_swamp", "mangrove, mangroves, mud swamp, muddy swamp"),
         new BiomeWords("swamp", "swamp, marsh, witch hut biome, slime biome"),
         new BiomeWords("badlands", "mesa, badlands, red sand canyon"),
         new BiomeWords("wooded_badlands", "wooded mesa, badlands with trees"),
         new BiomeWords("eroded_badlands", "bryce, eroded mesa, hoodoos, the spiky mesa"),
         new BiomeWords("windswept_savanna", "shattered savanna, windswept savanna, broken savanna"),
         new BiomeWords("savanna_plateau", "savanna plateau, high savanna"),
         new BiomeWords("jagged_peaks", "mountains, peaks, tall mountains, jagged peaks, high mountains"),
         new BiomeWords("frozen_peaks", "icy peaks, frozen mountains"),
         new BiomeWords("stony_peaks", "stone mountains, bare peaks"),
         new BiomeWords("snowy_slopes", "snowy mountains, ski slopes, snowy hillside"),
         new BiomeWords("meadow", "meadow, alpine meadow, flowery hilltop"),
         new BiomeWords("grove", "grove, snowy forest hill"),
         new BiomeWords("ice_spikes", "ice spikes, spike biome, the icy spike field"),
         new BiomeWords("snowy_plains", "snow, snowy plains, tundra, snowfield"),
         new BiomeWords("warm_ocean", "coral reef, coral, tropical ocean, warm ocean"),
         new BiomeWords("deep_dark", "deep dark, sculk, warden biome, the ancient city biome"),
         new BiomeWords("lush_caves", "lush caves, azalea, glow berry caves"),
         new BiomeWords("dripstone_caves", "dripstone, stalactites, drip caves"),
         new BiomeWords("stony_shore", "rocky shore, stone beach, cliffs by the sea"));

   /**
    * One thing no seed search can answer: what a player asks for, WHY it cannot be answered, and
    * the nearest thing that can.
    *
    * The three parts are the whole point, and the third is the one that was missing. "Can't search
    * chest loot, searching the rest" is true and useless — it tells a player their wish half
    * failed and leaves them with nothing to do about it. "Loot is rolled when you open the chest,
    * not fixed by the seed — but I can find a village with a weaponsmith, which is early gear you
    * can actually count on" answers the question behind the question. Almost nobody wants a chest;
    * they want what they hoped was in it.
    *
    * {@code rows} names the catalog rows the suggestion depends on. It is checked against the live
    * catalog before the line is taught, for exactly the reason the phrase book is: a suggestion
    * naming a row this world did not build would send a player to a search that does not exist,
    * which is a worse failure than the flat note it replaces. A line whose rows are all missing
    * still teaches the WHY — that part depends on nothing.
    */
   private record Impossible(String what, String why, String instead, List<String> rows) {
      Impossible(String what, String why) {
         this(what, why, "", List.of());
      }
   }

   private static final List<Impossible> IMPOSSIBLE = List.of(
         new Impossible("what is inside any chest, barrel or shulker",
               "the loot is rolled when the container generates, long after the seed is fixed",
               "a village with a weaponsmith, toolsmith or armorer — an early-gear building whose "
               + "PRESENCE really is fixed by the seed",
               List.of("Village with Weaponsmith", "Village with Toolsmith", "Village with Armorer")),
         new Impossible("trial chamber vault or reward-vault loot",
               "vault contents are decided when you spend a key, not when the world is made",
               "a trial chamber with one of the rare rooms — the eruption room, the slanted room or "
               + "the encounter hall — which is a real reason to pick one chamber over another",
               List.of("Trial Chamber with Eruption Room", "Trial Chamber with Slanted Room",
                     "Trial Chamber with Encounter Hall")),
         new Impossible("what suspicious sand or gravel gives up when brushed",
               "brushing rolls the item then and there; the seed only fixes where the sand is",
               "trail ruins with the rare multi-part building groups, which is where the most "
               + "suspicious gravel is",
               List.of("Trail Ruins with Building Group", "Trail Ruins")),
         new Impossible("dungeons — the mossy cobblestone room with the spawner",
               "dungeons are scattered chunk by chunk as terrain generates and have no seed-level "
               + "position at all, so there is nothing to search for",
               "trial chambers, which are full of spawners and ARE placed by the seed, or a "
               + "mineshaft if the point was a cave to explore",
               List.of("Trial Chambers", "Mineshaft")),
         // The desert well is the one entry here that is half searchable, and saying so is the
         // difference between a useful note and a wrong one. The building is a catalog row; only
         // the suspicious sand in its floor is a roll.
         new Impossible("what is under a desert well's suspicious sand",
               "the sand rolls its item when you brush it — but the WELL itself is searchable",
               "the Desert Well row, which finds the well near spawn",
               List.of("Desert Well")),
         new Impossible("eyes of ender, end portals and strongholds",
               "every seed has exactly one stronghold, ring-placed 1300+ blocks out, so none are "
               + "ever near spawn and there is nothing to optimise",
               "a ruined portal near spawn for a fast trip to the Nether, and a fortress close to "
               + "where that portal comes out for the blaze rods",
               List.of("Giant Ruined Portal", "Ruined Portal", "Nether Fortress")),
         new Impossible("villager trades, professions, or a specific enchanted book",
               "a villager takes his job after the world loads and rolls his trades then",
               "the profession BUILDING — the library, the cartographer's house, the armorer — "
               + "which is part of the village and certain",
               List.of("Village with Librarian", "Village with Cartographer", "Village with Armorer")),
         new Impossible("ores, diamonds, or anything underground that is not a structure",
               "ore is placed chunk by chunk as terrain generates, not fixed as a seed-level position",
               "a mineshaft near spawn, or an ancient city if the point was going deep",
               List.of("Mineshaft", "Ancient City")),
         new Impossible("which mobs spawn, or any specific mob",
               "mobs spawn from light and space while you play; the seed does not place them",
               "the few mobs that are built INTO a structure: the allays in an outpost cage, the "
               + "witch's hut, or a zombie (abandoned) village",
               List.of("Outpost with Allay Cage", "Swamp Hut", "Abandoned Village")),
         new Impossible("terrain shape — mountains, cliffs, islands, a flat area, a big cave",
               "the search reads biomes and structures, not the shape of the ground",
               "the biome that has that shape: jagged_peaks or snowy_slopes for mountains, meadow "
               + "or plains for flat, lush_caves or dripstone_caves for caves — and ask for a "
               + "small, big or huge one",
               List.of()),
         // NARROWED 2026-08-12 alongside the size tiers. "Small" used to be listed here as
         // uncheckable and is now a real request measured against each biome's own p33, so the line
         // no longer says it cannot be done. What is still true is the STRUCTURE half: a village is
         // the size a village is.
         new Impossible("how large a structure is",
               "a structure is built from a fixed set of pieces, so its size is not something the "
               + "seed varies in a way that can be searched",
               "biome size instead — \"small\", \"big\" and \"huge\" all work on biomes now, each "
               + "judged against that biome's own measured sizes",
               List.of()),
         // NARROWED 2026-08-11. This line used to say no mansion room could be searched at all,
         // which stopped being true when the four room rows shipped: the secret room, the lava
         // vault, the TNT trap and the cobweb spawner room are real searches now. What remains
         // unsearchable is the OTHER sixty-odd rooms, whose templates contain nothing but planks
         // and cobblestone — a player standing in one could not tell it from its neighbours, so
         // there is nothing for a row to promise. And the arena is not rarity but absence: no
         // mansion template is an arena, and the nearest thing (the jail block with its iron bars
         // and cells) is in 60% of mansions, which filters nothing.
         // THE END, ADDED 1.41.4 — and this entry is not a limitation of effort, it is a promise
         // this program could not keep. An End City is placed by the seed but does not necessarily
         // GENERATE at the placed slot, so the finder reported one at coordinates where a player
         // flew out and found empty sky. Every other row in the catalog can be trusted because
         // placement plus biome IS generation; in the End it is not. See SeedCriteria.Dim.
         //
         // No "instead" is offered, deliberately. There is no near-miss End search to suggest and
         // inventing one would be the same over-promise in a smaller package.
         new Impossible("anything in the End — End Cities, the End Ship or the elytra, End biomes, "
               + "the outer islands, or the dragon",
               "the End is not searched at all: a structure the seed PLACES there does not always "
               + "generate, so any End coordinate would be a guess dressed up as a find",
               "", List.of()),
         new Impossible("a mansion ARENA, or a mansion room other than the four searchable ones",
               "the mansion has no arena template at all, and its other rooms contain nothing "
               + "distinctive enough to recognise or to search for",
               "one of the four rooms that ARE searchable — the secret room with the diamond block, "
               + "the lava vault, the TNT trap room, or the cobweb room with the spawner",
               List.of("Mansion with Secret Room", "Mansion with Lava Vault",
                     "Mansion with TNT Trap", "Mansion with Spawner")));

   /** Player phrases for structure rows, as {phrase, label} — for the no-AI reader. Parenthesised
    *  notes in the phrase book ("(PLAINS villages only …)") are advice for the model and are dropped. */
   static List<String[]> structurePhrases() {
      List<String[]> out = new ArrayList<>();
      for (Phrase p : GLOSSARY) {
         for (String w : p.words().replaceAll("[(][^)]*[)]", "").split(",")) {
            if (!w.isBlank()) {
               out.add(new String[]{w.trim(), p.label()});
            }
         }
      }
      return out;
   }

   /** Player phrases for biomes, as {phrase, biome id path}. */
   static List<String[]> biomePhrases() {
      List<String[]> out = new ArrayList<>();
      for (BiomeWords b : BIOME_GLOSSARY) {
         for (String w : b.words().split(",")) {
            if (!w.isBlank()) {
               out.add(new String[]{w.trim(), b.path()});
            }
         }
      }
      return out;
   }

   /** The impossible list as the prompt teaches it: what, why, and what to offer instead. Lines
    *  whose suggested rows are all absent from this world keep the WHY and drop the suggestion. */
   private static String impossibleSection(Set<String> labels) {
      StringBuilder sb = new StringBuilder();
      for (Impossible i : IMPOSSIBLE) {
         sb.append("  ").append(i.what()).append("\n      WHY: ").append(i.why());
         boolean any = i.rows().isEmpty();
         for (String row : i.rows()) {
            if (labels.contains(row)) {
               any = true;
               break;
            }
         }
         if (any && !i.instead().isEmpty()) {
            sb.append("\n      OFFER INSTEAD: ").append(i.instead());
         }
         sb.append('\n');
      }
      return sb.toString();
   }

   /**
    * The phrase book as it will actually be taught, one entry per line: {@code label TAB words}.
    * Only rows this world built are included, exactly as {@link #buildPrompt} filters them.
    *
    * Exists so the coverage map is read off the shipped table rather than a copy of it — a map
    * that can drift from the thing it describes is worse than no map.
    */
   public static List<String> phraseBook() {
      List<SeedCriteria.StructureTarget> catalog = SeedCatalogCache.list();
      Set<String> labels = new HashSet<>();
      if (catalog != null) {
         for (SeedCriteria.StructureTarget t : catalog) {
            labels.add(t.label);
         }
      }
      List<String> out = new ArrayList<>();
      for (Phrase p : GLOSSARY) {
         if (labels.contains(p.label())) {
            out.add(p.label() + "\t" + p.words());
         }
      }
      return out;
   }

   /** Builds the strict prompt: schema + the exact allowed names. Catalog must be ready. */
   public static String buildPrompt(String wish) {
      List<SeedCriteria.StructureTarget> catalog = SeedCatalogCache.list();
      List<Identifier> biomes = SeedCatalogCache.biomeList();
      Set<String> labels = new HashSet<>();
      StringBuilder structureNames = new StringBuilder();
      for (SeedCriteria.StructureTarget t : catalog) {
         if (labels.add(t.label)) {
            if (structureNames.length() > 0) {
               structureNames.append(", ");
            }
            structureNames.append(t.label);
         }
      }
      StringBuilder biomeNames = new StringBuilder();
      Set<String> biomePaths = new HashSet<>();
      // Biomes with no usable size control, worked out from the live list crossed with what was
      // actually measured — six overworld ones came out all-one-size, and the Nether and End were
      // never measured at all. Listing the EXCEPTIONS rather than the 44 that do work keeps the
      // prompt short and, more importantly, keeps it correct without anyone maintaining a list.
      StringBuilder noSize = new StringBuilder();
      for (Identifier b : biomes) {
         if (biomeNames.length() > 0) {
            biomeNames.append(", ");
         }
         biomeNames.append(b.getPath());
         biomePaths.add(b.getPath());
         if (!BiomeShape.tiered(b)) {
            if (noSize.length() > 0) {
               noSize.append(", ");
            }
            noSize.append(b.getPath());
         }
      }
      StringBuilder biomeGlossary = new StringBuilder();
      for (BiomeWords bw : BIOME_GLOSSARY) {
         if (biomePaths.contains(bw.path())) {
            biomeGlossary.append("  ").append(bw.words()).append("  ->  \"").append(bw.path()).append("\"\n");
         }
      }
      // Only teach phrases whose row this world actually built. A line for a row that no longer
      // exists would train the AI to answer with a name the validator below rejects.
      StringBuilder glossary = new StringBuilder();
      for (Phrase p : GLOSSARY) {
         if (labels.contains(p.label())) {
            glossary.append("  ").append(p.words()).append("  ->  \"").append(p.label()).append("\"\n");
         }
      }

      return "You convert a Minecraft world wish into strict JSON for a seed search. Reply with ONLY the JSON object - no explanation, no code fences.\n"
            + "Schema: {\"structures\":[{\"name\":\"<allowed structure name>\",\"distance\":<blocks from spawn>,"
            + "\"count\":<how many of it, default 1>,\"spawn_inside\":<true only if the wish says INSIDE>,"
            + "\"size\":\"any|big\"}],"
            + "\"biomes\":[{\"name\":\"<allowed biome id>\",\"size\":\"any|small|big|huge\",\"distance\":<blocks from spawn>}],"
            + "\"adjacent\":[{\"a\":\"<allowed biome id OR structure name>\",\"b\":\"<allowed biome id OR structure name>\",\"within\":<blocks apart>}],"
            + "\"slime\":{\"count\":<how many slime chunks>,\"within\":<blocks from spawn>},"
            + "\"exclude\":[{\"name\":\"<allowed structure OR biome that must NOT be nearby>\"}],"
            + "\"without\":[{\"name\":\"<allowed structure name>\",\"distance\":<blocks from spawn>}],"
            + "\"stronghold_mentioned\":<true if the wish mentions strongholds, end portals or eyes of ender>,"
            + "\"cant\":\"<empty, or a short plain note>\"}\n"
            + "Rules: \"distance\" is how far from spawn that ONE thing may be, in blocks. Omit it for a simple wish "
            + "that names one or two things and the app will use its own close-to-spawn setting. Set it only when you are "
            + "composing a BUNDLE (see below), and then give each item its own sensible number — never put everything at the same "
            + "small value, because several things all within 64 blocks of spawn is a world that does not exist. "
            + "'near spawn / close to spawn / at spawn' just means include that thing — everything found ends up near spawn by definition. "
            + "For 'X not next to Y' / 'X without Y nearby' / 'no Y around' put X (if any) in structures/biomes and Y in \"exclude\". "
            + "A wish that is ONLY about avoiding something ('no deserts near spawn') is valid: leave structures/biomes empty and just fill \"exclude\". "
            + "\"exclude\" AND \"without\" ARE DIFFERENT AND THE DIFFERENCE MATTERS. "
            + "\"exclude\" means the thing must not be ANYWHERE near spawn. \"without\" means: still find this "
            + "structure near spawn, but it must NOT have the feature named in the row. "
            + "So 'an igloo with no basement' is \"without\":[{\"name\":\"Igloo with Basement\"}] — you name the "
            + "FEATURE ROW and it gets flipped; putting Igloo in \"exclude\" would instead demand no igloo at all, "
            + "which is the opposite of what was asked. Same shape for 'a village with no zombies' "
            + "(\"without\":[{\"name\":\"Abandoned Village\"}]) and 'an outpost but no cages'. "
            + "Only rows whose name reads \"<structure> with <feature>\", plus \"Abandoned Village\", can go in \"without\"; "
            + "anything else belongs in \"exclude\". Never put the same row in both. "
            + "\"Abandoned Village\" in \"without\" DOES combine with a village profession row in \"structures\" — "
            + "'a village with a library and no zombies' is both, and the app merges them into one village. "
            + "The reverse does NOT work: a village cannot be abandoned AND have an armorer, mason or leatherworker, "
            + "because zombie villages build from their own small house set and most profession buildings have no zombie "
            + "version at all. If the wish asks for both, pick the one it cares about more and say so in \"cant\". "
            + "\"NEXT TO\" IS ITS OWN FIELD NOW — this changed, and the old way is wrong.\n"
            + "When the wish says one thing should be next to ANOTHER THING (not next to spawn), add an entry to "
            + "\"adjacent\" AND still list both things in \"structures\"/\"biomes\" as usual. "
            + "\"a\" is the thing the distance is measured OUT FROM and \"b\" is the other end. When one end is a "
            + "biome and the other a structure, \"a\" must be the BIOME — so 'a village next to a cherry grove' is "
            + "a=\"cherry_grove\", b=\"Surface Village\". TWO STRUCTURES also works now: 'a fortress next to a bastion' "
            + "is a=\"Nether Fortress\", b=\"Bastion Remnant\", and either order is fine. "
            + "Some structure pairs are placed by the same rule and can never be neighbours; the app knows which and "
            + "will say so, so pair them anyway rather than refusing. "
            + "Words that mean this: next to, beside, by, near (when it names a second thing), adjacent to, bordering, "
            + "touching, on the edge of, right by, close to, neighbouring, on the border of, that borders, surrounded by, "
            + "backing onto, overlooking, with X nearby, X and Y together, X right beside Y.\n"
            + "\"within\" is how many blocks apart they may be. Omit it and the app uses its own measured default "
            + "(" + BiomeShape.NEXT_TO_BIOME + " blocks for biome-to-biome, " + BiomeShape.NEXT_TO_STRUCTURE
            + " for biome-to-structure). Set it only when the wish gives a number or clearly asks for it to be tight "
            + "('right on the edge of', 'literally touching') — under " + BiomeShape.TIGHT
            + " blocks most seeds fail, so mention that in \"cant\" if you go tighter.\n"
            + "NEVER pair a biome with ITSELF ('plains next to plains'): a biome is always next to itself, so it is not a "
            + "search at all. Leave \"adjacent\" empty, put the biome in \"biomes\" normally, and say so in \"cant\". "
            + "Never pair things in different dimensions (an overworld biome with a Nether structure) — that has no meaning.\n"
            + "HOW MANY OF SOMETHING: 'two shipwrecks', 'three villages', 'a couple of portals', 'four huts' — "
            + "put the number in \"count\" on that ONE structure entry. Do NOT list the same structure several times; "
            + "one entry with \"count\":3 is right and three entries are not. Words: a pair/couple/two = 2, "
            + "a few/three = 3, several = 3, quad = 4. Counting several of a DETAILED row (one that names a building "
            + "inside) is very slow, so if the wish wants several of those, say so in \"cant\".\n"
            + "SPAWNING INSIDE something is different from spawning NEAR it, and only counts when the wish really says "
            + "inside: 'I want to spawn IN a village', 'open my eyes standing in', 'wake up inside', 'spawn on top of'. "
            + "Set \"spawn_inside\":true on that structure. Plain 'a village at spawn' or 'near spawn' is NOT this — "
            + "leave it false. It only works for overworld structures.\n"
            + "SLIME CHUNKS: 'slime chunks', 'a slime farm spot', 'somewhere to build a slime farm'. Fill \"slime\" "
            + "with how many and how far. If no number is given use count 3 and omit \"within\". Slime chunks are one "
            + "chunk in ten, and measured over 2000 seeds EVERY seed has 3 within 128 blocks — so a wide \"within\" is "
            + "not a search. Only set \"within\" when the wish gives a number, and if that number is over "
            + SlimeChunks.PICKER_WITHIN + " say in \"cant\" that it will not narrow anything.\n"
            + "A BIG VILLAGE is a real search: put \"size\":\"big\" on the structure entry. It works for villages "
            + "(they vary a lot in size) and is ignored elsewhere. Do not use \"size\" on structures for anything else.\n"
            + "BIOME SIZE — \"small\" now WORKS, which it did not before.\n"
            + "size is for biomes only and must be EXACTLY \"any\", \"small\", \"big\" or \"huge\". "
            + "Map small/tiny/little/compact/narrow to \"small\"; big/large/wide/broad/sprawling to \"big\"; "
            + "huge/giant/massive/ginormous/enormous/vast to \"huge\". "
            + "Each biome is judged against ITS OWN measured sizes, so \"big\" means big for a pale garden (about 64 blocks "
            + "across) and big for a warm ocean (about 608) — you do not need to know the numbers, just pass the word. "
            + "Structures have no size; ignore size words on structures. "
            + "These biomes are all ONE SIZE and a size word on them does nothing — use \"any\" and mention it in \"cant\": "
            + noSize + ".\n"
            + "STRONGHOLDS CANNOT BE SEARCHED FOR AT ALL. Every seed has one about 1300+ blocks out and none are ever near spawn, "
            + "so there is nothing to optimise — the same is true of the end portal inside it and of how many eyes of ender it already has. "
            + "If the wish mentions any of those, set \"stronghold_mentioned\" true, do NOT put a stronghold in \"structures\", "
            + "and translate the rest of the wish normally (the app shows its own note, so you need not mention it in \"cant\"). "
            + "Only include things the wish actually asks for; NEVER invent a name not in the allowed lists - pick the closest allowed name instead.\n"
            + "MANY FEATURES AT ONCE: a wish often names several things ('big portal, basement, secret room'). "
            + "Translate EVERY phrase you recognise and put them all in \"structures\" — one entry each. Do not answer with only the first one.\n"
            + "USE THE SPECIFIC ROW: when the wish names a feature, choose the row for that feature and NOT the plain row. "
            + "'igloo with a basement' is [\"Igloo with Basement\"], never [\"Igloo\",\"Igloo with Basement\"] and never just [\"Igloo\"]. "
            + "The feature row already includes the structure.\n"
            + "ONE ROW PER STRUCTURE KIND: never list two rows about the same kind of structure (two trial chamber rows, "
            + "\"Warm Ocean Ruins\" together with \"Large Ocean Ruin\", \"Surface Village\" together with \"Village with Armorer\"). "
            + "Two rows would be satisfied by two DIFFERENT structures, which is not what the player pictured. "
            + "Pick the single closest row and name the other feature in \"cant\".\n"
            + "BLACKSMITH: the old lava-forge hut is now three separate houses — the armorer, the weaponsmith and "
            + "the toolsmith — and each has its OWN row. If the wish names one of them, use that row. If the wish "
            + "just says blacksmith, smithy, smith or forge without saying which, use \"Village with Weaponsmith\": "
            + "that is the one with the lava and the loot chest, so it IS the building they are picturing. "
            + "Do NOT put anything about the blacksmith in \"cant\" — this is a search that works, not a limitation, "
            + "and the app writes its own one-line explanation. Leave \"cant\" empty unless something else in the "
            + "wish genuinely could not be searched.\n"
            + "COMPOSING A BUNDLE — this is the important one.\n"
            + "When the wish asks for a KIND of seed rather than naming things ('a good survival seed', 'best speedrun seed', "
            + "'a cozy woodland base', 'somewhere good for a mob farm', 'a nice place to build'), do NOT return one village. "
            + "Work out what would actually make that kind of spawn good, and compose a GENEROUS bundle from the allowed lists: "
            + "typically 2-3 biomes plus 2-4 structures, each with its own distance. Think about what the player will really do "
            + "in the first hour — wood, food, shelter, early iron, a way into the nether — and about what suits THEIR stated "
            + "purpose. Two different quality wishes should produce two different bundles; there is no fixed answer.\n"
            + "BUNDLE LIMITS (a bundle that cannot be found is worse than a small one):\n"
            + "  - at most 6 structures and " + MAX_BIOMES + " biomes;\n"
            + "  - IF THE WISH ASKS FOR MORE BIOMES THAN THAT (\"7 wood types near spawn\", \"every "
            + "flower biome\"), do not refuse and do not stall: pick the " + MAX_BIOMES
            + " that best fit what they are after, list them, and say in \"cant\" which ones you left "
            + "out and that a seed with all of them near one spawn gets rarer with every extra;\n"
            + "  - at most TWO of the detailed feature rows — the ones that demand a particular building or room inside a "
            + "structure, like \"Village with Armorer\", \"Igloo with Basement\" or \"Trial Chamber with Eruption Room\". "
            + "Each one makes the search much slower, so spend them where they matter and use plain rows for the rest;\n"
            + "  - distances: roughly 150-300 for the thing the wish is really about, 300-600 for supporting extras, "
            + "150-300 for biomes. Ocean/deep structures and anything in the Nether can be given more room.\n"
            + "THE END CANNOT BE SEARCHED AT ALL, and this is absolute. There are no End structures and no End "
            + "biomes in the allowed lists below; End Cities, the End Ship, the elytra, the outer islands, the "
            + "dragon and every End biome are all outside what this app can do. If the wish mentions any of them, "
            + "do NOT invent a name for them and do NOT put anything End-related in any field — translate whatever "
            + "else the wish asks for, and say plainly in \"cant\" that the End isn't something the search can look "
            + "at. The reason, if it helps you word it: an End structure that the seed places does not always end up "
            + "generating there, so an End coordinate would be a guess. The Nether is fine and is searched normally.\n"
            + "Example bundles — these are ILLUSTRATIONS of the right shape, not a menu to copy:\n"
            + "  \"a good survival seed\" -> village(250), ruined portal(350), shipwreck(500), forest(200), plains(200)\n"
            + "  \"best speedrun seed\" -> village(200), ruined portal(300), nether fortress(300), bastion(300), plains(200)\n"
            + "  \"a cozy woodland base\" -> dark forest(200), river(250), village(400), pale garden(500)\n"
            + "  \"somewhere good for a mob farm\" -> a dark/flat biome(200), ocean or deep ocean(300), village(400) for beds\n"
            + "Compose your own for anything else the player asks; match the bundle to the words they used.\n"
            + "THE \"cant\" FIELD — this is an ANSWER, not an apology. It has three parts and needs all three:\n"
            + "  1. name the exact thing that cannot be checked, in the player's own words;\n"
            + "  2. say WHY in one short clause a non-technical player understands;\n"
            + "  3. offer the nearest thing that CAN be searched, and put that thing in the search too.\n"
            + "Good: \"can't check chest loot — it's rolled when you open it, not fixed by the seed — so I looked "
            + "for a village with a weaponsmith instead, which is early gear you can count on\". "
            + "Bad: \"some details can't be checked\", \"chest loot isn't searchable\" (true, and no use to anyone). "
            + "Keep it to one sentence. Leave it empty when everything asked for is searchable. "
            + "Always still fill in everything you CAN — a wish is never rejected for containing an impossible part.\n"
            + "THE IMPOSSIBLE LIST — what cannot be searched, why, and what to offer instead:\n"
            + impossibleSection(labels)
            + "FEATURE WORDS -> the allowed name to use:\n" + glossary
            + "BIOME WORDS -> the allowed biome id to use:\n" + biomeGlossary
            + "Examples:\n" + examples(labels, biomePaths)
            + "Allowed structure names: " + structureNames + "\n"
            + "Allowed biome ids: " + biomeNames + "\n"
            + "Wish: \"" + wish.replace('"', '\'') + "\"";
   }


   /**
    * THE WORKED EXAMPLES, AND WHY THEY ARE A GATED TABLE RATHER THAN EIGHT LINES OF PROSE.
    *
    * Everything else the prompt teaches is already filtered against the live catalog - the phrase
    * book, the biome glossary, the "offer instead" suggestions, the two allowed-name lists. The
    * examples were not. They are the most imitable part of the whole prompt (a model shown eight
    * finished answers copies their shape AND their names) and they were one hardcoded string, so a
    * row removed from the catalog stayed in the examples and went on being taught.
    *
    * That is exactly the difference between a name being unlisted and a name being unreachable:
    * the validator would reject the answer, and the player would be told "that isn't something the
    * search can look for" about a row the prompt had just demonstrated to the model.
    *
    * So every example declares the names it uses, and an example naming anything this world did not
    * build is dropped whole rather than shown with a hole in it. {@code wishDiag} then asserts that
    * the finished prompt mentions no structure name outside the allowed list, which is what makes
    * this a rule instead of a habit.
    */
   private record Example(String json, List<String> rows, List<String> biomes) {
      Example(String json, List<String> rows) {
         this(json, rows, List.of());
      }
   }

   private static final List<Example> EXAMPLES = List.of(
         new Example("\"big portal, basement, secret room\" -> {\"structures\":[{\"name\":\"Giant Ruined Portal\"},{\"name\":\"Igloo with Basement\"},{\"name\":\"Mansion with Secret Room\"}],\"biomes\":[],\"adjacent\":[],\"exclude\":[],\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               List.of("Giant Ruined Portal", "Igloo with Basement", "Mansion with Secret Room")),
         // THIS EXAMPLE SAID "Village with Armorer" UNTIL 1.43.0, three paragraphs below the rule
         // that says a blacksmith is the WEAPONSMITH. A model shown both follows whichever it weighs
         // more on the day, which is exactly the report that forced the fix: "I asked for a
         // weaponsmith and sometimes got the blast-furnace house". wishDiag now fails if any example
         // maps a glossary word to a different row from the glossary's own.
         new Example("\"village with a blacksmith next to a huge jungle\" -> {\"structures\":[{\"name\":\"Village with Weaponsmith\"}],\"biomes\":[{\"name\":\"jungle\",\"size\":\"huge\"}],\"adjacent\":[{\"a\":\"jungle\",\"b\":\"Village with Weaponsmith\"}],\"exclude\":[],\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               List.of("Village with Weaponsmith"), List.of("jungle")),
         new Example("\"a big pale garden next to dark oak\" -> {\"structures\":[],\"biomes\":[{\"name\":\"pale_garden\",\"size\":\"big\"},{\"name\":\"dark_forest\",\"size\":\"any\"}],\"adjacent\":[{\"a\":\"pale_garden\",\"b\":\"dark_forest\"}],\"exclude\":[],\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               List.of(), List.of("pale_garden", "dark_forest")),
         new Example("\"cherry blossom next to a village\" -> {\"structures\":[{\"name\":\"Surface Village\"}],\"biomes\":[{\"name\":\"cherry_grove\",\"size\":\"any\"}],\"adjacent\":[{\"a\":\"cherry_grove\",\"b\":\"Surface Village\"}],\"exclude\":[],\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               List.of("Surface Village"), List.of("cherry_grove")),
         new Example("\"plains next to plains\" -> {\"structures\":[],\"biomes\":[{\"name\":\"plains\",\"size\":\"any\"}],\"adjacent\":[],\"exclude\":[],\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"a biome is always next to itself, so 'plains next to plains' isn't something to search for — I just looked for plains near spawn\"}",
               List.of(), List.of("plains")),
         new Example("\"outpost with allays and a trial chamber with the eruption room, no ocean\" -> {\"structures\":[{\"name\":\"Outpost with Allay Cage\"},{\"name\":\"Trial Chamber with Eruption Room\"}],\"biomes\":[],\"adjacent\":[],\"exclude\":[{\"name\":\"ocean\"}],\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               List.of("Outpost with Allay Cage", "Trial Chamber with Eruption Room"), List.of("ocean")),
         new Example("\"an igloo but not one with the basement, and a village that isn't a zombie one\" -> {\"structures\":[],\"biomes\":[],\"adjacent\":[],\"exclude\":[],\"without\":[{\"name\":\"Igloo with Basement\"},{\"name\":\"Abandoned Village\"}],\"stronghold_mentioned\":false,\"cant\":\"\"}",
               List.of("Igloo with Basement", "Abandoned Village")),
         new Example("\"a chamber with loads of diamonds in the vaults\" -> {\"structures\":[{\"name\":\"Trial Chamber with Eruption Room\"}],\"biomes\":[],\"adjacent\":[],\"exclude\":[],\"without\":[],\"stronghold_mentioned\":false,\"cant\":\"can't check vault loot — it's decided when you spend a key, not when the world is made — so I looked for a chamber with the rare eruption room, which is a real reason to pick one chamber over another\"}",
               List.of("Trial Chamber with Eruption Room"))
   );

   /** The examples this world can honestly show, one per line. See {@link Example}. */
   private static String examples(Set<String> labels, Set<String> biomePaths) {
      StringBuilder sb = new StringBuilder();
      for (Example e : EXAMPLES) {
         if (!labels.containsAll(e.rows()) || !biomePaths.containsAll(e.biomes())) {
            continue;
         }
         sb.append("  ").append(e.json()).append('\n');
      }
      return sb.toString();
   }

   /** The weaponsmith row, recognised by its token rather than by a spelling of it. */
   private static boolean isWeaponsmith(SeedCriteria.StructureTarget row) {
      return row.building != null && VillageLayout.tokenMatches("weaponsmith", row.building);
   }

   /** Did the player name a smith OTHER than the weaponsmith? Then "blacksmith" is not the only word
    *  and whatever row they named is theirs to keep. */
   static boolean namedOtherSmith(String lowerWish) {
      return lowerWish.contains("armorer") || lowerWish.contains("armourer") || lowerWish.contains("blast furnace")
            || lowerWish.contains("toolsmith") || lowerWish.contains("tool smith") || lowerWish.contains("smithing table");
   }

   /** Validates the AI's reply into criteria. Unknown names come back as an error string. */
   public static Outcome parse(String aiReply) {
      return parse(aiReply, "");
   }

   /**
    * As above, told what the player actually TYPED.
    *
    * The wish text is not used to search — the JSON is — but it is used to refuse things the JSON
    * claims and the player never said. A model handed an optional field tends to fill it in, and a
    * search that quietly adds a requirement nobody asked for is the same failure as a "can't check"
    * note about a question nobody asked: it makes the app look like it is doing something other
    * than what it was told. See where {@code slime} is read.
    */
   public static Outcome parse(String aiReply, String wishText) {
      String wish = wishText == null ? "" : wishText.toLowerCase(Locale.ROOT);
      boolean askedBlacksmith = wish.contains("blacksmith") || wish.contains("smithy")
            || wish.contains("forge");
      if (aiReply == null || aiReply.startsWith("⚠") || aiReply.startsWith("No ") || aiReply.startsWith("Cooldown")) {
         return new Outcome(null, aiReply == null ? "No AI reply." : aiReply, null);
      }
      JsonObject root = extractJson(aiReply);
      if (root == null && aiReply.contains(CUT_OFF_MARKER)) {
         // THE ONE FAILURE THAT LOOKS LIKE A DIFFERENT FAILURE. A reply cut off by the token limit
         // usually ends mid-object inside a code fence, so the first line of it is "```json" — and
         // quoting that back ("It said: \"```json\"") describes the symptom and hides the cause.
         // AiVision marks a truncated answer, so when the JSON is unreadable AND the mark is there,
         // there is nothing to guess about.
         return new Outcome(null, "The AI ran out of room before it finished the search — its reply "
               + "was cut off. Try a shorter wish, or pick below.", null);
      }
      if (root == null) {
         // WHAT ACTUALLY CAME BACK, not "couldn't read it". The old message here was the same
         // sentence for a refusal, a truncated reply, a model that answered in prose and a model
         // that wrapped its JSON in reasoning — four different problems with four different fixes,
         // all reported as one dead end. Showing the first line of the reply costs nothing and is
         // usually the whole diagnosis: "I'd be happy to help!" is a prompt problem, a JSON
         // fragment ending mid-word is a token-limit problem, and "⚠ GPT 400: …" is neither.
         return new Outcome(null, "The AI didn't answer with a search. It said: "
               + excerpt(aiReply) + " — try simpler words, or pick below.", null);
      }

      List<SeedCriteria.StructureTarget> catalog = SeedCatalogCache.list();
      List<Identifier> biomes = SeedCatalogCache.biomeList();
      if (catalog == null || biomes == null) {
         return new Outcome(null, "World-gen data is still loading — give it a second and try again.", null);
      }

      // A SPREAD-OUT BUNDLE IN EXACT MODE IS REFUSED, NOT SQUASHED. The model composes "a good
      // survival seed" as village 250, portal 350, forest 200 — a description of a world. Exact mode
      // holds every item to the at-spawn radius, so that bundle used to be searched as "all of these
      // within 64 blocks": a different, far rarer world, with the biome distances dropped without a
      // word. One stray distance is still clamped (with a note); two or more is a bundle.
      String spread = FableVisionConfig.seedFastMode ? null : spreadInExact(root);
      if (spread != null) {
         // SHORT, because layoutDiag holds it to four lines at 320 pixels: two named items at most.
         return new Outcome(null, "That spreads things out (" + spread + "). Exact is at spawn — "
               + "switch to ⚡Fast.", null);
      }

      SeedCriteria criteria = new SeedCriteria();
      List<String> autoNotes = new ArrayList<>();
      // THE END, ANSWERED FROM THE PLAYER'S WORDS. Added first so it leads the note: somebody who
      // typed "seed with an end city near the elytra ship" is owed this sentence before anything
      // about what else got searched. See END_CANT.
      if (mentionsEnd(wish)) {
         autoNotes.add(END_CANT);
      }
      try {
         // "without" entries join the SAME list as "structures", negated, before clash resolution
         // runs. They have to: "Village with Armorer" and "Village without Butcher" are two demands
         // on one structure, and the engine can only carry one token per structure. Parsing them
         // into a separate list would let both through and quietly search for two villages.
         //
         // The wants array can be empty and the withouts non-empty — "an igloo with no basement" is
         // a whole wish — so the entry point is the merged list, not "structures".
         List<JsonObject> wanted = mergeWants(root);
         if (!wanted.isEmpty()) {
            // Collected first, filtered second. Which row survives a same-structure clash depends
            // on what ELSE is in the list, so nothing can be decided one entry at a time.
            List<SeedCriteria.StructureTarget> picked = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonObject el : wanted) {
               JsonObject o = el;
               boolean negate = o.has(WITHOUT_FLAG);
               String name = o.has("name") ? o.get("name").getAsString().trim() : "";
               SeedCriteria.StructureTarget match = findRow(catalog, name);
               if (match != null && negate) {
                  SeedCriteria.StructureTarget flipped = match.negated();
                  if (flipped == null) {
                     // The row has no honest negative — see StructureTarget.negated(). Say which,
                     // rather than silently searching for the POSITIVE, which is what dropping the
                     // flag would do and is the exact opposite of the wish.
                     String cant = "\"not " + match.label + "\" — that one has no useful \"without\","
                           + " so it was left out rather than searched the wrong way round";
                     if (!autoNotes.contains(cant)) {
                        autoNotes.add(cant);
                     }
                     continue;
                  }
                  match = flipped;
               }
               if (match == null) {
                  // One unrecognised name used to sink the whole wish. It shouldn't: the player
                  // asked for several things and we can honestly search the rest, as long as we
                  // SAY which part was dropped rather than quietly narrowing the search.
                  if (!name.isEmpty()) {
                     String cant = "\"" + name + "\" isn't something the search can look for";
                     if (!autoNotes.contains(cant)) {
                        autoNotes.add(cant);
                     }
                  }
                  continue;
               }
               // A BLACKSMITH IS THE WEAPONSMITH, ENFORCED HERE AND NOT ONLY TAUGHT. The prompt says so
               // and one of its examples said the opposite for three releases, so a model could land on
               // either. If the player typed blacksmith/smithy/forge and never named the armorer or the
               // toolsmith, a reply choosing one of those is corrected to the house they pictured.
               if (!negate && askedBlacksmith && !namedOtherSmith(wish)
                     && ("Village with Armorer".equals(match.label) || "Village with Toolsmith".equals(match.label))) {
                  SeedCriteria.StructureTarget smith = findRow(catalog, "Village with Weaponsmith");
                  if (smith != null) {
                     match = smith;
                  }
               }
               if (match.special == SeedCriteria.Special.DUNGEON) {
                  if (!autoNotes.contains(match.note)) autoNotes.add(match.note);
               } else if (seen.add(match.label)) {
                  // The distance is applied HERE, while the reply entry and the row are still side
                  // by side. resolveClashes reorders and drops rows, so a parallel list of numbers
                  // would quietly pair the wrong distance with the wrong structure.
                  int asked = o.has("distance") ? readInt(o.get("distance")) : -1;
                  // Only a distance that was actually HONOURED makes this a spread-out bundle. In
                  // Exact mode clampDistance ignores it, so treating the wish as composed would
                  // switch off the tight-at-spawn hunt for a search that is entirely at spawn.
                  if (asked > 0 && FableVisionConfig.seedFastMode) {
                     criteria.composed = true;
                  } else if (asked > FableVisionConfig.seedRadius()) {
                     note(autoNotes, "\"" + match.label + "\" was asked for up to " + asked
                           + " blocks out, but Exact mode means AT spawn — it was searched within "
                           + FableVisionConfig.seedRadius() + ". Switch to Fast mode for a wider spread.");
                  }
                  SeedCriteria.StructureTarget row = match.withRadius(clampDistance(asked));
                  // COUNT, capped. Each extra one is another confirm and, on a content row, another
                  // assembly — so a reply asking for eight villages with an armorer would be a
                  // search that never finishes rather than a slow one.
                  int want = o.has("count") ? readInt(o.get("count")) : 1;
                  if (want > 1) {
                     int cap = row.building != null ? MAX_COUNT_CONTENT : MAX_COUNT_PLAIN;
                     if (want > cap) {
                        note(autoNotes, want + " of \"" + match.label + "\" would take far too long, "
                              + "so it looked for " + cap + " instead");
                        want = cap;
                     }
                     row = row.withCount(want);
                  }
                  if (o.has("spawn_inside") && o.get("spawn_inside").getAsBoolean()) {
                     if (row.dim != SeedCriteria.Dim.OVERWORLD) {
                        note(autoNotes, "\"inside " + match.label + "\" only makes sense in the "
                              + "overworld — you don't spawn in the " + row.dim.name().toLowerCase(Locale.ROOT)
                              + " — so it looked for one nearby instead");
                     } else if (want > 1) {
                        note(autoNotes, "asking to spawn inside " + want + " things at once isn't "
                              + "possible, so it looked for one you spawn inside");
                        row = row.withCount(1).insideSpawn();
                     } else {
                        row = row.insideSpawn();
                     }
                  }
                  // "size":"big" on a structure. Only villages vary enough in size for this to be a
                  // real question, and the threshold is statsDiag's measured p67 for the row rather
                  // than a number chosen to sound large.
                  String sizeWord = o.has("size") ? o.get("size").getAsString() : "any";
                  if (!sizeWord.equalsIgnoreCase("any") && !sizeWord.isBlank()) {
                     int big = SeedCatalog.bigPieces(row);
                     if (big > 0) {
                        row = row.withMinPieces(big);
                     } else {
                        note(autoNotes, "\"" + sizeWord + " " + match.label + "\" — that one is the "
                              + "same size in every seed, so the size was ignored");
                     }
                  }
                  // THE PLAYER'S WORD WINS. If they typed "blacksmith" and this resolved to the
                  // weaponsmith — which is the right building, proved from the templates — then the
                  // row is shown under the name they used, and the explanation is an ℹ line rather
                  // than a "couldn't check". Asking for a blacksmith is a search that works.
                  if (askedBlacksmith && isWeaponsmith(row) && !namedOtherSmith(wish)) {
                     row = row.renamed("Blacksmith");
                     note(autoNotes, BLACKSMITH_NOTE);
                  }
                  picked.add(row);
               }
            }
            int contentRows = 0;
            for (SeedCriteria.StructureTarget t : resolveClashes(picked, autoNotes)) {
               if (criteria.structures.size() >= 6) {
                  break;
               }
               // THE CONTENT-ROW CAP, enforced here rather than trusted to the prompt. Each of
               // these assembles a structure for every candidate that survives the distance
               // filter, so a bundle stacking four of them does not search slowly, it effectively
               // does not finish. Two is the most that stays usable.
               if (t.building != null && ++contentRows > MAX_CONTENT_ROWS) {
                  String cant = "\"" + t.label + "\" was dropped — a search can only check "
                        + MAX_CONTENT_ROWS + " detailed building features at once without becoming too slow";
                  if (!autoNotes.contains(cant)) {
                     autoNotes.add(cant);
                  }
                  continue;
               }
               criteria.structures.add(t);
            }
         }
         if (root.has("biomes") && root.get("biomes").isJsonArray()) {
            for (var el : root.getAsJsonArray("biomes")) {
               if (!el.isJsonObject()) {
                  continue;
               }
               JsonObject o = el.getAsJsonObject();
               if (criteria.biomes.size() >= MAX_BIOMES) {
                  // NAMED, not silently dropped. "7 wood types near spawn" used to keep three and
                  // say nothing, so the search ran happily against less than half the wish and the
                  // player was told nothing was wrong.
                  String dropped = o.has("name") ? o.get("name").getAsString() : "one more biome";
                  note(autoNotes, "\"" + dropped + "\" was left out — a search can hold "
                        + MAX_BIOMES + " biomes at once, and every extra one makes a seed with ALL "
                        + "of them near spawn rarer than the last");
                  continue;
               }
               int asked = o.has("distance") ? readInt(o.get("distance")) : -1;
               if (asked > 0 && FableVisionConfig.seedFastMode) {
                  criteria.composed = true;
               } else if (asked > FableVisionConfig.seedRadius() && o.has("name")) {
                  // Same note the structures get. Biomes were the half that collapsed in silence.
                  note(autoNotes, "\"" + o.get("name").getAsString() + "\" was asked for up to " + asked
                        + " blocks out, but Exact mode means AT spawn — it was searched within "
                        + FableVisionConfig.seedRadius() + ". Switch to Fast mode for a wider spread.");
               }
               String err = addBiome(criteria, biomes,
                     o.has("name") ? o.get("name").getAsString() : "",
                     o.has("size") ? o.get("size").getAsString() : "any", autoNotes, clampDistance(asked));
               if (err != null) {
                  return new Outcome(null, err, null);
               }
            }
         }
         // Older single-biome shape — some replies still use it.
         if (criteria.biomes.isEmpty() && root.has("biome")) {
            String err = addBiome(criteria, biomes, root.get("biome").getAsString(), "any", autoNotes,
                  FableVisionConfig.seedRadius());
            if (err != null) {
               return new Outcome(null, err, null);
            }
         }
         // AFTER the biomes and structures arrays, on purpose: a pair is wired to the very objects
         // those arrays produced, so it has to see the finished lists. Reading it earlier would
         // create its own duplicates and then the wish would carry two of everything.
         if (root.has("adjacent") && root.get("adjacent").isJsonArray()) {
            for (var el : root.getAsJsonArray("adjacent")) {
               if (!el.isJsonObject() || criteria.adjacencies.size() >= 3) {
                  continue;
               }
               addAdjacency(criteria, catalog, biomes, el.getAsJsonObject(), autoNotes);
            }
         }
         // ONLY IF THE PLAYER SAID SO. This field was being filled in on wishes that had nothing to
         // do with slime — models given an optional object tend to populate it — and the result was
         // a slime line in the chat message of someone who never mentioned slime. The JSON is what
         // gets searched, but it does not get to invent a requirement: if the word is not in what
         // the player typed, the field is ignored. Checked against the raw wish rather than against
         // the model's own paraphrase, which would be the same source vouching for itself.
         boolean askedSlime = wish.contains("slime");
         if (root.has("slime") && root.get("slime").isJsonObject() && askedSlime) {
            JsonObject s = root.getAsJsonObject("slime");
            int n = s.has("count") ? readInt(s.get("count")) : 3;
            int within = s.has("within") ? readInt(s.get("within")) : SLIME_DEFAULT_WITHIN;
            n = Math.max(1, Math.min(n, SLIME_MAX_COUNT));
            within = Math.max(32, Math.min(within, SLIME_MAX_WITHIN));
            // SAY SO WHEN IT FILTERS NOTHING. One chunk in ten is a slime chunk and the chunks
            // inside a radius grow with its square, so a wide ask is true of every seed. Accepting
            // it silently would sell a decoration as a search — the same failure the "big" label
            // was fixed for.
            if (!SlimeChunks.narrows(n, within)) {
               note(autoNotes, n + " slime chunk" + (n == 1 ? "" : "s") + " within " + within
                     + " blocks is true of virtually every seed, so it didn't narrow anything — "
                     + "ask for them closer together if you want it to mean something");
            }
            criteria.slimes.add(new SeedCriteria.SlimeTarget(n, within));
         }
         if (root.has("exclude") && root.get("exclude").isJsonArray()) {
            for (var el : root.getAsJsonArray("exclude")) {
               JsonObject o = el.getAsJsonObject();
               addExclude(criteria, catalog, biomes, o.has("name") ? o.get("name").getAsString() : "");
            }
         }
         // A stronghold mention no longer adds anything to the search — there is nothing it could
         // add. It only earns an honest note.
         //
         // The second half used to be mentionsStronghold(root, "stronghold") — a key from a schema
         // no prompt has taught since the field was renamed, so no reply ever carried it. Replies are
         // never stored, so there was no old reply to stay compatible with either. What replaces it
         // is the check that does not depend on the model at all: the player's own words, the same
         // way the End note is decided.
         if (mentionsStronghold(root, "stronghold_mentioned") || mentionsStrongholdWords(wish)) {
            if (!autoNotes.contains(STRONGHOLD_CANT)) {
               autoNotes.add(STRONGHOLD_CANT);
            }
         }
      } catch (Exception e) {
         // Valid JSON of the wrong SHAPE — a field that should be an array arriving as a string,
         // say. Naming the exception is the difference between a report anyone can act on and a
         // sentence that only says the attempt failed.
         return new Outcome(null, "The AI's answer was the wrong shape (" + e.getClass().getSimpleName()
               + (e.getMessage() == null ? "" : ": " + trimTo(e.getMessage(), 40))
               + ") — try again, or pick below.", null);
      }

      String note = "";
      if (root.has("cant")) {
         try {
            note = root.get("cant").getAsString().trim();
         } catch (Exception ignored) {
         }
      }
      // NEVER APOLOGISE FOR THE BLACKSMITH. The prompt asks the model not to write one, but a limit
      // that depends on a model choosing to obey is not a limit — and the sentence is a bad one to
      // show, because the search it apologises for is a search that worked. Dropped here, and the
      // app's own ℹ explanation (added above) is what the player reads instead.
      if (askedBlacksmith && !note.isEmpty()) {
         String low = note.toLowerCase(Locale.ROOT);
         if (low.contains("blacksmith") || low.contains("smithy") || low.contains("forge")) {
            note = "";
         }
      }
      if (!note.isEmpty() && !autoNotes.contains(note)) {
         autoNotes.add(0, note);
      }
      // The chosen rows' own loot warnings are NOT added here any more, and that is the whole fix
      // for the note that kept appearing uninvited. Every row's cant is about loot or trades, and
      // adding them all meant that asking for "a village with an armorer" came back with "can't
      // check: what the armorer villager ends up trading" — an answer to a question the player had
      // not asked, attached to a search that had worked perfectly.
      //
      // The AI's own "cant" field above is the one that belongs here: the model writes it in
      // response to the player's actual words, so it only mentions loot when the player did. The
      // impossible list in the prompt is what teaches it to. Row cants remain on the rows, for the
      // one place they are genuinely useful — a player reading a single row in detail.
      String combined = String.join("; ", autoNotes);
      if (criteria.isEmpty()) {
         return new Outcome(null, combined.isEmpty()
               ? "The AI didn't find anything searchable in that wish — try naming a structure or biome."
               : "That can't be found by a seed search: " + combined, null);
      }
      return new Outcome(criteria, null, combined.isEmpty() ? null : combined);
   }

   /**
    * The wish object inside a reply that is not only that object — or null if there isn't one.
    *
    * THIS USED TO BE {@code indexOf('{')} TO {@code lastIndexOf('}')}, and that is the bug behind
    * "BEST survival seed" coming back as "Couldn't read the AI's answer". It works perfectly as
    * long as every brace in the reply belongs to the wish. A REASONING model (the one that broke it
    * was Groq's default, since removed) writes several paragraphs of working out first, and prose
    * about "the {@code structures} array" or a discarded draft object puts braces in front of the
    * real one. The span
    * from the first brace to the last then starts inside the thinking and ends inside the answer,
    * which is not JSON and never could be.
    *
    * {@link com.tensec.aiscreen.AiVision} strips reasoning tags from every reply, so in practice the
    * reply is the object alone. This is the second line of
    * defence, and it is the one that does not depend on a provider's cooperation: walk the reply,
    * find every span that parses as an object — matching braces by depth, skipping any inside a
    * string so a {@code "cant"} note containing one cannot throw the count off — and pick the one
    * that most looks like a wish.
    *
    * "THE FIRST ONE THAT PARSES" WOULD NOT DO, which is worth writing down because it is the
    * obvious version and it is wrong twice over. A model that reasons out loud drafts objects it
    * then discards ("maybe {"structures":[]} — no, the player wants more than that"), so the first
    * parseable object can be an empty draft, and taking it turns a good reply into "nothing
    * searchable in that wish". It is also not the last: the object that matters is the one carrying
    * the wish FIELDS, so they are what it is chosen by — recognised keys first, and the later one
    * on a tie, because the answer comes after the thinking.
    *
    * Returns null when nothing in the reply parses, which the caller reports along with what the
    * reply actually said.
    */
   static JsonObject extractJson(String reply) {
      JsonObject best = null;
      int bestScore = -1;
      for (int i = 0; i < reply.length(); i++) {
         if (reply.charAt(i) != '{') {
            continue;
         }
         int end = matchingBrace(reply, i);
         if (end < 0) {
            // Unclosed from here on — a reply the model was cut off in the middle of. Nothing after
            // this point can close it either, so there is nothing left to find.
            break;
         }
         JsonObject candidate;
         try {
            candidate = JsonParser.parseString(reply.substring(i, end + 1)).getAsJsonObject();
         } catch (Exception notThisOne) {
            continue;   // some other brace — a code fence, a sentence about JSON
         }
         int score = wishScore(candidate);
         // >= so that, between two objects scoring the same, the LATER one wins.
         if (score >= bestScore) {
            bestScore = score;
            best = candidate;
         }
      }
      return best;
   }

   /** Does this wish mention the End? Matched on the lower-cased text the player typed. */
   static boolean mentionsEnd(String lowerWish) {
      for (String w : END_WORDS) {
         if (lowerWish.contains(w)) {
            return true;
         }
      }
      return false;
   }

   /** How much this object looks like a wish: how many of the schema's own field names it has. */
   private static int wishScore(JsonObject o) {
      String[] keys = {"structures", "biomes", "adjacent", "exclude", "without", "slime",
            "stronghold_mentioned", "stronghold", "cant", "biome"};
      int score = 0;
      for (String k : keys) {
         if (o.has(k)) {
            score++;
         }
      }
      return score;
   }

   /** The index of the "}" closing the "{" at {@code open}, or -1 if it is never closed.
    *  String-aware, including escapes, so braces and quotes inside a note are just characters. */
   private static int matchingBrace(String s, int open) {
      int depth = 0;
      boolean inString = false;
      boolean escaped = false;
      for (int i = open; i < s.length(); i++) {
         char c = s.charAt(i);
         if (inString) {
            if (escaped) {
               escaped = false;
            } else if (c == '\\') {
               escaped = true;
            } else if (c == '"') {
               inString = false;
            }
            continue;
         }
         if (c == '"') {
            inString = true;
         } else if (c == '{') {
            depth++;
         } else if (c == '}' && --depth == 0) {
            return i;
         }
      }
      return -1;
   }

   /** The first line of a reply, short enough to show: what the AI said instead of a search. */
   private static String excerpt(String reply) {
      String first = reply.strip();
      int nl = first.indexOf('\n');
      if (nl > 0) {
         first = first.substring(0, nl).strip();
      }
      // 40, NOT 110. The excerpt is pasted into a message that then has to fit the feedback band,
      // and the band is four lines of a panel that can be 320 pixels wide. At 110 the message could
      // be longer than the space and get clipped — an error report cut off is the exact thing this
      // excerpt was added to prevent. 40 characters is enough to tell a refusal from a code fence
      // from a JSON fragment, which is all it is for; layoutDiag holds the total to the band.
      return first.isEmpty() ? "(nothing at all)" : "\"" + trimTo(first, 40) + "\"";
   }

   private static String trimTo(String s, int max) {
      return s.length() <= max ? s : s.substring(0, max - 1) + "…";
   }

   /**
    * The catalog row for a name the AI returned: exact label first, then a loosened match.
    *
    * The loosening exists because the failure it prevents is expensive and the risk is nil. A
    * reply of "Igloo Basement" or "igloo with basements" means exactly one row and nothing else,
    * but an exact-string check rejects it and the player is told their wish isn't searchable.
    * Comparison is on letters and digits only, with the filler words and a trailing plural "s"
    * dropped from each word — so it can collapse two spellings of the same row, and cannot
    * collapse two different rows (no two labels in the catalog share a key).
    */
   /**
    * How many rows demanding a specific building or room one search may stack.
    *
    * Every one of these assembles a structure for each candidate that survives the distance
    * filter, and assembly is the expensive half of the whole finder — a village takes about a
    * second. One is comfortable, two roughly doubles the work, and four turns a bundle from "slow"
    * into "never finishes". The prompt asks the AI to respect this; {@link #parse} enforces it,
    * because a limit that depends on a model choosing to obey is not a limit.
    */
   public static final int MAX_CONTENT_ROWS = 2;

   /**
    * How many of one thing a wish may ask for, capped for the same reason and by the same rule.
    *
    * A count is not free. The funnel stops at the first candidate that satisfies a row; asking for
    * n means it keeps going, so n confirms instead of one — and on a row that names a building
    * inside, n ASSEMBLIES instead of one, each around 700ms. Four plain structures near spawn is a
    * demanding but reachable wish; four villages each with a named building is not a search, it is
    * a hang. The plain cap is the higher of the two because its extra work is microseconds.
    */
   public static final int MAX_COUNT_PLAIN = 4;

   /**
    * How many biomes one wish may hold, raised from 3 in 1.39.0.
    *
    * The old cap of 3 silently threw away over half of "7 wood types near spawn" and said nothing,
    * which is the worst of both outcomes: the wish was not searched and the player was not told.
    *
    * It is a cap rather than "as many as you like", but NOT for the reason it looks like. Each
    * biome is a fresh {@code findBiomeHorizontal}, and measured with {@code gradlew repro}, three
    * biomes at radius 1000 ran at 72 seeds/sec in Exact mode — the same 72 seeds/sec a plain
    * structure search manages, because Exact is floored by {@code findSpawnPosition} and the biome
    * lookups vanish underneath it. A few more cost close to nothing.
    *
    * What bites is RARITY: every added biome multiplies the odds down, and six distinct biomes near
    * one spawn is already demanding. Six is where an answer still arrives in a sane time. Past that
    * the extras are named in the notes instead of dropped in silence.
    */
   public static final int MAX_BIOMES = 6;
   public static final int MAX_COUNT_CONTENT = 2;

   /**
    * Slime-chunk defaults and caps. The default reach is deliberately small and it is MEASURED:
    * {@code slimeDiag} found 3 slime chunks within 128 blocks in 100% of 2000 seeds and within 64
    * in 94%, so 128 would have made the app's own default a filter that filters nothing.
    */
   public static final int SLIME_DEFAULT_WITHIN = SlimeChunks.PICKER_WITHIN;
   public static final int SLIME_MAX_WITHIN = 512;
   public static final int SLIME_MAX_COUNT = 12;

   /** Widest a single item in a composed bundle may reach. Beyond this the distance stops meaning
    *  "near spawn" at all, and the stage-0 scan starts costing more than the search saves. */
   private static final int MAX_BUNDLE_DISTANCE = 2000;

   /**
    * The reach for one item: what the AI asked for, kept honest.
    *
    * A missing or nonsense number falls back to the app's own setting, which is what a simple
    * one-structure wish has always used. A real number is clamped — the floor stops a bundle
    * asking for six things inside 20 blocks, which is not a rare seed but an impossible one, and
    * the ceiling stops "near spawn" quietly becoming "somewhere in the world". Per-dimension
    * floors (Nether, End) are applied afterwards by {@code withRadius}.
    */
   /**
    * The spread a reply asks for, as "village 250, portal 350", when TWO OR MORE of its items carry
    * a distance past the Exact radius — or null when it is not a spread-out bundle.
    */
   static String spreadInExact(JsonObject root) {
      List<String> far = new ArrayList<>();
      for (String field : new String[]{"structures", "biomes", "without"}) {
         if (!root.has(field) || !root.get(field).isJsonArray()) {
            continue;
         }
         for (var el : root.getAsJsonArray(field)) {
            if (!el.isJsonObject()) {
               continue;
            }
            JsonObject o = el.getAsJsonObject();
            int d = o.has("distance") ? readInt(o.get("distance")) : -1;
            if (d > FableVisionConfig.seedRadius()) {
               String name = o.has("name") ? o.get("name").getAsString().replace('_', ' ') : "something";
               far.add(name + " " + d);
            }
         }
      }
      if (far.size() < 2) {
         return null;
      }
      return String.join(", ", far.subList(0, Math.min(2, far.size()))) + (far.size() > 2 ? ", …" : "");
   }

   private static int clampDistance(int asked) {
      if (asked <= 0) {
         return FableVisionConfig.seedRadius();
      }
      // EXACT MODE MEANS AT SPAWN, and the player's toggle outranks the model's guess. Someone who
      // asked for "a trial chamber and a cherry grove exact at spawn" got the grove at spawn and the
      // chamber 300 blocks out, because the reply carried "distance": 300 and this accepted it — so
      // the one control on the screen that promises something specific quietly did not.
      //
      // An explicit distance is an inference from the wording; the mode toggle is an instruction.
      // In Exact mode every item is held to the same reach the picker would have used, which is
      // also what makes the two paths agree. Fast mode still honours per-item distances, because
      // that is the mode whose whole purpose is "near spawn, spread out a bit".
      if (!FableVisionConfig.seedFastMode) {
         return FableVisionConfig.seedRadius();
      }
      return Math.max(64, Math.min(MAX_BUNDLE_DISTANCE, asked));
   }

   /**
    * A key that cannot collide with anything the model writes, used to carry "this entry came out
    * of the without list" through the one loop that handles both. A separate parallel list of
    * booleans would have to stay aligned with a list that gets reordered and filtered, which is
    * the same mistake the per-entry distance already avoids by living on the entry.
    */
   private static final String WITHOUT_FLAG = "__fablevision_without";

   /** The "structures" and "without" arrays as one list of entries, the latter flagged. Malformed
    *  or missing arrays contribute nothing rather than throwing — a wish with one good half is
    *  still worth searching. */
   private static List<JsonObject> mergeWants(JsonObject root) {
      List<JsonObject> out = new ArrayList<>();
      collect(root, "structures", false, out);
      collect(root, "without", true, out);
      return out;
   }

   private static void collect(JsonObject root, String key, boolean negate, List<JsonObject> into) {
      if (!root.has(key) || !root.get(key).isJsonArray()) {
         return;
      }
      for (var el : root.getAsJsonArray(key)) {
         if (!el.isJsonObject()) {
            continue;
         }
         JsonObject o = el.getAsJsonObject().deepCopy();
         if (negate) {
            o.addProperty(WITHOUT_FLAG, true);
         }
         into.add(o);
      }
   }

   /** Reads a number however the model spelled it ("300", 300, 300.0). */
   private static int readInt(com.google.gson.JsonElement el) {
      try {
         return el.getAsJsonPrimitive().isNumber() ? el.getAsInt()
               : (int) Double.parseDouble(el.getAsString().trim());
      } catch (Exception notANumber) {
         return -1;
      }
   }

   /** Reads a boolean-ish flag however the model spelled it (true, 1, "true"). */
   /** Does the player's own (lower-cased) wish mention a stronghold, its portal, or eyes? */
   static boolean mentionsStrongholdWords(String lowerWish) {
      return lowerWish.contains("stronghold") || lowerWish.contains("end portal")
            || lowerWish.contains("eye of ender") || lowerWish.contains("eyes of ender")
            || lowerWish.contains("ender eye");
   }

   private static boolean mentionsStronghold(JsonObject root, String key) {
      if (!root.has(key)) {
         return false;
      }
      try {
         var prim = root.get(key).getAsJsonPrimitive();
         return prim.isBoolean() ? prim.getAsBoolean()
               : prim.isNumber() ? prim.getAsInt() > 0
               : Boolean.parseBoolean(prim.getAsString());
      } catch (Exception notABoolean) {
         return false;
      }
   }

   private static SeedCriteria.StructureTarget findRow(List<SeedCriteria.StructureTarget> catalog, String name) {
      if (name == null || name.isBlank()) {
         return null;
      }
      for (SeedCriteria.StructureTarget t : catalog) {
         if (t.label.equalsIgnoreCase(name.trim())) {
            return t;
         }
      }
      String want = key(name);
      if (want.isEmpty()) {
         return null;
      }
      for (SeedCriteria.StructureTarget t : catalog) {
         if (key(t.label).equals(want)) {
            return t;
         }
      }
      return null;
   }

   private static String key(String s) {
      StringBuilder sb = new StringBuilder();
      for (String word : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
         if (word.isEmpty() || word.equals("with") || word.equals("a") || word.equals("an")
               || word.equals("the") || word.equals("and")) {
            continue;
         }
         // Plural or not is never the difference between two rows ("Cages" / "Cage").
         sb.append(word.length() > 3 && word.endsWith("s") ? word.substring(0, word.length() - 1) : word);
      }
      return sb.toString();
   }

   /**
    * Drops rows that are about the SAME structure as another chosen row, keeping the most
    * specific one and saying which was dropped.
    *
    * Why this cannot be left in: two targets are ANDed as two separate finds. "Warm Ocean Ruins"
    * plus "Large Ocean Ruin" is satisfied by a warm ruin over here and a large one over there —
    * both true, and not remotely what someone picturing one large warm ruin asked for. The engine
    * demands ONE piece token per structure, so a single row is the only honest answer, and the
    * note names the half that was not searched.
    *
    * Same-structure is decided by whether the rows' id sets overlap, which is read from the live
    * catalog. Villages span five ids and igloos one; nothing here needs to know that.
    */
   private static List<SeedCriteria.StructureTarget> resolveClashes(List<SeedCriteria.StructureTarget> picked,
                                                                    List<String> notes) {
      List<SeedCriteria.StructureTarget> kept = new ArrayList<>();
      for (SeedCriteria.StructureTarget t : picked) {
         SeedCriteria.StructureTarget clash = null;
         for (SeedCriteria.StructureTarget k : kept) {
            if (k.dim == t.dim && overlaps(k, t)) {
               clash = k;
               break;
            }
         }
         if (clash == null) {
            kept.add(t);
            continue;
         }
         SeedCriteria.StructureTarget merged = merge(clash, t);
         if (merged != null) {
            kept.set(kept.indexOf(clash), merged);
            continue;   // both halves survive — nothing to warn about
         }
         // Ties keep the row the AI listed first, which is the one closest to how the wish was
         // worded — the player put it first for a reason.
         SeedCriteria.StructureTarget win = specificity(t) > specificity(clash) ? t : clash;
         SeedCriteria.StructureTarget lose = win == t ? clash : t;
         kept.set(kept.indexOf(clash), win);
         if (absorbs(win, lose)) {
            continue; // nothing was lost, so there is nothing to warn about
         }
         String cant = impossibleTogether(win, lose)
               ? "\"" + lose.label + "\" and \"" + win.label + "\" cannot both be true of one"
                 + " village: a zombie village builds from its own small house set and most"
                 + " profession buildings have no zombie version at all, so this searched \""
                 + win.label + "\""
               : "\"" + lose.label + "\" too — one structure can only be checked for one"
                 + " feature, so this searched \"" + win.label + "\"";
         if (!notes.contains(cant)) {
            notes.add(cant);
         }
      }
      return kept;
   }

   /**
    * The two halves of a wish that CAN be asked at once, combined — or null when they cannot.
    *
    * There is exactly one safe combination and it is the one players actually ask for: a village
    * with a profession building that is NOT the abandoned kind. The engine has always been able to
    * carry both (the building is checked by assembly, the zombie start by the cheap dice replay);
    * nothing had ever put them together.
    *
    * The mirror of it — a profession building AND the abandoned kind — is refused, and see
    * {@link SeedCriteria.StructureTarget#withZombie} for the measurement that says why: zombie
    * villages have their own much smaller house set and most professions have no zombie template
    * anywhere. Merging those would produce a search with no possible answer, which is worse than
    * dropping half the wish and saying so.
    */
   private static SeedCriteria.StructureTarget merge(SeedCriteria.StructureTarget a,
                                                     SeedCriteria.StructureTarget b) {
      SeedCriteria.StructureTarget withBuilding = a.building != null ? a : b.building != null ? b : null;
      SeedCriteria.StructureTarget other = withBuilding == a ? b : a;
      if (withBuilding == null || other.building != null) {
         return null;
      }
      if (withBuilding.zombie != SeedCriteria.ZombieMode.ANY
            || other.zombie != SeedCriteria.ZombieMode.NORMAL) {
         return null;
      }
      return withBuilding.alsoNotAbandoned();
   }

   /** True when the clash is the village/zombie one, which is not "we can only check one thing"
    *  but "these two things never co-exist" — a different sentence and a more useful one. */
   private static boolean impossibleTogether(SeedCriteria.StructureTarget win,
                                             SeedCriteria.StructureTarget lose) {
      return (win.building != null && lose.zombie == SeedCriteria.ZombieMode.ABANDONED)
            || (lose.building != null && win.zombie == SeedCriteria.ZombieMode.ABANDONED);
   }

   /**
    * True when the winning row already asks for everything the losing row did, so dropping the
    * loser costs the player nothing and deserves no warning.
    *
    * Two conditions, and both are needed. The loser must demand no piece and no zombie start —
    * otherwise a real request is being dropped. And the winner's structure ids must all be inside
    * the loser's, so the winner is at least as narrow: "Igloo with Basement" beside "Igloo" is the
    * same igloo and silent, but "Large Ocean Ruin" beside "Warm Ocean Ruins" is not — Large covers
    * warm AND cold, so the word "warm" really was dropped and the player has to be told.
    */
   private static boolean absorbs(SeedCriteria.StructureTarget win, SeedCriteria.StructureTarget lose) {
      if (lose.building != null || lose.zombie != SeedCriteria.ZombieMode.ANY) {
         return false;
      }
      return lose.wanted.containsAll(win.wanted);
   }

   private static boolean overlaps(SeedCriteria.StructureTarget a, SeedCriteria.StructureTarget b) {
      for (Identifier id : a.wanted) {
         if (b.wanted.contains(id)) {
            return true;
         }
      }
      return false;
   }

   /** How narrow a row's question is: demanding a piece or a zombie start beats demanding
    *  neither, and a row covering fewer structure ids beats one covering more. */
   private static int specificity(SeedCriteria.StructureTarget t) {
      int score = 0;
      if (t.building != null) {
         score += 4;
      }
      if (t.zombie != SeedCriteria.ZombieMode.ANY) {
         score += 4;
      }
      return score + Math.max(0, 4 - t.wanted.size());
   }

   /** Adds a "must NOT be nearby" target (structure or biome). Unknown names are ignored so
    *  one bad exclude never sinks the whole wish. Uses a wider buffer than the positive radius. */
   private static void addExclude(SeedCriteria criteria, List<SeedCriteria.StructureTarget> catalog, List<Identifier> allowed, String name) {
      if (name == null || name.isBlank()) {
         return;
      }
      int radius = Math.max(FableVisionConfig.seedRadius(), 256);
      String n = name.trim();
      SeedCriteria.StructureTarget row = findRow(catalog, n);
      if (row != null && row.special == SeedCriteria.Special.NORMAL) {
         criteria.excludeStructures.add(row.withRadius(radius));
         return;
      }
      String norm = n.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_');
      for (Identifier b : allowed) {
         if (b.getPath().equals(norm)) {
            criteria.excludeBiomes.add(new SeedCriteria.BiomeTarget(
                  ResourceKey.create(Registries.BIOME, b), SeedCatalogCache.prettify(b.getPath()),
                  SeedCatalog.biomeDim(b), radius, 0));
            return;
         }
      }
   }

   /** Validates one biome pick into the criteria; returns an error message or null. */
   private static String addBiome(SeedCriteria criteria, List<Identifier> allowed, String name, String size,
                                  List<String> notes, int radius) {
      String norm = name == null ? "" : name.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_').trim();
      if (norm.isEmpty()) {
         return null;
      }
      Identifier found = null;
      for (Identifier b : allowed) {
         if (b.getPath().equals(norm)) {
            found = b;
            break;
         }
      }
      if (found == null) {
         return "Biome \"" + name + "\" isn't searchable — pick it yourself below.";
      }
      for (SeedCriteria.BiomeTarget existing : criteria.biomes) {
         if (existing.biome.identifier().equals(found)) {
            return null;
         }
      }
      // The AI is told to use any|small|big|huge, but replies routinely echo the player's own word
      // ("large", "giant", "tiny") — map them here so a size wish ALWAYS takes effect.
      String s = size == null ? "any" : size.toLowerCase(Locale.ROOT).trim();
      BiomeShape.Size tier = switch (s) {
         case "huge", "giant", "massive", "ginormous", "mega", "enormous", "vast",
              "big", "large", "wide", "broad", "sprawling" -> BiomeShape.Size.LARGE;
         case "small", "tiny", "little", "compact", "narrow" -> BiomeShape.Size.SMALL;
         default -> BiomeShape.Size.ANY;
      };
      // The threshold is this biome's own measured p33/p67 — never a global number, because the
      // median pale garden spans 32 blocks and the median warm ocean spans 480. A biome nobody
      // measured, or one whose sizes all came out the same, gets no bounds and an honest note
      // instead of a filter that would silently accept everything.
      int[] span = BiomeShape.bounds(found.getPath(), tier);
      if (tier != BiomeShape.Size.ANY && span[0] == 0 && span[1] == 0 && notes != null) {
         String cant = "\"" + s + " " + SeedCatalogCache.prettify(found.getPath())
               + "\" — that biome is all one size, so the size was ignored";
         if (!notes.contains(cant)) {
            notes.add(cant);
         }
      }
      criteria.biomes.add(new SeedCriteria.BiomeTarget(
            ResourceKey.create(Registries.BIOME, found), SeedCatalogCache.prettify(found.getPath()),
            SeedCatalog.biomeDim(found), radius, span[0], span[1]));
      return null;
   }

   /**
    * One {@code "adjacent"} entry into a real pair, or an honest note saying why it was not.
    *
    * Both ends are looked up among what the wish ALREADY chose before anything is created, because
    * the funnel matches an adjacency's anchor to a biome by identity — a second, equal-looking
    * BiomeTarget would leave the anchor's found position unreachable at run time and the pair would
    * fail every seed. When an end genuinely was not listed (the model named it only in "adjacent"),
    * it is added here so the wish still works rather than being thrown away on a technicality.
    */
   private static void addAdjacency(SeedCriteria criteria, List<SeedCriteria.StructureTarget> catalog,
                                    List<Identifier> allowed, JsonObject o, List<String> notes) {
      String aName = o.has("a") ? o.get("a").getAsString() : "";
      String bName = o.has("b") ? o.get("b").getAsString() : "";
      SeedCriteria.BiomeTarget anchor = existingBiome(criteria, allowed, aName);
      if (anchor == null) {
         // "a" has to be a biome; a reply that put a structure there names the pair backwards.
         SeedCriteria.BiomeTarget swapped = existingBiome(criteria, allowed, bName);
         if (swapped != null) {
            String tmp = aName;
            aName = bName;
            bName = tmp;
            anchor = swapped;
         }
      }
      if (anchor == null) {
         // NEITHER END IS A BIOME, which used to end the pair here. Two structures is a real pair
         // now — "a fortress next to a bastion" is the question people actually ask — and it is
         // measured from the structure the funnel already found, so it is cheaper than a biome
         // anchor rather than more expensive.
         SeedCriteria.StructureTarget sa = existingStructure(criteria, catalog, aName);
         SeedCriteria.StructureTarget sb = existingStructure(criteria, catalog, bName);
         if (sa != null && sb != null) {
            int apart = o.has("within") ? readInt(o.get("within")) : -1;
            if (apart <= 0) {
               apart = BiomeShape.NEXT_TO_STRUCTURE;
            }
            String pairErr = criteria.addStructurePair(sa, sb, apart);
            if (pairErr != null) {
               note(notes, pairErr);
            }
            return;
         }
         note(notes, "\"" + aName + " next to " + bName + "\" — at least one of those isn't "
               + "something the search can look for, so the rest of the wish was searched without "
               + "the pairing");
         return;
      }
      int within = o.has("within") ? readInt(o.get("within")) : -1;
      SeedCriteria.BiomeTarget otherBiome = existingBiome(criteria, allowed, bName);
      SeedCriteria.StructureTarget otherStructure = null;
      if (otherBiome == null) {
         otherStructure = existingStructure(criteria, catalog, bName);
         if (otherStructure == null) {
            note(notes, "\"next to " + bName + "\" — that isn't something the search can look for, "
                  + "so the rest of the wish was searched without it");
            return;
         }
      }
      if (within <= 0) {
         within = otherBiome != null ? BiomeShape.NEXT_TO_BIOME : BiomeShape.NEXT_TO_STRUCTURE;
      }
      String err = criteria.addAdjacency(anchor, otherBiome, otherStructure, within);
      if (err != null) {
         note(notes, err);
         return;
      }
      if (within < BiomeShape.TIGHT) {
         // ℹ: a warning about the wait, not a part of the wish that was dropped. Unmarked, it sent the
         // join message down the long form for a search that did everything it was asked (1.44.2).
         note(notes, PLAIN_NOTE + within + " blocks apart is tighter than most seeds manage — this may take a while");
      }
   }

   /** The BiomeTarget the wish already holds for this name, adding one if it named a real biome
    *  the wish had not listed. Null when the name is not a biome at all. */
   private static SeedCriteria.BiomeTarget existingBiome(SeedCriteria criteria, List<Identifier> allowed,
                                                         String name) {
      String norm = name == null ? "" : name.toLowerCase(Locale.ROOT)
            .replace("minecraft:", "").replace(' ', '_').trim();
      if (norm.isEmpty()) {
         return null;
      }
      Identifier found = null;
      for (Identifier b : allowed) {
         if (b.getPath().equals(norm)) {
            found = b;
            break;
         }
      }
      if (found == null) {
         return null;
      }
      for (SeedCriteria.BiomeTarget bt : criteria.biomes) {
         if (bt.biome.identifier().equals(found)) {
            return bt;
         }
      }
      SeedCriteria.BiomeTarget bt = new SeedCriteria.BiomeTarget(
            ResourceKey.create(Registries.BIOME, found), SeedCatalogCache.prettify(found.getPath()),
            SeedCatalog.biomeDim(found), FableVisionConfig.seedRadius(), 0);
      criteria.biomes.add(bt);
      return bt;
   }

   /** Same for the structure end: the instance the wish already holds, or a new one added to it. */
   private static SeedCriteria.StructureTarget existingStructure(SeedCriteria criteria,
                                                                 List<SeedCriteria.StructureTarget> catalog,
                                                                 String name) {
      SeedCriteria.StructureTarget row = findRow(catalog, name);
      if (row == null || row.special != SeedCriteria.Special.NORMAL) {
         return null;
      }
      for (SeedCriteria.StructureTarget t : criteria.structures) {
         if (t.label.equals(row.label) || overlaps(t, row)) {
            return t;   // the same structure by another row's name — pair with what is being searched
         }
      }
      SeedCriteria.StructureTarget added = row.withRadius(FableVisionConfig.seedRadius());
      criteria.structures.add(added);
      return added;
   }

   private static void note(List<String> notes, String text) {
      if (notes != null && !notes.contains(text)) {
         notes.add(text);
      }
   }

   private WishParser() {
   }
}
