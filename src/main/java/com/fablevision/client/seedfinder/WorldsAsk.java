package com.fablevision.client.seedfinder;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Give me 3 worlds with…", "five seeds that have…", "2 options for…": how many DIFFERENT worlds a wish
 * asks for, read from the player's own words (1.46.0).
 *
 * ONE READER FOR BOTH PATHS. {@link LocalWish} uses it to take the phrase out before it reads counts, and
 * {@link WishParser} uses it to check the AI's answer against what was typed. It matters most for what it
 * keeps apart: "3 seeds with a village" is three worlds with a village each, and "2 shipwrecks" is still
 * two shipwrecks in ONE world. Before 1.46.0 the offline reader took the "3" in "3 seeds with a village"
 * as three villages.
 *
 * THE RULE, kept narrow so it cannot eat a structure count: a number (digits, or one to ten, or couple /
 * pair / few / several) directly before "worlds", "seeds" or "options" — with at most two neutral words
 * between ("3 different seeds", "a couple of worlds"). A range written in front of it ("3 or 4 seeds",
 * "3-5 worlds") is part of the same phrase and the larger number counts. Words that change what is
 * searched ("3 survival seeds", "3 village seeds") are not neutral, so those are left to the AI and its
 * check in WishParser.
 */
public final class WorldsAsk {

   /** The phrase: the count asked for, and the token span [start, end) it covers. */
   public record Phrase(int count, int start, int end) {
      public boolean covers(int token) {
         return token >= start && token < end;
      }
   }

   static final Set<String> NOUNS = Set.of("world", "worlds", "seed", "seeds", "option", "options");

   /** Words allowed between the number and the noun because they change nothing about the search. */
   static final Set<String> NEUTRAL = Set.of("of", "different", "distinct", "separate", "unique", "other",
         "more", "new", "random", "possible", "good", "great", "nice", "cool");

   private static final Map<String, Integer> WORDS = Map.ofEntries(
         Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
         Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
         Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("couple", 2), Map.entry("pair", 2),
         Map.entry("double", 2), Map.entry("few", 3), Map.entry("several", 3), Map.entry("triple", 3));

   /** The number a token stands for, or -1. Digits up to four places; words as above. */
   static int value(String token) {
      if (token == null || token.isEmpty()) {
         return -1;
      }
      Integer w = WORDS.get(token);
      if (w != null) {
         return w;
      }
      if (token.length() <= 4 && token.chars().allMatch(Character::isDigit)) {
         return Integer.parseInt(token);
      }
      return -1;
   }

   /** The worlds phrase in these tokens ({@link LocalWish#tokens}), or null when there is none. */
   public static Phrase find(List<String> tokens) {
      for (int i = 0; i < tokens.size(); i++) {
         int n = value(tokens.get(i));
         if (n < 1) {
            continue;
         }
         int j = i + 1;
         int between = 0;
         while (j < tokens.size() && between < 2 && NEUTRAL.contains(tokens.get(j))) {
            j++;
            between++;
         }
         if (j >= tokens.size() || !NOUNS.contains(tokens.get(j))) {
            continue;
         }
         int start = i;
         int count = n;
         // A range in front: "3 or 4 seeds", "three to five worlds", "3-5 options" ("3 5" once tokenised).
         int k = i - 1;
         if (k >= 0 && (tokens.get(k).equals("or") || tokens.get(k).equals("to"))) {
            k--;
         }
         if (k >= 0 && value(tokens.get(k)) >= 1) {
            start = k;
            count = Math.max(count, value(tokens.get(k)));
         }
         return new Phrase(count, start, j + 1);
      }
      return null;
   }

   /**
    * Did the player say both the number {@code n} and a worlds word anywhere? The test an AI's "worlds"
    * answer must pass when {@link #find} found no phrase — the same rule as the slime field: the JSON does
    * not get to invent a request the player never made.
    */
   public static boolean grounded(List<String> tokens, int n) {
      return tokens.stream().anyMatch(NOUNS::contains) && mentions(tokens, n) > 0;
   }

   /** How many tokens stand for the number {@code n}. */
   public static int mentions(List<String> tokens, int n) {
      int c = 0;
      for (String t : tokens) {
         if (value(t) == n) {
            c++;
         }
      }
      return c;
   }

   private WorldsAsk() {
   }
}
