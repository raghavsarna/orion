package com.orion.common.southbound;

import com.orion.common.packet.Packet;

/** POST controller/packet-in: a packet that matched no flow rule. */
public record PacketIn(String switchId, int inPort, Packet packet) {
}
