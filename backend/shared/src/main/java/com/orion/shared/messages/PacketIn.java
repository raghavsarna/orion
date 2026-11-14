package com.orion.shared.messages;

import com.orion.shared.packet.Packet;

/** POST controller/packet-in: a packet that matched no flow rule. */
public record PacketIn(String switchId, int inPort, Packet packet) {
}
