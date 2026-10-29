package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;

/**
 * The REAL rectangles of vanilla's widgets on a live Create World screen (1.46.2), for
 * {@link CreateWorldColumn} to keep clear of.
 *
 *   - every widget on the screen that is not one of ours (the footer's Create / Cancel, the open tab's
 *     widgets and labels, anything another mod added);
 *   - the TAB BUTTONS rather than the tab bar: the bar is a full-width widget, but only its buttons are
 *     drawn and clicked, and the header beside them has always held the Custom spawn button;
 *   - the widgets of the two tabs that are NOT open, laid out by vanilla's own {@code Tab.doLayout} in the
 *     tab area vanilla gives the open one. Their positions are set again by vanilla when one is opened, so
 *     this changes nothing a player sees — and it means the buttons never have to move when the player
 *     switches tab, because they already avoid all three. The World tab is the widest (310 pixels).
 *
 * Read-only towards everything else: no widget is added, removed, resized, or moved on screen.
 */
public final class CreateWorldBounds {

   /** The footer band vanilla's Create World screen uses (its HeaderAndFooterLayout's default). */
   public static final int FOOTER_H = HeaderAndFooterLayout.DEFAULT_HEADER_AND_FOOTER_HEIGHT;

   private CreateWorldBounds() {
   }

   /** Where the tab bar ends; 24 in 26.3. */
   public static int headerBottom(CreateWorldScreen screen) {
      MenuTabBar bar = screen.tabNavigationBar;
      return bar != null ? bar.getRectangle().bottom() : 24;
   }

   /** Every vanilla widget's rectangle, of all three tabs; {@code ours} are left out. */
   public static List<CreateWorldColumn.Box> of(CreateWorldScreen screen, Set<?> ours) {
      List<CreateWorldColumn.Box> out = new ArrayList<>();
      Set<AbstractWidget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
      for (GuiEventListener child : screen.children()) {
         if (ours.contains(child)) {
            continue;
         }
         if (child instanceof TabNavigationBar bar) {
            for (GuiEventListener tabButton : bar.children()) {
               if (tabButton instanceof AbstractWidget w) {
                  add(out, seen, w);
               }
            }
            continue;
         }
         if (child instanceof AbstractWidget w && w.visible) {
            add(out, seen, w);
         }
      }
      MenuTabBar bar = screen.tabNavigationBar;
      if (bar != null) {
         int top = bar.getRectangle().bottom();
         ScreenRectangle area = new ScreenRectangle(0, top, screen.width, screen.height - FOOTER_H - top);
         for (Tab tab : bar.getTabs()) {
            boolean[] open = {false};
            tab.visitChildren(w -> open[0] |= seen.contains(w));
            if (!open[0]) {
               // The open tab is already where vanilla put it and is left alone.
               tab.doLayout(area);
            }
            tab.visitChildren(w -> add(out, seen, w));
         }
      }
      return out;
   }

   private static void add(List<CreateWorldColumn.Box> out, Set<AbstractWidget> seen, AbstractWidget w) {
      if (!seen.add(w) || w.getWidth() <= 0 || w.getHeight() <= 0) {
         return;
      }
      out.add(new CreateWorldColumn.Box(w.getX(), w.getY(), w.getWidth(), w.getHeight()));
   }
}
