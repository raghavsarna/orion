package com.orion.network.topology;

import java.util.List;

/** One switch from the topology file. Ports are numbered 1..ports. */
public record SwitchDef(String id, String name, int ports, List<HostDef> hosts) {

    public SwitchDef {
        hosts = hosts == null ? List.of() : List.copyOf(hosts);
    }
}
