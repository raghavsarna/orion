package com.orion.controller.intent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import java.time.Duration;
import java.util.stream.Collectors;

/** Claude through the official Anthropic Java SDK. */
public final class AnthropicLlmClient implements LlmClient {

    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final LlmConfig config;

    public AnthropicLlmClient(LlmConfig config, String apiKey) {
        this.config = config;
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(config.timeoutOrDefault()));
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            b.baseUrl(config.baseUrl());
        }
        this.client = b.build();
    }

    @Override
    public String complete(String systemPrompt, String userText) throws LlmException {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(config.model())
                .maxTokens(config.maxTokensOrDefault())
                .system(systemPrompt)
                .addUserMessage(userText);
        if (config.fallbacks() != null && !config.fallbacks().isBlank()) {
            // if the model declines, the API retries on a fallback model chosen by refusal category
            params.addBeta(FALLBACK_BETA)
                  .putAdditionalBodyProperty("fallbacks", JsonValue.from(config.fallbacks().trim()));
        }
        BetaMessage reply;
        try {
            reply = client.beta().messages().create(params.build());
        } catch (RuntimeException e) {
            throw new LlmException("Anthropic API call failed: " + e.getMessage(), e);
        }
        if (reply.stopReason().map(r -> r.equals(BetaStopReason.REFUSAL)).orElse(false)) {
            throw new LlmException("the model declined to translate this text");
        }
        String text = reply.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining());
        if (text.isBlank()) {
            throw new LlmException("the model returned no text");
        }
        return text;
    }
}
