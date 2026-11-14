package com.orion.controller.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Any provider with an OpenAI-style POST {baseUrl}/chat/completions endpoint
 * (for example a local model server). Plain java.net.http.
 */
public final class OpenAiCompatibleLlmClient implements LlmClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final LlmConfig config;
    private final String apiKey;

    public OpenAiCompatibleLlmClient(LlmConfig config, String apiKey) {
        this.config = config;
        this.apiKey = apiKey;
    }

    @Override
    public String complete(String systemPrompt, String userText) throws LlmException {
        if (config.baseUrl() == null || config.baseUrl().isBlank()) {
            throw new LlmException("llm.yaml: baseUrl is required for provider openai-compatible");
        }
        ObjectNode body = json.createObjectNode();
        body.put("model", config.model());
        body.put("max_tokens", config.maxTokensOrDefault());
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userText);
        String url = config.baseUrl().replaceAll("/+$", "") + "/chat/completions";
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(config.timeoutOrDefault()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (apiKey != null) {
                req.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> r = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new LlmException("LLM API returned " + r.statusCode() + ": " + r.body());
            }
            JsonNode content = json.readTree(r.body()).path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new LlmException("LLM API returned no text");
            }
            return content.asText();
        } catch (IOException e) {
            throw new LlmException("LLM API unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted", e);
        }
    }
}
