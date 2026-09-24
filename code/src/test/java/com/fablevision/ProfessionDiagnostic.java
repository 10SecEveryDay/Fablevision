package com.fablevision;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.fablevision.client.seedfinder.SeedCatalog;
import com.fablevision.client.seedfinder.SeedCriteria;
import com.fablevision.client.seedfinder.SeedCriteria.Dim;
import com.fablevision.client.seedfinder.VillageLayout;
import com.fablevision.client.seedfinder.WorldgenContext;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Dev-only: derives and proves the token for a village PROFESSION, across every village biome.
 *
 * This exists because of one specific failure. "Surface Village" spans five separate structures,
 * and each biome names its buildings its own way — {@code plains_armorer_house_1} against
 * {@code desert_armorer_1}. A token derived from a plains-only census looks perfect in that
 * census and then never fires on a desert village, so the row silently answers "no" for a fifth
 * of the world and nobody finds out until someone walks it.
 *
 * Three questions per candidate token, in order, because each can kill a row on its own:
 *   1. WHICH TEMPLATE FILES exist with this spelling? A token matching zero files is a search that
 *      can never fire. This is the {@code toolsmith}-vs-{@code tool_smith} trap.
 *   2. Are those files REACHABLE from a template pool? A file no pool references is dead weight
 *      and must not count as evidence.
 *   3. Does it fire in ALL FIVE biomes when villages are really assembled, and at what rate?
 *
 * A token only ships if all three pass. {@code --args="verify"} re-runs question 3 against the
 * tokens the catalog actually ships, so the rows cannot drift from what was proven here.
 */
public final class ProfessionDiagnostic {

   /** A profession and the spellings worth trying, most likely first. The candidates are
    *  deliberately naive in places — the naive spelling is exactly what needs disproving. */
   private record Candidate(String profession, String catalogLabel, List<String> tokens) {}

   private static final List<Candidate> CANDIDATES = List.of(
         // The armorer shipped before this diagnostic existed and was the only profession row it
         // never verified — which stopped being a curiosity when the picker started printing each
         // row's measured rate, because the oldest and most prominent row was the one with no
         // number beside eleven that had one.
         new Candidate("armorer", "Village with Armorer", List.of("armorer", "armourer")),
         new Candidate("toolsmith", "Village with Toolsmith", List.of("tool_smith", "toolsmith")),
         new Candidate("weaponsmith", "Village with Weaponsmith", List.of("weaponsmith", "weapon_smith")),
         new Candidate("butcher", "Village with Butcher", List.of("butcher", "butcher_shop", "butchers_shop")),
         new Candidate("librarian", "Village with Librarian", List.of("library", "librarian")),
         new Candidate("cartographer", "Village with Cartographer", List.of("cartographer", "cartographer_house")),
         // "stable" matches 62 files and only 3 are villages — the rest are the bastion's hoglin
         // stables and the trail-ruins ones. A village row can only ever read village pieces so
         // that is harmless, but the project's rule is to qualify rather than rely on it, and
         // "_stable_" is the spelling that keeps the token inside village houses on its own.
         new Candidate("stables", "Village with Stables", List.of("_stable_", "stable", "stables")),
         // Batch two. "leatherworker" is the job the player sees in game and the building is
         // called a tannery, so the naive spelling is expected to be dead — exactly the trap the
         // first batch hit with "librarian" and "toolsmith".
         new Candidate("mason", "Village with Mason", List.of("mason", "masons", "stonemason")),
         new Candidate("leatherworker", "Village with Leatherworker",
               List.of("tannery", "leatherworker", "leather")),
         new Candidate("fletcher", "Village with Fletcher", List.of("fletcher", "fletching")),
         new Candidate("shepherd", "Village with Shepherd", List.of("shepherd", "shepherds")),
         new Candidate("fisherman", "Village with Fisherman",
               List.of("fisher", "fisherman", "fisher_cottage")));

   private static final List<ResourceKey<Structure>> VILLAGES = List.of(
         BuiltinStructures.VILLAGE_PLAINS, BuiltinStructures.VILLAGE_DESERT,
         BuiltinStructures.VILLAGE_SAVANNA, BuiltinStructures.VILLAGE_TAIGA,
         BuiltinStructures.VILLAGE_SNOWY);

   /** Every village template lives under this; a token reaching outside it is matching something
    *  that is not a village building. */
   private static final String VILLAGE_FOLDER = "village/";

   public static void main(String[] args) {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen reg = VillageDiagnostic.loadFullRegistries();
      WorldgenContext ctx = WorldgenContext.get(reg);
      StructureTemplateManager manager = VillageLayout.templates();
      if (manager == null) {
         System.out.println("FAILED: no structure templates — nothing here can be trusted.");
         System.exit(2);   // printed FAILED and exited 0 until 1.44.2
      }

      boolean verify = args.length > 0 && "verify".equals(args[0]);
      boolean deriveOnly = args.length > 0 && "derive".equals(args[0]);
      // The seed count is the first argument that is actually a number, so a mode word can be
      // passed with or without one.
      int sample = 40;
      for (String a : args) {
         try {
            sample = Integer.parseInt(a);
            break;
         } catch (NumberFormatException notANumber) {
            // a mode word, not the sample size
         }
      }

      if (args.length > 0 && "zombie".equals(args[0])) {
         zombieCheck(reg, ctx, sample);
         return;
      }

      if (verify) {
         verifyShipped(reg, ctx, sample);
         return;
      }

      System.out.println("========== STEP 1: WHICH SPELLINGS EXIST AT ALL ==========");
      System.out.println("  (a token matching no file, or no pool, can never fire)");
      Map<String, String> chosen = new TreeMap<>();
      for (Candidate c : CANDIDATES) {
         System.out.println();
         System.out.println("-- " + c.profession() + " --");
         String winner = null;
         for (String token : c.tokens()) {
            int files = countFiles(manager, token);
            int pools = countPools(reg, token);
            int outside = outsideFolder(manager, token);
            // A leak OUTSIDE village/ is reported but does not disqualify, and the distinction
            // matters. hasBuilding only ever reads the piece list of the structure that was
            // actually assembled, and a village row only assembles villages — so a bastion file
            // sharing the spelling can never be mistaken for a village building. What WOULD
            // disqualify is a leak inside village/ itself, because those pieces really can turn up
            // in the same list. Both numbers are printed so the choice is visible, not implied.
            System.out.println("   " + pad("\"" + token + "\"", 20)
                  + pad(files + " files", 12) + pad(pools + " pool refs", 14)
                  + pad(inFolder(manager, token) + " in village/", 16)
                  + (files == 0 ? "MATCHES NOTHING — dead token"
                        : pools == 0 ? "no pool references it — unreachable"
                        : inFolder(manager, token) == 0 ? "no VILLAGE file — cannot fire on a village"
                        : outside > 0 ? "usable (leaks outside village/, harmless here)"
                        : "usable"));
            if (winner == null && files > 0 && pools > 0 && inFolder(manager, token) > 0) {
               winner = token;
            }
         }
         if (winner == null) {
            System.out.println("   !!! no usable spelling for " + c.profession());
         } else {
            chosen.put(c.profession(), winner);
            System.out.println("   -> chosen token: \"" + winner + "\"");
            villageFiles(manager, winner).forEach(f -> System.out.println("        " + f));
            biomeAvailability(manager, winner);
         }
      }

      if (deriveOnly) {
         System.out.println();
         System.out.println("(step 1 only — rerun without \"derive\" for the assembly rates)");
         return;
      }

      System.out.println();
      System.out.println("========== STEP 2: DOES IT FIRE IN ALL FIVE BIOMES ==========");
      System.out.println("  " + sample + " seeds per village type, assembled for real");
      for (Candidate c : CANDIDATES) {
         String token = chosen.get(c.profession());
         if (token == null) {
            continue;
         }
         perBiome(reg, ctx, c.profession(), token, sample);
      }
      System.out.println();
      System.out.println("Tokens proven above are the ONLY ones that may be typed into SeedCatalog.");
      System.out.println("=============================================================");
   }

   /**
    * Is the Abandoned Village row a PREDICTION or a FACT?
    *
    * The row does not assemble anything. It replays the jigsaw dice — seed the structure random
    * exactly as vanilla does, consume the rotation draw, take the start-template pick — and calls
    * the village abandoned when that start piece is a zombie variant. That is enormously cheaper
    * than assembling, which is why it runs in the cheap confirm() stage, but "we re-derived the
    * dice roll" is a claim, and the row's note has been hedging it as a guess ever since.
    *
    * This settles it. For each seed the prediction is compared against the ASSEMBLED piece list,
    * which is ground truth: a zombie village builds its pieces out of village/<biome>/zombie/,
    * and a normal one does not. If the two never disagree, the prediction is not a guess, it is
    * the same answer arrived at more cheaply, and the note should say so.
    */
   private static void zombieCheck(RegistryAccess.Frozen reg, WorldgenContext ctx, int sample) {
      System.out.println("========== ABANDONED VILLAGE: PREDICTION vs ASSEMBLED TRUTH ==========");
      System.out.println("  prediction = the start-piece dice replay used by the shipped row");
      System.out.println("  truth      = does the assembled piece list come out of village/*/zombie/");
      System.out.println();
      int agree = 0;
      int disagree = 0;
      int zombies = 0;
      int checked = 0;
      for (ResourceKey<Structure> key : VILLAGES) {
         Holder<Structure> village;
         try {
            village = reg.lookupOrThrow(Registries.STRUCTURE).getOrThrow(key);
         } catch (Exception missing) {
            continue;
         }
         String biome = key.identifier().getPath().replace("village_", "");
         int biomeAgree = 0;
         int biomeZombie = 0;
         int biomeBuilt = 0;
         // BALANCED ON PURPOSE. Zombie villages are about one in fifty, so a straight scan of 30
         // seeds contains one of them and a predictor that simply answered "no" every time would
         // score 29/30. That measures nothing. Because the prediction costs no assembly, seeds can
         // be sifted for free and only the chosen ones built — half predicted zombie, half not —
         // which is the only shape in which agreement means anything.
         int wantEach = Math.max(5, sample / 2);
         int gotZombie = 0;
         int gotNormal = 0;
         for (long seed = 1; seed <= 200_000L && (gotZombie < wantEach || gotNormal < wantEach); seed++) {
            boolean predicted = SeedCriteria.isZombieStart(seed, new ChunkPos(0, 0), village);
            if (predicted && gotZombie >= wantEach) {
               continue;
            }
            if (!predicted && gotNormal >= wantEach) {
               continue;
            }
            RandomState full = ctx.fullRandomState(Dim.OVERWORLD, seed);
            List<Identifier> pieces = VillageLayout.pieceTemplates(
                  reg, ctx, seed, new ChunkPos(0, 0), village, full);
            if (pieces.isEmpty()) {
               continue;
            }
            if (predicted) {
               gotZombie++;
            } else {
               gotNormal++;
            }
            biomeBuilt++;
            checked++;
            // Truth: the START piece is pieces.get(0) — the town centre the whole village grows
            // from. A zombie village's centre lives under .../zombie/.
            boolean reallyZombie = pieces.get(0).getPath().contains("/zombie/");
            if (reallyZombie) {
               biomeZombie++;
               zombies++;
            }
            if (predicted == reallyZombie) {
               agree++;
               biomeAgree++;
            } else {
               disagree++;
               System.out.println("   MISMATCH  " + biome + " seed " + seed
                     + "  predicted=" + predicted + "  assembled=" + reallyZombie
                     + "  start=" + pieces.get(0).getPath());
            }
         }
         System.out.println("   " + pad(biome, 12) + pad(biomeAgree + "/" + biomeBuilt + " agree", 16)
               + pad(gotZombie + " predicted zombie", 22)
               + biomeZombie + " really were");
      }
      System.out.println();
      System.out.println("  villages checked   : " + checked);
      System.out.println("  zombie villages    : " + zombies);
      System.out.println("  prediction agreed  : " + agree);
      System.out.println("  prediction WRONG   : " + disagree);
      System.out.println(disagree == 0
            ? "  The prediction matched the assembled village every single time. It is not a guess —\n"
              + "  it is the same answer, read from the dice instead of from the finished building."
            : "  !!! the prediction disagrees with what actually assembles — the row is unsound.");
      System.out.println("=====================================================================");
   }

   /** Re-runs the per-biome proof against the tokens the picker really ships, so a row can never
    *  drift from the derivation that justified it. */
   private static void verifyShipped(RegistryAccess.Frozen reg, WorldgenContext ctx, int sample) {
      System.out.println("========== SHIPPED PROFESSION ROWS: PER-BIOME PROOF ==========");
      List<SeedCriteria.StructureTarget> catalog = SeedCatalog.structures(ctx);
      int checked = 0;
      int bad = 0;
      for (Candidate c : CANDIDATES) {
         SeedCriteria.StructureTarget row = catalog.stream()
               .filter(t -> c.catalogLabel().equals(t.label))
               .findFirst()
               .orElse(null);
         if (row == null) {
            System.out.println("  MISSING ROW: " + c.catalogLabel());
            bad++;
            missingRows++;
            continue;
         }
         checked++;
         if (!perBiome(reg, ctx, c.profession(), row.building, sample)) {
            bad++;
         }
      }
      System.out.println();
      System.out.println("  rows checked : " + checked + "   rows with an unobserved biome: " + bad);
      // Deliberately NOT called a failure. Step 1 lists the real template files per biome, so a
      // token's SPELLING is already proven for every biome that has the building; what a zero here
      // means is that forty seeds did not happen to roll one. Desert toolsmiths are the standing
      // example — the asset and ten pool references exist, they are just uncommon. Calling that a
      // broken row would train whoever reads this to ignore the line that matters.
      System.out.println(bad == 0
            ? "  OK — every shipped row was observed in every biome that can have it."
            : "  OK with a gap: a biome that CAN have the building did not roll one in " + sample
              + " seeds.\n  That is rarity, not a broken token — the per-biome lines above say which.");
      System.out.println("  missing rows: " + missingRows + "   spellings the token misses: " + spellingMisses);
      System.out.println("=============================================================");
      // Rarity is not a failure (see above); a missing row or a missed spelling is. Exited 0 either way
      // until 1.44.2.
      System.exit(missingRows == 0 && spellingMisses == 0 ? 0 : 1);
   }

   private static int missingRows;
   private static int spellingMisses;

   /**
    * Hit rate per village biome, plus the real template names seen.
    *
    * The verdict cross-references the ASSET LIST, and it has to: zero hits in forty seeds means
    * two completely different things. If a template with this spelling exists for the biome, zero
    * is rarity and more seeds will find one — desert toolsmiths read 0/40 here and the file is
    * right there in the assets. If no template exists, zero is permanent, and a row that reports
    * it as merely rare would send someone hunting something that cannot be generated.
    *
    * It returns false for the RARE case (the template exists, forty seeds did not roll it). This
    * comment used to say the opposite — "false only for a spelling the token misses" — and that case
    * was not detected at all until 1.44.2; it is now counted in {@link #spellingMisses} and fails the run.
    */
   private static boolean perBiome(RegistryAccess.Frozen reg, WorldgenContext ctx, String profession,
                                   String token, int sample) {
      return perBiome(reg, ctx, profession, token, sample, VillageLayout.templates());
   }

   private static boolean perBiome(RegistryAccess.Frozen reg, WorldgenContext ctx, String profession,
                                   String token, int sample, StructureTemplateManager manager) {
      System.out.println();
      System.out.println("-- " + profession + "   token \"" + token + "\" --");
      boolean allFire = true;
      int totalHits = 0;
      int totalBuilt = 0;
      for (ResourceKey<Structure> key : VILLAGES) {
         Holder<Structure> village;
         try {
            village = reg.lookupOrThrow(Registries.STRUCTURE).getOrThrow(key);
         } catch (Exception missing) {
            System.out.println("   " + pad(key.identifier().getPath(), 18) + "not in this version");
            continue;
         }
         int hits = 0;
         int built = 0;
         Map<String, Integer> names = new TreeMap<>();
         for (long seed = 1; seed <= sample; seed++) {
            RandomState full = ctx.fullRandomState(Dim.OVERWORLD, seed);
            List<Identifier> pieces = VillageLayout.pieceTemplates(
                  reg, ctx, seed, new ChunkPos(0, 0), village, full);
            if (pieces.isEmpty()) {
               continue;
            }
            built++;
            if (VillageLayout.hasBuilding(pieces, token)) {
               hits++;
            }
            for (Identifier id : pieces) {
               String path = id.getPath();
               if (com.fablevision.client.seedfinder.VillageLayout.tokenMatches(path, token)) {
                  names.merge(path.substring(path.lastIndexOf('/') + 1), 1, Integer::sum);
               }
            }
         }
         totalHits += hits;
         totalBuilt += built;
         int pct = built == 0 ? 0 : hits * 100 / built;
         String biome = key.identifier().getPath().replace("village_", "");
         boolean assetExists = manager != null && villageFiles(manager, token).stream()
               .anyMatch(f -> f.startsWith(VILLAGE_FOLDER + biome + "/"));
         // AN INDEPENDENT SPELLING CHECK (1.44.2). assetExists is decided with the TOKEN, so a token that
         // misses a biome's spelling found no file there and was reported "IMPOSSIBLE HERE" — a
         // confident wrong verdict, and exactly how snowy_weapon_smith_1 hid until 1.43.0. This looks
         // for the profession's own word in the biome's house files with every underscore removed,
         // which knows nothing about the token and so can disagree with it.
         String word = profession.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
         boolean looseExists = manager != null && manager.listTemplates().map(Identifier::getPath)
               .anyMatch(p -> p.startsWith(VILLAGE_FOLDER + biome + "/houses/")
                     && p.substring(p.lastIndexOf('/') + 1).replace("_", "").contains(word));
         String verdict;
         if (hits > 0) {
            verdict = String.join(", ", names.keySet());
         } else if (!assetExists && looseExists) {
            verdict = "SPELLING MISSED - this biome HAS the building under a name the token does not match";
            spellingMisses++;
         } else if (!assetExists) {
            verdict = "IMPOSSIBLE HERE - this biome has no such building";
         } else {
            verdict = "none in " + built + " seeds - RARE, not missing (the template exists)";
            allFire = false;
         }
         System.out.println("   " + pad(biome, 12) + pad(hits + "/" + built, 9)
               + pad("(" + pct + "%)", 7) + verdict);
      }
      int pct = totalBuilt == 0 ? 0 : totalHits * 100 / totalBuilt;
      System.out.println("   " + pad("ALL BIOMES", 12) + pad(totalHits + "/" + totalBuilt, 9)
            + pad("(" + pct + "%)", 7)
            + (allFire ? "fires in every biome that can have one"
                  : "fires where it can, but some biome needs more seeds to show it"));
      return allFire;
   }

   private static int countFiles(StructureTemplateManager manager, String token) {
      String want = token.toLowerCase(Locale.ROOT);
      return (int) manager.listTemplates().map(Identifier::getPath)
            .filter(p -> com.fablevision.client.seedfinder.VillageLayout.tokenMatches(p, want)).count();
   }

   private static int outsideFolder(StructureTemplateManager manager, String token) {
      String want = token.toLowerCase(Locale.ROOT);
      return (int) manager.listTemplates().map(Identifier::getPath)
            .filter(p -> com.fablevision.client.seedfinder.VillageLayout.tokenMatches(p, want))
            .filter(p -> !p.startsWith(VILLAGE_FOLDER)).count();
   }

   private static int countPools(RegistryAccess.Frozen reg, String token) {
      String want = token.toLowerCase(Locale.ROOT);
      int[] n = {0};
      reg.lookupOrThrow(Registries.TEMPLATE_POOL).listElements().forEach(poolRef -> {
         for (var pair : poolRef.value().getTemplates()) {
            if (pair.getFirst() instanceof SinglePoolElement single
                  && com.fablevision.client.seedfinder.VillageLayout.tokenMatches(single.getTemplateLocation().getPath(), want)) {
               n[0]++;
            }
         }
      });
      return n[0];
   }

   private static int inFolder(StructureTemplateManager manager, String token) {
      return villageFiles(manager, token).size();
   }

   private static List<String> villageFiles(StructureTemplateManager manager, String token) {
      String want = token.toLowerCase(Locale.ROOT);
      return new ArrayList<>(manager.listTemplates().map(Identifier::getPath)
            .filter(p -> p.startsWith(VILLAGE_FOLDER))
            .filter(p -> com.fablevision.client.seedfinder.VillageLayout.tokenMatches(p, want)).sorted().toList());
   }

   /**
    * Which village biomes even HAVE a building with this spelling.
    *
    * This is the difference between "rare here" and "impossible here", and only the file list can
    * tell them apart — a 0/40 assembly result looks identical either way. A profession missing
    * from a biome's assets can never appear in that biome no matter how many seeds are searched,
    * so a row for it has to say so rather than let someone hunt for something that is not there.
    */
   private static void biomeAvailability(StructureTemplateManager manager, String token) {
      List<String> files = villageFiles(manager, token);
      List<String> missing = new ArrayList<>();
      StringBuilder have = new StringBuilder();
      for (ResourceKey<Structure> key : VILLAGES) {
         String biome = key.identifier().getPath().replace("village_", "");
         boolean any = files.stream().anyMatch(f -> f.startsWith(VILLAGE_FOLDER + biome + "/"));
         if (any) {
            have.append(have.length() > 0 ? ", " : "").append(biome);
         } else {
            missing.add(biome);
         }
      }
      System.out.println("        biomes that HAVE one : " + (have.length() == 0 ? "(none)" : have));
      System.out.println("        biomes that CANNOT   : "
            + (missing.isEmpty() ? "(none - available everywhere)" : String.join(", ", missing)
               + "   <- no template exists; a search there can never succeed"));
   }

   private static String pad(String s, int width) {
      StringBuilder sb = new StringBuilder(s);
      while (sb.length() < width) {
         sb.append(' ');
      }
      return sb.toString();
   }

   private ProfessionDiagnostic() {
   }
}
