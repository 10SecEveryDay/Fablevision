package com.fablevision.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The seed finder's settings: Exact or Fast, the Fast distance, whether to offer Custom spawn, whether a
 * found seed's coordinates are shown, and (1.47.0) how many search threads.
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
   /**
    * "Ask me": whether Custom spawn opens by itself when the Create World screen opens. The ✨ Custom spawn
    * button is there either way.
    *
    * OFF FOR NEW INSTALLS SINCE 1.47.3 (it was on): someone who has not chosen it is not taken over by a
    * screen they did not ask for. Only the default changed. A saved setting is read as saved, and a settings
    * file that exists without one — or cannot be read — is an install from before, which keeps the old
    * default, on ({@link #ASK_BEFORE_1_47_3}). See load().
    */
   public static boolean askCustomSpawn = false;
   /** What "Ask me" defaulted to before 1.47.3, for an existing settings file that does not say. */
   static final boolean ASK_BEFORE_1_47_3 = true;
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
   /**
    * How many search threads ("bots") a search runs on (1.47.0). 0 = auto: the CPU's cores less one, at most
    * 12 — what every search did before there was a setting. A number is used as given, up to
    * SeedFinder.maxWorkers() (the logical processors, what fits in the game's memory, and never more than
    * 16, the most tested — since 1.47.3).
    */
   public static int seedThreads = 0;

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
      load(file());
   }

   /** The same, from {@code source}. Package-private so a diagnostic can load from a scratch folder. */
   static void load(Path source) {
      if (!Files.exists(source)) {
         save(source);   // a new install: written with the defaults, "Ask me" off among them
      } else {
         // AN EXISTING INSTALL. Every save since "Ask me" existed writes it, so a file without it is older
         // than that, and one that cannot be read is an install whose setting cannot be known: both keep the
         // default it had before 1.47.3, rather than turning it off for someone who never chose.
         askCustomSpawn = ASK_BEFORE_1_47_3;
         try {
            JsonObject json = JsonParser.parseString(Files.readString(source)).getAsJsonObject();

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

            if (json.has("seedThreads")) {
               // Kept as written (0 = auto); SeedFinder clamps it to what THIS machine allows when it starts.
               seedThreads = clampInt(json.get("seedThreads").getAsInt(), 0, 1024);
            }

         } catch (Exception e) {
            FableVisionClient.LOGGER.warn("Could not read {}, using defaults", source, e);
         }
      }
   }

   /**
    * Writes the settings — one writer at a time, and never half a file (1.47.3), the same way the AI settings
    * are saved (com.tensec.aiscreen.Config.save): to a temporary file, then moved over the real one.
    *
    * It used to write over config/fablevision.json in place, from whichever thread called it — and two
    * mods call it (FableVision Extras saves the spoiler setting here). A crash or a full disk mid-write
    * left a truncated file, and load() reads an unreadable file as "use the defaults": the player's
    * settings gone, Custom spawn's auto-open back to its default among them.
    */
   public static synchronized void save() {
      save(file());
   }

   /** The same, to {@code target}. Package-private so a diagnostic can save to a scratch folder. */
   static synchronized void save(Path target) {
      JsonObject json = new JsonObject();
      try {
         if (Files.exists(target)) {
            json = JsonParser.parseString(Files.readString(target)).getAsJsonObject();
         }
      } catch (Exception unreadable) {
         json = new JsonObject();
      }
      json.addProperty("askCustomSpawn", askCustomSpawn);
      json.addProperty("seedFastMode", seedFastMode);
      json.addProperty("seedSpoilers", seedSpoilers);
      json.addProperty("seedFastDistance", seedFastDistance);
      json.addProperty("seedThreads", seedThreads);

      try {
         Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
         Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(json));
         try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (Exception noAtomicMove) {
            // Some filesystems refuse ATOMIC_MOVE; a plain replace is still better than writing over the live file.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
         }
      } catch (Exception e) {
         FableVisionClient.LOGGER.warn("Could not save {}", target, e);
      }
   }

   private static int clampInt(int value, int min, int max) {
      return Math.max(min, Math.min(max, value));
   }
}
