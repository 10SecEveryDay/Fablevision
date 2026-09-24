package com.fablevision.client.seedfinder;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * THE GAME'S OWN TEXT WIDTHS, measured headlessly from the game's own font files.
 *
 * WHY THIS EXISTS (1.43.2). layoutDiag passed while the custom-spawn title, the "Within 100" button
 * and the Mode button were drawn on top of each other. Part of why: it measured text with
 * {@link TextFit}, which assumes no glyph is wider than 6 pixels. That is true of plain letters and
 * false of exactly the characters the top row is made of — ✨ ⚡ 🎯 ▸ — which the game draws from its
 * fallback font at up to 9 pixels each. The check was under-measuring the strings it most needed to get
 * right.
 *
 * So this does not estimate. It loads what the game loads, in the order the game loads it, and applies
 * the game's own width rules, read from the game's code:
 *
 *   - "space" provider: ' ' is 4 pixels (include/space.json).
 *   - "bitmap" providers (include/default.json, from the Minecraft jar): a glyph's width is the
 *     rightmost column of its cell with any visible pixel, +1; its advance is
 *     {@code (int)(0.5 + width * height / cellHeight) + 1} — BitmapProvider.Definition.load.
 *   - "unihex" fallback (unifont.zip, from the downloaded game assets): the glyph's lit columns, from
 *     the leftmost to the rightmost, give a width; the advance is {@code width / 2 + 1}, with the
 *     provider's own size overrides — UnihexProvider.Glyph.
 *
 * The first provider that has a character wins, as in the game. A character no provider has is
 * reported rather than guessed. If the font files cannot be found at all, {@link #available()} is
 * false and callers fall back to {@link TextFit} and SAY so.
 */
public final class RealFont {

   private final Map<Integer, Integer> advance = new HashMap<>();
   private final TreeSet<Integer> unknown = new TreeSet<>();
   private final String problem;
   private static RealFont instance;

   /** {@code gradlew fontWidth --args="text one|text two"}: the game's width of each string. */
   public static void main(String[] args) {
      RealFont f = get();
      if (!f.available()) {
         System.out.println("REAL FONT UNAVAILABLE: " + f.problem());
         System.exit(1);
      }
      System.out.println("widest ASCII glyph: " + f.widestAscii() + "px (TextFit assumed " + TextFit.MAX_GLYPH_W + ")");
      // "@path" reads one string per line from a UTF-8 file: command lines mangle emoji and quotes.
      java.util.List<String> strings = new java.util.ArrayList<>();
      if (args.length == 1 && args[0].startsWith("@")) {
         try {
            strings.addAll(Files.readAllLines(Path.of(args[0].substring(1)), StandardCharsets.UTF_8));
         } catch (Exception e) {
            System.out.println("cannot read " + args[0] + ": " + e);
            System.exit(1);
         }
      } else {
         strings.addAll(java.util.List.of(String.join(" ", args).split("\\|")));
      }
      java.io.PrintStream out = new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8);
      for (String s : strings) {
         if (s.isEmpty()) {
            continue;
         }
         out.println(String.format("%4dpx  (TextFit %4d)  %s", f.width(s), TextFit.worstWidth(s), s));
      }
      if (!f.unknown().isEmpty()) {
         System.out.println("no glyph in any provider: " + f.unknown());
      }
   }

   public static synchronized RealFont get() {
      if (instance == null) {
         instance = new RealFont();
      }
      return instance;
   }

   public boolean available() {
      return problem == null;
   }

   /** Why the real font could not be loaded, or null. */
   public String problem() {
      return problem;
   }

   /** Characters asked about that no font provider has (the game draws them as a missing-glyph box). */
   public TreeSet<Integer> unknown() {
      return unknown;
   }

   /** The width the game draws this string at, in GUI pixels. § formatting codes draw nothing. */
   public int width(String s) {
      if (s == null) {
         return 0;
      }
      if (!available()) {
         return TextFit.worstWidth(s);
      }
      int w = 0;
      for (int i = 0; i < s.length(); ) {
         int cp = s.codePointAt(i);
         i += Character.charCount(cp);
         if (cp == '§') {
            if (i < s.length()) {
               i += Character.charCount(s.codePointAt(i));
            }
            continue;
         }
         Integer a = advance.get(cp);
         if (a == null) {
            unknown.add(cp);
            a = 9;   // unknown: assume the widest glyph any provider draws, never less
         }
         w += a;
      }
      return w;
   }

   public boolean fits(String s, int px) {
      return width(s) <= px;
   }

   /** The widest advance of any printable ASCII character — the number TextFit guessed at. */
   public int widestAscii() {
      int max = 0;
      for (int c = 32; c < 127; c++) {
         max = Math.max(max, advance.getOrDefault(c, 0));
      }
      return max;
   }

   public int advanceOf(int codepoint) {
      return advance.getOrDefault(codepoint, -1);
   }

   // ── Loading, in the game's provider order ──────────────────────────────────────────────────────

   private RealFont() {
      String p = null;
      try {
         // 1. include/space
         advance.put((int) ' ', 4);
         advance.put(0x200C, 0);
         // 2. include/default — the bitmap sheets inside the Minecraft jar
         loadBitmaps();
         // 3. include/unifont — from the downloaded assets (the jar's copy of unifont.json is empty)
         loadUnifont();
      } catch (Throwable t) {
         p = t.getClass().getSimpleName() + ": " + t.getMessage();
      }
      this.problem = p;
   }

   private static InputStream resource(String path) {
      InputStream in = RealFont.class.getClassLoader().getResourceAsStream(path);
      if (in == null) {
         throw new IllegalStateException("not on the classpath: " + path);
      }
      return in;
   }

   private static JsonObject json(InputStream in) throws Exception {
      try (in; InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
         return JsonParser.parseReader(r).getAsJsonObject();
      }
   }

   private void loadBitmaps() throws Exception {
      JsonObject def = json(resource("assets/minecraft/font/include/default.json"));
      for (JsonElement el : def.getAsJsonArray("providers")) {
         JsonObject prov = el.getAsJsonObject();
         if (!"bitmap".equals(prov.get("type").getAsString())) {
            continue;
         }
         String file = prov.get("file").getAsString();          // "minecraft:font/ascii.png"
         String path = "assets/" + file.replace(":", "/textures/");
         int height = prov.has("height") ? prov.get("height").getAsInt() : 8;
         JsonArray rows = prov.getAsJsonArray("chars");
         int[][] grid = new int[rows.size()][];
         for (int r = 0; r < rows.size(); r++) {
            grid[r] = rows.get(r).getAsString().codePoints().toArray();
         }
         BufferedImage img;
         try (InputStream in = resource(path)) {
            img = ImageIO.read(in);
         }
         int cellW = img.getWidth() / grid[0].length;
         int cellH = img.getHeight() / grid.length;
         float scale = (float) height / (float) cellH;
         for (int r = 0; r < grid.length; r++) {
            for (int c = 0; c < grid[r].length; c++) {
               int cp = grid[r][c];
               if (cp == 0 || advance.containsKey(cp)) {
                  continue;   // placeholder cell, or an earlier provider already has it
               }
               int width = actualGlyphWidth(img, cellW, cellH, c, r);
               advance.put(cp, (int) (0.5 + width * scale) + 1);
            }
         }
      }
   }

   /** BitmapProvider.Definition.getActualGlyphWidth: rightmost column with any non-transparent pixel, +1. */
   private static int actualGlyphWidth(BufferedImage img, int cellW, int cellH, int col, int row) {
      for (int x = cellW - 1; x >= 0; x--) {
         for (int y = 0; y < cellH; y++) {
            int argb = img.getRGB(col * cellW + x, row * cellH + y);
            if ((argb >>> 24) != 0) {
               return x + 1;
            }
         }
      }
      return 0;
   }

   private void loadUnifont() throws Exception {
      Path assets = Path.of(System.getProperty("user.home"), ".gradle", "caches", "fabric-loom", "assets");
      Path indexes = assets.resolve("indexes");
      Path index = null;
      try (var files = Files.list(indexes)) {
         for (Path f : (Iterable<Path>) files::iterator) {
            if (f.getFileName().toString().startsWith("26.1.2")) {
               index = f;
            }
         }
      }
      if (index == null) {
         throw new IllegalStateException("no asset index for 26.1.2 under " + indexes);
      }
      JsonObject objects = json(Files.newInputStream(index)).getAsJsonObject("objects");
      JsonObject provJson = json(Files.newInputStream(object(assets, objects, "minecraft/font/include/unifont.json")));
      for (JsonElement el : provJson.getAsJsonArray("providers")) {
         JsonObject prov = el.getAsJsonObject();
         if (!"unihex".equals(prov.get("type").getAsString())) {
            continue;
         }
         // The Japanese-variant sheet only applies with the jp font filter on, which the default is not.
         if (prov.has("filter") && prov.getAsJsonObject("filter").has("jp")) {
            continue;
         }
         String hex = prov.get("hex_file").getAsString().replace("minecraft:", "minecraft/");
         Map<int[], int[]> overrides = new HashMap<>();
         if (prov.has("size_overrides")) {
            for (JsonElement o : prov.getAsJsonArray("size_overrides")) {
               JsonObject ov = o.getAsJsonObject();
               overrides.put(new int[]{ov.get("from").getAsString().codePointAt(0), ov.get("to").getAsString().codePointAt(0)},
                     new int[]{ov.get("left").getAsInt(), ov.get("right").getAsInt()});
            }
         }
         readHex(Files.newInputStream(object(assets, objects, hex)), overrides);
      }
   }

   private static Path object(Path assets, JsonObject objects, String name) {
      JsonObject o = objects.getAsJsonObject(name);
      if (o == null) {
         throw new IllegalStateException("asset index has no " + name);
      }
      String hash = o.get("hash").getAsString();
      return assets.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
   }

   /** Reads a zipped .hex font and records each glyph's advance, as UnihexProvider does. */
   private void readHex(InputStream zipped, Map<int[], int[]> overrides) throws Exception {
      try (ZipInputStream zip = new ZipInputStream(zipped)) {
         ZipEntry e;
         while ((e = zip.getNextEntry()) != null) {
            if (!e.getName().endsWith(".hex")) {
               continue;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(zip, StandardCharsets.US_ASCII));
            String line;
            while ((line = r.readLine()) != null) {
               int colon = line.indexOf(':');
               if (colon <= 0) {
                  continue;
               }
               int cp = Integer.parseInt(line.substring(0, colon), 16);
               if (advance.containsKey(cp)) {
                  continue;   // an earlier provider (the bitmaps) already has it
               }
               String bits = line.substring(colon + 1).trim();
               int bitWidth = bits.length() * 4 / 16;   // 16 rows
               int digitsPerRow = bitWidth / 4;
               int mask = 0;
               for (int row = 0; row < 16; row++) {
                  long v = Long.parseLong(bits.substring(row * digitsPerRow, (row + 1) * digitsPerRow), 16);
                  mask |= (int) (v << (32 - bitWidth));   // left-aligned, as ByteContents/ShortContents do
               }
               int left;
               int right;
               int[] forced = null;
               for (var ov : overrides.entrySet()) {
                  if (cp >= ov.getKey()[0] && cp <= ov.getKey()[1]) {
                     forced = ov.getValue();
                  }
               }
               if (forced != null) {
                  left = forced[0];
                  right = forced[1];
               } else if (mask == 0) {
                  left = 0;
                  right = bitWidth;
               } else {
                  left = Integer.numberOfLeadingZeros(mask);
                  right = 32 - Integer.numberOfTrailingZeros(mask) - 1;
               }
               int width = right - left + 1;
               advance.put(cp, width / 2 + 1);
            }
         }
      }
   }
}
