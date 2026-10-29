# FableVision

A client-side **Fabric** mod for **Minecraft 26.3** that picks a seed for a **new** world, so you can describe
what you want near spawn ("a village next to a cherry grove", "a mansion in a dark forest") and let it search.

**Current version: 1.47.3.** Needs Fabric Loader 0.19.5+, Fabric API 0.162.0+ and Java 25.

## What it does

On the **Create New World** screen, click **Custom spawn**. Describe what you want, or tick rows from a list.
FableVision then:

1. Picks candidate seeds from a random number generator.
2. Runs the game's own world-generation code on each seed to see what it would contain.
3. Throws a seed away if it doesn't match, and tries another.
4. Types the first matching seed into the Create World screen.

You press Create and play a completely normal world. Nothing is placed, moved or teleported.
It also has a **Seed map** (view a seed's structures and biomes), "find N worlds" (up to 10 matching seeds),
and a **Bots** setting for how many search threads to use. Plain wishes work with no AI and no key.

## What it does NOT do

It is **not** a seed cracker. It works only on the Create World screen, for a NEW world, Default world type.
It cannot read the seed of a server, a LAN world, or any world that already exists, and it has no input for
observed structures and no reverse solver. The one gate is `SeedAccess.java`, and it fails closed.
Full detail, with the file for every claim: [SEED-FINDER-SCOPE.md](SEED-FINDER-SCOPE.md).

## Source

The mod's source is in `src/`. Release notes for the current version: [SHIPPED-1.47.3.md](SHIPPED-1.47.3.md).

## Install

Download `fablevision-1.47.3.jar` from the **Releases** page and put it in your `mods` folder with Fabric API.
Use only one `fablevision-<version>.jar` at a time.

## Build

Run `./gradlew build` (needs JDK 25). The jar is written to `build/libs/`.

## Testing

The project has headless diagnostics (`./gradlew <name>Diag`), each with a control that must be able to fail.
Minecraft itself can't be launched in them, so changes are also play-tested by hand.

## License

MIT. See [LICENSE](LICENSE).
