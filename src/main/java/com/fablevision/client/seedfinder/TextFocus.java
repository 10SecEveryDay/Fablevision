package com.fablevision.client.seedfinder;

import java.util.function.Consumer;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;

/**
 * THE KEYBOARD STAYS WHERE THE PLAYER LEFT IT WHEN A SCREEN REBUILDS ITS WIDGETS (1.45.1).
 *
 * Found in game: on Custom spawn, every keystroke in "Filter by name…" sent the cursor to the wish
 * box above it. Typing in the filter marks the list for a rebuild on the next tick, rebuildWidgets()
 * makes every widget again — so the box being typed in is replaced by a new one — and init() then
 * gave the focus to the wish box, unconditionally. The first character landed in the filter and the
 * rest in the wish box. 1.44.7 has the same code, and vanilla's rebuildWidgets() and setInitialFocus()
 * are identical in 26.1.2 and 26.3, so this was not a port regression. Any other rebuild while typing
 * (the catalog finishing loading, a status line changing, a resize) moved the caret to the end too.
 *
 * A screen hands its text boxes to {@link #capture} just before it rebuilds, and to {@link #restore}
 * from its setInitialFocus() — the last thing vanilla's rebuildWidgets() calls. Whichever box had the
 * keyboard gets it back, with the caret and the selection where they were; the TEXT is the screen's to
 * carry over (its drafts). When no text box had the keyboard — a button was just clicked — restore
 * says so and the screen applies its own default, exactly as before.
 *
 * {@code gradlew focusDiag} drives vanilla's own rebuildWidgets() on real EditBoxes through this class.
 */
final class TextFocus {

   private final int index;
   private final int cursor;
   private final int highlight;

   private TextFocus(int index, int cursor, int highlight) {
      this.index = index;
      this.cursor = cursor;
      this.highlight = highlight;
   }

   /**
    * Which of {@code boxes} has the keyboard now, with its caret and selection — or null if none does.
    * Pass the boxes in a fixed order; {@link #restore} gets the new ones in the same order. Null boxes
    * (not on the screen this time) are skipped.
    */
   static TextFocus capture(GuiEventListener focused, EditBox... boxes) {
      if (focused == null) {
         return null;
      }
      for (int i = 0; i < boxes.length; i++) {
         if (boxes[i] != null && boxes[i] == focused) {
            return new TextFocus(i, boxes[i].getCursorPosition(), highlightOf(boxes[i]));
         }
      }
      return null;
   }

   /**
    * Gives the keyboard back after a rebuild: focuses the new box in the kept box's place through
    * {@code focus} (the screen's own setInitialFocus(box), the same path a click takes) and puts the
    * caret and selection back. False when there was nothing to give back, so the caller applies its
    * default. Positions are clamped by EditBox, so a shorter text cannot put the caret out of range.
    */
   static boolean restore(TextFocus kept, Consumer<GuiEventListener> focus, EditBox... boxes) {
      if (kept == null || kept.index >= boxes.length || boxes[kept.index] == null) {
         return false;
      }
      EditBox box = boxes[kept.index];
      focus.accept(box);
      // Selection end first, caret second: each one scrolls the box to show itself, and the caret is
      // the one the player is looking at.
      box.setHighlightPos(kept.highlight);
      box.setCursorPosition(kept.cursor);
      return true;
   }

   /**
    * The other end of the selection. EditBox keeps it private, but getHighlighted() is exactly the
    * text between it and the caret, so it is the caret minus or plus that length — before the caret
    * if the text there matches (Ctrl+A puts the caret at the end and the other end at 0), else after.
    */
   static int highlightOf(EditBox box) {
      String selected = box.getHighlighted();
      int caret = box.getCursorPosition();
      if (selected.isEmpty()) {
         return caret;
      }
      int before = caret - selected.length();
      return before >= 0 && box.getValue().startsWith(selected, before) ? before : caret + selected.length();
   }
}
