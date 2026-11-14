package com.orion.network.topology;

/** Where the switches find the controller and the token they send with every request. */
public record ControllerConfig(String url, String authToken) {
}
