package com.orion.switchnetwork.network;

/** The topology file is missing, unreadable or describes an impossible network. */
public class TopologyException extends RuntimeException {

    public TopologyException(String message) {
        super(message);
    }

    public TopologyException(String message, Throwable cause) {
        super(message, cause);
    }
}
