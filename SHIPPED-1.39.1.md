# FableVision 1.39.1 — what shipped

Built from `E:\Projects\FableVision\code`, jar in `build\libs\` and copied to
`releases\26.1.2-fabric\`. Previous jar was 1.39.0.

The theme that connects most of this round: **the app was telling you things that were not so.**
A distance that was measured from somewhere else, a spoiler setting that leaked, an assistant that
agreed and did nothing, a model name that had been switched off a month ago. Each one is worse than
a missing feature, because you cannot tell from inside the game that it is happening.

---

## FAST MODE — shipped option (b), with the measurement

**Shipped:** on a hit, one spawn lookup, every find re-measured from the real spawn against the
reach it was searched with, and **the seed is rejected and the search carries on** if anything is
outside. The number on the control is now the number the result honours.

**Measured cost** (`gradlew spawnDrift`, new task, 1500 seeds + 400 hits per distance):

| | |
|---|---|
| one spawn lookup | **10.6 ms** |
| hits turned down | **2.5% – 9.7%** depending on distance |
| effective cost per result | about one extra lookup (~12 ms) |

The reason it is that cheap is the other half of the measurement, which I did not expect:
**the real spawn is a median of 40 blocks from 0,0** (p25 0, p75 167, p95 608, max 1224 over 1500
seeds). 68% of seeds spawn within 100 blocks of the origin. So most of the time Fast mode was
already right — it just had no way to know, and no way to catch the times it wasn't.

**I also measured the more expensive alternative** (re-run the whole wish from the real spawn, which
would also accept seeds where a *different* structure of the same kind is near the player). It keeps
91.8%–99.3% against this check's 90.3%–97.5% — a difference inside the noise — for 35% more time
per hit. Not taken.

**Verified**: `gradlew repro`, new BUG 7 case, reads the distance off the finished match line (the
same string chat prints) rather than recomputing it internally. At ≤100: 150 accepted, 13 turned
down, **worst kept exactly 100, zero overshoots**. At ≤400: same, worst kept exactly 400.

Two things this dragged in that were the same bug unnoticed:
- **Biomes were never re-measured at all** in Fast mode — only structures ever got a true-distance
  line. "cherry grove within 100" had exactly the same lie in it. Now checked.
- **Slime chunks were counted around the origin**, so "3 within 64 blocks of spawn" could be false
  where you actually stand. Now re-counted from the real spawn (costs microseconds — it is a hash).
- Fast-mode lines used to read "96 blocks from spawn" under a header saying they were measured from
  the origin. They now say "from the world origin", and the checked spawn distance is appended.

The searching screen now says "N near-misses turned down" while this is happening, because a search
finding and rejecting candidates looks identical to a search finding nothing, and those want
opposite responses from you.

---

## Bugs

**"Disable all features" did nothing and said it had.** There was no marker for "all" — the
assistant had five individual names and no way to say "all of them", so it did the human thing and
said it in prose, and prose is not a mechanism. Three changes: added `all` (plus `autoLan` and
`seedSpoilers`); a directive that gets **refused is now reported in red** instead of stripped in
silence; and **a reply that claims a change in the first person while applying none gets an explicit
correction under it** — "nothing actually changed, your settings are exactly as they were". The
claim detector requires an actor *and* a past tense, so "you can turn low shield off in /client"
(a correct answer to a question) does not trip it.

**Seed spoilers leaked — reproduced first.** `withoutCoords` matched three specific line shapes on
the reasoning that every line is built by `findLine`. That reasoning was wrong the day it was
written: the **slime row builds its own line** and printed exact chunk coordinates to someone who
had turned spoilers off. Rather than add a fourth pattern, the rule is inverted — any pair of
numbers separated by a comma is treated as a position and hidden — so the next line that prints
coordinates is covered before anyone thinks about it. The one line that legitimately said "0, 0"
was reworded to "the world origin" rather than excepted; an exception is an allowlist by another
name. New BUG 6 case in `repro` checks the *output*, not the patterns: **0 leaks**.

**Groq's model.** Confirmed externally: `meta-llama/llama-4-scout-17b-16e-instruct` was
**shut down by Groq on 17 July 2026**, so every G-menu request had been a 404 for a month. Now
`qwen/qwen3.6-27b`. That choice was not free — the panel's whole job is answering questions about
a screenshot, and of the two replacements Groq suggests, `gpt-oss-120b` is **text-only** and would
have broken the panel more quietly than the 404 did. qwen3.6-27b is the only vision model Groq
currently documents. Dead names in a saved `aiscreen.json` are migrated on load (the new default
never reaches a config file that already has the field). A 404 naming a model now also says where
the name is kept, because this will happen again.

**AppleSkin saturation going stale during a spear lunge.** Your diagnosis was exactly right and it
is a refresh-trigger problem, but the trigger is not ours. `ServerPlayer.doTick` only sends the
health packet when health changes, food **level** changes, or saturation crosses zero:

```
getHealth() != lastSentHealth
|| lastSentFood != foodData.getFoodLevel()
|| (foodData.getSaturationLevel() == 0.0F) != lastFoodSaturationZero
```

Saturation is *carried by* that packet but is not one of the things that triggers it. So draining
5.0 → 4.2 → 3.1 sends nothing and the client value is frozen — then it hits zero, the third clause
flips, a packet goes, and the display jumps. That is the "then it catches up". A lunge is just a
burst of exhaustion big enough to make the gap visible. **Fix:** when we host the world, read the
authoritative `FoodData` off our own integrated server. Deliberately not cached (a static
`ServerPlayer` would pin the dead server after you left the world).

---

## Sliders

Fire and shield offsets now read **1 to 50**, the same scale on both, with 50 landing on each one's
own measured ceiling (0.47 and 0.55 — those numbers were correct, showing them was the problem). The
stored float is unchanged, so renderers and older config files are unaffected. The scale starts at 1
rather than 0 because step 0 would mean "off", which is what the toggle beside it is for. The AI
speaks the same units now.

## Menus

- Empty button-sized gap **gone** — Auto LAN moved into it, so the grid is 5 full rows.
- **"Open to LAN now" removed.** But the Bedrock/Geyser join address it printed did not exist
  anywhere else, so deleting the button alone would have quietly deleted the Bedrock support. The
  toggle now **watches for the world becoming published** and prints the join info however it was
  opened — including from vanilla's own Esc menu, which is what you asked for.
- **AI usage counter removed.** It only tracked Gemini, and it was our local count against a cap we
  had guessed at — already wrong once by a factor of twelve (250, then 21) — sitting next to a
  provider error that is always right.

## Seed screen

- **▲▼ scroll buttons removed.** They were drawn at the tab row's y across the last 40px of its
  width, i.e. directly on top of "Nether · End", which is why the tab could not be clicked. Wheel,
  scrollbar and the "scroll ▲▼" count line all already covered it.
- **All five End biomes dropped.** The End is laid out by distance from the centre island in every
  seed, so those rows answer the same in every world and reject nothing. **End structures stay** —
  which outer island gets a city really is a per-seed roll.
- Slime row: "free to check" dropped.

## Icons — reviewed, plus the ones I could not solve

New `gradlew iconDiag` walks the **live catalog** (not the switch) and fails on a missing or shared
icon. Result: **60 structure rows / 60 distinct icons, 55 biome rows / 55 distinct icons.**

Actually broken and fixed:
- **Surface / Buried / Underwater Ruined Portal and Slime Chunks had no icons at all** — four rows
  silently showing the filled-map fallback. Now grass block / sand / water bucket (they read as a
  set: on land, in the sand, under water) and a slime ball.
- **Ocean Monument, Ocean Ruins, Large Ocean Ruin** were three shades of prismarine — one teal
  square, three times. Monument → sea lantern; the ruins now show the stone brick and sandstone they
  are actually built from (prismarine is not in an ocean ruin at all).
- **Two "Stables" rows shared a hay block** (village vs trail ruins) — trail ruins → packed mud.
- **Leatherworker and Swamp Hut shared a cauldron** — swamp hut → witch spawn egg.
- **frozen_peaks and ice_spikes** shared packed ice; **frozen_ocean and frozen_river** shared ice.
- Jungle Pyramid was chiseled stone bricks (a *stronghold* texture) → mossy cobblestone.
- Abandoned Village: cobweb → zombie villager spawn egg. Buried Treasure: chest → heart of the sea.
- Outpost with Cages: dark oak fence → iron bars. Desert Well: smooth sandstone → suspicious sand.
- Trial Chamber with Slanted Room: tuff bricks → polished tuff **stairs** (the only shape in the
  game that reads as a slope).
- Biome fallback was a **grass block**, i.e. an unlisted biome looked exactly like plains. Now the
  filled map, so a gap looks like a gap.
- Removed the dead Stronghold and Dungeon cases (neither row has existed since 1.36.0).

**Could not find a good item for — listed as asked:**

| Row | Why | What it shows now |
|---|---|---|
| **Capsized Shipwreck** | "Capsized" means upside-down; no item depicts an upside-down anything. Can only be a second boat next to the Shipwreck row's boat. | Spruce boat (dark) vs oak (pale) — the most separation available |
| **Ancient City with Sauna** | Nothing in the game depicts a sauna. | Soul lantern — says "ancient city", leaves the row text to say which part |
| **Giant Ruined Portal** | "Giant" is a size, and size is not depictable. | Obsidian, next to the plain row's crying obsidian — distinguishable (teal drips) but the weakest pair left |
| **Large Ocean Ruin** | Same problem: "large" is a size. | Cracked stone bricks, distinct from the plain and cold ruins but only just |
| **Trial Chamber with Encounter Hall** | Trial spawners are in every chamber, so it says "chamber", not "encounter hall". | Trial spawner |
| **Desert Pyramid** | Sandstone is generic desert rather than specifically a pyramid; nothing better is free. | Sandstone (unchanged) |

---

## Decisions I made where a choice came up

1. **Fast mode: cheap re-check, not the full re-run.** Measured both; the expensive one buys 1–2%
   more kept seeds for 35% more time per hit. Documented in the code with the numbers.
2. **A throw during the spawn re-check now rejects the seed** rather than keeping it with the old
   lines. The previous behaviour was defensible when the pass only *described*; now that it is part
   of the verdict, an unverifiable distance must not report a match — the project's own rule for
   unverifiable predicates.
3. **Groq → qwen3.6-27b, not gpt-oss-120b**, despite the latter being Groq's headline replacement,
   because the G-menu needs vision. Noted above.
4. **The AI claim-detector is deliberately conservative** — actor + past tense — because a warning
   that fires on correct answers teaches people to ignore warnings.
5. **Slider scale starts at 1, not 0.** Step 0 would be a slider position that silently means "off".
6. **The AppleSkin fix does nothing on a remote server and does not pretend to.** There is no local
   copy to read, and predicting exhaustion client-side would mean drawing a number the server has
   not agreed to. Real AppleSkin solves that case by installing on the server too.
7. **Corrected two false statements in the in-game guide** while I was in there: it promised
   portal-eye counts "this week" and said the search finds "the nearest stronghold". Neither has
   been true since 1.36.0. Also fixed `README.txt`, which still said the live code was 1.35.0.

## Known gap I did NOT close (deliberately)

**Fast-mode exclusions are still measured from the origin.** "No desert near spawn" in Fast mode
checks around 0,0, not around your spawn. Closing it needs a fresh stage-0 scan per hit, which is
more code in the riskiest file at the end of a session I cannot playtest. The error is also
proportionally much smaller than the one you reported: exclusion radii floor at 256 blocks against
a median 40-block drift. Flagging rather than building — say the word and it is a contained change.

---

## Needs your in-game test

I can verify seed-finder behaviour headlessly; the rest of these are things only the running game
can answer.

1. **AppleSkin during a spear lunge** — the actual fix. Watch the gold overlay drain smoothly
   instead of jumping when it hits zero. Also worth checking on a LAN world you host (should work)
   and, if you ever play on someone else's server, that it is no *worse* than before there.
2. **The G-menu on Groq** — paste your Groq key and ask it something about a screenshot, to confirm
   `qwen/qwen3.6-27b` accepts our image payload shape. This is the one change I could not test at
   all; the request format is unchanged and OpenAI-compatible, but the model is new to us.
3. **"Disable all features"** in the G panel — should flip everything and list what it changed in
   green. Then try something impossible ("turn on x-ray") and confirm you get the red warning rather
   than a confident sentence.
4. **The Nether · End tab** — confirm it is clickable now.
5. **Both offset sliders** — that 1–50 reads sensibly and 50 still puts the fire/shield fully out of
   view.
6. **Auto LAN via vanilla's Esc → Open to LAN**, with the toggle ON — the Geyser/Java addresses
   should print once, and only once.
7. **Fast mode at 100 blocks** — load in and confirm the chat distance is ≤100. This is verified
   headlessly, but it is the bug you reported and worth seeing.

## Regression run

`gradlew iconDiag layoutDiag wishDiag slimeDiag villageDiag repro`

- **iconDiag** — 60/60 and 55/55 distinct, no fallbacks ✓
- **layoutDiag** — 360 window sizes, **0 overlaps** ✓
- **wishDiag** — every documented wish resolves as expected; End biomes correctly absent from the
  allowed list the AI is taught ✓
- **slimeDiag** — 1-in-10 rate and the full radius table match the documented figures exactly ✓
- **villageDiag** — 40/40 villages assembled, 117.1 avg pieces, 83 distinct templates; Nether Fossil
  48% and End City 25% confirmed-slot rates unchanged (both still need their existence tokens) ✓
- **repro** — BUG 3/4/5 unchanged, **BUG 6 (spoilers) 0 leaks**, **BUG 7 (fast distance) 0
  overshoots** ✓

Not run: the multi-hour measurement diagnostics (`bench`, `speedDiag`, `mansionDiag`,
`professionDiag`, `statsDiag`, `portalDiag`, `biomeShape`, `strictDiag`). They measure world-gen
data none of this round touched, and you asked for no long benchmarks without checking first. The
one new measurement this round needed — `spawnDrift` — was run and is reported above.
