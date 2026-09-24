package com.tensec.aiscreen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Sends a screenshot + question to the selected AI provider (Gemini, OpenAI GPT or
 * Anthropic Claude) on a background thread, with a cooldown per keyset. (Groq was removed in 1.44.3;
 * see Config.)
 *
 * Security invariants (keep these when editing):
 *  - API keys travel in HTTP HEADERS only, never in URLs, so they can't leak through
 *    exception messages or logs that echo the request URL.
 *  - No logging in this class. Error strings shown to the user never contain the key.
 *  - Exactly three endpoints are ever contacted: generativelanguage.googleapis.com,
 *    api.openai.com, api.anthropic.com.
 */
public class AiVision {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();
    /**
     * When each KEYSET last sent a request, indexed by {@link Config.Keyset#ordinal()}.
     *
     * One per keyset, not one for the class. It was a single static until 1.43.0, so asking the G
     * panel a question put the Custom-spawn screen's "Ask AI" on cooldown too — two separate keys,
     * often two separate providers, and one of them refusing because of the other. The cooldown
     * exists to stop a double-click from spending the same key twice; it has no business crossing
     * from one key to another.
     */
    private static final long[] LAST_REQUEST = new long[Config.Keyset.values().length];

    /**
     * Sends one question to the provider configured for {@code keyset}.
     *
     * {@code onResult} is called exactly once, with the answer text or an "⚠ …" message, on a
     * background thread — callers hop back to the client thread for UI. {@code history} is prior
     * turns as {"user"|"ai", text} pairs (empty = single-shot) and is text-only; only the current
     * question can carry a screenshot. {@code systemPrompt} may be null.
     *
     * The G panel's system prompt (the guide to the whole mod) lives in FableVision Extras since
     * 1.43.0; the seed wish passes none, because its prompt is a strict JSON job that must not be
     * diluted.
     */
    public static void ask(byte[] pngImage, String question, List<String[]> history, String systemPrompt,
                           Config.Keyset keyset, Consumer<String> rawResult) {
        // EVERY string handed back goes through the redactor first: provider error messages have been
        // known to quote part of the key, and whatever reaches the panel may be copied into chat,
        // which Minecraft writes to latest.log. See KeySafe.
        final Consumer<String> onResult = reply -> rawResult.accept(KeySafe.redact(reply));
        Config.Provider provider = Config.getProvider(keyset);
        String key = Config.getKey(keyset);
        if (key.isEmpty()) {
            onResult.accept(keyset == Config.Keyset.SPAWN
                    ? "No " + provider.label + " key set for Custom spawn — click ⚙ key (it has its own key, separate from the G-menu)."
                    : "No " + provider.label + " API key set. Click ⚙ Key, pick your provider, and paste your key.");
            return;
        }

        long waitMs = claimCooldown(keyset);
        if (waitMs > 0) { onResult.accept("Cooldown — wait " + ((waitMs / 1000) + 1) + "s, then ask again."); return; }

        // AND EVERY STRING SENT. A key pasted into the question box by mistake (it happens — the key
        // box and the question box are one click apart) must not be sent to a model, least of all a
        // different provider's.
        final String q = KeySafe.redact((question == null || question.isBlank())
                ? "Briefly describe what's on this Minecraft screen and give a helpful tip."
                : question);
        final String model = Config.getModel(keyset);
        final String b64 = (pngImage != null && pngImage.length > 0)
                ? Base64.getEncoder().encodeToString(pngImage) : null;
        final List<String[]> hist = new java.util.ArrayList<>();
        if (history != null) {
            for (String[] turn : history) {
                hist.add(new String[]{turn[0], KeySafe.redact(turn[1])});
            }
        }
        final String system = systemPrompt == null ? null : KeySafe.redact(systemPrompt);
        // THE TWO CALLERS NEED DIFFERENT BUDGETS, and giving them one was the last piece of the
        // "best survival seed" failure.
        //
        // The G panel shows a handful of lines in a small box, so 1024 is already more than it can
        // display. The seed wish is a strict-JSON job whose answer for a bundle runs to six
        // structures, five biomes and a "cant" sentence — and a reasoning model is charged for its
        // thinking against the same budget even when that thinking is hidden. At 1024 the model
        // could spend most of it reasoning and be cut off mid-object, which arrives here as a
        // fenced block that never closes: the wish parser then correctly reports that the reply was
        // not a search, and the first line it quotes back is "```json". That is exactly what was
        // seen. 4096 is far more than the JSON needs and leaves the reasoning nowhere near the
        // ceiling; it costs nothing when unused, because output tokens are billed as generated.
        final int maxTokens = keyset == Config.Keyset.SPAWN ? 4096 : 1024;

        CompletableFuture.runAsync(() -> {
            try {
                onResult.accept(askWithFallback(provider, key, model, q, b64, hist, system, maxTokens, keyset));
            } catch (TimedOut e) {
                onResult.accept(e.getMessage());
            } catch (Throwable e) {
                // THROWABLE, NOT EXCEPTION, and it matters more here than anywhere else in the mod.
                // The one thing every caller relies on is that onResult is called exactly once: the
                // Custom-spawn screen sets aiWaiting and refuses to ask again until it hears back,
                // so an Error escaping this block does not produce an error message, it produces a
                // button stuck on "✨ …" for the rest of the session with no way to retry. An
                // OutOfMemoryError on a large reply is not hypothetical.
                onResult.accept("⚠ " + provider.label + " request failed: "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        });
    }

    /**
     * Takes this keyset's cooldown slot: 0 if the request may go out (and the slot is now taken), or
     * how many milliseconds are left. Package-visible so the per-keyset rule can be tested without a
     * network call.
     */
    static synchronized long claimCooldown(Config.Keyset keyset) {
        long now = System.currentTimeMillis();
        int i = keyset.ordinal();
        long waitMs = (LAST_REQUEST[i] + Config.getCooldownSeconds() * 1000L) - now;
        if (waitMs > 0) {
            return waitMs;
        }
        LAST_REQUEST[i] = now;
        return 0;
    }

    /** One request to one model: the raw reply, before it is turned into text. */
    private static HttpResponse<String> askOnce(Config.Provider provider, String key, String model, String q, String b64,
                                                List<String[]> hist, String system, int maxTokens,
                                                Config.Keyset keyset) throws Exception {
        return switch (provider) {
            case OPENAI -> send(buildOpenAi(key, model, q, b64, hist, system, maxTokens, keyset), provider);
            case ANTHROPIC -> send(buildAnthropic(key, model, q, b64, hist, system, maxTokens, keyset), provider);
            default -> {
                // Thinking off first (Gemini's slow part); a model that insists on thinking says so
                // with a 400 naming it, and is asked again without the switch.
                HttpResponse<String> res = send(buildGemini(key, model, q, b64, hist, system, maxTokens, keyset, true), provider);
                if (res.statusCode() == 400 && res.body() != null
                        && res.body().toLowerCase(java.util.Locale.ROOT).contains("thinking")) {
                    res = send(buildGemini(key, model, q, b64, hist, system, maxTokens, keyset, false), provider);
                }
                yield res;
            }
        };
    }

    private static String parse(Config.Provider provider, HttpResponse<String> res) {
        return switch (provider) {
            case OPENAI -> parseOpenAiStyle("GPT", res.statusCode(), res.body());
            case ANTHROPIC -> parseAnthropic(res.statusCode(), res.body());
            default -> parseGemini(res.statusCode(), res.body());
        };
    }

    /**
     * Asks the configured model; if the provider says that model is GONE, asks the provider which
     * models it has now and uses the first one that answers — see {@link ModelFallback}. A dead model
     * costs one extra round trip, once, instead of a broken panel until the next release.
     */
    private static String askWithFallback(Config.Provider provider, String key, String model, String q, String b64,
                                          List<String[]> hist, String system, int maxTokens,
                                          Config.Keyset keyset) throws Exception {
        HttpResponse<String> res = askOnce(provider, key, model, q, b64, hist, system, maxTokens, keyset);
        if (!ModelFallback.isRetired(res.statusCode(), res.body())) {
            return parse(provider, res);
        }
        String name = shortName(provider);
        List<String> candidates = ModelFallback.candidates(HTTP, provider, key, b64 != null, model);
        int tried = 0;
        for (String next : candidates) {
            if (tried++ == 3) {
                break;
            }
            HttpResponse<String> alt = askOnce(provider, key, next, q, b64, hist, system, maxTokens, keyset);
            if (alt.statusCode() == 200) {
                ModelFallback.adopt(provider, next);
                return parse(provider, alt) + "\n(" + name + " no longer offers " + model
                        + ", so FableVision switched to " + next + " and saved it.)";
            }
            if (ModelFallback.isRetired(alt.statusCode(), alt.body())
                    || ModelFallback.cannotSeeImages(alt.statusCode(), alt.body())) {
                continue;
            }
            return parse(provider, alt);   // a real error from a live model (rate limit, bad key…): say it
        }
        return "⚠ " + name + " has retired " + model + (candidates.isEmpty()
                ? " and its model list couldn't be read." : " and none of its current models answered.")
                + " Try again in a minute, or pick another provider under ⚙ Key.";
    }

    private static String shortName(Config.Provider p) {
        return switch (p) {
            case OPENAI -> "OpenAI";
            case ANTHROPIC -> "Anthropic";
            default -> "Gemini";
        };
    }

    /** A request that ran out of time twice. Its message is what the player reads. */
    private static final class TimedOut extends Exception {
        TimedOut(String message) {
            super(message);
        }
    }

    /**
     * Sends one request, and ONE retry with more time if the first attempt times out.
     *
     * Gemini's free tier in particular has slow spells: a request that times out once usually
     * answers on the second try. A second timeout is reported with what to do, not as
     * "request timed out".
     */
    private static HttpResponse<String> send(HttpRequest req, Config.Provider provider) throws Exception {
        try {
            return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException first) {
            Duration longer = req.timeout().orElse(Duration.ofSeconds(60)).multipliedBy(3).dividedBy(2);
            HttpRequest again = HttpRequest.newBuilder(req, (n, v) -> true).timeout(longer).build();
            try {
                return HTTP.send(again, HttpResponse.BodyHandlers.ofString());
            } catch (java.net.http.HttpTimeoutException second) {
                long total = req.timeout().orElse(Duration.ofSeconds(60)).plus(longer).toSeconds();
                throw new TimedOut("⚠ " + shortName(provider) + " didn't answer (tried twice, " + total + "s in all)."
                        + (provider == Config.Provider.GEMINI
                        ? " Google's free tier gets slow at busy times. Wait a minute and ask again;"
                        + " a shorter question helps too."
                        : " The service may be busy. Wait a minute and ask again, or pick another provider under ⚙ Key."));
            }
        }
    }

    /**
     * How long a request may take before it is given up on.
     *
     * TWO VALUES, because the two callers are waiting for different things. The G panel is a
     * conversation: the player is looking at a box and a minute of nothing is already too long.
     * The seed wish is a one-shot translation of a long, strict prompt — around fifteen thousand
     * characters of schema, glossary and catalog — answered by whatever model the player
     * configured, and a reasoning model writing a five-item bundle can genuinely take longer than a
     * minute. At 60s that came back as "request failed: request timed out" on exactly the wishes
     * the feature exists for, which reads as the AI ignoring long prompts.
     *
     * It is a ceiling, not a wait: a fast reply returns as soon as it arrives.
     */
    private static Duration timeoutFor(Config.Keyset keyset) {
        return Duration.ofSeconds(keyset == Config.Keyset.SPAWN ? 120 : 60);
    }

    private static HttpRequest.Builder base(String url, Config.Keyset keyset) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(timeoutFor(keyset))
                .header("Content-Type", "application/json");
    }

    // ── Gemini ──────────────────────────────────────────────────────────────
    private static HttpRequest buildGemini(String key, String model, String q, String b64, List<String[]> hist, String system, int maxTokens, Config.Keyset keyset, boolean noThinking) {
        JsonArray contents = new JsonArray();
        for (String[] turn : hist) { // prior turns, text-only
            JsonObject t = new JsonObject();
            t.addProperty("text", turn[1]);
            JsonArray p = new JsonArray();
            p.add(t);
            JsonObject c = new JsonObject();
            c.addProperty("role", "user".equals(turn[0]) ? "user" : "model");
            c.add("parts", p);
            contents.add(c);
        }
        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", q);
        parts.add(textPart);
        if (b64 != null) {
            JsonObject inline = new JsonObject();
            inline.addProperty("mime_type", "image/png");
            inline.addProperty("data", b64);
            JsonObject imgPart = new JsonObject();
            imgPart.add("inline_data", inline);
            parts.add(imgPart);
        }
        JsonObject content = new JsonObject();
        content.addProperty("role", "user");
        content.add("parts", parts);
        contents.add(content);
        JsonObject body = new JsonObject();
        body.add("contents", contents);
        if (system != null) {
            JsonObject sysText = new JsonObject();
            sysText.addProperty("text", system);
            JsonArray sysParts = new JsonArray();
            sysParts.add(sysText);
            JsonObject sys = new JsonObject();
            sys.add("parts", sysParts);
            body.add("system_instruction", sys);
        }

        // A CAP ON THE ANSWER, because the panel is small and the wait is the generation. The other
        // two providers already send max_tokens; Gemini was left uncapped, so it was free to write
        // several paragraphs into a box that shows a handful of lines — the player waited for text
        // that was then scrolled out of sight. 512 is comfortably more than the panel displays.
        JsonObject gen = new JsonObject();
        gen.addProperty("maxOutputTokens", Math.max(512, maxTokens));
        if (noThinking) {
            // THE TIMEOUTS WERE THE THINKING. Flash models reason before answering by default, and
            // with a screenshot or the long seed-wish prompt that reasoning is most of the wait —
            // long enough to hit the timeout. Neither job needs it. A model that can't turn it off
            // answers 400 naming "thinking", and askOnce repeats the request without this.
            JsonObject thinking = new JsonObject();
            thinking.addProperty("thinkingBudget", 0);
            gen.add("thinkingConfig", thinking);
        }
        body.add("generationConfig", gen);

        return base("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent", keyset)
                .header("x-goog-api-key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    private static String parseGemini(int status, String bodyStr) {
        try {
            JsonObject body = JsonParser.parseString(bodyStr).getAsJsonObject();
            if (status != 200) return "⚠ Gemini " + status + ": " + errorMessage(body, status);
            JsonArray candidates = body.getAsJsonArray("candidates");
            if (candidates == null || candidates.isEmpty()) {
                // A prompt the model declined to answer comes back with no candidates at all and a
                // promptFeedback block saying why. "(No answer returned.)" threw that away.
                return body.has("promptFeedback")
                        ? "⚠ Gemini returned no answer: " + body.get("promptFeedback")
                        : "(No answer returned.)";
            }
            JsonObject first = candidates.get(0).getAsJsonObject();
            String why = first.has("finishReason") && !first.get("finishReason").isJsonNull()
                    ? first.get("finishReason").getAsString() : "";
            // CONTENT CAN BE ABSENT, and this is the shape that produced "the AI just doesn't
            // respond". When Gemini stops at the token ceiling before writing anything, the
            // candidate has a finishReason and NO content object — and calling getAsJsonArray on
            // the null that came back threw, so the player got "Couldn't read Gemini's response:
            // null" or nothing useful at all, with no hint that the cause was the output budget.
            JsonObject contentObj = first.getAsJsonObject("content");
            JsonArray parts = contentObj == null ? null : contentObj.getAsJsonArray("parts");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; parts != null && i < parts.size(); i++) {
                JsonObject p = parts.get(i).getAsJsonObject();
                // A THOUGHT PART IS NOT AN ANSWER. Gemini marks its reasoning with "thought": true
                // on a part that otherwise looks exactly like the answer — same "text" field — so
                // appending everything with text in it prints the model's working out above what it
                // actually concluded. Same failure as the <think> tags reasoning models write, different spelling.
                if (p.has("thought") && !p.get("thought").isJsonNull() && p.get("thought").getAsBoolean()) {
                    continue;
                }
                if (p.has("text")) sb.append(p.get("text").getAsString());
            }
            String answer = stripReasoning(sb.toString());
            // Gemini spells the token ceiling "MAX_TOKENS"; the OpenAI shape says "length" and
            // Claude says "max_tokens". Three providers, three spellings, one fact — and the wish
            // parser needs to hear it, or a truncated reply is misreported as a model that did not
            // answer with a search. Keep all three in step.
            if ("MAX_TOKENS".equals(why)) {
                return answer.isEmpty()
                        ? "⚠ Gemini ran out of output tokens before writing an answer"
                          + " — finishReason: MAX_TOKENS."
                        : answer + "\n⚠ (Gemini "
                          + com.fablevision.client.seedfinder.WishParser.CUT_OFF_MARKER
                          + " — this answer is cut off.)";
            }
            if (answer.isEmpty()) {
                return why.isEmpty() || "STOP".equals(why)
                        ? "(No answer returned.)"
                        : "⚠ Gemini returned no answer — finishReason: " + why + ".";
            }
            return nonEmpty(answer);
        } catch (Exception e) {
            return "⚠ Couldn't read Gemini's response: " + e.getMessage();
        }
    }

    // ── OpenAI (GPT) ─────────────────────────────────────────────────────────
    private static HttpRequest buildOpenAi(String key, String model, String q, String b64, List<String[]> hist, String system, int maxTokens, Config.Keyset keyset) {
        return base("https://api.openai.com/v1/chat/completions", keyset)
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(chatCompletionsBody(model, q, b64, hist, system, maxTokens)))
                .build();
    }

    private static String chatCompletionsBody(String model, String q, String b64, List<String[]> hist,
                                              String system, int maxTokens) {
        JsonArray messages = new JsonArray();
        if (system != null) {
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", system);
            messages.add(sys);
        }
        for (String[] turn : hist) { // prior turns, text-only
            JsonObject m = new JsonObject();
            m.addProperty("role", "user".equals(turn[0]) ? "user" : "assistant");
            m.addProperty("content", turn[1]);
            messages.add(m);
        }
        JsonArray content = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", q);
        content.add(textPart);
        if (b64 != null) {
            JsonObject imageUrl = new JsonObject();
            imageUrl.addProperty("url", "data:image/png;base64," + b64);
            JsonObject imgPart = new JsonObject();
            imgPart.addProperty("type", "image_url");
            imgPart.add("image_url", imageUrl);
            content.add(imgPart);
        }
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.add("content", content);
        messages.add(msg);
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", maxTokens);
        body.add("messages", messages);
        return body.toString();
    }

    private static String parseOpenAiStyle(String label, int status, String bodyStr) {
        try {
            JsonObject body = JsonParser.parseString(bodyStr).getAsJsonObject();
            if (status != 200) {
                String msg = errorMessage(body, status);
                // A RETIRED MODEL IS A 404 AND READS LIKE A BROKEN MOD — "⚠ … 404: The model ... does
                // not exist" says nothing about what to do. Providers retire models on their own
                // schedule, so the fix is to say where the name is kept.
                if (status == 404 && msg.toLowerCase(java.util.Locale.ROOT).contains("model")) {
                    msg += "  — that model has been retired by " + label + ". Edit the model name in"
                            + " config/aiscreen.json (or delete the file to get the current default).";
                }
                return "⚠ " + label + " " + status + ": " + msg;
            }
            JsonArray choices = body.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) return "(No answer returned.)";
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");
            String text = message != null && message.has("content") && !message.get("content").isJsonNull()
                    ? message.get("content").getAsString() : "";
            String answer = stripReasoning(text);
            String why = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()
                    ? choice.get("finish_reason").getAsString() : "";
            // AN EMPTY ANSWER HAS A REASON, AND THE REASON IS IN THE RESPONSE. Returning
            // "(Empty answer.)" here threw that away, and the caller then had nothing to report but
            // its own generic failure — which is how a broken wish came out as "Couldn't read the
            // AI's answer" with no way to tell whether the model had refused, been cut off, or
            // simply not been asked. "length" is the one that actually happens: a reasoning model
            // spends the whole output budget thinking and the answer never starts.
            if (answer.isEmpty()) {
                if ("length".equals(why)) {
                    return "⚠ " + label + " ran out of output tokens before writing an answer"
                            + (text.isBlank() ? "" : " (it spent them on internal reasoning)")
                            + " — finish_reason: length.";
                }
                if (!why.isEmpty() && !"stop".equals(why)) {
                    return "⚠ " + label + " returned no answer — finish_reason: " + why + ".";
                }
                return "(Empty answer.)";
            }
            // Cut off mid-sentence, but with something in hand. Say so rather than handing back a
            // truncated answer that reads as a complete one — the wish parser in particular can
            // only report "that isn't valid JSON" about a reply that was simply stopped early.
            if ("length".equals(why)) {
                // The wording carries WishParser.CUT_OFF_MARKER, which is how the seed wish tells a
                // truncated reply apart from a reply that simply was not a search. Keep them in step.
                return answer + "\n⚠ (" + label + " "
                        + com.fablevision.client.seedfinder.WishParser.CUT_OFF_MARKER
                        + " — this answer is cut off.)";
            }
            return nonEmpty(answer);
        } catch (Exception e) {
            return "⚠ Couldn't read " + label + "'s response: " + e.getMessage();
        }
    }

    // ── Anthropic (Claude) ──────────────────────────────────────────────────
    private static HttpRequest buildAnthropic(String key, String model, String q, String b64, List<String[]> hist, String system, int maxTokens, Config.Keyset keyset) {
        JsonArray messages = new JsonArray();
        for (String[] turn : hist) { // prior turns, text-only
            JsonObject m = new JsonObject();
            m.addProperty("role", "user".equals(turn[0]) ? "user" : "assistant");
            m.addProperty("content", turn[1]);
            messages.add(m);
        }
        JsonArray content = new JsonArray();
        if (b64 != null) {
            JsonObject source = new JsonObject();
            source.addProperty("type", "base64");
            source.addProperty("media_type", "image/png");
            source.addProperty("data", b64);
            JsonObject imgPart = new JsonObject();
            imgPart.addProperty("type", "image");
            imgPart.add("source", source);
            content.add(imgPart); // image before text, per Anthropic's vision guidance
        }
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", q);
        content.add(textPart);
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.add("content", content);
        messages.add(msg);
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        // THE BUDGET THIS METHOD WAS HANDED, not a number typed here. It said 1024 regardless of
        // the argument, so the fix that gave the seed wish 4096 — written for exactly the "best
        // survival seed" failure described where maxTokens is computed — reached Gemini and OpenAI
        // and never reached Claude. A bundle wish answered by Claude kept being cut off
        // mid-object, and because the truncation was not detected either (see parseAnthropic) it
        // arrived at the wish parser as an unreadable reply whose first line was "```json". That is
        // the reply of "json" that was reported from the game.
        body.addProperty("max_tokens", maxTokens);
        body.add("messages", messages);
        if (system != null) {
            body.addProperty("system", system);
        }

        return base("https://api.anthropic.com/v1/messages", keyset)
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    private static String parseAnthropic(int status, String bodyStr) {
        try {
            JsonObject body = JsonParser.parseString(bodyStr).getAsJsonObject();
            if (status != 200) return "⚠ Claude " + status + ": " + errorMessage(body, status);
            // A refusal is a 200 with stop_reason "refusal" and no usable content.
            if (body.has("stop_reason") && !body.get("stop_reason").isJsonNull()
                    && "refusal".equals(body.get("stop_reason").getAsString())) {
                return "⚠ Claude declined to answer this request.";
            }
            String why = body.has("stop_reason") && !body.get("stop_reason").isJsonNull()
                    ? body.get("stop_reason").getAsString() : "";
            JsonArray content = body.getAsJsonArray("content");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; content != null && i < content.size(); i++) {
                JsonObject block = content.get(i).getAsJsonObject();
                // Only "text" blocks — a "thinking" block is a separate type here, so Claude's
                // reasoning has never leaked into the panel. The stripper below is belt and braces.
                if (block.has("type") && "text".equals(block.get("type").getAsString()) && block.has("text")) {
                    sb.append(block.get("text").getAsString());
                }
            }
            String answer = stripReasoning(sb.toString());
            // CUT OFF IS ITS OWN ANSWER, and Claude spells it "max_tokens" where the OpenAI shape
            // says finish_reason "length". Both are handled now; only one was, which is why a
            // truncated Claude reply reached the wish parser looking like a model that had simply
            // not answered with a search. Keep this in step with parseOpenAiStyle.
            if ("max_tokens".equals(why)) {
                return answer.isEmpty()
                        ? "⚠ Claude ran out of output tokens before writing an answer"
                          + " — stop_reason: max_tokens."
                        : answer + "\n⚠ (Claude "
                          + com.fablevision.client.seedfinder.WishParser.CUT_OFF_MARKER
                          + " — this answer is cut off.)";
            }
            if (answer.isEmpty()) {
                return why.isEmpty() || "end_turn".equals(why)
                        ? "(No answer returned.)"
                        : "⚠ Claude returned no answer — stop_reason: " + why + ".";
            }
            return nonEmpty(answer);
        } catch (Exception e) {
            return "⚠ Couldn't read Claude's response: " + e.getMessage();
        }
    }

    // ── Shared helpers ──────────────────────────────────────────────────────
    private static String errorMessage(JsonObject body, int status) {
        try {
            if (body.has("error")) {
                JsonObject err = body.getAsJsonObject("error");
                if (err.has("message")) return err.get("message").getAsString();
            }
        } catch (Exception ignored) {}
        return "HTTP " + status;
    }

    private static String nonEmpty(String s) {
        String t = s == null ? "" : s.trim();
        return t.isEmpty() ? "(Empty answer.)" : t;
    }

    // ── Reasoning tokens ────────────────────────────────────────────────────

    /** A complete {@code <think>…</think>} pair, and the same for the other names models use. */
    private static final java.util.regex.Pattern CLOSED_REASONING = java.util.regex.Pattern.compile(
            "(?is)<\\s*(think|thinking|reasoning|scratchpad)\\s*>.*?<\\s*/\\s*\\1\\s*>");
    /** Everything up to and including a CLOSING tag whose opener never arrived. */
    private static final java.util.regex.Pattern DANGLING_CLOSE = java.util.regex.Pattern.compile(
            "(?is)^.*?<\\s*/\\s*(think|thinking|reasoning|scratchpad)\\s*>");
    /** An OPENING tag with no close: the reply is reasoning and stopped before the answer began. */
    private static final java.util.regex.Pattern DANGLING_OPEN = java.util.regex.Pattern.compile(
            "(?is)<\\s*(think|thinking|reasoning|scratchpad)\\s*>.*$");

    /**
     * The ANSWER, with any reasoning the model wrote before it removed.
     *
     * Reasoning models emit their working out as part of the reply, wrapped in {@code <think>} tags
     * — several paragraphs of "wait, let me reconsider" that end up printed above the answer in the
     * G panel, and end up ahead of the JSON on the seed-wish path where they break the parse. This
     * removes it whichever provider or model sent it, including the next model to invent a fourth
     * spelling of the same tag.
     *
     * THREE SHAPES, because all three turn up and only the first is the tidy one:
     *
     *   1. a matched pair — deleted wherever it appears;
     *   2. a closing tag with no opener — some providers strip the opener on the way out, leaving
     *      the reasoning itself in front of a bare {@code </think>}. Everything up to that tag is
     *      the working out, so it goes;
     *   3. an opening tag with no close — the model was still thinking when it hit the token limit.
     *      There IS no answer in this reply, so what is left is empty, and the caller reports that
     *      honestly rather than showing the reasoning as though it were one.
     *
     * Anything with no tags at all is returned untouched, which is every non-reasoning model.
     *
     * PUBLIC so {@code gradlew wishDiag} can check the three shapes against the real method rather
     * than a copy of this regex. A stripper that is tested by reimplementing itself agrees with
     * itself whatever it does to a real reply.
     */
    public static String stripReasoning(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String out = CLOSED_REASONING.matcher(raw).replaceAll("");
        out = DANGLING_CLOSE.matcher(out).replaceFirst("");
        out = DANGLING_OPEN.matcher(out).replaceFirst("");
        return out.trim();
    }
}
