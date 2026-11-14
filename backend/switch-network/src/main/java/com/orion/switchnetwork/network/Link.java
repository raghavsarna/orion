package com.orion.switchnetwork.network;


/** A wire between two switch ports. Can be set down/up at runtime. */
public final class Link {

    private final String id;
    private final PortRef a;
    private final PortRef b;
    private final int latencyMs;
    private volatile boolean up = true;

    public Link(String id, PortRef a, PortRef b, int latencyMs) {
        this.id = id;
        this.a = a;
        this.b = b;
        this.latencyMs = latencyMs;
    }

    public String id() {
        return id;
    }

    public PortRef a() {
        return a;
    }

    public PortRef b() {
        return b;
    }

    public int latencyMs() {
        return latencyMs;
    }

    public boolean isUp() {
        return up;
    }

    void setUp(boolean up) {
        this.up = up;
    }

    /** The end of this link on the given switch. */
    public PortRef endOn(String switchId) {
        return a.switchId().equals(switchId) ? a : b;
    }

    /** The far end, seen from the given switch. */
    public PortRef peerOf(String switchId) {
        return a.switchId().equals(switchId) ? b : a;
    }
}
