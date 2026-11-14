package com.orion.common.flow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orion.common.net.Ip;
import com.orion.common.packet.Packet;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class MatchTest {

    private static final ZoneId Z = ZoneId.of("Asia/Kolkata");
    private static final ZonedDateTime NOON = ZonedDateTime.of(2026, 11, 12, 12, 0, 0, 0, Z);

    private static Packet ssh() {
        return new Packet("p1", "10.0.1.5", 51000, "10.0.3.7", 22, "ETHERNET", "IPV4", "TCP", "SSH", null, 512, 64, 0);
    }

    @Test
    void emptyMatchMatchesEverything() {
        assertTrue(Match.builder().build().matches(ssh(), 4, NOON));
    }

    @Test
    void ipFieldsAcceptSubnetsAndSingleIps() {
        assertTrue(Match.builder().srcIp("10.0.1.0/24").dstIp("10.0.3.7").build().matches(ssh(), 4, NOON));
        assertFalse(Match.builder().srcIp("10.0.2.0/24").build().matches(ssh(), 4, NOON));
    }

    @Test
    void portsProtocolsSizeAndInPort() {
        assertTrue(Match.builder().dstPort(22).l4Protocol("tcp").appProtocol("SSH").build().matches(ssh(), 4, NOON));
        assertFalse(Match.builder().dstPort(80).build().matches(ssh(), 4, NOON));
        assertFalse(Match.builder().l4Protocol("UDP").build().matches(ssh(), 4, NOON));
        assertFalse(Match.builder().maxSize(100).build().matches(ssh(), 4, NOON));
        assertTrue(Match.builder().minSize(100).maxSize(1000).build().matches(ssh(), 4, NOON));
        assertFalse(Match.builder().inPort(1).build().matches(ssh(), 4, NOON));
    }

    @Test
    void arrivalTimeIsComparedWithTheWindow() {
        Match office = Match.builder().timeWindow(new TimeWindow("09:00", "18:00", null)).build();
        assertTrue(office.matches(ssh(), 4, NOON));
        assertFalse(office.matches(ssh(), 4, NOON.withHour(20)));
        Match night = Match.builder().timeWindow(new TimeWindow("22:00", "06:00", null)).build();
        assertTrue(night.matches(ssh(), 4, NOON.withHour(23)));
        assertFalse(night.matches(ssh(), 4, NOON));
        Match ended = Match.builder().timeWindow(new TimeWindow(null, null, "2026-11-01T00:00")).build();
        assertFalse(ended.matches(ssh(), 4, NOON));
    }

    @Test
    void ipHelpers() {
        assertTrue(Ip.contains("10.0.0.0/16", "10.0.3.7"));
        assertTrue(Ip.covers("10.0.0.0/16", "10.0.3.0/24"));
        assertFalse(Ip.covers("10.0.3.0/24", "10.0.0.0/16"));
        assertTrue(Ip.overlaps("10.0.3.0/24", "10.0.0.0/16"));
        assertFalse(Ip.overlaps("10.0.1.0/24", "10.0.3.0/24"));
        assertTrue(Ip.contains("0.0.0.0/0", "8.8.8.8"));
    }
}
