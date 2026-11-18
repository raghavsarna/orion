package com.orion.controller.intent;

import com.orion.shared.flow.TimeWindow;

/**
 * What the user wants, in one flat format used by the form and the JSON API.
 * Endpoints (src/dst): an IP "10.0.1.5", a subnet "10.0.1.0/24", a group "group:guests", or empty/"any".
 * Only the fields relevant to the type are used.
 */
public record IntentSpec(
        String type,
        String src,
        String dst,
        String protocol,
        Integer port,
        String website,
        String linkId,
        Integer maxLatencyMs,
        Integer packetsPerSecond,
        TimeWindow timeWindow,
        String note) {

    public IntentType intentType() {
        return IntentType.valueOf(type.trim().toUpperCase());
    }
}
