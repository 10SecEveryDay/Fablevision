# FableVision 1.44.1 — what shipped

Built on top of 1.44.0 (the public API for the pregen mod). Extras is unchanged (1.0.1 still fits).

## 1. Top row overlap on small windows — fixed, and the check now catches it

**What was wrong.** The title "✨ Custom spawn — for the NEW world you're creating" shares its line
with the Within and Mode buttons, and nothing reserved it any room. It was drawn full length from the
left edge. Measured with the game's own font it is 269 pixels; on a 320–360 pixel wide window only
60–75 pixels were free before the first button. So all three were drawn through each other.

**The fix.**
- The Mode and Within buttons are sized to their labels (110 and 62 px) instead of taking up to 150 and
  104 px for 98 and 54 px of text.
- The title picks the longest wording that fits the space left. Every wording keeps "NEW world":

  | wording | width |
  |---|---|
  | ✨ Custom spawn — for the NEW world you're creating | 269 px |
  | ✨ Custom spawn — for your NEW world | 195 px |
  | ✨ Custom spawn — NEW world | 146 px |
  | ✨ NEW world spawn | 95 px (fits even at 320 wide) |

**Why layoutDiag passed.** Three gaps, all closed:
1. The top row was only compared *vertically*. Title and Mode button share a line on purpose, so their
   overlap was excused, and nothing then asked where the title *ends*.
2. The Within button wasn't in the check at all.
3. Text widths were a guess: "no character is wider than 6 px". The game's font disagrees: `@` and `~`
   are 7, `✨` is 8, `—` is 9.

layoutDiag now lays the top row out left to right, in both modes, and measures every string with
**RealFont**. RealFont reads the game's own font files (the bitmap sheets in the Minecraft jar, the
unifont fallback from the downloaded assets) and applies the game's own width rules, read from its code.
If the font files can't be found, the check refuses to run rather than falling back to a guess.

**Proof it would have caught it:** run against the 1.44.0 layout (wide buttons, full title), the new
check fails **640 window sizes**. For example, at 320 wide the title ends at pixel 279 and the Within
button starts at 77. Against 1.44.1 it passes all 1,440.

**Row tooltips** are now drawn beside the list, level with the row you're pointing at, over the
"Searching for" column. They no longer cover the rows you're choosing between.

New tool: `gradlew fontWidth --args="@file.txt"` prints the in-game width of each line of a file.

## 2. Join message — short by default again

Two rules had drifted from what we agreed:
- **The "gap" threshold was 32 blocks.** In Fast mode the spawn is usually 100–300 blocks from the world
  origin, so nearly every Fast result crossed it. The short form already prints the distance from where
  you actually stand, and Fast mode confirms each find is within reach of your real spawn. Now the long
  form only fires when a find's two distances differ by **more than 150 blocks**.
- **Any note forced the long form**, even notes that only explain something (the ℹ ones, the
  stronghold sentence). Those now appear as one line in the short form. Only a note saying part of the
  wish was **dropped** takes the long form.

And one agreed case had been removed: a **Nether find** takes the long form again.

The rule now lives in one place (`JoinForm`), and `gradlew joinDiag` pins it: the ordinary Fast result,
an explanation note and the stronghold note stay short; Nether, a 380-block gap, something dropped and
"no finds" are long.

## 3. Map labels

- **End:** "End City with Ship" (elytra icon) or "End City (no ship)".
- **Nether:** Treasure Bastion, Bridge Bastion, Hoglin Stable Bastion, Housing Units Bastion.

The map didn't know these before. The search never had an End row (removed in 1.41.4), and the map
only asked "is something here". Now End Cities and bastions are **assembled** with the game's own
`generate` step, and the ship or type is read from the pieces, the same way the search's bastion rows
read it. `generate` runs the same "would it start here" test first, so what gets drawn can't change.

**Checked against the game:** mapTruthDiag now compares the type, not just the location, with the pieces
the game's own structure step builds. **541 bastions and 282 End Cities checked, 0 wrong, 0 phantoms.**

Cost: the widest End map (20,000 blocks across) takes about 8 s to fill in, up from 6 s. It fills in
nearest-first, so what's around you shows first.

Also fixed while there: mapTruthDiag counted nether fossils as "missed" on wide maps that deliberately
skip one-per-chunk structures. Its skip list now comes from the same rule the map uses.

## 4. Icons

Rule for every icon: what a player recognises as **that place** at 16 pixels. Best is something only that
place has, then its defining mob or job block, and only as a last resort what it's built from. Most bad
icons were building materials, which also look like the landscape around them.

**Structures — changed**

| row | was | now | why |
|---|---|---|---|
| Village with Stables | hay block | **saddle** | stables = horses; hay is every farm |
| Desert Pyramid | sandstone | **chiseled sandstone** | the pyramid's carved front, not "the desert" |
| Woodland Mansion | dark oak planks | **totem of undying** | only mansions have evokers/totems |
| Igloo with Basement | ladder | **golden apple** | every basement chest has one |
| Ocean Monument | sea lantern | **sponge** | monuments are the only source |
| Ocean Ruins | stone bricks | **trident** | the drowned that guard them |
| Warm Ocean Ruins | chiseled sandstone | **sniffer egg** | only warm ocean ruins have them |
| Mineshaft | rail | **chest minecart** | the thing mineshafts are known for |
| Nether Fortress | nether bricks | **blaze rod** | blazes only spawn in fortresses |
| Housing Units Bastion | polished blackstone bricks | **piglin head** | where the piglins live |
| Trail Ruins with Stables | packed mud | **lead** | packed mud and mud bricks were the same brown square |
| End City with Ship (map) | — | **elytra** | the ship always carries one |
| Beached Shipwreck (map) | *capsized* boat | plain boat | a beached wreck isn't capsized |

**Biomes — changed**

| biome | was | now | why |
|---|---|---|---|
| Forest | oak sapling | **oak log** | saplings are a green smudge at 16 px |
| Flower Forest | poppy | **lilac** | a single poppy is any grassland |
| Dark Forest | dark oak sapling | **red mushroom block** | huge mushrooms grow there |
| Cherry Grove | cherry sapling | **pink petals** | the pink carpet is the biome |
| Pale Garden | pale oak sapling | **creaking heart** | only grows there |
| Taiga | spruce sapling | **sweet berries** | taiga-only berries |
| Old Growth Pine Taiga | spruce leaves | **mossy cobblestone** | its mossy boulders |
| Jagged Peaks | stone | **goat horn** | goat country; stone was every mountain |
| Jungle | jungle sapling | **cocoa beans** | only on jungle trees |
| Sparse Jungle | jungle leaves | **jungle sapling** | leaves were an anonymous green square |
| Deep Ocean | heart of the sea | **prismarine shard** | monuments are in deep ocean; the heart is from beaches |
| River | clay ball | **sugar cane** | lines every riverbank |
| Beach | sand | **turtle egg** | turtles nest only on beaches |
| Stony Shore | gravel | **stone** | a stone cliff at the water |
| Lush Caves | moss block | **glow berries** | only in lush caves |

Everything else was kept: the village job blocks, bell, crossbow, witch, trial chamber items, ancient
city echo shard, bastion gilded blackstone/gold/chain/hoglin, the ruined-portal placement icons, the
Nether biomes, and the ocean fish.

**Still weak — no good item exists. Alternatives, if you'd like one:**

| row | current | why it's weak | alternatives |
|---|---|---|---|
| Capsized Shipwreck | spruce boat | no item is "upside down"; it's a second boat | a **map** (the wreck's map chest), or accept the boat |
| Cold Ocean Ruins / Large Ocean Ruin | mossy / cracked stone bricks | both grey brick; nothing unique inside | **suspicious gravel** (cold ruins have it) if Trail Ruins moves to a **pottery sherd** |
| Giant Ruined Portal | obsidian | same thing as the plain portal, bigger | a **gold block** (giant ones carry more gold) |
| Ancient City with Sauna | soul lantern | nothing depicts a sauna | **campfire** / **soul campfire** |
| Igloo | snow block | an igloo *is* a snow dome | a **white carpet**, or keep it: nothing else in the list is white |
| Windswept Savanna | acacia leaves | no item says "broken hills" | **coarse dirt** |

iconDiag checks all of this: 58 structure rows, 55 biome rows and the map-only labels, all present and
all different. It also now **fails** when it finds a problem; before, it printed problems and exited 0.
It can't tell two *different items with near-identical pictures* apart (that's how mud bricks vs packed
mud survived), so those were checked by eye in this pass.

## Verification

layoutDiag (1,440 sizes, real font), iconDiag, joinDiag, mapDiag, wishDiag, privacyDiag, and
mapTruthDiag (6 seeds all dimensions + 10 seeds End/Nether at 3,000 blocks, with type checks) — see
regression-1433.log and map-truth-1433*.log.

## Needs your test in game

1. Custom spawn at a small window (the size in your screenshot): the title should be shorter and clear
   of both buttons.
2. Hover a row with a note: the box appears over the right column, not over the list.
3. A Fast search with a spawn ~200 blocks out: the join message should be the short one.
4. End tab: city labels say with/without ship; Nether tab: bastions say their type.
5. The picker and map icons — anything that still doesn't read right, say which.
