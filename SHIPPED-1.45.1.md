# FableVision 1.45.1 — the filter box keeps the keyboard

Jar: `releases\26.3-fabric\fablevision-1.45.1.jar` (Minecraft 26.3). The 1.45.0 jar stays next to it.
Extras 1.1.0 and Pregen 1.2.0 are unchanged and accept 1.45.1. Built and checked headless only.

## The filter box (found in game)
On Custom spawn, typing in "Filter by name…" kept only the first character: every keystroke sent the
cursor to the wish box above it.

**Why.** A filter keystroke marks the list for a rebuild on the next tick. Vanilla's `rebuildWidgets()`
throws every widget away and runs `init()` again, and `init()` ended with `setInitialFocus(wishBox)` —
on every build, not just the first. So the filter box being typed in was replaced by a new, unfocused
one, and the wish box took the keyboard.

**Not a port regression.** 1.44.7 has the same code, and vanilla's `rebuildWidgets()` and
`setInitialFocus()` are identical in 26.1.2 and 26.3. 1.44.7 behaves the same way on 26.1.2.

**The fix.** `init()` no longer sets focus. Just before any rebuild the screen notes which text box has
the keyboard, with its caret and selection (new `TextFocus`), and hands it back in `setInitialFocus()`,
the last thing the rebuild does. When no text box had the keyboard (the first open, or a button was
just clicked) the wish box gets it as before.

**The same pattern elsewhere.**
- *Wish box*: any rebuild while typing in it — the catalog finishing loading, a status line changing,
  a resize — put the caret at the end. Fixed by the same change.
- *Seed-map seed box*: loading the typed seed does not rebuild (since 1.44.3), so it never had the
  filter bug. A dimension tab or a resize did rebuild it, which dropped the cursor and any digits typed
  in the last moment before the seed loaded. It now keeps the keyboard, caret and unloaded text too.

**focusDiag** (new) runs vanilla's own `rebuildWidgets()` on a real Screen with two real EditBoxes,
rebuilding after every keystroke — letters, space, backspace, Ctrl+A and typing over it, a paste, a caret
moved mid-word, a Shift selection, clicks between the boxes — and checks focus, text, caret and
selection each time. Its control, the 1.45.0 behaviour, loses the keyboard 4 times and must. It also
reads both real screens and fails unless they are wired the same way. What it cannot do is press real
keys in a running game; that is the in-game list below.

## Housekeeping
- **Village with Armorer: 36% → 35%.** A fresh `rateDiag 4000` reads 35.1%. With the independent
  4,000-per-type run the 36 came from (35.8%) it pools to 35.49%, and three more streams in villageDiag
  read 33.8%. The 36 rested on that one run's snowy figure (55.5%), which later samples did not repeat
  (52%). Rechecked on a random stream no earlier run used (2,000 per type): 35.2% ± 0.5, and every
  other row within sampling error.
- Stale comments fixed: the igloo, capsized-shipwreck and Abandoned Village counts in SeedCatalog now
  quote the random-sampling runs (Abandoned Village: 1,000 of 1,000 villages agree, 500 of them zombie;
  the player-facing note says 1,000 instead of 120), and SeedAccess.java's comment no longer quotes
  catalog figures. Only that comment changed in SeedAccess.java; its code is still line for line 1.44.7's.
- SHIPPED-1.45.0.md corrected: the four mansion rows moved because 1.44.7 cut percentages down to whole
  numbers (16/600 = 2.7% shipped as 2), not because of chunk-0,0 sampling — mansions come out the same
  at chunk 0,0 and at random chunks.
- mansionDiag samples 600 mansions by default (was 40, too few to meet a 4% room reliably).
- rateDiag takes an optional random-stream argument, so a rate set from one run can be checked on
  samples that run never saw.

## Verification
Full suite, 31 tasks, every one exit 0, nothing else running: `regression-1451.log`.
- **focusDiag** (new): every step of the script keeps focus, text, caret and selection; the control loses
  the keyboard 4 times; both real screens are wired through TextFocus.
- **mapTruthDiag**: Overworld 1,127 / 1,127, Nether 2,060 drawn, End 13 / 13 — 0 phantoms, 0 missed, 92
  types named, 0 wrong; Overworld at 2,800 blocks: 1,600 drawn, 0 phantoms, 0 missed.
- **featureDiag**: 0 false matches. **rateDiag**: every quoted rate within sampling error (Armorer 35).
  **statsDiag**: 126 pieces is the top third (32.4%). **tieDiag, strictDiag, radiusDiag, netherDiag,
  portalDiag, samplerDiag** (294,912 quarts identical): OK.
- **mapReplay** (3 seeds): black gone ≤ 158 ms after a move, 0 of 16 cells empty.
- **layoutDiag, iconDiag, mapDiag, wishDiag, joinDiag, joinRepro, privacyDiag, streamDiag, slimeDiag,
  repro, smithDiag, watchdogDiag, caveDiag, villageDiag, etaDiag, professionDiag**: pass.
- **The gate**: SeedAccess's compiled code is identical in the 1.45.0 and 1.45.1 jars (javap, 271 lines,
  0 differences); only its comment's wording changed.
- **Add-ons**: Extras 1.1.0 and Pregen 1.2.0 rebuilt against 1.45.1 are entry-for-entry identical to the
  released jars, so those were not replaced; Fabric Loader's own version parser says both accept 1.45.1
  (and 1.45.0).

## Needs your test in game
1. **Filter box.** Custom spawn → click "Filter by name…" → type `village` one letter at a time. Every
   letter stays in the filter, the list narrows after each one, and the wish box never shows a cursor.
   Then: Space; Backspace twice; Ctrl+A then type `c` (replaces everything); Ctrl+V a word; click in the
   middle of the text and type (it goes in where you clicked).
2. **Switching boxes.** Click the wish box and type: text goes there; Enter still starts the AI search.
   Click the filter again and type: text goes there; Enter there does not start the AI search.
3. **Rebuilds while typing.** Start typing in the wish box straight after opening Custom spawn the first
   time after launching the game (while the list is still loading), and resize the window or press F11
   while typing in either box: the cursor stays in that box, where it was.
4. **Unchanged on purpose.** Clicking a row, a tab or a toggle still moves the cursor to the wish box —
   the click gives the keyboard to that button, as in 1.44.7.
5. **Keyboard only.** Tab into the filter box and type: it stays there.
6. **Seed map.** Create World → seed map: type a seed; it loads a moment after you stop and the cursor
   stays in the box; keep typing. Type a few digits and resize the window at once: the digits stay and
   still load.
