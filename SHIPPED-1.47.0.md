# FableVision 1.47.0 — faster counted searches, and a Bots setting

Jar: `releases\26.3-fabric\fablevision-1.47.0.jar` (Minecraft 26.3). Older jars kept. Built and checked
headless only. SeedAccess and SeedSearchApi untouched (byte-identical, below). Pregen 1.2.0 still refuses
1.46+ (its range is the separate card).

## 1. Faster Exact searches — measured first

All numbers single-threaded, fixed seeds, `exactDiag-1470.log` (`gradlew exactDiag --args="10 500000"`).

**The real-spawn lookup** costs about **1.6 ms a seed** on 26.3 (12.7 s for 8,000 seeds) — not the 10–15 ms
the old comments said; 26.3's NoiseSpawnFinder is cheaper. Its share of an Exact search:

| wish (Exact) | seeds/s | spawn lookup share |
|---|---|---|
| Surface Village within 64 | 556 | 89% |
| Spawn inside Surface Village | 335 | 53% (the rest is assembling the 1% of villages near spawn) |
| Village with Weaponsmith within 100 | 255 | 39% |
| 2× Village with Weaponsmith within 100 | 253 → **743** | 39% → 89% |
| 2× Village with Weaponsmith within 300 | 36 → **116** | 6% → 19% |

- **Can the lookup be deferred further?** No. It already comes after every check that does not need the spawn
  (placement, the confirm gate); everything after it — the distance to the spawn, the footprint for spawn
  inside, biomes, slime chunks — is measured FROM the spawn. Nothing that would give identical results can
  move ahead of it, so for a plain Exact row the lookup simply is most of the cost.
- **Is the spawn-wander prefilter as tight as it safely can be?** Measured over **500,000 seeds**: median 40
  blocks from 0,0, 99% within 886, 99.9% within 1,240, furthest **1,932**; none past 2,048. The prefilter adds
  2,144. Vanilla's search can in principle land further (it searches 2,048 out, then up to 512 more from that
  point), so the margin is already tighter than the algorithm allows and is not tightened further — that could
  only lose matches. It was never exceeded in half a million seeds. Left as is.
- **Is a count checked at placement level before any assembly?** It was NOT: a "2 of X" row assembled each
  in-range structure as it confirmed it, so a seed with one weaponsmith-village in range paid ~0.7 s to learn
  there was no second. Now (two changes, SeedFunnel fixes 8a and 8b):
  - **8a**, before the spawn lookup: N placements must exist with N of them close enough together to fit in
    the radius around ANY spawn (each within 2R of the others, the triangle inequality) — arithmetic on the
    list already made.
  - **8b**, after it: N confirmed structures inside the radius before any is assembled.
- **Identical results, by checksum.** Every variant (off, 8a, 8b, both, off again) on every wish gave the same
  RESULT-CHECKSUM over every seed's verdict and the same passing seeds with the same lines and finds — byte for
  byte. Count-1 rows were run with both on and off too: identical, same speed (1.02×, noise). Speed-ups
  (both on): **2× weaponsmith ≤100 Exact 2.9×, ≤300 Exact 3.3×** (assemblies 337 → 80), 2× village ≤200 Exact
  1.07×; in Fast mode, where the spawn lookup is not paid at all, 22× and more.
- **Speed and rarity, apart** (`exactDiag --args="rarity 300000"`, all cores, Exact): Village with Weaponsmith
  within 100 is 1 match in 166 seeds; **2× Surface Village within 100 is about 1 in 300,000** (one found); **2×
  Village with Weaponsmith within 100: none in 300,000** — millions of seeds per match by any reckoning. At
  ~6,700 seeds/s on 12 workers that is the wish, not the machine. The estimate now says so: "Rarer than 1 in
  15,440 seeds (2× Village with Weaponsmith near spawn is rare by itself) — could take much longer than 3 s".
  It also charged a counted content row the building's rate only ONCE; it is now once per structure (both
  villages need a weaponsmith), marked rough.

## 2. The Bots setting

On the Custom spawn screen, beside Must have: **"Bots: auto (12)"**. Click for one more, right-click or
shift-click for one fewer; auto → 1 → 2 → … → the maximum → auto. Saved in `config/fablevision.json`
(`seedThreads`, 0 = auto). Above auto the label turns yellow, the tooltip warns that no CPU thread is left
free and the game may stutter, and the searching screen says "(more than auto: the game may stutter)" next to
the bot count. Workers stay at the lowest priority.

- **Auto is unchanged**: cores − 1, at most 12.
- **The maximum** is the lower of the CPU's logical processors and what fits in half the game's heap at
  32 MB a worker. How 32 MB was set — measured on 26.3 (`threadsDiag-1470.log`), Exact, assembling villages:
  - what a worker KEEPS (live after a full GC): **about 0.2 MB** (1 MB at most). The per-seed world-gen state
    is garbage within the seed; the old note that each worker keeps a full noise state overstated it;
  - what it USES between collections: 8–14 MB, and ~170 MB/s of short-lived allocation;
  - **16 workers in a 512 MB heap** (= 16 × 32 MB, exactly their share): GC 3.4% of the time — fits. In 1 GB:
    2.5%, in 256 MB: 5%. The control, 96 MB, runs out of memory.
  So in the vanilla launcher's 2 GB heap the memory bound is 32 workers, and in practice the CPU decides: on
  this 16-thread machine the maximum is 16.
- **Read inside SeedFinder.start**, so every search follows it — including Pregen's through SeedSearchApi,
  whose contract (and class bytes) are unchanged.
- **Per-worker slots past 12**: the watchdog's per-worker arrays stopped at 12; a setting can now exceed that,
  so they are sized to the logical processors. threadsDiag reports a stuck 16th worker by number.
- **Tested at 1 worker, auto (12) and the maximum (16)**: multiDiag's N-world section on each — 18 searches,
  54 seeds, 324 claims independently re-checked, **0 false matches**, every search 3 different seeds. Stop: every
  worker out of its loop 117 / 484 / 318 ms after the flag (the slowest seeds there are, village assembly). The
  watchdog: quiet while healthy, then the last worker reported stuck, at each count.

## Verification
Full suite on the final code, 38 tasks, every verdict line read: `regression-1470.log`. All exit 0 except the
one that must not: `threadsDiag -Pheap=96M --args="gc 16"`, the memory control, which runs out of memory.

- **exactDiag** (in the suite at a third of the seeds): every count-check variant identical on every wish —
  verdict checksum, passing seeds, lines, finds. Speed-ups 3.26× and 3.03× on the two Exact weaponsmith counts,
  27.7× on 2× village in Fast; count-1 rows 0.99–1.00×.
- **threadsDiag**: memory (a worker keeps ≤1 MB), the count rule, Bots stepping auto → 1 … 16 → auto and back,
  a saved number above the machine's maximum, the setting read where the search starts, Stop at 1 / 12 / 16
  workers, the watchdog at 1 / 12 / 16. `gc 16` at 512 MB: FITS. At 96 MB: out of memory (the control).
- **multiDiag at 1, auto (12) and max (16) workers**: 18 N-world searches, every one 3 different seeds, every
  claim re-checked independently (built there, the building inside, within the distance of the real spawn):
  **0 false matches**.
- **The gate**: `SeedAccess`, `SeedAccess$OwnWorld`, `SeedSearchApi` and its three inner classes are
  BYTE-identical (SHA-256) to 1.45.1 and 1.46.2.
- Everything else passes as in 1.46.2: layoutDiag (picker 1,440 sizes incl. the Must-have / Bots row, Create
  World 32,020 placements, searching screens 1,440, action bar — all with their controls), featureDiag 0 false
  matches, mapTruthDiag 0 phantoms / 0 missed / 0 wrong / 0 unverifiable at 1,200 and 2,800, coldDiag, mapReplay
  (14/14 lowest priority), worldsDiag 68/68, etaDiag (all within 6×), wishDiag, rateDiag, statsDiag (126 = top
  third, 32.4%), radiusDiag, netherDiag, strictDiag, tieDiag, samplerDiag, focusDiag, watchdogDiag, joinDiag,
  joinRepro, repro, smithDiag, professionDiag, villageDiag, caveDiag, iconDiag, mapDiag, privacyDiag, slimeDiag,
  streamDiag, portalDiag.

## Not verified
- **Anything in game.** Headless only. In particular the stutter itself: whether more bots than auto makes
  YOUR game stutter depends on the machine — the warning says it may.
- The memory figures are this machine's (16 threads, JDK 25, G1). The rule leaves half the heap to the game.

## Needs your test in game
1. **Bots button** (Custom spawn screen, beside Must have): it reads "Bots: auto (N)" with N = your CPU threads
   less one, at most 12. Click through 1, 2 … up to your CPU's thread count and back to auto; right-click and
   shift-click go back. Hover: the tooltip gives your numbers. Above auto the label turns yellow.
2. **Saved**: set it to 2, close and restart the game → still "Bots: 2". Set it back to auto.
3. **Searching with few and many bots**: search "Surface Village" with Bots 1 → the searching screen says
   "1 bots working". With Bots at the maximum → it says that many, plus "(more than auto: the game may
   stutter)" — and tell me whether it did. Press Stop: the screen changes at once.
4. **A counted search**: pick Village with Weaponsmith, ×2, Exact → the estimate line names it as "rare by
   itself"; it runs (and may well not finish — that is the rarity, measured above). Then ×2 Surface Village
   within 200 in Fast mode → it should find seeds quickly.
5. **The 1.46.2 list still applies** (Create World buttons, one Seed map, Worlds 1–10, Mode lock, bottom
   Search, searching screen) if you have not run it yet.
