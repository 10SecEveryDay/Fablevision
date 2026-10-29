package com.fablevision.client;

import com.fablevision.client.seedfinder.OldJar;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tensec.aiscreen.KeySafe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SETTINGS ON DISK (1.47.3): {@code gradlew configDiag}.
 *
 *   1. "ASK ME" IS OFF FOR NEW INSTALLS ONLY. A missing settings file (a new install) gets it off, and the file
 *      is written that way; a file that says on or off is read as it says; an older file that does not say,
 *      or cannot be read, keeps the default it had (on). CONTROL: 1.47.2's default, from the shipped jar, is on.
 *   2. NEVER HALF A FILE. Four threads save config/fablevision.json over and over while another reads it; every
 *      read must be a whole settings object. CONTROL: the same race with the old in-place write must produce
 *      reads that are not.
 *   3. THE KEY SCREEN SAYS "NOT ENCRYPTED" WHERE IT IS NOT: macOS, Linux, and Windows without the encryption
 *      library; and only "encrypted" where it is. CONTROL: 1.47.2's words, from the shipped jar, never say it.
 */
public final class ConfigDiagnostic {

   private static int failed;

   public static void main(String[] args) throws Exception {
      System.out.println("========== SETTINGS ON DISK ==========");
      boolean initial = FableVisionConfig.askCustomSpawn;   // before anything is loaded: the class's own default
      Path dir = Files.createTempDirectory("fv-configDiag");

      // ── 1. Ask me ──
      System.out.println("-- 1. \"Ask me\" (Custom spawn opens by itself) --");
      check("the default, before any settings file is read, is OFF", !initial);
      Path fresh = dir.resolve("new").resolve("fablevision.json");
      Files.createDirectories(fresh.getParent());
      FableVisionConfig.load(fresh);
      JsonObject written = read(fresh);
      check("a new install (no settings file): off, and the file it writes says off",
            !FableVisionConfig.askCustomSpawn && written != null && written.has("askCustomSpawn")
                  && !written.get("askCustomSpawn").getAsBoolean());
      check("an older file with no \"Ask me\" in it keeps the old default, on",
            loaded(dir, "older", "{\"seedFastMode\":true}"));
      check("a saved ON stays on", loaded(dir, "on", "{\"askCustomSpawn\":true}"));
      check("a saved OFF stays off", !loaded(dir, "off", "{\"askCustomSpawn\":false}"));
      check("an unreadable file (an existing install) keeps the old default, on", loaded(dir, "broken", "{not json"));
      Boolean oldDefault = oldAskDefault();
      if (oldDefault != null) {
         check("CONTROL: 1.47.2's default is ON, so the new-install check fails on it, as it must", oldDefault);
      }

      // ── 2. never half a file ──
      System.out.println("-- 2. four threads saving while another reads --");
      Path race = dir.resolve("race").resolve("fablevision.json");
      Files.createDirectories(race.getParent());
      FableVisionConfig.save(race);
      int[] atomic = race(race, () -> FableVisionConfig.save(race));
      check(String.format("the real save: %,d reads, %,d of them NOT a whole settings object (%,d could not open it)",
            atomic[0], atomic[1], atomic[2]), atomic[0] > 0 && atomic[1] == 0);
      String body = Files.readString(race);
      int[] inPlace = race(race, () -> {
         try {
            Files.writeString(race, body);   // 1.47.2's save: over the live file
         } catch (Exception ignored) {
         }
      });
      check(String.format("CONTROL: the old in-place write: %,d reads, %,d NOT whole — the check can fail",
            inPlace[0], inPlace[1]), inPlace[1] > 0);
      check("the file is whole afterwards", read(race) != null);

      // ── 3. the key screen's words ──
      System.out.println("-- 3. how the key screen says keys are kept --");
      String os = System.getProperty("os.name");
      try {
         System.setProperty("os.name", "Linux");
         String linux = KeySafe.protectionWords();
         System.setProperty("os.name", "Mac OS X");
         String mac = KeySafe.protectionWords();
         System.setProperty("os.name", "Windows 11");
         String win = KeySafe.protectionWords();
         setDpapiMissing(true);
         String winMissing = KeySafe.protectionWords();
         setDpapiMissing(false);
         System.out.println("  Linux: \"" + linux + "\"");
         System.out.println("  Windows without the library: \"" + winMissing + "\"");
         System.out.println("  Windows: \"" + win + "\"");
         check("Linux and macOS say NOT encrypted, plain text", linux.contains("NOT encrypted") && linux.contains("plain text")
               && mac.equals(linux));
         check("Windows without the encryption library says NOT encrypted", winMissing.contains("NOT encrypted"));
         check("Windows with it says encrypted, and not \"NOT\"", win.equals("encrypted to your Windows account"));
         System.setProperty("os.name", "Linux");
         String oldLinux = oldProtectionWords();
         if (oldLinux != null) {
            check("CONTROL: 1.47.2 on Linux said \"" + oldLinux + "\" — no NOT encrypted, so the check fails on it",
                  !oldLinux.contains("NOT encrypted"));
         }
      } finally {
         System.setProperty("os.name", os);
      }

      System.out.println(failed == 0 ? "ALL CHECKS PASS" : failed + " FAILED");
      System.exit(failed == 0 ? 0 : 1);
   }

   /** Loads a settings file holding {@code content}; what "Ask me" came out as. */
   private static boolean loaded(Path dir, String name, String content) throws Exception {
      Path p = dir.resolve(name).resolve("fablevision.json");
      Files.createDirectories(p.getParent());
      Files.writeString(p, content);
      FableVisionConfig.askCustomSpawn = false;   // whatever the last case left; load() must decide it
      FableVisionConfig.load(p);
      return FableVisionConfig.askCustomSpawn;
   }

   /**
    * Four writers doing {@code write} 400 times each while one reader reads the file as fast as it can.
    * Returns {reads, reads that were not a whole settings object, reads that could not open the file}.
    */
   private static int[] race(Path file, Runnable write) throws InterruptedException {
      AtomicBoolean writing = new AtomicBoolean(true);
      AtomicInteger reads = new AtomicInteger();
      AtomicInteger broken = new AtomicInteger();
      AtomicInteger busy = new AtomicInteger();
      Thread reader = new Thread(() -> {
         while (writing.get()) {
            String s;
            try {
               s = Files.readString(file);
            } catch (Exception cannotOpen) {
               busy.incrementAndGet();
               continue;
            }
            reads.incrementAndGet();
            try {
               JsonObject o = JsonParser.parseString(s).getAsJsonObject();
               if (!o.has("seedThreads") || !o.has("askCustomSpawn")) {
                  broken.incrementAndGet();
               }
            } catch (Exception notWhole) {
               broken.incrementAndGet();
            }
         }
      });
      reader.start();
      Thread[] writers = new Thread[4];
      for (int i = 0; i < writers.length; i++) {
         writers[i] = new Thread(() -> {
            for (int k = 0; k < 400; k++) {
               write.run();
            }
         });
         writers[i].start();
      }
      for (Thread w : writers) {
         w.join();
      }
      writing.set(false);
      reader.join();
      return new int[]{reads.get(), broken.get(), busy.get()};
   }

   private static JsonObject read(Path p) {
      try {
         return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
      } catch (Exception e) {
         return null;
      }
   }

   private static void setDpapiMissing(boolean missing) throws ReflectiveOperationException {
      var f = KeySafe.class.getDeclaredField("dpapiUnavailable");
      f.setAccessible(true);
      f.setBoolean(null, missing);
   }

   private static Boolean oldAskDefault() {
      try {
         if (OldJar.get() == null) {
            return null;
         }
         return (Boolean) OldJar.staticField(OldJar.cls("com.fablevision.client.FableVisionConfig"), "askCustomSpawn");
      } catch (ReflectiveOperationException | LinkageError e) {
         System.out.println("  (control could not read 1.47.2's default: " + OldJar.why(e) + ")");
         return null;
      }
   }

   private static String oldProtectionWords() {
      try {
         if (OldJar.get() == null) {
            return null;
         }
         Class<?> safe = OldJar.cls("com.tensec.aiscreen.KeySafe");
         return (String) OldJar.call(safe, null, "protectionWords", new Class<?>[0]);
      } catch (ReflectiveOperationException | LinkageError e) {
         System.out.println("  (control could not run 1.47.2's KeySafe: " + OldJar.why(e) + ")");
         return null;
      }
   }

   private static void check(String what, boolean ok) {
      System.out.println("  " + (ok ? "ok   " : "FAIL ") + what);
      if (!ok) {
         failed++;
      }
   }

   private ConfigDiagnostic() {
   }
}
