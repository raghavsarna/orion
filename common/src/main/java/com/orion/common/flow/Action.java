package com.orion.common.flow;

/** What to do with a matching packet: FORWARD out of a port, or DROP. */
public record Action(String type, Integer port) {

    public static final String FORWARD = "FORWARD";
    public static final String DROP = "DROP";

    public static Action forward(int port) {
        return new Action(FORWARD, port);
    }

    public static Action drop() {
        return new Action(DROP, null);
    }

    public boolean isForward() {
        return FORWARD.equalsIgnoreCase(type);
    }

    public boolean isDrop() {
        return DROP.equalsIgnoreCase(type);
    }
}
