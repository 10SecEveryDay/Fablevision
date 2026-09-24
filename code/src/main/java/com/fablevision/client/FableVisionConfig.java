package com.fablevision.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The seed finder's settings: Exact or Fast, the Fast distance, whether to offer Custom spawn, and
 * whether a found seed's coordinates are shown.
 *
 * SINCE 1.43.0 ONLY THESE. The vision, HUD and LAN settings moved to the FableVision Extras jar and its
 * own config file. This file is still config/fablevision.json, and save() now UPDATES it rather than
 * replacing it, so the old extras values stay readable for Extras' one-time migration no matter which
 * mod happens to save first.
 */
public final class FableVisionConfig {
   /**
    * Worked out on first use, not at class-load.
    *
    * As a static final it asked FabricLoader for the config directory the moment anything read a
    * setting — so a headless diagnostic, which has no loader and no config directory, died on the
    * class initialiser before running a line of its own code. The path is only needed by load()
    * and save(), both of which only happen in a running game; the settings themselves are plain
    * defaults until then, which is exactly what a diagnostic wants.
    */
   private static Path file() {
      return FabricLoader.getInstance().getConfigDir().resolve("fablevision.json");
   }
   public static boolean askCustomSpawn = true; // offer the seed-finder before creating a world
   public static boolean seedFastMode = false; // seed finder: search from 0,0 (fast) vs real spawn
   /**
    * Whether the "this world came from your search" chat message may print COORDINATES.
    *
    * Default true, which is what it has always done. Turned off, the message still confirms what
    * the search found and how far away it is — so you know the seed is the one you asked for —
    * and simply does not say where. Finding it yourself is most of the point of a good seed, and
    * the old message handed that away before the player had taken a step.
    */
   public static boolean seedSpoilers = true;
   public static int seedFastDistance = 100; // fast mode only: how close to 0,0 (blocks)

   /** "Exact" (non-fast) means RIGHT where you'll spawn — 64 blocks from the real spawn
    *  point, close enough that it's in view the moment you load in (was 128, which could
    *  put the find a ~100-block walk away). Want distance instead? That's what Fast mode's
    *  distance picker is for. Neither mode ever moves your spawn. */
   public static final int SEED_EXACT_RADIUS = 64;
   /** Exact mode AIMS even tighter first: a hit with everything this close is taken
    *  instantly, while a looser (≤64) hit is parked as a backup and the search spends a
    *  few extra seconds hunting a truly-at-spawn seed before settling for the backup. */
   public static final int SEED_EXACT_TIGHT = 32;
   public static final int[] SEED_FAST_DISTANCES = {100, 200, 400, 800};

   public static int seedRadius() {
      return seedFastMode ? Math.max(48, seedFastDistance) : SEED_EXACT_RADIUS;
   }
   private FableVisionConfig() {
   }

   public static void load() {
      if (!Files.exists(file())) {
         save();
      } else {
         try {
            JsonObject json = JsonParser.parseString(Files.readString(file())).getAsJsonObject();

            if (json.has("askCustomSpawn")) {
               askCustomSpawn = json.get("askCustomSpawn").getAsBoolean();
            }

            if (json.has("seedSpoilers")) {
               seedSpoilers = json.get("seedSpoilers").getAsBoolean();
            }

            if (json.has("seedFastMode")) {
               seedFastMode = json.get("seedFastMode").getAsBoolean();
            }

            if (json.has("seedFastDistance")) {
               seedFastDistance = clampInt(json.get("seedFastDistance").getAsInt(), 48, 2000);
            }

         } catch (Exception e) {
            FableVisionClient.LOGGER.warn("Could not read {}, using defaults", file(), e);
         }
      }
   }

   public static void save() {
      JsonObject json = new JsonObject();
      try {
         if (Files.exists(file())) {
            json = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
         }
      } catch (Exception unreadable) {
         json = new JsonObject();
      }
      json.addProperty("askCustomSpawn", askCustomSpawn);
      json.addProperty("seedFastMode", seedFastMode);
      json.addProperty("seedSpoilers", seedSpoilers);
      json.addProperty("seedFastDistance", seedFastDistance);

      try {
         Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(json));
      } catch (Exception e) {
         FableVisionClient.LOGGER.warn("Could not save {}", file(), e);
      }
   }

   private static int clampInt(int value, int min, int max) {
      return Math.max(min, Math.min(max, value));
   }
}
