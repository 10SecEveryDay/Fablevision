# FableVision 1.46.2 — fixes from your in-game testing

Jar: `releases\26.3-fabric\fablevision-1.46.2.jar` (Minecraft 26.3). The 1.46.1, 1.46.0, 1.45.1 and 1.45.0 jars
stay next to it. Built and checked headless only. SeedAccess and SeedSearchApi are untouched (javap below);
Pregen is untouched and 1.2.0 still refuses 1.46.x.

## What changed

**1. Worlds button: every number 1 to 10.** Click = one more, 10 wraps to 1; right-click or shift-click = one
fewer, 1 wraps to 10. The cap is still `FoundWorlds.MAX` = 10, and the label still shortens on small windows
("Worlds: 7" → "7 worlds" → "×7").

**2. Create World screen: no more drawing over vanilla.** The buttons are placed from vanilla's REAL widget
bounds, read off the live screen: the tab buttons, the widgets and labels of all three tabs (so nothing moves
when you switch tab — the World tab, 310 wide, is the widest), and the footer. The first place and size that
touches none of them wins:
- the top-right corner, then just under the tab bar — full labels, then short ones ("✨ Spawn", "🗺 Map"), then
  icons (with tooltips);
- for the narrowest windows, a row just above Create / Cancel;
- then icons side by side in the header; then the Custom spawn button alone; never on top of vanilla.

Measured with vanilla's own layout code: 427x240 (your screenshot's kind of window) → short labels under the
tab bar; 480x270 → the same; 640x360 and up → full labels top right; 320x240 → full labels in a row above the
footer. The found seed's count ("✓ 318 seeds · under a second") is now plain text under the buttons with NO
tooltip repeating it; where it doesn't fit, it moves into the map button's tooltip instead.

**3. N-key refusal on a server.** The action bar is one line that never wraps, and the whole sentence (535 px)
was cut off at both ends. It now says "The map only opens in your own single-player world" (264 px; fits even
a 320-wide window), measured with the game's font against the window it's drawn in, and goes to chat in full
if it ever doesn't fit. Same for the non-Default-world refusal. **Audit:** this was the only action-bar message
in the core mod. Extras has none either; its one HUD text is the first-run welcome splash, whose last line
("press G to ask AI · /client for settings · ✨ Custom spawn on Create World") is 388 px and is cut off on any
window narrower than that (320–384 wide at the game's own GUI scale) — not changed here (Extras isn't in this
release).

**4. Mode lock.** While any pick is "spawn inside" (⌂), the Mode button shows "Mode: 🎯Exact", greyed, with a
tooltip saying why; your saved Fast setting comes back when the ⌂ is off. The "Within" button, the title and
each row's distance line follow the real mode. **A count above 1 ("2 weaponsmith") does NOT force Exact** —
`SeedCriteria.requiresExact()` only looks at spawn-inside, and worldsDiag now checks that a Fast job with a
count of 2 stays Fast. It is just slower (a counted row is checked in full, candidate by candidate, instead of
being deferred until the rest of the wish has passed) and rarer. So it isn't locked; the row's line says
"2 of them · slower search · within 100 blocks of spawn", and the ×2 button's tooltip explains.

**5. Bottom Search.** With rows picked it searches them, as before. With no rows and a wish the offline reader
understands completely (no AI key needed), it is enabled and runs the wish exactly as ✨ Search does. Anything
else keeps it greyed, and the tooltip says why (which words weren't understood, and what ✨ Search would do).

**6. One map button.** Before: "🗺 Seed map" opened the seed in the box with a box to type another and nothing
marked; "✓ Spawn map" (after a search) opened the found seed with the search's finds marked — with a found seed
in the box, both opened the same seed. Now ONE button, "🗺 Seed map": the seed in the seed box, and, when that
seed came from your search, with its finds marked. A non-Default World Type still gets the refusal.
SEED-FINDER-SCOPE.md updated.

**7. Searching screen.**
- a. A new search clears the old "Stopped after…" message (the ✨ path cleared it; the picker's Search didn't).
- b. One alignment: everything — title, status lines, list, placeholder, feedback, the Stop button — starts on
  the content edge. Paragraphs are wrapped and then evened out, so none ends on a lone word like "64)".
- c. A lower bound no longer reads as a time: "Rarer than 1 in 210,632 seeds — could take much longer than
  2 min for 10 worlds" (and "much longer than a second", never "at least under a second"). Once the search is
  4× past the bound (per world still wanted) with nothing found it says "none found in 105.0M seeds — it may
  be far rarer than that, or out of reach at this distance"; with some found, "only 2 found in …".
- d. layoutDiag now covers the searching screens (single, several-searching, results) — see below.

## Verification
Full suite on the final code, 34 tasks, all exit 0 and every verdict line read: `regression-1462.log`.

- **layoutDiag**, new sections, each with a control that must be caught:
  - **Create World**: 32,020 placements — every width 320 to 1920 × 10 heights × with/without a count line —
    against vanilla's real layout (vanilla's own MenuTabBar, GridLayoutTab.doLayout, GridLayout/LinearLayout and
    HeaderAndFooterLayout, with CreateWorldScreen's sizes; the sizes are read back from the game's classes and
    must match). 0 overlaps, labels inside their buttons, and both buttons present at every size the game makes
    (320x240 up). Control: the 1.46.1 column at 427x240 is caught (5 overlaps, the first with the More tab's button);
    the new placement there passes.
  - **Searching screens**: 1,440 layouts (single, several-searching, results × every size × feedback height ×
    the worst estimate lines) — every line inside the column and on the content edge, no paragraph ending on a
    stranded word, the subtitle whole, and the waited-on lines (the wish and progress; "Found n of N"; the
    results' head) never dropped on a real window. Controls: the greedy wrap's lone "64)" (found at 156 px),
    a centred line and an unwrapped line are all caught; the even wrap passes the same text.
  - **Action bar**: both short wordings fit the narrowest GUI (264 and 233 of 304 px). Control: 1.46.1's
    sentence (535 px) is caught at 320 and at 427.
  - Everything layoutDiag already checked: 1,440 picker sizes and 2,880 found-worlds layouts, 0 problems,
    including the locked Mode label and the shortest count line.
- **worldsDiag 68/68**: Worlds steps 1…10 and back, the new estimate wording (the screenshot's case
  included), a count of 2 stays Fast, spawn-inside runs Exact, plus everything from 1.46.0.
- **The gate**: `SeedAccess`, `SeedAccess$OwnWorld`, `SeedSearchApi` and its three inner classes are
  BYTE-identical (SHA-256 of the .class files) in 1.45.1, 1.46.1 and 1.46.2.
- Everything else as in 1.46.1: featureDiag and multiDiag 0 false matches (6 three-world searches, all three
  different seeds), mapTruthDiag 0 phantoms / 0 missed / 0 wrong types / 0 unverifiable at 1,200 and 2,800 in
  all three dimensions, coldDiag (warm-up on: nothing given up or hidden; the control raced), mapReplay (at most
  6 map threads, 14/14 lowest priority), statsDiag 126 = top third (32.4%), rateDiag, radiusDiag, netherDiag,
  strictDiag, tieDiag, samplerDiag, focusDiag (control loses the keyboard 4 times), wishDiag, joinDiag,
  joinRepro, repro, smithDiag, professionDiag, villageDiag, caveDiag, etaDiag, iconDiag, mapDiag, privacyDiag,
  slimeDiag, streamDiag, portalDiag, watchdogDiag — all pass. villageDiag's "Nether Fossil: confirm is not
  enough" line is unchanged from 1.46.1 (same numbers).

## Not verified
- **Anything in game.** Headless only.
- The Create World model is vanilla's layout code with CreateWorldScreen's sizes copied in; layoutDiag fails if
  those sizes change in the game's classes. In game the screen reads the live widgets, so another mod's widgets
  on that screen are avoided too — but that can only be seen in game.

## Needs your test in game
1. **Create World, window NOT maximized** (the size of your screenshot): Custom spawn and Seed map sit right of
   the tabs' content and touch nothing — check the **Game**, **World** and **More** tabs (switching tabs must not
   move them). Hover each: short-label/icon buttons have tooltips. Then maximize, and shrink as small as the
   game allows: they move (corner → under the tabs → a row above Create / Cancel) and never cover anything.
2. **After a search** (single world): back on Create World there is ONE map button, and a green "✓ … seeds · …"
   line under it with no tooltip of its own (on a small window the line is gone and the map button's tooltip
   has it). Seed map shows the search's finds. Edit the seed in the World tab → the ✓ line goes, and Seed map
   then opens the new seed with a box to type another.
3. **Worlds button**: click from 1 through 10 and back to 1; right-click and shift-click go back one.
4. **Spawn inside**: pick Surface Village, click ⌂ → the Mode button reads "Mode: 🎯Exact", greyed; hover says
   why; the Within button is gone. Click ⌂ again → your Fast setting is back.
5. **Count**: set a village row to ×2 → its line says "2 of them · slower search · …", the Mode button stays
   clickable.
6. **Bottom Search**: with nothing picked, type "village next to a jungle" → the bottom 🔍 Search lights up and
   searches it. Type "a good speedrun seed" → it greys out and the tooltip says what wasn't understood.
7. **Searching screen**: search, press Stop, then search again from the picker → no "Stopped after…" line under
   the new search. All text lines up on the left, Stop included, and nothing ends on a lone word. A rare wish
   with 10 worlds says "could take much longer than …", never "at least under a second".
8. **On a server** (or a friend's LAN world): press N → a short grey line on the action bar, readable end to
   end at your normal window size.
