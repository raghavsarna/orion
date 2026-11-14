package com.orion.common.southbound;

/**
 * One switch port as reported to the controller. A link port has linkId/peer fields,
 * a host port has hostSubnet, a free port has neither.
 */
public record PortInfo(int port, String linkId, String peerSwitch, Integer peerPort, Integer latencyMs,
                       String hostSubnet, boolean up) {

    public boolean isLink() {
        return linkId != null;
    }

    public boolean isHost() {
        return hostSubnet != null;
    }
}
