package com.clean.stickman.core;

/**
 * Start-at-boot retry policy (pure). The first ensure after a boot often fails: the stick is busy, adbd or the
 * network stack is not up yet, the script runs out of time. The periodic boot job only comes round every 15-30
 * minutes, which to the user is "Stickman does not start with the stick"; so a failed start is retried soon,
 * backing off, until the daemons are up or the tries run out (the periodic job stays as the last resort).
 */
public final class BootRetry {
    private BootRetry() {}

    public static final int MAX_TRIES = 12;
    private static final long[] DELAYS_MS = { 20_000, 40_000, 90_000, 180_000, 300_000 };

    /** Delay before retry number attempt (0-based), or -1 when the quick retries are used up. */
    public static long delayMs(int attempt) {
        if (attempt < 0 || attempt >= MAX_TRIES) return -1;
        return DELAYS_MS[Math.min(attempt, DELAYS_MS.length - 1)];
    }
}
