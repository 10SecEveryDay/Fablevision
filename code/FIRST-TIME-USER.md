# What would make FableVision easier for a first-time user

Requested 2026-08-13. **Nothing here is built.** This is the list and the reasoning, ranked by how
much confusion each one removes per unit of work.

The brief was "I found cubiomes-viewer confusing and don't want to be that." Worth naming what
actually goes wrong in cubiomes-viewer, because it is not that it is ugly or badly made — it is
that **it shows you the whole machine before it shows you a reason to care.** You open it and you
are looking at a map, a seed field, forty filter checkboxes, a threads setting and a progress bar,
none of which mean anything until you already know what a structure salt is. FableVision does not
have that problem in the same shape, because the wish box is a genuinely good front door. It has a
smaller version of the same problem in three places, listed below.

---

## Since this was written (1.40.0)

Three things in this list have moved, and one of them contradicts what you then asked for. Flagging
rather than quietly editing:

- **Item 5 got worse, not better.** It said the row notes "only surface on the old standalone
  screen". That screen has since been deleted for the Modrinth compliance work, so those notes now
  surface **nowhere at all**. Several paragraphs of measured, honest per-row explanation are sitting
  in the jar with no reader. This is now the strongest item on the list.
- **Item 2 is half done.** Fast mode's number is a real distance from spawn as of 1.39.1, and now
  survives exclusions and the radius fix too — so the *behaviour* is there. The two controls have
  not been collapsed into one; that is still worth doing.
- **"Do not add a map preview" — you overrode this, and I think correctly.** The warning was about a
  map that replaces the wish box: something you have to interpret *before* you know what you want,
  which is exactly the cubiomes-viewer failure. What 1.40.0 actually ships is narrower and does not
  have that problem: it is a **result view**, opened from the badge *after* a search has already
  succeeded, showing only finds the search itself decided. Nobody has to read it to use the mod, and
  nothing is asked of the player in front of it. The original warning still stands against putting a
  map on the *picking* screen.

---

## 1. The first thing a new player sees is a screen they did not ask for

`askCustomSpawn` defaults ON, so the very first time someone clicks "Create New World" the mod
takes over the screen with a two-panel picker and a text box. Someone who installed this for the
low-shield setting has no idea what any of it is, and the way out ("No thanks") is a small button
at the bottom left.

**Do:** keep the takeover — it IS the best feature and nobody would find it otherwise — but make
the first appearance say what it is in one sentence at the top, and make the escape hatch the same
visual weight as the search. Something like *"Want your world to start next to something specific?
Describe it, or pick from the list. Or skip this — your world will be completely normal."*

**Why this is first:** it is the only moment where the mod can make someone feel lost before they
have chosen to engage with it. Every other confusion below is one the player walked into on
purpose.

## 2. "Exact" and "Fast" are engine words for a player question

The mode toggle reads `Mode: 🎯Exact ▸ Fast`. Those name the *implementation* (whether the search
pays for a spawn lookup), not the choice being made. The choice being made is **how far away am I
willing to walk**, and that is now literally true after the 1.39.1 fix — Fast's number is a real
distance from spawn.

**Do:** collapse the two controls into one distance control that reads *"Within: right at spawn /
100 / 200 / 400 / 800 blocks"*, where "right at spawn" is what Exact mode is. The speed difference
becomes an implementation detail the app picks for itself, which is what it always was.

**Why:** it removes a whole concept from the UI without removing any capability, and it deletes the
class of bug that produced this round's complaint — a mode whose name promised speed while the
number underneath promised distance.

## 3. Nothing tells you how long a search will take before you start it

The rows say "30% have it" and "§c· slow search", which is real information and the right instinct.
But a player cannot turn "30%" and "2%" and three other rows into "this is a 4-second search" or
"this is a 20-minute search", and the difference matters enormously — it is the difference between
waiting and thinking the mod has hung.

**Do:** put a one-line estimate above the Search button that updates as rows are added: *"About 5
seconds"* / *"A minute or two"* / *"⚠ This could run for a very long time — consider dropping one
thing."* The numbers to build it from already exist (`rarityPct` per row, the measured seeds/sec,
and the multiplication is just the product of the rates).

**Why:** it is the single biggest cause of "is it broken?", and the project already has every
number it needs. It also gives the "slow search" tag somewhere to add up to.

## 4. The bottleneck explanation arrives 90 seconds too late

There is already an excellent feature here — after 90s the app works out which item is the rare one
and names it. It is the best thing on the search screen and almost nobody will ever see it, because
by 90 seconds most people have given up or alt-tabbed.

**Do:** run it at ~15s instead of 90s, and run it *before* the search when the estimate from (3)
comes out very high, as a warning rather than a post-mortem.

**Why:** near-free — the code exists and is already off-thread. It is purely a threshold.

## 5. There is no way to see what a row means before committing to it

Every catalog row carries a genuinely good `note` — several paragraphs of measured, honest
explanation of what is and is not guaranteed. In the custom-spawn picker **none of it is ever
shown.** The player gets a name and a percentage. The notes only surface on the old standalone
screen, which no longer has a command to reach it.

**Do:** show the row's `note` in the empty space at the bottom of the panel when the mouse is over
a row. No new content required, no new layout band — the feedback line is already there.

**Why:** this is the highest ratio of "value already written" to "work to expose it" in the whole
project. It is a tooltip.

## 6. The two AI keys are one concept too many

There are two independent AI keysets — one for the G panel, one for the seed wish — each with its
own provider and key. That split is defensible (a seed wish shouldn't spend your screenshot
quota), but for a first-time user it means being asked to paste a key, and then later being asked
to paste a key again, in a screen that looks identical, for what they think is the same thing.

**Do:** keep the split internally, but on first setup ask once and copy to both. The "Copy my
G-menu key + provider here" button already does exactly this — make it the default rather than a
thing to discover.

**Why:** cheap, and "I already set that up" is a specific and annoying kind of confusion.

## 7. `/client` is not discoverable and the settings screen has no explanation

The welcome chat mentions it once, on first launch, in a message that scrolls away. After that,
someone who forgets has no way to find the settings — there is no entry in the pause menu, no key
bind, nothing in Mods.

**Do:** add the settings screen to the vanilla Options or pause menu, the same way the mod already
bolts a button onto the Create World screen. The pattern is already in the codebase.

## 8. The seed screen's two panels don't say they are two halves of one thing

Left is "browse", right is "what I've chosen", and a new user reads them as two separate lists. The
header on the right says "Searching for", which helps; the left panel has no header at all — it
opens straight into tabs.

**Do:** one word above the left column ("Browse" / "Add things") and an arrow or a `+` between the
columns.

## 9. Nothing recovers from a failed search

When a search is stopped or finds nothing, the player is returned to the picker with their rows
intact and no advice beyond the bottleneck line. There is no "try this instead" button.

**Do:** offer the one-click loosening the bottleneck scan has already identified — *"Drop 'Mansion
with Lava Vault' and search again"* as an actual button.

**Why:** the analysis is already done and shown as text. Making it a button is the difference
between information and help.

## 10. Small honest-labelling things

- The `⚙ key` / `⚙ ✓` button gives no hint what it is for until clicked. `⚙ AI key` fits.
- `Seeds: random` vs `Seeds: 0, 1, 2, …` on the standalone screen is a developer control.
- "8 things at once is the limit" appears only after you try to add a ninth.
- The slime row is the only non-structure on the Structures tab and nothing says so.

---

## What NOT to do

**Do not add a map preview TO THE PICKING SCREEN.** (Narrowed in 1.40.0 — the result map that
shipped is a different thing; see the note at the top.) A map in front of someone who has not yet
said what they want turns "tell me what you want" into "interpret this image", which is a much
harder task for the person the wish box was written for, and it is the thing that made
cubiomes-viewer confusing. After a search has succeeded the position is reversed: the player
already knows what they asked for and the map is just a nicer way to read the answer.

**Do not surface the mode/threads/seed-order internals.** They are currently invisible and correct.

**Do not add more rows to the catalog** until (3) and (5) exist. The catalog is already sixty rows
deep and the constraint is no longer coverage, it is being able to choose.
