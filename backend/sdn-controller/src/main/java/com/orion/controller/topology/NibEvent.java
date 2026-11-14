package com.orion.controller.topology;

/**
 * A change notification published by the NIB, always after the change is applied to the topology.
 * Types: SWITCH_ADDED, SWITCH_REBOOTED, SWITCH_DEAD, SWITCH_ALIVE, SWITCH_REMOVED, LINK_ADDED, LINK_REMOVED, PORT_CHANGED.
 */
public record NibEvent(long version, String type, String switchId, String linkId, String detail, long time) {

    static NibEvent of(String type, String switchId, String linkId, String detail) {
        return new NibEvent(0, type, switchId, linkId, detail, System.currentTimeMillis());
    }

    NibEvent withVersion(long v) {
        return new NibEvent(v, type, switchId, linkId, detail, time);
    }
}
