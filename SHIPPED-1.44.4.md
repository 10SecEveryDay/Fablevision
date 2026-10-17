# FableVision 1.44.4 — search biome-tie fix, map staleness, slime note

Jar: `releases\26.1.2-fabric\fablevision-1.44.4.jar`. Extras stays at 1.0.3 (needs ≥ 1.44.3; unchanged).

## Search biome ties (was open in 1.44.3)

Vanilla's biome lookup keeps each thread's last answer as the starting point for the next, so on an
exact tie between two biomes the answer depends on what that thread looked up before. The search
checks a structure right after sampling the biome at its locate point, on the same thread.

`tieDiag` (new) runs the search's own confirm + structure check for every candidate twice — history
as the search leaves it, and cleared — and asks the game's structure step about every disagreement
or tie. 40,137 checks, 11 start points on an exact tie. Clearing the history alone was NOT a fix: on
two tied Nether fossils the game built one and not the other. At a tie the game has no fixed answer.

**Rule (search and map): promise or draw a structure only if every biome in the tie allows it**
(`VillageLayout.biomeHolds`, `WorldgenContext.nearestBiomes` — vanilla's own distance arithmetic).
Result: 0 answers depend on history, 0 promised where the game built nothing, 1 tied fossil the game
happened to build is not promised (conservative).

## Map: black at full zoom-out, structures not appearing

| cause | fix |
|---|---|
| every drag release / scroll cancelled the build in flight; a widest build is 6–8 s, so frequent interaction meant none ever finished | a build for the same seed, tab and zoom (within 2×) is left to finish; the map then asks for where it has got to |
| views could never be wider than the screen at full zoom-out, so any drag uncovered unpainted ground | built 1.5× wider than the screen (up to 15,000 from centre); biome grid 128 → 192 so it isn't blockier |
| a failed check thread returned "nothing" like a cancel — silent, never retried | thrown, logged, shown, retried with back-off |
| "couldn't check" (structure files still loading) was cached for the whole session | not cached; a view with unchecked structures is re-asked after 3 s |
| nothing re-asked when the picture no longer matched the camera | tick() re-asks whenever nothing is building and the newest picture doesn't cover the view |
| once 3,000 structures were known, the marker list stopped rebuilding | rebuilt on any new entry, not on a change in count |

Cost: default views 287–443 ms (1.44.3: 201–350) — the finer grid and the tie check.

## Slime note
"Slime chunks are 1 in 10…" (and the wish parser's spelling) once per session — `JoinForm.slimeNoteOnce`,
checked in joinDiag.

## Verification — regression-1444.log, 21 checks, verdicts read
stream 32,000/32,000 distinct · slime 10.002% · repro all pass, 0 leaks · strict: every hit vanilla
rejects, the funnel rejects (471 examined) · profession 0 missing rows / 0 missed spellings · layout
0 problems at 1,440 sizes · icons distinct · radius, nether, cave, eta, smith, wish, privacy, join
(+5 slime cases), joinRepro (all short) pass · feature unchanged (7 of 33, worst 114) · mapDiag OK,
widest build 7.7 s, cancel mid-way stops at once · mapTruth 0 phantoms / 0 missed in all dimensions,
wide Overworld 1,600 drawn, strongholds 3/3 × 4 · tieDiag as above.

## Needs your test in game
1. Full zoom-out, drag repeatedly and fast: no black that stays; new ground arrives between builds.
2. First open of the seed map: structures appear without zooming.
3. Two slime searches in one session: the note appears on the first only.
