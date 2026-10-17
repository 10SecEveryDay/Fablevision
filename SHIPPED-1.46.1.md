# FableVision 1.46.1 — no more hidden bastions on a cold start

Jar: `releases\26.3-fabric\fablevision-1.46.1.jar` (Minecraft 26.3). The 1.46.0, 1.45.1 and 1.45.0 jars stay
next to it. Built and checked headless only. SeedAccess, SeedSearchApi and Pregen are untouched; Pregen 1.2.0
still refuses 1.46.x (that range is the separate card).

## The problem
Vanilla works out a structure template's jigsaw blocks the first time they are asked for and keeps them in a
plain HashMap (`StructureTemplate.Palette`). Two threads filling the same cold template at once make one of
them throw `ConcurrentModificationException`. 1.45.0 retries a check up to 3 times; a bastion is built from up
to 167 templates, so on the first wide Nether view of a session the map's six threads, all starting bastions on
cold templates, could lose three times in a few milliseconds. The bastion was then hidden ("couldn't be
checked") — failing closed, never a phantom — until the map asked again about 3 seconds later. mapTruthDiag at
2,800 blocks hid 5 real bastions and still printed "OK". The diagnosis was done in a separate session.

## The fix
`TemplateWarmup` walks the jigsaw pools on ONE thread before the check threads start, asking each template for
its jigsaw blocks once — the call assembly itself makes — so the caches are full before anything can race over
them. Public API only (`JigsawStructure.getStartPool()`/`getPoolAliases()`, each pool's templates and fallback,
each element's `getShuffledJigsawBlocks`, each jigsaw's `pool()`).
- **Map:** in the map's background build (`SeedMapData`, the map's own worker thread — never the render
  thread), before the parallel checks, holding one of the map's CPU permits, at the map's priority (lowest in
  a world). Assembled structures (bastions) get every pool; other jigsaw structures their start pool, which
  is all their existence check reads.
- **Search:** each worker calls it before its first seed (`SeedFinder.warmTemplates`); the first walks, the
  others wait for it and find it done. Rows that assemble (a named building, spawn-inside, a big village) get
  every pool, other rows the start pool.
- Once per template manager per structure, remembered weakly. A 2-second budget; a walk cut short is finished
  next time. If it throws, it is logged at debug and everything carries on — the retry and hide-if-unsure stay
  as the backstops.
- It cannot change what is drawn or found: it stores exactly what vanilla's lazy path stores, earlier. No
  verdict reads anything from it.

## What it costs
About 120–160 ms for the Overworld's start pools (27 jigsaw structures, 223 templates) and 90 ms for the
bastion (176 templates), once per session, on the map's worker. A search pays about 50 ms per structure it
assembles, once per session. Part of that is template loading the checks would have done anyway.

## Which structures race — measured, not assumed
`coldDiag` (new) starts every round from a fresh, cold template manager and alternates the warm-up off and on;
collisions are counted per structure (asked again / given up). Regression run, 4 cold starts each way:

| | warm-up OFF | warm-up ON |
|---|---|---|
| **Map**, first wide view (2,800 blocks), all three dimensions | **16 bastions hidden** (0, 4, 2, 10 per start); 65 collisions, all `bastion_remnant` | 0 hidden, 0 collisions |
| **Search**, content rows (village w/ armorer, mansion w/ lava vault, trial chamber w/ eruption room, ancient city w/ sauna, treasure bastion), 6 s each | **123 seeds turned down** (trial chambers 53, bastions 46, ancient cities 24); villages collided 7 times and always recovered on retry | 0 turned down, 0 collisions |

- **Villages, mansions, ancient cities, trial chambers, End Cities on the map: never hidden, 0 collisions.** The
  map only reads jigsaw caches on structures it assembles (bastions; End Cities are built from fixed templates
  and one at a time) and on a start piece's named jigsaw. They are warmed anyway (start pools, ~120–160 ms).
- **The search** assembles content rows, so it raced on trial chambers, bastions and ancient cities, and turned
  real matches down — never a false match, a lost race only says no. Mansions have no jigsaw pools (0 templates
  to warm, 0 collisions). Warm-up cost per structure, once per session: villages 735 templates ~70 ms, trial
  chambers 207 ~70 ms, bastions 176 ~80 ms, ancient cities 66 ~20 ms.
- **The search warm-up is in** (`SeedFinder.warmTemplates`, called by each worker before its first seed): a few
  lines in SeedFinder, nothing in SeedAccess or SeedSearchApi. Pregen's searches go through the same workers, so
  they get it too; its API is unchanged. One cost: a Stop pressed in the first fraction of a second waits for
  the warm-up (at most the 2 s budget, ~0.1 s measured).

## Verification
Full suite on the final code, 34 tasks, all exit 0 and every verdict line read: `regression-1461.log`. The
2,800-block mapTruthDiag now covers all three dimensions (it was Overworld-only). The control runs:
`regression-1461-control.log`; the cold-start runs used while measuring: `regression-1461-cold.log`.

- **The control, 3 runs each, `mapTruthDiag --args="4 2800"`, all three dimensions:**
  - `-Pnowarm` (warm-up skipped): **failed 3 of 3** — Nether 4, 3 and 5 bastions hidden as unverifiable
    (19, 24, 23 collisions retried, all bastions), 0 phantoms, 0 wrong types.
  - normal: **passed 3 of 3** — Overworld 1,600 drawn / 0 phantoms / 0 missed; Nether 978 drawn, 978 really
    there, 0 phantoms, 0 missed, 331 typed, 0 wrong; End 89 / 89; 0 unverifiable; 0 collisions.
  - The Overworld and End numbers are identical with and without the warm-up, and the Nether gains exactly the
    hidden bastions, typed right: the warm-up changes which structures can be CHECKED, not which are drawn.
- **mapTruthDiag** now fails on any unverifiable structure (it said "OK" with 5 hidden before), counts the
  widest views too, and prints collisions per structure. Regression: 6 seeds at 1,200 and 4 at 2,800, every
  dimension, 0 phantoms, 0 missed, 0 wrong types, 0 unverifiable, 0 collisions.
- **mapReplay** (3 seeds): the warm-up holds one map permit — still at most 6 map threads, all 14 in-world map
  threads at lowest priority, no lasting black.
- **worldsDiag** 60/60, **multiDiag** 3 worlds: 18 seeds, 108 claims, 0 false matches; **coldDiag** as above.
- **The gate and the API**: SeedAccess, SeedSearchApi and its inner classes — javap identical to 1.45.1.
- Everything else as in 1.46.0: featureDiag 0 false matches, layoutDiag 0 problems (+ its control),
  wishDiag, rateDiag, statsDiag (126 = top third, 32.4%), tieDiag, strictDiag, radiusDiag, netherDiag,
  portalDiag, samplerDiag (identical, control differs), focusDiag (control loses the keyboard 4 times),
  watchdogDiag, joinDiag, joinRepro, repro, smithDiag, professionDiag, villageDiag, caveDiag, etaDiag, iconDiag,
  mapDiag, privacyDiag, slimeDiag, streamDiag — all pass.

## Not verified
- **Anything in game.** Headless only.
- **The in-world map (N key) uses the server's own template manager**, which the server's worldgen threads also
  fill. The warm-up fills it from one map thread, which is less concurrent than the six map threads did before,
  but whether the server was mid-fill at the same moment cannot be tested headless.

## Needs your test in game
1. **First wide Nether view.** Start the game, Create World → 🗺 Seed map. Go straight to the **Nether** tab
   and zoom all the way out before touching any other tab. There should be no yellow "couldn't be checked"
   line, and the bastions should be there on the first draw (before, a few could appear only ~3 s later).
2. **Bastion labels.** Create a world from a seed you looked at, and visit one or two bastions the map drew
   far out (1,500–2,900 blocks in the Nether): the type label (Treasure, Bridge, Hoglin Stable, Units) matches.
3. **In a world (N key)**: open the map, go to the Nether tab, zoom out: no "couldn't be checked" line, and the
   game does not stutter more than before while it fills in.
4. **A search right after starting the game.** First thing in a session: Custom spawn → "Trial Chamber with
   Eruption Room" (or a bastion row) → Search. It starts within a moment as usual and finds one; Stop still
   works straight away.
5. **Unchanged**: the 1.46.0 list (find N worlds) still applies — a quick pass of Worlds 3 → Use this seed.
