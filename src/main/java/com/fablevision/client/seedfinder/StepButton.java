package com.fablevision.client.seedfinder;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

/**
 * A button that steps a number: a left click goes forward, a RIGHT click or a SHIFT-click goes back
 * (1.46.2, the Worlds button). Shift-click backwards is what vanilla's own cycling buttons do.
 *
 * A plain button only listens to the left mouse button, so the right one is let in here. Mouse buttons
 * are numbered from 1 in 26.3 (left 1, right 3), so they are compared with the game's own constants.
 */
final class StepButton extends Button.Plain {
   private final IntConsumer step;

   /** {@code step} gets +1 for forward and -1 for back. */
   StepButton(int x, int y, int w, int h, Component message, IntConsumer step) {
      super(x, y, w, h, message, b -> {}, DEFAULT_NARRATION);
      this.step = step;
   }

   @Override
   protected boolean isValidClickButton(MouseButtonInfo info) {
      return info.button() == InputConstants.MOUSE_BUTTON_LEFT || info.button() == InputConstants.MOUSE_BUTTON_RIGHT;
   }

   @Override
   public void onPress(InputWithModifiers input) {
      step.accept(back(input) ? -1 : 1);
   }

   /** Right mouse button, or Shift held (with the mouse or with Enter/Space on the keyboard). */
   static boolean back(InputWithModifiers input) {
      return input.hasShiftDown()
            || input instanceof MouseButtonEvent e && e.button() == InputConstants.MOUSE_BUTTON_RIGHT;
   }
}
