package com.orion.controller.flowrules;

import com.orion.controller.topology.LinkInfo;
import com.orion.controller.topology.NibSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The part of the topology used for routing: ALIVE switches and the links between them.
 * Provides Dijkstra in two flavours: fewest hops (baseline routing) and lowest latency (latency intents).
 */
public final class PathGraph {

    /** How to weigh a link. */
    public enum Weight {
        /** Fewest hops; latency breaks ties ("least effort" baseline routing). */
        HOPS,
        /** Lowest total latency. */
        LATENCY
    }

    /** The best way from a switch towards a target: leave on outPort; hops and total latency to the target. */
    public record Route(int outPort, int hops, int latencyMs) {
    }

    private final Map<String, List<LinkInfo>> adjacency;

    private PathGraph(Map<String, List<LinkInfo>> adjacency) {
        this.adjacency = adjacency;
    }

    public static PathGraph of(NibSnapshot snap) {
        Map<String, List<LinkInfo>> adj = new LinkedHashMap<>();
        for (String node : snap.graph().nodes()) {
            if (snap.isAlive(node)) {
                adj.put(node, new ArrayList<>());
            }
        }
        for (String node : adj.keySet()) {
            for (LinkInfo l : snap.graph().neighbours(node)) {
                if (adj.containsKey(l.toSwitch())) {
                    adj.get(node).add(l);
                }
            }
        }
        return new PathGraph(adj);
    }

    public boolean hasNode(String id) {
        return adjacency.containsKey(id);
    }

    public boolean hasLink(String linkId) {
        return adjacency.values().stream().flatMap(List::stream).anyMatch(l -> l.linkId().equals(linkId));
    }

    /** A copy without the given link. */
    public PathGraph without(String linkId) {
        Map<String, List<LinkInfo>> adj = new LinkedHashMap<>();
        adjacency.forEach((n, links) -> adj.put(n, links.stream().filter(l -> !l.linkId().equals(linkId)).toList()));
        return new PathGraph(adj);
    }

    private static long cost(LinkInfo l, Weight w) {
        return w == Weight.HOPS ? 1_000_000L + l.latencyMs() : l.latencyMs();
    }

    /** For every switch that can reach {@code target}: the port to leave on, and hops/latency to get there. */
    public Map<String, Route> routesTo(String target, Weight weight) {
        Map<String, Route> routes = new HashMap<>();
        if (!adjacency.containsKey(target)) {
            return routes;
        }
        Map<String, Long> dist = new HashMap<>();
        PriorityQueue<Object[]> queue = new PriorityQueue<>((a, b) -> {
            int c = Long.compare((long) a[1], (long) b[1]);
            return c != 0 ? c : ((String) a[0]).compareTo((String) b[0]);
        });
        dist.put(target, 0L);
        routes.put(target, new Route(0, 0, 0));
        queue.add(new Object[] {target, 0L});
        while (!queue.isEmpty()) {
            Object[] top = queue.poll();
            String u = (String) top[0];
            long d = (long) top[1];
            if (d > dist.get(u)) {
                continue;
            }
            Route ru = routes.get(u);
            // links are symmetric: a link u -> v means v can reach u by leaving on l.toPort
            for (LinkInfo l : adjacency.get(u)) {
                String v = l.toSwitch();
                long nd = d + cost(l, weight);
                if (nd < dist.getOrDefault(v, Long.MAX_VALUE)) {
                    dist.put(v, nd);
                    routes.put(v, new Route(l.toPort(), ru.hops() + 1, ru.latencyMs() + l.latencyMs()));
                    queue.add(new Object[] {v, nd});
                }
            }
        }
        return routes;
    }

    /** Links along the best path from {@code from} to {@code to} (empty when equal, null when unreachable). */
    public List<LinkInfo> path(String from, String to, Weight weight) {
        if (!adjacency.containsKey(from) || !adjacency.containsKey(to)) {
            return null;
        }
        Map<String, Long> dist = new HashMap<>();
        Map<String, LinkInfo> via = new HashMap<>();
        PriorityQueue<Object[]> queue = new PriorityQueue<>((a, b) -> Long.compare((long) a[1], (long) b[1]));
        dist.put(from, 0L);
        queue.add(new Object[] {from, 0L});
        while (!queue.isEmpty()) {
            Object[] top = queue.poll();
            String u = (String) top[0];
            long d = (long) top[1];
            if (d > dist.get(u)) {
                continue;
            }
            if (u.equals(to)) {
                break;
            }
            for (LinkInfo l : adjacency.get(u)) {
                long nd = d + cost(l, weight);
                if (nd < dist.getOrDefault(l.toSwitch(), Long.MAX_VALUE)) {
                    dist.put(l.toSwitch(), nd);
                    via.put(l.toSwitch(), l);
                    queue.add(new Object[] {l.toSwitch(), nd});
                }
            }
        }
        if (!dist.containsKey(to)) {
            return null;
        }
        List<LinkInfo> path = new ArrayList<>();
        String cur = to;
        while (!cur.equals(from)) {
            LinkInfo l = via.get(cur);
            path.add(l);
            cur = l.fromSwitch();
        }
        Collections.reverse(path);
        return path;
    }

    /** Switches reachable from {@code start}. */
    public Set<String> reachableFrom(String start) {
        Set<String> seen = new HashSet<>();
        if (!adjacency.containsKey(start)) {
            return seen;
        }
        List<String> stack = new ArrayList<>(List.of(start));
        while (!stack.isEmpty()) {
            String u = stack.remove(stack.size() - 1);
            if (seen.add(u)) {
                adjacency.get(u).forEach(l -> stack.add(l.toSwitch()));
            }
        }
        return seen;
    }
}
