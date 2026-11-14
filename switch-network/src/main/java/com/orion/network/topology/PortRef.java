package com.orion.network.topology;

/** One port of one switch, written as "switchId:port". */
public record PortRef(String switchId, int port) {

    public static PortRef parse(String text) {
        if (text == null) {
            throw new TopologyException("link end is missing (expected \"switchId:port\")");
        }
        int colon = text.lastIndexOf(':');
        if (colon <= 0 || colon == text.length() - 1) {
            throw new TopologyException("bad link end \"" + text + "\" (expected \"switchId:port\")");
        }
        try {
            return new PortRef(text.substring(0, colon).trim(), Integer.parseInt(text.substring(colon + 1).trim()));
        } catch (NumberFormatException e) {
            throw new TopologyException("bad port number in link end \"" + text + "\"");
        }
    }

    @Override
    public String toString() {
        return switchId + ":" + port;
    }
}
