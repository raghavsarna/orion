package com.orion.shared.packet;

/**
 * A simulated packet. Protocol names are free text per layer, e.g.
 * l2 ETHERNET, l3 IPV4, l4 TCP/UDP/ICMP, app HTTP/HTTPS/SSH/DNS. {@code host} is the website name (optional).
 * {@code pathMs} is the simulated travel time so far: the sum of the configured latencies of the links it crossed.
 * Unlike wall-clock time it does not depend on how precisely the host machine schedules threads.
 */
public record Packet(
        String id,
        String srcIp,
        Integer srcPort,
        String dstIp,
        Integer dstPort,
        String l2Protocol,
        String l3Protocol,
        String l4Protocol,
        String appProtocol,
        String host,
        int sizeBytes,
        int ttl,
        long sentAt,
        int pathMs) {

    public Packet(String id, String srcIp, Integer srcPort, String dstIp, Integer dstPort, String l2Protocol,
                  String l3Protocol, String l4Protocol, String appProtocol, String host, int sizeBytes, int ttl,
                  long sentAt) {
        this(id, srcIp, srcPort, dstIp, dstPort, l2Protocol, l3Protocol, l4Protocol, appProtocol, host, sizeBytes,
                ttl, sentAt, 0);
    }

    public Packet withTtl(int newTtl) {
        return new Packet(id, srcIp, srcPort, dstIp, dstPort, l2Protocol, l3Protocol, l4Protocol, appProtocol,
                host, sizeBytes, newTtl, sentAt, pathMs);
    }

    public Packet withIdentity(String newId, long newSentAt) {
        return new Packet(newId, srcIp, srcPort, dstIp, dstPort, l2Protocol, l3Protocol, l4Protocol, appProtocol,
                host, sizeBytes, ttl, newSentAt, pathMs);
    }

    public Packet withSrcPort(Integer newSrcPort) {
        return new Packet(id, srcIp, newSrcPort, dstIp, dstPort, l2Protocol, l3Protocol, l4Protocol, appProtocol,
                host, sizeBytes, ttl, sentAt, pathMs);
    }

    /** The packet after crossing a link with this latency. */
    public Packet crossed(int linkLatencyMs) {
        return new Packet(id, srcIp, srcPort, dstIp, dstPort, l2Protocol, l3Protocol, l4Protocol, appProtocol,
                host, sizeBytes, ttl - 1, sentAt, pathMs + linkLatencyMs);
    }

    /** Short one-line description used in logs. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(srcIp).append(srcPort == null ? "" : ":" + srcPort)
          .append(" -> ").append(dstIp).append(dstPort == null ? "" : ":" + dstPort);
        String protos = join(l4Protocol, appProtocol);
        if (!protos.isEmpty()) {
            sb.append(' ').append(protos);
        }
        if (host != null && !host.isBlank()) {
            sb.append(" host=").append(host);
        }
        sb.append(' ').append(sizeBytes).append('B');
        return sb.toString();
    }

    private static String join(String a, String b) {
        boolean ha = a != null && !a.isBlank();
        boolean hb = b != null && !b.isBlank();
        if (ha && hb) {
            return a + "/" + b;
        }
        return ha ? a : hb ? b : "";
    }
}
