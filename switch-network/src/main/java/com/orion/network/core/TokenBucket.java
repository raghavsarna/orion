package com.orion.network.core;

/** Token bucket: refills {@code ratePerSecond} tokens per second, holds at most that many. One packet = one token. */
public final class TokenBucket {

    private final double ratePerSecond;
    private double tokens;
    private long lastRefill;

    public TokenBucket(int ratePerSecond) {
        this.ratePerSecond = Math.max(1, ratePerSecond);
        this.tokens = this.ratePerSecond;
        this.lastRefill = System.nanoTime();
    }

    public synchronized boolean tryTake() {
        long now = System.nanoTime();
        tokens = Math.min(ratePerSecond, tokens + (now - lastRefill) / 1e9 * ratePerSecond);
        lastRefill = now;
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }
}
