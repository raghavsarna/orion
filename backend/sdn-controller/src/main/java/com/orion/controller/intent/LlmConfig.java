package com.orion.controller.intent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Contents of llm.yaml: which LLM translates natural language into intents.
 *
 * @param provider   "anthropic" (official Anthropic SDK) or "openai-compatible" (any /chat/completions API)
 * @param model      model id, e.g. claude-opus-5-5
 * @param baseUrl    API base URL (empty = provider default)
 * @param apiKey     the key itself (prefer apiKeyEnv)
 * @param apiKeyEnv  name of the environment variable holding the key
 * @param maxTokens  max output tokens
 * @param timeoutSec request timeout
 * @param fallbacks  Anthropic only: server-side refusal fallback mode ("default"), empty to turn off
 * @param promptFile system prompt template; {{context}} is replaced with the current topology and groups
 */
public record LlmConfig(String provider, String model, String baseUrl, String apiKey, String apiKeyEnv,
                        Integer maxTokens, Integer timeoutSec, String fallbacks, String promptFile) {

    public static LlmConfig load(String path) throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return yaml.readValue(Files.readString(Path.of(path)), LlmConfig.class);
    }

    /** The API key from apiKey or the env var named by apiKeyEnv; null when neither is set. */
    public String resolveApiKey() {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }
        if (apiKeyEnv != null && !apiKeyEnv.isBlank()) {
            String v = System.getenv(apiKeyEnv.trim());
            return v == null || v.isBlank() ? null : v.trim();
        }
        return null;
    }

    public int maxTokensOrDefault() {
        return maxTokens == null ? 4096 : maxTokens;
    }

    public int timeoutOrDefault() {
        return timeoutSec == null ? 60 : timeoutSec;
    }
}
