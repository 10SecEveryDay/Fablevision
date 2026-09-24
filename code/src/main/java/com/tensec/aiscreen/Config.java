package com.tensec.aiscreen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads/writes config/aiscreen.json — models, the active provider, cooldown and memory.
 *
 * THE KEYS ARE NOT IN THIS FILE. Since 1.43.0 they live in {@link KeySafe} (config/aiscreen-keys.dat,
 * encrypted to the Windows account where available). The key fields below still exist only so an
 * older file can be READ once: load() moves any key it finds into KeySafe and blanks the field.
 *
 * There are TWO independent keysets: MAIN (the G-menu "ask AI about my screen" panel)
 * and SPAWN (the Custom-spawn seed wish). Each keeps its own provider and keys, so seed wishes never
 * spend the key used for screen questions.
 */
public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Supported AI providers. Each has its own key, model, and endpoint format. */
    public enum Provider {
        // The free one since Groq went (1.44.3), and labelled so on the ⚙ Key screen.
        GEMINI("Gemini (free)", "aistudio.google.com"),
        OPENAI("GPT (OpenAI)", "platform.openai.com"),
        ANTHROPIC("Claude (Anthropic)", "console.anthropic.com");

        public final String label;
        public final String keySite;

        Provider(String label, String keySite) {
            this.label = label;
            this.keySite = keySite;
        }
    }

    /** Which stored keyset a request uses: the G-menu's or the Custom-spawn wish's. */
    public enum Keyset { MAIN, SPAWN }

    /**
     * GROQ WAS REMOVED IN 1.44.3. It retired this mod's default model twice in two months (July and
     * September 2026) and then put the free tier on daily limits, so it cost more upkeep than it saved.
     * Gemini is the free option; wishes made of plain names need no AI at all (LocalWish).
     *
     * A config that still names it is moved to Gemini on load, and any Groq key in the key store is
     * deleted then too (see {@link #dropGroq}) — nothing is left behind that the mod can no longer use.
     */
    private static final String REMOVED_PROVIDER = "groq";

    public static class Data {
        // Legacy field from 1.x configs — migrated into geminiKey on load, kept so old
        // files parse cleanly.
        public String apiKey = "";

        public String provider = "gemini"; // gemini | openai | anthropic
        public String geminiKey = "";
        public String openaiKey = "";
        public String anthropicKey = "";
        public String geminiModel = "gemini-2.5-flash";
        public String openaiModel = "gpt-4o-mini";
        public String anthropicModel = "claude-opus-4-8";
        public int cooldownSeconds = 10;
        // Legacy. "always" let a capture happen with no prompt; since 1.43.0 every capture asks,
        // so load() rewrites it to "ask". Kept so the field in an old file still parses.
        public String captureMode = "ask";
        // Remember the conversation (follow-up questions resend the chat = more tokens/requests).
        public boolean rememberConversation = false;

        // The Custom-spawn wish keeps its OWN provider + keys (blank spawnProvider marks a
        // pre-1.30 config — load() migrates by copying the main setup across once).
        public String spawnProvider = "";
        public String spawnGeminiKey = "";
        public String spawnOpenaiKey = "";
        public String spawnAnthropicKey = "";

    }

    private static Data data = new Data();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("aiscreen.json");
    }

    public static synchronized void load() {
        try {
            Path p = file();
            if (Files.exists(p)) {
                String raw = Files.readString(p);
                Data d = GSON.fromJson(raw, Data.class);
                if (d != null) data = d;
                // A 1.x single-key config's apiKey was always a Gemini key — migrateKeys() below
                // moves it to the Gemini slot.
                // 1.30.0: the Custom-spawn wish got its own keyset. Older configs copy the
                // main setup across ONCE so existing installs keep working; after that the
                // two keysets are fully independent (change either without touching the other).
                if (data.spawnProvider == null || data.spawnProvider.isBlank()) {
                    data.spawnProvider = data.provider == null || data.provider.isBlank() ? "gemini" : data.provider;
                    // Pre-1.30 files are also pre-1.43, so the keys are still in the plain fields here
                    // and are copied across before migrateKeys() moves both sets out.
                    data.spawnGeminiKey = data.geminiKey != null && !data.geminiKey.isBlank() ? data.geminiKey : data.apiKey;
                    data.spawnOpenaiKey = data.openaiKey;
                    data.spawnAnthropicKey = data.anthropicKey;
                    save();
                }
                // 1.43.0: keys leave this file. Each non-empty legacy field is moved into KeySafe
                // (unless KeySafe already holds that slot) and blanked here, so the plain-text copy
                // is gone from disk after the first launch.
                if (migrateKeys()) {
                    save();
                }
                // 1.43.0: screen capture is opt-in on every request. An old "always" is not honoured.
                if (!"ask".equals(data.captureMode)) {
                    data.captureMode = "ask";
                    save();
                }
                // 1.44.3: Groq is gone. The old groqModel/groqKey fields are simply not read any more
                // and drop out of the file on this save; a provider still set to Groq becomes Gemini.
                boolean hadGroq = dropGroq(data) | raw.toLowerCase().contains(REMOVED_PROVIDER);
                dropGroqKeys();
                if (hadGroq) {
                    save();
                }
            } else {
                save(); // create a template file so it's easy to find and edit
            }
        } catch (Exception e) {
            data = new Data();
        }
    }

    /**
     * Writes the settings file — one writer at a time, and never half a file.
     *
     * BOTH HALVES MATTER SINCE 1.43.1, because saving is no longer something only the player's own
     * clicks do: when a provider retires a model, {@link ModelFallback} saves the replacement from the
     * network thread, which can land at the same moment as a save from the game thread. Two threads
     * truncating and rewriting the same file interleave, and the result is JSON that will not parse —
     * and {@link #load} answers unparseable by starting from defaults, so a collision would quietly
     * reset the player's provider, cooldown and model to factory settings.
     *
     * Written to a temporary file and moved into place, so a crash or a full disk mid-write leaves the
     * previous file intact rather than a truncated one.
     */
    public static synchronized void save() {
        try {
            Path target = file();
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(data));
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception noAtomicMove) {
                // Some filesystems refuse ATOMIC_MOVE; a plain replace is still better than writing
                // over the live file in place.
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Moves a keyset still set to Groq over to Gemini. True if anything changed. Package-private and
     * handed its data so privacyDiag can check it without a settings file.
     */
    static boolean dropGroq(Data d) {
        boolean changed = false;
        if (d.provider != null && d.provider.trim().toLowerCase().startsWith(REMOVED_PROVIDER)) {
            d.provider = "gemini";
            changed = true;
        }
        if (d.spawnProvider != null && d.spawnProvider.trim().toLowerCase().startsWith(REMOVED_PROVIDER)) {
            d.spawnProvider = "gemini";
            changed = true;
        }
        return changed;
    }

    /** Deletes any stored Groq key: a key for a provider the mod no longer talks to is a secret kept
     *  on disk for nothing. */
    private static void dropGroqKeys() {
        for (Keyset ks : Keyset.values()) {
            String slot = (ks == Keyset.SPAWN ? "spawn." : "main.") + REMOVED_PROVIDER;
            if (!KeySafe.get(slot).isEmpty()) {
                KeySafe.put(slot, "");
            }
        }
    }

    // ── Provider (per keyset) ───────────────────────────────────────────────
    public static Provider getProvider() { return getProvider(Keyset.MAIN); }

    public static Provider getProvider(Keyset ks) {
        String raw = ks == Keyset.SPAWN ? data.spawnProvider : data.provider;
        String p = raw == null ? "gemini" : raw.toLowerCase();
        if (p.startsWith("openai") || p.startsWith("gpt")) return Provider.OPENAI;
        if (p.startsWith("anthropic") || p.startsWith("claude")) return Provider.ANTHROPIC;
        return Provider.GEMINI;
    }

    public static void setProvider(Provider p) { setProvider(Keyset.MAIN, p); }

    public static void setProvider(Keyset ks, Provider p) {
        String v = switch (p) {
            case OPENAI -> "openai";
            case ANTHROPIC -> "anthropic";
            default -> "gemini";
        };
        if (ks == Keyset.SPAWN) {
            data.spawnProvider = v;
        } else {
            data.provider = v;
        }
        save();
    }

    // ── Keys (per keyset + provider; the active provider's key is used) ─────
    public static String getKey() { return getKey(Keyset.MAIN); }

    public static String getKey(Keyset ks) { return getKey(ks, getProvider(ks)); }

    public static String getKey(Provider p) { return getKey(Keyset.MAIN, p); }

    public static String getKey(Keyset ks, Provider p) {
        return KeySafe.get(slot(ks, p));
    }

    /** The KeySafe entry name for one keyset's key for one provider, e.g. "spawn.anthropic". */
    static String slot(Keyset ks, Provider p) {
        return (ks == Keyset.SPAWN ? "spawn." : "main.") + providerId(p);
    }

    private static String providerId(Provider p) {
        return switch (p) {
            case OPENAI -> "openai";
            case ANTHROPIC -> "anthropic";
            default -> "gemini";
        };
    }

    public static void setKey(String k) { setKey(Keyset.MAIN, k); }

    public static void setKey(Keyset ks, String k) {
        KeySafe.put(slot(ks, getProvider(ks)), k);
    }

    /** One tap of "use my G-menu key here too": copies the MAIN provider and its key into
     *  the spawn keyset. The two stay fully independent afterwards. */
    public static void copyMainKeyToSpawn() {
        Provider p = getProvider(Keyset.MAIN);
        data.spawnProvider = providerId(p);
        KeySafe.put(slot(Keyset.SPAWN, p), getKey(Keyset.MAIN, p));
        save();
    }

    /** Moves plain-text keys out of the legacy fields. True if anything in this file changed. */
    private static boolean migrateKeys() {
        boolean changed = false;
        boolean allStored = true;
        String[][] legacy = {
            {data.geminiKey, slot(Keyset.MAIN, Provider.GEMINI)},
            {data.openaiKey, slot(Keyset.MAIN, Provider.OPENAI)},
            {data.anthropicKey, slot(Keyset.MAIN, Provider.ANTHROPIC)},
            {data.spawnGeminiKey, slot(Keyset.SPAWN, Provider.GEMINI)},
            {data.spawnOpenaiKey, slot(Keyset.SPAWN, Provider.OPENAI)},
            {data.spawnAnthropicKey, slot(Keyset.SPAWN, Provider.ANTHROPIC)},
            {data.apiKey, slot(Keyset.MAIN, Provider.GEMINI)},
        };
        for (String[] pair : legacy) {
            String v = pair[0] == null ? "" : pair[0].trim();
            if (v.isEmpty()) {
                continue;
            }
            if (KeySafe.get(pair[1]).isEmpty()) {
                allStored &= KeySafe.put(pair[1], v);
            }
            changed = true;
        }
        // Only blank the plain copies once the safe copy is really on disk. A key store that could
        // not be written must not cost the player the only copy of a paid key.
        if (changed && allStored) {
            data.apiKey = "";
            data.geminiKey = "";
            data.openaiKey = "";
            data.anthropicKey = "";
            data.spawnGeminiKey = "";
            data.spawnOpenaiKey = "";
            data.spawnAnthropicKey = "";
        }
        return changed && allStored;
    }

    // ── Models (shared per provider — both keysets use the same model names) ─
    public static String getModel() { return getModel(Keyset.MAIN); }

    public static String getModel(Keyset ks) {
        return switch (getProvider(ks)) {
            case OPENAI -> orDefault(data.openaiModel, "gpt-4o-mini");
            case ANTHROPIC -> orDefault(data.anthropicModel, "claude-opus-4-8");
            default -> orDefault(data.geminiModel, "gemini-2.5-flash");
        };
    }

    /** Replaces a provider's model name (used when the provider has retired the old one). */
    public static void setModel(Provider p, String model) {
        switch (p) {
            case OPENAI -> data.openaiModel = model;
            case ANTHROPIC -> data.anthropicModel = model;
            default -> data.geminiModel = model;
        }
        save();
    }

    private static String orDefault(String v, String dflt) {
        return (v == null || v.isBlank()) ? dflt : v.trim();
    }

    public static int getCooldownSeconds() { return data.cooldownSeconds <= 0 ? 10 : data.cooldownSeconds; }
    public static Path path() { return file(); }

    // ── Conversation memory ─────────────────────────────────────────────────
    public static boolean isRememberConversation() { return data.rememberConversation; }

    public static void setRememberConversation(boolean v) {
        data.rememberConversation = v;
        save();
    }

    // THE DAILY USAGE COUNTERS WERE DELETED IN 1.43.1. They counted every request per provider per
    // keyset, reset at midnight, and were written to this file on every single request — and nothing
    // read them. The panel deliberately shows no counter (see ModGuide: the provider itself says when
    // a limit is hit, and that message is the only figure that is ever right), so what was left was a
    // file write per question and a number nobody could see. The fields are gone from Data too; an
    // older file still carrying them parses fine, the values are simply ignored.
}
