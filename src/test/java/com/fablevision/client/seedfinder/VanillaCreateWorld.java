package com.fablevision.client.seedfinder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * VANILLA'S CREATE WORLD SCREEN, LAID OUT BY VANILLA'S OWN LAYOUT CODE, without a game (layoutDiag, 1.46.2).
 *
 * A real CreateWorldScreen cannot be built headless — its tabs reach for Minecraft.getInstance() — so this
 * builds the same tree out of the same classes: the real MenuTabBar (its arrangeElements places the tab
 * buttons), real GridLayoutTabs whose doLayout centres each tab in the tab area exactly as the game does,
 * vanilla's GridLayout / LinearLayout / SpacerElement, and the real HeaderAndFooterLayout for the footer.
 * Only the leaf SIZES are copied, from CreateWorldScreen's source (26.3): the name box 208x20, the Game tab's
 * 210-wide buttons, the World tab's 150 + 150, the 308-wide seed box and the 310-wide switch grid with its
 * 44-wide switches, the More tab's three 210-wide buttons, the footer's two 150-wide buttons. Labels are
 * measured with the game's font.
 *
 * {@link #tripwires} reads the constants those sizes come from out of the game's classes, so a game update
 * that changes them fails layoutDiag instead of letting this model go quietly stale. In game none of this is
 * used: the screen reads the live widgets (CreateWorldBounds).
 */
final class VanillaCreateWorld {

   static final int FOOTER_H = HeaderAndFooterLayout.DEFAULT_HEADER_AND_FOOTER_HEIGHT;
   private static final int LABEL_H = 9;

   private VanillaCreateWorld() {
   }

   /** The model's figures against the game's own constants; empty when they agree. */
   static List<String> tripwires() {
      List<String> bad = new ArrayList<>();
      expect(bad, "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen", "TAB_COLUMN_WIDTH", 210);
      expect(bad, "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab", "WORLD_TAB_WIDTH", 310);
      expect(bad, "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen", "VERTICAL_BUTTON_SPACING", 8);
      expect(bad, "net.minecraft.client.gui.screens.worldselection.SwitchGrid", "DEFAULT_SWITCH_BUTTON_WIDTH", 44);
      expect(bad, "net.minecraft.client.gui.components.tabs.MenuTabBar", "HEIGHT", 24);
      expect(bad, "net.minecraft.client.gui.components.tabs.MenuTabBar", "MAX_WIDTH", 400);
      expect(bad, "net.minecraft.client.gui.layouts.CommonLayouts", "LABEL_SPACING", 4);
      expect(bad, "net.minecraft.client.gui.components.Button", "DEFAULT_WIDTH", 150);
      expect(bad, "net.minecraft.client.gui.layouts.HeaderAndFooterLayout", "DEFAULT_HEADER_AND_FOOTER_HEIGHT", 33);
      // The three tabs, and no fourth.
      int tabs = 0;
      try {
         for (Class<?> c : Class.forName("net.minecraft.client.gui.screens.worldselection.CreateWorldScreen").getDeclaredClasses()) {
            if (GridLayoutTab.class.isAssignableFrom(c)) {
               tabs++;
            }
         }
      } catch (Throwable t) {
         bad.add("cannot read CreateWorldScreen's tabs: " + t);
      }
      if (tabs != 3) {
         bad.add("CreateWorldScreen has " + tabs + " tabs, the model has 3 (Game, World, More)");
      }
      return bad;
   }

   private static void expect(List<String> bad, String cls, String field, int value) {
      try {
         Field f = Class.forName(cls).getDeclaredField(field);
         f.setAccessible(true);
         int actual = f.getInt(null);
         if (actual != value) {
            bad.add(cls.substring(cls.lastIndexOf('.') + 1) + "." + field + " is " + actual + ", the model uses " + value);
         }
      } catch (Throwable t) {
         bad.add("cannot read " + cls + "." + field + ": " + t);
      }
   }

   /** Is this game version one that shows the Experiments button on the Game tab (unstable builds)? */
   static boolean unstable() {
      try {
         return !SharedConstants.getCurrentVersion().stable();
      } catch (Throwable unknown) {
         return true;   // the taller Game tab: the stricter check
      }
   }

   /** Where the tab bar ends, from the real MenuTabBar. */
   static int headerBottom(int width) {
      return bar(width).getRectangle().bottom();
   }

   private static MenuTabBar bar(int width) {
      MenuTabBar bar = MenuTabBar.builder(new TabManager(w -> {}, w -> {}), width)
            .addTabs(new GameTab(), new WorldTab(), new MoreTab()).build();
      bar.arrangeElements(width);
      return bar;
   }

   /** Every vanilla rectangle at this window size: tab buttons, all three tabs' widgets and labels, footer. */
   static List<CreateWorldColumn.Box> bounds(int width, int height) {
      List<CreateWorldColumn.Box> out = new ArrayList<>();
      MenuTabBar bar = bar(width);
      for (GuiEventListener b : bar.children()) {
         if (b instanceof AbstractWidget w) {
            out.add(new CreateWorldColumn.Box(w.getX(), w.getY(), w.getWidth(), w.getHeight()));
         }
      }
      int top = bar.getRectangle().bottom();
      ScreenRectangle area = new ScreenRectangle(0, top, width, height - FOOTER_H - top);
      for (GridLayoutTab tab : new GridLayoutTab[]{new GameTab(), new WorldTab(), new MoreTab()}) {
         tab.doLayout(area);
         leaves(tab.getLayout(), out);
      }
      HeaderAndFooterLayout frame = new HeaderAndFooterLayout(new Sized(width, height));
      LinearLayout footer = frame.addToFooter(LinearLayout.horizontal().spacing(8));
      footer.addChild(box(150, 20));
      footer.addChild(box(150, 20));
      frame.setHeaderHeight(top);
      frame.arrangeElements();
      leaves(footer, out);
      return out;
   }

   private static void leaves(LayoutElement e, List<CreateWorldColumn.Box> out) {
      if (e instanceof Layout l) {
         l.visitChildren(c -> leaves(c, out));
      } else if (e.getWidth() > 0 && e.getHeight() > 0) {
         out.add(new CreateWorldColumn.Box(e.getX(), e.getY(), e.getWidth(), e.getHeight()));
      }
   }

   private static SpacerElement box(int w, int h) {
      return new SpacerElement(w, h);
   }

   /** A label: a line of the game's font, as wide as the English text (CommonLayouts / SwitchGrid labels). */
   private static SpacerElement label(String text) {
      return new SpacerElement(RealFont.get().width(text), LABEL_H);
   }

   /** CommonLayouts.labeledElement: the label above the element, 4 apart. */
   private static Layout labeled(String text, int w, int h) {
      LinearLayout l = LinearLayout.vertical().spacing(4);
      l.addChild(label(text));
      l.addChild(box(w, h));
      return l;
   }

   /** CreateWorldScreen.GameTab: name, game mode, difficulty, commands (and Experiments on unstable builds). */
   private static final class GameTab extends GridLayoutTab {
      GameTab() {
         super(Component.translatable("createWorld.tab.game.title"));
         GridLayout.RowHelper helper = this.layout.rowSpacing(8).createRowHelper(1);
         LayoutSettings buttons = helper.newCellSettings();
         helper.addChild(labeled("World Name", 208, 20), helper.newCellSettings().alignHorizontallyCenter());
         helper.addChild(box(210, 20), buttons);
         helper.addChild(box(210, 20), buttons);
         helper.addChild(box(210, 20));
         if (unstable()) {
            helper.addChild(box(210, 20));
         }
      }
   }

   /** CreateWorldScreen.WorldTab: type and customize, the seed box, the switch grid. */
   private static final class WorldTab extends GridLayoutTab {
      WorldTab() {
         super(Component.translatable("createWorld.tab.world.title"));
         GridLayout.RowHelper helper = this.layout.columnSpacing(10).rowSpacing(8).createRowHelper(2);
         helper.addChild(box(150, 20));
         helper.addChild(box(150, 20));
         helper.addChild(labeled("Seed for the World Generator", 308, 20), 2);
         // SwitchGrid.Builder.build, for two switches with no info underneath.
         GridLayout grid = new GridLayout().rowSpacing(4);
         grid.addChild(SpacerElement.width(310 - 44), 0, 0);
         grid.addChild(SpacerElement.width(44), 0, 1);
         int row = 0;
         for (String switchLabel : new String[]{"Generate Structures", "Bonus Chest"}) {
            row++;
            grid.addChild(label(switchLabel), row, 0, grid.newCellSettings().align(0.0F, 0.5F));
            grid.addChild(box(44, 20), row, 1, grid.newCellSettings().alignHorizontallyRight());
         }
         grid.arrangeElements();
         helper.addChild(grid, 2);
      }
   }

   /** CreateWorldScreen.MoreTab: Game Rules, Experiments, Data Packs. */
   private static final class MoreTab extends GridLayoutTab {
      MoreTab() {
         super(Component.translatable("createWorld.tab.more.title"));
         GridLayout.RowHelper helper = this.layout.rowSpacing(8).createRowHelper(1);
         helper.addChild(box(210, 20));
         helper.addChild(box(210, 20));
         helper.addChild(box(210, 20));
      }
   }

   /** A screen of a size, for HeaderAndFooterLayout to read its width and height from. Never shown. */
   private static final class Sized extends Screen {
      Sized(int width, int height) {
         super(null, null, Component.empty());
         this.width = width;
         this.height = height;
      }
   }
}
