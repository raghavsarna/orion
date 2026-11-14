package com.orion.controller.intent;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored intent. status: PENDING (saved, rules not generated yet), ACTIVE, NOT_POSSIBLE (reason says why), EXPIRED.
 */
public record Intent(UUID id, IntentSpec spec, String status, String reason, Instant createdAt) {

    public static final String PENDING = "PENDING";
    public static final String ACTIVE = "ACTIVE";
    public static final String NOT_POSSIBLE = "NOT_POSSIBLE";
    public static final String EXPIRED = "EXPIRED";

    public Intent withStatus(String newStatus, String newReason) {
        return new Intent(id, spec, newStatus, newReason, createdAt);
    }
}
