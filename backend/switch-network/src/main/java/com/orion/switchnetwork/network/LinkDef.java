package com.orion.switchnetwork.network;

/** A wire between two switch ports. Ends are written as "switchId:port", e.g. "s1:2". */
public record LinkDef(String id, String a, String b, int latencyMs) {

    public PortRef endA() {
        return PortRef.parse(a);
    }

    public PortRef endB() {
        return PortRef.parse(b);
    }
}
