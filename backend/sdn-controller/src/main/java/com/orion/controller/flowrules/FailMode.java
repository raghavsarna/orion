package com.orion.controller.flowrules;

import com.orion.controller.topology.NibSnapshot;
import com.orion.controller.topology.SwitchStatus;
import com.orion.controller.topology.SwitchView;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * How the controller reacts to switches whose state is unknown (Orion paper, §3.2 "React optimistically to
 * correlated unreachability").
 *
 * <p>Ternary health, as in the paper:
 * <ul>
 *   <li><b>healthy</b>: the switch answers probes (status ALIVE);</li>
 *   <li><b>unknown</b>: the switch stopped answering, but its neighbours still see its links up, so it is
 *       probably still forwarding (status DEAD, kept in the NIB);</li>
 *   <li><b>unhealthy</b>: its neighbours report its links down; the Topology Manager removes it.</li>
 * </ul>
 *
 * <p>The controller compares two routing graphs: one that routes around every unknown switch and one that keeps
 * them. If routing around them would lose more than {@code threshold} of the link capacity, or cut any host subnet
 * off, it is better not to react: <b>Fail Static</b> (keep routing through them on their existing rules).
 * Otherwise it reacts conservatively: <b>Fail Closed</b> (route around them).
 *
 * @param mode           NORMAL (nothing unknown), FAIL_CLOSED or FAIL_STATIC
 * @param unknown        switches in the unknown state
 * @param totalLinks     links among healthy + unknown switches
 * @param linksLost      links that routing around the unknown switches would give up
 * @param degradation    linksLost / totalLinks
 * @param threshold      the capacity degradation threshold in force
 * @param disconnects    host subnet that routing around them would cut off (null if none)
 */
public record FailMode(String mode, List<String> unknown, int totalLinks, int linksLost, double degradation,
                       double threshold, String disconnects) {

    public static final String NORMAL = "NORMAL";
    public static final String FAIL_CLOSED = "FAIL_CLOSED";
    public static final String FAIL_STATIC = "FAIL_STATIC";
    public static final double DEFAULT_THRESHOLD = 0.4;

    /** The decision plus the graph to route on. */
    record Decision(FailMode mode, PathGraph graph) {
    }

    public static FailMode normal(double threshold) {
        return new FailMode(NORMAL, List.of(), 0, 0, 0, threshold, null);
    }

    public boolean isStatic() {
        return FAIL_STATIC.equals(mode);
    }

    /** One line for the activity log and the UI. */
    public String explain() {
        if (NORMAL.equals(mode)) {
            return "all switches healthy";
        }
        String who = String.join(", ", unknown) + (unknown.size() == 1 ? " is" : " are") + " in unknown state";
        String cost = Math.round(degradation * 100) + "% of link capacity (threshold " + Math.round(threshold * 100) + "%)";
        if (FAIL_CLOSED.equals(mode)) {
            return who + "; routing around costs " + cost + ", so the controller routes around";
        }
        if (disconnects != null) {
            return who + "; routing around would cut off " + disconnects + ", so the controller keeps using them";
        }
        return who + "; routing around would cost " + cost + ", so the controller keeps using them";
    }

    static Decision decide(NibSnapshot snap, double threshold) {
        Set<String> unknown = new TreeSet<>();
        for (SwitchView s : snap.switches().values()) {
            if (s.status() == SwitchStatus.DEAD) {
                unknown.add(s.id());
            }
        }
        PathGraph around = PathGraph.of(snap);
        if (unknown.isEmpty()) {
            return new Decision(normal(threshold), around);
        }
        PathGraph through = PathGraph.of(snap, unknown);
        int total = through.linkCount();
        int lost = total - around.linkCount();
        double degradation = total == 0 ? 0 : (double) lost / total;
        List<NibSnapshot.HostSubnet> hosts = snap.hosts().stream().filter(h -> through.hasNode(h.switchId())).toList();
        String cut = disconnected(through, around, hosts);
        boolean stay = cut != null || degradation > threshold;
        FailMode mode = new FailMode(stay ? FAIL_STATIC : FAIL_CLOSED, List.copyOf(unknown), total, lost,
                degradation, threshold, cut);
        return new Decision(mode, stay ? through : around);
    }

    /** First host subnet reachable in {@code before} but not in {@code after}. */
    private static String disconnected(PathGraph before, PathGraph after, List<NibSnapshot.HostSubnet> hosts) {
        for (NibSnapshot.HostSubnet a : hosts) {
            if (!after.hasNode(a.switchId()) && hosts.size() > 1) {
                return a.subnet();   // its own edge switch is the one in unknown state
            }
            Set<String> was = before.reachableFrom(a.switchId());
            Set<String> now = after.reachableFrom(a.switchId());
            for (NibSnapshot.HostSubnet b : hosts) {
                if (!a.subnet().equals(b.subnet()) && was.contains(b.switchId()) && !now.contains(b.switchId())) {
                    return b.subnet();
                }
            }
        }
        return null;
    }
}
