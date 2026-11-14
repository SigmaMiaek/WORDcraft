package pl.wordcraft.core;

import java.util.concurrent.TimeUnit;

public final class ActivePlayScheduler {
    private long lastNanos;
    private long elapsedNanos;
    private boolean initialized;
    private boolean previouslyActive;

    public boolean tick(long nowNanos, boolean activeWorld, boolean safeToAsk, WordcraftConfig config) {
        config.normalize();
        boolean active = activeWorld && config.enabled;
        if (!this.initialized) {
            this.initialized = true;
            this.lastNanos = nowNanos;
        }

        long delta = Math.max(0L, nowNanos - this.lastNanos);
        this.lastNanos = nowNanos;
        if (active && this.previouslyActive) {
            this.elapsedNanos = saturatingAdd(this.elapsedNanos, delta);
        }

        this.previouslyActive = active;
        long interval = TimeUnit.MINUTES.toNanos(config.intervalMinutes);
        if (active && safeToAsk && this.elapsedNanos >= interval) {
            this.elapsedNanos = 0L;
            return true;
        } else {
            return false;
        }
    }

    public void reset(long nowNanos) {
        this.lastNanos = nowNanos;
        this.elapsedNanos = 0L;
        this.initialized = true;
        this.previouslyActive = false;
    }

    public long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(this.elapsedNanos);
    }

    private static long saturatingAdd(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }
}
