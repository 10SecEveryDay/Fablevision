package com.fablevision.client.seedfinder;

import java.util.List;

/**
 * THE SEED IN THE WORLD SETTINGS, AND WHAT THE SEARCH SAID ABOUT IT — what the badge on the Create
 * World screen and the message on joining are built from.
 *
 * MOVED OUT OF SpawnWishScreen IN 1.46.0, unchanged in meaning. Two paths now put a seed in the world
 * settings — the single search that applies its first find, and "Use this seed" on one of several found
 * worlds — and both must leave exactly the same state behind, or the join message describes a different
 * world from the one created. So both build an {@link Applied} the same way ({@link Applied#of}) and
 * hand it to {@link #set}; there is no second way to fill these fields. Living outside the screen class
 * also lets {@code worldsDiag} check what a "Use this seed" stores without a game.
 */
public final class AppliedSeed {

   /**
    * One seed as the world settings will hold it: the number, the search's stats in the two wordings
    * the badge and the join message use, the lines the search verified, the same finds as data, and the
    * note about anything that could not be checked.
    */
   public record Applied(String seed, String stats, String shortStats, List<String> matches,
                         List<SeedCriteria.Find> finds, long checked, String took, String note) {

      /**
       * The ONE way an Applied is made. {@code checked} and {@code elapsedMs} are how many seeds the search
       * had tried, and for how long, when this seed was found — for a single search that is the whole
       * search, which is what this has always said.
       */
      public static Applied of(long seed, List<String> matches, List<SeedCriteria.Find> finds, long checked,
                               long elapsedMs, String note) {
         String took = SeedFinder.prettyMs(elapsedMs);
         return new Applied(Long.toString(seed), String.format("searched %,d seeds in %s", checked, took),
               shortCount(checked) + " seeds · " + took,
               matches == null ? List.of() : matches, finds == null ? List.of() : finds, checked, took,
               note == null ? "" : note);
      }
   }

   /** Last seed typed into the Create World screen (for its little ✓ badge). */
   public static volatile String seed;
   /** Search stats for that seed — full sentence for chat, short form for the badge. */
   public static volatile String stats = "";
   public static volatile String shortStats = "";
   /** What the search verified ("Spawn @ 120, -80", "Pale Garden right at spawn"). */
   public static volatile List<String> matches = List.of();
   /** The same finds as data, for the SHORT join message and the map page. May be empty. */
   public static volatile List<SeedCriteria.Find> finds = List.of();
   /** Seeds searched, for the short header ("1.4B seeds"). */
   public static volatile long checked;
   /** How long it took, already formatted ("3m36s"). */
   public static volatile String took = "";
   /** The AI's honest "couldn't check this part" note, if any. */
   public static volatile String note = "";
   private static boolean announcePending;

   /** Puts {@code a} in as the applied seed, and arms the message for the first join of that world. */
   public static void set(Applied a) {
      seed = a.seed();
      stats = a.stats();
      shortStats = a.shortStats();
      matches = a.matches();
      finds = a.finds();
      checked = a.checked();
      took = a.took();
      note = a.note();
      announcePending = true;
   }

   /**
    * A new search is starting: the seed in the settings is no longer one this search found. Exactly what
    * starting a search has done since the stale-seed fix — the seed and its finds go, nothing else.
    */
   public static void forget() {
      seed = null;
      finds = List.of();
   }

   /** One-shot: true when the freshly joined world is the seed we applied (chat the stats). */
   public static boolean consumeAnnounce(String worldSeed) {
      if (announcePending && worldSeed != null && worldSeed.equals(seed)) {
         announcePending = false;
         return true;
      }
      return false;
   }

   /** "1.4B", "12.0M", "340k", "812" — the badge's and the join message's one spelling of a count. */
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

   private AppliedSeed() {
   }
}
