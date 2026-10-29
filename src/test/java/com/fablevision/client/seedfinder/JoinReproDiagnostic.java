package com.fablevision.client.seedfinder;

import java.util.ArrayList;
import java.util.List;
import com.fablevision.VillageDiagnostic;
import com.fablevision.client.FableVisionConfig;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;

/**
 * THE JOIN MESSAGE, REPRODUCED END TO END on real searches.
 *
 * joinDiag checks JoinForm with hand-made inputs, and it passed while a plain Fast search still came
 * back with the long paragraph. A rule tested only on inputs someone imagined is a rule about those
 * inputs. This runs the real pipeline the screen runs — the no-key reader, the parser, a real Fast
 * search with the worker's own hot-path test and the describe re-run — and then asks JoinForm, so
 * whatever the player gets, this gets.
 *
 * {@code gradlew joinRepro}
 */
public final class JoinReproDiagnostic {

   public static void main(String[] args) throws Exception {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      SeedCatalogCache.ensure(reg);
      for (int waited = 0; !SeedCatalogCache.ready() && waited < 60_000; waited += 100) {
         Thread.sleep(100);
      }
      WorldgenContext ctx = WorldgenContext.get(reg);
      FableVisionConfig.seedFastMode = true;
      FableVisionConfig.seedFastDistance = 100;   // the default

      String[] wishes = {
         "village", "village with an armorer", "pillager outpost", "shipwreck", "ruined portal",
         "cherry grove", "plains", "desert", "village next to a plains", "village and a ruined portal",
         "3 slime chunks", "no desert", "trial chambers", "mineshaft",
      };
      int longs = 0;
      for (String wish : wishes) {
         LocalWish.Result local = LocalWish.parse(wish);
         if (!local.found()) {
            System.out.println("  " + wish + ": not readable without AI");
            continue;
         }
         WishParser.Outcome out = WishParser.parse(local.json().toString(), wish);
         if (out.error() != null) {
            System.out.println("  " + wish + ": " + out.error());
            continue;
         }
         // The note exactly as SpawnWishScreen.startAi builds it on the no-key path.
         String ignored = local.unknown().isEmpty() ? ""
               : "Didn't understand \"" + String.join(" ", local.unknown()) + "\" without an AI key";
         String note = out.note() == null ? "" : out.note();
         note = ignored.isEmpty() ? note : note.isEmpty() ? ignored : ignored + "; " + note;

         SeedCriteria c = out.criteria();
         java.util.Random rnd = new java.util.Random(wish.hashCode());
         List<SeedCriteria.Find> finds = null;
         List<String> matches = null;
         int[] worst = new int[1];
         long until = System.currentTimeMillis() + 60_000;
         while (finds == null && System.currentTimeMillis() < until) {
            long seed = rnd.nextLong();
            List<String> lines = new ArrayList<>();
            if (!c.test(seed, ctx, lines, true, worst)) {
               continue;
            }
            // SeedFinder.describe: the re-run that builds the structured finds.
            List<String> full = new ArrayList<>();
            List<SeedCriteria.Find> f = new ArrayList<>();
            if (c.test(seed, ctx, full, true, null, true, f)) {
               finds = f;
               matches = full;
            }
         }
         if (finds == null) {
            System.out.println("  " + wish + ": no seed in 60s");
            continue;
         }
         boolean longForm = JoinForm.needsLongForm(finds, note);
         if (longForm) {
            longs++;
         }
         System.out.println("  " + (longForm ? "LONG " : "short") + "  " + wish + "   finds=" + finds.size()
               + (note.isEmpty() ? "" : "  note=\"" + note + "\"") + "   why: " + why(finds, note));
      }
      System.out.println(longs == 0 ? "ALL SHORT" : longs + " plain Fast search(es) got the LONG message");
      System.exit(longs == 0 ? 0 : 1);
   }

   private static String why(List<SeedCriteria.Find> finds, String note) {
      if (finds.isEmpty()) {
         return "NO STRUCTURED FINDS";
      }
      if (JoinForm.somethingDropped(note)) {
         return "note counted as something dropped";
      }
      for (SeedCriteria.Find f : finds) {
         if (f.dim() != SeedCriteria.Dim.OVERWORLD) {
            return "find in " + f.dim();
         }
         if (f.fromSpawn() >= 0 && Math.abs(f.fromSpawn() - f.distance()) > JoinForm.BIG_GAP) {
            return "gap " + Math.abs(f.fromSpawn() - f.distance());
         }
      }
      return "-";
   }

   private JoinReproDiagnostic() {
   }
}
