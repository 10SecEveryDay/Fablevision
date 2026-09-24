package com.fablevision.client.seedfinder;

import java.util.Locale;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The picker icons. Real wiki photos can't be bundled (Minecraft's asset licence + the GUI
 * renders item stacks, not arbitrary images), so each structure/biome gets the item that
 * most reads as it — the block or item you'd picture for that place. Matched by the exact
 * catalog labels/biome ids in {@link SeedCatalog}.
 *
 * TWO RULES, both learned from the 1.39.1 review, and the second is the one that was being broken:
 *
 *   1. The icon must SAY WHAT THE ROW IS, because people look at the picture instead of reading
 *      the line next to it. An icon that is merely thematically adjacent ("some sandstone" for a
 *      desert pyramid) is not doing the job it is there for.
 *   2. NO TWO ROWS IN THE SAME LIST MAY SHARE AN ICON, and near-identical textures count as
 *      sharing. Shipwreck and Capsized Shipwreck were an oak boat and a spruce boat; Ocean
 *      Monument, Ocean Ruins and Large Ocean Ruin were three shades of prismarine. At 16 pixels
 *      those are the same picture, so the list looked like it had duplicate rows in it.
 *
 * Structures and biomes are separate lists on separate tabs, so an item may appear once in each
 * (a water bucket is the ocean biome AND the underwater portal) without either being ambiguous.
 *
 * A row with NO case here falls through to a filled map, which is not a picture of anything — so
 * a missing case is invisible rather than loud. Three ruined-portal rows and the slime row had
 * been shipping like that. {@code iconDiag} now fails the build-time check if any catalog row
 * lands on the fallback, which is the only way this stays true as the catalog grows.
 */
public final class SeedIcons {

   /**
    * True when this structure label has an icon of its own rather than the filled-map fallback.
    *
    * Exists so {@code gradlew iconDiag} can walk the whole live catalog and fail on a row nobody
    * gave a picture to. Reading the fallback back out of the returned stack rather than keeping a
    * second list of "labels that have icons" is deliberate: a second list is a thing that can
    * disagree with the switch below, and the switch is what the player actually sees.
    */
   public static boolean hasIcon(String label) {
      return structureItem(label) != Items.FILLED_MAP;
   }

   /** Same for a biome id — the biome fallback is also a filled map for the same reason. */
   public static boolean hasBiomeIcon(String path) {
      return biomeItem(path) != Items.FILLED_MAP;
   }

   /** The icon for a structure row, ready to draw. */
   public static ItemStack structure(String label) {
      return new ItemStack(structureItem(label));
   }

   /**
    * The same icon as a bare {@link Item}.
    *
    * Split out so {@code iconDiag} can read the whole table headlessly. Building an
    * {@link ItemStack} needs the item COMPONENTS bound, which only happens in a running game —
    * so a check that constructed stacks could only ever run inside the thing it was meant to
    * check before release. The item constants themselves are just registry entries and are
    * available the moment Bootstrap has run.
    */
   /*
    * THE 1.43.2 PASS: every icon re-chosen by one question — what would a player recognise as THIS
    * place at 16 pixels? In order of preference: something only this place has (a totem is a mansion,
    * an elytra is an End ship, a sniffer egg is a warm ocean ruin); then the job-site block or mob
    * that defines it; then, only as a last resort, what it is built from. "What it is built from" is
    * how most of the weak icons got here — sandstone, dark oak planks, nether bricks, stone bricks —
    * because a building material is a picture of the material, and the same material is half the
    * surrounding landscape.
    *
    * The ones that are still weak are marked KNOWN WEAK with the reason, and listed in SHIPPED-1.43.2.
    */
   public static Item structureItem(String label) {
      return switch (label) {
         // The village bell: every village has one, nothing else does, and it is the thing you
         // ring to call the village. Kept.
         case "Surface Village" -> Items.BELL;
         // One profession, one job-site block — the thing you actually look for when you walk in.
         // All kept: a job block is the most direct picture of "this village has that villager".
         case "Village with Armorer" -> Items.BLAST_FURNACE;
         case "Village with Toolsmith" -> Items.SMITHING_TABLE;
         case "Village with Weaponsmith" -> Items.GRINDSTONE;
         case "Village with Butcher" -> Items.SMOKER;
         case "Village with Librarian" -> Items.LECTERN;
         case "Village with Cartographer" -> Items.CARTOGRAPHY_TABLE;
         // The saddle, not a hay block. Stables are where the horses are; a hay block is also every
         // farm and every trading hall's feed stack, and says "wheat" before it says "horse".
         case "Village with Stables" -> Items.SADDLE;
         case "Village with Mason" -> Items.STONECUTTER;
         case "Village with Leatherworker" -> Items.CAULDRON;
         case "Village with Fletcher" -> Items.FLETCHING_TABLE;
         case "Village with Shepherd" -> Items.LOOM;
         case "Village with Fisherman" -> Items.BARREL;
         // The zombie villager: "Abandoned Village" means the ZOMBIE village. Kept.
         case "Abandoned Village" -> Items.ZOMBIE_VILLAGER_SPAWN_EGG;
         // The crossbow is the pillager's weapon and reads as "pillagers" at a glance. Kept.
         case "Pillager Outpost" -> Items.CROSSBOW;
         case "Outpost with Cages" -> Items.IRON_BARS;
         case "Outpost with Allay Cage" -> Items.ALLAY_SPAWN_EGG;
         // Chiseled sandstone, not plain sandstone. Plain sandstone is the whole desert; the carved
         // face-pattern block is the front of the pyramid and is the picture of a desert temple.
         // (It was the Warm Ocean Ruins icon, which has a far better one now — see below.)
         case "Desert Pyramid" -> Items.CHISELED_SANDSTONE;
         case "Jungle Pyramid" -> Items.MOSSY_COBBLESTONE;
         // The totem of undying, not dark oak planks. Evokers — and so totems — come from mansions
         // and essentially nowhere else, and "go to the mansion for a totem" is why most people go.
         // Dark oak planks were the whole dark forest round it.
         case "Woodland Mansion" -> Items.TOTEM_OF_UNDYING;
         case "Mansion with Lava Vault" -> Items.LAVA_BUCKET;
         case "Mansion with TNT Trap" -> Items.TNT;
         case "Mansion with Spawner" -> Items.SPAWNER;
         case "Mansion with Secret Room" -> Items.DIAMOND_BLOCK;
         case "Swamp Hut" -> Items.WITCH_SPAWN_EGG;
         // KNOWN WEAK: an igloo IS a snow dome, and the snow block is the least-bad picture of one.
         // Nothing else in the structure list is white, which is what keeps it readable.
         case "Igloo" -> Items.SNOW_BLOCK;
         // The golden apple, not a ladder. Every igloo basement has one in its chest, as the cure for
         // the zombie villager locked in the cell — it is the reason to go down. A ladder is how you
         // get into half the structures in this list.
         case "Igloo with Basement" -> Items.GOLDEN_APPLE;
         // A sponge: monuments are the only place in the game sponges come from, and that is the
         // picture most players already have of one. The sea lantern it replaces is also in ocean
         // ruins and every lit base under water.
         case "Ocean Monument" -> Items.SPONGE;
         case "Shipwreck" -> Items.OAK_BOAT;
         // KNOWN WEAK. "Capsized" means upside-down and no item depicts an upside-down anything, so
         // this is a second boat, dark spruce against the plain row's pale oak.
         case "Capsized Shipwreck" -> Items.SPRUCE_BOAT;
         // A trident for the generic ruins: drowned — the ruins' guards — carry them, and it is the
         // one "ocean" picture that is not a fish, a block of water or a monument.
         case "Ocean Ruins" -> Items.TRIDENT;
         // THE SNIFFER EGG. Warm ocean ruins are the only place in the game it comes from (their
         // suspicious sand), so this icon cannot be any other structure. Chiseled sandstone was a
         // desert-pyramid picture and has gone back there.
         case "Warm Ocean Ruins" -> Items.SNIFFER_EGG;
         // KNOWN WEAK: cold and large ruins are both stone brick and nothing unique lives in either.
         // Mossy against cracked is the most separation the game's items offer.
         case "Cold Ocean Ruins" -> Items.MOSSY_STONE_BRICKS;
         case "Large Ocean Ruin" -> Items.CRACKED_STONE_BRICKS;
         case "Buried Treasure" -> Items.HEART_OF_THE_SEA;
         case "Trial Chambers" -> Items.TRIAL_KEY;
         case "Trial Chamber with Eruption Room" -> Items.BREEZE_ROD;
         case "Trial Chamber with Slanted Room" -> Items.POLISHED_TUFF_STAIRS;
         case "Trial Chamber with Encounter Hall" -> Items.TRIAL_SPAWNER;
         case "Ancient City" -> Items.ECHO_SHARD;
         // KNOWN WEAK. Nothing in the game depicts a sauna; the soul lantern says "ancient city" and
         // the row's text says which room.
         case "Ancient City with Sauna" -> Items.SOUL_LANTERN;
         // A chest minecart, not a rail. Rails are also every player's railway; the minecart with a
         // chest in it is the thing mineshafts are known for.
         case "Mineshaft" -> Items.CHEST_MINECART;
         // The blaze rod, not nether bricks. Blazes spawn only in fortresses and a blaze rod is the
         // reason anybody goes looking for one; nether bricks were a building material.
         case "Nether Fortress" -> Items.BLAZE_ROD;
         case "Bastion Remnant" -> Items.GILDED_BLACKSTONE;
         case "Treasure Bastion" -> Items.GOLD_BLOCK;
         case "Bridge Bastion" -> Items.IRON_CHAIN;
         case "Hoglin Stable Bastion" -> Items.HOGLIN_SPAWN_EGG;
         // A piglin head: the housing units are where the piglins live. Polished blackstone bricks
         // were what every bastion is built from, so they said "bastion" and nothing about which.
         case "Housing Units Bastion" -> Items.PIGLIN_HEAD;
         case "Nether Fossil" -> Items.BONE_BLOCK;
         // MAP ONLY: the End tab names each End City by what was built, read from its assembled
         // pieces. There is still no End search row. The ship always carries an elytra.
         case "End City with Ship" -> Items.ELYTRA;
         case "End City" -> Items.PURPUR_BLOCK;
         case "Ruined Portal" -> Items.CRYING_OBSIDIAN;
         // KNOWN WEAK: plain obsidian next to crying obsidian. A giant portal is the same thing,
         // bigger, and no item says "bigger".
         case "Giant Ruined Portal" -> Items.OBSIDIAN;
         // The three placement rows are iconed by WHERE the portal sits. Kept.
         case "Surface Ruined Portal" -> Items.GRASS_BLOCK;
         case "Buried Ruined Portal" -> Items.SAND;
         case "Underwater Ruined Portal" -> Items.WATER_BUCKET;
         case "Desert Well" -> Items.SUSPICIOUS_SAND;
         case "Trail Ruins" -> Items.SUSPICIOUS_GRAVEL;
         case "Trail Ruins with Building Group" -> Items.MUD_BRICKS;
         // A lead, not packed mud. Packed mud and mud bricks (the row above) are the same brown
         // square at 16 pixels — two rows, one picture. A lead is what you tie a horse up with.
         case "Trail Ruins with Stables" -> Items.LEAD;
         case SlimeChunks.ROW_LABEL -> Items.SLIME_BALL;
         default -> Items.FILLED_MAP;
      };
   }

   /** The icon for a biome row, ready to draw. */
   public static ItemStack biome(String path) {
      return new ItemStack(biomeItem(path));
   }

   /** The same icon as a bare {@link Item} — see {@link #structureItem}. */
   public static Item biomeItem(String path) {
      String p = path.toLowerCase(Locale.ROOT).replace("minecraft:", "");
      // Same question as the structures: what does a player recognise as THIS biome? Something only it
      // grows or spawns first; its ground or its trees only when nothing better exists.
      return switch (p) {
         // Plains / forests
         case "plains" -> Items.GRASS_BLOCK;
         case "sunflower_plains" -> Items.SUNFLOWER;
         // An oak log: saplings are small sprites with a transparent background and read as a green
         // smudge at 16 pixels on a dark panel. A log is a solid square with the tree's own bark.
         case "forest" -> Items.OAK_LOG;
         // Lilac, the tall flower flower forests are covered in. A single poppy is every grassland.
         case "flower_forest" -> Items.LILAC;
         // Birch: the white bark is the most recognisable tree in the game, so both birch biomes use
         // it — the log for the old-growth one (taller trunks), the sapling for the ordinary one.
         case "birch_forest" -> Items.BIRCH_SAPLING;
         case "old_growth_birch_forest" -> Items.BIRCH_LOG;
         // The huge red mushroom: dark forests are where they grow, and nothing else here is red and
         // white. The dark oak sapling it replaces was a dark smudge.
         case "dark_forest" -> Items.RED_MUSHROOM_BLOCK;
         // Pink petals — the carpet of pink under the trees IS the cherry grove. The sapling was small.
         case "cherry_grove" -> Items.PINK_PETALS;
         // The creaking heart: it grows only in pale gardens and is the biome's whole story.
         case "pale_garden" -> Items.CREAKING_HEART;
         // Taiga: sweet berries grow in taigas and nowhere else in the overworld's forests.
         case "taiga" -> Items.SWEET_BERRIES;
         case "snowy_taiga" -> Items.SPRUCE_LOG;
         // The mossy boulders scattered through old-growth pine taiga, which no other forest has.
         case "old_growth_pine_taiga" -> Items.MOSSY_COBBLESTONE;
         case "old_growth_spruce_taiga" -> Items.PODZOL;
         // Mountains / cold
         case "meadow" -> Items.DANDELION;
         case "grove" -> Items.SNOW;
         case "snowy_slopes" -> Items.POWDER_SNOW_BUCKET;
         // A goat horn: jagged peaks are goat country. Plain stone was every mountain and every cave.
         case "jagged_peaks" -> Items.GOAT_HORN;
         case "frozen_peaks" -> Items.GOAT_SPAWN_EGG;
         case "stony_peaks" -> Items.CALCITE;
         case "ice_spikes" -> Items.PACKED_ICE;
         case "snowy_plains" -> Items.SNOW_BLOCK;
         // Wet
         case "swamp" -> Items.LILY_PAD;
         case "mangrove_swamp" -> Items.MANGROVE_PROPAGULE;
         // Cocoa beans grow only on jungle trees. The jungle sapling moves to the sparse jungle,
         // which is the same trees, fewer of them; jungle leaves were an anonymous green square.
         case "jungle" -> Items.COCOA_BEANS;
         case "sparse_jungle" -> Items.JUNGLE_SAPLING;
         case "bamboo_jungle" -> Items.BAMBOO;
         // Warm / dry
         case "desert" -> Items.CACTUS;
         case "savanna" -> Items.ACACIA_SAPLING;
         case "savanna_plateau" -> Items.ACACIA_LOG;
         // KNOWN WEAK: windswept savanna is savanna on broken hills, and no item says "hills".
         case "windswept_savanna" -> Items.ACACIA_LEAVES;
         case "badlands" -> Items.RED_SAND;
         case "wooded_badlands" -> Items.RED_SANDSTONE;
         case "eroded_badlands" -> Items.TERRACOTTA;
         // Water
         case "ocean" -> Items.WATER_BUCKET;
         // A prismarine shard: deep oceans are where monuments are. The heart of the sea it replaces
         // comes from buried treasure, which is on beaches — the wrong place entirely.
         case "deep_ocean" -> Items.PRISMARINE_SHARD;
         case "frozen_ocean" -> Items.POLAR_BEAR_SPAWN_EGG;
         case "deep_frozen_ocean" -> Items.BLUE_ICE;
         case "cold_ocean" -> Items.COD;
         case "deep_cold_ocean" -> Items.SALMON;
         case "lukewarm_ocean" -> Items.TROPICAL_FISH;
         case "deep_lukewarm_ocean" -> Items.PUFFERFISH;
         case "warm_ocean" -> Items.BRAIN_CORAL;
         // Sugar cane, the plant lining every riverbank. A clay ball was on the riverbed, invisible.
         case "river" -> Items.SUGAR_CANE;
         case "frozen_river" -> Items.ICE;
         // A turtle egg: beaches are where turtles nest, and it cannot be any other place. Sand was
         // also the desert, and the buried-portal icon.
         case "beach" -> Items.TURTLE_EGG;
         case "snowy_beach" -> Items.SNOWBALL;
         // Stone, now the goat horn has the peaks: a stony shore is a stone cliff at the water.
         case "stony_shore" -> Items.STONE;
         // Caves
         case "deep_dark" -> Items.SCULK;
         case "dripstone_caves" -> Items.POINTED_DRIPSTONE;
         // Glow berries hang only in lush caves. A moss block was a plain green square.
         case "lush_caves" -> Items.GLOW_BERRIES;
         // Nether — all kept: each is the one block that biome is made of, and they are all different.
         case "nether_wastes" -> Items.NETHERRACK;
         case "soul_sand_valley" -> Items.SOUL_SAND;
         case "crimson_forest" -> Items.CRIMSON_FUNGUS;
         case "warped_forest" -> Items.WARPED_FUNGUS;
         case "basalt_deltas" -> Items.BASALT;
         // The End's biomes were dropped from the picker in 1.39.1 — see SeedCatalog.buildBiomeSpecs
         // for why (they are laid out by distance from the centre island in every seed, so there is
         // nothing to search for). Their icons went with them rather than sitting here as five
         // unreachable branches implying five rows that no longer exist.
         //
         // The fallback is a filled map, NOT a grass block. A grass block was a picture of the
         // plains biome, so an unlisted biome used to look exactly like a listed one — a wrong
         // picture, which is worse than a blank one. See hasBiomeIcon().
         default -> Items.FILLED_MAP;
      };
   }

   private SeedIcons() {
   }
}
