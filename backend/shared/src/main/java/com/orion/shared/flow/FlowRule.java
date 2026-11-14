package com.orion.shared.flow;

/** One flow rule: match + action + priority (+ optional rate limit). No two rules on a switch share a priority. */
public record FlowRule(String ruleId, int priority, Match match, Action action, RateLimit rateLimit, String intentId) {
}
