# FableVision 1.42.0 — what shipped

Nine things were reported from playing 1.41.3. This is what each one turned out to be, what
changed, and what still needs testing in game.

Two of them were not what they looked like, and both are worth reading before the rest.

---

## 1. "The weaponsmith house isn't there" — the search was right and the walk still failed

This was reported as the same failure as the End City phantom: coordinates handed over, player goes
there, nothing. It is not that failure, and the difference matters because the fix is different.

**The claim is true.** Two independent checks say so, and neither uses the search's own answer:

- `fastDiag` re-assembles the reported structure with code that never touches the funnel and finds
  the building in it every time, in Fast mode and in Exact mode, with zero false matches.
- `professionDiag verify` proves the token fires in every village type that ships the building —
  plains 22%, desert 17%, savanna 37%, taiga 77%, and snowy 0% because snowy villages have **no
  weaponsmith template at all**, which is absence rather than rarity.

I also compared the 1.41.3 and 1.41.4 jars method by method: nothing about village content rows
changed between them, so this is not a regression either.

**So what fails.** Nobody had asked the question a player actually asks, which is not "is it there"
but "where is it". A content row promises a **building**; every line this program prints names the
**structure's** locate position, which is derived from its chunk. A village routinely spans 130 by
176 blocks. Those are different places.

The new `featureDiag` measures the gap, for every content row, in both modes:

| row | median gap | worst seen |
|---|---|---|
| Village with Weaponsmith | 29 blocks | 59 |
| Village with Armorer | 45 blocks | 49 |
| Village with Cartographer | 58 blocks | 63 |
| Ancient City with Sauna | 114 blocks | 114 |

One of the first six weaponsmith seeds it looked at put the house **67 blocks** from the printed
coordinate. The player stood exactly where they were sent, inside a village that really did have a
weaponsmith, with nothing on screen telling them which way to look or how far.

**The fix.** The assembly already knows where the piece landed and was throwing it away. Every find
now names the building's own coordinates:

```
Village with Weaponsmith — Overworld 64, 16 · 66 blocks from the world origin
    — weaponsmith 48 blocks further on, at 33, -20
```

It is suppressed under 12 blocks, where the building is where you land and a second coordinate is
noise. It applies to **every** content row, not just villages, which answers the "does this affect
other professions or other rows" half of the report: it affected all of them and all of them are
fixed.

**What I did NOT change, deliberately.** The radius is still measured to the structure. "A village
with a weaponsmith within 100" can still put the weaponsmith at 160. Holding the *building* to the
radius would be a defensible reading of the wish and would make every content search substantially
rarer and slower, and that is your call rather than mine. The line now tells you the number either
way.

## 2. The AI failing on long prompts — it was not the prompt

The 4096-token output budget added in 1.41.4, written for exactly this symptom, **never reached
Claude**. `buildAnthropic` took the budget as an argument and then sent a hardcoded `1024`. Every
other provider got the fix.

Three more faults in the same area, all of which make a request look like it simply did not answer:

- **Neither Claude nor Gemini detected truncation.** Claude spells the ceiling `max_tokens` and
  Gemini `MAX_TOKENS`; only the OpenAI shape's `finish_reason: length` was handled. A cut-off reply
  therefore arrived at the wish parser indistinguishable from a model that had answered in prose,
  and was reported as "the AI didn't answer with a search. It said: json" — which is the `json` reply
  from the report, verbatim.
- **Gemini threw on a response with a `finishReason` and no `content`.** That is what it returns
  when it stops at the ceiling before writing anything, and it is the literal "it doesn't respond
  at all" case.
- **`AiVision` caught `Exception`, not `Throwable`.** The one thing every caller depends on is that
  the callback fires exactly once; an `Error` escaping left the Custom-spawn button on `✨ …` for
  the rest of the session with no way to retry.

All three providers now report a cut-off answer the same way. The wish request gets a 120-second
ceiling instead of 60 (the G panel keeps 60 — different kind of wait). The screen gives up on a lost
request after 135 seconds instead of locking the button.

**The real limit, stated.** `wishDiag` now prints it: the prompt is **27,140 characters, about 6,800
tokens**, before the player's own words. The wish box caps at 200 characters. That is the number to
look at first if a provider starts refusing.

---

## 3. The End

The tab said "Nether · End". It says **Nether**.

The dimension itself was already gone. What was missing was proof that it is *unreachable* rather
than merely unlisted, so `wishDiag` now:

- puts **nine invented End names** through the real validator — "End City", "End City with Ship",
  "end_highlands" and so on — and fails if any of them resolves to a search;
- pulls **every name written into the prompt's worked examples** and holds it to the live allowed
  lists. The examples were the one part of the prompt that was never gated, and they are the most
  imitable part of it: a model shown eight finished answers copies their shape *and their names*.
  They are a gated table now — an example naming a row this world did not build is dropped whole.

The prompt still *says* "End City", in the one paragraph whose job is to refuse it. Removing that
would mean a player asking about the elytra gets "that isn't something the search can look for",
which is true, useless, and indistinguishable from a bug.

## 4. "≤100" read like code

Every player-visible `≤` and `≥` is now words: the search summary ("Village within 300"), the slime
row, the Fast distance button ("Within 800"), the biome size line ("608 blocks across or more"), and
the pair chip. The only ones left are in source comments.

## 5. Text overflow, and why `layoutDiag` could not see the worst of it

It had never looked at a **button**. A vanilla button does not truncate — it draws its label centred
and lets the overflow run over whatever is next to it. On a 320-wide window each of the four category
tabs gets 34 pixels and "Structures" needs 60, so all four drew through each other. The geometry was
perfect; only the words overlapped, and the check was about boxes.

Second one: a chosen row drew its detail line at +14, inside an 18-pixel band of buttons, defended
only by reserving 62 pixels of width — which on a narrow column left the sentence ten characters.

Fixed:

- Tabs pick the longest of three wordings that fits (`Village` / `Vill` / `Vil`).
- A chosen row is 30 tall instead of 26, with its detail line below the buttons and the full column
  width. The size word is dropped when keeping it would leave the name with nothing.
- The detail line and the distance line degrade through three wordings rather than being cut.
- Several fixed messages shortened, including one that named a button ("⚙ Add AI key") that does not
  exist — it says "⚙ key".

`layoutDiag` now checks **1,440 window-and-message combinations** — 9 widths × 10 heights × 4 pair
counts × 4 feedback heights — and covers every button label, every truncating slot and every fixed
message, with the worst-case strings **read from the live catalog** rather than typed in. Clipping
is down from 840 combinations to 96, all of them at 240 pixels tall, which is the absolute floor
Minecraft will ever hand a screen. **Half-screen (480×270) shows every message in full.**

## 6. Distances in every dimension

Already fixed in 1.41.4, which you never received — the per-dimension radius floors are gone and
Exact mode looks up the real spawn for Nether-only wishes. `radiusDiag` confirms it across both
dimensions and both modes.

The audit you asked for is now a file rather than a claim. **`netherDiag` did not exist** — several
comments in the main code pointed at it. It does now, and it holds every Nether row to the radius
asked, measured from the portal-in point:

```
7 Nether rows × 2 modes = 14 runs, 0 finds over the asked radius
the portal-in point is up to 143 Nether blocks from Nether 0,0 across 400 seeds
```

The other two verification fixes were checked in the Nether too: the exact-radius test and the
Fast-mode spawn re-check are not dimension-gated, and `strictDiag` already walks the Nether rows.

## 7. The join message on Nether finds

1.41.4's fix was not wrong, it was bypassed. The tight-Nether **warning** ("this may run a long
time") was written into the same field as a real note, and a non-empty note is what forces the long
form. Every Nether wish tighter than 208 blocks — which is every Nether wish in Exact mode and the
default one in Fast — got the four-line message because it had been warned it might be slow and then
wasn't.

A warning about the wait is void the moment a result arrives. It has its own field now and never
leaves the search screen.

Found alongside it: Exact mode's parked **backup** result carried no structured finds, so any search
that settled for one got the long message *and an empty map*. It is described before it is parked.

---

## Also fixed, not reported

- **`VillageLayout.piecesAt`** keeps a piece's id and its box together. `templateIds` returned a list
  of a *different length* from `getPieces()` — a code-built piece contributes nothing and a ruined
  portal contributes two entries — and `inspect` was zipping them by index, getting away with it only
  because in a village the unnamed pieces happen to come last.
- **`SeedAccess.worldTypeProblem`** — see the next section. This is the one I would want you to look
  at hardest.

## The thing I found that nobody reported

**Nothing checked the World Type button.**

Every seed the finder tests is generated with the default overworld and nether generators — the
vanilla multi-noise biome preset and the vanilla noise settings, hard-wired in `WorldgenContext`.
The Create World screen's **World Type** button changes exactly those. Pick Large Biomes, Amplified,
Single Biome or Superflat, press Create, and the world that generates is not the world the search
examined. Every coordinate it reported is then a coordinate about a different world.

Nothing warned. The seed was typed in and the search looked like it had worked. This is the End City
failure in a different costume, and it would produce exactly the report "I went to the coordinates
and there was nothing there" — intermittently, depending on what the World Type button happened to
be set to.

Both search paths now refuse, with the reason:

> The seed finder only works on the default world type. Set World Type back to Default and it will
> search — on Large Biomes, Amplified, Single Biome or Superflat the world generates differently
> from the one it checks, so the coordinates would be wrong.

I chose refusal over support on purpose. Following the selected preset would mean every measured
rate in the catalog — 30% of villages have an armorer, 1 mansion in 50 has the lava vault, a big
village is 133 pieces — is a number measured in one world type and quoted in another, and those
numbers are the whole basis on which someone decides whether a search is worth waiting for.

---

## Verification

| check | result |
|---|---|
| `featureDiag` — every content row, both modes | 0 false matches, 0 un-re-derivable |
| `netherDiag` — 7 Nether rows, both modes | 0 finds over the asked radius |
| `radiusDiag` — 9 cases, both dimensions, both modes | every dimension honours its radius |
| `layoutDiag` — 1,440 combinations | 0 failures; clipping only at 240px tall |
| `wishDiag` — reply shapes, End reachability, prompt vocabulary | all pass |
| `repro` — the eight historical bug reproductions | all pass |
| `strictDiag` | unchanged |

## What needs your test in game

1. **A village profession search.** Ask for a weaponsmith. The find line should now end with the
   weaponsmith's own coordinates and how much further on it is. Go to the structure coordinate
   first, then the building one — confirm the second is the house.
2. **The World Type refusal.** Set World Type to Large Biomes on the Create World screen and press
   Search. It should refuse with the message above rather than searching.
3. **The Nether tab** should read "Nether", and a Nether Fortress search at 100 blocks should give
   one within 100 of where your portal comes out.
4. **A Nether find's join message** should be three or four short lines, not the paragraph.
5. **Half-screen and smaller.** Drag the window down, trigger a long message (press Search with
   nothing picked, or pick two bastion-family rows and link them), and confirm nothing is cut off or
   drawn over anything. The tabs should abbreviate rather than run together.
6. **A long AI wish**, and a bundle wish like "best survival seed". If your provider is Claude this
   is the one that was broken.
