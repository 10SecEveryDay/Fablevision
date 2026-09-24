package com.fablevision.client.seedfinder;

import java.util.List;

/**
 * Which join message a found seed gets: the SHORT one (the default) or the long explanation.
 *
 * THE AGREED RULE, in one place so it cannot drift again. The long form is for exactly three cases:
 *
 *   1. SOMETHING WAS DROPPED — part of the wish could not be checked, was refused, or was read as
 *      something else. The player has to be told, and "couldn't check: …" belongs next to the full list.
 *   2. A FIND IN ANOTHER DIMENSION — a Nether coordinate needs its "measured from where a portal at
 *      your spawn comes out" sentence to mean anything.
 *   3. A BIG SPAWN-VS-ORIGIN GAP — Fast mode aims at the world origin; when a find's distance from
 *      the origin and from the player's real spawn differ by more than {@link #BIG_GAP}, the two
 *      numbers describe noticeably different walks and both are worth printing.
 *
 * WHY IT KEPT COMING BACK LONG (1.43.2). Two rules had drifted from that list:
 *
 *   - The gap threshold was 32 blocks. A Fast search's origin and the player's spawn are normally
 *     100–300 blocks apart, so nearly every Fast result crossed it and got the paragraph. And the short
 *     form already prints the distance from where the player stands — Fast mode confirms every find is
 *     inside the reach from the real spawn — so a small gap never makes the short line wrong.
 *   - ANY note forced the long form, including notes that are only explanations (the ℹ ones, and the
 *     stronghold sentence). Those are not "something dropped"; they now ride along as one line of the
 *     short form.
 *
 * And one had been removed that should not have been: a Nether find had stopped forcing the long form.
 * It is back, because it is one of the three agreed cases.
 */
public final class JoinForm {

   /**
    * How far apart a find's origin distance and spawn distance must be before the long form is worth
    * it. 150 blocks is about nine chunks — past that the two numbers are two different trips. Below it
    * the short form's single number (measured from where the player stands) is simply the right one.
    */
   public static final int BIG_GAP = 150;

   /**
    * The separate notes inside one note string. Notes are joined with "; " by the parser and the
    * screen, so a single string can hold an explanation AND a drop.
    *
    * JUDGED ONE PART AT A TIME (1.44.2). The whole string used to be judged by how it STARTED, so
    * "ℹ an explanation; the armorer couldn't be checked" counted as an explanation and the drop was
    * lost from the short message, while the same two in the other order sent the paragraph for nothing.
    */
   static List<String> parts(String note) {
      List<String> out = new java.util.ArrayList<>();
      if (note == null || note.isBlank()) {
         return out;
      }
      for (String p : note.split("; ")) {
         if (!p.isBlank()) {
            out.add(p.trim());
         }
      }
      return out;
   }

   private static boolean statementPart(String p) {
      return p.startsWith(WishParser.STRONGHOLD_CANT) || p.startsWith(WishParser.PLAIN_NOTE);
   }

   /** Every part of this note EXPLAINS something; none reports a part of the wish that was dropped. */
   public static boolean isStatement(String note) {
      List<String> ps = parts(note);
      return !ps.isEmpty() && ps.stream().allMatch(JoinForm::statementPart);
   }

   /** The explanation parts of a note, without their ℹ marker — what the short message prints. */
   public static List<String> statements(String note) {
      List<String> out = new java.util.ArrayList<>();
      for (String p : parts(note)) {
         if (statementPart(p)) {
            out.add(p.replace(WishParser.PLAIN_NOTE, ""));
         }
      }
      return out;
   }

   /** Some part of the wish was dropped, refused or reinterpreted, and the player must be told. */
   public static boolean somethingDropped(String note) {
      return parts(note).stream().anyMatch(p -> !statementPart(p));
   }

   /** True for the three agreed cases above; false means the short form. */
   public static boolean needsLongForm(List<SeedCriteria.Find> finds, String note) {
      // NO FINDS IS NOT A FOURTH CASE (1.44.2). It used to be: a slime-chunk or "no desert" search
      // has nothing to point at, so it has no structured finds, so it got the long form — for a
      // search where nothing was dropped and nothing needed explaining. joinRepro caught it on real
      // searches after joinDiag, fed only imagined inputs, had passed. The short form now prints
      // what such a search verified (FableVisionClient.announceShort).
      if (somethingDropped(note)) {
         return true;
      }
      if (finds == null) {
         return false;
      }
      for (SeedCriteria.Find f : finds) {
         if (f.dim() != SeedCriteria.Dim.OVERWORLD) {
            return true;
         }
         if (f.fromSpawn() >= 0 && Math.abs(f.fromSpawn() - f.distance()) > BIG_GAP) {
            return true;
         }
      }
      return false;
   }

   /**
    * What the join message says when coordinates are hidden (1.44.5). It said "show them from the map
    * screen", and the map screen that has the Coords toggle — the search result's — is only reachable
    * from Create World, before the world exists; in the world there was no such place. Two things DO
    * work from inside the world, and this names them: the map key (your own world's map always shows
    * coordinates, and the structures the search found are on it) and, for next time, the Coords
    * toggle on the Custom spawn screen.
    */
   public static String coordsHiddenHint(String mapKeyName) {
      return "coordinates hidden — press " + mapKeyName + " for this world's map, or turn Coords on in Custom spawn next time";
   }

   /** The "slime chunks are 1 in 10, this narrows nothing" explanation has been shown this session. */
   private static boolean slimeNoteShown;

   /**
    * The note with the slime explanation left out if it has already been shown this session (1.44.4),
    * like the /locate line. It is true every time, but after the first it was the same paragraph on
    * every slime search — on the search screen and again in the join message, which both read this
    * note. Both spellings are caught (the picker's and the wish parser's), and only that part of a
    * combined note is dropped. Called once per search started, by SpawnWishScreen.
    */
   public static synchronized String slimeNoteOnce(String note) {
      List<String> kept = new java.util.ArrayList<>();
      for (String part : parts(note)) {
         boolean slimeNote = part.contains("lime chunk") && part.contains("narrow anything");
         if (slimeNote && slimeNoteShown) {
            continue;
         }
         slimeNoteShown |= slimeNote;
         kept.add(part);
      }
      return String.join("; ", kept);
   }

   private JoinForm() {
   }
}
