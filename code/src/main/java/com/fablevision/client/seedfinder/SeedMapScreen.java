package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import com.fablevision.client.FableVisionClient;
import com.fablevision.client.FableVisionConfig;
import com.fablevision.client.seedfinder.SeedMapData.MapDim;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.lwjgl.glfw.GLFW;

/**
 * A top-down map of a seed: biomes, biome edges, structures, slime chunks and the spawn.
 *
 * ONE SCREEN, THREE WAYS IN, and what each is allowed to know is decided before this screen exists:
 *
 *   - RESULT: the badge on the Create World screen after a search. Draws what the search found,
 *     highlighted, on top of everything else. The finds are drawn exactly as the search reported
 *     them; nothing here re-measures them.
 *   - LOOKUP: "🗺 Seed map" on the Create World screen. The player types a seed they already have and
 *     looks at it. Registries come from that screen through {@link SeedAccess#sourceFor}, the same
 *     door a search uses, with the same World Type rule. There is no search here.
 *   - WORLD: the map key, in YOUR OWN single-player world. Registries, seed and the real spawn come
 *     from {@link SeedAccess#ownWorldForViewing}, which refuses on any server or someone else's LAN
 *     world. This screen has no search controls in any mode.
 *
 * THE BACKGROUND AND THE STRUCTURES come from {@link SeedMapData}; every structure it hands back has
 * passed the game's own generation check.
 *
 * DRAWING. Each view's biomes and edges are painted ONCE into a small texture and drawn as one
 * picture, moved and scaled to where the map now is. Up to 1.43.0 the map was redrawn every frame as
 * tens of thousands of rectangles, which is what made it slow; and a new view only was asked for when
 * the mouse was let go, which is what made it look empty while dragging. Now the last few pictures
 * stay on screen under the new one, and new ground is asked for while the drag is still going.
 *
 * THE END TAB is view only, like everything here. The search still cannot name the End.
 */
public class SeedMapScreen extends Screen {

   public enum Mode { RESULT, LOOKUP, WORLD }

   private static final int LEGEND_W = 168;
   private static final int DOT = 3;
   private static final int MARKER_SPACING = 15;
   private static final int SLIME_MIN_PX = 3;
   /** Pixels per biome cell in the painted picture: 2, so an edge is one pixel inside a cell. */
   private static final int CELL_PX = 2;

   private final Mode mode;
   private final Screen parent;
   private final CreateWorldScreen createScreen;
   private final HolderLookup.Provider registries;
   private final List<SeedCriteria.Find> finds;
   private String seedText;
   private Long seedValue;
   private final String problem;
   private MapDim tab = MapDim.OVERWORLD;

   private final boolean spawnExact;

   /** Everything the map does except draw it — camera, views, pictures, structures. See SeedMapModel. */
   private final SeedMapModel model;

   /** A marker in world coordinates; projected to pixels every frame so panning is smooth. */
   private record Marker(String label, int x, int z, long distance, ItemStack icon, boolean target) {}

   /** The painted texture of each picture on screen, by its colour array (a ground-only view and the
    *  finished one share one array, so they share one texture). */
   private final Map<int[], Identifier> textures = new java.util.IdentityHashMap<>();
   private static int textureCounter;

   /** The marker list and what it was built from — see {@link #markers()}. */
   private List<Marker> markerCache;
   private MapDim markerTab;
   private int markerVersion = -1;

   /** Slime chunks worked out once per camera position rather than per frame — see {@link #drawSlimes}. */
   private long[] slimeCache;
   private String slimeCacheKey = "";

   private int mapX;
   private int mapY;
   private int mapSize;
   private boolean dragging;
   private boolean slimes;
   private EditBox seedBox;
   /** When the seed box last changed, for loading a typed seed without a Show button; 0 = nothing pending. */
   private long typedAt;

   // ── Ways in ──────────────────────────────────────────────────────────────

   /** RESULT: the map of the seed a search just produced. */
   public SeedMapScreen(CreateWorldScreen parent, List<SeedCriteria.Find> finds, String seed) {
      this(Mode.RESULT, parent, parent, WorldgenContext.findSource(parent), finds, seed, null, null);
   }

   /** LOOKUP: any seed the player types, on the Create World screen. */
   public static SeedMapScreen lookup(CreateWorldScreen parent) {
      String problem = SeedAccess.lookupProblem(parent);
      String typed = "";
      try {
         typed = parent.getUiState().getSeed();
      } catch (Throwable ignored) {
      }
      return new SeedMapScreen(Mode.LOOKUP, parent, parent,
            problem == null ? SeedAccess.sourceFor(parent) : null, List.of(), typed, null, problem);
   }

   /** WORLD: the world the player is standing in. {@code own} must be a non-refused answer. */
   public static SeedMapScreen inWorld(SeedAccess.OwnWorld own) {
      return new SeedMapScreen(Mode.WORLD, null, null, own.registries(), List.of(),
            Long.toString(own.seed()), own.spawn(), own.problem());
   }

   private SeedMapScreen(Mode mode, Screen parent, CreateWorldScreen createScreen, HolderLookup.Provider registries,
                         List<SeedCriteria.Find> finds, String seed, BlockPos knownSpawn, String problem) {
      super(Component.literal(mode == Mode.WORLD ? "World map" : mode == Mode.LOOKUP ? "Seed map" : "Your spawn"));
      this.mode = mode;
      this.parent = parent;
      this.createScreen = createScreen;
      this.registries = registries;
      this.finds = finds == null ? List.of() : finds;
      this.problem = problem;
      this.spawnExact = knownSpawn != null;
      this.model = new SeedMapModel(registries, this.finds, problem, knownSpawn, mode == Mode.WORLD ? () -> {
         var mc = net.minecraft.client.Minecraft.getInstance();
         return mc.player != null && tab == playerDim()
               ? new int[]{mc.player.getBlockX(), mc.player.getBlockZ()} : null;
      } : null);
      setSeed(seed == null ? "" : seed);
      if (mode == Mode.RESULT && !hasAnyIn(MapDim.OVERWORLD) && hasAnyIn(MapDim.NETHER)) {
         setTab(MapDim.NETHER);
      }
      if (mode == Mode.WORLD) {
         MapDim here = playerDim();
         if (here != null) {
            setTab(here);
         }
      }
   }

   /** Which tab the player is standing in, or null outside a world. */
   private static MapDim playerDim() {
      var mc = net.minecraft.client.Minecraft.getInstance();
      if (mc.level == null) {
         return null;
      }
      var d = mc.level.dimension();
      return d == Level.NETHER ? MapDim.NETHER : d == Level.END ? MapDim.END : MapDim.OVERWORLD;
   }

   /**
    * The seed as a number, the way the Create World screen reads it. RESULT mode never hashes — the
    * finder always writes a plain number, so text there means the box was edited by hand.
    */
   private void setSeed(String s) {
      seedText = s.trim();
      if (mode == Mode.RESULT) {
         try {
            seedValue = Long.valueOf(seedText);
         } catch (RuntimeException notANumber) {
            seedValue = null;
         }
      } else {
         OptionalLong parsed = WorldOptions.parseSeed(seedText);
         seedValue = parsed.isPresent() ? parsed.getAsLong() : null;
      }
      model.setSeed(seedValue);
      releaseLayers();
   }

   private void setTab(MapDim d) {
      tab = d;
      model.setTab(d);
   }

   private boolean hasAnyIn(MapDim dim) {
      for (SeedCriteria.Find f : finds) {
         if (MapDim.of(f.dim()) == dim) {
            return true;
         }
      }
      return false;
   }

   private List<SeedCriteria.Find> current() {
      List<SeedCriteria.Find> out = new ArrayList<>();
      if (tab == MapDim.END) {
         return out;
      }
      for (SeedCriteria.Find f : finds) {
         if (MapDim.of(f.dim()) == tab) {
            out.add(f);
         }
      }
      return out;
   }

   /**
    * Gets the map's slow first-time parts ready in the background, so the first view does not pay for
    * them: the world-gen context, the structure files, and — when the seed is known — that seed's noise
    * state and stronghold rings. Called when the Create World screen opens and when you join your own
    * world. Nothing is drawn or kept that the map would not build itself on its first view.
    */
   public static void warm(HolderLookup.Provider registries, Long seed) {
      if (registries == null) {
         return;
      }
      SeedMapModel.WORKER.execute(() -> {
         try {
            WorldgenContext ctx = WorldgenContext.get(registries);
            VillageLayout.templates();
            if (seed != null) {
               SeedMapData.warm(ctx, seed);
            }
         } catch (Throwable t) {
            FableVisionClient.LOGGER.debug("Seed map warm-up failed", t);
         }
      });
   }

   // ── Layout ───────────────────────────────────────────────────────────────

   @Override
   protected void init() {
      int margin = 14;
      int available = Math.min(this.width - LEGEND_W - margin * 3, this.height - 111);
      mapSize = Math.max(80, available);
      mapX = margin;
      mapY = 52;
      int lx = mapX + mapSize + 14;

      if (mode == Mode.LOOKUP) {
         // NO SHOW BUTTON (1.44.3). Typing loads the seed by itself a moment after the last keystroke
         // (see tick), and the button's room went to Random.
         int boxW = Math.max(60, mapSize - 64);
         seedBox = new EditBox(this.font, mapX, 6, boxW, 16, Component.literal("seed"));
         seedBox.setMaxLength(64);
         seedBox.setValue(seedText);
         seedBox.setHint(Component.literal("§8type or paste a seed"));
         seedBox.setResponder(text -> typedAt = System.nanoTime());
         addRenderableWidget(seedBox);
         Button random = Button.builder(Component.literal("Random"), b -> {
            // Any long is a valid seed; the game's own "no seed typed" picks from the same range.
            seedBox.setValue(Long.toString(new java.util.Random().nextLong()));
            applyTypedSeed();
         }).tooltip(Tooltip.create(Component.literal("Show a random seed")))
               .bounds(mapX + boxW + 4, 5, 60, 18).build();
         addRenderableWidget(random);
         // A BOX THAT CANNOT ANSWER MUST NOT INVITE TYPING. On a non-Default world type there are no
         // registries to read, so nothing could be shown; the reason is printed under the map, but a
         // box that still took text read as a broken screen.
         if (problem != null) {
            seedBox.setEditable(false);
            seedBox.setHint(Component.literal("§8unavailable here"));
            random.active = false;
            random.setTooltip(Tooltip.create(Component.literal(problem)));
         }
      }

      // Dimension tabs. Every map shows all three; the End is view only and has no finds.
      int tabW = Math.max(40, Math.min(70, (mapSize - 8) / 3));
      int tx = mapX;
      for (MapDim d : MapDim.values()) {
         Button.Builder builder = Button.builder(Component.literal(tab == d ? "§f" + d.title() : "§7" + d.title()),
               btn -> {
                  setTab(d);
                  rebuildWidgets();
               }).bounds(tx, 28, tabW, 18);
         if (d == MapDim.END) {
            builder.tooltip(Tooltip.create(Component.literal(SeedMapData.END_STRUCTURES
                  ? "The End: biomes and End Cities, view only. Every city shown passed the game's own"
                  + " check that it really generates there. Searching the End is still off."
                  : "The End: biomes only. End structures can't be checked reliably, so none are shown.")));
         }
         Button b = builder.build();
         b.active = tab != d;
         addRenderableWidget(b);
         tx += tabW + 4;
      }

      // Map controls, in the legend column so they never run into the tabs. Every one says what it does.
      addRenderableWidget(Button.builder(Component.literal("−"), b -> model.zoomBy(1.6, mapSize / 2.0, mapSize / 2.0, mapSize))
            .tooltip(Tooltip.create(Component.literal("Zoom out (or scroll on the map)")))
            .bounds(lx, 28, 20, 18).build());
      addRenderableWidget(Button.builder(Component.literal("+"), b -> model.zoomBy(1 / 1.6, mapSize / 2.0, mapSize / 2.0, mapSize))
            .tooltip(Tooltip.create(Component.literal("Zoom in (or scroll on the map)")))
            .bounds(lx + 22, 28, 20, 18).build());
      addRenderableWidget(Button.builder(Component.literal(mode == Mode.WORLD ? "Me" : "Centre"), b -> model.recentre())
            .tooltip(Tooltip.create(Component.literal(mode == Mode.WORLD
                  ? "Move the map back to where you are standing"
                  : "Move the map back to " + (tab == MapDim.END ? "the main End island" : "spawn")
                  + " and reset the zoom")))
            .bounds(lx + 44, 28, 40, 18).build());
      if (tab == MapDim.OVERWORLD) {
         addRenderableWidget(Button.builder(Component.literal(slimes ? "§aSlime" : "§7Slime"), b -> {
            slimes = !slimes;
            b.setMessage(Component.literal(slimes ? "§aSlime" : "§7Slime"));
         }).tooltip(Tooltip.create(Component.literal("Show slime chunks (green squares) when zoomed in far enough")))
               .bounds(lx + 86, 28, 44, 18).build());
      }

      int bottomY = this.height - 30;
      if (mode == Mode.RESULT) {
         // THE SPOILER SETTING LIVES WHERE THE SPOILERS ARE: this is the one screen that shows them.
         addRenderableWidget(Button.builder(Component.literal(FableVisionConfig.seedSpoilers ? "Coords: shown" : "Coords: hidden"), b -> {
            FableVisionConfig.seedSpoilers = !FableVisionConfig.seedSpoilers;
            FableVisionConfig.save();
            b.setMessage(Component.literal(FableVisionConfig.seedSpoilers ? "Coords: shown" : "Coords: hidden"));
         }).bounds(lx, bottomY - 22, LEGEND_W, 20).build());
      }
      if (mode == Mode.LOOKUP && createScreen != null) {
         // THE BOX'S TEXT, read when pressed (1.44.3). It used to take the last seed SHOWN, so a seed
         // typed and not yet shown never arrived; and it only set the seed behind the Create World
         // screen, whose own seed box never re-reads it, so the World tab kept showing the old one.
         // SeedBox.put does both.
         addRenderableWidget(Button.builder(Component.literal("Use this seed"), b -> {
            String typed = seedBox != null ? seedBox.getValue().trim() : seedText;
            if (!typed.isEmpty()) {
               SeedBox.put(createScreen, typed);
               this.minecraft.setScreen(parent);
            }
         }).tooltip(Tooltip.create(Component.literal("Put this seed in the world settings and go back to Create World")))
               .bounds(lx, bottomY - 22, LEGEND_W, 20).build());
      }
      addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(lx, bottomY, LEGEND_W, 20).build());

      model.requestView();
   }

   /**
    * Loads what is in the seed box. No rebuild: nothing else on the screen depends on the seed, and
    * rebuilding would replace the box the player is typing in, losing the cursor mid-number.
    */
   private void applyTypedSeed() {
      typedAt = 0;
      if (seedBox != null && !seedBox.getValue().trim().equals(seedText)) {
         setSeed(seedBox.getValue());
         model.requestView();
      }
   }

   /** How long the seed box must sit still before it loads: long enough not to build a map per digit. */
   private static final long TYPE_SETTLE_NS = 300_000_000L;

   @Override
   public void tick() {
      super.tick();
      if (typedAt != 0 && System.nanoTime() - typedAt > TYPE_SETTLE_NS) {
         applyTypedSeed();
      }
      // THE MAP CATCHES UP BY ITSELF — see SeedMapModel.tick.
      model.tick();
   }

   private int[] cam() {
      return model.cam();
   }

   private boolean tabMatchesPlayer() {
      return tab == playerDim();
   }

   private double blocksPerPixel() {
      return model.blocksPerPixel(mapSize);
   }

   // ── Pictures ─────────────────────────────────────────────────────────────

   /** Takes in whatever view arrived, then makes sure every picture on screen has a texture and none
    *  that has left the screen keeps one. */
   private void absorb() {
      model.absorb();
      java.util.Set<int[]> onScreen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
      for (SeedMapData.View v : model.layers()) {
         onScreen.add(v.tint());
         if (!textures.containsKey(v.tint())) {
            Identifier id = paint(v);
            if (id != null) {
               textures.put(v.tint(), id);
            }
         }
      }
      var it = textures.entrySet().iterator();
      while (it.hasNext()) {
         var e = it.next();
         if (!onScreen.contains(e.getKey())) {
            release(e.getValue());
            it.remove();
         }
      }
   }

   /** Paints biomes and biome edges into a texture: one draw call per frame instead of thousands. */
   private Identifier paint(SeedMapData.View view) {
      int cells = view.cells();
      int size = cells * CELL_PX;
      int[] tint = view.tint();
      short[] biome = view.cellBiome();
      try {
         NativeImage img = new NativeImage(size, size, false);
         for (int row = 0; row < cells; row++) {
            for (int col = 0; col < cells; col++) {
               int c = darken(tint[row * cells + col], 0.81);
               int edge = darken(c, 0.56);
               boolean right = col + 1 < cells && biome[row * cells + col] != biome[row * cells + col + 1];
               boolean below = row + 1 < cells && biome[row * cells + col] != biome[(row + 1) * cells + col];
               for (int py = 0; py < CELL_PX; py++) {
                  for (int px = 0; px < CELL_PX; px++) {
                     boolean onEdge = (right && px == CELL_PX - 1) || (below && py == CELL_PX - 1);
                     img.setPixel(col * CELL_PX + px, row * CELL_PX + py, onEdge ? edge : c);
                  }
               }
            }
         }
         Identifier id = Identifier.fromNamespaceAndPath("fablevision", "seedmap/" + (textureCounter++));
         DynamicTexture tex = new DynamicTexture(() -> "FableVision seed map", img);
         this.minecraft.getTextureManager().register(id, tex);
         return id;
      } catch (Throwable t) {
         FableVisionClient.LOGGER.warn("Seed map could not paint the biome picture", t);
         return null;
      }
   }

   private static int darken(int argb, double f) {
      int r = (int) (((argb >> 16) & 0xFF) * f);
      int g = (int) (((argb >> 8) & 0xFF) * f);
      int b = (int) ((argb & 0xFF) * f);
      return 0xFF000000 | (r << 16) | (g << 8) | b;
   }

   private void release(Identifier id) {
      try {
         this.minecraft.getTextureManager().release(id);
      } catch (Throwable ignored) {
      }
   }

   private void releaseLayers() {
      if (this.minecraft != null) {
         textures.values().forEach(this::release);
      }
      textures.clear();
   }

   /**
    * The markers for this tab: the search's finds first (they win overlaps), then every verified
    * structure met so far. All of them are real; which view found them does not matter.
    *
    * BUILT ONCE PER CHANGE, not once per frame. This used to run inside the render method, so a map
    * carrying a few thousand remembered structures allocated a few thousand markers — each with a
    * fresh ItemStack for its icon — sixty times a second, purely to draw the same list again.
    */
   private List<Marker> markers() {
      if (markerCache != null && markerTab == tab && markerVersion == model.knownVersion()) {
         return markerCache;
      }
      List<Marker> out = new ArrayList<>();
      for (SeedCriteria.Find f : current()) {
         out.add(new Marker(f.label(), f.x(), f.z(), f.shown(), iconFor(f), true));
      }
      LinkedHashMap<String, SeedMapData.Nearby> k = model.known();
      if (k != null) {
         for (SeedMapData.Nearby n : k.values()) {
            out.add(new Marker(n.label(), n.x(), n.z(), n.distance(), SeedIcons.structure(n.iconLabel()), false));
         }
      }
      markerCache = out;
      markerTab = tab;
      markerVersion = model.knownVersion();
      return out;
   }

   private int px(double worldX) {
      int[] c = cam();
      return mapX + (int) Math.round(mapSize / 2.0 + (worldX - c[0]) / blocksPerPixel());
   }

   private int pz(double worldZ) {
      int[] c = cam();
      return mapY + (int) Math.round(mapSize / 2.0 + (worldZ - c[1]) / blocksPerPixel());
   }

   private static ItemStack iconFor(SeedCriteria.Find f) {
      if (SeedIcons.hasIcon(f.label())) {
         return SeedIcons.structure(f.label());
      }
      int hash = f.label().indexOf(" #");
      if (hash > 0 && SeedIcons.hasIcon(f.label().substring(0, hash))) {
         return SeedIcons.structure(f.label().substring(0, hash));
      }
      return SeedIcons.biome(f.label().toLowerCase(java.util.Locale.ROOT).replace(' ', '_'));
   }

   /** Coordinates may be printed: always for a typed seed or your own world; for a search result,
    *  only with spoilers on. */
   private boolean coordsAllowed() {
      return SeedMapModel.coordsShown(mode == Mode.RESULT, FableVisionConfig.seedSpoilers);
   }

   // ── Input ────────────────────────────────────────────────────────────────

   private boolean overMap(double x, double y) {
      return x >= mapX && x < mapX + mapSize && y >= mapY && y < mapY + mapSize;
   }

   @Override
   public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      if (super.mouseClicked(event, doubleClick)) {
         return true;
      }
      if (event.button() == 0 && overMap(event.x(), event.y())) {
         dragging = true;
         return true;
      }
      return false;
   }

   @Override
   public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
      if (dragging) {
         // The old picture stays under the map while new ground is asked for — see SeedMapModel.drag.
         model.drag(dx, dy, mapSize);
         return true;
      }
      return super.mouseDragged(event, dx, dy);
   }

   @Override
   public boolean mouseReleased(MouseButtonEvent event) {
      if (dragging && event.button() == 0) {
         dragging = false;
         model.release();
         return true;
      }
      return super.mouseReleased(event);
   }

   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      if (overMap(mouseX, mouseY) && scrollY != 0) {
         model.zoomBy(scrollY > 0 ? 1 / 1.25 : 1.25, mouseX - mapX, mouseY - mapY, mapSize);
         return true;
      }
      return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
   }

   @Override
   public boolean keyPressed(KeyEvent event) {
      if (seedBox != null && seedBox.isFocused()
            && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
         applyTypedSeed();
         return true;
      }
      return super.keyPressed(event);
   }

   // ── Drawing ──────────────────────────────────────────────────────────────

   @Override
   public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
      g.fill(0, 0, this.width, this.height, 0xC0000000);
      super.extractRenderState(g, mouseX, mouseY, partialTick);

      absorb();
      SeedMapData.View view = model.view();
      if (mode != Mode.LOOKUP) {
         String head = mode == Mode.WORLD ? "§f🗺 This world" : "§f🗺 Your spawn";
         g.text(this.font, Component.literal(head + " §7— seed §f" + seedText), mapX, 12, 0xFFFFFFFF);
      }

      g.fill(mapX, mapY, mapX + mapSize, mapY + mapSize, 0xFF14171D);
      g.enableScissor(mapX, mapY, mapX + mapSize, mapY + mapSize);
      for (SeedMapData.View v : model.layers()) {
         Identifier tex = textures.get(v.tint());
         if (tex != null) {
            int x0 = px(v.centreX() - v.reach());
            int y0 = pz(v.centreZ() - v.reach());
            int x1 = px(v.centreX() + v.reach());
            int y1 = pz(v.centreZ() + v.reach());
            if (x1 < mapX || x0 > mapX + mapSize || y1 < mapY || y0 > mapY + mapSize) {
               continue;
            }
            g.blit(tex, x0, y0, Math.max(x0 + 1, x1), Math.max(y0 + 1, y1), 0f, 1f, 0f, 1f);
         }
      }
      drawSlimes(g);

      int[] c = cam();
      BlockPos at = model.spawn();
      int ox = at == null ? 0 : SeedMapData.originX(tab, at);
      int oz = at == null ? 0 : SeedMapData.originZ(tab, at);
      int cx = px(ox);
      int cy = pz(oz);
      double bpp = blocksPerPixel();
      for (int ring : new int[]{50, 100, 200, 400, 800, 1600, 3200, 6400}) {
         int r = (int) Math.round(ring / bpp);
         if (r < 12 || r > mapSize * 2) {
            continue;
         }
         drawRing(g, cx, cy, r, 0x40FFFFFF);
         g.text(this.font, Component.literal("§8" + ring), cx + 2, cy - r - 9, 0xFFCCCCCC);
      }
      g.verticalLine(cx, mapY + 1, mapY + mapSize - 1, 0x33FFFFFF);
      g.horizontalLine(mapX + 1, mapX + mapSize - 1, cy, 0x33FFFFFF);

      // OVERLAP BY BUCKET, not by comparing every marker with every marker already drawn. Two markers
      // can only clash if they are within MARKER_SPACING pixels, so each drawn marker is filed in a
      // grid of that size and a newcomer looks in the nine buckets around it. The old loop was
      // thousands of markers times hundreds of drawn ones, with two projections per comparison, every
      // frame — the thing that made a well-panned map get slower the longer it was used.
      List<Marker> drawn = new ArrayList<>();
      Map<Long, List<int[]>> buckets = new java.util.HashMap<>();
      for (Marker m : markers()) {
         int mx = px(m.x());
         int my = pz(m.z());
         if (mx < mapX || mx > mapX + mapSize || my < mapY || my > mapY + mapSize) {
            continue;
         }
         int bx = Math.floorDiv(mx, MARKER_SPACING);
         int by = Math.floorDiv(my, MARKER_SPACING);
         boolean close = false;
         for (int ox2 = -1; ox2 <= 1 && !close; ox2++) {
            for (int oz2 = -1; oz2 <= 1 && !close; oz2++) {
               List<int[]> here = buckets.get((long) (bx + ox2) << 32 ^ (by + oz2) & 0xFFFFFFFFL);
               for (int i = 0; here != null && i < here.size(); i++) {
                  int[] p = here.get(i);
                  if (Math.abs(p[0] - mx) < MARKER_SPACING && Math.abs(p[1] - my) < MARKER_SPACING) {
                     close = true;
                     break;
                  }
               }
            }
         }
         if (close) {
            continue;
         }
         buckets.computeIfAbsent((long) bx << 32 ^ by & 0xFFFFFFFFL, key -> new ArrayList<>())
               .add(new int[]{mx, my});
         drawn.add(m);
         if (m.target()) {
            g.fill(mx - DOT, my - DOT, mx + DOT, my + DOT, 0xFFFFD264);
            g.outline(mx - DOT - 1, my - DOT - 1, DOT * 2 + 2, DOT * 2 + 2, 0xFF3A2A00);
         } else {
            g.fill(mx - 1, my - 1, mx + 2, my + 2, 0xC0DCE4EC);
         }
         g.item(m.icon(), mx - 8, my - 20);
      }

      g.fill(cx - 2, cy - 2, cx + 3, cy + 3, 0xFF66FF88);
      g.text(this.font, Component.literal("§a✦"), cx - 3, cy - 4, 0xFF66FF88);
      if (mode == Mode.WORLD && this.minecraft.player != null && tabMatchesPlayer()) {
         int ppx = px(this.minecraft.player.getX());
         int ppz = pz(this.minecraft.player.getZ());
         g.fill(ppx - 3, ppz - 3, ppx + 4, ppz + 4, 0xFF000000);
         g.fill(ppx - 2, ppz - 2, ppx + 3, ppz + 3, 0xFF4FD1FF);
      }
      g.disableScissor();
      g.outline(mapX, mapY, mapSize, mapSize, 0xFF4A5060);

      drawBelowMap(g, mouseX, mouseY, view, c);
      drawLegend(g, drawn, view);
      drawTooltip(g, drawn, mouseX, mouseY);
   }

   /**
    * The lines under the map, WRAPPED to the map's width rather than cut with "…". The scale line
    * first, then what the map is doing; if the window is too short for all of it, the status wins,
    * because it is the part that changes.
    */
   private void drawBelowMap(GuiGraphicsExtractor g, int mouseX, int mouseY, SeedMapData.View view, int[] c) {
      String scale = "§8✦ " + originName() + " · " + String.format("%,d", c[2] * 2)
            + " blocks across · drag to move, scroll to zoom";
      List<FormattedCharSequence> top = this.font.split(Component.literal(scale), mapSize);
      List<FormattedCharSequence> status = this.font.split(Component.literal(statusLine(mouseX, mouseY, view)), mapSize);
      int y = mapY + mapSize + 5;
      int room = Math.max(1, (this.height - 2 - y) / 10);
      List<FormattedCharSequence> lines = new ArrayList<>();
      int topLines = Math.max(0, Math.min(top.size(), room - Math.min(status.size(), room)));
      lines.addAll(top.subList(0, topLines));
      lines.addAll(status.subList(0, Math.min(status.size(), room - topLines)));
      for (FormattedCharSequence line : lines) {
         g.text(this.font, line, mapX, y, 0xFF9AA2AA);
         y += 10;
      }
   }

   private void drawSlimes(GuiGraphicsExtractor g) {
      if (!slimes || tab != MapDim.OVERWORLD || seedValue == null) {
         return;
      }
      double bpp = blocksPerPixel();
      if (16 / bpp < SLIME_MIN_PX) {
         return;
      }
      int[] c = cam();
      int minCx = Math.floorDiv(c[0] - c[2], 16);
      int maxCx = Math.floorDiv(c[0] + c[2], 16);
      int minCz = Math.floorDiv(c[1] - c[2], 16);
      int maxCz = Math.floorDiv(c[1] + c[2], 16);
      // WORKED OUT ONCE PER VIEW, not per frame. At the zoom where slime chunks first appear this is
      // about seventeen thousand chunks, each of which seeds a random number generator — cheap once,
      // and sixty times a second it is most of a frame. The answer only changes when the camera or
      // the seed does, so that is the key.
      String key = seedValue + ":" + minCx + ":" + minCz + ":" + maxCx + ":" + maxCz;
      if (!key.equals(slimeCacheKey)) {
         long s = seedValue;
         java.util.List<Long> found = new ArrayList<>();
         for (int chx = minCx; chx <= maxCx; chx++) {
            for (int chz = minCz; chz <= maxCz; chz++) {
               if (SlimeChunks.isSlimeChunk(s, chx, chz)) {
                  found.add((long) chx << 32 ^ chz & 0xFFFFFFFFL);
               }
            }
         }
         slimeCache = new long[found.size()];
         for (int i = 0; i < found.size(); i++) {
            slimeCache[i] = found.get(i);
         }
         slimeCacheKey = key;
      }
      for (long packed : slimeCache) {
         int chx = (int) (packed >> 32);
         int chz = (int) packed;
         int x0 = px(chx * 16.0);
         int y0 = pz(chz * 16.0);
         g.fill(x0, y0, px((chx + 1) * 16.0), pz((chz + 1) * 16.0), 0x6048E060);
      }
   }

   private String originName() {
      if (tab == MapDim.NETHER) {
         return "= where a portal at spawn comes out";
      }
      if (tab == MapDim.END) {
         return "= the main End island (0, 0)";
      }
      return spawnExact ? "= world spawn" : "= spawn (the game settles it within about a chunk)";
   }

   /** The hover readout, or what the map is doing. Never silence. */
   private String statusLine(int mouseX, int mouseY, SeedMapData.View view) {
      if (problem != null) {
         return "§c" + problem;
      }
      if (registries == null) {
         return "§8biomes and structures need the Create World screen";
      }
      if (seedValue == null) {
         return mode == Mode.LOOKUP ? "§7type or paste a seed above, or press Random" : "§8the seed box was edited by hand — showing the finds only";
      }
      if (model.loadError() != null) {
         return "§ccouldn't read the world for this seed (" + model.loadError() + ") — trying again";
      }
      if (overMap(mouseX, mouseY)) {
         int[] c = cam();
         double bpp = blocksPerPixel();
         int wx = (int) Math.floor(c[0] + (mouseX - mapX - mapSize / 2.0) * bpp);
         int wz = (int) Math.floor(c[1] + (mouseY - mapY - mapSize / 2.0) * bpp);
         String biome = biomeUnder(wx, wz);
         String where = coordsAllowed() ? "§fx " + wx + ", z " + wz : "§8coordinates hidden";
         String chunk = coordsAllowed() ? " §8· chunk " + Math.floorDiv(wx, 16) + ", " + Math.floorDiv(wz, 16) : "";
         String slime = slimes && tab == MapDim.OVERWORLD
               && SlimeChunks.isSlimeChunk(seedValue, Math.floorDiv(wx, 16), Math.floorDiv(wz, 16)) ? " §a· slime chunk" : "";
         return where + chunk + (biome.isEmpty() ? "" : " §7· " + biome) + slime;
      }
      if (tab == MapDim.END && !SeedMapData.END_STRUCTURES) {
         return "§7End: biomes only — End structures can't be checked reliably, so none are shown";
      }
      if (view == null) {
         return "§7reading the world…";
      }
      if (!view.structuresDone()) {
         return "§7checking structures…";
      }
      if (view.unverifiable() > 0) {
         return "§e" + view.unverifiable() + " structure" + (view.unverifiable() == 1 ? "" : "s")
               + " couldn't be checked, so " + (view.unverifiable() == 1 ? "it isn't" : "they aren't") + " shown";
      }
      if (view.capped()) {
         return "§8zoomed out: a share of the structures in every part of the map — zoom in for all of them";
      }
      if (model.loading()) {
         return "§7reading the world…";
      }
      return mode == Mode.RESULT ? "§8§oyellow = what you searched for · white = also here" : "§8§ohover for coordinates and biome";
   }

   /** The biome under a world point, from the newest picture that covers it. */
   private String biomeUnder(int wx, int wz) {
      List<SeedMapData.View> list = model.layers();
      for (int i = list.size() - 1; i >= 0; i--) {
         SeedMapData.View v = list.get(i);
         double cell = v.reach() * 2.0 / v.cells();
         int col = (int) Math.floor((wx - (v.centreX() - v.reach())) / cell);
         int row = (int) Math.floor((wz - (v.centreZ() - v.reach())) / cell);
         String b = v.biomeAt(col, row);
         if (!b.isEmpty()) {
            return b;
         }
      }
      return "";
   }

   private void drawLegend(GuiGraphicsExtractor g, List<Marker> drawn, SeedMapData.View view) {
      int lx = mapX + mapSize + 14;
      int ly = mapY;
      int bottom = this.height - 58;
      if (mode == Mode.RESULT && tab != MapDim.END) {
         g.text(this.font, Component.literal("§eFound"), lx, ly, 0xFFFFD264);
         ly += 13;
         for (SeedCriteria.Find f : current()) {
            if (ly > bottom - 22) {
               g.text(this.font, Component.literal("§7…more"), lx, ly, 0xFF9AA2AA);
               ly += 12;
               break;
            }
            g.text(this.font, Component.literal("§f" + fitPx(f.label(), LEGEND_W)), lx, ly, 0xFFFFFFFF);
            String where = coordsAllowed() ? f.x() + ", " + f.z() : "?, ?";
            g.text(this.font, Component.literal("§7" + where + " §8· " + f.shown() + " blocks"), lx, ly + 10, 0xFFB9C0C8);
            ly += 22;
         }
         ly += 6;
      }
      BlockPos at = model.spawn();
      if (at != null && tab == MapDim.OVERWORLD && coordsAllowed()) {
         g.text(this.font, Component.literal("§a✦ §7spawn §f" + at.getX() + ", " + at.getZ()), lx, ly, 0xFFDDE3EA);
         ly += 13;
      }
      if (tab == MapDim.END && !SeedMapData.END_STRUCTURES) {
         g.text(this.font, Component.literal("§7Biomes only"), lx, ly, 0xFFAAAAAA);
         return;
      }
      if (view == null) {
         return;
      }
      g.text(this.font, Component.literal(mode == Mode.RESULT ? "§7Also here" : "§7On the map"), lx, ly, 0xFFAAAAAA);
      ly += 13;
      int shown = 0;
      int total = 0;
      for (Marker m : drawn) {
         if (m.target()) {
            continue;
         }
         total++;
         if (ly > bottom - 10) {
            continue;
         }
         // NAME ONLY (1.44.3). The "82m" after each was clutter; the hover tooltip still gives the distance.
         g.text(this.font, Component.literal("§f" + fitPx(m.label(), LEGEND_W)), lx, ly, 0xFFDDE3EA);
         ly += 11;
         shown++;
      }
      if (total == 0) {
         g.text(this.font, Component.literal(view.structuresDone() ? "§8no structures in view" : "§8checking…"),
               lx, ly, 0xFF6A7280);
      } else if (shown < total) {
         g.text(this.font, Component.literal("§8+" + (total - shown) + " more on the map"), lx, ly, 0xFF6A7280);
      }
   }

   /** The name of whatever the pointer is over. */
   private void drawTooltip(GuiGraphicsExtractor g, List<Marker> drawn, int mouseX, int mouseY) {
      Marker over = null;
      for (Marker m : drawn) {
         int mx = px(m.x());
         int my = pz(m.z());
         if (Math.abs(mx - mouseX) <= 8 && mouseY <= my + 4 && mouseY >= my - 20) {
            over = m;
         }
      }
      if (over == null) {
         return;
      }
      String where = coordsAllowed() ? over.x() + ", " + over.z() : "?, ?";
      String line = (over.target() ? "§e" : "§f") + over.label() + " §8· " + where + " §7(" + over.distance() + " blocks)";
      int w = this.font.width(line) + 8;
      int x = Math.max(2, Math.min(mouseX + 8, this.width - w - 2));
      int y = Math.max(2, mouseY - 14);
      g.fill(x, y, x + w, y + 12, 0xE0101318);
      g.outline(x, y, w, 12, 0xFF4A5060);
      g.text(this.font, Component.literal(line), x + 4, y + 2, 0xFFFFFFFF);
   }

   /** A circle, drawn as points; only the points inside the map are sent. */
   private void drawRing(GuiGraphicsExtractor g, int cx, int cy, int r, int colour) {
      int steps = Math.max(48, Math.min(720, r * 2));
      for (int i = 0; i < steps; i++) {
         double a = i * 2 * Math.PI / steps;
         int x = cx + (int) Math.round(Math.cos(a) * r);
         int y = cy + (int) Math.round(Math.sin(a) * r);
         if (x < mapX || x >= mapX + mapSize || y < mapY || y >= mapY + mapSize) {
            continue;
         }
         g.fill(x, y, x + 1, y + 1, colour);
      }
   }

   private String fitPx(String s, int maxPx) {
      if (this.font.width(s) <= maxPx) {
         return s;
      }
      String cut = s;
      while (!cut.isEmpty() && this.font.width(cut + "…") > maxPx) {
         cut = cut.substring(0, cut.length() - 1);
      }
      return cut + "…";
   }

   @Override
   public void onClose() {
      model.stop();
      releaseLayers();
      this.minecraft.setScreen(parent);
   }

   @Override
   public void removed() {
      // Also when the screen is left some other way (a world starting, a disconnect).
      model.stop();
      releaseLayers();
      super.removed();
   }

   @Override
   public boolean isPauseScreen() {
      return false;
   }
}
