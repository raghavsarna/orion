package com.orion.controller.nib;

import com.orion.common.southbound.PortInfo;
import java.util.List;

/** Read-only copy of what the NIB knows about one switch. */
public record SwitchView(String id, String name, String address, String bootId, int totalPorts, SwitchStatus status,
                         int missedProbes, long lastSeen, long registeredAt, List<PortInfo> ports) {
}
