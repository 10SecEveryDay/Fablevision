package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import com.fablevision.client.seedfinder.SeedCriteria.Special;
import com.fablevision.client.seedfinder.SeedCriteria.StructureTarget;
import com.fablevision.client.seedfinder.SeedCriteria.ZombieMode;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalStructure;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

/**
 * The FIXED, curated list of exactly what the finder searches for — the user's canonical
 * structure and biome sets, in their order, nothing more or less. Built against the live
 * registry so a spec only appears if its structure/biome actually exists in this version
 * (all vanilla ones do); datapack extras are intentionally NOT auto-added here.
 *
 * Each structure spec matches by id path + dimension, so one label can span several
 * structure sets (a "Surface Village" is five village sets) and several structures in a
 * set (Shipwreck = beached + sunken). The Dungeon is flagged {@link Special} — it has no
 * seed-level position to search for at all.
 *
 * TWO dimensions are on the list. Nether targets are measured from the seed's spawn divided by 8
 * (where a portal lit at spawn comes out). The End is not searched at all — see
 * {@link SeedCriteria.Dim} for the phantom End City that removed it, and {@link #dimensionOf},
 * which is where an End structure set stops being a candidate. The Dungeon is also left off,
 * because it is scattered per-chunk as terrain generates and has no seed-level position at all.
 *
 * ONE row carries a {@code building} token that is NOT a search: Nether Fossil. For it, placement
 * and biome both saying yes is not enough — the structure's own rules can still decline — so it
 * demands a piece every one of them has, which turns the deferred layout gate into proof that one
 * really generated there. End City was the other row of that kind, and its token was NOT enough,
 * which is the whole lesson: an existence token is a patch over a gap in the guarantee, and it is
 * only as good as the piece happening to be absent whenever the structure is. Everything else on
 * the list generates wherever placement says it does, measured by {@code gradlew villageDiag}.
 */
public final class SeedCatalog {

   /**
    * One catalog row before it's resolved against the registry.
    *
    * {@code cant} is the SHORT form of {@code note} — a few words naming the one thing the row
    * cannot promise. The picker shows one row at a time and the full paragraph is worth reading
    * there; an AI wish can choose three content rows at once, and three paragraphs joined into a
    * one-line banner is a sentence cut off mid-word. The wish path joins these instead.
    *
    * The eight-argument constructor is kept so the plain rows — the ones with nothing to warn
    * about — stay unchanged.
    */
   private record Spec(String label, Dim dim, int sampleY, Predicate<String> match,
                       ZombieMode zombie, Special special, String note, String building, String cant,
                       int rarityPct, String placement) {
      Spec(String label, Dim dim, int sampleY, Predicate<String> match,
           ZombieMode zombie, Special special, String note, String building, String cant,
           int rarityPct) {
         this(label, dim, sampleY, match, zombie, special, note, building, cant, rarityPct, null);
      }

      Spec(String label, Dim dim, int sampleY, Predicate<String> match,
           ZombieMode zombie, Special special, String note, String building, String cant) {
         this(label, dim, sampleY, match, zombie, special, note, building, cant, -1);
      }

      Spec(String label, Dim dim, int sampleY, Predicate<String> match,
           ZombieMode zombie, Special special, String note, String building) {
         this(label, dim, sampleY, match, zombie, special, note, building, "", -1);
      }

      static Spec structure(String label, Dim dim, int sampleY, Predicate<String> match) {
         return new Spec(label, dim, sampleY, match, ZombieMode.ANY, Special.NORMAL, "", null, "", -1);
      }

      /** The same spec plus its MEASURED hit rate — how many of that structure really have this
       *  feature. Only ever a number a diagnostic in this project printed; see
       *  {@link SeedCriteria.StructureTarget#rarityPct} for why guessing one is worse than none. */
      Spec rate(int pct) {
         return new Spec(label, dim, sampleY, match, zombie, special, note, building, cant, pct, placement);
      }

      /** The same spec narrowed to ruined-portal variants CONFIGURED to land this way. Free: the
       *  answer is in the structure's own setup list, so nothing is assembled. */
      Spec placed(String verticalPlacement) {
         return new Spec(label, dim, sampleY, match, zombie, special, note, building, cant,
               rarityPct, verticalPlacement);
      }
   }

   private static Predicate<String> eq(String s) {
      return p -> p.equals(s);
   }

   private static Predicate<String> pre(String s) {
      return p -> p.startsWith(s);
   }

   // The list, in the user's order.
   private static final List<Spec> STRUCTURE_SPECS = List.of(
      Spec.structure("Surface Village", Dim.OVERWORLD, 64, pre("village")),
      // The armorer house — lava, blast furnace, stone hut; the closest thing modern villages
      // have to the old blacksmith. One token covers every village type because each biome
      // spells the building differently (plains_armorer_house_1, desert_armorer_1,
      // snowy_armorer_house_2, taiga_armorer_2 …); villageDiag confirms it fires in all five.
      new Spec("Village with Armorer", Dim.OVERWORLD, 64, pre("village"), ZombieMode.ANY, Special.NORMAL,
            "The armorer's house (lava + blast furnace). Read from the village's real building "
            + "list, so the BUILDING is certain — the villager has to grow into the job, and what "
            + "he trades is not checked. Verify with /locate structure minecraft:village_plains.",
            "armorer", "what the armorer villager ends up trading").rate(30),
      // ── The rest of the village professions ───────────────────────────────────────────
      // Every token below was DERIVED by `gradlew professionDiag`, which asks three questions the
      // plains-only census cannot: does a file with this spelling exist, does any template pool
      // reference it, and does it fire when all five village types are really assembled. Two of
      // the obvious spellings are dead on the first question alone — "toolsmith" matches 0 files
      // (the asset is tool_smith) and "librarian" matches 0 (the asset is library). Typing either
      // would have shipped a row that silently never matches.
      //
      // The tokens are deliberately SHORTER than the file names, because each biome spells the
      // building its own way and one token has to span them: "butcher" covers plains'
      // butcher_shop and savanna's butchers_shop, "cartographer" covers plains' cartographer_1
      // and desert's cartographer_house_1.
      new Spec("Village with Toolsmith", Dim.OVERWORLD, 64, pre("village"), ZombieMode.ANY, Special.NORMAL,
            "The toolsmith's house — read from the village's real building list, so the BUILDING "
            + "is certain. The villager still has to grow into the job, and what he trades is NOT "
            + "checked. Present in all five village types.",
            "tool_smith", "what the toolsmith villager ends up trading").rate(18),
      // CORRECTED 1.43.0. This comment used to say there is no snowy weaponsmith template, and the
      // note told players every match would be a non-snowy village. Both were wrong in the same way
      // "toolsmith" once was: the snowy file is spelled snowy_weapon_smith_1, the snowy pool builds
      // it, and it is one of the four houses with the lava and the chest. The token now lists both
      // spellings (see VillageLayout.tokenMatches), and smithDiag fails if a smith house is missed.
      // Re-measured with both spellings (professionDiag verify, 200 villages): 38%, snowy 37%.
      new Spec("Village with Weaponsmith", Dim.OVERWORLD, 64, pre("village"), ZombieMode.ANY, Special.NORMAL,
            "The weaponsmith's house (grindstone). Read from the real building list, so the "
            + "BUILDING is certain; trades are NOT checked. Present in all five village types. Only "
            + "some versions of the house have the lava and the loot chest — the plains, desert, "
            + "snowy and one of the two savanna houses; the taiga ones are grindstone-only.",
            "weaponsmith|weapon_smith", "what the weaponsmith villager ends up trading").rate(38),
      // CORRECTED 2026-08-12. This comment said "the rarest of the six by a clear margin" and the
      // note repeated it, and the census disagrees: the butcher is 24% of villages, which is
      // middling — the shepherd (9%) and the fisherman (7%) are the rare ones, and the stable is
      // 2% because only plains villages have the building at all. The claim was written before
      // any of the eleven professions had been measured; the numbers now live on the rows
      // themselves (see .rate below) so the prose and the data cannot drift apart again.
      new Spec("Village with Butcher", Dim.OVERWORLD, 64, pre("village"), ZombieMode.ANY, Special.NORMAL,
            "The butcher's shop (smoker + the meat on the wall) — about a quarter of villages have "
            + "one. Read from the real building list, so the BUILDING is certain; trades are NOT "
            + "checked. Present in all five village types.",
            "butcher", "what the butcher villager ends up trading").rate(24),
      new Spec("Village with Librarian", Dim.OVERWORLD, 64, pre("village"), ZombieMode.ANY, Special.NORMAL,
            "The library (lectern + bookshelves). Read from the village's real building list, so "
            + "the BUILDING is certain — the villager has to take the job before he sells any "
            + "enchanted books, and what he offers is NOT checked. In all five village types.",
            "library", "what the librarian ends up trading — enchanted books are not checked").rate(15),
      new Spec("Village with Cartographer", Dim.OVERWORLD, 64, pre("village"), ZombieMode.ANY, Special.NORMAL,
            "The cartographer's house (cartography table). Read from the real building list, so "
            + "the BUILDING is certain; the villager's job and his maps are NOT checked. Present "
            + "in all five village types.",
            "cartographer", "what the cartographer ends up trading — woodland/ocean maps are not checked").rate(42),
      // Token is folder-shaped rather than the bare word, and the measurement is why: "stable"
      // matches 62 template files this version ships and only THREE are villages — the rest are
      // the bastion's hoglin stables and the trail-ruins ones. A village row can only ever read
      // village pieces, so the bare word would have worked, but "_stable_" is true on its own
      // terms and does not depend on that argument holding forever.
      new Spec("Village with Stables", Dim.OVERWORLD, 64, pre("village"), ZombieMode.ANY, Special.NORMAL,
            "The stables (the open hay-and-fence building). NOTE: only PLAINS villages have a "
            + "stable building — the other four village types have no template for it — so every "
            + "match will be a plains village. Read from the real building list, so the BUILDING "
            + "is certain; whether a horse actually spawned in it is NOT checked.",
            "_stable_", "whether any horses are actually in the stables").rate(2),
      // ── Batch two, same derivation (gradlew professionDiag) ───────────────────────────
      // Three more naive spellings died on the file check: "leatherworker" and "leather" both
      // match zero templates (the building is a tannery) and so does "fisherman" (the asset says
      // fisher). "stonemason" is dead too. Every token below is the spelling the assets use.
      new Spec("Village with Mason", Dim.OVERWORLD, 64, pre("village"), ZombieMode.ANY, Special.NORMAL,
            "The mason's house (stonecutter). Read from the village's real building list, so the "
            + "BUILDING is certain; the villager's job and trades are NOT checked. Present in all "
            + "five village types.",
            "mason", "what the mason ends up trading").rate(33),
      // Called the LEATHERWORKER in game and the tannery in the files — the row is named for the
      // player's word and the token for the asset, which is the whole point of keeping them apart.
      new Spec("Village with Leatherworker", Dim.OVERWORLD, 64, pre("village"), ZombieMode.ANY, Special.NORMAL,
            "The tannery — the leatherworker's building (cauldron + leather). Read from the real "
            + "building list, so the BUILDING is certain; trades are NOT checked. In all five "
            + "village types.",
            "tannery", "what the leatherworker ends up trading").rate(29),
      new Spec("Village with Fletcher", Dim.OVERWORLD, 64, pre("village"), ZombieMode.ANY, Special.NORMAL,
            "The fletcher's house (fletching table). Read from the real building list, so the "
            + "BUILDING is certain; trades are NOT checked. In all five village types.",
            "fletcher", "what the fletcher ends up trading").rate(18),
      // Second-rarest of the eleven at 9% (only the fisherman is rarer, and only the stable is
      // rarer still because it exists in one village type out of five).
      new Spec("Village with Shepherd", Dim.OVERWORLD, 64, pre("village"), ZombieMode.ANY, Special.NORMAL,
            "The shepherd's house (loom). One of the least common village buildings — under one "
            + "village in ten — so expect a longer search. Read from the real building list, so "
            + "the BUILDING is certain; trades are NOT checked. In all five village types.",
            "shepherd", "what the shepherd ends up trading").rate(9),
      new Spec("Village with Fisherman", Dim.OVERWORLD, 64, pre("village"), ZombieMode.ANY, Special.NORMAL,
            "The fisher cottage (barrel + the fishing spot). Read from the real building list, so "
            + "the BUILDING is certain; trades are NOT checked. Every village type has one, though "
            + "it is uncommon in plains.",
            "fisher", "what the fisherman ends up trading").rate(7),
      Spec.structure("Pillager Outpost", Dim.OVERWORLD, 64, eq("pillager_outpost")),
      // An outpost is a tower plus a handful of OPTIONAL side pieces the jigsaw scatters round its
      // base: cages, tents, target dummies, log piles. Which ones land is per-seed, and the cages
      // are the pieces worth naming. Token is folder-qualified on purpose — bare "cage" would also
      // be a legal substring elsewhere, and villageDiag's live-data check shows that a bare "big_"
      // already leaks into village and bastion templates. Measured 34/40 outposts (villageDiag).
      new Spec("Outpost with Cages", Dim.OVERWORLD, 64, eq("pillager_outpost"), ZombieMode.ANY, Special.NORMAL,
            "The pens beside the tower (feature_cage1/2/with_allays) — most outposts have at least "
            + "one, so this narrows little. Read from the outpost's real piece list, so the CAGE is "
            + "certain; the tower chest's contents are rolled later and are NOT checked.",
            "pillager_outpost/feature_cage", "what's in the outpost tower chest").rate(85),
      // The one cage that is worth a trip: its template has two minecraft:allay entities baked into
      // it, so the piece landing IS the allays landing — unlike a chest, nothing is rolled later.
      // Roughly 23/40 outposts (villageDiag).
      new Spec("Outpost with Allay Cage", Dim.OVERWORLD, 64, eq("pillager_outpost"), ZombieMode.ANY, Special.NORMAL,
            "The cage holding two allays (feature_cage_with_allays), a little over half of outposts. "
            + "The allays are part of the template rather than a loot roll, so if the piece is there "
            + "they are there. Chest loot is still NOT checked.",
            "pillager_outpost/feature_cage_with_allays", "what's in the outpost tower chest").rate(57),
      Spec.structure("Desert Pyramid", Dim.OVERWORLD, 64, eq("desert_pyramid")),
      Spec.structure("Jungle Pyramid", Dim.OVERWORLD, 64, eq("jungle_pyramid")),
      Spec.structure("Woodland Mansion", Dim.OVERWORLD, 64, eq("mansion")),
      // ── Mansion rooms (2026-08-11) ────────────────────────────────────────────────────
      // The mansion is the one structure whose piece names carry NO meaning. A village says
      // plains_butcher_shop and the row writes itself; a mansion says "1x2_s2", which is a grid
      // size and a letter. There is no secret_room template and no arena template. So these four
      // tokens could not be derived the usual way — by matching a player's word against an asset's
      // word — and were derived one level down instead, from the BLOCKS in each template file
      // (`gradlew mansionDiag`), then rated over 600 assembled mansions.
      //
      // THE FOLDER IS "woodland_mansion/", NOT "mansion/". The structure id is "mansion" (see the
      // eq() above) and the templates are filed under woodland_mansion, and nothing warns you: a
      // census hardcoding the obvious guess returned 0 templates and looked like a working run
      // with an empty result. Every token below is folder-qualified with the spelling the assets
      // actually use, which is also what keeps "2x2_s1" from being read as a bare substring.
      //
      // Only these four earned rows. The rule is the trail-ruins tower lesson in both directions:
      // a room has to be RARE (or it filters nothing) and RECOGNISABLE (or nobody can tell they
      // found it). The jail block 2x2_a1 has iron bars, eight iron doors and four cauldrons and is
      // the closest thing the mansion has to an "arena" — it is in 60% of mansions, so it is not a
      // search. And 1x2_s1, 1x1_as1 and 1x1_as4 are genuinely rare (6%, 15%, 14%) but contain
      // nothing but planks and cobble, so a player standing in one could not know.
      //
      // Only TWO of the 73 templates contain a diamond block, and they are the two secret rooms.
      // Rates below are from `gradlew mansionDiag` over 600 assembled mansions.
      new Spec("Mansion with Lava Vault", Dim.OVERWORLD, 64, eq("mansion"), ZombieMode.ANY, Special.NORMAL,
            "The big secret room — a sealed obsidian box with lava, glass and a single diamond "
            + "block, and no door: you break in. VERY RARE, about 1 mansion in 50 (16 of 600 "
            + "assembled). EXPECT A LONG SEARCH — mansions are already spaced 80 chunks apart, so "
            + "this filter makes an already-slow search roughly fifty times slower again. Use "
            + "\"Mansion with Secret Room\" (1 in 8) if you want a hidden room without the wait. "
            + "Read from the mansion's real piece list, so the ROOM is certain; the diamond block "
            + "is part of the room, but any chest loot is rolled later and is NOT checked.",
            "woodland_mansion/2x2_s1", "chest loot — the diamond block itself is part of the room").rate(2),
      // The only TNT and the only trapped chest anywhere in the building, plus 24 silverfish blocks.
      new Spec("Mansion with TNT Trap", Dim.OVERWORLD, 64, eq("mansion"), ZombieMode.ANY, Special.NORMAL,
            "The trap room — the only TNT and the only trapped chest in the whole mansion, with "
            + "silverfish hidden in the cobblestone. About 1 mansion in 16 (41 of 600). Read from "
            + "the real piece list, so the ROOM is certain; what is in the trapped chest is rolled "
            + "later and is NOT checked — and opening it is what sets the TNT off.",
            "woodland_mansion/1x2_s2", "what's in the trapped chest (opening it triggers the TNT)").rate(6),
      // The mansion's only spawner. Unlike a chest, a spawner is a BLOCK in the template, so the
      // piece landing is the spawner landing.
      new Spec("Mansion with Spawner", Dim.OVERWORLD, 64, eq("mansion"), ZombieMode.ANY, Special.NORMAL,
            "The cobweb room — 95 cobwebs and the only mob spawner in the entire mansion. About 1 "
            + "mansion in 8 (82 of 600). The spawner is a block in the template rather than "
            + "something rolled later, so if the room is there the spawner is there.",
            "woodland_mansion/1x1_as2", "which mob the spawner is set to").rate(13),
      new Spec("Mansion with Secret Room", Dim.OVERWORLD, 64, eq("mansion"), ZombieMode.ANY, Special.NORMAL,
            "The hidden shrine — a small obsidian room with one diamond block in it, walled in with "
            + "no entrance. About 1 mansion in 8 (80 of 600), which makes it the practical secret-"
            + "room search; the bigger lava-and-glass version is \"Mansion with Lava Vault\" and is "
            + "six times rarer. Read from the real piece list, so the ROOM is certain.",
            "woodland_mansion/1x1_as3", "chest loot — the diamond block itself is part of the room").rate(13),
      Spec.structure("Swamp Hut", Dim.OVERWORLD, 64, eq("swamp_hut")),
      Spec.structure("Igloo", Dim.OVERWORLD, 64, eq("igloo")),
      // An igloo is one room plus a coin-flip: half of them hide a ladder down to the basement
      // lab. "igloo/bottom" is that lab, confirmed against what the game actually assembles
      // (villageDiag: 30/60 seeds, the other 30 return just igloo/top) — not a guessed name.
      new Spec("Igloo with Basement", Dim.OVERWORLD, 64, eq("igloo"), ZombieMode.ANY, Special.NORMAL,
            "The basement is the ladder shaft under the carpet down to the lab — read from the "
            + "igloo's real piece list, so its PRESENCE is certain. What's in the chest down "
            + "there is rolled later and is not checked. Verify with /locate structure minecraft:igloo.",
            "igloo/bottom", "what's in the basement chest").rate(50),
      Spec.structure("Ocean Monument", Dim.OVERWORLD, 64, eq("monument")),
      Spec.structure("Shipwreck", Dim.OVERWORLD, 64, pre("shipwreck")),
      // Wrecks come in three orientations and the names carry them: rightsideup_*, sideways_*,
      // upsidedown_*. Capsized is the rarest — 11 of 80 assembled wrecks, about 1 in 7
      // (villageDiag). "Beached" is NOT an orientation despite sounding like one; it is a separate
      // structure id sharing the same slots, and pre("shipwreck") covers both.
      new Spec("Capsized Shipwreck", Dim.OVERWORLD, 64, pre("shipwreck"), ZombieMode.ANY, Special.NORMAL,
            "A wreck lying upside-down (upsidedown_*), roughly 1 in 7. Read from the wreck's real "
            + "template, so the SHAPE is certain. What is in its chests is rolled when the chest "
            + "generates, long after this, and is NOT checked — a wreck missing its stern may have "
            + "no treasure chest at all. Verify with /locate structure #minecraft:shipwreck.",
            "upsidedown", "the wreck's chest loot, or whether it even kept its treasure chest").rate(14),
      Spec.structure("Ocean Ruins", Dim.OVERWORLD, 64, pre("ocean_ruin")),
      // Warm vs cold is NOT a template question — it is two different structures sharing one set,
      // exactly like beached vs normal shipwrecks, and the finder's confirm already enforces which
      // one won. That matters because the word "cold" appears in NO ocean-ruin template: the cold
      // ruins are named brick_/cracked_/mossy_ and only the warm ones say "warm". A "cold" token
      // would have been another "toolsmith" — it matches nothing (villageDiag proves it: 0/40).
      //
      // CORRECTED 2026-08-05: this note used to name "tool_smith" as the dead token, and that is
      // backwards. "tool_smith" is the REAL asset spelling and matches six template files; the
      // dead one is the naive "toolsmith", with no underscore, which matches zero. Anyone reading
      // the old wording would have skipped a perfectly good row, so the shorthand used here and
      // below is now the spelling that actually fails.
      Spec.structure("Warm Ocean Ruins", Dim.OVERWORLD, 64, eq("ocean_ruin_warm")),
      Spec.structure("Cold Ocean Ruins", Dim.OVERWORLD, 64, eq("ocean_ruin_cold")),
      // Big vs small IS a template question. A ruin is a cluster — a scatter of small buildings,
      // and about a third of the time one oversized building among them. The folder is
      // "underwater_ruin", not "ocean_ruin", so the token has to be spelled the assets' way, and it
      // is folder-qualified because a bare "big_" also matches plains_big_house_1 and a bastion
      // piece (villageDiag's live-data check lists them). ~12/40 warm and ~12/40 cold.
      new Spec("Large Ocean Ruin", Dim.OVERWORLD, 64, pre("ocean_ruin"), ZombieMode.ANY, Special.NORMAL,
            "A ruin cluster containing one of the oversized buildings (big_brick/big_cracked/"
            + "big_mossy/big_warm), roughly one ruin in three — warm or cold, whichever the ocean "
            + "here is. Read from the real piece list, so the BUILDING is certain; the buried chest "
            + "and its loot are rolled later and are NOT checked.",
            "underwater_ruin/big_", "what's in the ruin's buried chest").rate(30),
      Spec.structure("Buried Treasure", Dim.OVERWORLD, 64, eq("buried_treasure")),
      Spec.structure("Trial Chambers", Dim.OVERWORLD, -20, eq("trial_chambers")),
      // A trial chamber is ~250 pieces of corridor, and the corridors are in every single one:
      // "chamber", "corridor", "hallway", "atrium", "vault", "reward" and "spawner" all read 40/40
      // (villageDiag), so every obvious-sounding row here is not a search. What actually varies is
      // WHICH BIG ROOM the jigsaw drops in — the three chamber shapes below — and one rare hallway.
      // Tokens are folder-qualified because "chamber" alone spans the whole structure.
      new Spec("Trial Chamber with Eruption Room", Dim.OVERWORLD, -20, eq("trial_chambers"),
            ZombieMode.ANY, Special.NORMAL,
            "The eruption chamber — the round room whose floor erupts around a breeze, about half "
            + "of chambers. Read from the chamber's real piece list, so the ROOM is certain. What "
            + "is in the vaults is decided when you spend a key, long after this, and is NOT "
            + "checked — no seed search can tell you vault loot.",
            "trial_chambers/chamber/eruption", "vault loot — no seed search can tell you that").rate(52),
      new Spec("Trial Chamber with Slanted Room", Dim.OVERWORLD, -20, eq("trial_chambers"),
            ZombieMode.ANY, Special.NORMAL,
            "The slanted chamber — the big sloping room with ramps, roughly two chambers in three. "
            + "The ROOM is read from the real piece list and is certain; vault loot is not checked "
            + "and cannot be.",
            "trial_chambers/chamber/slanted", "vault loot — no seed search can tell you that").rate(65),
      // The rarest thing a trial chamber builds by a wide margin, and the one worth a long search.
      new Spec("Trial Chamber with Encounter Hall", Dim.OVERWORLD, -20, eq("trial_chambers"),
            ZombieMode.ANY, Special.NORMAL,
            "The encounter hallway (hallway/encounter_*) — a wide fighting corridor only a few "
            + "chambers get, so expect a long search. Read from the real piece list, so the HALL "
            + "is certain; vault loot is not checked.",
            "trial_chambers/hallway/encounter", "vault loot — no seed search can tell you that").rate(10),
      Spec.structure("Ancient City", Dim.OVERWORLD, -40, eq("ancient_city")),
      // Almost everything an ancient city builds is in almost every ancient city — barracks 38/40,
      // the chambers, the statue and the ruin families 40/40 (villageDiag). That is the trail-ruins
      // tower lesson again: the obvious row is not a search. The ONE building that genuinely varies
      // is the sauna, at about 1 city in 4. "camp", "ice_box" and "sculk" were probed too and match
      // no template at all — more "toolsmith"s (see the correction above: the dead spelling is the
      // one without the underscore; tool_smith itself is a real, working token).
      new Spec("Ancient City with Sauna", Dim.OVERWORLD, -40, eq("ancient_city"),
            ZombieMode.ANY, Special.NORMAL,
            "The sauna — the small sealed room off the main city, in roughly one ancient city in "
            + "four and the only building that really varies between cities. Read from the city's "
            + "real piece list, so the BUILDING is certain. What is in its chests is rolled later "
            + "and is NOT checked.",
            "ancient_city/structures/sauna", "what's in the ancient city's chests").rate(22),
      // STRONGHOLD ROW REMOVED (1.36.0) AND IT SHOULD NOT COME BACK.
      // Strongholds are ring-placed ~1280-2816 blocks out, so there is no such thing as a seed
      // with one near spawn — the row could only ever offer "the nearest one", which is a fact
      // about every seed rather than a property worth searching for. Worse, it was implemented as
      // "a stronghold within 8000 blocks", a test EVERY seed passes, so a wish that reduced to it
      // returned the very first seed examined and presented a portal 1000 blocks away as a find.
      // If someone asks for one, the honest answer is that it cannot be optimised, which is what
      // WishParser now says.
      Spec.structure("Mineshaft", Dim.OVERWORLD, 40, pre("mineshaft")),
      // ── The Nether, back on the list (2026-08-02) ─────────────────────────────────────
      // It was pulled in 2026-07-14 because "distance from spawn" had no honest meaning for it. It
      // does now, and it is the AT-SPAWN PORTAL model: a Nether target is measured from the seed's
      // spawn divided by 8 — where you come out if you light a portal at spawn, which is the portal
      // a player actually builds. Implemented in SeedCriteria.originX/originZ.
      //
      // TWO THINGS THAT USED TO BE IN THIS PARAGRAPH ARE GONE. The End came back at the same time
      // and left again in 1.41.4 (SeedCriteria.Dim). And dimRadius() used to apply per-dimension
      // radius FLOORS here — a Nether row was quietly widened to at least 208 blocks because
      // fortress and bastion share one structure set and a tight pair is close to impossible. That
      // is a true fact about the Nether and it was the wrong response to it: the control said 100
      // and the search ran at 208. The floors are gone; a tight Nether wish is simply a rare one,
      // and the picker says so on the row.
      // Dungeon stays off the list — it is scattered per-chunk as terrain generates, so there is
      // no seed-level position to search for.
      // Fortress is the one structure on this list built from CODE rather than templates (like
      // stronghold corridors), so it has no piece list at all — nothing inside it can ever be
      // searched for, and it needs no existence token either: every confirmed slot really has one.
      new Spec("Nether Fortress", Dim.NETHER, 64, eq("fortress"), ZombieMode.ANY, Special.NORMAL,
            "Measured in NETHER coordinates from spawn / 8 — where a portal lit at your spawn "
            + "comes out. Fortress and bastion share one structure set, so only one of them wins "
            + "each region and a pair can never be right next to each other.",
            null),
      new Spec("Bastion Remnant", Dim.NETHER, 64, eq("bastion_remnant"), ZombieMode.ANY, Special.NORMAL,
            "Measured in NETHER coordinates from spawn / 8. Pick one of the four type rows below "
            + "if you want a particular bastion.",
            null),
      // A bastion is one of four TYPES, and the type is simply the folder every one of its pieces
      // comes out of — so it is the one structure where "which top-level folder" IS the answer.
      // The trap is the fourth one: everybody calls it the "generic" bastion, and "generic" appears
      // in no bastion template at all (villageDiag: 0/34, alongside "bastion/generic" 0/34). The
      // assets spell it "units". Trailing slashes keep each token inside its own folder.
      // MEASURED 2026-09-02 by `gradlew bastionDiag`, 800 assembled bastions: treasure 200 (25%),
      // bridge 205 (26%), hoglin stable 186 (23%), housing units 209 (26%). The four types are as
      // close to an even split as makes no difference, which is worth knowing because it means NONE
      // of them is a slow search — picking a type costs you roughly four times the seeds, and that
      // is all.
      //
      // CORRECTED. The Bridge row used to say it was "the rarest of the four types, so expect a
      // longer search", with no measurement anywhere in the project behind it. It is the SECOND MOST
      // COMMON. This is the butcher mistake again — prose called that "the rarest of the six" and
      // the census put it at a middling 24% — and it is exactly what this file's own header warns
      // about: a guessed rarity is the number a player uses to decide whether a search is worth
      // starting, so being wrong about it costs them the wait or costs them the search.
      new Spec("Treasure Bastion", Dim.NETHER, 64, eq("bastion_remnant"), ZombieMode.ANY, Special.NORMAL,
            "The treasure bastion — the square one built round a lava basin, with the gold blocks "
            + "guarded by the piglin brutes. About one bastion in four. Read from the bastion's real "
            + "piece list, so the TYPE is certain; what is in its chests is rolled later and is NOT "
            + "checked.",
            "bastion/treasure/", "what's in the bastion's chests").rate(25),
      new Spec("Bridge Bastion", Dim.NETHER, 64, eq("bastion_remnant"), ZombieMode.ANY, Special.NORMAL,
            "The bridge bastion — the long walkway on huge legs with the far-end tower. About one "
            + "bastion in four; the four types are evenly spread, so this is no slower to find than "
            + "any other. The TYPE is certain; chest loot is not checked.",
            "bastion/bridge/", "what's in the bastion's chests").rate(26),
      new Spec("Hoglin Stable Bastion", Dim.NETHER, 64, eq("bastion_remnant"), ZombieMode.ANY, Special.NORMAL,
            "The hoglin stable — the ramped one with the crimson pens. About one bastion in four, "
            + "and marginally the least common of them. The TYPE is certain; what is in the chests, "
            + "and whether the hoglins survived, are not checked.",
            "bastion/hoglin_stable/", "the bastion's chests, and whether the hoglins survived").rate(23),
      new Spec("Housing Units Bastion", Dim.NETHER, 64, eq("bastion_remnant"), ZombieMode.ANY, Special.NORMAL,
            "The housing-units bastion — the blocky stacked-rooms one, usually called the "
            + "\"generic\" bastion (the game spells that folder \"units\"; the word \"generic\" is "
            + "in no template). About one bastion in four. The TYPE is certain; chest loot is not "
            + "checked.",
            "bastion/units/", "what's in the bastion's chests").rate(26),
      // Needs an existence token — the same shape of problem End City had, and the measurement is what
      // said so: only about HALF of confirmed nether-fossil slots actually contain a fossil
      // (villageDiag's "does a confirmed slot really have one" table).
      //
      // And the token is the sharpest naming trap in the whole project, because the wrong answer
      // is not an empty match — it is a CONFIDENT one. "fossil/" matches sixteen real template
      // files this version ships (fossil/skull_1, fossil/spine_1 …) and not one of them is this
      // structure: those are the OVERWORLD fossil feature. A nether fossil assembles exactly one
      // piece and it is named nether_fossils/fossil_N. Measured 0/57 for "fossil/" against 57/57
      // for the real folder — a token can match plenty of files and still be about nothing.
      new Spec("Nether Fossil", Dim.NETHER, 40, eq("nether_fossil"), ZombieMode.ANY, Special.NORMAL,
            "Measured in NETHER coordinates from spawn / 8 — where a portal lit at your spawn "
            + "comes out. A fossil is a single bone structure; there is nothing inside it to "
            + "search for.",
            "nether_fossils/fossil"),
      // THE TWO END ROWS WERE HERE AND WERE REMOVED IN 1.41.4. Do not add them back.
      //
      // "End City" and "End City with Ship" both carried a piece token as an EXISTENCE PROOF rather
      // than as a feature — the note on the first said so in as many words. That was an admission
      // that this row could not make the promise the other sixty make. Every other structure in this
      // list really generates wherever placement and biome both say yes, so confirm() settles it. An
      // End City also needs an outer island whose ground reaches the height it needs, and that test
      // runs inside the structure's own generation, past everything this code can see. The token was
      // meant to stand in for it; in play it did not, and a search returned a city with a ship at a
      // coordinate where there was nothing at all.
      //
      // Reporting a structure where none exists is the worst failure this program has, so the
      // response was not to strengthen the token — it was to stop making End claims. See
      // SeedCriteria.Dim. The whole dimension is out of the picker, the AI's vocabulary and the map;
      // dimensionOf() below returns null for an End structure set, which is the single point where
      // that becomes true for every path at once.
      new Spec("Ruined Portal", Dim.OVERWORLD, 64,
            p -> p.startsWith("ruined_portal") && !p.contains("nether"), ZombieMode.ANY, Special.NORMAL, "", null),
      // A ruined portal is a single piece drawn from ~13 templates, and three of them are the
      // oversized "giant" builds. Measured across all six overworld variants (villageDiag): 8 of
      // 360 assembled portals, about 2%, which is what makes it worth searching for rather than
      // just walking to the nearest portal. Token confirmed against what assembles.
      new Spec("Giant Ruined Portal", Dim.OVERWORLD, 64,
            p -> p.startsWith("ruined_portal") && !p.contains("nether"), ZombieMode.ANY, Special.NORMAL,
            "The oversized ruined portal (giant_portal_1/2/3) — roughly 1 portal in 50, so expect "
            + "longer searches. Read from the portal's real piece list, so its SHAPE is certain; "
            + "what is in its chest is rolled later and is not checked.",
            "giant_portal", "what's in the portal's chest").rate(2),
      // ── WHERE the portal sits, which is a different question from which templates it uses ─────
      // portalDiag assembled 840 portals to settle this and the answer was cleaner than expected:
      // the placement is not rolled per seed at all. Each ruined-portal variant ships exactly one
      // configured placement, so which one you get is decided entirely by WHICH VARIANT wins the
      // chunk — and that is already a plain structure-id filter, the same mechanism warm vs cold
      // ocean ruins uses. These three rows therefore cost nothing: no assembly, no piece list.
      //
      // No rate on any of them, deliberately. How often a portal near YOUR spawn is the desert one
      // depends on the biomes near your spawn, which nothing here has measured — and a plausible
      // invented number is exactly what these rows must not carry.
      new Spec("Surface Ruined Portal", Dim.OVERWORLD, 64,
            p -> p.startsWith("ruined_portal") && !p.contains("nether"), ZombieMode.ANY, Special.NORMAL,
            "A portal standing in the open air, on the ground, where you can walk up to it — the "
            + "standard, jungle and mountain variants, which are configured ON_LAND_SURFACE. This "
            + "is read from the structure's own configuration rather than by building it, so it "
            + "costs nothing and cannot be wrong. What is in its chest is still rolled later.",
            null, "what's in the portal's chest").placed("on_land_surface"),
      new Spec("Buried Ruined Portal", Dim.OVERWORLD, 64,
            p -> p.startsWith("ruined_portal") && !p.contains("nether"), ZombieMode.ANY, Special.NORMAL,
            "The desert portal, which is configured PARTLY_BURIED — sunk into the sand with only "
            + "part of it showing. If your portals keep turning up half-underground, this is the "
            + "one you have been finding, and \"Surface Ruined Portal\" is the row that avoids it.",
            null, "what's in the portal's chest").placed("partly_buried"),
      new Spec("Underwater Ruined Portal", Dim.OVERWORLD, 64,
            p -> p.startsWith("ruined_portal") && !p.contains("nether"), ZombieMode.ANY, Special.NORMAL,
            "The ocean and swamp portals, configured ON_OCEAN_FLOOR — at the bottom of the water "
            + "rather than on land. Worth knowing before you swim: these are the portals that look "
            + "\"buried\" on a map and are actually just wet.",
            null, "what's in the portal's chest").placed("on_ocean_floor"),
      Spec.structure("Desert Well", Dim.OVERWORLD, 64, eq("desert_well")),
      Spec.structure("Trail Ruins", Dim.OVERWORLD, 64, eq("trail_ruins")),
      // Trail ruins is where leaf names are actively dangerous, so every token here is
      // FOLDER-QUALIFIED like the igloo's "igloo/bottom": one_room_N exists under BOTH buildings/
      // and tower/, and tower/hall_N is a different building from buildings/group_hall_N. Matching
      // the bare leaf would answer a question nobody asked.
      //
      // The tower itself is in EVERY trail ruins (villageDiag: 40/40 for tower/, hall/, tower_top/),
      // so "with a tower" is not a search at all — that was the obvious-looking row and it is wrong.
      // What actually varies is which BUILDINGS the jigsaw lays along the roads.
      // The rarest thing trail ruins builds, by a wide margin: 4 of 40 (villageDiag). Note the
      // number is NOT the sum of its families (2+2+3+4) — a ruins that gets a group usually gets
      // several of its floors at once, so the families overlap in the same few seeds.
      new Spec("Trail Ruins with Building Group", Dim.OVERWORLD, 64, eq("trail_ruins"), ZombieMode.ANY, Special.NORMAL,
            "The multi-part house groups (buildings/group_*) rather than the usual single rooms — "
            + "about 1 trail ruins in 10, so expect a longer search. Read from the real piece list, "
            + "so the BUILDING is certain. What the suspicious gravel gives up is decided when you "
            + "brush it, long after this, and is NOT checked.",
            "trail_ruins/buildings/group_", "what the suspicious gravel gives up when you brush it").rate(10),
      // 19 of 40 (villageDiag) — the middle option, for when the group search is too slow.
      new Spec("Trail Ruins with Stables", Dim.OVERWORLD, 64, eq("trail_ruins"), ZombieMode.ANY, Special.NORMAL,
            "The stable buildings off the tower (tower/stable_*), about half of trail ruins. Read "
            + "from the real piece list, so the BUILDING is certain; its suspicious gravel is not.",
            "trail_ruins/tower/stable", "what the suspicious gravel gives up when you brush it").rate(48),
      // CAVEAT REMOVED (1.36.0) — it was measured, not assumed.
      //
      // This row does not assemble anything. It replays the jigsaw start dice (see
      // SeedCriteria.isZombieStart) and reads whether the town centre it lands on is a zombie
      // variant, which costs nothing next to building the village. The old note called that
      // "predicted" and told people to go and check, because a re-derived dice roll is a claim
      // until someone tests it.
      //
      // It has now been tested against the only thing that could settle it — the village the game
      // really assembles. `gradlew professionDiag --args="zombie"` builds a BALANCED set (half the
      // seeds predicted zombie, half not, because zombie villages are ~1 in 50 and a straight scan
      // would let "always answer no" score 98%) and compares the prediction with whether the
      // assembled town centre came out of village/<biome>/zombie/. Result: 120 of 120 across all
      // five village types, 60 real zombie villages among them, zero disagreements. The prediction
      // is not an approximation of the answer, it IS the answer, reached more cheaply.
      new Spec("Abandoned Village", Dim.OVERWORLD, 64, pre("village"), ZombieMode.ABANDONED, Special.NORMAL,
            "\"Abandoned\" = a zombie village: cobwebs, mossy and stripped blocks, no doors, and "
            + "zombie villagers instead of villagers. This is CERTAIN — it is read from the same "
            + "dice the game uses to pick the village's town centre, and checked against 120 "
            + "assembled villages with no disagreement. What is not checked is the same as "
            + "everywhere else: what ends up in the village's chests.",
            null, "what's in the village's chests")
   );

   /**
    * Builds the structure picker list from the live registry, in spec order. A spec with
    * no matching structure in this world is skipped; the DUNGEON special always
    * show. Surface Village is set to require a NORMAL start, Abandoned Village a zombie one,
    * and Igloo with Basement a piece the plain Igloo row doesn't demand.
    */
   public static List<StructureTarget> structures(WorldgenContext ctx) {
      // id path -> its set + spread + dimension (only RandomSpread sets are placeable this way).
      Map<String, Placement> byId = new LinkedHashMap<>();
      for (Holder.Reference<StructureSet> setRef : ctx.allSets) {
         if (!(setRef.value().placement() instanceof RandomSpreadStructurePlacement spread)) {
            continue;
         }
         Dim dim = dimensionOf(ctx, setRef);
         if (dim == null) {
            continue;
         }
         for (StructureSet.StructureSelectionEntry e : setRef.value().structures()) {
            Identifier id = e.structure().unwrapKey().map(ResourceKey::identifier).orElse(null);
            if (id != null) {
               byId.putIfAbsent(id.getPath(), new Placement(setRef, spread, dim, id, e.structure()));
            }
         }
      }

      List<StructureTarget> out = new ArrayList<>();
      for (Spec spec : STRUCTURE_SPECS) {
         if (spec.special() == Special.DUNGEON) {
            out.add(StructureTarget.special(spec.label(), spec.special(), spec.note()));
            continue;
         }
         List<Holder.Reference<StructureSet>> sets = new ArrayList<>();
         List<RandomSpreadStructurePlacement> spreads = new ArrayList<>();
         java.util.Set<Identifier> wanted = new java.util.HashSet<>();
         for (Placement pl : byId.values()) {
            // The portal rows filter on the CONFIGURED placement as well as the id, which is what
            // lets "surface" and "buried" be separate rows without either of them assembling
            // anything. Every other spec leaves this null and is unaffected.
            if (spec.placement() != null
                  && !spec.placement().equals(portalPlacement(pl.structure()))) {
               continue;
            }
            if (pl.dim == spec.dim() && spec.match().test(pl.id.getPath())) {
               if (!sets.contains(pl.set)) {
                  sets.add(pl.set);
                  spreads.add(pl.spread);
               }
               wanted.add(pl.id);
            }
         }
         if (wanted.isEmpty()) {
            continue; // not present in this world
         }
         out.add(StructureTarget.build(spec.label(), spec.dim(), spec.sampleY(), 500,
               sets, spreads, wanted, spec.zombie(), spec.note(), spec.building(), spec.cant(),
               spec.rarityPct()));
      }
      return out;
   }

   /** Which dimension a structure set belongs to, decided by whose biome list its structures are
    *  in. Package-visible so {@link SeedMapData} can split the map's scan by dimension with the
    *  same rule the catalog uses, rather than a second table of which structure lives where. */
   static Dim dimensionOf(WorldgenContext ctx, Holder.Reference<StructureSet> setRef) {
      for (StructureSet.StructureSelectionEntry e : setRef.value().structures()) {
         for (Holder<Biome> b : e.structure().value().biomes()) {
            if (ctx.overworldBiomes.possibleBiomes().contains(b)) return Dim.OVERWORLD;
            if (ctx.netherBiomes.possibleBiomes().contains(b)) return Dim.NETHER;
            // AN END STRUCTURE IS "NOT SEARCHABLE", and this is the one line that makes that true
            // everywhere. Returning null is what an unrecognised set already meant, so the End now
            // takes the path a modded dimension's structures take: skipped by the catalog builder
            // above, and skipped by the map's scan, without either of them needing to know why.
            // The reason is in SeedCriteria.Dim.
            if (ctx.endBiomes.possibleBiomes().contains(b)) return null;
         }
      }
      return null;
   }

   private record Placement(Holder.Reference<StructureSet> set, RandomSpreadStructurePlacement spread,
                            Dim dim, Identifier id, Holder<Structure> structure) {}

   /**
    * Where this structure puts a ruined portal — "on_land_surface", "partly_buried",
    * "on_ocean_floor" — or null when it is not a ruined portal, or is one that could land in more
    * than one way.
    *
    * READ FROM THE STRUCTURE'S OWN CONFIG, not from a table, and that is the whole reason this is
    * three lines of reflection-free field access rather than a map of seven names. {@code portalDiag}
    * assembled 840 portals and found the placement is not a per-seed roll at all: the piece is built
    * with {@code setup.placement()} straight from the configuration, and each variant in this
    * version ships exactly ONE setup — standard, jungle and mountain are always on the land surface,
    * desert is always partly buried, ocean and swamp are always on the ocean floor. So "a portal I
    * can walk up to" costs nothing to check: it is a structure-id filter, like warm vs cold ocean
    * ruins, and never assembles anything.
    *
    * The multi-setup case returns null on purpose. A variant configured with two placements really
    * would be a per-seed roll, and answering "surface" for it would be a guess — better to leave it
    * out of both rows than to put it in the wrong one.
    */
   private static String portalPlacement(Holder<Structure> structure) {
      if (!(structure.value() instanceof RuinedPortalStructure portal)) {
         return null;
      }
      String only = null;
      for (RuinedPortalStructure.Setup setup : portal.setups) {
         String name = setup.placement().getSerializedName();
         if (only == null) {
            only = name;
         } else if (!only.equals(name)) {
            return null;   // genuinely varies — see above
         }
      }
      return only;
   }

   // ── Biomes (the user's exact list, in order, with dimension) ──────────────

   private record BiomeSpec(String path, Dim dim) {}

   private static final List<BiomeSpec> BIOME_SPECS = buildBiomeSpecs();

   private static List<BiomeSpec> buildBiomeSpecs() {
      List<BiomeSpec> l = new ArrayList<>();
      String[] overworld = {
         "plains", "sunflower_plains", "forest", "flower_forest", "birch_forest", "old_growth_birch_forest",
         "dark_forest", "cherry_grove", "pale_garden", "taiga", "snowy_taiga", "old_growth_pine_taiga",
         "old_growth_spruce_taiga", "meadow", "grove", "snowy_slopes", "jagged_peaks", "frozen_peaks",
         "stony_peaks", "ice_spikes", "snowy_plains", "swamp", "mangrove_swamp", "jungle", "sparse_jungle",
         "bamboo_jungle", "desert", "savanna", "savanna_plateau", "windswept_savanna", "badlands",
         "wooded_badlands", "eroded_badlands", "ocean", "deep_ocean", "frozen_ocean", "deep_frozen_ocean",
         "cold_ocean", "deep_cold_ocean", "lukewarm_ocean", "deep_lukewarm_ocean", "warm_ocean", "river",
         "frozen_river", "beach", "snowy_beach", "stony_shore", "deep_dark", "dripstone_caves", "lush_caves"
      };
      for (String p : overworld) l.add(new BiomeSpec(p, Dim.OVERWORLD));
      String[] nether = {"nether_wastes", "soul_sand_valley", "crimson_forest", "warped_forest", "basalt_deltas"};
      for (String p : nether) l.add(new BiomeSpec(p, Dim.NETHER));
      // THE END'S BIOMES WERE REMOVED IN 1.39.1 AND SHOULD NOT COME BACK.
      //
      // They are not a search. The End is laid out the same way in every seed: the centre island
      // is the_end, a ring of small_end_islands sits beyond the void gap, and everything past that
      // is end_barrens / end_midlands / end_highlands arranged by distance from 0,0. Which one you
      // are standing in is a function of how far out you flew, not of the seed — so "find me a
      // seed with end_highlands near the arrival platform" is a question with the same answer in
      // every world, and a row that answers the same in every world rejects nothing.
      //
      // dimBiomeRadius() already had to floor End biomes at 1024 blocks to stop them claiming
      // finds "at spawn" that are a thousand blocks out, which was the same problem showing
      // through: a distance from the arrival platform is a fact about the End, not about a seed.
      //
      // AND IN 1.41.4 THE END STRUCTURES WENT TOO. This paragraph used to argue they should stay:
      // an End City really IS placed by the seed, so the row looked like a real search. It was
      // placed by the seed and still not GENERATED at every placed slot — a different claim, and
      // the one the coordinates were making. See SeedCriteria.Dim.
      return l;
   }

   /** Biome ids for the picker, in the user's order, only those present in this world. */
   public static List<Identifier> biomes(WorldgenContext ctx) {
      List<Identifier> out = new ArrayList<>();
      for (BiomeSpec spec : BIOME_SPECS) {
         Identifier id = Identifier.withDefaultNamespace(spec.path());
         if (ctx.biomes.get(ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, id)).isPresent()) {
            out.add(id);
         }
      }
      return out;
   }

   /**
    * A "big village" in assembled pieces — the p67 from {@code gradlew statsDiag village}, over 250
    * villages, 50 of each of the five types:
    * <pre>
    *   TYPE               n   min   p33   med   p67   max
    *   village_desert    50    45    88    97   107   221
    *   village_plains    50    65   112   133   145   179
    *   village_savanna   50    72   114   124   136   220
    *   village_snowy     50    63    99   120   130   228
    *   village_taiga     50    40    99   119   139   238
    *   ALL TYPES        250    40    99   119   133   238
    * </pre>
    * A village really does vary — 40 to 238 pieces is nearly a factor of six — which is what makes
    * "a big village" a question worth asking, unlike "a big igloo".
    *
    * ONE THRESHOLD FOR ALL FIVE TYPES, and the table above is why that is worth stating rather than
    * hiding. The village row covers every type, so this is the top third of villages IN GENERAL:
    * a desert village clears it only when it is unusually large for a desert village (their own p67
    * is 107), while a plains village clears it more often. That is a fair reading of "big village";
    * it is NOT a claim that each type is judged against itself, which is what the biome size tiers
    * do and what would need five rows here to be true.
    */
   private static final int VILLAGE_BIG_PIECES = 133;

   /**
    * What "big" means for this structure, in assembled pieces — or 0 if the question is meaningless.
    *
    * MEASURED, and only where the measurement found something to measure. {@code statsDiag}
    * assembles structures and prints the real piece-count distribution; the number below is that
    * run's p67, so "big" means the top third of the ones this version actually builds. Every other
    * structure returns 0 and the AI is told the size was ignored, because a threshold on a
    * structure that is the same size every time is the same mistake as a size tier on a beach: a
    * control that looks like it narrows the search and does not.
    *
    * Villages are the case that earns it. {@code statsDiag village} measured piece counts spanning
    * roughly 55 to 190 across the five village types — a factor of three — which is why "a big
    * village" is a real thing to ask for and "a big igloo" is not.
    */
   public static int bigPieces(StructureTarget row) {
      for (SizeSpec spec : SIZE_SPECS) {
         if (row.label.contains(spec.labelPart())) {
            return spec.p67();
         }
      }
      return 0;
   }

   /** One measured size threshold. See {@link #bigPieces}. */
   private record SizeSpec(String labelPart, int p67) {}

   /**
    * The only structures whose size varies enough to be worth asking about.
    *
    * Matched on a label FRAGMENT so every village row qualifies at once — "Surface Village",
    * "Village with Armorer" and "Abandoned Village" are all villages and all vary the same way.
    * The threshold is filled in from statsDiag rather than guessed; a row added here without a
    * measurement behind it would be exactly the overselling the "big" label was just fixed for.
    */
   private static final List<SizeSpec> SIZE_SPECS = List.of(
         new SizeSpec("Village", VILLAGE_BIG_PIECES));

   /** Which dimension a biome id belongs to (default OVERWORLD). */
   public static Dim biomeDim(Identifier id) {
      for (BiomeSpec spec : BIOME_SPECS) {
         if (spec.path().equals(id.getPath())) {
            return spec.dim();
         }
      }
      return Dim.OVERWORLD;
   }

   private SeedCatalog() {
   }
}
