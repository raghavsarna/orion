package com.orion.shared.messages;

/** Probe in both directions: switch -> controller (POST controller/probe) and controller -> switch (GET reply). */
public record SwitchProbe(String switchId, String bootId) {
}
