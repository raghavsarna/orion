package com.orion.shared.packet;

/**
 * A simulated packet. Protocol names are free text per layer, e.g.
 * l2 ETHERNET, l3 IPV4, l4 TCP/UDP/ICMP, app HTTP/HTTPS/SSH/DNS. {@code host} is the website name (optional).
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
        long sentAt) {

    public Packet withTtl(int newTtl) {
        return new Packet(id, srcIp, srcPort, dstIp, dstPort, l2Protocol, l3Protocol, l4Protocol, appProtocol,
                host, sizeBytes, newTtl, sentAt);
    }

    public Packet withIdentity(String newId, long newSentAt) {
        return new Packet(newId, srcIp, srcPort, dstIp, dstPort, l2Protocol, l3Protocol, l4Protocol, appProtocol,
                host, sizeBytes, ttl, newSentAt);
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
