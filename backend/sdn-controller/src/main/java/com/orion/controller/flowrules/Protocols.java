package com.orion.controller.flowrules;

import com.orion.shared.flow.Match;
import java.util.Locale;
import java.util.Set;

/** Puts a protocol name on the right layer of a match: ETHERNET -> l2, IPV4 -> l3, TCP -> l4, SSH -> application. */
public final class Protocols {

    private static final Set<String> L2 = Set.of("ETHERNET", "VLAN");
    private static final Set<String> L3 = Set.of("IPV4", "IPV6", "ARP");
    private static final Set<String> L4 = Set.of("TCP", "UDP", "ICMP");

    private Protocols() {
    }

    public static void apply(Match.Builder m, String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return;
        }
        String p = protocol.trim().toUpperCase(Locale.ROOT);
        if (L2.contains(p)) {
            m.l2Protocol(p);
        } else if (L3.contains(p)) {
            m.l3Protocol(p);
        } else if (L4.contains(p)) {
            m.l4Protocol(p);
        } else {
            m.appProtocol(p);
        }
    }
}
