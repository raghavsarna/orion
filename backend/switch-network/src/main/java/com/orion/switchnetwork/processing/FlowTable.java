package com.orion.switchnetwork.processing;

import com.orion.shared.flow.FlowBatch;
import com.orion.shared.flow.FlowRule;
import com.orion.shared.flow.FlowTableView;
import com.orion.shared.packet.Packet;
import com.orion.switchnetwork.network.NetworkException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A switch's flow rules, highest priority first. The packet thread reads, API threads write.
 * Changes are applied all-or-nothing, and two rules may never share a priority.
 */
public final class FlowTable {

    private volatile List<FlowRule> rules = List.of();
    private final Map<String, AtomicLong> hits = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /** Highest-priority rule matching the packet, or null (send to controller). */
    public FlowRule lookup(Packet packet, int inPort, ZonedDateTime arrival) {
        for (FlowRule rule : rules) {
            if (rule.match() == null || rule.match().matches(packet, inPort, arrival)) {
                hits.computeIfAbsent(rule.ruleId(), k -> new AtomicLong()).incrementAndGet();
                return rule;
            }
        }
        return null;
    }

    /** False when the rule has a rate limit and its token bucket is empty. */
    public boolean allowedByRateLimit(FlowRule rule) {
        if (rule.rateLimit() == null) {
            return true;
        }
        String key = rule.rateLimit().key() == null ? rule.ruleId() : rule.rateLimit().key();
        return buckets.computeIfAbsent(key, k -> new TokenBucket(rule.rateLimit().packetsPerSecond())).tryTake();
    }

    public synchronized void apply(FlowBatch batch, int totalPorts) {
        Map<String, FlowRule> byId = new LinkedHashMap<>();
        for (FlowRule r : rules) {
            byId.put(r.ruleId(), r);
        }
        for (String id : batch.delete()) {
            byId.remove(id);
        }
        Set<String> changed = new HashSet<>();
        for (FlowRule r : batch.modify()) {
            check(r, totalPorts);
            if (!byId.containsKey(r.ruleId())) {
                throw NetworkException.badRequest("cannot modify unknown rule " + r.ruleId());
            }
            byId.put(r.ruleId(), r);
            changed.add(r.ruleId());
        }
        for (FlowRule r : batch.add()) {
            check(r, totalPorts);
            if (byId.put(r.ruleId(), r) != null) {
                changed.add(r.ruleId());
            }
        }
        Map<Integer, String> byPriority = new HashMap<>();
        for (FlowRule r : byId.values()) {
            String other = byPriority.putIfAbsent(r.priority(), r.ruleId());
            if (other != null) {
                throw NetworkException.badRequest("priority " + r.priority() + " is used by both " + other
                        + " and " + r.ruleId() + " (priorities must be unique)");
            }
        }
        List<FlowRule> sorted = new ArrayList<>(byId.values());
        sorted.sort(Comparator.comparingInt(FlowRule::priority).reversed());
        rules = List.copyOf(sorted);

        hits.keySet().retainAll(byId.keySet());
        changed.forEach(hits::remove);
        // drop buckets no rule uses any more, and reset buckets of changed rules
        Set<String> keys = new HashSet<>();
        for (FlowRule r : sorted) {
            if (r.rateLimit() != null) {
                String key = r.rateLimit().key() == null ? r.ruleId() : r.rateLimit().key();
                keys.add(key);
                if (changed.contains(r.ruleId())) {
                    buckets.remove(key);
                }
            }
        }
        buckets.keySet().retainAll(keys);
    }

    private static void check(FlowRule r, int totalPorts) {
        if (r.ruleId() == null || r.ruleId().isBlank()) {
            throw NetworkException.badRequest("every rule needs a ruleId");
        }
        if (r.action() == null || (!r.action().isForward() && !r.action().isDrop())) {
            throw NetworkException.badRequest("rule " + r.ruleId() + ": action must be FORWARD or DROP");
        }
        if (r.action().isForward()) {
            List<Integer> ports = r.action().allPorts();
            if (ports.isEmpty() || ports.stream().anyMatch(port -> port == null || port < 1 || port > totalPorts)) {
                throw NetworkException.badRequest("rule " + r.ruleId() + ": FORWARD needs ports 1.." + totalPorts);
            }
        }
        if (r.rateLimit() != null && r.rateLimit().packetsPerSecond() < 1) {
            throw NetworkException.badRequest("rule " + r.ruleId() + ": packetsPerSecond must be at least 1");
        }
    }

    public synchronized void clear() {
        rules = List.of();
        hits.clear();
        buckets.clear();
    }

    public FlowTableView view() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (FlowRule r : rules) {
            AtomicLong n = hits.get(r.ruleId());
            counts.put(r.ruleId(), n == null ? 0 : n.get());
        }
        return new FlowTableView(rules, counts);
    }
}
