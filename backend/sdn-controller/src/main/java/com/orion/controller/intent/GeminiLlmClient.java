package com.orion.controller.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Gemini through its REST API (POST {baseUrl}/v1beta/models/{model}:generateContent), plain HTTP, no SDK.
 * Asks for a JSON-only reply so the answer parses as an intent directly.
 *
 * <p>Gemini models are sometimes overloaded (503), rate-limited (429), retired (404) or slow. Then the next model in
 * llm.yaml's {@code fallbacks} list is tried, so a live demo does not depend on one model being available.
 */
public final class GeminiLlmClient implements LlmClient {

    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    private final LlmConfig config;
    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private volatile String modelUsed;

    public GeminiLlmClient(LlmConfig config, String apiKey) {
        this.config = config;
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String modelUsed() {
        return modelUsed;
    }

    @Override
    public String complete(String systemPrompt, String userText) throws LlmException {
        List<String> models = new ArrayList<>();
        models.add(config.model());
        if (config.fallbacks() != null) {
            for (String m : config.fallbacks().split(",")) {
                if (!m.isBlank() && !models.contains(m.trim())) {
                    models.add(m.trim());
                }
            }
        }
        List<String> problems = new ArrayList<>();
        for (String model : models) {
            for (int attempt = 1; attempt <= 2; attempt++) {   // busy models often answer a moment later
                try {
                    String text = completeWith(model, systemPrompt, userText);
                    modelUsed = model;
                    return text;
                } catch (Retryable e) {
                    if (attempt == 2 || !e.getMessage().contains("HTTP 5") && !e.getMessage().contains("HTTP 429")) {
                        problems.add(model + ": " + e.getMessage());
                        break;
                    }
                    pause(1500);
                }
            }
        }
        throw new LlmException("no Gemini model answered (" + String.join("; ", problems) + ")");
    }

    private static void pause(long ms) throws LlmException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted", e);
        }
    }

    /** A failure worth trying the next model for: overloaded, rate-limited, retired or too slow. */
    private static final class Retryable extends Exception {
        Retryable(String message) {
            super(message);
        }
    }

    private String completeWith(String model, String systemPrompt, String userText) throws LlmException, Retryable {
        String base = config.baseUrl() == null || config.baseUrl().isBlank() ? DEFAULT_BASE_URL : config.baseUrl();
        String url = base.replaceAll("/+$", "") + "/v1beta/models/"
                + URLEncoder.encode(model, StandardCharsets.UTF_8) + ":generateContent";

        ObjectNode body = json.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", systemPrompt);
        ObjectNode user = body.putArray("contents").addObject();
        user.put("role", "user");
        user.putArray("parts").addObject().put("text", userText);
        ObjectNode gen = body.putObject("generationConfig");
        gen.put("responseMimeType", "application/json");
        gen.put("temperature", 0);
        if (config.maxTokens() != null) {
            gen.put("maxOutputTokens", config.maxTokens());
        }

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(config.timeoutSec() == null ? 20 : config.timeoutSec()))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)   // header, not query string, so the key never lands in URL logs
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode reply = r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
            int code = r.statusCode();
            if (code / 100 != 2) {
                String status = reply.path("error").path("status").asText("");
                if (code == 404 || code == 429 || code >= 500) {
                    throw new Retryable("HTTP " + code + (status.isBlank() ? "" : " " + status));
                }
                String msg = reply.path("error").path("message").asText("");
                throw new LlmException("Gemini API returned " + code + (msg.isBlank() ? "" : ": " + msg));
            }
            JsonNode candidate = reply.path("candidates").path(0);
            String finish = candidate.path("finishReason").asText("");
            StringBuilder text = new StringBuilder();
            candidate.path("content").path("parts").forEach(p -> text.append(p.path("text").asText("")));
            if (text.isEmpty()) {
                String blocked = reply.path("promptFeedback").path("blockReason").asText("");
                throw new LlmException("Gemini returned no text" + (blocked.isBlank() ? (finish.isBlank() ? "" : " (" + finish + ")")
                        : " (blocked: " + blocked + ")"));
            }
            return text.toString();
        } catch (HttpTimeoutException e) {
            throw new Retryable("no answer in " + (config.timeoutSec() == null ? 20 : config.timeoutSec()) + " s");
        } catch (IOException e) {
            throw new LlmException("Gemini API unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted", e);
        }
    }
}
