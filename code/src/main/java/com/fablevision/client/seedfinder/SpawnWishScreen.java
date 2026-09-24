package com.fablevision.client.seedfinder;

import com.fablevision.client.FableVisionClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.fablevision.client.FableVisionConfig;
import com.tensec.aiscreen.AiVision;
import com.tensec.aiscreen.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

/**
 * The "want a custom spawn?" ask before creating a singleplayer world.
 *
 * Describe the wish in your own words (the AI ONLY translates words → search settings) or
 * pick a structure/biome from the icon lists — either way {@link SeedFinder} legit-searches
 * real seeds with the game's own world-gen code. The moment one is found, the seed is typed
 * into the Create World screen and you're taken straight back — just press Create.
 */
public class SpawnWishScreen extends Screen {
   private enum Phase { PICK, SEARCHING }
   // Picker enum removed in 1.36.0 — the screen no longer swaps between a wish view and a
   // separate full-screen pick list. Both are on screen at once, which is what the two panels are.

   /** One-shot guard so returning to the Create World screen doesn't re-ask. */
   private static boolean suppressOnce;
   /** Last seed we typed into the Create World screen (for its little ✓ badge). */
   public static volatile String lastAppliedSeed;
   /** Search stats for that seed — full sentence for chat, short form for the badge. */
   public static volatile String lastAppliedStats = "";
   public static volatile String lastAppliedShort = "";
   /** What the search verified ("Spawn @ 120, -80", "Pale Garden right at spawn"). */
   public static volatile List<String> lastAppliedMatches = List.of();
   /** The same finds as data, for the SHORT join message and the map page. May be empty. */
   public static volatile List<SeedCriteria.Find> lastAppliedFinds = List.of();
   /** Seeds searched, for the short header ("1.4B seeds"). */
   public static volatile long lastAppliedChecked;
   /** How long it took, already formatted ("3m36s"). */
   public static volatile String lastAppliedTook = "";
   /** The AI's honest "couldn't check this part" note, if any. */
   public static volatile String lastAppliedNote = "";
   private static boolean announcePending;

   public static boolean consumeSuppress() {
      boolean was = suppressOnce;
      suppressOnce = false;
      return was;
   }

   public static void suppressNext() {
      suppressOnce = true;
   }

   /** One-shot: true when the freshly joined world is the seed we found (chat the stats). */
   public static boolean consumeAnnounce(String worldSeed) {
      if (announcePending && worldSeed != null && worldSeed.equals(lastAppliedSeed)) {
         announcePending = false;
         return true;
      }
      return false;
   }

   // ── Layout constants (everything else is computed from width/height, so a resize or a jump to
   // fullscreen re-anchors by itself — init() runs again and nothing is remembered in pixels) ──
   // MARGIN / GAP / ROW_H / PICK_H moved into WishLayout in 1.37.0 and must not come back here.
   // Half the layout living in this file as constants counted down from the top of the WINDOW, and
   // the other half computed in render() counting up from the bottom of it, is precisely how the
   // feedback line ended up drawn over the last row of the list on a short window. There is one
   // source of geometry now, and it is testable without a game.
   private static final int TOP_H = 20;

   /** Below this many pixels for a chosen row's NAME, the size word is dropped to make room.
    *  See where it is used; {@code layoutDiag} models the same rule. */
   private static final int NAME_FLOOR = 48;

   /** Past this with no reply, the request is treated as lost, CANCELLED (see aiRequest), and the
    *  button works again. It is no longer longer than AiVision's own ceiling: since 1.43.1 a timed-out
    *  request is retried once, so the provider can take up to about 300 s. Waiting that long on a
    *  "thinking…" line is worse than letting the player ask again, which is safe now that a late
    *  reply to a cancelled request is dropped rather than starting a search nobody expects. */
   private static final long AI_GIVE_UP_MS = 135_000L;

   /**
    * One thing the player has asked for, stored by LABEL rather than by index or by object.
    *
    * The catalog is rebuilt off-thread whenever the registry source changes, so an index is a
    * reference to a list that can be replaced underneath it and an object is one that can go
    * stale. A label survives both and is what the search resolves against anyway.
    */
   private record Pick(String label, boolean biome, boolean negated, BiomeShape.Size size, int link,
                       int count, boolean inside) {
      Pick(String label, boolean biome, boolean negated) {
         this(label, biome, negated, BiomeShape.Size.ANY, 0, 1, false);
      }

      Pick withSize(BiomeShape.Size s) {
         return new Pick(label, biome, negated, s, link, count, inside);
      }

      Pick withLink(int id) {
         return new Pick(label, biome, negated, size, id, count, inside);
      }

      Pick withCount(int n) {
         return new Pick(label, biome, negated, size, link, n, inside);
      }

      /** Spawning INSIDE one is a statement about a single structure, so it forces the count to 1. */
      Pick withInside(boolean b) {
         return new Pick(label, biome, negated, size, link, b ? 1 : count, b);
      }

      /** The slime row is neither a biome nor a structure; it is matched by its own label. */
      boolean slime() {
         return !biome && SlimeChunks.ROW_LABEL.equals(label);
      }
   }

   /**
    * Which group of rows the left panel is showing. Fixed order; three lengths of name.
    *
    * THREE, BECAUSE A BUTTON DOES NOT TRUNCATE. The four tabs divide the left column between them,
    * so each one gets (leftW - 6) / 4 pixels — 34 of them on a 320-wide window, which is five
    * characters. A vanilla button draws its label centred and lets whatever does not fit run past
    * its own edge, so "Structures" at 60 pixels in a 34-pixel tab is not clipped, it is drawn
    * across the tab beside it. That is the "text overlays other elements" report, and no amount of
    * checking the GEOMETRY could have found it: the boxes never overlapped, only the words did.
    *
    * The abbreviations are only ever seen on a window too narrow for the words, and the row list
    * underneath says what the tab holds either way.
    */
   private enum Tab {
      VILLAGE("Village", "Vill", "Vil"),
      STRUCTURE("Structures", "Struct", "Str"),
      BIOME("Biomes", "Biome", "Bio"),
      DEEP("Nether", "Neth", "Net");

      final String title;
      final String medium;
      final String tiny;

      Tab(String title, String medium, String tiny) {
         this.title = title;
         this.medium = medium;
         this.tiny = tiny;
      }
   }

   /**
    * A left-panel row, already reduced to exactly what gets drawn.
    *
    * {@code note} is the long explanation, shown as a tooltip on hover. It had NO READER at all
    * until 1.41.0: every catalog row carries several paragraphs saying exactly what the row does and
    * does not promise — measured rates, which village types have the building, what is rolled later
    * and therefore cannot be checked — and the only screen that ever displayed them was the
    * standalone finder, deleted in 1.40.0. They were being compiled into every jar and shown
    * nowhere, which is the worst possible place for the most careful writing in the project: a
    * player picking "Mansion with Lava Vault" could not find out it is 1 in 50 until they had waited.
    */
   private record Row(String label, boolean biome, String line1, String line2, ItemStack icon,
                      boolean slow, String note) {}

   // Selection and browsing state is static so it survives rebuildWidgets(), a resize, and
   // stepping out to the AI-key screen and back — the same reason wishDraft always has been.
   private static final List<Pick> PICKS = new ArrayList<>();
   private static boolean pickNegated;
   private static Tab tab = Tab.VILLAGE;
   private static String filterDraft = "";
   /**
    * "Next to" pairs, held as a link id shared by exactly two picks plus that link's own distance.
    *
    * A link id rather than a pointer between picks, for the same reason a {@link Pick} stores a
    * label rather than an object: picks are immutable records that get replaced whenever anything
    * about them changes, so anything pointing AT one would be stale a click later. An id survives.
    */
   private static final java.util.Map<Integer, Integer> LINK_WITHIN = new java.util.LinkedHashMap<>();
   private static int nextLinkId = 1;
   /** The pick waiting for a partner after its 🔗 was clicked, by label. */
   private static String linkPending;

   private final CreateWorldScreen parent;
   private final HolderLookup.Provider registries;
   private EditBox wishBox;
   private EditBox filterBox;
   private String wishDraft = "";
   private int rowScroll;
   /** How many rows the current tab+filter produced, so the scrollbar can size its thumb without
    *  rebuilding the list every frame. */
   private int rowCount;
   /** The geometry this screen was last built with. Recomputed in init(), which vanilla re-runs on
    *  every resize, so it is never stale — render() reads it rather than re-deriving coordinates
    *  and risking a second, subtly different answer. */
   private WishLayout lay;
   private boolean aiWaiting;
   /** Which AI request a reply must belong to in order to be used. STATIC so a reply for a closed
    *  screen instance is still recognised as stale by the next one. */
   private static long aiRequest;
   /**
    * The wish whose AI request was cancelled by closing this screen, or null. Set by {@link #back};
    * the next screen opened says so plainly and puts the wish back in the box (1.44.3). Before this,
    * pressing Esc while the AI was thinking and reopening showed an ordinary screen — no "thinking",
    * no word that anything had happened — because the request had been cancelled and the new screen
    * had no way to know it had ever existed.
    */
   private static String cancelledWish;
   /**
    * When the outstanding AI request was sent, so a reply that never arrives cannot lock the
    * button on "✨ …" for the rest of the session.
    *
    * {@link AiVision} calls back exactly once on every path it knows about, and it now catches
    * Throwable so an Error cannot escape one. This is the layer that does not have to trust that:
    * the only cost of being wrong about a stuck request is that the player can ask again, and the
    * cost of not having it is a screen whose main button silently stops working.
    */
   private long aiSentMs;
   private String feedback = "";
   private String searchNote = "";
   /**
    * A warning about the SEARCH, as opposed to a note about the RESULT — and the difference is
    * the whole reason this is a second field.
    *
    * {@code searchNote} says something was dropped, refused or reinterpreted, so it is still true
    * after a seed is found and it travels into the join message. "This may run a long time" is not
    * that. It is advice about the wait, void the moment a result arrives, and while it shared a
    * field with the real notes it did something worse than take up room: a non-empty note is what
    * {@code FableVisionClient.needsLongForm} reads to decide the join message cannot be shortened.
    * So every Nether wish tighter than {@link SeedCriteria#NETHER_TIGHT} — which is every Nether
    * wish in Exact mode, and the default one in Fast — printed the four-line explanation on join
    * because it had been warned it might be slow and then wasn't.
    *
    * Shown on the searching screen exactly like a note. Never stored in {@code lastAppliedNote}.
    */
   private String searchWarning = "";
   private Phase builtPhase = Phase.PICK;
   private boolean builtWithCatalog;
   /** How many feedback lines the built layout was asked for. A message can be set from a handler
    *  that does not rebuild (every early return in {@code startAi}, for one), and the extra lines
    *  come out of the browse list — so the screen has to notice and rebuild, or the message would
    *  be clipped to one line until something else happened to rebuild for it. */
   private int builtFeedbackLines = 1;
   private SeedFinder.Job myJob;
   /** Filled in once, off-thread, when a search has run long enough to look stuck. */
   private volatile String bottleneck;
   private boolean bottleneckStarted;
   private long searchStartMs;
   private boolean seedApplied;
   private long rateAtMs;
   private long rateAtChecked;
   private long ratePerSec;
   private final List<IconAt> icons = new ArrayList<>();

   private record IconAt(ItemStack stack, int x, int y) {}

   /** A left-panel row's TEXT, drawn by render() rather than by the button underneath it: a
    *  vanilla button centres a single line, and these are two lines, left-aligned. */
   private record RowText(Row row, int x, int y, int w, boolean chosen) {}

   private final List<RowText> rowText = new ArrayList<>();

   public SpawnWishScreen(CreateWorldScreen parent) {
      super(Component.literal("Custom spawn?"));
      this.parent = parent;
      // findSource goes through SeedAccess, so this is null anywhere that is not the Create World
      // screen with no world loaded. Null registries means every path below reports the block and
      // does nothing — there is no branch that searches without them.
      this.registries = WorldgenContext.findSource(parent);
      if (cancelledWish != null) {
         wishDraft = cancelledWish;
         feedback = "Your AI request was cancelled when you closed this screen. Press Search to ask again.";
         cancelledWish = null;
      }
   }

   private Phase phase() {
      return myJob != null && (myJob.running || myJob.bootstrapping) ? Phase.SEARCHING : Phase.PICK;
   }

   /** The current geometry. Two things vary from frame to frame and both change the layout, so both
    *  are passed in: the live pair count (the pair rows take room from the list above them) and how
    *  tall the feedback message is once wrapped. */
   private WishLayout layout() {
      return WishLayout.of(this.width, this.height, liveLinks().size(), wantedFeedbackLines());
   }

   /**
    * How many lines the feedback message needs, before the layout decides how many it can afford.
    *
    * Kept separate from the finished layout because the two are compared: the screen rebuilds when
    * this changes, and comparing against the GRANTED count instead would rebuild forever on a
    * window too short to give a three-line message three lines.
    */
   private int wantedFeedbackLines() {
      if (feedback.isEmpty()) {
         return 1;
      }
      // Measured against a one-line layout first, purely to learn the panel width — that does not
      // depend on the feedback height, so there is no circularity here, just two cheap
      // constructions of a pure record.
      WishLayout probe = WishLayout.of(this.width, this.height, liveLinks().size());
      return Math.min(WishLayout.MAX_FEEDBACK_LINES, Math.max(1, wrappedFeedback(probe).size()));
   }

   /**
    * The feedback message, wrapped to the panel width.
    *
    * IT USED TO BE CUT AT 70 CHARACTERS with an ellipsis, and that was fine while the only things
    * it said were "Pick a structure or a biome first". The messages that matter are the ones from
    * the AI — a provider error, a quota notice, or (now) the first line of a reply that was not a
    * search — and every one of those is longer than 70 characters and useless with its end missing.
    * A message you cannot finish reading is not an error report.
    *
    * The SEARCHING phase has wrapped its feedback since 1.40.0; this is the same treatment for the
    * picker, and {@link WishLayout} is told the height so the extra lines take room from the list
    * instead of being drawn on top of it.
    */
   private java.util.List<net.minecraft.util.FormattedCharSequence> wrappedFeedback(WishLayout L) {
      if (feedback.isEmpty()) {
         return List.of();
      }
      return this.font.split(Component.literal("§e" + feedback), L.panelW() - 20);
   }

   @Override
   protected void init() {
      SeedCatalogCache.ensure(registries);
      builtWithCatalog = SeedCatalogCache.ready();
      builtPhase = phase();
      icons.clear();
      rowText.clear();
      filterDirty = false;
      builtFeedbackLines = wantedFeedbackLines();
      this.lay = layout();

      if (builtPhase == Phase.SEARCHING) {
         // THE STOP BUTTON SITS ON THE PANEL FLOOR NOW, not 110 pixels down from the top.
         //
         // At panelY + 110 it was an island in the middle of the status text, and every line had to
         // be placed either above it or below it by a hand-counted offset — sy + 16, + 30, + 44,
         // + 74, + 88, + 100. Those offsets are what collided (the run-id line at + 92 was drawn
         // through the "working out the hard part" line at + 88), and they are also why no line
         // could be allowed to wrap: wrapping one would have pushed it into the next fixed offset.
         // With the button anchored to the floor, everything above it simply flows.
         addRenderableWidget(Button.builder(Component.literal("■ Stop searching"), b -> {
            SeedFinder.stop();
            feedback = String.format("Stopped after %,d seeds. Pick something else, or start again.",
                  myJob == null ? 0 : myJob.checked.get());
         }).bounds(lay.panelX() + lay.panelW() / 2 - 80, lay.bottomY(), 160, 20).build());
      } else {
         initPickPhase();
      }
   }

   /**
    * The two-panel picker: browse on the left, the wish you are building on the right.
    *
    * Every coordinate is computed from {@code this.width}/{@code this.height} here, which is the
    * whole re-anchoring story — vanilla calls {@code init()} again on a resize or a fullscreen
    * toggle, so nothing needs to remember a pixel. What DOES need remembering (the picks, the tab,
    * the filter text, the wish) is static or stashed, so it survives that rebuild.
    */
   private void initPickPhase() {
      WishLayout L = layout();
      this.lay = L;

      // ── Top strip: title (drawn in render) on the left, mode toggle on the right ──────────
      // MODE, worded so it cannot be read backwards. "🎯 Exact — at spawn" is ambiguous on a
      // button: half of people read a button label as the state and half as the action, and someone
      // who read it as the action believed they were in Exact while every search ran Fast — which
      // is how a "fast mode aimed at 0, 0" line turned up in a session the player was sure was
      // Exact. Saying "Mode:" makes it a statement, and the arrow says what clicking does.
      addRenderableWidget(Button.builder(Component.literal(FableVisionConfig.seedFastMode
            ? "Mode: ⚡Fast ▸ Exact" : "Mode: 🎯Exact ▸ Fast"), b -> {
         FableVisionConfig.seedFastMode = !FableVisionConfig.seedFastMode;
         FableVisionConfig.save();
         stash();
         rebuildWidgets();
      }).bounds(L.modeX(), L.modeY(), L.modeW(), TOP_H).build());
      // THE DISTANCE CONTROL FAST MODE NEEDS. It lived only on the other seed screen, so anyone who
      // came in through this one had it permanently stuck at its default of 100 blocks with no way
      // to tell, let alone change it. Exact mode has no equivalent because its reach is fixed at
      // SEED_EXACT_RADIUS by definition — that is what "at spawn" means — so the slot shows why.
      if (FableVisionConfig.seedFastMode) {
         addRenderableWidget(Button.builder(
               Component.literal("Within " + FableVisionConfig.seedFastDistance), b -> {
            FableVisionConfig.seedFastDistance = SeedCatalogCache.nextFastDistance();
            FableVisionConfig.save();
            stash();
            rebuildWidgets();
         }).bounds(L.subX(), L.modeY(), L.subW(), TOP_H).build());
      }

      // ── Wish box, full width under the title ─────────────────────────────────────────────
      wishBox = new EditBox(this.font, L.wishX(), L.wishY(), L.wishW(), TOP_H,
            Component.literal("wish"));
      wishBox.setMaxLength(200);
      wishBox.setValue(wishDraft);
      wishBox.setHint(Component.literal("Describe your spawn — \"a good survival seed with a weaponsmith\""));
      addRenderableWidget(wishBox);
      setInitialFocus(wishBox);
      addRenderableWidget(Button.builder(Component.literal(aiWaiting ? "✨ …" : "✨ Search"), b -> startAi())
            .bounds(L.aiX(), L.wishY(), L.aiW(), TOP_H).build());
      addRenderableWidget(Button.builder(Component.literal(
            Config.getKey(Config.Keyset.SPAWN).isEmpty() ? "⚙ key" : "⚙ ✓"), b -> {
         stash();
         this.minecraft.setScreen(new com.tensec.aiscreen.ConfigScreen(this, Config.Keyset.SPAWN));
      }).bounds(L.keyX(), L.wishY(), L.keyW(), TOP_H).build());

      initLeftPanel(L);
      initRightPanel(L);

      int[] band = L.bottomLeft();
      addRenderableWidget(Button.builder(Component.literal("No thanks"), b -> back())
            .bounds(band[0], L.bottomY(), band[1], TOP_H).build());
      String ask = widest(band[3] - 4, FableVisionConfig.askCustomSpawn ? WishLayout.ASK_ON : WishLayout.ASK_OFF);
      addRenderableWidget(Button.builder(Component.literal(
            ask.replace(": ON", ": §aON").replace(": OFF", ": §cOFF")), b -> {
         FableVisionConfig.askCustomSpawn = !FableVisionConfig.askCustomSpawn;
         FableVisionConfig.save();
         stash();
         rebuildWidgets();
      }).tooltip(Tooltip.create(Component.literal("Open this screen by itself every time you create a world")))
            .bounds(band[2], L.bottomY(), band[3], TOP_H).build());
      // BACK ON THIS SCREEN (1.44.3). The spoiler setting was only reachable from the result map and
      // Extras' settings, so someone who wanted to go in blind had to find it after the search.
      addRenderableWidget(Button.builder(Component.literal(widest(band[5] - 4,
            FableVisionConfig.seedSpoilers ? WishLayout.COORDS_SHOWN : WishLayout.COORDS_HIDDEN)), b -> {
         FableVisionConfig.seedSpoilers = !FableVisionConfig.seedSpoilers;
         FableVisionConfig.save();
         stash();
         rebuildWidgets();
      }).tooltip(Tooltip.create(Component.literal(
            "Whether the join message and the map show where the things you asked for are")))
            .bounds(band[4], L.bottomY(), band[5], TOP_H).build());
   }

   /** Filter box, category tabs, then the two-line rows. */
   private void initLeftPanel(WishLayout L) {
      int x = L.leftX();
      int w = L.leftW();
      filterBox = new EditBox(this.font, x, L.filterY(), w, 18, Component.literal("filter"));
      filterBox.setMaxLength(40);
      filterBox.setValue(filterDraft);
      filterBox.setHint(Component.literal("Filter by name…"));
      filterBox.setResponder(v -> {
         // Live filtering, and the scroll has to go back to the top with it: filtering to four
         // rows while scrolled to row thirty shows an empty list, which reads as "no matches".
         if (!v.equals(filterDraft)) {
            filterDraft = v;
            rowScroll = 0;
            rebuildRowsOnly();
         }
      });
      addRenderableWidget(filterBox);

      Tab[] tabs = Tab.values();
      int tabW = (w - (tabs.length - 1) * 2) / tabs.length;
      for (int i = 0; i < tabs.length; i++) {
         Tab t = tabs[i];
         // Longest wording that fits THIS tab, so the label can never be drawn over its neighbour.
         // A button has no truncation of its own; see the Tab enum.
         String title = widest(tabW - 4, t.title, t.medium, t.tiny);
         Button b = Button.builder(Component.literal(tab == t ? "§f" + title : "§7" + title), btn -> {
            tab = t;
            rowScroll = 0;
            stash();
            rebuildWidgets();
         }).bounds(x + i * (tabW + 2), L.tabsY(), tabW, 18).build();
         b.active = tab != t;
         addRenderableWidget(b);
      }

      List<Row> rows = rows();
      rowCount = rows.size();
      int visible = L.visibleRows();
      // The gutter is only reserved when the list actually scrolls, so a short list keeps the full
      // width for its names rather than leaving a permanent empty stripe.
      boolean scrolls = rows.size() > visible;
      int rowW = w - (scrolls ? WishLayout.SCROLL_W + 3 : 0);
      rowScroll = Math.max(0, Math.min(rowScroll, Math.max(0, rows.size() - visible)));
      for (int i = 0; i < visible && rowScroll + i < rows.size(); i++) {
         Row r = rows.get(rowScroll + i);
         int y = L.listY() + i * L.rowH();
         icons.add(new IconAt(r.icon(), x + 3, y + 5));
         boolean chosen = PICKS.stream().anyMatch(p -> p.label().equals(r.label()));
         // Two pixels of the row height are given up as a gap so consecutive rows read as separate
         // things rather than one striped block.
         Button rowButton = Button.builder(Component.literal(""), b -> toggle(r))
               .bounds(x + 22, y, rowW - 22, L.rowH() - 3).build();
         // THE NOTE, ON HOVER — drawn by renderRowNote, NOT as a vanilla widget tooltip. Vanilla puts a
         // tooltip next to the pointer, and the notes are paragraphs: wrapped at vanilla's width they
         // came out taller than the list and covered the very rows being chosen between.
         addRenderableWidget(rowButton);
         // The label text is drawn in render() rather than as the button's own text, because a
         // vanilla button centres one line and these rows are two lines, left-aligned.
         rowText.add(new RowText(r, x + 26, y + 4, rowW - 30, chosen));
      }
      // THE ▲▼ BUTTONS WERE REMOVED IN 1.39.1 BECAUSE THEY COULD NOT BE CLICKED.
      //
      // They were placed at (x + w - 40, tabsY) and (x + w - 20, tabsY) — the tab row — and the
      // four tabs are laid out across the FULL width of the same row. So both buttons sat directly
      // on top of the last tab (then "Nether · End", now "Nether"), and whichever widget vanilla hit-tested
      // first won. In practice that meant the tab was unreachable and the arrows did the scrolling
      // of a list the player was trying to switch away from.
      //
      // Nothing replaces them: the mouse wheel already scrolls this list (see mouseScrolled), the
      // scrollbar drawn down the gutter shows there is more, and the "1–10 of 43 · scroll ▲▼" line
      // under the list says so in words. Three ways of communicating one thing were already two
      // too many; the one that was breaking a tab is the one that goes.
   }

   /** The "Searching for" column: what is chosen, the must-have toggle, the counter, Search. */
   private void initRightPanel(WishLayout L) {
      int x = L.rightX();
      int w = L.rightW();
      int y = L.rightListY();
      List<Integer> links = liveLinks();
      int listBottom = L.rightListBottom();
      for (Pick p : List.copyOf(PICKS)) {
         if (y + L.pickH() > listBottom) {
            break;
         }
         // Size, only where a size control would actually do something: the biome has to be one
         // BiomeShape measured, and its two bands have to be far enough apart to separate.
         if (p.biome() && !p.negated() && BiomeShape.tiered(biomeIdOf(p.label()))) {
            addRenderableWidget(Button.builder(Component.literal(sizeMark(p.size())), b -> {
               replace(p, p.withSize(switch (p.size()) {
                  case ANY -> BiomeShape.Size.LARGE;
                  case LARGE -> BiomeShape.Size.SMALL;
                  case SMALL -> BiomeShape.Size.ANY;
               }));
            }).bounds(x + w - 58, y, 18, 18).build());
         } else if (!p.biome() && !p.negated()) {
            // HOW MANY. Same slot the size control uses on a biome row, because no row is ever both
            // — a biome has a size and no count, a structure has a count and no size. Cycling
            // rather than typing keeps it to one 18px button, and the choices are capped at what
            // the search can actually deliver: several of a row that names a building inside means
            // several assemblies, so those stop lower.
            addRenderableWidget(Button.builder(Component.literal(
                  p.count() > 1 ? "§b×" + p.count() : "×1"), b -> {
               replace(p, p.withCount(nextCount(p)));
            }).bounds(x + w - 58, y, 18, 18).build());
            // SPAWN INSIDE, offered only where it can be answered: an overworld structure, and not
            // the slime row (a chunk has no footprint to stand in).
            if (!p.slime() && insideCapable(p.label())) {
               addRenderableWidget(Button.builder(Component.literal(p.inside() ? "§a⌂" : "§8⌂"),
                     b -> replace(p, p.withInside(!p.inside())))
                     .bounds(x + w - 78, y, 18, 18).build());
            }
         }
         // "⇄" rather than a link emoji: it is inside the BMP, which is all Minecraft's bundled
         // unicode font covers, so it cannot come out as an empty box on someone's install.
         addRenderableWidget(Button.builder(Component.literal(
               p.link() != 0 ? "§b⇄" : p.label().equals(linkPending) ? "§e⇄" : "⇄"),
               b -> clickLink(p)).bounds(x + w - 38, y, 18, 18).build());
         addRenderableWidget(Button.builder(Component.literal("✖"), b -> {
            unlink(p);
            PICKS.remove(p);
            if (p.label().equals(linkPending)) {
               linkPending = null;
            }
            stash();
            rebuildWidgets();
         }).bounds(x + w - 18, y, 18, 18).build());
         y += L.pickH();
      }

      // ── The "next to" pairs, each with its own distance ───────────────────────────────────────
      // Capped at what the layout says fits. On a window too short for all of them the extras are
      // left undrawn rather than stacked upward over the list — they are still searched, and the
      // count line below says how many are hidden.
      int ly = L.linkY() + 8;
      for (int id : links.subList(0, Math.min(links.size(), L.visibleLinks()))) {
         final int linkId = id;
         int within = LINK_WITHIN.getOrDefault(id, BiomeShape.NEXT_TO_BIOME);
         addRenderableWidget(Button.builder(Component.literal("§f" + within), b -> {
            LINK_WITHIN.put(linkId, nextWithin(within));
            stash();
            rebuildWidgets();
         }).bounds(x + w - 62, ly, 44, 18).build());
         addRenderableWidget(Button.builder(Component.literal("✖"), b -> {
            for (Pick p : List.copyOf(PICKS)) {
               if (p.link() == linkId) {
                  replaceQuiet(p, p.withLink(0));
               }
            }
            LINK_WITHIN.remove(linkId);
            stash();
            rebuildWidgets();
         }).bounds(x + w - 18, ly, 18, 18).build());
         ly += L.pickH();
      }

      // The toggle GOVERNS THE NEXT PICK, so it sits under the list it feeds rather than at the
      // top: you look at what you have, then decide what the next click means.
      addRenderableWidget(Button.builder(Component.literal(pickNegated
            ? "§cMust NOT have" : "§aMust have"), b -> {
         pickNegated = !pickNegated;
         stash();
         rebuildWidgets();
      }).bounds(x, L.toggleY(), w, TOP_H).build());

      Button search = Button.builder(Component.literal("🔍 Search"), b -> startSimple())
            .bounds(x, L.bottomY(), w, TOP_H).build();
      search.active = !PICKS.isEmpty();
      addRenderableWidget(search);
   }

   /** Rebuilds only what the filter changed. rebuildWidgets() from inside a responder would
    *  dispose the very EditBox that is mid-keystroke, so the whole screen is rebuilt on the next
    *  frame instead — see {@link #filterDirty}. */
   private void rebuildRowsOnly() {
      filterDirty = true;
   }

   private boolean filterDirty;

   /**
    * The rows the left panel shows: this tab, matching the filter, in catalog order.
    *
    * Category is DERIVED rather than listed. A village row is one whose every structure id is a
    * village — which is true of all thirteen of them and would stay true if a fourteenth were
    * added tomorrow — and the deep tab is simply anything not in the overworld. Nothing here has
    * to be updated when the catalog grows, which is the only kind of grouping worth having when
    * the catalog is the thing that keeps growing.
    */
   private List<Row> rows() {
      List<Row> out = new ArrayList<>();
      String f = filterDraft.trim().toLowerCase(Locale.ROOT);
      if (tab == Tab.BIOME) {
         List<Identifier> biomes = SeedCatalogCache.biomeList();
         if (biomes == null) {
            return out;
         }
         for (Identifier id : biomes) {
            String label = SeedCatalogCache.prettify(id.getPath());
            if (!f.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(f)) {
               continue;
            }
            // Same rule as the structure rows: a real measurement or nothing at all. BiomeShape
            // only covers the overworld, so a Nether biome prints a blank second line rather
            // than a rate borrowed from a dimension nobody measured.
            BiomeShape.Shape s = BiomeShape.of(id);
            String line2 = "";
            boolean slow = false;
            if (s != null) {
               // "of seeds" and the reach it was measured at, because the two are inseparable —
               // 38% within 2000 blocks is a different claim from 38% within 100.
               line2 = s.nearPct() + "% of seeds (within 2000)";
               if (s.tiered()) {
                  line2 += " · size ✔";
               }
               slow = s.nearPct() <= 40;
               if (slow) {
                  line2 += " §c· slow search";
               }
            }
            out.add(new Row(label, true, label, line2, SeedIcons.biome(id.getPath()), slow,
                  biomeNote(label, s)));
         }
         return out;
      }
      List<SeedCriteria.StructureTarget> cat = SeedCatalogCache.list();
      if (cat == null) {
         return out;
      }
      for (SeedCriteria.StructureTarget t : cat) {
         if (t.special != SeedCriteria.Special.NORMAL) {
            continue;
         }
         Tab mine = t.dim != SeedCriteria.Dim.OVERWORLD ? Tab.DEEP
               : isVillageRow(t) ? Tab.VILLAGE : Tab.STRUCTURE;
         if (mine != tab) {
            continue;
         }
         String short1 = t.shortName();
         if (!f.isEmpty() && !t.label.toLowerCase(Locale.ROOT).contains(f)
               && !short1.toLowerCase(Locale.ROOT).contains(f)) {
            continue;
         }
         // NOTHING rather than a guess. rarityPct is -1 for every row this project has not
         // actually measured, and a plausible invented number is the one thing that would make
         // this line worse than blank — it is what a player uses to decide whether to wait.
         boolean slow = t.rarityPct >= 0 && t.rarityPct <= 5;
         String line2 = t.rarityPct < 0 ? "" : t.rarityPct + "% have it";
         if (slow) {
            line2 = line2 + " §c· slow search";
         }
         out.add(new Row(t.label, false, short1, line2, SeedIcons.structure(t.label), slow,
               structureNote(t)));
      }
      // THE ONE ROW THAT IS NOT A STRUCTURE. It lives on the Structures tab because that is where
      // someone looking for "somewhere to build a farm" would go, and it costs nothing to check —
      // no world is generated at all, just a hash of the seed. Its second line states the rate
      // rather than a rarity, because 1 in 10 is the fact that decides whether the ask is worth
      // making and it is the same in every seed.
      if (tab == Tab.STRUCTURE && (f.isEmpty() || SlimeChunks.ROW_LABEL.toLowerCase(Locale.ROOT).contains(f)
            || "slime".contains(f))) {
         // "free to check" dropped in 1.39.1. It was true and it was noise: nothing in this picker
         // costs the player anything, so saying it about one row implied the others did.
         out.add(new Row(SlimeChunks.ROW_LABEL, false, SlimeChunks.ROW_LABEL,
               "1 chunk in " + SlimeChunks.ONE_IN,
               SeedIcons.structure(SlimeChunks.ROW_LABEL), false,
               "Chunks where slimes spawn underground, whatever the biome. One chunk in "
               + SlimeChunks.ONE_IN + ", decided by a hash of the seed — so this costs nothing to "
               + "check and is certain. Measured from your spawn within " + SlimeChunks.PICKER_WITHIN
               + " blocks, which is the widest reach where asking for more of them still narrows "
               + "anything: past about 96 blocks every seed has plenty. Use the count button to ask "
               + "for more than one."));
      }
      return out;
   }

   /**
    * The full explanation for a structure row: what it promises, and what it cannot.
    *
    * Both halves, because the row's own {@code cant} is the part a player most needs before
    * committing to a long search — "the BUILDING is certain, the villager's trades are not" is the
    * difference between a search that will satisfy them and one that will not.
    */
   private static String structureNote(SeedCriteria.StructureTarget t) {
      StringBuilder sb = new StringBuilder();
      if (!t.note.isEmpty()) {
         sb.append(t.note);
      }
      if (!t.cant.isEmpty()) {
         if (sb.length() > 0) {
            sb.append("\n\n");
         }
         sb.append("§6Can't check: §f").append(t.cant);
      }
      if (sb.length() == 0) {
         // A plain row with nothing to warn about. Saying so beats an empty tooltip, which reads as
         // a row whose description failed to load.
         sb.append("Any ").append(t.label.toLowerCase(Locale.ROOT))
               .append(". Found from the game's own placement rules, so its position is certain.");
      }
      return sb.toString();
   }

   /** The same for a biome row, built from what BiomeShape actually measured. */
   private static String biomeNote(String label, BiomeShape.Shape shape) {
      if (shape == null) {
         // Nether biomes: BiomeShape only ever covered the overworld, and inventing a rate for the
         // others is exactly what the rest of this project refuses to do.
         return label + ". How often this is near spawn has not been measured for this dimension, "
               + "so no rate is shown — the search itself works normally.";
      }
      StringBuilder sb = new StringBuilder(label + " within reach of spawn in about ")
            .append(shape.nearPct()).append("% of seeds (measured at 2000 blocks).");
      if (shape.tiered()) {
         sb.append("\n\nSize works on this one: LARGE means at least ").append(shape.largeMin())
               .append(" blocks across and SMALL at most ").append(shape.smallMax())
               .append(" — both measured against THIS biome's own sizes, not a global number, so "
                     + "\"large\" means large for a ").append(label.toLowerCase(Locale.ROOT))
               .append(".");
      }
      if (shape.nearPct() <= 40) {
         sb.append("\n\n§6Expect a longer search: §fthis one is near spawn in well under half of seeds.");
      }
      return sb.toString();
   }

   /** Every structure id in this row is a village — true of all thirteen village rows, and it
    *  stays true for a fourteenth without anyone editing a list. */
   private static boolean isVillageRow(SeedCriteria.StructureTarget t) {
      if (t.wanted.isEmpty()) {
         return false;
      }
      for (Identifier id : t.wanted) {
         if (!id.getPath().startsWith("village")) {
            return false;
         }
      }
      return true;
   }

   // ── "Next to" plumbing ───────────────────────────────────────────────────────────────────────

   /** Link ids that still have BOTH ends chosen. A pick removed out from under a link leaves the
    *  other half dangling, and a half a pair is not a pair — it is silently dropped here rather
    *  than searched as something the player never asked for. */
   private static List<Integer> liveLinks() {
      java.util.Map<Integer, Integer> counts = new java.util.LinkedHashMap<>();
      for (Pick p : PICKS) {
         if (p.link() != 0) {
            counts.merge(p.link(), 1, Integer::sum);
         }
      }
      List<Integer> out = new ArrayList<>();
      for (var e : counts.entrySet()) {
         if (e.getValue() == 2) {
            out.add(e.getKey());
         }
      }
      return out;
   }

   private static List<Pick> endsOf(int linkId) {
      List<Pick> out = new ArrayList<>(2);
      for (Pick p : PICKS) {
         if (p.link() == linkId) {
            out.add(p);
         }
      }
      return out;
   }

   /**
    * The 🔗 button: first click arms this pick, second click on a different one pairs them.
    *
    * Refusals are spelled out rather than being a button that does nothing. Two structures cannot
    * be paired because the search anchors on a biome patch — that is where the measurement said the
    * distance has to be taken from — and a must-NOT-have end has no position at all to be next to.
    */
   private void clickLink(Pick p) {
      stash();
      if (p.link() != 0) {
         unlink(p);
         feedback = "";
         rebuildWidgets();
         return;
      }
      if (p.negated()) {
         feedback = "\"Next to\" needs two things that must BE there — a must-not-have has nothing to measure from.";
         rebuildWidgets();
         return;
      }
      if (linkPending == null) {
         linkPending = p.label();
         feedback = "Now click ⇄ on the thing it should be next to.";
         rebuildWidgets();
         return;
      }
      Pick first = PICKS.stream().filter(q -> q.label().equals(linkPending)).findFirst().orElse(null);
      linkPending = null;
      if (first == null || first.label().equals(p.label())) {
         feedback = "";
         rebuildWidgets();
         return;
      }
      // TWO STRUCTURES IS A REAL PAIR and this used to refuse it, with a reason that was not true:
      // "one end has to be a biome — the distance is measured from the biome patch". The engine has
      // supported structure-to-structure since 1.38 (SeedCriteria.addStructurePair), the AI path has
      // been using it, and when the anchor is a structure the distance is measured from the one the
      // funnel already found — which is CHEAPER than a biome anchor, not impossible. So "fortress
      // next to bastion" worked if you typed it and was refused if you clicked it.
      //
      // What genuinely cannot be paired is asked properly instead, from the same code the search
      // uses, so the picker and the engine cannot give different answers — and it is asked NOW,
      // before a link is drawn, rather than at Search time after the player has built a wish around
      // it. The commonest case is fortress + bastion: one structure set, so the world never puts
      // both in the same region.
      if (!first.biome() && !p.biome()) {
         SeedCriteria.StructureTarget a = structOf(first.label());
         SeedCriteria.StructureTarget b = structOf(p.label());
         String refusal = SeedCriteria.whyNotStructurePair(a, b);
         if (refusal != null) {
            feedback = refusal;
            rebuildWidgets();
            return;
         }
      }
      int id = nextLinkId++;
      // Structures are sparser than biomes, so any pair with a structure in it starts wider. Both
      // numbers are the p75 of the real nearest-neighbour distance measured over 60 seeds.
      LINK_WITHIN.put(id, first.biome() && p.biome()
            ? BiomeShape.NEXT_TO_BIOME : BiomeShape.NEXT_TO_STRUCTURE);
      replaceQuiet(first, first.withLink(id));
      replaceQuiet(p, p.withLink(id));
      feedback = "";
      rebuildWidgets();
   }

   /** Clears a link and its partner, so a pair never survives with one end. */
   private static void unlink(Pick p) {
      if (p.link() == 0) {
         return;
      }
      int id = p.link();
      for (Pick q : List.copyOf(PICKS)) {
         if (q.link() == id) {
            replaceQuiet(q, q.withLink(0));
         }
      }
      LINK_WITHIN.remove(id);
   }

   private static void replaceQuiet(Pick oldPick, Pick newPick) {
      int i = PICKS.indexOf(oldPick);
      if (i >= 0) {
         PICKS.set(i, newPick);
      }
   }

   private void replace(Pick oldPick, Pick newPick) {
      stash();
      replaceQuiet(oldPick, newPick);
      rebuildWidgets();
   }

   private static int nextWithin(int current) {
      int[] opts = BiomeShape.NEXT_TO_CHOICES;
      for (int i = 0; i < opts.length; i++) {
         if (opts[i] == current) {
            return opts[(i + 1) % opts.length];
         }
      }
      return opts[0];
   }

   /**
    * The next value the count button offers, which is not the same ladder for every row.
    *
    * A slime row steps 1, 2, 3, 4, 6, 8 — a wide range is useful there because slime chunks are
    * cheap to count and the interesting asks are the high ones (at 64 blocks, 3 is 94% of seeds and
    * 8 is 11%). A structure steps by one to its own cap, which is low, because each extra one is
    * another confirm and on a content row another ~700ms assembly.
    */
   private static int nextCount(Pick p) {
      if (p.slime()) {
         int[] opts = SlimeChunks.COUNT_CHOICES;
         for (int i = 0; i < opts.length; i++) {
            if (opts[i] == p.count()) {
               return opts[(i + 1) % opts.length];
            }
         }
         return opts[0];
      }
      int cap = maxCountFor(p.label());
      return p.count() >= cap ? 1 : p.count() + 1;
   }

   /**
    * The largest count this row can be asked for, which is NOT the same for every row.
    *
    * A row naming a building inside assembles the structure for each one it has to find, at about
    * 700ms a time, so its ceiling is the same {@link WishParser#MAX_COUNT_CONTENT} the AI path
    * enforces — the picker must not be a way around a limit that exists for a measured reason.
    */
   private static int maxCountFor(String label) {
      SeedCriteria.StructureTarget t = structOf(label);
      return t != null && t.needsAssembly() ? WishParser.MAX_COUNT_CONTENT : WishParser.MAX_COUNT_PLAIN;
   }

   /** Can "I spawn inside this" even be asked here? Overworld only — you do not spawn in the Nether. */
   private static boolean insideCapable(String label) {
      SeedCriteria.StructureTarget t = structOf(label);
      return t != null && t.dim == SeedCriteria.Dim.OVERWORLD;
   }

   /** The catalog row behind a picker label, or null when the label is a biome or the slime row. */
   private static SeedCriteria.StructureTarget structOf(String label) {
      List<SeedCriteria.StructureTarget> cat = SeedCatalogCache.list();
      if (cat == null) {
         return null;
      }
      for (SeedCriteria.StructureTarget t : cat) {
         if (t.label.equals(label)) {
            return t;
         }
      }
      return null;
   }

   private static String sizeMark(BiomeShape.Size s) {
      return switch (s) {
         case ANY -> "§7◻";
         case SMALL -> "§b▪";
         case LARGE -> "§a▮";
      };
   }

   /** The biome id behind a picker label, or null when the label is a structure. */
   private static Identifier biomeIdOf(String label) {
      List<Identifier> biomes = SeedCatalogCache.biomeList();
      if (biomes == null) {
         return null;
      }
      for (Identifier b : biomes) {
         if (SeedCatalogCache.prettify(b.getPath()).equals(label)) {
            return b;
         }
      }
      return null;
   }

   /** Click a row: add it, or remove it if it is already chosen. */
   private void toggle(Row r) {
      stash();
      Pick existing = PICKS.stream().filter(p -> p.label().equals(r.label())).findFirst().orElse(null);
      if (existing != null) {
         // A link whose other end is about to vanish has to go with it — see liveLinks().
         unlink(existing);
         PICKS.remove(existing);
         if (r.label().equals(linkPending)) {
            linkPending = null;
         }
         // Clicking a chosen row while the toggle has since flipped CHANGES it rather than just
         // deleting it. Removing and stopping would make "turn this want into a without" take two
         // clicks, the first of which looks like it did nothing.
         if (existing.negated() == pickNegated) {
            rebuildWidgets();
            return;
         }
      }
      if (PICKS.size() >= 8) {
         feedback = "8 things at once is the limit — remove one first.";
      } else {
         PICKS.add(new Pick(r.label(), r.biome(), pickNegated));
         feedback = "";
      }
      rebuildWidgets();
   }

   private void stash() {
      if (wishBox != null) {
         wishDraft = wishBox.getValue();
      }
      if (filterBox != null) {
         filterDraft = filterBox.getValue();
      }
   }

   private void startAi() {
      stash();
      if (aiWaiting) {
         if (System.currentTimeMillis() - aiSentMs < AI_GIVE_UP_MS) {
            return;
         }
         // Past the provider's own ceiling with nothing back. Whatever happened, waiting longer is
         // not going to fix it, and refusing to let the player press the button again is the worst
         // of the available answers. The old request is CANCELLED here, not just forgotten: its
         // reply may still arrive, and it must not start a search after being reported as failed.
         aiWaiting = false;
         aiRequest++;
         feedback = "§cThe AI never answered that one. Try a shorter wish, or pick below.";
      }
      if (SeedCatalogCache.error() != null) {
         feedback = "§c" + SeedCatalogCache.error();
         return;
      }
      String wrongType = SeedAccess.worldTypeProblem(parent);
      if (wrongType != null) {
         feedback = "§c" + wrongType;
         return;
      }
      if (!SeedCatalogCache.ready()) {
         feedback = "World-gen data is still loading — try again in a second.";
         return;
      }
      String wish = wishDraft.trim();
      if (wish.isEmpty()) {
         feedback = "Type what you want first — like \"village next to a jungle\".";
         return;
      }
      // THE COMMON WISHES NEED NO AI. A wish made only of names this app knows, joined by "next to",
      // "no" or a number, is read here and searched straight away — no key, no request, no wait. The
      // AI is only asked when some word is not understood, and without a key the understood part is
      // searched with the ignored words named. See LocalWish.
      boolean hasKey = !Config.getKey(Config.Keyset.SPAWN).isEmpty();
      LocalWish.Result local = LocalWish.parse(wish);
      if (local.complete() || (!hasKey && local.found())) {
         WishParser.Outcome out = WishParser.parse(local.json().toString(), wish);
         if (out.error() != null) {
            feedback = "§c" + friendly(out.error());
            return;
         }
         feedback = "";
         String ignored = local.unknown().isEmpty() ? "" : "Didn't understand \"" + String.join(" ", local.unknown())
               + "\" without an AI key";
         String note = out.note() == null ? "" : out.note();
         searchNote = ignored.isEmpty() ? note : note.isEmpty() ? ignored : ignored + "; " + note;
         searchWarning = "";
         startSearch(out.criteria());
         rebuildWidgets();
         return;
      }
      if (!hasKey) {
         // NAMES THE BUTTON THAT IS REALLY THERE. The button says "⚙ key".
         feedback = "§eTry plain names, like \"village by a jungle\" — or add a §f⚙ key§e.";
         return;
      }
      aiWaiting = true;
      aiSentMs = System.currentTimeMillis();
      feedback = "Asking the AI to turn that into a search…";
      rebuildWidgets();
      final long mine = ++aiRequest;
      AiVision.ask(null, WishParser.buildPrompt(wish), List.of(), (String) null, Config.Keyset.SPAWN, reply ->
            Minecraft.getInstance().execute(() -> {
               // A REPLY FOR A REQUEST NOBODY IS WAITING FOR IS DROPPED (1.44.2). It used to run on
               // this screen whatever had happened since: after the screen was closed it cleared the
               // Create World seed box — including a seed the player had typed there — and started a
               // search nobody could see. Closing, giving up, or asking again all bump aiRequest. (Not
               // "is this screen showing": a reply that lands while the ⚙ key screen is open on top
               // belongs to this search and must still be used.)
               if (mine != aiRequest) {
                  return;
               }
               aiWaiting = false;
               // The player's own words go in alongside the reply, so the parser can refuse
               // requirements the model added that nobody asked for.
               WishParser.Outcome out = WishParser.parse(reply, wish);
               // Every error is now simply an error. The old code let a wish mentioning "eye" fall
               // through a failed parse into a stronghold search, which is how "a good speedrun
               // seed" ended up returning the first seed tested — there is no stronghold search
               // left to fall through to, and inventing one was never honest anyway.
               if (out.error() != null) {
                  feedback = "§c" + friendly(out.error());
               } else {
                  feedback = "";
                  searchNote = out.note() == null ? "" : out.note();
                  searchWarning = "";
                  startSearch(out.criteria());
               }
               rebuildWidgets();
            }));
   }

   private void startSimple() {
      stash();
      if (!SeedCatalogCache.ready()) {
         feedback = SeedCatalogCache.error() != null ? "§c" + SeedCatalogCache.error()
               : "World-gen data is still loading — one second.";
         return;
      }
      // THE WORLD TYPE, CHECKED ON BOTH PATHS. Every seed is tested against the default generators;
      // a world made with any other preset is a different world from the one that was searched. See
      // SeedAccess.ONLY_DEFAULT_WORLD_TYPE.
      String wrongType = SeedAccess.worldTypeProblem(parent);
      if (wrongType != null) {
         feedback = "§c" + wrongType;
         return;
      }
      SeedCriteria criteria = new SeedCriteria();
      List<SeedCriteria.StructureTarget> cat = SeedCatalogCache.list();
      List<Identifier> biomes = SeedCatalogCache.biomeList();
      searchNote = "";
      searchWarning = "";
      int radius = FableVisionConfig.seedRadius();
      // Exclusions reach further than wants for the same reason they do on the AI path: "no desert
      // near spawn" that only checks 64 blocks is a promise about the block you are standing on.
      int exRadius = Math.max(radius, 256);
      List<String> notes = new ArrayList<>();
      // The target object each pick produced, so a "next to" can be wired to the SAME instance the
      // criteria already holds. The funnel matches an adjacency's ends by identity — building a
      // second, equal-looking BiomeTarget would leave the anchor's position unfindable at run time.
      java.util.Map<String, SeedCriteria.BiomeTarget> biomeOf = new java.util.HashMap<>();
      java.util.Map<String, SeedCriteria.StructureTarget> structOf = new java.util.HashMap<>();
      for (Pick p : List.copyOf(PICKS)) {
         if (p.biome()) {
            Identifier id = biomes.stream()
                  .filter(b -> SeedCatalogCache.prettify(b.getPath()).equals(p.label()))
                  .findFirst().orElse(null);
            if (id == null) {
               continue;
            }
            // Thresholds come from BiomeShape, never from this screen: they are this biome's own
            // measured p33 and p67, and an unmeasured or single-size biome yields no bounds at all.
            int[] span = BiomeShape.bounds(id.getPath(), p.negated() ? BiomeShape.Size.ANY : p.size());
            SeedCriteria.BiomeTarget bt = new SeedCriteria.BiomeTarget(
                  net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, id),
                  p.label(), SeedCatalog.biomeDim(id), p.negated() ? exRadius : radius,
                  span[0], span[1]);
            (p.negated() ? criteria.excludeBiomes : criteria.biomes).add(bt);
            if (!p.negated()) {
               biomeOf.put(p.label(), bt);
            }
            continue;
         }
         if (p.slime()) {
            // ITS OWN FIXED REACH, deliberately not the screen's search distance — see
            // SlimeChunks.PICKER_WITHIN. Slime chunks get MORE certain as the radius grows, so
            // wiring this to the usual distance control would build a row that stops filtering
            // exactly when a player widens the search to find more seeds.
            criteria.slimes.add(new SeedCriteria.SlimeTarget(p.count(), SlimeChunks.PICKER_WITHIN));
            if (!SlimeChunks.narrows(p.count(), SlimeChunks.PICKER_WITHIN)) {
               // ℹ-MARKED: an explanation, not a part of the wish that was dropped. Unmarked, the join
               // message read it as "something dropped" and printed the long form for it.
               notes.add(WishParser.PLAIN_NOTE + "Slime chunks are 1 in 10, so " + p.count() + " within "
                     + SlimeChunks.PICKER_WITHIN + " blocks is true of nearly every seed — ask for "
                     + "more of them if you want this to narrow anything.");
            }
            continue;
         }
         SeedCriteria.StructureTarget t = cat.stream()
               .filter(c -> c.label.equals(p.label())).findFirst().orElse(null);
         if (t == null || t.special == SeedCriteria.Special.DUNGEON) {
            continue;
         }
         if (!p.negated()) {
            SeedCriteria.StructureTarget sized = t.withRadius(radius).withCount(p.count());
            if (p.inside() && sized.dim == SeedCriteria.Dim.OVERWORLD) {
               sized = sized.insideSpawn();
            }
            criteria.structures.add(sized);
            structOf.put(p.label(), sized);
            // NO "can't check" NOTE HERE. Every row's cant is about LOOT — "what the armorer
            // villager ends up trading", "what's in the basement chest" — and clicking a row in a
            // list is not asking about loot. Answering a question nobody asked reads as a warning
            // that something is wrong with the search, which is the opposite of the truth: the row
            // does exactly what its name says. These notes exist for the WISH path, where the
            // player really did type "a chest full of diamonds" and deserves an answer about it.
            continue;
         }
         // Same rule the picker's own toggle uses: a row demanding a piece flips that piece, the
         // abandoned-village row flips to a normal start, and anything else means "none of these
         // near spawn at all".
         SeedCriteria.StructureTarget flipped = t.negated();
         if (flipped != null) {
            criteria.structures.add(flipped.withRadius(radius));
         } else {
            criteria.excludeStructures.add(t.withRadius(exRadius));
         }
      }
      // ── Wire the "next to" pairs ──────────────────────────────────────────────────────────────
      // The anchor is whichever end is a biome (both, when both are), because the distance is
      // measured from a biome patch. A refusal from addAdjacency is shown rather than swallowed:
      // "plains next to plains" is the case that has to be said out loud, not quietly dropped.
      for (int id : liveLinks()) {
         List<Pick> ends = endsOf(id);
         Pick e0 = ends.get(0);
         Pick e1 = ends.get(1);
         int within = LINK_WITHIN.getOrDefault(id,
               e0.biome() && e1.biome() ? BiomeShape.NEXT_TO_BIOME : BiomeShape.NEXT_TO_STRUCTURE);
         String err;
         if (!e0.biome() && !e1.biome()) {
            // BOTH ENDS ARE STRUCTURES. This branch did not exist, so even after the click was
            // allowed the pair would have been silently dropped here — the loop below only ever
            // looked up a BIOME anchor and skipped anything else. Measured from the structure the
            // funnel already located, which costs nothing extra.
            SeedCriteria.StructureTarget a = structOf.get(e0.label());
            SeedCriteria.StructureTarget b = structOf.get(e1.label());
            if (a == null || b == null) {
               continue;   // a "must NOT have" end, which has no position to measure from
            }
            err = criteria.addStructurePair(a, b, within);
         } else {
            // The anchor is whichever end is a biome (either, when both are), because that is the
            // patch the distance is measured out from.
            Pick anchorPick = e0.biome() ? e0 : e1;
            Pick otherPick = anchorPick == e0 ? e1 : e0;
            SeedCriteria.BiomeTarget anchor = biomeOf.get(anchorPick.label());
            if (anchor == null) {
               continue;
            }
            err = criteria.addAdjacency(anchor,
                  otherPick.biome() ? biomeOf.get(otherPick.label()) : null,
                  otherPick.biome() ? null : structOf.get(otherPick.label()), within);
         }
         if (err != null) {
            feedback = "§c" + err;
            return;
         }
      }
      searchNote = String.join("; ", notes);
      if (criteria.isEmpty()) {
         feedback = "Pick a structure or a biome first (or use the AI box).";
         return;
      }
      startSearch(criteria);
   }

   /**
    * Works out which item is holding the search up, on its own daemon thread.
    *
    * Off the render thread because it runs real world-gen (a few hundred seed evaluations), and at
    * MIN_PRIORITY because the actual search is still going and must not be slowed down by the
    * report about it. 300 seeds is enough to separate "rare" from "never" without taking long.
    */
   /** The rarity half of the search ETA, measured off-thread when the search starts. Volatile: the
    *  estimator thread writes it and the render thread reads it. */
   private volatile SeedFinder.Estimate estimate;
   private volatile boolean estimating;
   /** Set when the estimate could not be worked out, so the screen SAYS so instead of going quiet —
    *  a missing line was indistinguishable from one that simply had not arrived yet. */
   private volatile boolean estimateFailed;

   private void startEstimate(SeedFinder.Job job) {
      estimate = null;
      estimateFailed = false;
      estimating = true;
      Thread t = new Thread(() -> {
         try {
            // Capped at 8 seconds in total, and dropped the moment the search it describes is over or
            // the player has moved on: an estimate is worth one core for a few seconds, never more.
            SeedFinder.Estimate e = SeedFinder.estimate(job.criteria, WorldgenContext.get(registries), job.fast,
                  1500, 8_000, () -> myJob != job || job.done());
            if (myJob == job) {
               estimate = e;
               if (e != null) {   // what the search watchdog measures "far past the estimate" against
                  job.expectedRough = e.rough();
                  job.expectedChance = e.chance();
               }
               // Null because the SEARCH finished first is not a failure to report — there is nothing
               // left to estimate. Null with the search still running is.
               estimateFailed = e == null && !job.done();
            }
         } catch (Throwable failed) {
            // A failed estimate must never disturb the search it is estimating — but it is logged
            // and said on screen, which is how the structure-pair crash above would have been seen.
            FableVisionClient.LOGGER.warn("Search estimate failed", failed);
            if (myJob == job) {
               estimateFailed = true;
            }
         } finally {
            estimating = false;
         }
      }, "FableVision-Estimate");
      t.setDaemon(true);
      t.setPriority(Thread.MIN_PRIORITY);
      t.start();
   }

   /**
    * "About 1 in 40,000 seeds — about 15 s at this speed." Rarity times measured speed, so a 2% mansion
    * room says what it costs before anyone waits for it. Null while there is nothing honest to say.
    */
   static String etaLine(SeedFinder.Estimate e, long ratePerSec, long checked) {
      if (e == null) {
         return null;
      }
      long per = e.seedsPerMatch();
      String seeds = per == Long.MAX_VALUE ? "?" : String.format("%,d", per);
      StringBuilder sb = new StringBuilder(e.atMost() ? "§eRarer than 1 in " + seeds + " seeds" : "§7Expect about 1 in " + seeds + " seeds");
      if (ratePerSec > 0 && per != Long.MAX_VALUE) {
         sb.append(e.atMost() ? " — at least " : " — about ").append(duration(per / (double) ratePerSec)).append(" at this speed");
      }
      if (e.rough()) {
         sb.append(" §8(rough)");
      }
      if (per != Long.MAX_VALUE && checked > per * 4 && !e.atMost()) {
         sb.append(" §8· taking longer than the estimate, which is a rough guide");
      }
      return sb.toString();
   }

   static String duration(double seconds) {
      if (seconds < 1) {
         return "under a second";
      }
      if (seconds < 90) {
         return Math.round(seconds) + " s";
      }
      if (seconds < 5400) {
         return Math.round(seconds / 60) + " min";
      }
      if (seconds < 172_800) {
         return Math.round(seconds / 3600) + " h";
      }
      return Math.round(seconds / 86_400) + " days";
   }

   private void startBottleneckScan() {
      SeedFinder.Job job = myJob;
      if (job == null) {
         return;
      }
      Thread t = new Thread(() -> {
         try {
            bottleneck = SeedFinder.findBottleneck(job.criteria, WorldgenContext.get(registries), job.fast, 300);
         } catch (Throwable ignored) {
            // A failed explanation must never disturb the search it is explaining.
         }
      }, "FableVision-Bottleneck");
      t.setDaemon(true);
      t.setPriority(Thread.MIN_PRIORITY);
      t.start();
   }

   /**
    * Warns, once, when a Nether row is being searched at a distance that is genuinely rare.
    *
    * THIS IS WHAT REPLACED THE SILENT WIDENING. Until 1.41.4 a Nether row asked for 100 blocks was
    * quietly searched at 208, because fortress and bastion share one structure set — about one of
    * them per 432-block region — and a tight ask can run for a very long time. The floor made those
    * searches finish by answering a different question, and the player got a fortress at 200 with a
    * control that said 100.
    *
    * The distance is honoured now, so the search may take a while or find nothing, and the honest
    * thing is to say so BEFORE the wait rather than to shorten it by changing the wish. That is the
    * whole trade: the app gives up the right to move the goalposts and takes on the duty to warn.
    */
   private void noteTightNether(SeedCriteria criteria) {
      for (SeedCriteria.StructureTarget t : criteria.structures) {
         if (t.dim == SeedCriteria.Dim.NETHER && t.radius < SeedCriteria.NETHER_TIGHT) {
            String note = WishParser.PLAIN_NOTE + "Nether structures are spread about one per 432 "
                  + "blocks, so " + t.radius + " blocks from the portal-in point is a rare ask — "
                  + "this may run a long time. It is searched exactly as asked.";
            searchWarning = note;
            return;
         }
      }
   }

   private void startSearch(SeedCriteria criteria) {
      searchNote = JoinForm.slimeNoteOnce(searchNote);
      seedApplied = false;
      bottleneck = null;
      bottleneckStarted = false;
      // The third place that used to volunteer the chosen rows' loot warnings, and the last one
      // removed. Whatever note is showing now came either from the AI (which writes it about the
      // player's own words) or from something the app really did drop from the wish.
      // THE STALE SEED IS CLEARED BEFORE THE NEW SEARCH, and this is the fix for "I ran it again and
      // got the same seed".
      //
      // The search itself has always been random, so it was never returning the same world twice.
      // What it could do was leave the PREVIOUS result sitting in the Create World seed box with the
      // green badge still next to it: start a second search, stop it or back out before it finished,
      // and the screen you return to still says "✓ custom spawn" and still holds the old seed. Press
      // Create at that point and you get the old world, having every reason to believe it was a
      // fresh result. Clearing both means an unfinished search leaves an empty box, which is the
      // truth about what it found.
      lastAppliedSeed = null;
      lastAppliedFinds = List.of();
      SeedBox.put(parent, "");
      noteTightNether(criteria);
      myJob = SeedFinder.start(criteria, registries, 1, FableVisionConfig.seedFastMode);
      if (myJob != null) {
         startEstimate(myJob);
      }
      searchStartMs = System.currentTimeMillis();
      rateAtMs = 0;
      rebuildWidgets();
   }

   private void back() {
      stash();
      aiRequest++;   // an AI reply still on its way belongs to a screen that is gone — see startAi
      if (aiWaiting) {
         cancelledWish = wishDraft;
      }
      aiWaiting = false;
      SeedFinder.stop();
      suppressNext();
      this.minecraft.setScreen(parent);
   }

   @Override
   public void onClose() {
      back();
   }

   /**
    * The hovered row's note, placed BESIDE the list rather than on top of it.
    *
    * The notes are paragraphs ("The treasure bastion — the square one built round a lava basin…"), and
    * as a vanilla tooltip they were wrapped next to the pointer and came out taller than the list, so
    * the explanation of one row hid all the others — the rows the player was choosing between. Now the
    * box is wrapped to the RIGHT column's width and pinned there, level with the hovered row and nudged
    * up only as far as the window needs. It covers the "Searching for" list for as long as the pointer
    * is on a row, which is the column not being read at that moment.
    */
   private void renderRowNote(GuiGraphicsExtractor g, WishLayout L, int mouseX, int mouseY) {
      RowText over = null;
      for (RowText rt : rowText) {
         // The row's button (initLeftPanel) starts 4 pixels left of its text, is 8 wider, and one row tall.
         if (mouseX >= rt.x() - 4 && mouseX < rt.x() - 4 + rt.w() + 8 && mouseY >= rt.y() - 4
               && mouseY < rt.y() - 4 + L.rowH() - 3) {
            over = rt;
         }
      }
      if (over == null || over.row().note().isEmpty()) {
         return;
      }
      int wrapW = Math.max(80, L.rightW() - 10);
      List<net.minecraft.util.FormattedCharSequence> lines =
            this.font.split(Component.literal(over.row().note()), wrapW);
      final int rowTop = over.y() - 4;
      final int boxX = L.rightX();
      g.setTooltipForNextFrame(this.font, lines, (screenW, screenH, x, y, tipW, tipH) -> {
         // Level with the row, then pulled up just enough to stay on screen. The tooltip renderer adds
         // its own border, so a few pixels are kept clear of the edges.
         int top = Math.max(4, Math.min(rowTop, screenH - tipH - 4));
         int left = Math.min(boxX, screenW - tipW - 4);
         return new org.joml.Vector2i(left, top);
      }, mouseX, mouseY, false);
   }

   /**
    * Everything the two-panel picker draws that is not a widget: the title, the two-line rows, the
    * chosen list, and the counter.
    */
   private void renderPickPhase(GuiGraphicsExtractor g) {
      WishLayout L = lay != null ? lay : layout();
      int rightX = L.rightX();

      // THE TITLE SAYS WHAT THIS IS, not just what it is called. "Custom spawn" on its own tells a
      // reader nothing about which world is being acted on, and that ambiguity is what got the mod
      // rejected once. "for the NEW world you're creating" is six words and removes it.
      //
      // AND IT FITS ITS OWN SPACE (1.43.2). It shares a line with the Within and Mode buttons and used
      // to be drawn at full length whatever was beside it, so a small window drew all three on top of
      // each other. The layout says how many pixels are left before the first button; the longest
      // wording that fits them is drawn, and every wording keeps "NEW world".
      String title = WishLayout.pickTitle(this.font::width, L.titleW(FableVisionConfig.seedFastMode));
      g.text(this.font, Component.literal(title), L.leftX(), L.titleY(), 0xFFFFFFFF);

      // ── The browse list ───────────────────────────────────────────────────────────────────────
      for (RowText rt : rowText) {
         Row r = rt.row();
         int tick = rt.chosen() ? this.font.width("✔ ") : 0;
         g.text(this.font, Component.literal((rt.chosen() ? "§a✔ §f" : "§f") + fit(r.line1(), rt.w() - tick)),
               rt.x(), rt.y(), 0xFFFFFFFF);
         if (!r.line2().isEmpty()) {
            // §7 and a light explicit colour. This line used to be §8 (dark grey) drawn on a dark
            // background, which is the one piece of text on the screen a player actually reads a
            // number off — "30% have it" is how they decide whether a search is worth starting, and
            // it was the least legible thing on the panel.
            // line2 already carries a §c for the "slow search" tail, so it is coloured then fitted
            // against the raw text length — close enough, since the code adds no visible width.
            g.text(this.font, Component.literal("§7" + fit(r.line2(), rt.w())), rt.x(), rt.y() + 12, 0xFFB9C0C8);
         }
      }
      if (rowText.isEmpty()) {
         g.text(this.font, Component.literal("§7" + (SeedCatalogCache.ready()
                     ? widest(L.leftW() - 4, "Nothing matches that filter.", "No matches.")
                     : widest(L.leftW() - 4, "Loading world-gen data…", "Loading…"))),
               L.leftX() + 4, L.listY() + 4, 0xFFB9C0C8);
      }
      drawScrollbar(g, L);

      // ── The right column ──────────────────────────────────────────────────────────────────
      g.text(this.font, Component.literal("§7Searching for"), rightX, L.rightHeaderY() + 4, 0xFFAAAAAA);
      int y = L.rightListY();
      List<Integer> links = liveLinks();
      int listBottom = L.rightListBottom();
      if (PICKS.isEmpty()) {
         g.text(this.font, Component.literal("§7" + widest(L.rightW() - 2,
                     "Nothing yet — click a row on the left.", "Nothing yet — pick a row",
                     "Nothing picked yet")), rightX, y + 2, 0xFF9AA2AA);
      }
      for (Pick p : List.copyOf(PICKS)) {
         if (y + L.pickH() > listBottom) {
            g.text(this.font, Component.literal("§7…more"), rightX, y + 2, 0xFF9AA2AA);
            break;
         }
         // WHAT THE FIRST LINE HAS TO STAY CLEAR OF: up to four 18-pixel buttons, the leftmost of
         // which starts at w - 78. It used to reserve 62, which is three of them — so on any pick
         // offering "spawn inside" the name was drawn under that fourth button. The second line no
         // longer reserves anything, because it now sits below the whole button band (see PICK_H).
         int textW = L.rightW() - 80;
         int detailW = L.rightW() - 12;
         // "Larger"/"Smaller", not "Big"/"Small". A large pale garden is 96 blocks across, which is
         // nobody's idea of big — the tier is a COMPARISON within one biome, and a word that sounds
         // absolute promises something the measurement does not support. The span on the line below
         // says what it really means in blocks.
         String sizeWord = p.size() == BiomeShape.Size.LARGE ? "Larger " : p.size() == BiomeShape.Size.SMALL ? "Smaller " : "";
         String sizeCol = p.size() == BiomeShape.Size.LARGE ? "§a" : "§b";
         int used = this.font.width("+ " + sizeWord);
         // THE NAME COMES FIRST WHEN THERE IS ONLY ROOM FOR ONE OF THEM. On a narrow window the
         // column is 132 pixels, the three little buttons take 62, and "Smaller " takes another 48
         // — which left one character of the thing the player actually picked. The size is on the
         // second line of the same row in blocks, so dropping the word here loses nothing; dropping
         // the name loses the row.
         if (textW - used < NAME_FLOOR) {
            sizeWord = "";
            used = this.font.width("+ ");
         }
         g.text(this.font, Component.literal((p.negated() ? "§c− " : "§a+ ")
               + sizeCol + sizeWord + "§f" + fit(shortOf(p), textW - used)), rightX, y + 2, 0xFFFFFFFF);
         g.text(this.font, Component.literal("§7" + fit(secondLine(p, detailW), detailW)),
               rightX + 10, y + 19, 0xFFB9C0C8);
         y += L.pickH();
      }

      // ── The pairs, with the warnings the measurements earned ──────────────────────────────────
      int shownLinks = Math.min(links.size(), L.visibleLinks());
      int ly = L.linkY() + 8;
      for (int id : links.subList(0, shownLinks)) {
         List<Pick> ends = endsOf(id);
         int within = LINK_WITHIN.getOrDefault(id, BiomeShape.NEXT_TO_BIOME);
         int pairW = (L.rightW() - 68 - this.font.width("⇄  + ")) / 2;
         g.text(this.font, Component.literal("§b⇄ §f" + fit(shortOf(ends.get(0)), pairW)
                     + " §7+ §f" + fit(shortOf(ends.get(1)), pairW)), rightX, ly + 2, 0xFFFFFFFF);
         // THE SECOND LINE DROPS BELOW THE BUTTONS instead of squeezing past them.
         //
         // It used to be drawn at ly + 14 and reserve 78 pixels on the right so it would not run
         // under the "within" and ✖ buttons, which occupy ly .. ly + 18. At a 340-pixel panel that
         // reserve left 63 pixels — ten characters — for a sentence, so the line was ellipsis
         // whatever it said. The buttons end at ly + 18 and the row is 26 tall, so putting the text
         // at ly + 18 clears them and buys back the whole column width.
         int warnW = L.rightW() - 12;
         String warn = pairWarning(ends, within, warnW);
         g.text(this.font, Component.literal(warn.isEmpty()
                     ? "§7" + widest(warnW, "within " + within + " blocks of each other",
                           within + " blocks apart", "within " + within)
                     : warn),
               rightX + 10, ly + 18, 0xFFB9C0C8);
         ly += L.pickH();
      }
      if (shownLinks < links.size()) {
         int hidden = links.size() - shownLinks;
         g.text(this.font, Component.literal("§7" + widest(L.rightW() - 2,
                     "+" + hidden + " more pair(s) — still searched",
                     "+" + hidden + " more, still searched",
                     "+" + hidden + " more")), rightX, L.linkY(), 0xFF9AA2AA);
      }

      if (!feedback.isEmpty()) {
         java.util.List<net.minecraft.util.FormattedCharSequence> flines = wrappedFeedback(L);
         int fy = L.feedbackY();
         // Never more lines than the layout reserved room for. On a window too short to give the
         // message all its lines the last one is marked, so a clipped message says it is clipped
         // rather than simply stopping.
         int shown = Math.min(flines.size(), L.feedbackLines());
         for (int i = 0; i < shown; i++) {
            g.text(this.font, flines.get(i), L.leftX(), fy, 0xFFFFDD88);
            fy += 12;
         }
         if (shown < flines.size()) {
            g.text(this.font, Component.literal("§e…"), L.leftX() + L.leftW() - 8, fy - 12, 0xFFFFDD88);
         }
      }
      if (L.tooSmall()) {
         g.text(this.font, Component.literal("§cWindow too small — make it taller to see the list."),
               L.leftX(), L.listY(), 0xFFFF6666);
      }
   }

   /**
    * A dark panel behind everything, and it is the fix for more than one complaint at once.
    *
    * Bare text over a live Minecraft world is unreadable against grass and sky, and — the part that
    * actually confused things — with no panel edge there was nothing to say where one column stopped
    * and the next began, so text that merely sat close together read as text that had collided.
    * A filled panel with a border gives every band a visible container.
    */
   private void drawPanel(GuiGraphicsExtractor g, WishLayout L) {
      g.fill(0, 0, this.width, this.height, 0xB0000000);
      g.fill(L.panelX(), L.panelY(), L.panelRight(), L.panelBottom(), 0xE81A1D24);
      g.outline(L.panelX(), L.panelY(), L.panelW(), L.panelH(), 0xFF4A5060);
      // A faint divider between the two columns — and ONLY where there are two columns. The
      // searching screen is a single centred column, so this drew a grey line straight down the
      // middle of it, separating nothing from nothing.
      if (builtPhase == Phase.PICK) {
         int divX = L.rightX() - 5;
         g.verticalLine(divX, L.filterY() - 4, L.bottomY() - 4, 0x40FFFFFF);
      }
   }

   /**
    * The scrollbar, which exists because nobody could tell the list scrolled.
    *
    * A track plus a proportional thumb is the whole of it — the wheel and the ▲▼ buttons do the
    * moving. The point is purely that something on screen says "there is more below this", which a
    * list that simply ends does not.
    */
   private void drawScrollbar(GuiGraphicsExtractor g, WishLayout L) {
      int visible = L.visibleRows();
      if (rowCount <= visible || visible <= 0) {
         return;
      }
      int top = L.listY();
      int bottom = L.listBottom();
      int trackH = bottom - top;
      g.fill(L.scrollX(), top, L.scrollX() + WishLayout.SCROLL_W, bottom, 0x40FFFFFF);
      int thumbH = Math.max(16, trackH * visible / rowCount);
      int maxScroll = Math.max(1, rowCount - visible);
      int thumbY = top + (trackH - thumbH) * Math.min(rowScroll, maxScroll) / maxScroll;
      g.fill(L.scrollX(), thumbY, L.scrollX() + WishLayout.SCROLL_W, thumbY + thumbH, 0xFFAAB2BC);
      // The count in words as well, because a thumb tells you there IS more and this tells you how
      // much — the difference between "scroll a bit" and "there are thirty more".
      g.text(this.font, Component.literal("§7" + (rowScroll + 1) + "–"
                  + Math.min(rowCount, rowScroll + visible) + " of " + rowCount + "  ·  scroll ▲▼"),
            L.leftX(), L.listCountY(), 0xFF9AA2AA);
   }

   /** The chosen row's short name — the same one the left panel shows, so the two columns agree. */
   private String shortOf(Pick p) {
      if (p.biome()) {
         return p.label();
      }
      List<SeedCriteria.StructureTarget> cat = SeedCatalogCache.list();
      if (cat != null) {
         for (SeedCriteria.StructureTarget t : cat) {
            if (t.label.equals(p.label())) {
               return t.shortName();
            }
         }
      }
      return p.label();
   }

   /** One sentence for every pick, because the distance is a MODE setting rather than per-row on
    *  this screen — Exact means at spawn, Fast means within the chosen reach. */
   private String distanceLine(int px) {
      return FableVisionConfig.seedFastMode
            ? widest(px, "within " + FableVisionConfig.seedFastDistance + " blocks of spawn",
                  "within " + FableVisionConfig.seedFastDistance + " of spawn",
                  "within " + FableVisionConfig.seedFastDistance)
            : widest(px, "right at spawn", "at spawn");
   }

   /**
    * The small grey line under a chosen thing: where it has to be, plus what a size tier really
    * asks for in blocks.
    *
    * The number is spelled out because "Big" on its own means nothing — a big pale garden is 64
    * blocks across and a big warm ocean is 608, and the whole reason the tiers are per biome is
    * that those are not the same request.
    */
   private String secondLine(Pick p, int px) {
      // A SLIME ROW SAYS WHETHER IT IS DOING ANYTHING, because the honest answer is usually no and
      // it depends on the distance the player has set — this is the only line that can catch it.
      if (p.slime()) {
         int r = SlimeChunks.PICKER_WITHIN;
         return SlimeChunks.narrows(p.count(), r)
               ? widest(px, p.count() + " within " + r + " blocks of spawn",
                     p.count() + " within " + r + " blocks", p.count() + " in " + r)
               : widest(px, "§c" + p.count() + " within " + r + " — nearly every seed has that",
                     "§c" + p.count() + " in " + r + " — nearly every seed", "§cnearly every seed");
      }
      // Both structure controls, only when they are on. The count reads as a plain multiplier and
      // "spawn inside" says what it costs, because it silently overrides Fast mode and a player who
      // set Fast is entitled to know why the search got slower.
      if (!p.biome() && !p.negated() && (p.count() > 1 || p.inside())) {
         String s = p.inside()
               ? widest(px, "you spawn inside it · Exact mode only", "you spawn inside it",
                     "spawn inside")
               : distanceLine(px);
         return p.count() > 1 ? p.count() + " of them · " + s : s;
      }
      if (p.size() == BiomeShape.Size.ANY) {
         return distanceLine(px);
      }
      BiomeShape.Shape s = BiomeShape.of(biomeIdOf(p.label()));
      if (s == null) {
         return distanceLine(px);
      }
      // The number is the point. "Big" means nothing on its own — a large pale garden is 96 blocks
      // across and a large warm ocean is 608 — so the line gives the real threshold and says out
      // loud that the tier is relative to THIS biome, which is the only claim the measurement
      // supports. "for a pale garden" is the honest qualifier the word "big" was hiding.
      String biome = p.label().toLowerCase(Locale.ROOT);
      // THREE LENGTHS, because this is the longest sentence the chosen list ever holds and the
      // column can be 60 pixels wide. Cut to ten characters it said "256 blocks…", which is the
      // number without the thing that makes it mean anything; the short form keeps the pairing.
      String article = "aeiou".indexOf(biome.charAt(0)) >= 0 ? "an " : "a ";
      return p.size() == BiomeShape.Size.LARGE
            ? widest(px, s.largeMin() + " blocks across or more — large for " + article + biome,
                  s.largeMin() + "+ blocks across — large here",
                  s.largeMin() + "+ across")
            : widest(px, s.smallMax() + " blocks across or less — small for " + article + biome,
                  s.smallMax() + " blocks across at most — small here",
                  "under " + s.smallMax() + " across");
   }

   /**
    * What is wrong with a pair, in the player's interest — or "" when nothing is.
    *
    * Two warnings, both earned by measurement rather than intuition. A tight distance is under the
    * closest MEDIAN any measured pair managed (181 blocks), so most seeds simply cannot satisfy it.
    * And a pair of biomes that are each near spawn in nearly every seed is a filter that filters
    * nothing: plains and forest were both in reach in 60 seeds out of 60, typically 181 blocks
    * apart, so "plains next to forest" describes very nearly the whole game.
    */
   /**
    * The warning under a "next to" pair, in the longest wording that fits the space it has.
    *
    * Every string here is offered in three lengths — see {@link #widest}. The shortest of each set
    * has to fit the narrowest panel the layout supports, and {@code layoutDiag} asserts that at all
    * 360 window sizes rather than leaving it to whoever last resized their game.
    */
   private String pairWarning(List<Pick> ends, int within, int px) {
      if (within < BiomeShape.TIGHT) {
         return widest(px,
               "§c" + within + " blocks is tight — most seeds will fail this",
               "§ctight — most seeds fail",
               "§ctight");
      }
      Identifier a = biomeIdOf(ends.get(0).label());
      Identifier b = biomeIdOf(ends.get(1).label());
      if (a != null && b != null
            && BiomeShape.weakPair(a.getPath(), b.getPath())) {
         return widest(px,
               "§eboth are near spawn in almost every seed — narrows little",
               "§eboth common — narrows little",
               "§enarrows little");
      }
      // A pair of STRUCTURES is measured from the structure the funnel already found rather than
      // from a biome patch, which is worth saying once on the line: it is the cheap end of this
      // feature, not the compromise it looks like next to the biome version.
      if (a == null && b == null) {
         return widest(px,
               "§7measured between the two structures",
               "§7structure to structure",
               "§7direct");
      }
      return "";
   }

   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
      // Only over the left column, so a scroll aimed at the chosen list does not move the browser.
      WishLayout L = lay != null ? lay : layout();
      if (builtPhase == Phase.PICK && mouseX >= L.leftX() && mouseX <= L.leftX() + L.leftW()
            && mouseY >= L.tabsY() && mouseY <= L.listBottom()) {
         int before = rowScroll;
         int max = Math.max(0, rowCount - L.visibleRows());
         rowScroll = Math.max(0, Math.min(max, rowScroll - (int) Math.signum(dy)));
         if (rowScroll != before) {
            stash();
            rebuildWidgets();
         }
         return true;
      }
      return super.mouseScrolled(mouseX, mouseY, dx, dy);
   }

   @Override
   public boolean keyPressed(KeyEvent event) {
      if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)
            && builtPhase == Phase.PICK && wishBox != null && wishBox.isFocused()) {
         startAi();
         return true;
      }
      return super.keyPressed(event);
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
      // Found one? Type the seed into the Create World screen and go STRAIGHT back to it.
      if (myJob != null && myJob.done() && !myJob.results.isEmpty() && !seedApplied) {
         seedApplied = true;
         SeedFinder.Result found = myJob.results.get(0);
         String seed = Long.toString(found.seed());
         SeedBox.put(parent, seed);
         lastAppliedSeed = seed;
         long checked = myJob.checked.get();
         String took = SeedFinder.prettyMs(myJob.elapsedMs());
         lastAppliedStats = String.format("searched %,d seeds in %s", checked, took);
         lastAppliedShort = shortCount(checked) + " seeds · " + took;
         lastAppliedMatches = found.matches();
         lastAppliedFinds = found.finds();
         lastAppliedChecked = checked;
         lastAppliedTook = took;
         lastAppliedNote = searchNote;
         announcePending = true;
         back();
         return;
      }
      if (myJob != null && myJob.done() && myJob.error != null && !myJob.error.equals(feedback)) {
         feedback = "§c" + myJob.error;
      }
      // filterDirty is set by the filter box's responder, which cannot rebuild the screen itself
      // — that would dispose the EditBox mid-keystroke and swallow the character being typed.
      if (phase() != builtPhase || filterDirty || wantedFeedbackLines() != builtFeedbackLines
            || (!builtWithCatalog && SeedCatalogCache.ready())) {
         stash();
         rebuildWidgets();
      }

      // ORDER MATTERS HERE. The panel has to go down BEFORE super draws the widgets, or it covers
      // every button on the screen; the loose text has to go after, or the widgets cover it.
      WishLayout L = lay != null ? lay : layout();
      drawPanel(g, L);
      super.extractRenderState(g, mouseX, mouseY, partialTick);
      int boxW = Math.min(420, this.width - 40);
      int left = (this.width - boxW) / 2;
      int cx = L.panelX() + L.panelW() / 2;
      if (builtPhase == Phase.PICK) {
         renderPickPhase(g);
         renderRowNote(g, L, mouseX, mouseY);
      } else {
         g.centeredText(this.font, this.title, cx, L.panelY() + 10, 0xFFFFFFFF);
         // Wrapped like everything else on this phase. At 97 characters this ran off both sides of
         // any panel narrower than about 600 pixels — centred text does not truncate, it just
         // leaves the screen, which is why it was never caught by a check for something being cut
         // off.
         flow(g, "§7Real seeds from the game's own world-gen — you spawn naturally, nothing is"
               + " moved or spawned in.", cx, L.panelY() + 24, L.panelW() - 20);
      }

      if (builtPhase == Phase.SEARCHING) {
         long checked = myJob == null ? 0 : myJob.checked.get();
         long now = System.currentTimeMillis();
         if (now - rateAtMs >= 1000) {
            ratePerSec = rateAtMs == 0 ? 0 : (checked - rateAtChecked) * 1000 / Math.max(1, now - rateAtMs);
            rateAtMs = now;
            rateAtChecked = checked;
         }
         // EVERY LINE FLOWS. Nothing here is placed at a hand-counted offset any more.
         //
         // The offsets it replaces were sy + 16, + 30, + 44, + 74, + 88, + 100, and they had two
         // faults that were really one fault. They COLLIDED — the run-id line at + 92 was drawn
         // through the bottleneck status at + 88, which is what got noticed — and they made every
         // line unwrappable, because a line that grew to two would land on whatever was at the next
         // offset. So the long sentences here were left to run off both sides of the panel instead,
         // which is the overflow that got reported at the same time. One cursor, advanced by however
         // many lines each message actually took, removes both at once.
         int sy = L.panelY() + 60;
         int wrapW = L.panelW() - 20;
         // Where the status text has to stop: the feedback band, or the Stop button if there is no
         // message. Flowing text only grows downwards, so it needs a floor.
         final int flowLimit = (feedback.isEmpty() ? L.bottomY() : L.feedbackY()) - 4;
         sy = flow(g, "§e⏳ Searching: §f" + (myJob == null ? "" : myJob.summary), cx, sy, wrapW, flowLimit);
         sy = flow(g, String.format("§7checked %,d · %,d/sec · %d bots working",
               checked, ratePerSec, myJob == null ? 0 : myJob.threads), cx, sy, wrapW, flowLimit);
         String eta = etaLine(estimate, ratePerSec, checked);
         if (eta != null) {
            sy = flow(g, eta, cx, sy, wrapW, flowLimit);
         } else if (estimating) {
            sy = flow(g, "§8working out how rare this is…", cx, sy, wrapW, flowLimit);
         } else if (estimateFailed) {
            sy = flow(g, "§8no time estimate for this wish — it couldn't be measured. The search itself is unaffected.",
                  cx, sy, wrapW, flowLimit);
         }
         sy += 4;
         SeedFinder.Result parked = myJob == null ? null : myJob.backup;
         if (parked != null && !myJob.done()) {
            // Exact-mode tiering: a ≤64 hit is in hand, still hunting a RIGHT-at-spawn one.
            sy = flow(g, "§a✓ found one " + myJob.backupWorst
                  + " blocks out — few more seconds hunting one RIGHT at spawn…", cx, sy, wrapW, flowLimit);
         } else {
            sy = flow(g, "§7the moment one is found, it's put into your world settings", cx, sy, wrapW, flowLimit);
         }
         // THE WATCHDOG'S ONE LINE (1.44.5): far past the estimate, with the details in the log.
         String slow = myJob == null ? null : myJob.slowNote;
         if (slow != null && !myJob.done()) {
            sy = flow(g, "§6" + slow, cx, sy, wrapW, flowLimit);
         }
         // FAST MODE'S REJECTIONS ARE PROGRESS, so they are shown as progress. Without this line a
         // search that keeps finding candidates and turning them down because they are not really
         // within the asked distance looks exactly like a search finding nothing at all — and the
         // right response to those two is opposite ("wait" versus "ask for less").
         long turnedDown = myJob == null ? 0 : myJob.rejectedAfterSpawnCheck.get();
         if (turnedDown > 0 && !myJob.done()) {
            sy = flow(g, "§8" + turnedDown + " near-miss" + (turnedDown == 1 ? "" : "es")
                  + " turned down — looked right from the world origin, too far from the real spawn",
                  cx, sy, wrapW, flowLimit);
         }
         // The wait warning, drawn like a note and kept out of the result — see searchWarning.
         if (!searchWarning.isEmpty()) {
            sy += 4;
            sy = flow(g, "§e" + WishParser.stripPlainNote(searchWarning), cx, sy, wrapW, flowLimit);
         }
         if (!searchNote.isEmpty()) {
            sy += 4;
            // NOT TRIMMED TO 60 CHARACTERS ANY MORE. The note is the one line on this screen that
            // says what the app could NOT do with the wish, and cutting it mid-sentence with an
            // ellipsis is the specific failure of an explanation: it tells you there was a reason
            // and withholds it. It wraps now, like everything else here.
            sy = flow(g, searchNote.startsWith(WishParser.STRONGHOLD_CANT)
                        || searchNote.startsWith(WishParser.PLAIN_NOTE)
                  ? "§e" + searchNote
                  : "§6Can't check: §f" + searchNote + " §6— searching the rest.",
                  cx, sy, wrapW, flowLimit);
         }
         // After 90s a bundle is not "slow", it is probably asking for something one of its items
         // makes impossible — and the useful thing to say is WHICH item, not "try something else".
         if (System.currentTimeMillis() - searchStartMs > 90_000) {
            if (!bottleneckStarted) {
               bottleneckStarted = true;
               startBottleneckScan();
            }
            sy += 4;
            if (bottleneck == null) {
               sy = flow(g, "§7Taking long — working out which part is the rare one…", cx, sy, wrapW, flowLimit);
            } else {
               sy = flow(g, "§eHardest part: §f" + bottleneck, cx, sy, wrapW, flowLimit);
               sy = flow(g, "§7Stop and drop that one, or give it more distance.", cx, sy, wrapW, flowLimit);
            }
         }
      }
      for (IconAt icon : icons) {
         g.item(icon.stack(), icon.x(), icon.y());
      }
      // The PICK phase draws its own feedback inside renderPickPhase, positioned against the left
      // panel; this wrapped version is for the SEARCHING phase, where the screen is one column and
      // an AI daily-limit message needs the whole width to be readable.
      if (builtPhase == Phase.SEARCHING && !feedback.isEmpty()) {
         int wrapW = Math.min(boxW, L.panelW() - 20);
         java.util.List<net.minecraft.util.FormattedCharSequence> flines = this.font.split(Component.literal(feedback), wrapW);
         int fy = L.bottomY() - 6 - flines.size() * 10;
         for (net.minecraft.util.FormattedCharSequence fl : flines) {
            g.text(this.font, fl, L.panelX() + 10, fy, 0xFFDDDDDD);
            fy += 10;
         }
      }
   }

   /**
    * Draws one centred message, wrapped, and returns the y the NEXT one should start at.
    *
    * The whole of the searching phase is built out of this. A message that fits takes one line and
    * costs 12 pixels; one that does not takes as many as it needs and the line below it moves down.
    * Nothing has to know in advance how tall anything is, which is the property the fixed offsets
    * did not have.
    */
   private int flow(GuiGraphicsExtractor g, String text, int cx, int y, int wrapW) {
      return flow(g, text, cx, y, wrapW, Integer.MAX_VALUE);
   }

   /** Same, told where it must stop. Flowing text can only grow downwards, so on a short panel it
    *  would eventually reach the feedback band and the Stop button — the one thing the offsets it
    *  replaced could not do, and the one thing it must not start doing. */
   private int flow(GuiGraphicsExtractor g, String text, int cx, int y, int wrapW, int limit) {
      for (net.minecraft.util.FormattedCharSequence line
            : this.font.split(Component.literal(text), wrapW)) {
         if (y + 12 > limit) {
            return y;
         }
         g.centeredText(this.font, line, cx, y, 0xFFFFFFFF);
         y += 12;
      }
      return y;
   }

   /**
    * The longest of these wordings that actually fits, measured with the real font.
    *
    * FOR THE SLOTS THAT CANNOT WRAP. Most overflowing text on this screen was fixed by letting it
    * wrap, but the pair rows are 26 pixels tall and hold two lines by construction — there is
    * nowhere for a third to go. The old answer there was {@code fit()}, which appends an ellipsis,
    * and "64 blocks is tight — most seeds will f…" is the result: the half of the sentence that
    * survives is the half that says nothing.
    *
    * So instead of one sentence cut to fit, several sentences and the widest that FITS. A 1080p
    * window gets the full explanation, a 340-pixel panel gets three words, and neither gets an
    * ellipsis. Pass them longest first; the last one is the fallback and should be short enough to
    * fit anywhere ({@code layoutDiag} checks exactly that).
    */
   private String widest(int px, String... wordings) {
      for (String w : wordings) {
         if (this.font.width(w) <= px) {
            return w;
         }
      }
      return wordings[wordings.length - 1];
   }

   /** "1.4B", "12.0M", "340k", "812". Public because the join message wants the same wording the
    *  badge uses — two spellings of one number is how a player ends up thinking they disagree. */
   public static String shortCount(long n) {
      if (n >= 1_000_000_000L) {
         return String.format("%.1fB", n / 1_000_000_000.0);
      }
      if (n >= 1_000_000) {
         return String.format("%.1fM", n / 1_000_000.0);
      }
      if (n >= 10_000) {
         return Math.round(n / 1000.0) + "k";
      }
      return String.format("%,d", n);
   }

   private static String trim(String s, int max) {
      return s.length() <= max ? s : s.substring(0, max - 1) + "…";
   }

   /**
    * Trim to a WIDTH IN PIXELS rather than a character count.
    *
    * A character count is a guess about the font, and it was wrong in the direction that matters:
    * "30 characters" fits comfortably in the right column of an 854-wide window and runs straight
    * under the three little buttons in a 480-wide one, because the column shrinks and the characters
    * do not. Measuring the actual string against the actual space available cannot get that wrong,
    * and it means the same code is correct at every window size instead of at the one it was
    * eyeballed on.
    *
    * Formatting codes are deliberately NOT passed in here — they are prepended by the caller — so
    * this can never cut a string in the middle of a "§a" and leave the rest of the line coloured.
    */
   private String fit(String s, int maxPx) {
      if (maxPx <= 0 || this.font.width(s) <= maxPx) {
         return s;
      }
      String cut = s;
      while (!cut.isEmpty() && this.font.width(cut + "…") > maxPx) {
         cut = cut.substring(0, cut.length() - 1);
      }
      return cut + "…";
   }

   /** Shorten a long provider error (especially a quota/limit one) into something that fits
    *  the panel. Other errors pass through and are wrapped by the renderer. */
   private static String friendly(String raw) {
      if (raw == null) {
         return "";
      }
      String low = raw.toLowerCase(Locale.ROOT);
      if (low.contains("429") || low.contains("quota") || low.contains("rate limit")
            || low.contains("rate_limit") || low.contains("resource_exhausted")
            || low.contains("exceeded") || low.contains("insufficient")) {
         // ONE SHORT LINE (1.44.3). The old sentence ran past the divider on every window size.
         return "AI limit reached for today — pick below instead.";
      }
      return raw;
   }
}
