package com.orion.controller.topology;

import java.util.List;
import java.util.Map;

/**
 * A consistent copy of the NIB at one version: switches, the adjacency-matrix graph and host subnets.
 */
public record NibSnapshot(long version, Map<String, SwitchView> switches, TopologyGraph graph, List<HostSubnet> hosts) {

    /** A host subnet attached to a switch port (where an IP "lives"). */
    public record HostSubnet(String subnet, String switchId, int port) {
    }

    public boolean isAlive(String switchId) {
        SwitchView s = switches.get(switchId);
        return s != null && s.status() == SwitchStatus.ALIVE;
    }
}
