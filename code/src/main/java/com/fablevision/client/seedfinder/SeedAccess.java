package com.fablevision.client.seedfinder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.HolderLookup;

/**
 * THE ONE GATE. Seed searching happens on the Create World screen or it does not happen.
 *
 * WHY THIS CLASS EXISTS, stated plainly because a reviewer should be able to settle the question
 * by reading one file:
 *
 * This mod generates NEW single-player worlds. You describe what you want, it tries candidate seeds
 * with the game's own world-gen code, and it types a winner into the Create World screen so you can
 * press Create. It is a world CHOOSER. It is not, and cannot be made into, a way to learn the seed
 * of a world somebody else is running — there is no input for an observed structure position, no
 * reverse solver, no network read, and nothing here ever looks at a world that already exists.
 *
 * "Cannot be made into" is the part this class is for. Being true in spirit is not enough, because
 * the next person to add a feature will not have read the spirit. So every entry point funnels
 * through {@link #allowed()}, and the conditions are deliberately far stricter than the threat:
 *
 *   - a world must NOT be loaded ({@code level == null}). Not "not a remote world" — no world at
 *     all, including your own single-player one. The Create World screen is only reachable from the
 *     title screen and the world list, where no level is loaded, so this costs nothing legitimate.
 *   - there must be NO live connection ({@code getConnection() == null}). Belt and braces: the
 *     integrated server also runs over a connection, so this alone would catch an in-world call.
 *   - there must be no server selected ({@code getCurrentServer() == null}).
 *   - registries must come from a {@link CreateWorldScreen} that the caller is holding. There is no
 *     longer any other way to get them — see {@link WorldgenContext#findSource}, which used to fall
 *     back to the running single-player server's registries and now returns null instead.
 *
 * FAIL CLOSED. Every condition is written so that "cannot tell" reads as "no": a null Minecraft
 * instance, a null screen, an exception, all return false. There is no state in which the answer is
 * "probably fine".
 *
 * The gate is also why there is no in-world seed-finder command or screen any more. Both were
 * removed in 1.40.0 rather than gated, because a feature that exists and is switched off is a
 * feature somebody can switch back on.
 */
public final class SeedAccess {

   /** The single sentence shown wherever the block is visible. Kept here so it cannot drift. */
   public static final String ONLY_ON_CREATE_WORLD =
         "The seed finder only runs on the Create World screen, for a NEW world you are about to "
         + "make. It cannot read the seed of a server or of a world that already exists.";

   /**
    * Said when the Create World screen is set to a world type this finder cannot answer for.
    *
    * THIS IS THE SAME FAILURE AS THE END CITY PHANTOM AND IT WAS NEVER CHECKED. Every seed the
    * finder tests is generated with the DEFAULT overworld and nether generators: the vanilla
    * multi-noise biome preset and the vanilla noise settings, built in WorldgenContext and hard-
    * wired there. The Create World screen's "World Type" button changes exactly those. Pick Large
    * Biomes, Amplified, Single Biome or Superflat, press Create, and the world that generates is
    * not the world the search examined — so every coordinate it reported is a coordinate about a
    * different world, which is to say a guess.
    *
    * Nothing warned. The seed was typed in and the search looked like it had worked.
    *
    * The fix is a refusal rather than support: making the finder follow the selected preset means
    * every measured rate in the catalog (30% of villages have an armorer, 1 mansion in 50 has the
    * lava vault, a big village is 133 pieces) is a number measured in one world type and quoted in
    * another. Those numbers are the whole basis on which a player decides whether a search is worth
    * waiting for. Refusing is honest; following the preset while quoting the old numbers is the
    * overselling this project keeps removing.
    */
   public static final String ONLY_DEFAULT_WORLD_TYPE =
         "Set World Type back to Default — the finder only checks that one. Large Biomes, "
         + "Amplified and the rest generate a different world.";

   /**
    * Is the screen's World Type something this finder can honestly search?
    *
    * Returns the refusal to show, or null when the type is the default one. Reads the screen's own
    * chosen preset rather than any copy of it; an unrecognisable answer is treated as a refusal,
    * for the same reason everything else here fails closed — "cannot tell" must never read as
    * "probably fine" about a promise made in coordinates.
    */
   public static String worldTypeProblem(CreateWorldScreen screen) {
      if (screen == null) {
         return ONLY_ON_CREATE_WORLD;
      }
      try {
         var entry = screen.getUiState().getWorldType();
         if (entry == null || entry.preset() == null) {
            return ONLY_DEFAULT_WORLD_TYPE;
         }
         boolean normal = entry.preset().unwrapKey()
               .map(k -> k.equals(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL))
               .orElse(false);
         return normal ? null : ONLY_DEFAULT_WORLD_TYPE;
      } catch (Throwable cannotTell) {
         return ONLY_DEFAULT_WORLD_TYPE;
      }
   }

   /**
    * True only when we are at the Create World screen with no world and no connection.
    *
    * {@code screen} is the Create World screen the caller is attached to. Passing null is treated
    * as "no screen", which is a refusal — a caller that does not have one has no business here.
    */
   public static boolean allowed(CreateWorldScreen screen) {
      return screen != null && allowed();
   }

   /**
    * The context half of the check, without the screen.
    *
    * Separate so that background work already in flight (a running search, the catalog builder) can
    * re-check the context on its own and stop the moment a world starts loading, which is the one
    * window where a search could otherwise outlive the screen that started it.
    */
   public static boolean allowed() {
      try {
         Minecraft mc = Minecraft.getInstance();
         if (mc == null) {
            return false;
         }
         // A loaded world of ANY kind — yours, a friend's, a server's — is a refusal. This is much
         // stronger than "not multiplayer" and it is deliberate: it means the searching code cannot
         // observe a live world even in the case where doing so would be harmless.
         if (mc.level != null) {
            return false;
         }
         // The integrated server talks over a connection too, so this catches an in-world call even
         // if a future refactor stops clearing mc.level somewhere.
         if (mc.getConnection() != null) {
            return false;
         }
         if (mc.getCurrentServer() != null) {
            return false;
         }
         return !mc.hasSingleplayerServer();
      } catch (Throwable cannotTell) {
         // Cannot tell => no. See the class note on failing closed.
         return false;
      }
   }

   /**
    * The registries for a search, or null when this is not a place a search may happen.
    *
    * The ONLY source is the Create World screen's own load context — the data packs chosen for the
    * world that is about to be made. Nothing else in the codebase may hand registries to the finder.
    */
   public static HolderLookup.Provider sourceFor(CreateWorldScreen screen) {
      if (!allowed(screen)) {
         return null;
      }
      try {
         return screen.getUiState().getSettings().worldgenLoadContext();
      } catch (Throwable cannotTell) {
         return null;
      }
   }

   // ── The second door: LOOKING at your own single-player world ─────────────────────────────────

   /**
    * What the in-world map needs, or the reason it cannot open.
    *
    * Exactly one of {@code problem} and {@code registries} is null.
    */
   public record OwnWorld(HolderLookup.Provider registries, long seed,
                          net.minecraft.core.BlockPos spawn, String problem) {}

   /** Shown when the in-world map is opened anywhere but a single-player world you are hosting. */
   public static final String MAP_OWN_WORLD_ONLY =
         "The map only opens in your own single-player world. It never works on a server or a friend's LAN world.";

   /** Shown when this world's generator is not the default one the map knows how to draw. */
   public static final String MAP_DEFAULT_WORLD_ONLY =
         "This world isn't the Default world type, so the map would draw a different world. It only shows Default worlds.";

   /**
    * THE ONLY OTHER PLACE A LOADED WORLD'S SEED IS READ, AND IT IS FOR VIEWING, NEVER SEARCHING.
    *
    * Added in 1.43.0 for the in-world map. Everything above still holds for SEARCHING: {@link #allowed()}
    * refuses whenever any world is loaded, so no search, catalog or seed candidate can run in here.
    * This method hands out what a MAP needs — the registries and seed of the world you are standing in
    * — and it hands them out only when that world is YOURS:
    *
    *   - a world is loaded and you are its host ({@code isLocalServer}, an integrated server exists);
    *   - no remote server is selected ({@code getCurrentServer() == null}) — joining a friend's LAN
    *     world or any server is a remote connection and is refused;
    *   - the overworld generator is the default noise generator with the default biome preset, so the
    *     map draws the world that actually exists (the same rule the finder applies to World Type).
    *
    * The seed read here is the one vanilla's own /seed and F3 already show in single-player. Nothing
    * reached through this method can start a search: the map screen has no search controls and
    * {@link SeedFinder} still asks {@link #allowed()}, which says no whenever a world is loaded.
    *
    * Must be called on the client thread. Fails closed like everything else in this class.
    */
   public static OwnWorld ownWorldForViewing() {
      try {
         Minecraft mc = Minecraft.getInstance();
         if (mc == null || mc.level == null || mc.player == null) {
            return new OwnWorld(null, 0, null, MAP_OWN_WORLD_ONLY);
         }
         if (!mc.isLocalServer() || !mc.hasSingleplayerServer() || mc.getCurrentServer() != null) {
            return new OwnWorld(null, 0, null, MAP_OWN_WORLD_ONLY);
         }
         var server = mc.getSingleplayerServer();
         if (server == null) {
            return new OwnWorld(null, 0, null, MAP_OWN_WORLD_ONLY);
         }
         var overworld = server.overworld();
         var generator = overworld.getChunkSource().getGenerator();
         boolean defaultType = generator instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator noise
               && noise.stable(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD)
               && noise.getBiomeSource() instanceof net.minecraft.world.level.biome.MultiNoiseBiomeSource biomes
               && biomes.stable(net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists.OVERWORLD);
         if (!defaultType) {
            return new OwnWorld(null, 0, null, MAP_DEFAULT_WORLD_ONLY);
         }
         return new OwnWorld(server.registryAccess(), overworld.getSeed(),
               overworld.getRespawnData().pos(), null);
      } catch (Throwable cannotTell) {
         return new OwnWorld(null, 0, null, MAP_OWN_WORLD_ONLY);
      }
   }

   /**
    * Registries for LOOKING UP a typed seed on the Create World screen — the same source and the same
    * conditions as a search, plus the World Type rule. Returns the refusal, or null when it may open.
    */
   public static String lookupProblem(CreateWorldScreen screen) {
      if (sourceFor(screen) == null) {
         return ONLY_ON_CREATE_WORLD;
      }
      return worldTypeProblem(screen);
   }

   private SeedAccess() {
   }
}
