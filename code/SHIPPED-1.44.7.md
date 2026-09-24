# FableVision 1.44.7 — map threads keep a core free

Jar: `releases\26.1.2-fabric\fablevision-1.44.7.jar`. Extras stays 1.0.3 (needs FableVision ≥ 1.44.3);
pregen 1.1.0 accepts any 1.44.x.

## Map threads
The map's two pools (biomes, structure checks) were capped separately at cores − 1 each, so right after
a move at full zoom-out they could together want more threads than the machine has — with the game
running behind the N-key map. Now every piece of map work holds one of cores − 1 permits (at most 6)
shared by both pools: biome rows, structure checks and their cache lookups, the stronghold biome snap,
and the spawn search. In a world every map thread runs at the lowest priority; on the Create World
screen just below normal.

mapReplay now reads the threads themselves (RUNNABLE, with map work on the stack) and fails over the
cap, and opens the in-world map and fails if any map thread is not at the lowest priority. It found,
in order: cached lookups running outside the cap; threads that did no in-world work keeping their old
priority; and the stronghold biome snap running on the build thread outside the cap. All fixed.

Three seeds: at most 5 map threads working at once on a 6-core machine (cap 5); 12 of 12 in-world map
threads at the lowest priority; black gone 232–330 ms after any move; 0 of 16 squares without
structures. mapDiag OK; mapTruthDiag 0 phantoms, 0 missed, all dimensions.

## Docs
SEED-FINDER-SCOPE.md: "The places a loaded world's seed is read" now lists the third one (the in-world
map's warm-up on joining your own world, since 1.44.3), and the Multiplayer section no longer
describes `/client lan` and Auto LAN, removed in Extras 1.0.1.

## Modrinth changelog (1.44.3 → 1.44.7, user-facing)
- Seed map: typing a seed loads it straight away; Random seed button; "Use this seed" now also fills
  the Create World screen's seed box.
- Map: loads faster; the ground shows at once and every structure arrives together; structures across
  the whole map when zoomed out (strongholds included); no lasting black after dragging or zooming;
  the N-key map keeps a core free and runs at low priority while you play.
- Search: a structure is only promised where the game is certain to build it (an exact biome tie at
  its start point, rare, is no longer promised).
- If a search misbehaves, what it was doing is written to logs/latest.log; if its structure checks
  keep failing it stops with a message instead of running for ever.
- Custom spawn: Coords shown/hidden toggle; shorter AI-limit message; closing the screen while the AI
  is thinking says the request was cancelled when you come back.
- Join message: the /locate and slime notes show once per session; with coordinates hidden it names
  your map key.
- AI: Groq is no longer supported. If you had it selected you are moved to Gemini (free) and the
  stored Groq key is deleted.
