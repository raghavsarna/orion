package com.orion.controller.intent;

import com.orion.controller.ControllerException;
import com.orion.shared.net.Ip;
import java.util.ArrayList;
import java.util.List;

/** Checks that an intent is well formed before it is stored (not whether the network can do it). */
public final class IntentValidator {

    /** Returns the cleaned-up spec (type upper-cased, "any" -> null) or throws 400 with every problem listed. */
    public IntentSpec validate(IntentSpec s) {
        if (s == null) {
            throw ControllerException.badRequest("intent body is missing");
        }
        List<String> errors = new ArrayList<>();
        IntentType type = null;
        try {
            type = s.intentType();
        } catch (RuntimeException e) {
            errors.add("type must be one of AVOID_LINK, MAX_LATENCY, ISOLATE_GROUPS, BLOCK_TRAFFIC, BLOCK_PROTOCOL, RATE_LIMIT");
        }
        String src = clean(s.src());
        String dst = clean(s.dst());
        checkEndpoint("src", src, errors);
        checkEndpoint("dst", dst, errors);
        if (s.port() != null && (s.port() < 1 || s.port() > 65535)) {
            errors.add("port must be 1..65535");
        }
        if (s.timeWindow() != null && s.timeWindow().validationError() != null) {
            errors.add(s.timeWindow().validationError());
        }
        if (type != null) {
            switch (type) {
                case AVOID_LINK -> require(blank(s.linkId()), "AVOID_LINK needs linkId", errors);
                case MAX_LATENCY -> {
                    require(src == null || dst == null, "MAX_LATENCY needs src and dst", errors);
                    require(s.maxLatencyMs() == null || s.maxLatencyMs() < 1, "MAX_LATENCY needs maxLatencyMs > 0", errors);
                }
                case ISOLATE_GROUPS -> require(src == null || dst == null, "ISOLATE_GROUPS needs src and dst", errors);
                case BLOCK_TRAFFIC -> require(src == null && dst == null && s.port() == null && blank(s.website()),
                        "BLOCK_TRAFFIC needs at least one of src, dst, port, website", errors);
                case BLOCK_PROTOCOL -> require(blank(s.protocol()), "BLOCK_PROTOCOL needs protocol", errors);
                case RATE_LIMIT -> {
                    require(src == null, "RATE_LIMIT needs src", errors);
                    require(s.packetsPerSecond() == null || s.packetsPerSecond() < 1,
                            "RATE_LIMIT needs packetsPerSecond > 0", errors);
                }
            }
        }
        if (!errors.isEmpty()) {
            throw ControllerException.badRequest(String.join("; ", errors));
        }
        return new IntentSpec(type.name(), src, dst, blankToNull(s.protocol()), s.port(), blankToNull(s.website()),
                blankToNull(s.linkId()), s.maxLatencyMs(), s.packetsPerSecond(),
                s.timeWindow() == null || s.timeWindow().isEmpty() ? null : s.timeWindow(), blankToNull(s.note()));
    }

    private static void checkEndpoint(String name, String value, List<String> errors) {
        if (value == null) {
            return;
        }
        if (value.startsWith("group:")) {
            if (value.length() <= "group:".length()) {
                errors.add(name + ": group name is missing");
            }
        } else if (!Ip.isIpOrCidr(value)) {
            errors.add(name + " must be an IP, a subnet like 10.0.1.0/24, group:<name>, or any");
        }
    }

    private static String clean(String endpoint) {
        if (endpoint == null || endpoint.isBlank() || endpoint.trim().equalsIgnoreCase("any")) {
            return null;
        }
        return endpoint.trim();
    }

    private static void require(boolean failed, String message, List<String> errors) {
        if (failed) {
            errors.add(message);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return blank(s) ? null : s.trim();
    }
}
