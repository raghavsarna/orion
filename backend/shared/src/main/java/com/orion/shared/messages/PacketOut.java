package com.orion.shared.messages;

import com.orion.shared.packet.Packet;

/** POST switch/packet-out: send this packet out of a port. */
public record PacketOut(Packet packet, int outPort) {
}
