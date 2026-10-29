package com.fablevision.client.seedfinder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;

/**
 * DOES A TEXT BOX KEEP THE KEYBOARD, ITS CARET AND ITS SELECTION ACROSS A WIDGET REBUILD? (1.45.1)
 *
 * The bug: on Custom spawn every keystroke in the filter box rebuilt the screen, and the rebuild gave
 * the keyboard to the wish box (see TextFocus). This drives VANILLA'S OWN Screen.rebuildWidgets() —
 * clearWidgets, clearFocus, init, setInitialFocus — on a real Screen holding two real EditBoxes, the
 * way SpawnWishScreen holds the wish box and the filter box, and rebuilds after EVERY keystroke:
 * letters, a space, backspace, Ctrl+A then typing over it, a paste, a caret moved into the middle, a
 * selection made with Shift, and a click from one box to the other. After each rebuild the box that
 * had the keyboard must still have it, with the same text, caret and selection.
 *
 * ITS OWN CONTROL: the same script against the 1.45.0 behaviour (init focuses the first box every
 * time) must FAIL. If it passes, this check cannot see the bug and says so.
 *
 * And because a probe screen is not the real one, it also reads SpawnWishScreen and SeedMapScreen and
 * fails unless both route their rebuilds through TextFocus and the pick phase's init no longer sets
 * focus. What cannot be checked here is the real key handling in a running game — see SHIPPED-1.45.1.
 *
 * Headless notes: Screen has a constructor that takes the Minecraft instance, so a null one works as
 * long as nothing asks it anything; the boxes are made non-editable only so that focusing them does not
 * notify a game that is not running. insertText and deleteChars do not check editability, and the
 * Ctrl+A and paste steps call exactly what EditBox.keyPressed calls for them.
 *
 * {@code gradlew focusDiag}
 */
public final class TextFocusDiagnostic {

   /** A real vanilla Screen with two boxes, built the way SpawnWishScreen builds its pick phase. */
   static final class Probe extends Screen {
      final boolean fixed;
      EditBox first;
      EditBox second;
      String firstDraft = "";
      String secondDraft = "";
      private TextFocus kept;

      Probe(boolean fixed) {
         super(null, null, Component.literal("probe"));
         this.fixed = fixed;
      }

      @Override
      protected void init() {
         first = box(firstDraft);
         second = box(secondDraft);
         addRenderableWidget(first);
         addRenderableWidget(second);
         if (!fixed) {
            setInitialFocus(first);   // 1.45.0: init focused the wish box on every build
         }
      }

      private EditBox box(String text) {
         EditBox b = new EditBox(null, 0, 0, 200, 18, Component.literal("box"));
         b.setMaxLength(60);
         b.setEditable(false);
         b.setValue(text);
         return b;
      }

      /** The screen's stash(), then vanilla's rebuild, through the same hooks the real screens use. */
      void rebuild() {
         if (first != null) {
            firstDraft = first.getValue();
            secondDraft = second.getValue();
         }
         rebuildWidgets();
      }

      @Override
      protected void rebuildWidgets() {
         if (fixed) {
            kept = TextFocus.capture(getFocused(), first, second);
         }
         super.rebuildWidgets();
      }

      @Override
      protected void setInitialFocus() {
         // Vanilla's own no-argument version only acts on keyboard navigation and reads the game for
         // it; typed letters are not keyboard navigation, so it does nothing in this script.
         if (fixed) {
            TextFocus k = kept;
            kept = null;
            if (!TextFocus.restore(k, this::setInitialFocus, first, second)) {
               setInitialFocus(first);
            }
         }
      }

      void click(EditBox b) {
         setInitialFocus(b);
      }
   }

   private record Step(String name, java.util.function.Consumer<EditBox> keys) {}

   public static void main(String[] args) throws Exception {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();

      List<String> problems = new ArrayList<>();
      System.out.println("=========== DOES THE KEYBOARD SURVIVE A REBUILD? ===========");
      int fixedFailures = run(new Probe(true), true, problems);
      int controlFailures = run(new Probe(false), false, new ArrayList<>());
      System.out.println();
      System.out.println("  with TextFocus (1.45.1)      : " + (fixedFailures == 0 ? "every step kept focus, text, caret and selection"
            : fixedFailures + " step(s) lost something"));
      System.out.println("  control, 1.45.0 behaviour    : " + (controlFailures > 0 ? controlFailures
            + " step(s) lost the keyboard — the bug, reproduced" : "no step failed"));
      if (controlFailures == 0) {
         problems.add("the control did not fail, so this check cannot see the bug it is for");
      }

      // The probe is not the real screen, so the real screens are read too.
      String wish = Files.readString(Path.of("src/main/java/com/fablevision/client/seedfinder/SpawnWishScreen.java"));
      String map = Files.readString(Path.of("src/main/java/com/fablevision/client/seedfinder/SeedMapScreen.java"));
      String pickInit = between(wish, "private void initPickPhase()", "private void initLeftPanel(");
      // Code only: the comment that explains the fix quotes the old line.
      String pickCode = pickInit == null ? null : pickInit.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
      check(problems, pickCode != null && !pickCode.contains("setInitialFocus("),
            "SpawnWishScreen.initPickPhase sets focus again — every rebuild would take the keyboard");
      check(problems, wish.contains("TextFocus.capture(getFocused(), wishBox, filterBox)"),
            "SpawnWishScreen.rebuildWidgets does not capture the wish and filter boxes");
      check(problems, wish.contains("TextFocus.restore(kept, this::setInitialFocus"),
            "SpawnWishScreen.setInitialFocus does not restore through TextFocus");
      check(problems, map.contains("TextFocus.capture(getFocused(), seedBox)")
            && map.contains("TextFocus.restore(kept, this::setInitialFocus, seedBox)"),
            "SeedMapScreen does not route its seed box through TextFocus");
      check(problems, map.contains("carriedSeedText != null ? carriedSeedText : seedText"),
            "SeedMapScreen does not carry an unloaded typed seed across a rebuild");
      System.out.println("  real screens wired the same way: " + (problems.isEmpty() ? "yes" : "see below"));

      System.out.println();
      if (problems.isEmpty()) {
         System.out.println("OK — focus, text, caret and selection survive every rebuild, and the control shows the old bug");
      } else {
         problems.forEach(p -> System.out.println("PROBLEM: " + p));
      }
      System.out.println("=============================================================");
      System.exit(problems.isEmpty() ? 0 : 1);
   }

   /** Types the script into the probe, rebuilding after every step; returns how many steps failed. */
   private static int run(Probe p, boolean print, List<String> problems) {
      p.rebuild();   // the first build, as on opening the screen
      int failures = 0;
      if (p.getFocused() != p.first) {
         failures++;
         problems.add("on opening, the first box (the wish box) does not have the keyboard");
      }
      p.click(p.second);
      List<Step> script = List.of(
            new Step("type 'v'", b -> b.insertText("v")),
            new Step("type 'i'", b -> b.insertText("i")),
            new Step("type 'l'", b -> b.insertText("l")),
            new Step("space", b -> b.insertText(" ")),
            new Step("backspace", b -> b.deleteChars(-1)),
            new Step("backspace", b -> b.deleteChars(-1)),
            new Step("Ctrl+A", b -> {
               b.moveCursorToEnd(false);
               b.setHighlightPos(0);
            }),
            new Step("type over the selection", b -> b.insertText("c")),
            new Step("paste 'herry grove'", b -> b.insertText("herry grove")),
            new Step("caret to 3", b -> b.moveCursorTo(3, false)),
            new Step("type 'X' mid-word", b -> b.insertText("X")),
            new Step("Shift+Right twice", b -> {
               b.moveCursor(1, true);
               b.moveCursor(1, true);
            }),
            new Step("click the other box", b -> p.click(p.first)),
            new Step("type 'a village'", b -> b.insertText("a village")),
            new Step("click back", b -> p.click(p.second)),
            new Step("type '!'", b -> b.insertText("!")));
      for (Step s : script) {
         EditBox target = p.getFocused() instanceof EditBox b ? b : null;
         if (target == null) {
            failures++;
            if (print) {
               System.out.printf("  %-26s no text box has the keyboard%n", s.name());
            }
            continue;
         }
         s.keys().accept(target);
         GuiEventListener before = p.getFocused();
         boolean wasSecond = before == p.second;
         EditBox had = (EditBox) before;
         String text = had.getValue();
         int caret = had.getCursorPosition();
         String selected = had.getHighlighted();
         p.rebuild();
         EditBox now = wasSecond ? p.second : p.first;
         boolean ok = p.getFocused() == now && now.isFocused() && now.getValue().equals(text)
               && now.getCursorPosition() == caret && now.getHighlighted().equals(selected);
         if (!ok) {
            failures++;
         }
         if (print) {
            System.out.printf("  %-26s %-8s \"%s\" caret %d%s  %s%n", s.name(), wasSecond ? "filter" : "wish",
                  text, caret, selected.isEmpty() ? "" : " selected \"" + selected + "\"",
                  ok ? "kept" : "LOST: now " + (p.getFocused() == p.first ? "wish box" : p.getFocused() == p.second
                        ? "filter box" : "nothing") + " focused, caret " + now.getCursorPosition());
         }
      }
      if (failures > 0) {
         problems.add(failures + " step(s) lost the keyboard, text, caret or selection with TextFocus in place");
      }
      return failures;
   }

   private static void check(List<String> problems, boolean ok, String problem) {
      if (!ok) {
         problems.add(problem);
      }
   }

   private static String between(String s, String from, String to) {
      int a = s.indexOf(from);
      int b = a < 0 ? -1 : s.indexOf(to, a);
      return a < 0 || b < 0 ? null : s.substring(a, b);
   }

   private TextFocusDiagnostic() {
   }
}
