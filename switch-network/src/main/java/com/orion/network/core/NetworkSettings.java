package com.orion.network.core;

import java.time.ZoneId;

/**
 * Settings of the switch network.
 *
 * @param controllerUrl  where switches reach the controller
 * @param authToken      sent with every switch -> controller request
 * @param publicBaseUrl  how the controller reaches this container, e.g. http://switch-network:9191
 * @param heartbeatSec   how often a switch probes the controller
 * @param zone           time zone used to compare packet arrival time with rule time windows
 */
public record NetworkSettings(String controllerUrl, String authToken, String publicBaseUrl, int heartbeatSec,
                              ZoneId zone) {
}
