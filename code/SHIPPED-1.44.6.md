# FableVision 1.44.6 — search watchdog; map ground on its own thread

Jar: `releases\26.1.2-fabric\fablevision-1.44.6.jar`. Extras stays 1.0.3.

## Why
One featureDiag run (1.44.5 regression) spent 2 h 20 min on "Village with Shepherd, exact" and could not
be reproduced. The rerun shows that row hitting at seeds 20, 51, 69, 78, 138 and 206, so the stalled
process was not slow — it answered "no" to seeds that are hits. Cause unknown. One candidate found in
the search code: both structure checks (VillageLayout.generatesHere and the assembly) caught every
exception and returned "no match", logged at debug only. If that happened on every check, a search
would run for ever finding nothing — exactly the profile seen.

## Search watchdog (SearchWatchdog, one thread per search incl. pregen's)
Logs to logs/latest.log, each report with wish, mode, seed stream (replays the search), counts, estimate:
- a worker on one seed > 60 s: its seed, thread state and stack; again every 5 min while stuck;
- no seed finished for 60 s: every worker's state and stack;
- 20× the seeds the estimate predicts per match with nothing found (50× if rough): snapshot, and a
  line on the search screen pointing at logs/latest.log;
- ≥ 90% of ≥ 50 recent structure checks threw: the search is STOPPED with an error on screen and the
  exception's stack in the log (the only case where it acts).
Structure-check exceptions are now counted (VillageLayout.CHECK_FAILURES / CHECK_ATTEMPTS) and logged
with a stack, first and then once a minute.

featureDiag: 15-minute row limit (reports how far it got; fails), per-seed stall capture with the
thread's stack, hit seeds printed per row so runs can be compared seed by seed.

watchdogDiag (new): real stuck thread, real stalled job, 60 real exceptions from the real check — 22/22.
It caught a bug in the watchdog itself: an attempt was counted after the first line that can throw, so
the failure rate could never reach the threshold.

## Map
mapReplay failed once in the regression: 1.1 s of black after zooming out. New ground was queued behind
structure checks still winding down after a cancel. Ground now has its own thread and sampling pool
(SeedMapData.buildGround / addStructures). Six replays on three seeds: black gone 210–349 ms after any
move ends (was up to 1,111), 0 of 16 squares without structures.

## Verification — regression-1446.log (+ map checks rerun on the final code)
All checks pass with their verdicts read: stream, slime, repro, strict, profession, feature (0 false,
no stall), layout, icons, radius, nether, smith, wish, privacy, eta, join, joinRepro, cave, tie,
watchdog. Map, on the final code: mapDiag OK; mapTruth 0 phantoms / 0 missed in all dimensions, wide
Overworld 1,600 drawn, 0/0, strongholds 3/3 × 4; mapReplay 6/6.

## Needs your test in game
1. Search → map → drag far → zoom fully out → drag: no black that stays; structures across the map.
2. Join with coords hidden: the hint names your map key; pressing it shows coordinates.
3. Normal searches behave as before (the watchdog should be invisible unless something is wrong).
