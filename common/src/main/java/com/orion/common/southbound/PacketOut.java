package com.orion.common.southbound;

import com.orion.common.packet.Packet;

/** POST switch/packet-out: send this packet out of a port. */
public record PacketOut(Packet packet, int outPort) {
}
