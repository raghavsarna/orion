package com.orion.shared.flow;

import java.util.List;
import java.util.Map;

/** A switch's rules (highest priority first) and how many packets each one matched. */
public record FlowTableView(List<FlowRule> rules, Map<String, Long> hits) {
}
