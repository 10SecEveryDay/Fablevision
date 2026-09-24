package com.tensec.aiscreen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * WHAT TO DO WHEN A PROVIDER RETIRES THE MODEL WE ASK FOR.
 *
 * Providers switch models off on their own schedule (Groq did it to this mod's default twice in two
 * months, which is why Groq was removed in 1.44.3). When the fix is to type a new name into the code and
 * ship a release, every question fails with "model does not exist" until the player updates.
 * Hard-coding one name was the bug; which name was only ever the symptom.
 *
 * So a retired model is now handled at the moment it is found out:
 *
 *   1. The request is sent with the configured model, exactly as before. Nothing extra happens while
 *      the model works — no list is fetched on a healthy request.
 *   2. If the provider answers "that model doesn't exist / was decommissioned", its OWN list of
 *      models is fetched (the same host and key the request just used; no new endpoint), the ones
 *      that cannot chat are dropped, and the rest are ranked.
 *   3. Up to three are tried in order. A candidate that turns out not to accept screenshots is
 *      skipped rather than reported, because nothing in the list says which models can see.
 *   4. The first one that answers is used, saved to config/aiscreen.json so the next launch starts
 *      on it, and the player is told in one line which model was swapped for which.
 *
 * No preference list of model names lives here on purpose: a list of names is the same bug with more
 * entries. The ranking reads only what the provider publishes today.
 */
final class ModelFallback {

    private ModelFallback() {
    }

    /** Save a working replacement for a model the provider no longer has; both keysets use it. */
    static void adopt(Config.Provider p, String model) {
        Config.setModel(p, model);
    }

    /** Is this reply the provider saying the MODEL is gone (as opposed to any other error)? */
    static boolean isRetired(int status, String body) {
        if (status == 200 || body == null) {
            return false;
        }
        String low = body.toLowerCase(Locale.ROOT);
        if (low.contains("model_not_found") || low.contains("model_decommissioned")
                || low.contains("decommissioned") || low.contains("has been deprecated")
                || low.contains("no longer supported")) {
            return true;
        }
        // OpenAI: 404 "The model `x` does not exist". Gemini: 404 "models/x is not found".
        // Anthropic: 404 not_found_error "model: x".
        return status == 404 && low.contains("model");
    }

    /** Did this candidate refuse because it cannot take an image? Then the next one is tried. */
    static boolean cannotSeeImages(int status, String body) {
        if (status == 200 || body == null) {
            return false;
        }
        String low = body.toLowerCase(Locale.ROOT);
        return status == 400 && (low.contains("image") || low.contains("vision") || low.contains("multimodal")
                || low.contains("content must be a string") || low.contains("image_url"));
    }

    /**
     * The provider's own current models that could replace {@code dead}, best first. Empty when the
     * list cannot be read; the caller then says so rather than guessing.
     */
    static List<String> candidates(HttpClient http, Config.Provider p, String key, boolean needsImage, String dead) {
        try {
            List<Candidate> found = switch (p) {
                case GEMINI -> geminiModels(http, key);
                case ANTHROPIC -> anthropicModels(http, key);
                case OPENAI -> openAiModels(http, key);
            };
            String family = familyOf(dead);
            List<Candidate> usable = new ArrayList<>();
            for (Candidate c : found) {
                if (!c.id().equals(dead) && canChat(p, c.id())) {
                    usable.add(c);
                }
            }
            usable.sort(Comparator.comparingDouble((Candidate c) -> -score(p, c, needsImage, family))
                    .thenComparing(Candidate::id));
            List<String> out = new ArrayList<>();
            for (Candidate c : usable) {
                out.add(c.id());
            }
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** One entry from a provider's model list: its id, how new it is, and how much it can read. */
    record Candidate(String id, long created, long context) {}

    // ── Reading each provider's list ─────────────────────────────────────────

    private static JsonObject getJson(HttpClient http, HttpRequest req) throws Exception {
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("model list " + res.statusCode());
        }
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    private static HttpRequest.Builder get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET();
    }

    /** OpenAI: {@code GET /v1/models}, {@code data[].id} and when it was created. */
    private static List<Candidate> openAiModels(HttpClient http, String key) throws Exception {
        JsonObject body = getJson(http, get("https://api.openai.com/v1/models")
                .header("Authorization", "Bearer " + key).build());
        List<Candidate> out = new ArrayList<>();
        for (JsonElement e : body.getAsJsonArray("data")) {
            JsonObject m = e.getAsJsonObject();
            out.add(new Candidate(m.get("id").getAsString(), longOf(m, "created"), 0));
        }
        return out;
    }

    /** Gemini: {@code GET /v1beta/models}, only models that support {@code generateContent}. */
    private static List<Candidate> geminiModels(HttpClient http, String key) throws Exception {
        JsonObject body = getJson(http, get("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
                .header("x-goog-api-key", key).build());
        List<Candidate> out = new ArrayList<>();
        JsonArray models = body.getAsJsonArray("models");
        for (int i = 0; models != null && i < models.size(); i++) {
            JsonObject m = models.get(i).getAsJsonObject();
            JsonArray methods = m.getAsJsonArray("supportedGenerationMethods");
            boolean chats = false;
            for (int j = 0; methods != null && j < methods.size(); j++) {
                chats |= "generateContent".equals(methods.get(j).getAsString());
            }
            if (!chats) {
                continue;
            }
            String name = m.get("name").getAsString();
            out.add(new Candidate(name.startsWith("models/") ? name.substring(7) : name, 0,
                    longOf(m, "inputTokenLimit")));
        }
        return out;
    }

    /** Anthropic: {@code GET /v1/models}, newest first. */
    private static List<Candidate> anthropicModels(HttpClient http, String key) throws Exception {
        JsonObject body = getJson(http, get("https://api.anthropic.com/v1/models?limit=100")
                .header("x-api-key", key).header("anthropic-version", "2023-06-01").build());
        List<Candidate> out = new ArrayList<>();
        JsonArray data = body.getAsJsonArray("data");
        for (int i = 0; data != null && i < data.size(); i++) {
            JsonObject m = data.get(i).getAsJsonObject();
            // Listed newest first; the position stands in for a date.
            out.add(new Candidate(m.get("id").getAsString(), data.size() - i, 0));
        }
        return out;
    }

    private static long longOf(JsonObject o, String field) {
        try {
            return o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsLong() : 0;
        } catch (RuntimeException notANumber) {
            return 0;
        }
    }

    // ── Ranking ──────────────────────────────────────────────────────────────

    /** Words in a model id that mean it is not a chat model at all (speech, safety filters, embeddings…). */
    private static final String[] NOT_CHAT = {"whisper", "tts", "embed", "moderation",
            "dall-e", "audio", "realtime", "transcribe", "search", "image", "imagen", "veo", "aqa", "live",
            "robotics", "computer-use", "babbage", "davinci", "instruct"};

    static boolean canChat(Config.Provider p, String id) {
        String low = id.toLowerCase(Locale.ROOT);
        for (String bad : NOT_CHAT) {
            if (low.contains(bad)) {
                return false;
            }
        }
        return true;
    }

    /** Words that suggest a model can read a picture. A hint only: a wrong guess is caught by
     *  {@link #cannotSeeImages} and the next candidate is tried. */
    private static final String[] SEES = {"vision", "omni", "4o", "gpt-4.1", "gpt-5", "gemini", "claude"};

    private static double score(Config.Provider p, Candidate c, boolean needsImage, String family) {
        String low = c.id().toLowerCase(Locale.ROOT);
        double s = 0;
        if (needsImage) {
            for (String hint : SEES) {
                if (low.contains(hint)) {
                    s += 50;
                    break;
                }
            }
        }
        if (!family.isEmpty() && low.startsWith(family)) {
            s += 20;   // the same maker's line as the one that worked until now
        }
        // Stable and cheap beats experimental and slow for a panel people ask quick questions in.
        if (low.contains("preview") || low.contains("exp")) {
            s -= 15;
        }
        if (p == Config.Provider.GEMINI) {
            if (low.contains("flash")) {
                s += 10;
            }
            if (low.contains("lite")) {
                s -= 4;
            }
            if (low.contains("gemma")) {
                s -= 30;
            }
            s += versionOf(low) * 3;
        }
        // Bigger context and newer, as tie-breaks that still order a long list sensibly.
        s += Math.min(5, c.context() / 32_000.0);
        s += c.created() > 0 ? Math.min(5, c.created() / 400_000_000.0) : 0;
        return s;
    }

    /** "gemini-2.5-flash" -> 2.5, so a newer Gemini outranks an older one. */
    private static double versionOf(String id) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(id);
        return m.find() ? Math.min(20, Double.parseDouble(m.group(1))) : 0;
    }

    /** "gemini-2.5-flash" -> "gemini-", "gpt-4o-mini" -> "gpt-", "claude-opus-4-8" -> "claude-opus". */
    private static String familyOf(String id) {
        String low = id == null ? "" : id.toLowerCase(Locale.ROOT);
        if (low.startsWith("claude-")) {
            String[] parts = low.split("-");
            return parts.length > 1 ? parts[0] + "-" + parts[1] : low;
        }
        int dash = low.indexOf('-');
        return dash > 0 ? low.substring(0, dash + 1) : low;
    }
}
