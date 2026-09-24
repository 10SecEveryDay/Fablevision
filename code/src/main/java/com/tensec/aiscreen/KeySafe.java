package com.tensec.aiscreen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Where the AI keys live, and the one place that knows what a key looks like.
 *
 * WHY NOT IN aiscreen.json ANY MORE. That file is a normal config file, and config folders get
 * copied: into modpack exports, into "here's my config" support posts, into a zip for a friend. Every
 * one of those carried the player's paid API keys in plain text. The options a client-side Fabric mod
 * realistically has, and why this is the one picked:
 *
 *   - OS-PROTECTED STORAGE (chosen, on Windows). Windows' Data Protection API encrypts with a key tied
 *     to the Windows user account; the blob this writes is useless on any other PC or account. It is
 *     called through JNA, which Minecraft itself ships, so this adds no library and no download.
 *     What it does NOT stop: other software running as the same Windows user, which can ask Windows
 *     to decrypt it just as this mod does. Nothing a mod can do stops that.
 *   - macOS Keychain / Linux Secret Service: the right answer on those systems, but they need native
 *     calls or command-line tools that cannot be tested from the machine this was built on, and a
 *     key store that silently fails is worse than a plain one. On those systems the key is kept in
 *     this separate file, readable by your user account only.
 *   - "Encrypt it with a key stored next to it": obfuscation. It defeats nothing and would let this
 *     comment claim a protection that does not exist. Not done.
 *   - A passphrase typed each session: real protection, and nobody would use the feature.
 *   - Environment variables: unusable from most launchers.
 *
 * The file is config/aiscreen-keys.dat. Each entry is tagged with how it was stored, so a file moved
 * between systems is recognised as unreadable (and asks for the key again) instead of being misread.
 *
 * NEVER IN A LOG, A CRASH REPORT OR A REQUEST. Nothing here logs. {@link #redact} is applied to every
 * string AiVision hands back and to every question it sends, so a key pasted into the question box,
 * or echoed back by a provider's error message, is replaced before it reaches the screen, the chat
 * (which Minecraft writes to latest.log) or the model.
 */
public final class KeySafe {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DPAPI = "dpapi:";
    private static final String PLAIN = "plain:";
    /** Extra input to the Windows encryption, so a generic "decrypt this blob" tool is not enough. */
    private static final byte[] ENTROPY = "fablevision-aiscreen-keys".getBytes(StandardCharsets.UTF_8);

    private static final Map<String, String> KEYS = new HashMap<>();
    /** Entries this PC cannot decrypt, kept byte-for-byte so saving here does not destroy them for
     *  the account or machine that CAN read them. Replaced only when that slot gets a new key. */
    private static final Map<String, String> FOREIGN = new HashMap<>();
    private static boolean loaded = false;
    /** Entries that exist on disk but could not be decrypted here — reported, never guessed at. */
    private static int unreadable = 0;
    /**
     * Set when the Windows encryption could not be reached AT ALL — the JNA classes Minecraft ships
     * are missing, or the call failed to link.
     *
     * It exists because the message was otherwise a lie. An entry that cannot be decrypted is normally
     * a key copied from another PC or another Windows account, and the screen says exactly that. If
     * the LIBRARY is missing, the same path is taken for a key that was written on this very PC, and
     * the player is told to go and find a key they never moved. The two causes need different
     * sentences, so they are now told apart.
     */
    private static volatile boolean dpapiUnavailable = false;

    /** True when Windows encryption should work here but could not be reached. */
    public static boolean dpapiMissing() {
        return dpapiUnavailable;
    }

    private KeySafe() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("aiscreen-keys.dat");
    }

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** How keys are protected on this system, in words a player can check. */
    public static String protectionWords() {
        if (windows()) {
            return dpapiUnavailable
                    ? "kept in your profile folder (Windows encryption unavailable in this install)"
                    : "encrypted to your Windows account";
        }
        return "kept in a file only your account can read";
    }

    public static synchronized String get(String slot) {
        load();
        return KEYS.getOrDefault(slot, "");
    }

    /** Stores (or with a blank key, removes) one key. False if it could not be written to disk. */
    public static synchronized boolean put(String slot, String key) {
        load();
        String v = key == null ? "" : key.trim();
        FOREIGN.remove(slot);
        if (v.isEmpty()) {
            KEYS.remove(slot);
        } else {
            KEYS.put(slot, v);
        }
        return save();
    }

    /** How many stored keys could not be read on this PC (copied from another machine or account). */
    public static synchronized int unreadableCount() {
        load();
        return unreadable;
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path p = file();
            if (!Files.exists(p)) {
                return;
            }
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            for (var e : root.entrySet()) {
                String stored = e.getValue().getAsString();
                String key = open(stored);
                if (key == null) {
                    unreadable++;
                    FOREIGN.put(e.getKey(), stored);
                } else if (!key.isEmpty()) {
                    KEYS.put(e.getKey(), key);
                }
            }
        } catch (Throwable t) {
            // Unreadable file: behave as "no keys" and let the player paste them again. Never log the
            // contents or the exception message, which could quote part of the file.
        }
    }

    private static boolean save() {
        try {
            JsonObject root = new JsonObject();
            for (var e : FOREIGN.entrySet()) {
                root.addProperty(e.getKey(), e.getValue());
            }
            for (var e : KEYS.entrySet()) {
                root.addProperty(e.getKey(), seal(e.getValue()));
            }
            // WRITTEN TO A TEMPORARY FILE AND MOVED INTO PLACE (1.44.2), the same as Config.save. A crash
            // or a full disk mid-write used to leave a truncated file, which load() reads as "no keys"
            // — the player's paid key gone. The temporary file is made owner-only BEFORE the key goes in.
            Path p = file();
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, "");
            ownerOnly(tmp);
            Files.writeString(tmp, GSON.toJson(root));
            try {
                Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception noAtomicMove) {
                Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            ownerOnly(p);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void ownerOnly(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (Throwable notPosix) {
            // Windows: the file sits in the user's own profile, and the contents are DPAPI blobs.
        }
    }

    static String seal(String key) {
        byte[] raw = key.getBytes(StandardCharsets.UTF_8);
        if (windows()) {
            try {
                byte[] blob = com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(raw, ENTROPY,
                        com.sun.jna.platform.win32.WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "FableVision AI key", null);
                return DPAPI + Base64.getEncoder().encodeToString(blob);
            } catch (LinkageError | RuntimeException unavailable) {
                // Fall through to the plain form rather than losing the key the player just pasted —
                // but remember WHY, so the screen can say "not encrypted here" instead of implying
                // the key was fine. A LinkageError is the missing-library case.
                if (unavailable instanceof LinkageError) {
                    dpapiUnavailable = true;
                }
            }
        }
        return PLAIN + Base64.getEncoder().encodeToString(raw);
    }

    /** The key, "" for an empty entry, or null when this PC cannot read it. */
    static String open(String stored) {
        try {
            if (stored.startsWith(DPAPI)) {
                if (!windows()) {
                    return null;
                }
                byte[] raw = com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(
                        Base64.getDecoder().decode(stored.substring(DPAPI.length())), ENTROPY,
                        com.sun.jna.platform.win32.WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null);
                return new String(raw, StandardCharsets.UTF_8);
            }
            if (stored.startsWith(PLAIN)) {
                return new String(Base64.getDecoder().decode(stored.substring(PLAIN.length())), StandardCharsets.UTF_8);
            }
            return null;
        } catch (LinkageError missingLibrary) {
            // The encryption itself is not reachable — not the same thing as a key from another PC.
            dpapiUnavailable = true;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Redaction ────────────────────────────────────────────────────────────

    /**
     * Shapes the providers' keys come in, for text that may contain a key this PC never stored —
     * a key the player pasted into the question box by mistake, or one a provider quotes back.
     * Deliberately specific: a pattern broad enough to catch "any long token" would also eat seeds,
     * coordinates and model names out of real answers.
     */
    private static final Pattern KEY_SHAPES = Pattern.compile(
            "AIza[0-9A-Za-z_\\-]{30,}"            // Google / Gemini
            + "|sk-ant-[0-9A-Za-z_\\-]{20,}"      // Anthropic
            + "|sk-(?:proj-|svcacct-)?[0-9A-Za-z_\\-]{20,}"  // OpenAI
            // Groq. The provider was removed in 1.44.3, but an old Groq key pasted into a question
            // by mistake is still a secret, so its shape is still hidden.
            + "|gsk_[0-9A-Za-z]{20,}");

    /** {@code text} with every stored key, and anything shaped like a provider key, replaced. */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        synchronized (KeySafe.class) {
            load();
            for (String key : KEYS.values()) {
                if (key.length() >= 8) {
                    out = out.replace(key, "[key hidden]");
                }
            }
        }
        return KEY_SHAPES.matcher(out).replaceAll("[key hidden]");
    }

    /** For tests: redaction against an explicit key list, with no file involved. */
    static String redactWith(String text, Iterable<String> keys) {
        String out = text;
        for (String key : keys) {
            if (key != null && key.length() >= 8) {
                out = out.replace(key, "[key hidden]");
            }
        }
        return KEY_SHAPES.matcher(out).replaceAll("[key hidden]");
    }
}
