package com.orion.shared.flow;

/**
 * Token-bucket limit for packets matching a rule. Rules on the same switch with the same {@code key}
 * share one bucket (one intent = one shared limit). Null key = bucket per rule.
 */
public record RateLimit(int packetsPerSecond, String key) {
}
