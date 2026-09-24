package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;

/**
 * Reads the COMMON wishes with no AI and no key: "village next to a cherry grove", "mansion in a dark
 * forest", "igloo with a basement", "two shipwrecks", "pale garden not next to a forest".
 *
 * WHY. Requiring an API key kept the wish box closed to most players, and most wishes are a couple of
 * names joined by "next to", "no" or a number — which needs a phrase book, not a language model. The
 * phrase book already exists: it is the one the AI prompt teaches from (WishParser's structure and
 * biome glossaries), plus every catalog label and biome name as-is.
 *
 * IT NEVER GUESSES. Every word must be either a name it knows, a connector it understands, a number or
 * size word, or filler ("a", "near spawn", "please"). One word it cannot place and the result is
 * marked incomplete, the unknown words are listed, and the caller decides: with an AI key the wish goes
 * to the AI; without one, the understood part can be searched and the player is told exactly which
 * words were ignored. A search that silently means something other than the sentence is the one
 * failure this must not have.
 *
 * ITS OUTPUT IS THE AI'S REPLY SHAPE, fed through the same {@link WishParser#parse}. So every rule the
 * AI path has — same-structure clashes, the content-row cap, Exact mode's radius, the blacksmith, the
 * End and stronghold notes — applies here without being written twice.
 */
public final class LocalWish {

   /** {@code json} is null when nothing was recognised. {@code unknown} lists words not understood. */
   public record Result(JsonObject json, List<String> unknown, String understood) {
      public boolean found() {
         return json != null;
      }

      /** Every word accounted for — safe to search without asking an AI. */
      public boolean complete() {
         return json != null && unknown.isEmpty();
      }
   }

   private enum Kind { STRUCTURE, BIOME, SLIME }

   private record Entity(Kind kind, String name, int start, int end) {}

   /** Words that carry no meaning for a search. Deliberately generous; anything NOT here and not a
    *  name makes the wish incomplete rather than being skipped. */
   private static final Set<String> FILLER = Set.of(
         "a", "an", "the", "and", "or", "plus", "also", "some", "any", "one", "i", "im", "id", "want", "wanna",
         "would", "like", "love", "please", "pls", "give", "me", "find", "get", "make", "show", "need", "looking",
         "for", "seed", "seeds", "world", "spawn", "spawning", "at", "in", "on", "of", "to", "right", "just",
         "really", "very", "that", "has", "have", "with", "there", "is", "are", "be", "my", "it", "its",
         "can", "you", "u", "where", "which", "should", "close", "closeby", "nearby", "around", "somewhere",
         "good", "nice", "cool", "starting", "start", "point", "area", "place", "spot", "location",
         "blocks", "block", "within", "from", "away", "near", "by", "next", "beside", "besides", "together",
         "surrounded", "bordering", "adjacent", "touching", "edge", "border", "neighbouring", "neighboring",
         "chunk", "chunks", "lots", "lot", "many", "few", "couple", "pair", "several", "both", "each");

   private static final Map<String, Integer> NUMBERS = Map.ofEntries(
         Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5),
         Map.entry("six", 6), Map.entry("couple", 2), Map.entry("pair", 2), Map.entry("few", 3),
         Map.entry("several", 3), Map.entry("double", 2), Map.entry("triple", 3));

   private static final Set<String> NEGATIONS = Set.of("no", "not", "without", "avoid", "never", "isnt",
         "arent", "dont", "except", "none", "nothing", "zero");

   private static final Set<String> SIZE_BIG = Set.of("big", "large", "wide", "broad", "sprawling");
   private static final Set<String> SIZE_HUGE = Set.of("huge", "giant", "massive", "ginormous", "enormous", "vast", "mega");
   private static final Set<String> SIZE_SMALL = Set.of("small", "tiny", "little", "compact", "narrow");

   /** "X next to Y" connectors, as token sequences. */
   private static final List<List<String>> NEXT_TO = List.of(
         List.of("next", "to"), List.of("beside"), List.of("besides"), List.of("by"), List.of("near"),
         List.of("adjacent", "to"), List.of("bordering"), List.of("touching"), List.of("surrounded", "by"),
         List.of("on", "edge", "of"), List.of("close", "to"), List.of("right", "by"), List.of("in"),
         List.of("inside"), List.of("neighbouring"), List.of("neighboring"));

   /** Plain structure words that are not catalog labels, each with the rows it may mean in order of
    *  preference. Only rows the live catalog has are used. */
   private static final String[][] STRUCTURE_WORDS = {
      {"village", "Surface Village"}, {"town", "Surface Village"},
      {"portal", "Ruined Portal"}, {"ruined portal", "Ruined Portal"}, {"nether portal", "Ruined Portal"},
      {"mansion", "Woodland Mansion"}, {"woodland mansion", "Woodland Mansion"},
      {"outpost", "Pillager Outpost"}, {"pillager outpost", "Pillager Outpost"},
      {"desert temple", "Desert Pyramid"}, {"pyramid", "Desert Pyramid"}, {"desert pyramid", "Desert Pyramid"},
      {"jungle temple", "Jungle Pyramid"}, {"jungle pyramid", "Jungle Pyramid"},
      {"witch hut", "Swamp Hut"}, {"swamp hut", "Swamp Hut"}, {"witch house", "Swamp Hut"},
      {"monument", "Ocean Monument"}, {"ocean monument", "Ocean Monument"},
      {"shipwreck", "Shipwreck"}, {"ship", "Shipwreck"}, {"boat", "Shipwreck"}, {"wreck", "Shipwreck"},
      {"fortress", "Nether Fortress"}, {"nether fortress", "Nether Fortress"},
      {"bastion", "Bastion Remnant"}, {"trial chamber", "Trial Chambers"}, {"trial chambers", "Trial Chambers"},
      {"ancient city", "Ancient City"}, {"mineshaft", "Mineshaft"}, {"mine shaft", "Mineshaft"},
      {"igloo", "Igloo"}, {"trail ruins", "Trail Ruins"}, {"desert well", "Desert Well"}, {"wishing well", "Desert Well"},
      // No bare "well" (1.44.2): "a village as well" was adding a Desert Well to the search.
      {"buried treasure", "Buried Treasure"}, {"treasure", "Buried Treasure"},
      {"ocean ruins", "Ocean Ruins"}, {"ocean ruin", "Ocean Ruins"}, {"fossil", "Nether Fossil"},
   };

   private LocalWish() {
   }

   /** Reads {@code wish} against the live catalog. Catalog must be ready. */
   public static Result parse(String wish) {
      List<SeedCriteria.StructureTarget> catalog = SeedCatalogCache.list();
      List<Identifier> biomes = SeedCatalogCache.biomeList();
      if (catalog == null || biomes == null || wish == null) {
         return new Result(null, List.of(), "");
      }
      Map<String, Entity> dict = dictionary(catalog, biomes);
      List<String> tokens = tokens(wish);
      List<Entity> found = new ArrayList<>();
      Set<Integer> used = new HashSet<>();

      for (int i = 0; i < tokens.size(); ) {
         Entity hit = null;
         for (int len = Math.min(7, tokens.size() - i); len >= 1 && hit == null; len--) {
            Entity e = dict.get(String.join(" ", tokens.subList(i, i + len)));
            if (e != null) {
               hit = new Entity(e.kind(), e.name(), i, i + len);
            }
         }
         if (hit != null) {
            found.add(hit);
            for (int k = hit.start(); k < hit.end(); k++) {
               used.add(k);
            }
            i = hit.end();
         } else {
            i++;
         }
      }

      JsonObject root = new JsonObject();
      JsonArray structures = new JsonArray();
      JsonArray biomeArr = new JsonArray();
      JsonArray adjacent = new JsonArray();
      JsonArray exclude = new JsonArray();
      JsonArray without = new JsonArray();
      List<String> said = new ArrayList<>();
      boolean inside = false;
      for (int k = 0; k < tokens.size(); k++) {
         String t = tokens.get(k);
         if ((t.equals("inside") || t.equals("in")) && k > 0 && tokens.get(k - 1).equals("spawn")) {
            inside = true;
         }
      }

      for (int idx = 0; idx < found.size(); idx++) {
         Entity e = found.get(idx);
         // Look back to the previous entity (or the start) for a negation, a size, a count.
         int from = idx == 0 ? 0 : found.get(idx - 1).end();
         boolean negated = false;
         String size = "any";
         int count = 1;
         boolean countGiven = false;
         for (int k = from; k < e.start(); k++) {
            String t = tokens.get(k);
            if (NEGATIONS.contains(t)) {
               negated = true;
               used.add(k);
            } else if (SIZE_HUGE.contains(t)) {
               size = "huge";
               used.add(k);
            } else if (SIZE_BIG.contains(t)) {
               size = "big";
               used.add(k);
            } else if (SIZE_SMALL.contains(t)) {
               size = "small";
               used.add(k);
            } else if (NUMBERS.containsKey(t)) {
               count = NUMBERS.get(t);
               countGiven = true;
               used.add(k);
            } else if (t.matches("[1-9]")) {
               // Single digits only: a two-digit number here is far more often a distance ("within 15
               // of a village") than a count, and reading it as one would ask for fifteen villages.
               count = Integer.parseInt(t);
               countGiven = true;
               used.add(k);
            }
         }
         // "X next to Y": a connector between this entity and the previous one.
         boolean linked = false;
         if (idx > 0 && !negated) {
            List<String> between = tokens.subList(found.get(idx - 1).end(), e.start());
            for (List<String> c : NEXT_TO) {
               if (java.util.Collections.indexOfSubList(between, c) >= 0) {
                  linked = true;
                  break;
               }
            }
         }
         switch (e.kind()) {
            case SLIME -> {
               JsonObject s = new JsonObject();
               // THE NUMBER ASKED FOR (1.44.2), not raised to three behind the player's back: "two slime
               // chunks" used to search for three. Three is only the DEFAULT when no number was said,
               // the same default the AI path is told to use. A count too small to narrow anything is
               // then explained by the search itself (SlimeChunks.narrows) rather than changed.
               s.addProperty("count", countGiven ? count : 3);
               root.add("slime", s);
               said.add("slime chunks");
            }
            case BIOME -> {
               JsonObject o = new JsonObject();
               o.addProperty("name", e.name());
               if (negated) {
                  exclude.add(o);
                  said.add("no " + e.name().replace('_', ' '));
               } else {
                  o.addProperty("size", size);
                  biomeArr.add(o);
                  said.add((size.equals("any") ? "" : size + " ") + e.name().replace('_', ' '));
               }
            }
            case STRUCTURE -> {
               JsonObject o = new JsonObject();
               o.addProperty("name", e.name());
               if (negated) {
                  (e.name().contains(" with ") || e.name().equals("Abandoned Village") ? without : exclude).add(o);
                  said.add("no " + e.name());
               } else {
                  if (count > 1) {
                     o.addProperty("count", count);
                  }
                  if (inside) {
                     o.addProperty("spawn_inside", true);
                  }
                  if (!size.equals("any")) {
                     o.addProperty("size", "big");
                  }
                  structures.add(o);
                  said.add((count > 1 ? count + " × " : "") + e.name());
               }
            }
         }
         if (linked && e.kind() != Kind.SLIME && found.get(idx - 1).kind() != Kind.SLIME) {
            Entity prev = found.get(idx - 1);
            // When one end is a biome and the other a structure, the biome is the anchor ("a").
            Entity a = prev.kind() == Kind.BIOME || e.kind() != Kind.BIOME ? prev : e;
            Entity b = a == prev ? e : prev;
            if (!(a.kind() == Kind.BIOME && b.kind() == Kind.BIOME && a.name().equals(b.name()))) {
               JsonObject pair = new JsonObject();
               pair.addProperty("a", a.name());
               pair.addProperty("b", b.name());
               adjacent.add(pair);
            }
         }
      }

      // Words accounted for: names, the modifiers consumed above, and filler.
      List<String> unknown = new ArrayList<>();
      for (int k = 0; k < tokens.size(); k++) {
         String t = tokens.get(k);
         if (used.contains(k) || FILLER.contains(t) || NEGATIONS.contains(t) || t.matches("\\d+")
               || SIZE_BIG.contains(t) || SIZE_HUGE.contains(t) || SIZE_SMALL.contains(t) || NUMBERS.containsKey(t)
               || endOrStronghold(t)) {
            continue;
         }
         unknown.add(t);
      }
      if (structures.isEmpty() && biomeArr.isEmpty() && exclude.isEmpty() && without.isEmpty() && !root.has("slime")) {
         return new Result(null, List.copyOf(new LinkedHashSet<>(unknown)), "");
      }
      root.add("structures", structures);
      root.add("biomes", biomeArr);
      root.add("adjacent", adjacent);
      root.add("exclude", exclude);
      root.add("without", without);
      root.addProperty("stronghold_mentioned", false);
      root.addProperty("cant", "");
      return new Result(root, List.copyOf(new LinkedHashSet<>(unknown)), String.join(", ", said));
   }

   /** Words the parser's own End and stronghold notes answer from the raw wish — known, not unknown. */
   private static boolean endOrStronghold(String t) {
      return switch (t) {
         case "stronghold", "strongholds", "elytra", "end", "ender", "eye", "eyes", "city", "cities" -> true;
         default -> false;
      };
   }

   /** Lower-case words, punctuation and apostrophes removed, articles kept for the phrase match. */
   static List<String> tokens(String text) {
      String clean = text.toLowerCase(Locale.ROOT).replace("'", "").replaceAll("[^a-z0-9]+", " ").trim();
      List<String> out = new ArrayList<>();
      for (String t : clean.split(" ")) {
         if (!t.isEmpty() && !t.equals("a") && !t.equals("an") && !t.equals("the")) {
            out.add(t);
         }
      }
      return out;
   }

   private static Map<String, Entity> dictionary(List<SeedCriteria.StructureTarget> catalog, List<Identifier> biomes) {
      Map<String, Entity> dict = new HashMap<>();
      Set<String> labels = new HashSet<>();
      for (SeedCriteria.StructureTarget t : catalog) {
         if (t.special != SeedCriteria.Special.DUNGEON) {
            labels.add(t.label);
         }
      }
      // Biomes first, so a structure phrase that spells the same words wins where they collide
      // ("desert portal" is a portal; "desert" alone is a biome).
      for (Identifier b : biomes) {
         String path = b.getPath();
         put(dict, path.replace('_', ' '), new Entity(Kind.BIOME, path, 0, 0));
      }
      for (String[] p : WishParser.biomePhrases()) {
         if (biomes.stream().anyMatch(b -> b.getPath().equals(p[1]))) {
            put(dict, p[0], new Entity(Kind.BIOME, p[1], 0, 0));
         }
      }
      for (String label : labels) {
         put(dict, label, new Entity(Kind.STRUCTURE, label, 0, 0));
      }
      for (String[] w : STRUCTURE_WORDS) {
         if (labels.contains(w[1])) {
            put(dict, w[0], new Entity(Kind.STRUCTURE, w[1], 0, 0));
         }
      }
      for (String[] p : WishParser.structurePhrases()) {
         if (labels.contains(p[1])) {
            put(dict, p[0], new Entity(Kind.STRUCTURE, p[1], 0, 0));
         }
      }
      put(dict, "slime chunks", new Entity(Kind.SLIME, "slime", 0, 0));
      put(dict, "slime chunk", new Entity(Kind.SLIME, "slime", 0, 0));
      put(dict, "slime farm", new Entity(Kind.SLIME, "slime", 0, 0));
      put(dict, "slime", new Entity(Kind.SLIME, "slime", 0, 0));
      return dict;
   }

   /** Adds a phrase and its plural, normalised the same way the wish is. */
   private static void put(Map<String, Entity> dict, String phrase, Entity e) {
      List<String> t = tokens(phrase);
      if (t.isEmpty()) {
         return;
      }
      String key = String.join(" ", t);
      dict.put(key, e);
      String last = t.get(t.size() - 1);
      String plural = last.endsWith("s") ? last + "es" : last.endsWith("y") ? last.substring(0, last.length() - 1) + "ies" : last + "s";
      List<String> p = new ArrayList<>(t.subList(0, t.size() - 1));
      p.add(plural);
      dict.putIfAbsent(String.join(" ", p), e);
   }
}
