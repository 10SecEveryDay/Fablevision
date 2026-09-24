# FableVision 1.43.0 + FableVision Extras 1.0.0 — what shipped

Two jars now. **FableVision 1.43.0** is only the seed finder and its maps. **FableVision Extras 1.0.0**
has everything else and needs FableVision installed.

---

## The weaponsmith report

"I picked weaponsmith and sometimes got the lava-and-chest house, sometimes the blast-furnace-and-
smooth-stone house."

**Checked three ways, from the game's own files (`smithDiag`):**

| Question | Answer |
|---|---|
| Can the weaponsmith search match a blast-furnace house? | **No.** No weaponsmith template in any village type has a blast furnace. The blast-furnace + smooth-stone house is `plains_armorer_house_1` and nothing else. |
| Does the second coordinate stand on the right house? | **Yes, 30 of 30** real searches (15 Fast, 15 Exact). Every time, the house covering that spot is a weaponsmith. |
| What is at the FIRST coordinate? | The middle of the village. In **3 of 30** the nearest house there was an armorer. |

**Two real bugs found, both fixed:**

1. **The AI was taught both answers.** The prompt's rule said "blacksmith = weaponsmith", and one of
   its worked examples, three paragraphs later, answered "village with a blacksmith" with **Armorer**.
   AI models copy examples, so a typed wish went to either house depending on the day. The example is
   fixed, the parser now corrects an Armorer answer to a plain "blacksmith" wish, and `wishDiag` fails
   if any example ever disagrees with the phrase book again (checked by putting the old example back:
   it fails).
2. **Snowy villages do have a weaponsmith.** Its file is spelled `snowy_weapon_smith_1`, so the search
   never saw it and the catalog said snowy villages have none. The row now matches both spellings.

**Also worth knowing:** not every weaponsmith house has lava. The plains, desert, snowy and one savanna
house do; the taiga ones and the other savanna one are grindstone-only. The row's note says so now.

**What to test in game:** walk to the *second* coordinate on the find line ("weaponsmith N blocks
further on, at X, Z"). That spot is the house. The first coordinate is the village middle.

---

## P1 — bugs

| # | Fix |
|---|---|
| 1 | AI cooldown is per key. Asking the G panel no longer puts Custom spawn on cooldown. |
| 2 | Screenshots: the frame is grabbed on the game thread, then shrunk and encoded on the game's background IO pool (what vanilla F2 does). |
| 3 | LAN address: every adapter is scored — the one the PC routes out of wins, Docker/WSL/VPN/Tailscale/VirtualBox-looking ones lose. Other addresses are printed under it. `/client lan address` lists them; `/client lan address 192.168.1.20` pins one; `auto` goes back. |
| 4 | Opening to LAN uses your current game mode, not the world's starting one. |
| 5 | The dead `"stronghold"` key is gone. The stronghold note is now also triggered by the player's own words. |
| 6 | A spread-out bundle ("good survival seed" → village 250, portal 350…) is **refused** in Exact mode with a message naming the spread and saying to switch to Fast. One stray distance is still searched at spawn, with a note — biomes now get that note too. |

## P2 — security

**Keys.** Options for a client mod, and the pick:

- **Windows Data Protection API — chosen.** Encrypts to your Windows account; the file is useless on any
  other PC or account. Uses JNA, which Minecraft already ships. Does not stop other programs running as
  you (nothing a mod does can).
- macOS Keychain / Linux Secret Service — right answer there, but can't be tested from this machine, so
  those systems get a separate file readable only by your account.
- Encrypting with a key stored next to it — fake security, not done. Passphrase each session — nobody
  would use it. Environment variables — don't work from launchers.

Keys moved out of `aiscreen.json` into `aiscreen-keys.dat` on first launch (only blanked after the new
copy is written). Every AI reply and every question sent is scrubbed of stored keys and provider-shaped
keys, so a pasted key never reaches the model, the chat, or `latest.log`. The key box is masked while
typing.

**Screenshots.** No "always allow". Every Capture asks: *"Send a screenshot? Only your next question gets
it, then it's dropped. It can show chat, coordinates and names."* Old "always" settings are reset.

`privacyDiag` checks all of this (including a real Windows encrypt/decrypt round trip).

## P3 — the split

| FableVision (core) | FableVision Extras |
|---|---|
| Custom spawn, AI wish reader, catalog, maps, AI client + key storage | /client settings, Low Fire/Shield, Fullbright, No Explosions, food HUD, G-key AI panel, LAN + Bedrock, world-list tools, welcome animation |
| no commands, no mixins | all 6 mixins, all commands |
| `config/fablevision.json` | `config/fablevision-extras.json` (copies old values on first run) |

Extras depends on FableVision because the G panel uses the core's AI client and key storage — one copy
of the key-handling code instead of two. The pregen mod still works unchanged.

## P4 — new

- **Seed map** (Create World screen, "🗺 Seed map"): type or paste a seed, see it. "Use this seed" puts it
  in the box. Same rules as a search, including World Type.
- **Map of this world** (N key, rebindable): your own single-player world only; refused on servers and on
  LAN worlds you joined, and on non-Default world types. Shows where you are.
- **Every map:** drag to pan, scroll to zoom (64 to 10,000 blocks out), hover for x/z, chunk and biome,
  slime-chunk toggle, biome edges, spawn. Coordinates on a *search result* still obey spoilers (toggle is
  on the map now, since the core has no /client).
- **No-key wishes:** names joined by next to / no / a number / big / small are read without AI. With a
  key, the AI is only asked when a word isn't understood. Without one, the understood part is searched
  and the ignored words are named. 13 wishes checked in `wishDiag`.
- **Search ETA:** "Expect about 1 in 40,000 seeds — about 15 s at this speed (rough)". Each part of the
  wish is tried on random seeds; building rows use the catalog's measured rate. `etaDiag`: the estimate
  was 1.0–3.5× of the real search on six wishes.

## What was harder than it looked (and what I did about it)

1. **"Real spawn."** The game settles spawn by generating real blocks in up to 11×11 chunks around the
   spawn point. Nothing short of generating chunks can reproduce that. The seed map shows the game's spawn
   *point* and says the real spawn is within about a chunk; the in-world map reads the exact saved spawn.
2. **In-world map vs. "nothing reads a loaded world."** That rule was published. It still holds for
   *searching*; the map is a second, documented door for viewing your own world (`SeedAccess.
   ownWorldForViewing`, `SEED-FINDER-SCOPE.md`). Worth a re-read before the next Modrinth submission.
3. **ETA.** Parts of a wish aren't independent (villages and plains go together), so a product of chances
   can be off several times. It says "about" and "(rough)".
4. **Chunkbase parity.** Pan/zoom forced a rewrite of the map screen; very zoomed-out maps skip
   mineshafts and buried treasure (a million slots, icon noise).

## Verification (full run, 18 Sept — regression-1430.log)

All 15 checks pass.

| check | result |
|---|---|
| layout, icons, streams, slime, repro, nether, strict, feature | pass, unchanged from 1.42 |
| radiusDiag | pass. Its one failure was a wrong assumption in the test, not the finder: in Fast mode the fix can now take the in-range bastion the old code skipped past (seed 2441, 86 blocks). Same result on the untouched 1.42.0 code. The test now counts that case instead of failing, and still fails on anything out of range. |
| professionDiag verify | pass. Weaponsmith now **38%** of villages (was listed 31%); **snowy 37%** (was 0%). Row rate updated. |
| smithDiag | 30/30 second coordinates on the weaponsmith house |
| wishDiag | pass, incl. 13 no-key wishes and example/phrase-book agreement |
| privacyDiag | pass, incl. a real Windows encrypt/decrypt round trip |
| etaDiag | estimate within 1.0–3.5× of the real search on 6 wishes |
| mapDiag | every structure on the map confirmed by the search |

The layout check caught one thing mid-run: the Exact-mode bundle refusal was too long for a small
window (8 lines where 4 fit). Shortened to name two items; re-run passes.

## Needs your test in game

1. Both jars together, and FableVision alone (Create World still works; no /client).
2. Weaponsmith: go to the second coordinate.
3. G panel then Custom spawn "Ask AI" straight after — no cooldown message.
4. Capture Screen asks every time. `config/aiscreen.json` has no keys after first launch.
5. `/client lan` on a PC with a VPN/WSL — the yellow address should be your Wi-Fi/Ethernet one.
6. Seed map on Create World; N in a single-player world; N on a server says it won't open.
7. Type "village next to a cherry grove" with no AI key.
