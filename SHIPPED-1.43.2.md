# FableVision 1.43.2 — the review fixes

A second pair of eyes (DeepSeek, reading a decompiled 1.43.1 jar) raised nine points. Eight were
real and are fixed; one was a misreading. Each was checked against the source before anything moved.

## Real, and fixed

**1. The map got slower the more you panned.** Every frame, the map rebuilt its whole marker list —
up to three thousand markers, each with a freshly made icon — and then checked each one against every
marker already drawn to see if they overlapped. That is millions of comparisons a second for a picture
that had not changed. Now the list is built once and only when it changes, and overlap is settled
through a grid of buckets: a marker only looks at the nine buckets around it.

**2. An abandoned pan kept working.** Dragging asks for new ground several times a second, and the
older request already stopped checking structures when it went stale — but it still finished its
16,384-sample biome grid first. It now stops mid-grid. Measured: **18ms instead of 1,186ms**
(`mapDiag` now checks this).

**3. Slime chunks were recounted every frame.** About seventeen thousand chunks, each seeding a random
number generator, sixty times a second. Now worked out once per camera position.

**4. The settings file could be corrupted.** Since 1.43.1 a retired model is saved by the network
thread, which can collide with a save from the game thread; two writers truncating one file produce
JSON that will not parse, and the loader answers that by starting from defaults — a silent reset of
your provider, model and cooldown. Saving is now one-at-a-time, and written to a temporary file that
is moved into place, so a crash mid-write cannot leave a half file either.

**5. A misleading key message.** If the Windows key encryption could not be reached, a key saved on
*this* PC was reported as "came from another PC or account" — telling you to go and find something you
never moved. The two causes now say different things. (Declaring the library in `fabric.mod.json` is
not possible — that file lists mods, not libraries — and Minecraft 26.1.2 does ship JNA 5.17.0, so
this is a message fix for an unusual install rather than a missing dependency.)

**6. The time estimate could steal a core for half a minute.** A bundle is eight or ten items and each
had its own budget plus a floor of 40 trials, so working out how long a search would take could make
that search slower. It now has a ceiling of 8 seconds for the whole job and is dropped the moment the
search finishes. Checked: a 2-second ceiling finishes in 2,017ms, and "stop" stops it in 0ms.

**7. A seed box that could not answer.** On a non-Default world type the Seed map still let you type
and still had a clickable Show that did nothing. Both are now disabled, with the reason in a tooltip.

**8. Dead code.** The daily usage counters (per provider, per keyset, reset at midnight) were written
to disk on every single question and read by nothing — the panel deliberately shows no counter. All of
it is gone: the methods, the fields and the file write per question.

## Not real

**9. "The map's picture check always passes."** The claim was that comparing colour arrays by identity
is meaningless because each is freshly allocated. It is not: a view that arrives while structures are
still being checked carries *the same* array, on purpose, so the check is what stops the map repainting
its picture several times per build. A new build does allocate a new array and is painted. The comment
now says so.

Two of the nine ("won't compile") were artefacts of reading decompiled code; the build was clean.

## Verification

`mapDiag`, `etaDiag`, `privacyDiag`, `wishDiag`, `layoutDiag` and `mapTruthDiag` all pass
(regression-1432.log). mapTruthDiag still finds **0 phantoms** in every dimension.

New checks, so these cannot come back: a cancelled map build must return nothing and be fast;
the estimate must honour its ceiling and its stop; the settings file must be written under a lock;
the dead usage methods must stay gone.

## Needs your test in game

1. Pan the map around for a while, then keep panning — it should not get slower.
2. Turn slime chunks on and drag: still smooth.
3. Seed map with a non-Default world type: the seed box should be greyed out with a reason.
