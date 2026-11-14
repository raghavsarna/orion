package com.orion.controller;

/** An operation that cannot be done. {@code status} is the HTTP status the API returns. */
public class ControllerException extends RuntimeException {

    private final int status;

    public ControllerException(int status, String message) {
        super(message);
        this.status = status;
    }

    public ControllerException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public static ControllerException badRequest(String message) {
        return new ControllerException(400, message);
    }

    public static ControllerException notFound(String message) {
        return new ControllerException(404, message);
    }

    public static ControllerException unavailable(String message, Throwable cause) {
        return new ControllerException(503, message, cause);
    }

    public int status() {
        return status;
    }
}
