package com.orion.controller.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orion.controller.core.ControllerException;
import com.orion.controller.db.GroupRepository;
import com.orion.controller.intent.IntentSpec;
import com.orion.controller.intent.IntentValidator;
import com.orion.controller.nib.LinkInfo;
import com.orion.controller.nib.Nib;
import com.orion.controller.nib.NibSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Natural language (Hindi, English, Hinglish) -> intent JSON. The LLM only fills in the intent format;
 * the result is validated and returned to the user to check. Flow rules are never produced by the LLM.
 * The provider and model come from llm.yaml, re-read on every call so edits apply without a restart.
 */
public final class LlmTranslator {

    private static final Logger log = LoggerFactory.getLogger(LlmTranslator.class);

    /** The translated intent plus what the model actually said. */
    public record Translation(IntentSpec intent, String raw, String provider, String model) {
    }

    /** Whether translation is usable, for the UI. */
    public record Status(boolean configured, String provider, String model, String problem) {
    }

    private final String configFile;
    private final Nib nib;
    private final GroupRepository groups;
    private final IntentValidator validator = new IntentValidator();
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public LlmTranslator(String configFile, Nib nib, GroupRepository groups) {
        this.configFile = configFile;
        this.nib = nib;
        this.groups = groups;
    }

    public Status status() {
        try {
            LlmConfig c = LlmConfig.load(configFile);
            String key = c.resolveApiKey();
            boolean needsKey = "anthropic".equalsIgnoreCase(c.provider());
            if (needsKey && key == null) {
                return new Status(false, c.provider(), c.model(), "API key not set (env " + c.apiKeyEnv() + ")");
            }
            return new Status(true, c.provider(), c.model(), null);
        } catch (IOException e) {
            return new Status(false, null, null, "cannot read " + configFile);
        }
    }

    public Translation translate(String text) {
        if (text == null || text.isBlank()) {
            throw ControllerException.badRequest("text is required");
        }
        LlmConfig config;
        try {
            config = LlmConfig.load(configFile);
        } catch (IOException e) {
            throw ControllerException.unavailable("cannot read LLM config " + configFile, e);
        }
        LlmClient client = createClient(config);
        String system = systemPrompt(config);

        String reply = call(client, system, text);
        try {
            return new Translation(parse(reply), reply, config.provider(), config.model());
        } catch (ControllerException first) {
            // one retry, telling the model what was wrong
            log.info("LLM output rejected ({}), retrying once", first.getMessage());
            String retry = call(client, system, text + "\n\nYour previous answer was invalid: " + first.getMessage()
                    + "\nReply again with only the corrected JSON object.");
            return new Translation(parse(retry), retry, config.provider(), config.model());
        }
    }

    private static String call(LlmClient client, String system, String text) {
        try {
            return client.complete(system, text);
        } catch (LlmClient.LlmException e) {
            throw ControllerException.unavailable(e.getMessage(), e);
        }
    }

    private static LlmClient createClient(LlmConfig c) {
        String key = c.resolveApiKey();
        String provider = c.provider() == null ? "" : c.provider().trim().toLowerCase();
        return switch (provider) {
            case "anthropic" -> {
                if (key == null) {
                    throw new ControllerException(503, "LLM not configured: set the API key (env "
                            + c.apiKeyEnv() + ") or apiKey in llm.yaml");
                }
                yield new AnthropicLlmClient(c, key);
            }
            case "openai-compatible" -> new OpenAiCompatibleLlmClient(c, key);
            default -> throw new ControllerException(503, "llm.yaml: provider must be anthropic or openai-compatible");
        };
    }

    /** Pulls the JSON object out of the reply and validates it as an intent. */
    private IntentSpec parse(String reply) {
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw ControllerException.badRequest("the reply contained no JSON object");
        }
        IntentSpec spec;
        try {
            spec = json.readValue(reply.substring(start, end + 1), IntentSpec.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw ControllerException.badRequest("the JSON did not match the intent format: " + e.getOriginalMessage());
        }
        return validator.validate(spec);
    }

    private String systemPrompt(LlmConfig c) {
        String template = null;
        if (c.promptFile() != null && !c.promptFile().isBlank() && Files.isRegularFile(Path.of(c.promptFile()))) {
            try {
                template = Files.readString(Path.of(c.promptFile()));
            } catch (IOException e) {
                log.warn("Cannot read prompt file {}, using the built-in prompt", c.promptFile());
            }
        }
        if (template == null) {
            try (InputStream in = LlmTranslator.class.getResourceAsStream("/intent_prompt.txt")) {
                template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException | NullPointerException e) {
                throw new IllegalStateException("built-in prompt missing", e);
            }
        }
        return template.replace("{{context}}", context());
    }

    /** Current links, host subnets and groups, so the model can use real names. */
    private String context() {
        NibSnapshot snap = nib.snapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("Links:\n");
        for (LinkInfo l : snap.graph().links()) {
            sb.append("- ").append(l.linkId()).append(": ").append(l.fromSwitch()).append(" <-> ")
              .append(l.toSwitch()).append(", ").append(l.latencyMs()).append(" ms\n");
        }
        sb.append("Host subnets:\n");
        for (NibSnapshot.HostSubnet h : snap.hosts()) {
            sb.append("- ").append(h.subnet()).append(" on switch ").append(h.switchId()).append('\n');
        }
        sb.append("Groups:\n");
        try {
            for (Map.Entry<String, List<String>> g : groups.findAll().entrySet()) {
                sb.append("- group:").append(g.getKey()).append(" = ").append(String.join(", ", g.getValue())).append('\n');
            }
        } catch (ControllerException e) {
            sb.append("- (unavailable)\n");
        }
        return sb.toString();
    }
}
