# FableVision 1.47.3 — fixes from a review of the decompiled jar

Jar: `releases\26.3-fabric\fablevision-1.47.3.jar` (Minecraft 26.3). Older jars kept. Built and checked
headless only. **SeedAccess is byte-identical to 1.47.2** (both class files, SHA-256 compared).
**SeedSearchApi:** every method byte-identical; the class differs from 1.47.2 only in the
`COMPATIBLE_RANGE` constant (`javap -p -c -l` diff = that one line). Its nested classes are byte-identical.
Pregen 1.2.0 still refuses 1.46+; rebuilding it is the separate card.

Four AI models reviewed a decompiled copy of 1.47.2. Every claim was checked against the real source
before anything was changed.

## Verdicts

| # | Claim | Verdict |
|---|---|---|
| 1 | Stuck "Searching" when the world-gen bootstrap throws | **Real.** `running` was cleared, `bootstrapping` was not, so `phase()` stayed on Searching for ever and Stop could not move it. Confirmed by running 1.47.2's own worker from the shipped jar: `running=false bootstrapping=true`. |
| 2 | Catalog builds race | **Real, narrow.** Two overlapping builds: the last to finish won, so the older registries could land over the newer. The older build's `finally` also cleared `building` while the newer was running. A build finishing after `forget()` refilled the cache. |
| 3 | Offline End/stronghold words get no notes | **Mostly false.** WishParser adds the End and stronghold notes from the player's own words, so "a village and an end city" offline already got the End note. **Real part:** a wish made *only* of those words came back "try plain names / add a key", or went to the AI. Their words were counted as understood one by one, so "city" or "eye" alone were dropped silently. **Worse, found while fixing it:** 1.47.2 read "an end ship with elytra" as a **Shipwreck** search and "a stronghold near an end portal" as a **Ruined Portal** search (shown by running 1.47.2's reader from the jar). |
| 4 | Global failure counter | **Real in code, tiny in effect.** A stopped search's workers each finish one seed, a few checks, against the watchdog's 50-check, 90% bar. Fixed anyway, because it was cheap. |
| 5 | Manual Bots can exceed the cap | **Real.** A number went up to every logical processor (32 on a 32-thread CPU), past the 16 ever tested. |
| 6 | Unsafe config save; swallowed failures; interrupt flag | **Save: real.** `fablevision.json` was written in place, with no lock (Extras saves it too). Config and KeySafe swallowed save failures: real. **Interrupt flag in SeedFinder: false.** It never catches InterruptedException. The only catch that didn't restore the flag was SearchWatchdog's, whose thread ends right there. Restored anyway. |
| 7 | Stop is slow | **Measured: no.** See below. Nothing changed. |
| 8 | README; COMPATIBLE_RANGE; non-Windows key text | **All real.** The README named 1.47.0 as newest. The range had said 1.45.x since 1.46.0. On macOS and Linux the key screen said "kept in a file only your account can read", never "unencrypted". The Custom spawn key screen did not show the storage line at all. |
| — | drawRing, radius overflow, empty End blocks, "useless continue", unused `AI_GIVE_UP_MS`, missing build files/LICENSE, modded structures, custom AI URL | **Dismissed.** `AI_GIVE_UP_MS` is used (SpawnWishScreen, the AI give-up check); the decompiler inlined it. `drawRing` is at most 720 one-pixel fills per ring, with about 5 rings visible. The Fast distance is clamped 48–2000 on load. LICENSE, build.gradle and settings.gradle are in the repo. The last two are features. |

## What changed

1. **One way a search ends: `SeedFinder.Job.end(why)`.** It clears `running` and `bootstrapping`, sets the
   end time, and keeps the first error. It is used for: the refusals at start, Stop, enough results, the
   backup settled for, the gate closing, a crash, a failed bootstrap, and the watchdog. Each worker thread
   now runs inside a try/finally: anything unexpected ends the search with an error, and no exit path can
   leave `bootstrapping` set. The screen asks `job.searching()`. A search that cannot start goes back to the
   pick view with its error in red.
2. **Catalog builds are numbered.** `begin()` takes the next number and empties the cache. `finish()`
   publishes both lists together (or the error) only if its number is still current. `forget()` moves the
   number on, so a build still running when a world loads is dropped.
3. **Offline wishes and the End.** Before names are read, LocalWish takes out every phrase WishParser's End
   or stronghold note answers, using the same lists WishParser decides from. It does this only when that
   note will fire, so "understood" and "answered" can't disagree.
   - Their words never become rows.
   - A wish made only of them is read in full, so WishParser answers it with exactly the AI path's sentence.
   - A count or size word before such a phrase belongs to it: "two end cities and a village" is one village.
   - "city", "eye" and "ender" on their own are now **not understood**. With a key that sends the wish to
     the AI; without one, the search runs and names the ignored word.
   - `stronghold_mentioned` is set as the AI is told to set it.
4. **Failures counted per search.** `VillageLayout.CheckCount` lives on the job, and a worker thread counts
   for its job through a ThreadLocal. The watchdog judges that count. The session-wide total stays for the
   log line only.
5. **Bots cap.** `AUTO_MAX_WORKERS = 12` (was `MAX_WORKERS`) and `MANUAL_MAX_WORKERS = 16`. Auto is
   unchanged: cores − 1, at most 12. A number now goes up to the smallest of: logical processors, what fits
   in half the heap, and 16. The tooltip says "16 is the most tested". `SLOTS` is 16.
6. **Settings saved safely.**
   - `FableVisionConfig.save()` is `synchronized` and writes a temporary file, then moves it over the real
     one: atomic, with a plain replace as fallback. This is the same scheme as `Config.save`.
   - Config and KeySafe now log a failed save. KeySafe logs only the exception's class, never a message or
     stack.
   - SearchWatchdog restores the interrupt flag.
7. **Key screen.** `KeySafe.protectionWords()` says "NOT encrypted — plain text in a file only your account
   can read" on macOS and Linux, and "NOT encrypted (Windows encryption isn't available in this install)" on
   Windows without the library. Both key screens now show that line, and the key box has it as a tooltip.
8. **"Ask me" (Custom spawn opens by itself) is OFF for new installs only.**
   - No settings file means a new install: off, and written as off.
   - A file that stores it is read as stored.
   - An existing file that doesn't store it (older than the setting), or can't be read, keeps the old
     default, on.
9. `SeedSearchApi.COMPATIBLE_RANGE` = `">=1.47.0 <1.48.0"`. Nothing else in that file changed.
10. Docs: `releases\26.3-fabric\README.txt` (1.47.3 newest), `releases\README.txt`, the top-level
    `README.txt`, and FIRST-TIME-USER.md (notes the "Ask me" change).

## Stop, measured (item 7)

`gradlew stopDiag --args=10` (new) runs a real search on auto's 12 workers. It watches their stacks until
one is *inside* a structure assembly, sets the Stop flag, and times the exits. 10 trials per case:

| case | the worker caught assembling | every worker out |
|---|---|---|
| Mansion with Lava Vault, Exact 64 | worst 174–185 ms | worst 174–185 ms |
| Mansion with Lava Vault, Fast 800 | worst 3–6 ms | worst 6–9 ms |
| Trial Chamber with Eruption Room, Exact 64 | worst 97–115 ms | worst 97–144 ms |
| Trial Chamber with Eruption Room, Fast 800 | worst 310–372 ms | worst 467–529 ms |

These are three runs: before any change (1.47.2's code), the covering run, and the full regression. **Worst:
0.53 s, under the ~1 s line, so no cancel check was added** inside the assembly, where vanilla's own code
runs. threadsDiag's own Stop check (villages, Exact) read 663 and 803 ms at 12 workers, and 477 and 452 ms
at 16. That is under the line too, but closest to it: a village is the slowest single assembly.

## Checks, and the control for each new one

- **watchdogDiag:**
  - A search whose registries cannot be read (`RegistryAccess.EMPTY`, 3 workers) ends with "World-gen
    bootstrap failed", both flags down and an end time.
  - **Control:** 1.47.2's worker, loaded from the shipped jar, on the same failure: `running=false
    bootstrapping=true`, i.e. stuck.
  - Per search: 60 failing checks from a stopped search's worker don't stop the next search.
  - **Control:** the same 60, counted for the next search, do stop it.
- **catalogDiag** (new) drives `begin`/`finish`/`forget` in the orders that went wrong. **Control:** a cache
  without build numbers (1.47.2's publish rule, modelled; its real race can't be timed deterministically)
  fails both checks.
- **wishDiag:** 11 End/stronghold cases offline, plus two controls. A wish with no End words gets no End
  note. 1.47.2's reader, from the jar, does not read "find me an end city" at all.
- **threadsDiag:** 32 asked on 32 threads gives 16; 64 on 64 gives 16; the heap limit is still honoured
  (16 asked in 512 MB gives 8). **Control:** 1.47.2's rule, from the jar, starts 32.
- **configDiag** (new):
  - "Ask me" in all five cases. **Control:** 1.47.2's default is on.
  - Four threads saving while one reads: about 22,000 reads, 0 not whole. **Control:** the old in-place
    write gives about 700 of 1,240 reads not whole. About 4,500 times the reader could not open the file at
    the instant it was being replaced; that is Windows refusing the open, never a half file. FableVision
    itself reads the file only inside the same lock.
  - The key words on Linux, macOS and Windows with and without the library. **Control:** 1.47.2 on Linux
    never says "NOT encrypted".
- **stopDiag** (new): the measurement above.

The 1.47.2-jar controls use a test-only class loader (`OldJar`) that runs the shipped jar's own classes on
the same game classes.

## Verification

Covering run (`covering-1473.log`), 13 tasks, all exit 0 with these verdicts:

| diagnostic | verdict |
|---|---|
| catalogDiag, configDiag, watchdogDiag, wishDiag, privacyDiag, joinDiag | all pass |
| joinRepro | ALL SHORT |
| etaDiag | ALL WITHIN 6.0x |
| layoutDiag | 0 problems at every size; controls caught |
| worldsDiag | pass |
| multiDiag (`3 both worlds threads=1,auto,max`) | 54 seeds, 324 row claims, **0 false matches**, 0 unverifiable |
| threadsDiag | all pass |
| stopDiag | worst 509 ms |

**Full regression** (`regression-1473.log` and its `.summary.txt`): 41 tasks, run alone in the background,
on the final code. 40 exit 0. The 41st, `threadsDiag gc 16` in a 96 MB heap, exits 1 as it must: it is the
control ("DOES NOT FIT", GC 100%). The verdicts read from the log:

| area | verdict |
|---|---|
| map vs game (mapTruthDiag, both runs, every dimension) | 0 phantoms, 0 missed, 0 unverifiable, 0 wrongly named |
| featureDiag | **0 false matches** (worst gap 114 blocks, Ancient City sauna) |
| multiDiag | 54 seeds, 0 false matches, 18 of 18 N-world searches gave 3 different seeds |
| rateDiag | every quoted rate within sampling error |
| statsDiag | 126 pieces = 32.4%, the top third |
| exactDiag | every count-check variant gave the same verdicts, seeds, lines and finds |
| coldDiag | warm-up ON: nothing given up; the warm-up-OFF control raced (121 seeds given up) |
| layoutDiag | 0 problems in 32,020 Create World placements and 1,440 searching layouts; controls caught |
| radius, nether, repro, strict, profession, smith, portal, sampler, tie | pass |
| map, cave, icon, stream, slime | pass |
| mapReplay ×3, focusDiag | pass |
| wishDiag (incl. the new End section), watchdogDiag, joinDiag, privacyDiag | all pass |
| joinRepro | ALL SHORT |
| etaDiag | ALL WITHIN 6.0x |
| catalogDiag, configDiag | all pass, controls failing as they must |
| stopDiag | worst 467 ms |
| threadsDiag | all pass; 16 workers FIT in 512 MB (GC 3.9%) |

villageDiag's "Nether Fossil … confirm is NOT enough" line is the same as in 1.47.0. It is why that row
carries an existence token, which passes 39/39 on the same run.

## What to test in game (nothing here could run Minecraft)

1. **"Ask me" on a new install:**
   - Rename `config/fablevision.json` (keep it), launch, and click Create New World. Custom spawn must
     **not** open by itself, the ✨ Custom spawn button must be there, and inside, the button reads "Ask me:
     OFF".
   - Switch it on, restart, and check it stayed on.
   - Put your own file back: whatever you had before must be what you get.
2. **End and stronghold wishes, typed in the wish box:**
   - "find me an end city" → a red line, "That can't be found by a seed search: The End isn't searchable…".
     No search, and no AI request even with a key.
   - "a stronghold near an end portal" → the stronghold sentence, and no Ruined Portal search.
   - "a village and an end city" → searches a village; the note mentions the End.
   - "a village near a city" → without a key it searches a village and says it didn't understand "city";
     with a key it asks the AI.
3. **Bots:** click through the setting. It must stop at your CPU threads or 16, whichever is fewer, and the
   tooltip mentions 16. Auto still shows your threads − 1, at most 12.
4. **Stop:** search "Trial Chamber with Eruption Room" with ⚡Fast at 800, press Stop mid-search. Back
   within about half a second.
5. **Key screens:** ⚙ key from Custom spawn now shows "Stored on this PC only, encrypted to your Windows
   account." above the seed-wishes line, and hovering the key box says the same. If you have a Mac or Linux
   machine, it must say "NOT encrypted".
6. **Settings files:** change Mode, Bots and Coords, then quit. `config/fablevision.json` must be whole and
   hold the new values, with no `fablevision.json.tmp` left behind.
7. **Normal use still works:** change data packs on the Create World screen, then open Custom spawn. The list
   loads. Run a plain village search: it finds one and puts the seed in the box.
8. **Not testable in game:** the "stuck on Searching" fix needs world-gen data that fails to load. It is
   checked headless only (watchdogDiag, with the 1.47.2 jar as the control).
