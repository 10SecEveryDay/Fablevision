FABLEVISION - seed finder source
================================

WHAT THE SEED FINDER IS (read this first if you are reviewing the mod)
    It picks a seed for a NEW world you are about to create, on the Create
    World screen, and nowhere else. You describe a spawn; it generates
    candidate seeds with the game's own world-gen until one matches; it types
    that seed into the world settings. You then press Create and play a
    completely normal world.

    It CANNOT read the seed of a server or of any world that already exists.
    There is no input for an observed structure, no reverse solver, and every
    seed-finding feature is hard-disabled unless no world is loaded and no
    connection is open. The block is one file - SeedAccess.java, under
    code/src/main/java/com/fablevision/client/seedfinder/ - and it fails closed.

    Full detail, with the file for every claim: code/SEED-FINDER-SCOPE.md

code\
    THE SEED FINDER MOD ("FableVision"), for Minecraft 26.1.2 on Fabric.
    Version 1.44.7. Since 1.43.0 it is ONLY the seed finder and its maps.
    The real number is always mod_version in code\gradle.properties.

    Build:   cd code  then  gradlew build
    Output:  code\build\libs\fablevision-<version>.jar - drop it in your
             mods folder.

    com.fablevision.api.SeedSearchApi is the one class other mods may call.
    Everything else under code\ is internal and can change without warning.

NOT IN THIS REPOSITORY
    "FableVision Extras" and "FableVision Manhunt Pregen" are separate mods
    that depend on FableVision. Their source is not published here.
    Built jars are not committed to this repository.
