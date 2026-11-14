package com.orion.controller.intent;

/** The kinds of intent a user can give. */
public enum IntentType {
    /** Do not use a link (linkId). */
    AVOID_LINK,
    /** Traffic src -> dst must arrive in under maxLatencyMs. */
    MAX_LATENCY,
    /** Group src must never reach group dst. */
    ISOLATE_GROUPS,
    /** Block traffic from/to an IP, port or website (src, dst, port, website). */
    BLOCK_TRAFFIC,
    /** Block a kind of traffic (protocol), optionally only for src/dst. */
    BLOCK_PROTOCOL,
    /** Limit src (optionally to dst) to packetsPerSecond. */
    RATE_LIMIT
}
