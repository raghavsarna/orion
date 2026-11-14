package com.orion.controller.flowrules;

import com.orion.shared.flow.FlowRule;

/**
 * A generated rule for one switch. {@code kind} sets its priority band (DROP > RATE_LIMIT > PATH > BASELINE);
 * {@code order} is the push round: switches closest to the destination get their rules first.
 */
public record PlannedRule(String switchId, FlowRule rule, String kind, int order) {

    public static final String DROP = "DROP";
    public static final String RATE_LIMIT = "RATE_LIMIT";
    public static final String PATH = "PATH";
    public static final String BASELINE = "BASELINE";
}
