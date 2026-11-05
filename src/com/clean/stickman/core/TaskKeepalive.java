package com.clean.stickman.core;

/**
 * When the Tasks page (re)sends tasks_start: every {@link #KEEPALIVE_MS} while live, and sooner - at most every
 * {@link #RETRY_MS} - while the feed is stale (stickd restarted by an ensure, or a keepalive was skipped), so the page
 * recovers within ~10 s instead of up to a minute. tasks_start is idempotent in stickd.
 */
public final class TaskKeepalive {
    private TaskKeepalive() {}

    public static final long KEEPALIVE_MS = 60000, RETRY_MS = 10000;

    /**
     * sinceStartMs = time since the last tasks_start (Long.MAX_VALUE = never); feedAgeMs = age of the latest completed
     * snapshot (Long.MAX_VALUE = none).
     */
    public static boolean shouldStart(boolean paused, long sinceStartMs, long feedAgeMs) {
        if (paused) return false;
        if (sinceStartMs >= KEEPALIVE_MS) return true;
        return feedAgeMs > RETRY_MS && sinceStartMs >= RETRY_MS;
    }
}
