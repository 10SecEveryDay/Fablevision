# FableVision 1.46.0 — find N worlds

Jar: `releases\26.3-fabric\fablevision-1.46.0.jar` (Minecraft 26.3). The 1.45.0 and 1.45.1 jars stay next to
it. Built and checked headless only — nothing here has been run inside Minecraft yet (see the end).

**Pregen 1.2.0 will not load with this jar.** It declares `fablevision >=1.45.0 <1.46.0`, and Fabric Loader
refuses it at startup with a clear message. SeedSearchApi was not touched (still API 1, byte-identical to
1.45.1), so the fix is a Pregen rebuild with a wider range — not done here, because this task excluded
Pregen and SeedSearchApi. (Its `COMPATIBLE_RANGE` constant still says 1.45.x, which now excludes this jar —
left alone for the same reason.) Extras 1.1.0 accepts this jar (`>=1.45.0`), and the core members it uses —
FableVisionConfig (byte-identical), FableVisionClient.KEY_CATEGORY, SeedAccess — are unchanged; not run in game.

## What changed

**A Worlds button** beside 🔍 Search: 1, 2, 3, 5, 10, then back to 1 (cap 10 — `FoundWorlds.MAX`; raising it
is your call). It starts at 1 every session and is not saved, so a forgotten 10 can never quietly stop a
search from filling the seed in.

**1 world is the search as before.** Same call into the engine, the first find goes straight into the world
settings, same messages. The applied-seed state moved from `SpawnWishScreen` to `AppliedSeed`, and both the
single search and "Use this seed" fill it through one method (`AppliedSeed.Applied.of`), so the badge and
the join message are built the same way whichever path set them.

**More than 1:** the search keeps going until that many DIFFERENT seeds have each passed the whole check —
the same funnel, the same Fast-mode re-check against the real spawn, the same biome rules — and nothing goes
into the world settings by itself. The searching screen says "Found 2 of 3 worlds" and lists each world as it
passes: `#n`, the seed, and that seed's find lines (the join message's short lines, built by the same code —
`JoinForm.shortLines` — so the Coords toggle hides them the same way; a Nether find is tagged "(Nether)").
Each world has:
- **Use this seed** — into the seed box through SeedBox, with THAT world's finds, lines and stats
  (seeds checked and time when it was found), then back to Create World. The used one shows "✓ In use".
- **Copy seed**, and **Copy all** (one seed per line) — clipboard only.
- **View on map** — the existing seed map for that seed; Done comes back to the list. Greyed out while the
  search is still running (both build world-gen on every core); Stop, or let it finish.

**Stop keeps what was found.** Closing the screen keeps the list too (opening Custom spawn again shows it),
and "◂ Back to wish" goes to the picker with a "3 found ▸" button on the "Searching for" line to come back.
A new search clears the list, like the seed box. The list belongs to the Create World screen that started
it: another screen, or new data packs, gets an empty one. Use and Map re-ask the gate, the Default World Type
rule and the registries at the click, and refuse otherwise.

**Distinct seeds**, twice over: the engine accepts a seed once per job (`SeedFinder.Job#accept`), so a search
for 3 only stops on 3 different seeds; the list also refuses a repeat. Pregen's unbounded stream
(want = `Integer.MAX_VALUE`) is left exactly as it was — no per-seed record, which would grow all night.

**The estimate** says "about 2 min for 3 worlds at this speed", allows one match's worth per world found plus
the one being hunted before "taking longer than the estimate", and adds "a long wait — fewer worlds finish
sooner" past 10 minutes. With 1 world the line is exactly 1.45.1's (checked word for word).

**The watchdog**: each world found is progress — it clears the screen's "far past its estimate" line — and
that line is allowed (found + 1) matches' worth, up to N for the last world. The structure-check failure stop
is unchanged and keeps the worlds already found. With 1 world the check is the old one.

**Wishes**: "give me 3 worlds with…", "3 seeds that have…", "five options for…", "a couple of seeds…",
"3 or 4 seeds…" (takes 4). A number, digits or one–ten, right before worlds / seeds / options, with at most
two neutral words between ("3 different seeds"). It is kept apart from structure counts: "2 shipwrecks" is
still two in ONE world, and "3 seeds with 2 shipwrecks" is 3 worlds with two each. The offline reader takes
the phrase out before reading counts (before this, "3 seeds with a village" searched for three villages). The
AI is taught a `"worlds"` field and an example; the validator decides from the player's own words — the AI's
number is used only when the player said that number and a worlds word, a structure count equal to the worlds
number is reset to 1 when the player said that number only once, and anything over 10 is capped (the
searching screen says "you asked for 20 — 10 is the most at once"). A number in the wish wins over the button.

**Not changed:** SeedAccess (javap: 271 lines, 0 differences from 1.45.1), SeedSearchApi and its inner
classes (0 differences), no file writing, no chunk generation, no pregen.

One thing to know: in Exact mode a search for several worlds does not do the single search's extra "aim
right at spawn (≤32 blocks)" hunt — that tiering has only ever applied to a single result. Every world is
still within Exact's 64 blocks.

## Verification
Full suite on the final code, 33 tasks, every one exit 0 and every verdict line read: `regression-1460.log`.
Follow-up runs (multiDiag with the distance check, one replayed seed, mapTruthDiag like-for-like):
`regression-1460b.log`.

- **worldsDiag** (new, 60 checks, all pass). The list: add, the same seed refused, full at N, Stop keeps
  it, the owner check (another screen / other registries / nothing → refused), clear, Copy all, the button's
  1-2-3-5-10 steps. **Use on result 2 of 3** stores seed, finds, lines and stats that are result 2's and not
  1's or 3's — on made-up worlds and on three real ones from the engine. The engine, through its own per-seed
  step on one thread per core less one (as the workers run): a search for 3 stops by itself on 3 different seeds; the same real seed offered twice is
  accepted once and the search goes on; a search for 1 stops on its first find; pregen's unbounded stream
  still accepts a repeat (the control: the same path without the rule). The estimate line for one world is
  1.45.1's word for word (five cases written out); "about 2 min for 3 worlds", the long-wait warning, the
  per-world allowance. The lines under a world equal the join message's, Coords hidden and shown.
- **multiDiag, 3 worlds** (new section): a content row (Village with Weaponsmith), a two-row combo (Surface
  Village + Shipwreck) and a content row with a plain one (Village with Armorer + Ruined Portal), Fast and
  Exact, within 400. 6 searches, each 3 different seeds; 18 seeds, 108 claims — every row at every
  coordinate printed, in the lines AND in the structured finds the list shows, rebuilt independently (piece
  lists, never the funnel); every spawn re-derived with the game's spawn search (18 of 18 agree with the
  printed one); every find within 400 of the real spawn. **0 false matches, 0 unverifiable.** One Exact world
  from the regression run (-976, 224) looked far and was replayed alone: spawn -720, -33, 363 blocks — inside.
- **layoutDiag**: 1,440 picker layouts, 0 problems (144 too-small and 32 clipped, the same as 1.45.1), now
  with the Search + Worlds row (every Worlds label 1–10) and the "N found ▸" button. Plus 2,880 found-worlds
  layouts (searching and results at every size and feedback height): no band overlaps, every button label
  fits, the widest seed fits beside its buttons, a ten-world list (8 finds each, the longest label, widest
  coordinates, 250 rows) scrolls end to end with every world's seed and buttons reachable, and every feedback
  message fits. Smallest list at a real window: 36 px (one world's head and a line). **Control**: a layout
  broken on purpose (12-px Use button, 12-px list) is caught.
- **wishDiag**: 17 new worlds-count cases pass — offline ("give me 3 worlds with a village", "five options
  for a cherry grove", "a couple of seeds…", "3 or 4 seeds…", "2 shipwrecks" stays a count, "3 seeds with 2
  shipwrecks", "20 seeds…" → 10) and AI replies (the worlds number repeated as a count is reset; said twice
  it stays; a "worlds" nobody asked for is ignored; "three survival worlds" accepted; 50 → 10). All earlier
  checks still pass.
- **watchdogDiag**: the old checks plus N = 3 — no report at 19× one match; reported past 20× with none
  found; a find clears the screen's line; the second world gets 2 matches' worth; all found → never reported;
  a find counts as progress.
- **The gate and the API**: SeedAccess (271 lines), SeedSearchApi and its three inner classes — javap identical
  to 1.45.1. Byte for byte, 78 classes are unchanged, including SeedCriteria, SeedFunnel, VillageLayout,
  WorldgenContext, SeedMapData and SeedMapModel.
- Everything else as in 1.45.1: featureDiag 0 false matches; mapTruthDiag (6 seeds) 0 phantoms, 0 missed in
  every dimension; at 2,800 blocks, Overworld 1,600 drawn, 0 phantoms, 0 missed; rateDiag, statsDiag (126 =
  top third, 32.4%), tieDiag, strictDiag, radiusDiag, netherDiag, portalDiag, samplerDiag (294,912 quarts
  identical, control differs), mapReplay ×3, focusDiag (control loses the keyboard 4 times), joinDiag,
  joinRepro, repro, smithDiag, professionDiag, villageDiag, caveDiag, etaDiag, iconDiag, mapDiag, privacyDiag,
  slimeDiag, streamDiag — all pass.
- Seen along the way, not from this change: my first 2,800-block mapTruthDiag ran all three dimensions (the
  dimension filter is `-Pdims`, not an argument). The Nether at 2,800 had 5 bastions the map **hid** as
  unverifiable — 0 phantoms; the map failed closed. The map code is byte-identical to 1.45.1, and 1.45.1 never
  ran the Nether at that reach, so this is not new — but it is unexplained.

## Not verified, and why
- **Anything in game.** I cannot launch Minecraft; everything above is headless.
- **The real `SeedFinder.start` with N > 1.** It is refused headless by the gate, as it must be. The tests
  drive the engine's own per-seed step (`SeedFinder.consider`, the worker loop's body) on one thread per core
  less one, which covers the funnel, the re-check, distinct seeds and the stop at N — not the thread start-up
  or the gate re-check inside the loop (both unchanged).
- **Drawing.** The list's geometry, wrapping and scrolling are checked with the game's font widths at every
  size; the actual drawing, the buttons' look and the scroll wheel are not.
- **Clipboard** and **opening the map from the list** were not run.
- **A live AI model** with the new prompt: only the validator was tested, against hand-written replies.

## Needs your test in game
1. **One world, unchanged.** Custom spawn → the button beside Search says "Worlds: 1" (or "1 world") → pick
   Surface Village → Search. Straight back to Create World with the seed and "✓ Spawn map", as before; create
   it and the join message is the usual short one.
2. **The button.** Click it: 1 → 2 → 3 → 5 → 10 → 1. Hover: the tooltip explains it. At a small window or
   GUI scale 4 the label shortens ("3 worlds", then "×3") and never spills onto Search.
3. **Three worlds.** Set 3, pick Surface Village, Search. "Found 0 of 3 worlds", the estimate says "for 3
   worlds", worlds appear one by one, View on map is greyed out while searching, and nothing is put in the seed
   box. When done: "Found 3 of 3 worlds for: …".
4. **Use this seed** on #2. Back on Create World the seed box shows #2's seed and "✓ Spawn map" opens #2's
   map. Create the world: the join message lists the same finds the list showed under #2.
5. **Copy seed** and **Copy all** — paste into a text editor: one number; then three lines.
6. **View on map** on any world, then Done → back on the list, same scroll position.
7. **Coords**: toggle it on the list — the find lines show "?, ?" with the distance kept; the join message
   follows the same setting.
8. **Stop** with 1 of 3 found → "Stopped with 1 of 3 worlds", the one stays. Stop with none found → the
   picker says "Stopped after … seeds" as before.
9. **Keeping and clearing.** Esc, then Custom spawn again → the same list. "◂ Back to wish" → the picker with
   "3 found ▸" on the "Searching for" line → back to the list. Start a new search → the old list is gone.
10. **Wishes** (no AI key needed): "give me 3 seeds with a village" → 3 worlds; "2 shipwrecks" → one world
    with two; "3 seeds with 2 shipwrecks" → 3 worlds, two shipwrecks each; "give me 20 worlds with a village"
    → 10, with the "10 is the most at once" note. With a key: "three survival worlds with a village" → 3.
11. **Ten at a small window.** 10 worlds at GUI scale 4 (or a small window): the wheel scrolls the list, every
    world's seed and buttons can be reached, nothing overlaps the status lines or the bottom buttons.
12. **Settings changed after a search.** Find 3, set World Type to Amplified, Custom spawn → Use this seed
    refuses ("Set World Type back to Default…"). Back to Default and it works. Change data packs, open Custom
    spawn → no list (those worlds were checked with the old packs).
13. **Long waits.** 5 or 10 worlds of something rare (Mansion with Lava Vault): the estimate ends with "a long
    wait — fewer worlds finish sooner".
14. **Resize** the window or press F11 while searching for several and on the list: everything re-anchors.
15. **The gate, unchanged.** On a server or someone's LAN world there is still no Custom spawn.
16. **Pregen 1.2.0** with this jar: Fabric Loader refuses to start and names the version range — expected.
