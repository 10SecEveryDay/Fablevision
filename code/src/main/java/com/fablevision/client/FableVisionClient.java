package com.fablevision.client;

import com.fablevision.client.seedfinder.JoinForm;
import com.fablevision.client.seedfinder.SeedAccess;
import com.fablevision.client.seedfinder.SeedCriteria;
import com.fablevision.client.seedfinder.SeedCriteria.Find;
import com.fablevision.client.seedfinder.SeedMapScreen;
import com.fablevision.client.seedfinder.WishParser;
import com.fablevision.client.seedfinder.SeedCatalogCache;
import com.fablevision.client.seedfinder.SpawnWishScreen;
import java.lang.ref.WeakReference;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FableVisionClient implements ClientModInitializer {
   public static final String MOD_ID = "fablevision";
   public static final Logger LOGGER = LoggerFactory.getLogger("fablevision");
   private static String lastWorldKey;
   /** The /locate warning has been printed once this session; later joins leave it out. */
   private static boolean locateTipShown;
   private static WeakReference<Screen> askedCreateScreen = new WeakReference<>(null);

   /**
    * The "FableVision" section in Controls. Registered here, in the core mod, because a category can
    * only be registered once and both jars put keys in it — Extras uses this one for G.
    */
   public static final net.minecraft.client.KeyMapping.Category KEY_CATEGORY = net.minecraft.client.KeyMapping.Category.register(
         net.minecraft.resources.Identifier.fromNamespaceAndPath("fablevision", "fablevision"));

   /** Our bolted-on Create World buttons, tracked per screen so a re-init (window resize /
    *  maximize can re-fire init on the SAME screen without clearing widgets) REPLACES them
    *  instead of stacking a duplicate at the old width's coordinates. */
   private record SpawnButtons(Button spawn, Button badge, Button stats, Button lookup) {}

   /** Opens the map of the single-player world you are standing in. View only — see SeedAccess. */
   private static net.minecraft.client.KeyMapping mapKey;
   private static final java.util.Map<Screen, SpawnButtons> SPAWN_BUTTONS =
         java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

   @Override
   public void onInitializeClient() {
      FableVisionConfig.load();
      com.tensec.aiscreen.Config.load();
      mapKey = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(new net.minecraft.client.KeyMapping(
            "key.fablevision.map", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
            org.lwjgl.glfw.GLFW.GLFW_KEY_N, KEY_CATEGORY));
      // NO COMMANDS IN THIS JAR. Since 1.43.0 the core mod is only the seed finder, which lives on the
      // Create World screen; /client and everything it configures moved to FableVision Extras. There
      // is no seed-related command in either jar, and there must not be one: an in-world seed-search
      // command is the single thing this project could offer that would look like server cheating.
      // Before you create a singleplayer world: offer a custom spawn (structure/biome AT
      // spawn via a legit found seed). "No thanks" and a Don't-ask toggle are right there.
      ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
         // SeedAccess.allowed() as well as "is this the Create World screen", and the redundancy is
         // the point. The Create World screen cannot be opened from inside a world, so in practice
         // the second test never fires — which is exactly why it is cheap to keep as a guarantee
         // rather than an argument. If some future version ever makes that screen reachable from a
         // loaded world, the button does not appear and nothing else has to be remembered.
         if (screen instanceof CreateWorldScreen createScreen && SeedAccess.allowed(createScreen)) {
            // Resizes are messy here: some paths reposition the layout WITHOUT re-init (our
            // widgets keep stale coordinates and "drift" to the top-middle), others re-fire
            // init on the SAME screen instance WITHOUT clearing bolted-on widgets (a
            // DUPLICATE appears). Cure both: remove whatever we added before, add fresh,
            // and let one per-screen tick handler re-anchor the current set to live width.
            // The map's slow first-time parts, loaded now so the first map opened from here is quick.
            if (SeedAccess.lookupProblem(createScreen) == null) {
               SeedMapScreen.warm(SeedAccess.sourceFor(createScreen), null);
            }
            SpawnButtons prev = SPAWN_BUTTONS.get(screen);
            if (prev != null) {
               Screens.getWidgets(screen).remove(prev.spawn());
               if (prev.badge() != null) {
                  Screens.getWidgets(screen).remove(prev.badge());
               }
               if (prev.stats() != null) {
                  Screens.getWidgets(screen).remove(prev.stats());
               }
               if (prev.lookup() != null) {
                  Screens.getWidgets(screen).remove(prev.lookup());
               }
            }
            // SEED LOOKUP: look at a seed you already have. No search, no reverse anything — the player
            // types the number. Same registries and the same World Type rule as a search.
            Button lookupBtn = Button.builder(Component.literal("🗺 Seed map"),
                  b -> client.setScreen(SeedMapScreen.lookup(createScreen)))
                  .bounds(scaledWidth - 108, 28, 102, 20).build();
            Screens.getWidgets(screen).add(lookupBtn);
            Button spawnBtn = Button.builder(Component.literal("✨ Custom spawn"),
                  b -> client.setScreen(new SpawnWishScreen(createScreen)))
                  .bounds(scaledWidth - 108, 6, 102, 20).build();
            Screens.getWidgets(screen).add(spawnBtn);
            // Little green badge once a found seed is sitting in the world settings.
            Button badge = null;
            Button stats = null;
            String applied = SpawnWishScreen.lastAppliedSeed;
            if (applied != null && applied.equals(createScreen.getUiState().getSeed())) {
               // THE BADGE OPENS THE MAP NOW. It was an inactive button that said a thing had
               // happened and did nothing — which is the obvious place to put "show me what you
               // found", because it is already the thing on screen that refers to the result.
               //
               // The CreateWorldScreen goes in rather than a bare parent: since 1.41.3 the map also
               // draws the biomes and the other structures around the spawn, and the registries
               // that takes come from this screen and nowhere else (see SeedAccess).
               badge = Button.builder(Component.literal("§a✓ Spawn map"),
                     b -> client.setScreen(new SeedMapScreen(createScreen,
                           SpawnWishScreen.lastAppliedFinds, applied)))
                     // SHORT ENOUGH TO FIT. "✓ custom spawn — map" was cut to "custom spawn — m…" in a
                     // 102-pixel button; the longer sentence lives in the tooltip instead.
                     .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                           "Your custom spawn seed is in the world settings. Click to see it on the map.")))
                     .bounds(scaledWidth - 108, 50, 102, 20).build();
               // Only clickable when there is something to draw. A result from a path that could
               // not produce structured finds still gets its badge; it just stays inert rather
               // than opening an empty map.
               badge.active = !SpawnWishScreen.lastAppliedFinds.isEmpty();
               Screens.getWidgets(screen).add(badge);
               if (!SpawnWishScreen.lastAppliedShort.isEmpty()) {
                  stats = Button.builder(Component.literal("§7" + SpawnWishScreen.lastAppliedShort), b -> {})
                        .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                              Component.literal(SpawnWishScreen.lastAppliedShort)))
                        .bounds(scaledWidth - 158, 72, 152, 20).build();
                  stats.active = false;
                  Screens.getWidgets(screen).add(stats);
               }
            }
            SPAWN_BUTTONS.put(screen, new SpawnButtons(spawnBtn, badge, stats, lookupBtn));
            if (prev == null) {
               // Register the re-anchor ONCE per screen; it always reads the CURRENT set.
               ScreenEvents.afterTick(screen).register(s -> {
                  SpawnButtons cur = SPAWN_BUTTONS.get(s);
                  if (cur == null) {
                     return;
                  }
                  cur.spawn().setX(s.width - 108);
                  cur.spawn().setY(6);
                  if (cur.lookup() != null) {
                     cur.lookup().setX(s.width - 108);
                     cur.lookup().setY(28);
                  }
                  if (cur.badge() != null) {
                     cur.badge().setX(s.width - 108);
                     cur.badge().setY(50);
                  }
                  if (cur.stats() != null) {
                     cur.stats().setX(s.width - 158);
                     cur.stats().setY(72);
                  }
               });
            }
            boolean suppressed = SpawnWishScreen.consumeSuppress();
            if (FableVisionConfig.askCustomSpawn && !suppressed && askedCreateScreen.get() != createScreen) {
               askedCreateScreen = new WeakReference<>(createScreen);
               client.execute(() -> {
                  if (client.screen == createScreen) {
                     client.setScreen(new SpawnWishScreen(createScreen));
                  }
               });
            }
         }
      });
      ClientTickEvents.END_CLIENT_TICK.register((EndTick)client -> {
         // SEED-RESULT AUTOSAVE REMOVED IN 1.40.0. It wrote every finished search to
         // config/fablevision/seedfinder.json for the /seedfind command's "last" subcommand to read
         // back. The command is gone, so the file had no reader — and a mod that quietly keeps a
         // file of seeds and structure coordinates on disk is a bad thing to have to explain.
         //
         // The catalog is dropped as soon as a world starts loading, so world-gen data built for
         // one Create World screen can never be sitting around inside a different world.
         if (client.level != null) {
            SeedCatalogCache.forget();
         }
         while (mapKey.consumeClick()) {
            if (client.screen != null || client.level == null) {
               continue;
            }
            // IN-WORLD MAP: your own single-player world only, view only. The refusal is said on the
            // action bar rather than silently ignored, so pressing the key on a server is not a mystery.
            SeedAccess.OwnWorld own = SeedAccess.ownWorldForViewing();
            if (own.problem() != null) {
               if (client.player != null) {
                  client.player.sendOverlayMessage(Component.literal("§7" + own.problem()));
               }
            } else {
               client.setScreen(SeedMapScreen.inWorld(own));
            }
         }
         if (client.level == null) {
            lastWorldKey = null;
         } else {
            String key = currentWorldKey(client);
            if (key != null && !key.equals(lastWorldKey)) {
               lastWorldKey = key;
               maybeAnnounceFoundSeed(client);
               // Your own single-player world only (the same door the N-key map uses), so the first
               // press of N does not pay for building the world-gen parts.
               SeedAccess.OwnWorld own = SeedAccess.ownWorldForViewing();
               if (own.problem() == null) {
                  SeedMapScreen.warm(own.registries(), own.seed());
               }
            }
         }
      });
      LOGGER.info("FableVision seed finder loaded - it is on the Create World screen");
   }

   /**
    * First join of a world made from a found seed: say what was found, SHORTLY.
    *
    * The long form was four lines of explanation before the first coordinate, and most of it was
    * about the search rather than about the world — where distances were measured from, why not to
    * use /locate, a "— checked" tag on every line confirming a check that had already passed. All
    * true, all read once, and after that it was a wall of text between the player and the game.
    *
    * So the default is three or four short lines, and the long form is kept for the results where
    * it CARRIES something: a find in another dimension (where "80, 16" means nothing without the
    * portal or platform it is measured from), a Fast-mode search whose two distances really do
    * differ, or a note saying something was dropped, refused or reinterpreted. Everything the long
    * form said is still said — just only when saying it changes what the player would do.
    */
   private static void maybeAnnounceFoundSeed(Minecraft client) {
      if (client.player == null || !client.isLocalServer() || client.getSingleplayerServer() == null) {
         return;
      }
      // Reads the seed of the world WE JUST MADE, on our own integrated server, purely to confirm
      // that the world now loading is the one the search produced rather than some other save the
      // player opened instead. It is the same value vanilla's own /seed prints in single-player, it
      // never leaves this method, and there is no path to it from a multiplayer session.
      String worldSeed = Long.toString(client.getSingleplayerServer().overworld().getSeed());
      if (!SpawnWishScreen.consumeAnnounce(worldSeed)) {
         return;
      }
      boolean spoilers = FableVisionConfig.seedSpoilers;
      List<Find> finds = SpawnWishScreen.lastAppliedFinds;
      String note = SpawnWishScreen.lastAppliedNote;

      // THE RULE LIVES IN JoinForm, with the three agreed cases written down and joinDiag holding them.
      if (JoinForm.needsLongForm(finds, note)) {
         announceLong(client, spoilers, note);
         return;
      }
      announceShort(client, spoilers, finds, note);
   }

   /**
    * Three or four lines: what, where, how far, and the one warning that matters. Only Overworld finds
    * reach here — a find in another dimension takes the long form (see {@link JoinForm}).
    */
   private static void announceShort(Minecraft client, boolean spoilers, List<Find> finds, String note) {
      client.player.sendSystemMessage(Component.literal("§b[FableVision] §fCustom spawn §7- §f"
            + SpawnWishScreen.shortCount(SpawnWishScreen.lastAppliedChecked) + " §7seeds, §f"
            + SpawnWishScreen.lastAppliedTook));
      for (Find f : finds) {
         // ONE distance per line, and it is the one measured from where the player is standing.
         String line = "§7· §f" + f.label() + " §7- §f"
               + f.x() + ", " + f.z()
               + " §7(" + f.shown() + " blocks)"
               + (f.extra() == null || f.extra().isEmpty() ? "" : " §8" + f.extra());
         // The spoiler filter runs on the SHORT form too. It hides any pair of numbers, so the
         // coordinates go and the distance stays — which is the part that makes the line worth
         // reading at all when you have asked not to be told where things are.
         client.player.sendSystemMessage(Component.literal(
               spoilers ? line : SeedCriteria.withoutCoords(line)));
      }
      // NOTHING TO POINT AT (1.44.2): a slime-chunk or "no desert" search. What it verified is in the
      // match lines, after the first one, which is always the spawn line (SeedCriteria.test writes it
      // before anything else). Those lines ARE the short form for such a search.
      if (finds.isEmpty()) {
         List<String> lines = SpawnWishScreen.lastAppliedMatches;
         for (int i = 1; i < lines.size(); i++) {
            String line = "§7· §f" + lines.get(i);
            client.player.sendSystemMessage(Component.literal(spoilers ? line : SeedCriteria.withoutCoords(line)));
         }
      }
      // AN EXPLANATION NOTE IS ONE LINE HERE, not a reason for the paragraph. Only a note reporting
      // something DROPPED takes the long form (JoinForm.somethingDropped); an ℹ statement is simply said.
      for (String statement : JoinForm.statements(note)) {
         client.player.sendSystemMessage(Component.literal("§7· §e" + statement));
      }
      // KEPT EVEN IN THE SHORT FORM, whenever there are coordinates to go to. Every walk this project
      // has recorded as a "wrong prediction" was /locate being asked a different question.
      // ONCE PER SESSION (1.44.3): the same warning on every join was noise after the first.
      if (!finds.isEmpty() && !locateTipShown) {
         locateTipShown = true;
         client.player.sendSystemMessage(Component.literal("§7· §8" + SeedCriteria.LOCATE_TIP));
      }
      if (!spoilers) {
         client.player.sendSystemMessage(Component.literal(
               "§8   (" + JoinForm.coordsHiddenHint(mapKeyName()) + ")"));
      }
   }

   /** The full explanation, for the results where it says something. */
   private static void announceLong(Minecraft client, boolean spoilers, String note) {
      client.player.sendSystemMessage(Component.literal(
         "§b[FableVision] §a✓ This world came from your custom-spawn search — §f"
               + SpawnWishScreen.lastAppliedStats + "§a."));
      // The spoiler setting decides whether this message hands over the map. It still confirms
      // WHAT was found and how far off it is, so the player can tell the search worked; it just
      // stops naming the coordinates, which is the part that ends the game of finding it.
      for (String line : SpawnWishScreen.lastAppliedMatches) {
         if (line.equals(SeedCriteria.LOCATE_TIP)) {
            if (locateTipShown) {
               continue;
            }
            locateTipShown = true;
         }
         client.player.sendSystemMessage(Component.literal("§7   • "
               + (spoilers ? line : SeedCriteria.withoutCoords(line))));
      }
      if (!spoilers) {
         client.player.sendSystemMessage(Component.literal(
               "§8   (" + JoinForm.coordsHiddenHint(mapKeyName()) + ")"));
      }
      if (!note.isEmpty()) {
         // Some notes are EXPLANATIONS, not apologies, and printing those under "couldn't check"
         // turns a search that worked into one that sounds like it failed — which is exactly what
         // asking for a blacksmith used to look like. The stronghold sentence and anything carrying
         // the ℹ marker are statements aimed at the player and are printed as they stand.
         String plainMark = WishParser.PLAIN_NOTE;
         boolean plainNote = note.startsWith(WishParser.STRONGHOLD_CANT) || note.startsWith(plainMark);
         client.player.sendSystemMessage(Component.literal(
               plainNote ? "§e   " + note.replace(plainMark, "") : "§6   couldn't check: " + note));
      }
   }

   /** The map key as the player has it bound ("N" unless they changed it). */
   private static String mapKeyName() {
      try {
         return mapKey.getTranslatedKeyMessage().getString();
      } catch (Throwable unknown) {
         return "the map key";
      }
   }

   private static String currentWorldKey(Minecraft client) {
      if (client.isLocalServer()) {
         // The world's FOLDER, which is unique, not its display name, which is not: two worlds both
         // called "New World" live in "New World" and "New World (1)". (The name never caused a
         // visible bug — the key is cleared whenever a world unloads — but it named the wrong thing.)
         return client.getSingleplayerServer() != null
               ? client.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                     .toAbsolutePath().normalize().toString()
               : null;
      }

      ServerData server = client.getCurrentServer();
      return server != null ? server.ip : null;
   }

}
