package dev.elontusk.gtnhvoice.core;

/** Token bucket with bounded bursts; use a monotonic nanosecond clock. */
public final class RateLimit {
    private double tokens;
    private long updated;
    private final double perSecond;
    private final int capacity;

    public RateLimit(double perSecond, int capacity, long now) {
        this.perSecond = perSecond; this.capacity = capacity; this.tokens = capacity; this.updated = now;
    }

    public boolean allow(long now) {
        tokens = Math.min(capacity, tokens + Math.max(0, now - updated) * perSecond / 1_000_000_000.0);
        updated = Math.max(updated, now);
        if (tokens < 1) return false;
        tokens--;
        return true;
    }
}
