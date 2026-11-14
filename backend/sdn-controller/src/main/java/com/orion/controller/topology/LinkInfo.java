package com.orion.controller.topology;

/** A direct connection seen from {@code fromSwitch}: leaves on fromPort, arrives at toSwitch on toPort. */
public record LinkInfo(String linkId, String fromSwitch, int fromPort, String toSwitch, int toPort, int latencyMs) {

    public LinkInfo reversed() {
        return new LinkInfo(linkId, toSwitch, toPort, fromSwitch, fromPort, latencyMs);
    }
}
