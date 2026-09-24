# Pre-release audit — everything broken, stale, contradictory or half-finished

Nothing below is fixed. 35 main sources + 17 diagnostics, checked for: dead code, stale comments,
features referenced but removed, notes that disagree with measured data, diagnostics testing things
that no longer exist.

**23 findings.** Four of them are things I introduced in the last three releases, marked ⚠ **mine**.

> **UPDATE 1.41.1 — six fixed.** A1, A2, A4/B2, B1, D1 and D2 are done and are marked **[FIXED]**
> below. One of them turned out worse than reported: the Bridge Bastion claim was not merely
> unmeasured, it was **false** — measured at 800 assembled bastions, bridge is 26% and the *second
> most common*, with all four types within 23–26% of each other. Everything else on this list is
> still open.

---

## A. Contradicts the shipped behaviour — fix before release

### A1. [FIXED] `SeedCriteria`'s class javadoc describes the bug that was fixed ⚠ **mine**
`SeedCriteria.java:37-44` — the class-level doc of the core file still says:

> *"unless Fast mode is on, which measures from 0,0 like classic seed tools (much faster, but the
> target may be **a short walk from where you spawn**)"*

Since 1.39.1 that is exactly what cannot happen: Fast mode verifies every find against the real
spawn and rejects the seed if anything is outside the asked distance. The one sentence a reader
starts from describes the behaviour we removed, in a file whose whole point is now the opposite.

### A2. [FIXED] The picker refuses a pair the engine supports, with a false reason
`SpawnWishScreen.java:639-640` — linking two structures is rejected with:

> *"One end has to be a biome — the distance is measured from the biome patch."*

Both halves are untrue. `SeedCriteria.addStructurePair` has shipped since 1.38 and is used by the
AI path (`WishParser:1316`) and by `repro` BUG 3; when the anchor is a structure the distance is
measured from the structure the funnel already found, which the code comments correctly note is
*cheaper* than a biome anchor. So "fortress next to bastion" works if you type it and is refused if
you click it — and the refusal explains a limitation that does not exist.

Half-finished, not just mislabelled: `startSimple()` (~line 947) only ever looks up a **biome**
anchor and `continue`s otherwise, so allowing the click alone would silently drop the pair.

### A3. [FIXED in passing] `SeedFunnel` header: "Production ships with all three ON"
`SeedFunnel.java:24` — there are **nine** toggles, and `BIOME_PREFILTER` ships **OFF** (its own doc
says so and explains why). The sentence is wrong about the count and about the default.

### A4. [FIXED] `allOff()` now disables a correctness fix, not just optimisations ⚠ **mine**
`SeedFunnel.java:161` is documented as *"All fixes off = the code as it was before this round of
work"* and is what `bench` uses to baseline. I added `EXACT_RADIUS = false` to it. That toggle is
not an optimisation — it is the 508-block fix — so a baseline run now silently produces
out-of-radius results mixed in with the timing numbers. `radiusDiag` toggles it deliberately and is
fine; `bench` is the problem.

---

## B. Diagnostics testing things that no longer exist

### B1. [FIXED] `repro` BUG 4 asserts a cap that changed two releases ago
`ReproDiagnostic.java` — `manyBiomes()` hardcodes `if (c.biomes.size() >= 3)` and prints
**"asked for 7, kept 3"**. `WishParser.MAX_BIOMES` has been **6** since 1.39.0.

This is the dangerous shape, not just a stale number: the output *reads* as a report of shipped
behaviour and is actually a report of the diagnostic's own hardcoded constant. Anyone reading the
regression log sees "kept 3" and concludes the cap is 3. It is the same class of failure as the two
we already caught — a checking tool that agrees with itself.

### B2. [FIXED] `bench`'s baseline is no longer a baseline
Consequence of A4; listing separately because the fix is different (exclude `EXACT_RADIUS` from
`allOff`, or split correctness toggles from optimisation toggles).

---

## C. Dead code

| # | What | Notes |
|---|---|---|
| C1 | `FableVisionClient.queueScreen(Screen)` | public, **zero callers**. The `/client` command assigns the `queuedScreen` field directly. |
| C2 | `Config.usageSummary()`, `getDailyCap()`, `getUsedToday()` | dead chain since the usage counter was removed from the panel in 1.39.1 ⚠ **mine**. `noteRequest()` is still called, so the counters still tick and still write to disk — they just have no reader. `geminiDailyCap` feeds only `getDailyCap()`. |
| C3 | `SeedIcons.hasBiomeIcon(String)` | **zero callers** — I added it in 1.39.1 alongside `hasIcon` and then had `iconDiag` use `biomeItem` directly ⚠ **mine**. |
| C4 | `FableVisionConfig.welcomeAnimShown` | round-trips through save/load, never read. Its comment says *"kept so old configs parse"* — which is wrong: `load()` guards every field with `json.has(...)`, so absent and unknown fields already parse fine. The justification is stale even if you keep the field. |
| C5 | `import net.minecraft.world.item.Items` in `SpawnWishScreen` | unused since icons moved to `SeedIcons`. |
| C6 | `LanBedrock.geyserInstalled()` | still correct, but no longer called from outside `LanBedrock` — `FableVisionScreen` stopped using it when "Open to LAN now" went ⚠ **mine**. Should be private. |
| C7 | `dimBiomeRadius(END, …)` and the `bt.dim == Dim.END ? 96` biome step (`SeedCriteria:707`) | unreachable since End biomes left the catalog in 1.39.1 ⚠ **mine**. The End branch at `SeedCriteria:1051` is live (End *structures* remain). |

---

## D. Claims not backed by measurement

### D1. [FIXED — and it was FALSE, not just unmeasured] "Bridge Bastion — the rarest of the four types"
`SeedCatalog.java:409` states this in prose, with **no `.rate()`** and no diagnostic anywhere that
measures bastion-type distribution. All four bastion rows carry `rarityPct = -1`.

This is precisely what the file's own header forbids: *"a guessed rarity is worse than a blank one:
it is the number a player uses to decide whether a search is worth starting"*. It is also the exact
mistake corrected in 1.37 for the butcher ("the rarest of the six by a clear margin" — the census
said 24%, middling). Either measure it or drop the clause.

Everything else checks out — I verified every `.rate()` against the sample sizes quoted in its own
note (armorer 30%, capsized 11/80 → 14%, lava vault 16/600 → 2%, TNT 41/600 → 6%, spawner 82/600 →
13%, secret room 80/600 → 13%, trail stables 19/40 → 48%, allay cage 23/40 → 57%, and the rest).
All consistent.

### D2. [FIXED] The row notes now have no reader at all ⚠ **mine**
Every catalog row carries a `note` — often several paragraphs of measured, careful explanation. The
only screen that displayed them was `SeedFinderScreen`, which I deleted in 1.40.0. They are now
compiled into every jar and shown nowhere. That is ~8 KB of the most carefully written prose in the
project, dead. (This is also item 5 of the friendliness list, which the deletion promoted from
"weak" to "strongest".)

---

## E. Stale comments and text

| # | Where | Problem |
|---|---|---|
| E1 | `AiScreenScreen.java:147` | comment example *"how do I use /build?"* — `/build` was deleted in 1.39.0, and `MOD_GUIDE` explicitly tells the AI to say so. |
| E2 | **[FIXED 1.41.2]** `AiVision` `MOD_GUIDE` | described the offset sliders without mentioning they now read **1–50**, while `AiSettings.GUIDE` (appended to the same prompt) said 1–50. The model was handed both. Fixed alongside E3 — same paragraph, one line. |
| E3 | **[FIXED 1.41.2]** `AiVision` `MOD_GUIDE` | never stated that the seed finder is Create-World-only and hard-disabled in multiplayer. Now carries the scope, the one-way seed→world direction, the absence of any observed-structure input or solver, and the full block conditions — interpolated from `SeedAccess.ONLY_ON_CREATE_WORLD` so it cannot drift from the block. |
| E4 | `SeedFinder.java:150` | refers to "the standalone screen", deleted in 1.40.0 ⚠ **mine**. |
| E5 | `SlimeChunks.PICKER_WITHIN` doc | quotes a table "measured by slimeDiag" — I re-ran it, the numbers match exactly. **No finding**, recorded because I checked. |

---

## F. Things I checked and found clean

Recording these so the next pass does not redo them:

- **All 23 gradle diagnostic tasks** point at classes that exist; every test class except the shared
  `Placed` helper has a task, and `Placed` is used by five diagnostics.
- **All six mixins** in `fablevision.client.mixins.json` exist and are registered.
- **All five accesswidener entries** are still used by live code.
- **The `MOD_GUIDE` downscale claim** ("downscaled before sending") is true — `ScreenCapture`
  caps the longest edge at 1280.
- **No `TODO` / `FIXME` / `XXX` / `HACK` markers** anywhere in the source.
- **`WishParser`'s glossary and impossible-list** are gated on the live catalog, so a removed row
  stops being taught rather than teaching a name the validator would reject.
- **End biomes** are gone from the picker, the AI's allowed list (55 ids, verified in `wishDiag`)
  and the icon table, while End *structures* remain — consistent everywhere.

---

## Still open after 1.41.1

**E2 and E3 were also fixed in 1.41.2** (the release build), which leaves:

C1–C7 (dead code) and E1 + E4 (two stale comments — a `/build` example in `AiScreenScreen` and a
reference to "the standalone screen" in `SeedFinder`). All cosmetic, none affect behaviour, none
visible to a player. Safe to batch whenever.

## Suggested order (for what remains)

1. **A1, A3, E4** — one-line comment corrections, zero risk, and A1 is the first thing a reader sees.
2. **B1** — the diagnostic actively reports a false number into the regression log.
3. **A4 / B2** — `bench` baselines are currently meaningless.
4. **D1** — delete four words, or measure it.
5. **A2** — real feature work (wire structure pairs into the picker, or say why it is refused
   truthfully).
6. **D2** — the biggest *value* item, and it is the friendliness list's item 5.
7. **C1–C7, E1–E3** — cleanup, safe to batch.
