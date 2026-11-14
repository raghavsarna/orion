package com.orion.common.net;

/** IPv4 helpers: plain IPs ("10.0.1.5") and subnets ("10.0.1.0/24"). */
public final class Ip {

    private Ip() {
    }

    /** True for "a.b.c.d" with each part 0..255. */
    public static boolean isIp(String text) {
        if (text == null) {
            return false;
        }
        try {
            toLong(text);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** True for "a.b.c.d/n" (n = 0..32). */
    public static boolean isCidr(String text) {
        if (text == null || !text.contains("/")) {
            return false;
        }
        String[] parts = text.trim().split("/");
        if (parts.length != 2 || !isIp(parts[0])) {
            return false;
        }
        try {
            int p = Integer.parseInt(parts[1]);
            return p >= 0 && p <= 32;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** True for a plain IP or a subnet. */
    public static boolean isIpOrCidr(String text) {
        return isIp(text) || isCidr(text);
    }

    public static long toLong(String ip) {
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException("not an IPv4 address: " + ip);
        }
        long value = 0;
        for (String part : parts) {
            int n;
            try {
                n = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not an IPv4 address: " + ip);
            }
            if (n < 0 || n > 255) {
                throw new IllegalArgumentException("not an IPv4 address: " + ip);
            }
            value = (value << 8) | n;
        }
        return value;
    }

    /** Prefix length: "10.0.1.0/24" -> 24, plain IP -> 32. */
    public static int prefixLength(String ipOrCidr) {
        int slash = ipOrCidr.indexOf('/');
        return slash < 0 ? 32 : Integer.parseInt(ipOrCidr.substring(slash + 1).trim());
    }

    private static long network(String ipOrCidr) {
        int slash = ipOrCidr.indexOf('/');
        String ip = slash < 0 ? ipOrCidr : ipOrCidr.substring(0, slash);
        return toLong(ip) & mask(prefixLength(ipOrCidr));
    }

    private static long mask(int prefix) {
        return prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
    }

    /** Does the subnet (or single IP) {@code range} contain the address {@code ip}? */
    public static boolean contains(String range, String ip) {
        if (range == null || ip == null || !isIp(ip) || !isIpOrCidr(range)) {
            return false;
        }
        int prefix = prefixLength(range);
        return (toLong(ip) & mask(prefix)) == network(range);
    }

    /** Is every address of {@code inner} inside {@code outer}? */
    public static boolean covers(String outer, String inner) {
        if (!isIpOrCidr(outer) || !isIpOrCidr(inner)) {
            return false;
        }
        int po = prefixLength(outer);
        int pi = prefixLength(inner);
        return pi >= po && (network(inner) & mask(po)) == network(outer);
    }

    /** Do two subnets share at least one address? */
    public static boolean overlaps(String a, String b) {
        return covers(a, b) || covers(b, a);
    }
}
