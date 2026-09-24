# FableVision 1.44.2 + Extras 1.0.2 — review round 2, and an audit

Four reviewers read the decompiled 1.44.1 jar. Every claim was checked against the source before
anything changed.

## The claims

| # | claim | verdict |
|---|---|---|
| Gemini | crashes on startup: access widener uses `Identifier` not `ResourceLocation` | **False.** 26.1.2 has no `ResourceLocation` class at all — it was renamed `Identifier`, which is what the widener uses. The build also validates every widener line, so a wrong name could not produce a jar. |
| 1 | cave biomes searched at Y=64 | **True, and worse.** Deep Dark could never be found. Fixed — below. |
| 2 | closing the wish screen doesn't cancel the AI; a late reply wipes the seed box and starts a hidden search | **True**, and partly my regression: 1.43.1's retry lets a request run ~300 s, past the screen's 135 s give-up. Fixed with a request token. |
| 3 | template manager built lazily; failure sticks; storage never closed; temp folders leak | **True.** 271 leaked folders on this PC. Fixed. |
| 4 | JNA missing → keys stored as plain Base64 on Windows | **False in practice.** Minecraft 26.1.2 ships `jna` and `jna-platform` 5.17.0; privacyDiag does a real Windows encrypt/decrypt round trip. Mac/Linux are not claimed to be encrypted anywhere. |
| 5 | map cache key collision | **True in principle** (two slots ~1 million blocks apart). Fixed with an exact key. |
| 6 | KeySafe.save not atomic | **True.** Now temp file + move. |
| 7 | world key uses display name | **True, no visible effect** (the key resets whenever a world unloads). Now the folder, in both mods. |
| 8 | "two slime chunks" → 3; "as well" → Desert Well | **Both true.** Fixed. |
| 9 | Exact keeps a seed its re-check rejected, Fast doesn't | **True.** Both now reject it, and Exact logs it. |
| 10 | generation check fails open | **True, by design until now.** Now fails closed, and a search that can't check buildings says so instead of spinning. |
| 11 | catalog cleared every tick | **True, harmless** (four null writes). Now returns without work. |
| 12 | worker threads | cores−1 at lowest priority (pregen: normal). "Priority 10" is false. There was **no upper limit** — now capped at 12. |
| 13 | Stop only flips a flag | **True, and correct.** Workers check it between seeds; world-gen never checks interrupts, so interrupting would not stop anything sooner. Documented on `stop()`. |
| 14 | AI package shouldn't be in core | **Not a bug.** The core's own "Ask AI" for wishes uses it; Extras reuses it; it ships once; config loads once. Only the jar description's "LAN features" was stale. |

## The join message — reproduced and fixed

`joinRepro` (new) runs real Fast searches through the same pipeline the screen does. It found
2 of 14 plain searches still getting the long message:

- **Slime-chunk and "no X" searches** have nothing to point at, and "no finds" counted as a reason for
  the long form. Now they get the short form, listing what was verified. Their long form was also
  telling people to "go to the coordinates above" when there were none — removed.
- **Unmarked explanation notes** counted as "something dropped": the picker's slime note and the parser's
  "N blocks apart is tighter than most seeds manage". Marked as explanations.
- **Combined notes** ("a; b") were judged by how the whole string started, so an explanation could hide a
  drop and vice versa. Each part is judged on its own now.

After: all 14 short. `joinDiag` gained four cases.

## Cave biomes

Measured with `caveDiag` (new), 200 seeds, within 200 blocks of spawn, asking the search's own question:

| | Y=64 | best single height | search before | search now |
|---|---|---|---|---|
| Deep Dark | 0% | 28% (Y=-48) | **0%** | **28%** |
| Lush Caves | 5% | 23% (Y=0) | 5% | **27%** |
| Dripstone Caves | 16% | 29% (Y=0) | 16% | **33%** |

Cave biomes are now searched at their measured heights, and the find line says
"underground, around Y −48". "No deep dark near spawn" used to reject nothing; it works now.
Pairs and patch sizes use the same heights.

**The picker's numbers for them were measured at Y=64 too.** Each biome row shows "N% of seeds" and
offers big/small from a measured table. Re-measured underground (surface biomes re-measured identically):

| | old | now |
|---|---|---|
| Deep Dark: seeds with one within 2000 | 5% | 100% |
| Deep Dark: small ≤ / big ≥ | 64 / 128 | 160 / 384 |
| Dripstone Caves | 98%, 160 / 288 | 100%, 192 / 480 |
| Lush Caves | 85%, 96 / 192 | 100%, 288 / 512 |

## Found in my own audit

1. **Five release checks could never fail.** `streamDiag`, `slimeDiag`, `repro`, `strictDiag` and
   `professionDiag` printed their own "BUG"/"WRONG" verdicts and exited 0. So every past "all checks
   pass" was partly vacuous. All five exit non-zero now.
2. **professionDiag's "IMPOSSIBLE HERE" was circular.** It was decided with the token being checked, so a
   token missing a biome's spelling (the snowy `weapon_smith` bug from 1.43.0) was reported as "this biome
   has no such building" and passed. It now has an independent spelling check. Its own comment also said
   the opposite of what the code did.
3. **The "which part is the rare one" scan crashed** on structure-to-structure pairs, so after 90 s the
   screen said "working out…" forever. Same bug the ETA had, fixed there in 1.43.1 and missed here.
4. The AI give-up comment claimed it outlasted the AI's own timeout — false since 1.43.1.
5. The blacksmith note's documentation was attached to the End note.
6. The in-game AI's guide didn't know the map has an End tab (Extras 1.0.2).
7. `releases\README.txt` named 1.42.0, didn't mention Extras, and described the old pregen
   ("/pregen start", "needs 1.36.0"). Rewritten; it no longer repeats version numbers.
   The top README said the release jars "were built from 1.31.0" — only the 26.1 beta was.

## Verification

**All 19 checks pass** (regression-1442.log), and this is the first run where all of them *could* fail:
layout, icons, stream, slime, repro, radius, nether, strict, feature, profession, smith, wish, privacy,
eta, map, mapTruth, joinDiag, and the new joinRepro and caveDiag. Their verdicts, not just exit codes:

- strict: 471 confirmed hits examined; every one vanilla rejects, the search rejects too.
- profession: 0 missing rows, 0 missed spellings (the new independent check).
- mapTruth: 0 phantoms in all dimensions; 37 bastions + 13 End Cities named by type, 0 wrong.
- joinRepro: all 14 real Fast searches get the short message.
- caveDiag: the search now matches or beats every cave biome's best single height.

After the BiomeShape re-measure, layout and wish were re-run: both pass.
Template temp folders: 271 → 0, and builds since leave none behind.

## Needs your test in game

1. A slime-chunk search and a "no desert" search: short join message.
2. Deep Dark search: finds one, with an underground Y.
3. Type a wish with a key, press Esc while it's thinking, type a seed in Create World: it stays.
4. Two worlds both named "New World": nothing odd on joining either.
