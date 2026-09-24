# FableVision 1.40.0 — what shipped

---

# Part 1 — Modrinth compliance

## The audit (item 2): everything that could touch a world you didn't generate

I went through all 35 source files. Here is every path that reads a world's seed, locates anything
in a running world, or could give an advantage on a server. **Four things found, three removed.**

### Removed

| # | What | Why it mattered |
|---|---|---|
| 1 | **`SeedFinderScreen`** — the standalone in-world seed-finder GUI, 586 lines | The `/seedfind` command was already gone, but **the screen was not**. It was a complete working search UI whose own class documentation read *"The standalone Seed Finder GUI (/seedfind)"*, and it searched using `currentSource()`, which fell back to the running world's registries. Nothing constructed it any more — but a moderator decompiling the jar would have found exactly the thing the mod was accused of. **Deleted.** Its catalog half (which the Create World screen genuinely needs) moved to `SeedCatalogCache`. |
| 2 | **`WorldgenContext.findSource(null)` → running world's registries** | *This was the load-bearing line.* With no Create World screen it returned `getSingleplayerServer().registryAccess()`. Registries are not a seed and it was single-player only, so nothing it did was unsafe — but it is the single line that made "this only builds new worlds" a fact about how the UI happened to be wired rather than about what the code could do. **Removed**; the only source is now a `CreateWorldScreen`. |
| 3 | **Seed-result autosave** | `tickAutosave()` wrote every finished search — seeds and structure coordinates — to `config/fablevision/seedfinder.json`, for the deleted command's `last` subcommand to read back. **No reader existed.** A mod that quietly keeps a file of seeds and coordinates on disk is a bad thing to have to explain. **Removed.** |

### Kept, and why

**`FableVisionClient.maybeAnnounceFoundSeed`** calls `getSingleplayerServer().overworld().getSeed()`.

That is **your own integrated server**, for a world **you just created**, and the value is used for
exactly one comparison: is the world now loading the one the search produced, or a different save
you opened instead? It never leaves the method, is never displayed, and the method returns
immediately if `isLocalServer()` is false. It is the same number vanilla's `/seed` prints. I've
documented it in place and in `SEED-FINDER-SCOPE.md` rather than removing it, because removing it
would mean the join summary fires on the wrong world.

### Checked and clean

- **No reverse solver, and no input for one.** Seeds come from `ThreadLocalRandom.nextLong()` or a
  counter. The only direction is seed → world.
- **`SeedCriteria.test()` never reads a live world** — it takes an explicit seed. Its only callers
  are the workers and the result re-check.
- **`VillageLayout.templates()`** uses the running server's `StructureTemplateManager` when there is
  one — that is template *assets*, not seed data, and with the finder now Create-World-only it
  always falls through to building from client resources.
- **The AI panel has no seed function at all.** It sends a screenshot you explicitly capture plus an
  inventory list. Its mod guide *describes* the seed finder; it cannot invoke it. `AiSettings` only
  flips client display settings.
- **`WorldTools` / `EditWorldScreenMixin`** touch `level.dat` on disk for world duplication and
  gamemode editing. No seed access, single-player saves only.
- **`SlimeChunks`** needs a seed passed in; unreachable except through a search.

## The block (items 1, 3): structural, not hidden

`SeedAccess.java` is the only door, and it **fails closed** — anything it cannot determine reads as
"no". A search may run only when **all** of these hold:

- no world loaded (`mc.level == null`) — not "not multiplayer", **no world at all**, including yours
- no live connection (`mc.getConnection() == null`) — the integrated server runs over one too
- no server selected (`mc.getCurrentServer() == null`)
- no integrated server (`!mc.hasSingleplayerServer()`)
- registries from a `CreateWorldScreen` the caller holds

Checked in **four** places, deliberately redundantly:

1. before the ✨ Custom spawn button is added to the Create World screen
2. when the wish screen asks for registries
3. before any worker thread starts
4. **again every 4096 seeds while workers run** — so a search cannot survive a world starting to
   load, which is the one window where it could otherwise outlive the screen that started it

There is no seed-related command. `/client`, `/client on|off` and `/client lan` are all client-side
settings; the LAN one refuses outright on a remote server.

## The wording (item 4)

- **`fabric.mod.json` description** rewritten — leads with "NEW-WORLD spawn chooser", states it
  cannot read a server's or an existing world's seed, and names the hard-disable condition.
- **`README.txt`** — scope statement is now the first section, above everything else.
- **`SEED-FINDER-SCOPE.md`** (new) — one page, names the file for every claim, includes a
  "a seed cracker needs X / FableVision has Y" table.
- **In-game**: the wish screen title now reads **"✨ Custom spawn — for the NEW world you're
  creating"**, and the help screen leads its Seed Finder section with the same statement.

**Optimization tag:** dropped, as you said. No performance claims anywhere.

---

# Part 2 — the rest of the list

## 5. Join message — short by default

```
[FableVision] Custom spawn - 1.4B seeds, 3m36s
· Pillager Outpost - 0, 32 (32 blocks)
· Woodland Mansion - 80, 16 (82 blocks)
· Don't use /locate - it finds the nearest one, not the one checked
```

One distance per line, measured from where you stand. The `— checked` suffix is gone entirely (a
find that failed the check isn't in the list, so its absence is the evidence).

**Long form only when it carries information:** a find in another dimension, a Fast-mode
origin-vs-spawn gap over 32 blocks, or a note about something dropped/refused/reinterpreted
(blacksmith-style). Everything the long form said is still said — just only when saying it changes
what you'd do.

This needed the finder to emit **structured data** (`SeedCriteria.Find`) rather than finished
English, which is the same thing item 10 needed. Worth noting *why* that matters beyond convenience:
the spoiler filter was originally written by parsing those sentences back apart with a regex, and
that is precisely how it came to miss the slime row and leak coordinates.

**Spoiler toggle verified against the short form** — new `repro` BUG 8: 0 leaks, and it also checks
the *distance survives*, since a line with neither position nor distance says nothing at all.

## 6. Fast-mode exclusions — done

Extracted into `checkExclusions(...)` and run a second time against the real spawn on a hit, exactly
like every other row type. "No desert near spawn" now means near *you*.

One honest note, documented in place: the origin pass is still a **prefilter** in Fast mode, so it
can skip a seed whose desert is near 0,0 but not near you. That's the same trade Fast mode already
makes on wanted structures and biomes, nothing wrong is ever *reported* either way, and dropping it
would make an exclude-only wish pay a spawn lookup on literally every seed.

## 7. The 508-block bug — fixed, with the A/B

Two causes, one shape: **the thing filtered was not the thing printed.**

- **Structures** — filtered on the candidate chunk's **middle** (`getMiddleBlockX`, i.e. min + 8),
  reported at `getLocatePos`, which is the chunk's **min corner** plus the placement's offset. Up to
  11.3 blocks apart on the diagonal. That is exactly the 508-on-a-500.
- **Biomes, worse** — `findBiomeHorizontal` walks rings of a **square** with no distance test at
  all, so a diagonal hit on a 500-block scan sits up to 707 blocks out.
- **Adjacency pairs** — the biome end had no upper bound *at all*, so a pair could print
  *"690 blocks from that grove (asked for 512 or less)"*, contradicting itself on one line.

Fix: test the point that gets **printed**, against the number that was **asked**, rounded the way
the line rounds. Cheap prefilters widened by `RADIUS_SLACK = 16` first so nothing that would qualify
is dropped before the exact test sees it.

**A/B, 4000 seeds per case, same seeds, one process** (`gradlew radiusDiag`):

| row | worst reported before → after | hits | dropped | gained | false drops |
|---|---|---|---|---|---|
| Surface Village | **526 → 500** | 2065 → 1990 | 75 | 0 | **0** |
| Desert Pyramid | **525 → 498** | 153 → 145 | 8 | 0 | **0** |
| jungle | **547 → 466** | 942 → 871 | 71 | 0 | **0** |
| cherry_grove | **545 → 466** | 203 → 193 | 10 | 0 | **0** |

Only boundary cases moved: every dropped seed had been reporting outside the radius, nothing was
gained, and nothing that was legitimately inside got rejected.

*One thing worth telling you:* my first attempt compared exact squared distances and produced **one
false drop** — a village at 500.4 blocks, displayed as "500", rejected on a "500 or less" search.
Correct to the millimetre and wrong from the player's side. Rounding the test the same way the
display rounds fixed it, and it's the better invariant anyway: **no match line can claim a distance
greater than the one asked for.**

## 8. User-friendliness list — reprinted

`FIRST-TIME-USER.md`, with a new section at the top flagging three things that have moved:

- **Item 5 got worse.** The row notes "only surface on the old standalone screen" — which I have now
  deleted. They surface **nowhere**. Several paragraphs of measured per-row explanation are sitting
  in the jar with no reader. This is now the strongest item on the list.
- **Item 2 is half done** (Fast mode's number is real now; the two controls still aren't merged).
- **"Do not add a map preview" — you overrode this, and I think correctly.** The warning was about a
  map *in front of* someone who hasn't said what they want yet — the cubiomes-viewer failure. What
  shipped is a **result view** opened after a search succeeds. The warning still stands against
  putting a map on the picking screen.

## 9. Terrain height — measured, not built

`gradlew terrainCost`. Three tiers, cost **and** accuracy:

| tier | cost/column | vs truth |
|---|---|---|
| `getBaseHeight` (truth) | **3948 µs** | — |
| `preliminarySurfaceLevel` (vanilla's own estimate) | **28.3 µs** | 139× cheaper |
| `erosion` only (shape) | **1.1 µs** | 3563× cheaper |

**The truth is unaffordable.** 25 columns (a 32×32 patch sampled every 8 blocks) is **98.7 ms** —
**6.6× the cost of an entire Exact-mode seed evaluation** (~15 ms). As a per-seed filter it would
take the search from ~66 seeds/sec/thread to under 10, for *one* candidate patch per seed.

**And the cheap tier can't cover for it.** Mean error **18.7 blocks**, median 15, p90 31, max 153 —
only 19% within 10 blocks. That rules out "a flat spot between y=64 and y=80" outright: the error is
wider than the window.

I then measured the thing that absolute error doesn't answer — **is it still right about
flatness?**, since flatness is about *spread across a patch*, not altitude, and the error might
cancel locally. It does not, enough:

- verdicts agree on only **72%** of patches
- **27 of 240 (11%) false flats** — it would promise level ground that is not level

That 11% is the disqualifying number, not the 72%. It's the dishonest direction, and it's the
failure mode this project has spent three releases removing.

**Proposal, if you want it built.** Don't use either tier alone — use the funnel pattern the rest of
the finder already uses:

1. **Biome prefilter (free).** Only plains / meadow / desert / savanna / snowy_plains can be flat.
   This costs nothing — biome sampling is already in the funnel — and removes most of the world.
2. **Cheap tier ranks (0.7 ms/patch).** Score candidate patches by `preliminarySurfaceLevel` spread
   with a **loosened** threshold, so its 17% missed-flats rate costs speed rather than correctness.
3. **Truth confirms the best 1–2 patches only (~100 ms each).** This is where the 11% false flats
   get caught, and it runs **last in the funnel, on seeds that already passed everything else** —
   the same placement the structure assembly has.

Expected cost: ~100–200 ms per near-hit rather than per seed. Usable, and honest, because the number
finally promised comes from `getBaseHeight`. What it loses: it will **miss** some genuinely flat
spots (step 2's false negatives) — the search is slower than perfect, never wrong.

**My recommendation:** worth building, but only after item 5 of the friendliness list, because a
terrain row is another row nobody can find out anything about before committing to it.

## 10. In-game map — built

`SeedMapScreen`. The badge on the Create World screen — previously an inactive button announcing
that something had happened and doing nothing — now opens a top-down map of the finds around spawn.

- **Draws only what the search already decided.** No world-gen at all, so it cannot disagree with
  the chat message about where something is.
- **One tab per dimension**, appearing only when that dimension has finds — Nether coordinates are
  around the portal exit and End coordinates around the arrival platform, so drawing them over the
  Overworld would put a bastion "next to" a village a dimension away. The legend names what the ✦ at
  the centre actually is.
- **Zoom-to-fit** with distance rings at round numbers and the span stated in words, so a variable
  zoom can't itself mislead.
- **Respects the spoiler setting** — the picture is shown either way (opening it is a deliberate
  act), but the coordinates in the legend read `?, ?` when spoilers are off, since printing them
  next to the map would make the setting meaningless the moment you looked.

---

## Needs your in-game test

1. **The block.** Load a world, confirm there is no way to reach any seed screen. Join a server,
   same. Then Create New World → the ✨ button is there and works.
2. **The map** — press the ✓ badge after a search. Check the icons land where the chat says.
3. **The short join message** — the format above, and that a Nether find still triggers the long one.
4. **Spoilers off** — both the short message and the map legend should hide coordinates and keep
   distances.

## Regression

`iconDiag` 60/60 + 55/55 distinct ✓ · `layoutDiag` 360 sizes, 0 overlaps ✓ · `wishDiag` all wishes
resolve as documented ✓ · `slimeDiag` matches documented figures ✓ · `radiusDiag` above ✓ ·
`terrainCost` above ✓ · `villageDiag` 40/40 assembled ✓ · `repro` BUG 5/6/7/8 all ✓ · build EXIT=0.

**Two `repro` counts moved from the 1.39.1 baseline. Both are the radius fix, in the right
direction — I checked rather than assumed:**

- **BUG 3 (armorer + outpost pair, Exact): 6 → 7 hits.** A *gain*, which looks wrong for a fix that
  only removes candidates — but it is the `RADIUS_SLACK` widening doing what it was added for. The
  old `inRange` prefilter measured the chunk middle and so could drop a candidate whose real
  structure position was inside the circle; the adjacency pass then had one fewer village to try.
  That seed should always have passed.
- **BUG 4 (three biomes at radius 1000, Exact): 2057 → 1809 hits, −12%.** The square-vs-circle fix.
  Consistent with `radiusDiag`'s −7.5% for jungle at radius 500 — a bigger radius means a bigger
  gap between the scanned square and the asked circle, so a bigger population was being found
  outside it. These are seeds that were being reported at up to 1414 blocks for a "within 1000"
  search.

Not run: the multi-hour measurement diagnostics (`bench`, `speedDiag`, `mansionDiag`,
`professionDiag`, `statsDiag`, `portalDiag`, `biomeShape`, `strictDiag`) — they measure world-gen
data none of this round touched.
