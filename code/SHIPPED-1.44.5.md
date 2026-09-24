# FableVision 1.44.5 — the map, reproduced headlessly; the coordinates hint

Jar: `releases\26.1.2-fabric\fablevision-1.44.5.jar`. Extras stays 1.0.3.

## Why the last two map fixes didn't work
They were verified with numbers about BUILDS (how long, how fast one stops). The bug was in what the
SCREEN did with builds, which nothing headless could see. The map's logic is now `SeedMapModel` (the
screen only paints what it says), and `mapReplay` plays a player's moves against it in real time:
open the search-result map, drag 3 widths, scroll fully out, drag 2 widths, let go — measuring black
on screen every frame and, at the end, asking an independent build square by square whether the game
has structures where the map shows none.

## Reproduced with the 1.44.4 logic
- black 2.8–4.1 s after letting go, 5–6 s at a stretch while moving: a view was handed over only once
  all its structures were checked (seconds at full zoom-out) though its ground was ready in ~0.1 s;
- 12 of 16 squares of the zoomed-out view showed no structures where the game had 244–400 each: the
  map kept the 400 nearest the middle — a disc in the centre. Zooming in and back out worked around it.

## Fixed
- Ground (biomes) handed over as soon as it is ready; all structures still arrive together.
- A build is replaced once the map leaves the ground it will cover; while the mouse is down, one still
  making its ground is let finish (some ground beats none); on release it is not waited for.
- Each of 10 × 10 squares gets 4 structures nearest its middle, then leftover budget is filled nearest
  first — close-up views still show everything. mapTruthDiag judges misses per square at the locate point.

## Join message
"coordinates hidden — show them from the map screen" pointed at a screen unreachable from inside the
world. Now: "press N for this world's map, or turn Coords on in Custom spawn next time" (the real key
binding). joinDiag checks the hint names only places where coordinates really show.

## Verification — regression-1445.log + feature-1445.log
mapReplay (after the release fix, 4 runs on 3 seeds): black gone 118–573 ms after any move ends,
every view completes, 0 of 16 squares empty where the game has structures. mapTruth 0 phantoms,
0 missed, all dimensions. tieDiag 0 history-dependent, 0 promised ties. featureDiag: 0 false matches,
all 66 rows identical to 1.44.4 (a first run stalled >2 h in one process; rerun alone finished in
28 min — not reproduced). All other checks pass as in 1.44.4.

## Needs your test in game
1. Search → map → drag far → scroll fully out → drag: no black that stays after letting go.
2. Zoomed fully out: structures across the whole map, not just the middle.
3. Join with coords hidden: the hint names your map key; pressing it shows coordinates.
