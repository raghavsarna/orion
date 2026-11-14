package com.orion.common.southbound;

import java.util.List;

/**
 * POST controller/register body, and the reply of GET switch/details.
 * {@code switchAddress} is the base URL the controller uses to call this switch back.
 * {@code bootId} changes every time the switch starts (empty flow table).
 */
public record SwitchRegistration(String switchId, String switchName, String switchAddress, String bootId,
                                 int switchTotalPorts, List<PortInfo> ports) {

    public SwitchRegistration {
        ports = ports == null ? List.of() : List.copyOf(ports);
    }
}
