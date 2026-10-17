# FableVision 1.45.0 — Minecraft 26.3

Jar: `releases\26.3-fabric\fablevision-1.45.0.jar`. Extras 1.1.0 and Pregen 1.2.0 are the 26.3 builds of the
add-ons, in the same folder. 1.44.7 stays the 26.1.2 build: `code\` and `releases\26.1.2-fabric\` are untouched.
Built and checked headless only — nothing here has been run inside Minecraft yet (see the end).

## The port

| | 26.1.2 (1.44.7) | 26.3 (1.45.0) |
|---|---|---|
| Minecraft range in fabric.mod.json | ~26.1 | ~26.3 |
| Fabric Loader | 0.19.3 | 0.19.5 (exact) |
| Fabric API | 0.153.0+26.1.2 | 0.162.0+26.3 (exact; requires ≥ 0.162.0) |
| Loom | 1.17-SNAPSHOT | 1.18.4 (exact release) |
| Gradle | 9.5.1 | 9.7.1, with its checksum in the wrapper |
| foojay resolver | 0.9.0 | 1.0.0 — 0.9.0 cannot load on Gradle 9 |

What 26.2/26.3 changed underneath, and what replaced it:
- **World-gen**: density functions compile lazily and there is no `RandomState.sampler()`; climate comes
  from `createClimateSampler`, and structure checks, biome lookups and the spawn search
  (`NoiseSpawnFinder`) all take it explicitly. A caching sampler is not thread-safe, so the search and the
  map use the uncached one, and structure checks get one each, made the way the game's own structure step
  makes it. **samplerDiag** compares the game's per-chunk climate with the finder's per-point one over whole
  chunks, bit for bit: identical (and its own control shows it can see a difference).
- **Screens and keys**: screens open through `minecraft.gui`; keys are SDL codes (`InputConstants.KEY_N`,
  `KEY_RETURN`, `KEY_NUMPADENTER`). One trap: on a 26.3 mouse event `isLeft()` means the left ARROW key —
  the map's click test uses `MOUSE_BUTTON_LEFT`.
- **Access widener**: one descriptor moved (`MenuTabBar`); all nine lines validated by Loom.
- **The safety gate is unchanged.** `SeedAccess.java` is byte-identical to 1.44.7, and the five game calls
  it rests on (`level`, `getConnection`, `getCurrentServer`, `isLocalServer`, `hasSingleplayerServer`) are
  identical in the 26.1.2 and 26.3 game code.
- **SeedSearchApi (the one class Pregen calls) is still API version 1** — its methods are 1.44.x's — but its
  `COMPATIBLE_RANGE` now names the 26.3 line, `>=1.45.0 <1.46.0`. Left alone, a 1.45.0 jar would have
  declared a range that excludes itself. The version moved because of the Minecraft port, not an API
  change; the convention note in the class says so.

## New in 26.3, measured and added (nothing was removed)
- **Dappled forest** (biome). Colour, icon (orange poplar leaves), size tier 73% / 96 / 224, AI words
  ("autumn forest", "poplar" …). 26.3 puts it in a slot that used to fall back to plains, which is why a few
  26.1.2 village seeds have no village on 26.3 (see Speed).
- **Sulfur caves** (cave biome). Colour, icon (sulfur), size tier 100% / 96 / 160, searched at its own
  heights — found near spawn in 15% of seeds at y 0, 7% at y 32 and y −24, 1% at y −48, and never at y 64
  or above (where the search used to look for every cave biome without its own heights).
- **Abandoned camp** (structure). One row covering all eighteen biome variants in their one structure set
  (icon: campfire). A plain existence row: which camp it is and what its chests hold are not checked.

## Re-measured — and what moved
- **Biome size tiers**, A/B on one machine: the 26.1.2 build reproduced the old table exactly, and 26.3 moved
  six — plains, forest, grove, mangrove swamp, dripstone caves, lush caves (old values are in BiomeShape).
- **Every content row's rate.** These came from structures built at chunk 0,0 with seeds 1, 2, 3 …, where a
  structure's random is seeded with the world seed itself and its first draws barely move — so every sample
  started the same way. Re-measured from 4,000 per variant at random seeds and random chunks. The same
  samples on 26.1.2 give the same numbers, so **these were wrong in 1.44.7 too**; 26.3 moved none of them.

  | row | was | now | | row | was | now |
  |---|---|---|---|---|---|---|
  | Armorer | 30 | 36 | | Mansion: Lava Vault | 2 | 4 (1 in 28) |
  | Toolsmith | 18 | 20 | | Mansion: TNT Trap | 6 | 7 |
  | Weaponsmith | 38 | 36 | | Mansion: Spawner | 13 | 15 |
  | Butcher | 24 | 29 | | Mansion: Secret Room | 13 | 14 |
  | Librarian | 15 | 20 | | Capsized Shipwreck | 14 | 15 |
  | Cartographer | 42 | 38 | | Large Ocean Ruin | 30 | 31 |
  | Mason | 33 | 32 | | Trial: Eruption Room | 52 | 45 |
  | Leatherworker | 29 | 31 | | Trial: Slanted Room | 65 | 61 |
  | Fletcher | 18 | 17 | | Trial: Encounter Hall | 10 | 8 |
  | Shepherd | 9 | 7 | | Ancient City: Sauna | 22 | 25 |
  | Fisherman | 7 | 11 | | Bastions (4 types) | 25/26/23/26 | 26/24/25/25 |
  | Outpost: Cages | 85 | 88 | | **Giant Ruined Portal** | **2** | **5** (1 in 20) |
  | Stables, Allay Cage, Igloo Basement, Trail Ruins Group | same | | | Trail Ruins: Stables | 48 | 53 |

  **Correction (1.45.1): the four mansion rows are the exception to the paragraph above.** Mansion rooms
  come out the same at chunk 0,0 and at random chunks (4,000 mansions each way). Their old numbers were
  percentages of 600 mansions cut down to whole numbers, not rounded — 16/600 = 2.7% shipped as 2, 41/600 =
  6.8% as 6, 82/600 = 13.7% as 13 — and the new ones are rounded from 3,970 mansions.

  **rateDiag** is new: it re-measures every quoted rate on a separate random stream and fails when one is
  off by more than sampling explains and enough to matter. Against the old numbers it failed 10 rows; against
  the new ones it passes.
- **"Big village"** was 133 pieces, the top third of villages built at chunk 0,0. At random seeds and chunks
  only 25–27% reach 133; the top third starts at **126**. statsDiag now fails when the shipped number is not
  the top third (it used to print the number and exit 0).

## Wrong notes, corrected (all of them were wrong in 1.44.7 as well)
- **Shepherd**: "in all five village types" — snowy villages never build one. The snowy shepherd's house in
  the game's own data has its door connector spelled `buidling_entrance`, so no snowy street can attach it:
  0 of 4,000 snowy villages. professionDiag had called the zero "RARE"; it now checks whether a house can
  attach at all and says IMPOSSIBLE, with the reason.
- **Surface Ruined Portal**: said it covers the standard, jungle and mountain portals. The standard and
  mountain portals roll between two placements per portal (underground / in the mountain, about half the
  time), and the code always left them out rather than promise them — so every match was a jungle portal.
  The note now says so. portalDiag (which had "found" one placement per variant by sampling at chunk 0,0)
  now samples properly and fails if what assembles differs from the configuration.
- **"big ancient city"** and the like: told the player the structure "is the same size in every seed". Now:
  only villages can be searched by size.

## Fixed
- **End City ship labels on the map.** The game keeps "has a ship been placed" in a static field while it
  builds an End City, so two map threads assembling cities at once could swap ships. End City assembly on the
  map is now one at a time. mapTruthDiag: every End City label right.
- **Vanilla's template cache race.** A template's block cache is a plain HashMap filled on first use; two
  threads filling it at once make one throw. The map then hid that structure (fail closed), and on 26.3 one
  Nether bastion was hidden on every run. That check — map or search — is now asked again, at most three
  times, and still fails closed after that. It can only ever hide a structure, never invent one.
- **blacksmithDiag and mansionDiag were blind on 26.3**: 26.3's structure files name a block's id
  `id` where they said `Name`, and both read nothing without failing. Fixed, and both now fail if they read
  an empty palette.

## Speed (same seeds, same machine, one thread, `gradlew bench … late`)

| search | 26.1.2 | 26.3 |
|---|---|---|
| Surface Village ≤300, Exact | 120 seeds/s | 503 seeds/s |
| jungle ≤1000, Exact | 130 | 432 |
| Village with Armorer ≤500, Exact | 2.7 | 11.2 |
| Surface Village ≤300, Fast | 564 | 4,409 |
| pale garden next to dark forest, Exact | 122 | 388 |

Same answers where the world is the same: five of the ten searches return identical seed lists. The village
searches lose 49 of 15,000 seeds on 26.3 and gain none. All 49 were checked: at 48 of the old village spots
26.3 now grows dappled forest, where villages cannot start; at the 49th (a savanna village) the 26.3 biome at
y 64 is sulfur caves. The finder is following the new world — mapTruthDiag compares it with the game's own
structure step.

## Verification
Full suite on the final code, every task exit 0: `regression-1450.log` (16-core machine, nothing else
running). `regression-1450a.log` is the run before the cache-race fix, kept because it shows the problem.

- **mapTruthDiag** 6 seeds, every dimension: Overworld 1,127 drawn / 1,127 really there, Nether 2,060 drawn,
  End 13 / 13 — **0 phantoms, 0 missed**, 92 bastions and End Cities named by type, 0 wrong. 4 seeds,
  Overworld at 2,800 blocks: 1,600 drawn, 0 phantoms, 0 missed.
- **featureDiag** (6 seeds × 400 blocks, Exact and Fast): 0 false matches over all 33 content rows; the
  furthest a feature sits from its printed coordinate is 114 blocks (ancient city sauna).
- **tieDiag**: every answer the same whichever biome was looked up first; no tie promised.
- **strictDiag**: the shipped funnel rejects every hit vanilla's biome test would reject.
- **radiusDiag / netherDiag**: asked == searched at every radius, every dimension.
- **mapReplay** (3 seeds): black gone ≤ 189 ms after a move, 0 of 16 cells empty, at most 6 map threads
  (cap 6 on 16 cores), 14 of 14 in-world map threads at lowest priority.
- **mapDiag, iconDiag** (the three new rows included), **layoutDiag** (1,440 window sizes, real font, 0
  problems), **wishDiag, joinDiag, joinRepro** (all short), **privacyDiag, streamDiag, slimeDiag, repro,
  smithDiag, watchdogDiag, caveDiag, villageDiag, etaDiag** (all within 6×): pass.
- **professionDiag verify**: 0 missing rows, 0 missed spellings; snowy shepherd reported IMPOSSIBLE with the
  reason; desert toolsmith unobserved in 40 (it is 1.4%).
- **samplerDiag**: 294,912 quarts, all six climate values identical bit for bit; control differs.
- **rateDiag**: every quoted rate within sampling error. **portalDiag**: every variant builds exactly the
  placements it is configured with. **statsDiag**: 126 pieces is the top third (32.4% reach it).
- Spawn points, zombie-village prediction (200 / 200) and the template contents of the content rows were
  compared with 26.1.2 earlier in the port: same.

## Not verified, and why
- **Anything in game.** I cannot launch Minecraft. Every claim above is from headless runs against the
  game's own code.
- **Bedrock tip (Extras).** Not tested with Geyser on 26.3. If Geyser is not installed or has no 26.3 build,
  the tip says Geyser is not loaded, as it already did on 26.1.2 without it.
- **Pregen's own API check** compares two compile-time constants, so the compiler removes it; the loader's
  version range is the guard that actually works. The same was true of Pregen 1.1.0; not changed here.
- `SeedAccess.java`'s comment still quotes the 1.44.7 example numbers (30% armorer, 1 in 50 lava vault,
  133-piece village). Left as is so the gate file stays byte-identical; the numbers are illustration only.
  (1.45.1 rewords that comment so it quotes no figures; the gate's code is unchanged.)

## Needs your test in game
Everything below is untested in a running game. Teleport lines assume creative or spectator.

1. **The gate.** (a) On a multiplayer server: no ✨ Custom spawn anywhere, and N shows a refusal on the
   action bar instead of a map. (b) Join someone else's LAN world: the same refusal. (c) In your own
   single-player world: N opens the map of that world, with no search control. (d) Create World with
   World Type Large Biomes, Amplified or Superflat: Custom spawn refuses and asks for Default.
2. **Input on 26.3.** N opens the map in a world; Enter in the map's seed box loads that seed; left-click
   and drag on the map works (26.3 changed what "left" means on an input event).
3. **New 26.3 rows.** Custom spawn → pick each, Create (with coordinates shown), then check:
   - Abandoned Camp: `/locate structure #minecraft:abandoned_camp` finds it at (or nearer than) the
     coordinate the join message gives.
   - Dappled Forest: walk to the given coordinate; F3 says `minecraft:dappled_forest`.
   - Sulfur Caves: go to the given coordinate and height; F3 says `minecraft:sulfur_caves`.
4. **Walk tests (fresh 26.3 coordinates, Default world type).** Go to every line; do not use /locate to
   choose — it goes to the nearest one, which is not the one being tested.
   - Seed `5`: `/tp @s 272 200 -544` → a GIANT ruined portal; `/tp @s -624 200 -512` → an ordinary one.
   - Seed `13`: `/tp @s -560 200 320` → a GIANT ruined portal.
   - Seed `1`: `/tp @s -144 200 -944` → an igloo WITH a basement (trapdoor under the carpet);
     `/tp @s -880 200 -1024` → an igloo without one.
   (Full lists: `port-logs\263-walk-portal.txt`, `263-walk-igloo.txt`.)
5. **Map labels.** End tab: fly to two or three "End City with Ship" labels and one "(no ship)"; the ship
   should be there or not as labelled. Nether tab: a bastion's label matches its type.
6. **Extras 1.1.0.** G opens the AI panel and Enter sends; low fire and low shield sit lower; the food HUD's
   saturation overlay draws over the food bar; world list → Edit shows Duplicate World and Gamemode.
7. **Pregen 1.2.0.** "Pregen seeds" appears on the Create World screen and starts and stops.
