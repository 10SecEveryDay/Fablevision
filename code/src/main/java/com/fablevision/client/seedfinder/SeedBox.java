package com.fablevision.client.seedfinder;

import com.fablevision.client.FableVisionClient;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;

/**
 * Puts a seed into the Create World screen's settings AND its visible seed box.
 *
 * WHY BOTH (1.44.3). {@code getUiState().setSeed} changes the seed the world is created with, and that
 * part always worked. But the World tab's seed box is filled once, when the tab is built, and nothing
 * updates it afterwards — so after "Use this seed" (or a search) the box went on showing the old text.
 * The seed was right and looked wrong, which is the same as being wrong to anyone reading the screen.
 *
 * Setting the box fires its responder, which sets the same seed on the state again — harmless. If the
 * tab can't be reached (a mod replaced the screen's tabs), the state is still set, which is what the
 * world is created from.
 */
public final class SeedBox {

   public static void put(CreateWorldScreen screen, String seed) {
      screen.getUiState().setSeed(seed);
      try {
         if (screen.tabNavigationBar == null) {
            return;
         }
         for (Tab tab : screen.tabNavigationBar.getTabs()) {
            if (tab instanceof CreateWorldScreen.WorldTab world && !world.seedEdit.getValue().equals(seed)) {
               world.seedEdit.setValue(seed);
            }
         }
      } catch (Throwable t) {
         FableVisionClient.LOGGER.debug("Couldn't update the World tab's seed box", t);
      }
   }

   private SeedBox() {
   }
}
