# What the seed finder does, and what it cannot do

**Short version: FableVision picks a seed for a NEW world you are about to create. It cannot tell
you the seed of a server, or of any world that already exists.**

> **Since 1.43.0** FableVision is two mods. **FableVision** is only the seed finder and its map.
> **FableVision Extras** has the PvP/vision settings, the food HUD, the G-key AI panel and LAN
> hosting, and has no seed or world-gen code of its own. Everything in this file is about
> FableVision. 1.43.0 also added two ways to LOOK at a seed — both described below, both without any
> search.

This file exists because the mod was once rejected for "assisting seed cracking". That was a
misreading, but the fix is not to argue — it is to make the misreading impossible. Everything below
is enforced in code, not by convention, and each claim names the file you can check it in.

---

## What it actually is

You click **Create New World → ✨ Custom spawn**. You describe what you want ("a village next to a
cherry grove") or tick rows from a list. The mod then:

1. Picks a candidate seed from a **random number generator**.
2. Runs the game's own world-gen code on that seed to see what it would contain.
3. Throws it away and tries another if it does not match.
4. When one matches, types that seed into the Create World screen.

You press Create and play a completely normal world. Nothing is placed, moved, or teleported.

It is a **world chooser**. The thing that makes seed cracking seed cracking — starting from
something you observed in a world you do not own and working backwards to the seed — has no input,
no code path, and nowhere to happen.

## Seed cracking would need these. None exist.

| A seed cracker needs | FableVision has |
|---|---|
| A way to enter an observed structure position | Nothing. Seeds come from `ThreadLocalRandom.nextLong()` or a counter. |
| A reverse solver (lattice/brute-force over observations) | Nothing. The only direction is seed → world. |
| The ability to run while connected to a server | Blocked. See below. |
| The ability to read a loaded world | Blocked. See below. |

## The block, in one file

`SeedAccess.java` is the only door. Every entry point goes through it, and it fails **closed** —
anything it cannot determine is treated as "no".

A seed search may run only when **all** of these hold:

- **no world is loaded** (`mc.level == null`) — not "not multiplayer": *no world at all*, including
  your own single-player one;
- **no connection is open** (`mc.getConnection() == null`) — the integrated server also runs over a
  connection, so this alone catches an in-world call;
- **no server is selected** (`mc.getCurrentServer() == null`);
- **no integrated server exists** (`!mc.hasSingleplayerServer()`);
- registries come from a **`CreateWorldScreen`** the caller is holding.

It is checked in three places, deliberately redundantly:

- before the **✨ Custom spawn** button is added to the Create World screen (`FableVisionClient`);
- when the wish screen asks for registries (`WorldgenContext.findSource` → `SeedAccess.sourceFor`);
- before any worker thread starts, **and again every 4096 seeds while they run** (`SeedFinder`), so
  a search cannot survive a world starting to load.

## Looking is not searching: the two maps added in 1.43.0

Both use the same map screen (`SeedMapScreen`) and the same map data (`SeedMapData`). Neither has a
search control, and neither can start one: `SeedFinder` still asks `SeedAccess.allowed()`, which says
no whenever any world is loaded.

| Map | Where | What it reads | Refused when |
|---|---|---|---|
| **Seed map** | Create World screen | the seed **the player types**, with that screen's registries (`SeedAccess.sourceFor`) | the same conditions as a search, plus a non-Default World Type |
| **Map of this world** (N key) | inside a world | the registries, seed and saved spawn of **the single-player world you are hosting** (`SeedAccess.ownWorldForViewing`) | any remote server, any LAN world you joined, any non-Default generator |

The seed map takes a number the player already has — there is still no input for an observed
structure and no reverse solver. The in-world map reads your own world's seed, the same number
vanilla's `/seed` and F3 show in single-player; `ownWorldForViewing` refuses unless an integrated
server exists, the connection is local, and no remote server is selected.

**Since 1.43.1** both maps have an **End tab** (biomes and End Cities). It is view only, under the
same doors as above. The search still cannot name the End: `SeedCriteria.Dim` has no End value, and
the tab uses the map's own `SeedMapData.MapDim`. Every structure either map draws, in any dimension,
passes the game's own generation check first (`SeedMapData.verify`), and `gradlew mapTruthDiag`
compares the map against the game's structure step on real chunks.

## Removed rather than gated

Things that exist and are switched off are things somebody can switch back on.

- **`/seedfind` command** — gone. There is no seed-related command of any kind. FableVision itself
  registers no commands; FableVision Extras registers `/client`, `/client on|off`, `/client lan` and
  `/client lan address`, all client-side settings.
- **`SeedFinderScreen`** — the standalone in-world seed-finder GUI. **Deleted** in 1.40.0, not
  hidden. Its catalog half (which the Create World screen needs) moved to `SeedCatalogCache`.
- **In-world registry fallback** — `WorldgenContext.findSource` used to fall back to the running
  single-player server's registries when it had no screen. That single line was what made the
  finder able to work inside a loaded world. Removed.
- **Seed-result autosave** — the mod used to write finished searches to
  `config/fablevision/seedfinder.json` for the deleted command to read back. Removed; nothing
  writes seeds or coordinates to disk.

## The places a loaded world's seed is read, and why

**1. The in-world map** — see the table above. View only, your own single-player world only.

**2. Confirming a found world**

`FableVisionClient.maybeAnnounceFoundSeed` calls `getSingleplayerServer().overworld().getSeed()`.

That is **your own integrated server**, for a world **you just created**, and the value is used for
exactly one thing: comparing it against the seed the search produced, so the mod knows whether the
world now loading is the one it found (and should print the summary) or a different save you opened
instead. It is the same number vanilla's own `/seed` prints in single-player. It never leaves the
method, is never displayed, and is unreachable in multiplayer — the method returns immediately if
`isLocalServer()` is false.

**3. Getting the in-world map ready (since 1.44.3)**

When you join a world, `FableVisionClient` asks `SeedAccess.ownWorldForViewing()` — the same door
as the in-world map, with the same refusals (any remote server, any LAN world you joined, any
non-Default generator) — and, only if it answers, hands that world's registries and seed to
`SeedMapScreen.warm`. That builds the world-gen parts the map would build on its first open, so
pressing N the first time is not slow. Nothing is drawn, shown, stored or sent; it is the in-world
map's own read, done a moment earlier, for your own single-player world only.

## Multiplayer

Every seed-finding feature, and both maps, are unreachable on a server or on someone else's LAN
world, by the conditions above. The features that *do* work in multiplayer are all in FableVision
Extras and have nothing to do with world generation: the vision toggles (low fire, low shield,
fullbright, no explosion particles), the food HUD, and the "ask AI about my screen" panel — which
sends a screenshot only when you click Capture and confirm, to an AI provider whose key you supply,
and has no seed or world-data function at all.

`/client lan` and Auto LAN were removed in Extras 1.0.1. What is left is a tip only: after you open
your own world with vanilla's Esc → Open to LAN, a chat line says how Bedrock friends join through
Geyser (or that Geyser isn't loaded). It prints no address, opens nothing, and reads no world data.

## Not an optimisation mod

FableVision makes no performance claims and carries no Optimization tag. Sodium and Lithium cover
that ground properly.
