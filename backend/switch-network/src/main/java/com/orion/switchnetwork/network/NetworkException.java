package com.orion.switchnetwork.network;

/** A request to the switch network that cannot be done. {@code status} is the HTTP status to return. */
public class NetworkException extends RuntimeException {

    private final int status;

    public NetworkException(int status, String message) {
        super(message);
        this.status = status;
    }

    public static NetworkException notFound(String message) {
        return new NetworkException(404, message);
    }

    public static NetworkException badRequest(String message) {
        return new NetworkException(400, message);
    }

    public static NetworkException unavailable(String message) {
        return new NetworkException(503, message);
    }

    public int status() {
        return status;
    }
}
