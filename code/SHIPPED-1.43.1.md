# FableVision 1.43.1 + FableVision Extras 1.0.1 — what shipped

## 1. The map drew structures that aren't there — fixed, and now checked against the game

**Why it happened.** The map decided "is there a structure here?" with the old cheap test: the
structure's spot is right, and the biome *at the surface* is one it likes. The search stopped trusting
that test long ago; the map never got the upgrade. The likely story for your Trial Chamber: it starts ~40 blocks underground,
and when the ground down there is Deep Dark the game doesn't build it — but the surface said "fine".
Same failure class as the End City bug.

**The fix.** Every marker now goes through the game's own check, the same one the game runs before it
builds anything: where the structure would *really* start (real terrain height), and the biome *there*.
When a structure type has variants (villages, ruined portals), the game's own re-roll is replayed too.
If the check can't run, the marker is **not drawn** and the map says how many it couldn't check.

**How it was proved.** New check, `gradlew mapTruthDiag`. It builds each world exactly the way a new
Default world does and runs the game's own structure step on every chunk the map covers, then compares.
It does not ask the search, which is how the old check passed while both were wrong.

| | 1.43.0 map (4 seeds) | 1.43.1 map (10 seeds) |
|---|---|---|
| Overworld phantoms | 11 of 626 drawn (shipwrecks, ocean ruins…) | **0** of 1,569 drawn |
| Nether phantoms | 317 of 640 drawn (nearly all nether fossils) | **0** of 1,588 drawn |
| End phantoms | 23 of 31 cities drawn were fake | **0** — 18 of 18 cities real |
| Missed (real but not drawn) | — | Overworld 5 of 1,530 · Nether 22 of 1,515 (fossils) · End 0 |

Also found by the same check: **Nether ruined portals were never on the Nether tab** (they share a
"set" with the overworld portals, and the map filed the whole set under the overworld). Fixed.

The few misses are a speed shortcut: a structure is only fully checked if some biome in its column
could hold it. That shortcut can hide a structure but can never draw a fake one.

## 2. Map slow and black while dragging — fixed

The biomes are painted once into a picture and drawn as one image (it was redrawn every frame as tens
of thousands of little rectangles). The last few pictures stay on screen while new ground loads, and
new ground is now fetched *during* the drag, not only when you let go. Structures fill in nearest-first
as they're checked ("checking which structures really generate… (12 so far)"). Checked structures are
remembered, so panning back is instant.

Widest zoom (20,000 blocks across): Overworld ~3.4 s, Nether ~0.1 s, End ~6 s for the structures to
finish filling in; the biomes appear first, in a fraction of a second.

## 3. End tab — added, with cities

View only; there is still no End search. Before turning cities on, `mapTruthDiag` checked them against
the game: 18 of 18 real, 0 phantoms (the old test drew 31 cities where 8 existed). The switch is
`SeedMapData.END_STRUCTURES`; if it ever shows a phantom, it goes off and the tab says "biomes only".

## 4. Groq model retired again — no longer breaks the panel

When a provider says "that model doesn't exist / was decommissioned", FableVision now asks the provider
for its current model list (same site, same key), drops ones that can't chat (speech, safety filters),
tries up to three, skips any that can't read screenshots, and **saves the first one that answers**. You
see one line: "Groq no longer offers X, so FableVision switched to Y and saved it." No list of model
names is hard-coded to go stale. Works for Groq, Gemini, OpenAI and Claude.
Groq's default is now `qwen/qwen3.8-27b` (Groq's current vision model).

## 5. Gemini timeouts

Gemini "thinks" before answering by default, which was most of the wait. It's now asked not to (the
panel and the seed wish don't need it; a model that insists is asked again normally). A timed-out
request is retried once with 50% more time. If it still fails: *"Gemini didn't answer (tried twice,
150s in all). Google's free tier gets slow at busy times. Wait a minute and ask again, or switch to Groq
under ⚙ Key…"*

## 6. Clear now drops the screenshot too (G panel).

## 7. Time estimate missing on some searches

Cause: for "structure next to structure" wishes (e.g. "village next to a ruined portal") the estimator
crashed on a missing biome and nothing was shown. Fixed — `etaDiag` now includes that wish: estimate
within 1.3× of the real search. If an estimate ever can't be made, the screen now says so: *"no time
estimate for this wish — it couldn't be measured. The search itself is unaffected."*

## 8. The unlabelled white button

It was "✦" = recentre. Now labelled **Centre** (**Me** on the in-world map), and −, +, Slime, Centre and
the End tab all have tooltips.

## 9. Cut-off text

- Under the map: wraps onto more lines instead of "…"; the status line gets priority on short windows.
- Create World badge: "✓ custom spawn — m…" → **"✓ Spawn map"**, full sentence in its tooltip. The grey
  stats line under it has a tooltip with the full text too.

## 10. LAN — cut down to the Bedrock tip

You were right: vanilla's Esc → Open to LAN does the opening. What vanilla doesn't do is Bedrock:
Bedrock friends join through Geyser on port 19132, not the port vanilla prints, and if Geyser isn't
loaded nothing tells you. So Extras 1.0.1 keeps only that, as the **Bedrock LAN tip** toggle in
/client. Removed: the address line, `/client lan`, `/client lan address`, auto-open on join, and all the
network-adapter guessing. Anyone who had Auto LAN on gets the tip on.

## Verification (regression-1431.log)

All 16 checks pass (regression-1431.log, reruns in regression-1431b.log): layout, icons, streams, slime,
repro, radius, nether, strict, feature, profession, smith, wish, privacy (now also the retired-model
rules), eta (now also a structure-pair wish), map, and the new mapTruthDiag.

Two checks failed on the first run and were fixed, not waved through:

- **smithDiag** (a 1.43.0 bug, found by this run's seeds): when the weaponsmith was 8–11 blocks from the
  village coordinate, no second coordinate was printed, so the player was sent next to the house, not
  onto it. Village buildings now always get their own coordinate. Rerun: 40 of 40 on the right house.
- **mapDiag** flagged a village the map shows that the search's first pass doesn't confirm. Asked the
  game: it IS there (village_plains). The map is now the stricter of the two; the search can skip a
  real village there (conservative, never a fake one). mapDiag now settles disagreements by asking the
  game, and only fails if the game disagrees with the map.

## Needs your test in game

1. Seed map: drag — the old picture should stay and new ground fill in while dragging; no black.
2. The seed + coordinates of the phantom Trial Chamber: it should no longer be on the map.
3. End tab on a seed; fly to a city it shows (e.g. with /tp in a test world).
4. Groq: put a made-up model name in config/aiscreen.json ("groqModel") and ask — it should answer and print the switch line. (A config still naming qwen3.6 is rewritten to the new default on load, silently.)
5. G panel: Capture, then Clear, then ask — no screenshot should be sent (the button reads "Capture Screen").
6. "village next to a ruined portal" search — the ETA line should appear.
7. Open to LAN with and without Geyser, Bedrock tip on.
