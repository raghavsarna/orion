package com.orion.controller.flowrules;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Output of the rule generator: the rules every switch should have, and per intent whether it could be applied.
 */
public record GenerationResult(Map<String, List<PlannedRule>> rules, Map<UUID, Outcome> outcomes, long topologyVersion,
                               FailMode failMode) {

    /** ACTIVE / NOT_POSSIBLE / EXPIRED, with a human-readable reason. */
    public record Outcome(String status, String reason) {
    }

    public static GenerationResult empty() {
        return new GenerationResult(Map.of(), Map.of(), -1, FailMode.normal(FailMode.DEFAULT_THRESHOLD));
    }
}
