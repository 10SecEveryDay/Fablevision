# FableVision 1.44.3 + Extras 1.0.3 — in-game test round

Eleven reports from playing 1.44.2. Jars: `releases\26.1.2-fabric\fablevision-1.44.3.jar` and
`fablevision-extras-1.0.3.jar` (Extras now requires FableVision ≥ 1.44.3).

## What changed

| # | report | what was wrong | fix |
|---|---|---|---|
| 1 | join message still lectures about /locate | two lines in capitals on every join | one short line (`SeedCriteria.LOCATE_TIP`), printed once per session |
| 2 | "Use this seed" doesn't put the seed in | it took the last seed *shown*, not the box's text; and the Create World screen's own seed box is filled once and never re-reads the settings, so even a correct seed looked wrong | reads the box when pressed; `SeedBox.put` sets the settings **and** the World tab's visible box (widened `tabNavigationBar`, `WorldTab.seedEdit`). Search results use it too |
| 3 | random seed button | — | **Random** next to the seed box |
| 4 | typing needs a Show press | — | Show removed; a typed seed loads 0.3 s after the last keystroke (Enter still loads at once) |
| 5 | map slow | one min-priority thread; noise state and structure bookkeeping rebuilt per view; structure files loaded on first view | biome grid and structure checks split over up to 6 threads; per-seed parts cached; warmed when Create World opens and when you join your own world |
| 6 | structures trickle in / some missing | views were published biomes-first then in 150 ms dribbles; strongholds never drawn; the cheap biome gate sampled only the chunk middle, so jigsaw structures whose start piece sat over a biome edge were hidden | one publish per view (biomes + everything); strongholds from vanilla's ring positions (only the few near the view are worked out — see below); gate also samples 24 blocks out; cap 160 → 400 |
| 7 | "82m" after each list entry | — | names only (distance stays in the hover tooltip) |
| 8 | Coords toggle gone from Custom spawn | — | third button in the bottom band, with shorter wordings on narrow windows (layoutDiag checks them) |
| 9 | AI-limit line runs on | — | "AI limit reached for today — pick below instead." |
| 10 | Esc while AI thinks, reopen: silence | the request is cancelled on close (correctly, since 1.44.2) but the new screen had no way to know | it says "Your AI request was cancelled when you closed this screen. Press Search to ask again." and the wish is back in the box |
| 11 | remove Groq | — | provider, endpoint, model fallback list, Groq-only model-name hints, key slots and settings fields gone. A saved Groq choice moves to Gemini (now labelled "Gemini (free)"); stored Groq keys are deleted; old fields drop out of `aiscreen.json` on first load. `gsk_` keys are still redacted if pasted into a question. AI guide, help screen and panel text updated |

## Found while fixing 6

**Vanilla's biome lookup is history-dependent at exact ties.** `Climate.RTree` keeps a per-thread
`lastResult` and starts each search from it, so on a tie between two biomes the answer depends on
what that thread looked up just before. With the wider gate, mapTruthDiag caught two Nether fossils
drawn on a nether_wastes / soul_sand_valley tie — the gate had just sampled soul_sand_valley on the
same thread. Proved with a probe: the stub point reads nether_wastes cold and soul_sand_valley right
after a soul_sand_valley lookup. Fix: `WorldgenContext.forgetBiomeHint` (widened `index`,
`lastResult`) before every real structure check. The narrow gate had been hiding both the misses and
this. **The search probably has the same exposure** (VillageLayout's checks); left for a separate
change.

**Strongholds without the one-second cost.** Vanilla places all 128 at once (~1.7 s here). The map
replays `generateRingPositions`' random stream for all 128 (nearly free) and does the 112-block biome
snap only for positions that could land in view. mapTruthDiag compares against the game's own list:
3 of 3 on every wide seed.

## Speed (mapDiag, same seeds as 1.44.2)

| | 1.44.2 | 1.44.3 |
|---|---|---|
| first build | 974 ms | 72–786 ms (warm-up dependent) |
| per fresh seed, 1,024 blocks across | 373–510 ms | 201–350 ms |
| mapTruth 2,400 across, Overworld | 1,400–1,770 ms (160 cap) | 570–1,340 ms (400 cap) |
| same, forced single thread | — | 8.8 s vs 2.8 s parallel at 5,600 across |

## Verification — regression-1443.log, 20 checks, verdicts read from output

The five that could only just start failing:
- **streamDiag**: 32,000 draws → 32,000 distinct, 0 wasted; random by default, reproducible when pinned.
- **slimeDiag**: 512,112 of 5,120,000 chunks (10.002%) — OK.
- **repro**: all repro checks pass; spoilers 0 leaks, short join 0 leaks.
- **strictDiag**: 471 confirmed hits examined; every hit vanilla rejects, the funnel rejects.
- **professionDiag**: missing rows 0, spellings missed 0.

The rest: layout 1,440 sizes / 0 problems (including the three-button band and new messages);
icons all distinct; radius, nether, cave, eta, smith, wish, join, joinRepro (all 14 short) pass;
feature unchanged from 1.44.2 (7 of 33, worst 114); privacy all ok including five new Groq-removal
checks; mapDiag OK; **mapTruth 0 phantoms, 0 missed in all three dimensions** (1.44.2: 0 / 13), and
the new wide Overworld run 1,600 drawn, 0 phantoms, 0 missed, strongholds 3/3 × 4 seeds.

## Needs your test in game
1. Seed map: type a seed (no Enter) — it loads; Random; Use this seed → World tab shows that seed → Create.
2. Map opens and fills in one go, both from Create World and with N in your own world; zoom out past
   ~1,500 blocks and strongholds appear. Drag feels OK (new ground arrives between drags, not mid-drag).
3. Custom spawn: Coords toggle at a small window size; Esc while the AI thinks, reopen → "cancelled".
4. A config that had Groq selected opens on Gemini with no error; ⚙ Key cycles Gemini → GPT → Claude.
5. Two joins in one session: the /locate line appears on the first only.
