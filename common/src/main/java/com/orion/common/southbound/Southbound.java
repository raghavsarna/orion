package com.orion.common.southbound;

/** Names shared by both sides of the southbound API. */
public final class Southbound {

    public static final String AUTH_HEADER = "Authorization";

    private Southbound() {
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }
}
