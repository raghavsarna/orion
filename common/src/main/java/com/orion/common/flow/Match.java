package com.orion.common.flow;

import com.orion.common.net.Ip;
import com.orion.common.packet.Packet;
import java.time.ZonedDateTime;

/**
 * The "match" half of a flow rule. A null field matches anything.
 * IP fields accept a single IP or a subnet. Protocol fields are compared case-insensitively.
 */
public record Match(
        String srcIp,
        String dstIp,
        Integer srcPort,
        Integer dstPort,
        String l2Protocol,
        String l3Protocol,
        String l4Protocol,
        String appProtocol,
        String host,
        Integer minSize,
        Integer maxSize,
        Integer inPort,
        TimeWindow timeWindow) {

    public static Builder builder() {
        return new Builder();
    }

    public boolean matches(Packet p, int arrivedOnPort, ZonedDateTime arrivalTime) {
        if (srcIp != null && !Ip.contains(srcIp, p.srcIp())) {
            return false;
        }
        if (dstIp != null && !Ip.contains(dstIp, p.dstIp())) {
            return false;
        }
        if (srcPort != null && !srcPort.equals(p.srcPort())) {
            return false;
        }
        if (dstPort != null && !dstPort.equals(p.dstPort())) {
            return false;
        }
        if (!same(l2Protocol, p.l2Protocol()) || !same(l3Protocol, p.l3Protocol())
                || !same(l4Protocol, p.l4Protocol()) || !same(appProtocol, p.appProtocol())
                || !same(host, p.host())) {
            return false;
        }
        if (minSize != null && p.sizeBytes() < minSize) {
            return false;
        }
        if (maxSize != null && p.sizeBytes() > maxSize) {
            return false;
        }
        if (inPort != null && inPort != arrivedOnPort) {
            return false;
        }
        return timeWindow == null || timeWindow.isActive(arrivalTime);
    }

    private static boolean same(String wanted, String actual) {
        return wanted == null || (actual != null && wanted.equalsIgnoreCase(actual));
    }

    /** Builder so callers set only the fields they need. */
    public static final class Builder {
        private String srcIp;
        private String dstIp;
        private Integer srcPort;
        private Integer dstPort;
        private String l2Protocol;
        private String l3Protocol;
        private String l4Protocol;
        private String appProtocol;
        private String host;
        private Integer minSize;
        private Integer maxSize;
        private Integer inPort;
        private TimeWindow timeWindow;

        public Builder srcIp(String v) { srcIp = v; return this; }
        public Builder dstIp(String v) { dstIp = v; return this; }
        public Builder srcPort(Integer v) { srcPort = v; return this; }
        public Builder dstPort(Integer v) { dstPort = v; return this; }
        public Builder l2Protocol(String v) { l2Protocol = v; return this; }
        public Builder l3Protocol(String v) { l3Protocol = v; return this; }
        public Builder l4Protocol(String v) { l4Protocol = v; return this; }
        public Builder appProtocol(String v) { appProtocol = v; return this; }
        public Builder host(String v) { host = v; return this; }
        public Builder minSize(Integer v) { minSize = v; return this; }
        public Builder maxSize(Integer v) { maxSize = v; return this; }
        public Builder inPort(Integer v) { inPort = v; return this; }
        public Builder timeWindow(TimeWindow v) { timeWindow = (v == null || v.isEmpty()) ? null : v; return this; }

        public Match build() {
            return new Match(srcIp, dstIp, srcPort, dstPort, l2Protocol, l3Protocol, l4Protocol, appProtocol,
                    host, minSize, maxSize, inPort, timeWindow);
        }
    }
}
