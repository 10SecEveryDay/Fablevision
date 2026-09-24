package com.tensec.aiscreen;

import java.util.List;

/**
 * The AI panel's privacy and fairness rules, checked against the real classes with no game and no
 * network: keys never survive into text, a stored key cannot be read back as plain text from the
 * file, the two keysets do not share a cooldown, and there is no "always allow" left for captures.
 *
 * {@code gradlew privacyDiag}
 */
public final class PrivacyDiagnostic {
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("========== AI KEYS, CAPTURES AND COOLDOWNS ==========");

        // ── redaction ─────────────────────────────────────────────────────────────
        String stored = "my-own-custom-key-1234567890";
        List<String> keys = List.of(stored);
        check("a stored key typed into a question is hidden",
                !KeySafe.redactWith("what does " + stored + " do?", keys).contains(stored));
        String[] shapes = {
            "AIzaSyD" + "x".repeat(32),
            "sk-ant-api03-" + "Ab1_".repeat(8),
            "sk-proj-" + "Zz9-".repeat(8),
            "sk-" + "q".repeat(40),
            "gsk_" + "k".repeat(40)};
        for (String k : shapes) {
            String out = KeySafe.redactWith("Incorrect API key provided: " + k + ".", List.of());
            check("provider-shaped key " + k.substring(0, 7) + "… is hidden even when never stored",
                    !out.contains(k) && out.contains("[key hidden]"));
        }
        String ordinary = "Seed -4172144997902289642 at 120, -64 using claude-opus-4-8 or gpt-4o-mini; "
                + "the sk-8 skeleton farm is 32 blocks away";
        check("seeds, coordinates and model names are left alone",
                KeySafe.redactWith(ordinary, keys).equals(ordinary));

        // ── storage ───────────────────────────────────────────────────────────────
        String sealed = KeySafe.seal(stored);
        check("the stored form does not contain the key", !sealed.contains(stored));
        check("and does not contain it base64-encoded either (on Windows)",
                !KeySafe.windows() || !sealed.contains(java.util.Base64.getEncoder().encodeToString(stored.getBytes())));
        check("it reads back as the same key on this PC", stored.equals(KeySafe.open(sealed)));
        check("Windows uses the Data Protection API, not the plain form",
                !KeySafe.windows() || sealed.startsWith("dpapi:"));
        check("an entry this PC cannot decrypt reads as unreadable, not as garbage",
                KeySafe.open("dpapi:AAAA") == null);

        // ── cooldowns ─────────────────────────────────────────────────────────────
        check("first G-panel request goes out", AiVision.claimCooldown(Config.Keyset.MAIN) == 0);
        check("a Custom-spawn request right after it is NOT on cooldown",
                AiVision.claimCooldown(Config.Keyset.SPAWN) == 0);
        check("a second G-panel request right after IS on cooldown",
                AiVision.claimCooldown(Config.Keyset.MAIN) > 0);

        // ── captures ──────────────────────────────────────────────────────────────
        boolean alwaysGone = true;
        for (var m : Config.class.getDeclaredMethods()) {
            if (m.getName().toLowerCase().contains("capturealways")) {
                alwaysGone = false;
            }
        }
        check("there is no \"always allow capture\" setting left to switch on", alwaysGone);

        // ── retired models (1.43.1) ───────────────────────────────────────────────
        // The exact replies OpenAI and Gemini give for a switched-off model, and ones that are NOT that.
        check("OpenAI's 'model does not exist' reply is recognised as a retired model",
                ModelFallback.isRetired(404, "{\"error\":{\"message\":\"The model `gpt-4-vision-preview` does not exist or"
                        + " you do not have access to it.\",\"type\":\"invalid_request_error\",\"code\":\"model_not_found\"}}"));
        check("a 'decommissioned' reply is recognised too",
                ModelFallback.isRetired(400, "{\"error\":{\"code\":\"model_decommissioned\"}}"));
        check("Gemini's 'models/x is not found' reply is recognised",
                ModelFallback.isRetired(404, "{\"error\":{\"code\":404,\"message\":\"models/gemini-9 is not found for API"
                        + " version v1beta\"}}"));
        check("a bad key is NOT mistaken for a retired model",
                !ModelFallback.isRetired(401, "{\"error\":{\"message\":\"Invalid API Key\"}}"));
        check("a rate limit is NOT mistaken for a retired model",
                !ModelFallback.isRetired(429, "{\"error\":{\"message\":\"Rate limit reached for model x\"}}"));
        check("speech and safety models are never picked as a chat replacement",
                !ModelFallback.canChat(Config.Provider.OPENAI, "whisper-1")
                        && !ModelFallback.canChat(Config.Provider.OPENAI, "omni-moderation-latest")
                        && ModelFallback.canChat(Config.Provider.OPENAI, "gpt-4o-mini"));

        // ── Groq removed (1.44.3) ─────────────────────────────────────────────────
        // Not just "the enum value is gone": a player who had Groq picked must land somewhere that
        // works, and nothing Groq-shaped may be left in the settings the mod writes.
        boolean noGroqProvider = true;
        for (Config.Provider p : Config.Provider.values()) {
            noGroqProvider &= !p.name().toLowerCase().contains("groq") && !p.label.toLowerCase().contains("groq");
        }
        check("there is no Groq provider to pick", noGroqProvider);
        boolean noGroqField = true;
        for (var f : Config.Data.class.getDeclaredFields()) {
            noGroqField &= !f.getName().toLowerCase().contains("groq");
        }
        check("the settings file has no Groq key or model field left", noGroqField);
        Config.Data wasGroq = new Config.Data();
        wasGroq.provider = "groq";
        wasGroq.spawnProvider = "Groq";
        boolean moved = Config.dropGroq(wasGroq);
        check("a saved Groq choice is moved to Gemini, for both keysets",
                moved && "gemini".equals(wasGroq.provider) && "gemini".equals(wasGroq.spawnProvider));
        Config.Data notGroq = new Config.Data();
        notGroq.provider = "anthropic";
        notGroq.spawnProvider = "openai";
        check("and any other choice is left alone",
                !Config.dropGroq(notGroq) && "anthropic".equals(notGroq.provider) && "openai".equals(notGroq.spawnProvider));
        check("Gemini is labelled as the free option", Config.Provider.GEMINI.label.toLowerCase().contains("free"));

        // ── the settings file (1.43.1) ────────────────────────────────────────────
        // ModelFallback saves a replacement model from the network thread, which can collide with a
        // save from the game thread. Two writers truncating one file makes JSON that will not parse,
        // and load() answers that by starting from defaults — a silent reset of the player's setup.
        boolean saveSync = false;
        boolean loadSync = false;
        for (var m : Config.class.getDeclaredMethods()) {
            if (m.getName().equals("save") && m.getParameterCount() == 0) {
                saveSync = java.lang.reflect.Modifier.isSynchronized(m.getModifiers());
            }
            if (m.getName().equals("load") && m.getParameterCount() == 0) {
                loadSync = java.lang.reflect.Modifier.isSynchronized(m.getModifiers());
            }
        }
        check("only one thread can write the settings file at a time", saveSync);
        check("and reading it is under the same lock", loadSync);
        boolean usageGone = true;
        for (var m : Config.class.getDeclaredMethods()) {
            String n = m.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.contains("usage") || n.contains("usedtoday") || n.contains("dailycap") || n.contains("noterequest")) {
                usageGone = false;
            }
        }
        check("the daily usage counters nothing ever read are gone", usageGone);

        System.out.println(failed == 0 ? "ALL CHECKS PASS" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void check(String what, boolean ok) {
        System.out.println("  " + (ok ? "ok   " : "FAIL ") + what);
        if (!ok) {
            failed++;
        }
    }

    private PrivacyDiagnostic() {
    }
}
