package com.orion.shared.flow;

import com.orion.shared.packet.Packet;
import java.util.List;
import java.util.Objects;

/**
 * What to do with a matching packet: FORWARD out of a port, or DROP.
 *
 * <p>A FORWARD action may list several equal-cost {@code ports} (ECMP, like the Routing Engine's multipath
 * forwarding in the Orion paper). The switch then picks one port per flow by hashing the packet's 5-tuple, so
 * one flow always takes one path while different flows spread over all of them. {@code port} is the first of
 * {@code ports}, so code that only knows single-port forwarding keeps working.
 */
public record Action(String type, Integer port, List<Integer> ports) {

    public static final String FORWARD = "FORWARD";
    public static final String DROP = "DROP";

    public Action {
        ports = ports == null || ports.size() < 2 ? null : List.copyOf(ports);
    }

    public static Action forward(int port) {
        return new Action(FORWARD, port, null);
    }

    /** Forward over several equal-cost ports (one port behaves exactly like {@link #forward(int)}). */
    public static Action multipath(List<Integer> ports) {
        List<Integer> sorted = ports.stream().distinct().sorted().toList();
        return new Action(FORWARD, sorted.get(0), sorted);
    }

    public static Action drop() {
        return new Action(DROP, null, null);
    }

    public boolean isForward() {
        return FORWARD.equalsIgnoreCase(type);
    }

    public boolean isDrop() {
        return DROP.equalsIgnoreCase(type);
    }

    public boolean isMultipath() {
        return ports != null;
    }

    /** Every port this action may send on. */
    public List<Integer> allPorts() {
        if (ports != null) {
            return ports;
        }
        return port == null ? List.of() : List.of(port);
    }

    /** The output port for this packet: the single port, or a hash of the flow's 5-tuple over the ECMP ports. */
    public int portFor(Packet p) {
        if (ports == null) {
            return port;
        }
        int h = Objects.hash(p.srcIp(), p.dstIp(), p.srcPort(), p.dstPort(), p.l4Protocol());
        h ^= (h >>> 16);
        return ports.get(Math.floorMod(h * 0x9E3779B1, ports.size()));
    }
}
