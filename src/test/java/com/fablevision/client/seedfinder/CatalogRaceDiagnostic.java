package com.fablevision.client.seedfinder;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;

/**
 * OVERLAPPING CATALOG BUILDS (1.47.3): {@code gradlew catalogDiag}.
 *
 * {@link SeedCatalogCache} builds the Custom spawn catalog on a background thread. Two builds can overlap (new
 * data packs on the same Create World screen start a second while the first runs), and a world can start
 * loading while one runs. Builds are numbered now and only the newest may publish. This drives the cache's own
 * {@code begin}/{@code finish}/{@code forget} in the orders that went wrong, with lists tagged by build so it can
 * tell whose catalog is showing:
 *
 *   1. an older build finishing AFTER the newer one does not replace it, and does not clear "building" while
 *      the newer one still runs;
 *   2. a build still running when the cache is emptied (a world loading) cannot refill it;
 *   3. a stale build's error does not land; the current build's does;
 *   4. CONTROL: the same orders through a cache without build numbers (1.47.2's rule: whatever finishes last
 *      is published) must fail checks 1 and 2 — so they can.
 */
public final class CatalogRaceDiagnostic {

   private static int failed;

   public static void main(String[] args) {
      System.out.println("========== OVERLAPPING CATALOG BUILDS ==========");
      HolderLookup.Provider packsA = provider();
      HolderLookup.Provider packsB = provider();
      HolderLookup.Provider packsC = provider();
      List<Identifier> builtA = tagged("built_from_a");
      List<Identifier> builtB = tagged("built_from_b");
      List<SeedCriteria.StructureTarget> catalog = new ArrayList<>();

      // ── 1. older build finishes last ──
      long a = SeedCatalogCache.begin(packsA);
      long b = SeedCatalogCache.begin(packsB);   // new data packs while A is still building
      boolean aEarly = SeedCatalogCache.finish(a, builtA, catalog, null);
      check("an older build finishing while the newer runs is dropped", !aEarly && !SeedCatalogCache.ready());
      check("  and does not clear \"building\" for the newer one", SeedCatalogCache.building());
      boolean bDone = SeedCatalogCache.finish(b, builtB, catalog, null);
      check("the newer build publishes", bDone && SeedCatalogCache.ready() && SeedCatalogCache.biomeList() == builtB
            && !SeedCatalogCache.building());
      long a2 = SeedCatalogCache.begin(packsA);
      long b2 = SeedCatalogCache.begin(packsB);
      SeedCatalogCache.finish(b2, builtB, catalog, null);
      boolean aLate = SeedCatalogCache.finish(a2, builtA, catalog, null);
      check("an older build finishing AFTER the newer one does not replace it",
            !aLate && SeedCatalogCache.biomeList() == builtB);

      // ── 2. forgotten while building ──
      long c = SeedCatalogCache.begin(packsC);
      SeedCatalogCache.forget();   // a world starts loading
      boolean cLate = SeedCatalogCache.finish(c, tagged("built_from_c"), catalog, null);
      check("a build still running when the cache is emptied cannot refill it",
            !cLate && !SeedCatalogCache.ready() && SeedCatalogCache.list() == null && SeedCatalogCache.biomeList() == null
                  && !SeedCatalogCache.building());

      // ── 3. errors ──
      long d = SeedCatalogCache.begin(packsA);
      long e = SeedCatalogCache.begin(packsB);
      boolean staleError = SeedCatalogCache.finish(d, null, null, "stale failure");
      check("a stale build's error does not land", !staleError && SeedCatalogCache.error() == null);
      SeedCatalogCache.finish(e, null, null, "current failure");
      check("the current build's error does", "current failure".equals(SeedCatalogCache.error())
            && !SeedCatalogCache.ready() && !SeedCatalogCache.building());
      SeedCatalogCache.forget();

      // ── 4. CONTROL: no build numbers ──
      Unnumbered old = new Unnumbered();
      old.begin(packsA);
      old.begin(packsB);
      old.finish(builtB);
      old.finish(builtA);   // the older one, last
      boolean controlOrder = old.biomes == builtB;
      old.begin(packsC);
      old.forget();
      old.finish(tagged("built_from_c"));
      boolean controlForget = old.biomes == null;
      check("CONTROL: without build numbers the older build lands over the newer (check 1 fails, as it must)",
            !controlOrder);
      check("CONTROL: without build numbers a forgotten build refills the cache (check 2 fails, as it must)",
            !controlForget);

      System.out.println(failed == 0 ? "ALL CHECKS PASS" : failed + " FAILED");
      System.exit(failed == 0 ? 0 : 1);
   }

   /** 1.47.2's publishing rule: each build writes its lists when it finishes, whatever started since. */
   private static final class Unnumbered {
      List<Identifier> biomes;

      void begin(HolderLookup.Provider from) {
         biomes = null;
      }

      void finish(List<Identifier> built) {
         biomes = built;
      }

      void forget() {
         biomes = null;
      }
   }

   /** A distinct registries object that is never read — the cache only compares it by identity. */
   private static HolderLookup.Provider provider() {
      return (HolderLookup.Provider) Proxy.newProxyInstance(CatalogRaceDiagnostic.class.getClassLoader(),
            new Class<?>[]{HolderLookup.Provider.class}, (self, m, args) -> switch (m.getName()) {
               case "equals" -> self == args[0];
               case "hashCode" -> System.identityHashCode(self);
               case "toString" -> "packs@" + Integer.toHexString(System.identityHashCode(self));
               default -> throw new UnsupportedOperationException(m.getName());
            });
   }

   private static List<Identifier> tagged(String name) {
      return new ArrayList<>(List.of(Identifier.withDefaultNamespace(name)));
   }

   private static void check(String what, boolean ok) {
      System.out.println("  " + (ok ? "ok   " : "FAIL ") + what);
      if (!ok) {
         failed++;
      }
   }

   private CatalogRaceDiagnostic() {
   }
}
