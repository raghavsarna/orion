package com.orion.controller.intent;

/** One request to an LLM: system prompt + user text in, reply text out. */
public interface LlmClient {

    String complete(String systemPrompt, String userText) throws LlmException;

    /** The model that produced the last reply, when the client can switch models; null otherwise. */
    default String modelUsed() {
        return null;
    }

    /** The LLM call failed (not configured, network, refusal, bad reply). */
    final class LlmException extends Exception {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
