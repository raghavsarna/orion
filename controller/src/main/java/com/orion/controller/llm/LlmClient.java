package com.orion.controller.llm;

/** One request to an LLM: system prompt + user text in, reply text out. */
public interface LlmClient {

    String complete(String systemPrompt, String userText) throws LlmException;

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
