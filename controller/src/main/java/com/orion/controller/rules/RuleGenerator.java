package com.orion.controller.rules;

import com.orion.common.flow.Action;
import com.orion.common.flow.FlowRule;
import com.orion.common.flow.Match;
import com.orion.common.flow.RateLimit;
import com.orion.common.flow.TimeWindow;
import com.orion.common.net.Ip;
import com.orion.controller.intent.Intent;
import com.orion.controller.intent.IntentSpec;
import com.orion.controller.intent.IntentType;
import com.orion.controller.nib.LinkInfo;
import com.orion.controller.nib.NibSnapshot;
import com.orion.controller.nib.NibSnapshot.HostSubnet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns intents + topology into the flow rules every switch should have. Pure function: no I/O.
 *
 * <ol>
 *   <li>Expired intents are marked EXPIRED.</li>
 *   <li>AVOID_LINK intents remove their link from the routing graph (unless that would cut the network).</li>
 *   <li>Baseline: every switch gets one forward rule per host subnet, along the fewest-hops path.</li>
 *   <li>MAX_LATENCY: lowest-latency path; NOT_POSSIBLE if it is slower than the limit.</li>
 *   <li>BLOCK_* / ISOLATE_GROUPS: DROP rules on the switches where the traffic enters.</li>
 *   <li>RATE_LIMIT: forward rules with a shared token bucket on the entry switches.</li>
 *   <li>Each switch's rules get unique priorities: DROP &gt; RATE_LIMIT &gt; PATH &gt; BASELINE,
 *       more specific destinations first.</li>
 * </ol>
 */
public final class RuleGenerator {

    /** Thrown while compiling one intent when the network cannot do it. */
    private static final class NotPossible extends Exception {
        NotPossible(String message) {
            super(message);
        }
    }

    private static final Map<String, Integer> BASE = Map.of(
            PlannedRule.DROP, 50_000, PlannedRule.RATE_LIMIT, 40_000, PlannedRule.PATH, 30_000, PlannedRule.BASELINE, 10_000);

    public GenerationResult generate(NibSnapshot snap, List<Intent> intents, Map<String, List<String>> groups,
                                     ZonedDateTime now) {
        Map<UUID, GenerationResult.Outcome> outcomes = new LinkedHashMap<>();
        PathGraph full = PathGraph.of(snap);
        List<HostSubnet> hosts = snap.hosts().stream().filter(h -> full.hasNode(h.switchId())).toList();

        // 1. expired intents
        List<Intent> active = new ArrayList<>();
        for (Intent i : intents) {
            TimeWindow tw = i.spec().timeWindow();
            if (tw != null && tw.isExpired(now.toInstant(), now.getZone())) {
                outcomes.put(i.id(), new GenerationResult.Outcome(Intent.EXPIRED, "its time window has ended"));
            } else {
                active.add(i);
            }
        }
        active.sort(Comparator.comparing(Intent::createdAt));

        // 2. AVOID_LINK changes the graph used for routing
        PathGraph g = full;
        for (Intent i : active) {
            if (i.spec().intentType() != IntentType.AVOID_LINK) {
                continue;
            }
            String linkId = i.spec().linkId();
            TimeWindow tw = i.spec().timeWindow();
            if (!full.hasLink(linkId)) {
                outcomes.put(i.id(), notPossible("link " + linkId + " is not in the topology (down or unknown)"));
            } else if (tw != null && !tw.isActive(now)) {
                outcomes.put(i.id(), new GenerationResult.Outcome(Intent.ACTIVE, "outside its time window right now"));
            } else {
                PathGraph without = g.without(linkId);
                String cut = firstDisconnected(g, without, hosts);
                if (cut != null) {
                    outcomes.put(i.id(), notPossible("avoiding " + linkId + " would cut off " + cut));
                } else {
                    g = without;
                    outcomes.put(i.id(), new GenerationResult.Outcome(Intent.ACTIVE, "routes avoid " + linkId));
                }
            }
        }

        Map<String, List<PlannedRule>> rules = new HashMap<>();

        // 3. baseline routing: fewest hops to every host subnet
        Map<String, Map<String, PathGraph.Route>> towards = new HashMap<>();
        for (HostSubnet h : hosts) {
            Map<String, PathGraph.Route> routes = g.routesTo(h.switchId(), PathGraph.Weight.HOPS);
            towards.put(h.subnet(), routes);
            for (Map.Entry<String, PathGraph.Route> e : routes.entrySet()) {
                String sw = e.getKey();
                int port = sw.equals(h.switchId()) ? h.port() : e.getValue().outPort();
                Match m = Match.builder().dstIp(h.subnet()).build();
                add(rules, sw, PlannedRule.BASELINE, null, m, Action.forward(port), null, e.getValue().hops());
            }
        }

        // 4. every other intent
        for (Intent i : active) {
            IntentType type = i.spec().intentType();
            if (type == IntentType.AVOID_LINK) {
                continue;
            }
            Map<String, List<PlannedRule>> own = new HashMap<>();
            try {
                String reason = switch (type) {
                    case MAX_LATENCY -> latency(i, g, hosts, groups, own);
                    case BLOCK_TRAFFIC, BLOCK_PROTOCOL, ISOLATE_GROUPS -> block(i, hosts, groups, own);
                    case RATE_LIMIT -> rateLimit(i, hosts, towards, groups, own);
                    default -> "";
                };
                own.forEach((sw, list) -> rules.computeIfAbsent(sw, k -> new ArrayList<>()).addAll(list));
                outcomes.put(i.id(), new GenerationResult.Outcome(Intent.ACTIVE, reason));
            } catch (NotPossible e) {
                outcomes.put(i.id(), notPossible(e.getMessage()));
            }
        }

        return new GenerationResult(assignPriorities(rules), outcomes, snap.version());
    }

    // ---------- intent compilers ----------

    private String latency(Intent i, PathGraph g, List<HostSubnet> hosts, Map<String, List<String>> groups,
                           Map<String, List<PlannedRule>> out) throws NotPossible {
        IntentSpec s = i.spec();
        int limit = s.maxLatencyMs();
        int worst = 0;
        List<String> paths = new ArrayList<>();
        for (String src : expand(s.src(), groups)) {
            for (String srcSwitch : owners(src, hosts, "source")) {
                for (String dst : expand(s.dst(), groups)) {
                    List<HostSubnet> dstHosts = hostsFor(dst, hosts);
                    if (dstHosts.isEmpty()) {
                        throw new NotPossible("destination " + dst + " is not in any known host subnet");
                    }
                    for (HostSubnet dh : dstHosts) {
                        List<LinkInfo> path = g.path(srcSwitch, dh.switchId(), PathGraph.Weight.LATENCY);
                        if (path == null) {
                            throw new NotPossible("no path from " + srcSwitch + " to " + dh.switchId());
                        }
                        int total = path.stream().mapToInt(LinkInfo::latencyMs).sum();
                        if (total > limit) {
                            throw new NotPossible("fastest path " + describe(srcSwitch, path) + " takes " + total
                                    + " ms (limit " + limit + " ms)");
                        }
                        worst = Math.max(worst, total);
                        String dstMatch = narrower(dst, dh.subnet());
                        for (int k = 0; k < path.size(); k++) {
                            LinkInfo l = path.get(k);
                            Match m = Match.builder().srcIp(src).dstIp(dstMatch).timeWindow(s.timeWindow()).build();
                            add(out, l.fromSwitch(), PlannedRule.PATH, i.id(), m, Action.forward(l.fromPort()), null,
                                    path.size() - k);
                        }
                        Match last = Match.builder().srcIp(src).dstIp(dstMatch).timeWindow(s.timeWindow()).build();
                        add(out, dh.switchId(), PlannedRule.PATH, i.id(), last, Action.forward(dh.port()), null, 0);
                        paths.add(describe(srcSwitch, path) + " " + total + " ms");
                    }
                }
            }
        }
        return "path " + String.join(", ", new LinkedHashSet<>(paths)) + " (limit " + limit + " ms)";
    }

    private String block(Intent i, List<HostSubnet> hosts, Map<String, List<String>> groups,
                         Map<String, List<PlannedRule>> out) throws NotPossible {
        IntentSpec s = i.spec();
        Set<String> switches = new LinkedHashSet<>();
        for (String src : expand(s.src(), groups)) {
            for (String sw : owners(src, hosts, "source")) {
                for (String dst : expand(s.dst(), groups)) {
                    Match.Builder m = Match.builder().srcIp(src).dstIp(dst).dstPort(s.port()).host(s.website())
                            .timeWindow(s.timeWindow());
                    Protocols.apply(m, s.protocol());
                    add(out, sw, PlannedRule.DROP, i.id(), m.build(), Action.drop(), null, -1);
                    switches.add(sw);
                }
            }
        }
        return "dropped at " + String.join(", ", switches);
    }

    private String rateLimit(Intent i, List<HostSubnet> hosts, Map<String, Map<String, PathGraph.Route>> towards,
                             Map<String, List<String>> groups, Map<String, List<PlannedRule>> out) throws NotPossible {
        IntentSpec s = i.spec();
        RateLimit limit = new RateLimit(s.packetsPerSecond(), "intent-" + i.id());
        Set<String> switches = new LinkedHashSet<>();
        for (String src : expand(s.src(), groups)) {
            for (String sw : owners(src, hosts, "source")) {
                for (String dst : expand(s.dst(), groups)) {
                    for (HostSubnet h : hosts) {
                        if (dst != null && !Ip.overlaps(dst, h.subnet())) {
                            continue;
                        }
                        PathGraph.Route r = towards.getOrDefault(h.subnet(), Map.of()).get(sw);
                        if (r == null) {
                            continue;   // unreachable from here
                        }
                        int port = sw.equals(h.switchId()) ? h.port() : r.outPort();
                        Match.Builder m = Match.builder().srcIp(src).dstIp(dst == null ? h.subnet() : narrower(dst, h.subnet()))
                                .dstPort(s.port()).timeWindow(s.timeWindow());
                        Protocols.apply(m, s.protocol());
                        add(out, sw, PlannedRule.RATE_LIMIT, i.id(), m.build(), Action.forward(port), limit, r.hops());
                        switches.add(sw);
                    }
                }
            }
        }
        return s.packetsPerSecond() + " pps at " + String.join(", ", switches);
    }

    // ---------- helpers ----------

    /** "group:x" -> its members; IP/subnet -> itself; null -> [null] (any). */
    private static List<String> expand(String endpoint, Map<String, List<String>> groups) throws NotPossible {
        if (endpoint == null) {
            List<String> any = new ArrayList<>();
            any.add(null);
            return any;
        }
        if (endpoint.startsWith("group:")) {
            String name = endpoint.substring("group:".length());
            List<String> members = groups.get(name);
            if (members == null) {
                throw new NotPossible("group " + name + " does not exist");
            }
            if (members.isEmpty()) {
                throw new NotPossible("group " + name + " is empty");
            }
            for (String m : members) {
                if (!Ip.isIpOrCidr(m)) {
                    throw new NotPossible("group " + name + " has a bad member: " + m);
                }
            }
            return members;
        }
        return List.of(endpoint);
    }

    /** Switches where traffic from {@code src} enters the network (all edge switches when src is any). */
    private static List<String> owners(String src, List<HostSubnet> hosts, String what) throws NotPossible {
        Set<String> out = new LinkedHashSet<>();
        for (HostSubnet h : hosts) {
            if (src == null || Ip.overlaps(src, h.subnet())) {
                out.add(h.switchId());
            }
        }
        if (out.isEmpty()) {
            throw new NotPossible(src == null ? "there are no host subnets in the topology"
                    : what + " " + src + " is not in any known host subnet");
        }
        return new ArrayList<>(out);
    }

    private static List<HostSubnet> hostsFor(String dst, List<HostSubnet> hosts) {
        return hosts.stream().filter(h -> dst == null || Ip.overlaps(dst, h.subnet())).collect(Collectors.toList());
    }

    /** The more specific of two overlapping IP ranges. */
    private static String narrower(String a, String b) {
        if (a == null) {
            return b;
        }
        return Ip.prefixLength(a) >= Ip.prefixLength(b) ? a : b;
    }

    /** First host subnet that was reachable from another one before but not after a change, or null. */
    private static String firstDisconnected(PathGraph before, PathGraph after, List<HostSubnet> hosts) {
        for (HostSubnet a : hosts) {
            Set<String> was = before.reachableFrom(a.switchId());
            Set<String> now = after.reachableFrom(a.switchId());
            for (HostSubnet b : hosts) {
                if (was.contains(b.switchId()) && !now.contains(b.switchId())) {
                    return b.subnet() + " from " + a.subnet();
                }
            }
        }
        return null;
    }

    private static String describe(String start, List<LinkInfo> path) {
        StringBuilder sb = new StringBuilder(start);
        path.forEach(l -> sb.append("→").append(l.toSwitch()));
        return sb.toString();
    }

    private static GenerationResult.Outcome notPossible(String reason) {
        return new GenerationResult.Outcome(Intent.NOT_POSSIBLE, reason);
    }

    private static void add(Map<String, List<PlannedRule>> rules, String sw, String kind, UUID intentId, Match m,
                            Action a, RateLimit limit, int order) {
        String intent = intentId == null ? null : intentId.toString();
        String id = ruleId(kind, intent, sw, m, a, limit);
        FlowRule rule = new FlowRule(id, 0, m, a, limit, intent);
        List<PlannedRule> list = rules.computeIfAbsent(sw, k -> new ArrayList<>());
        if (list.stream().noneMatch(p -> p.rule().ruleId().equals(id))) {
            list.add(new PlannedRule(sw, rule, kind, order));
        }
    }

    /** Same input -> same id, so recomputing an unchanged network produces no changes to push. */
    private static String ruleId(String kind, String intent, String sw, Match m, Action a, RateLimit limit) {
        String text = kind + "|" + intent + "|" + sw + "|" + m + "|" + a + "|" + limit;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            return "r" + HexFormat.of().formatHex(hash, 0, 5);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Unique priority per switch: band by kind, then more specific destination first, then a stable
     * hash of the rule id (probing upwards on collision).
     */
    private static Map<String, List<PlannedRule>> assignPriorities(Map<String, List<PlannedRule>> rules) {
        Map<String, List<PlannedRule>> out = new HashMap<>();
        rules.forEach((sw, list) -> {
            List<PlannedRule> sorted = new ArrayList<>(list);
            sorted.sort(Comparator.comparing(p -> p.rule().ruleId()));
            Set<Integer> used = new HashSet<>();
            List<PlannedRule> done = new ArrayList<>();
            for (PlannedRule p : sorted) {
                String dst = p.rule().match().dstIp();
                int specificity = dst == null ? 0 : Ip.prefixLength(dst);
                int priority = BASE.get(p.kind()) + specificity * 200 + Math.floorMod(p.rule().ruleId().hashCode(), 200);
                while (!used.add(priority)) {
                    priority++;
                }
                FlowRule r = p.rule();
                done.add(new PlannedRule(sw, new FlowRule(r.ruleId(), priority, r.match(), r.action(), r.rateLimit(),
                        r.intentId()), p.kind(), p.order()));
            }
            done.sort(Comparator.comparingInt((PlannedRule p) -> p.rule().priority()).reversed());
            out.put(sw, done);
        });
        return out;
    }
}
