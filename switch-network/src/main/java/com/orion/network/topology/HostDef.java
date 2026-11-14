package com.orion.network.topology;

/** A host subnet attached to one port of a switch, e.g. port 4 -> 10.0.1.0/24. */
public record HostDef(int port, String subnet) {
}
