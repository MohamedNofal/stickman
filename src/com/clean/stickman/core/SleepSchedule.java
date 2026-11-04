package com.clean.stickman.core;

/**
 * Sleep-timer state machine (pure, thread-safe). Every set()/dismiss()/end() bumps a generation; scheduled fade/end
 * tasks carry the generation they were scheduled with and no-op when it has changed, so a task already running
 * (blocked on the lock) when the timer is re-set can never act on the new timer. Callers run shell commands
 * after these methods return, never under the lock.
 */
public final class SleepSchedule {
    /** What the caller must do after set(). */
    public static final class Plan {
        public final int gen;
        /** An overlay was fading: send FADE_END. */
        public final boolean fadeEnd;
        /** Delay to the fade / end, or -1 when the timer is off. */
        public final long fadeInMs, endInMs;
        /** For "STATUS sleep_timer <status>": minutes or "off". */
        public final String status;
        final long endAt;

        Plan(int gen, boolean fadeEnd, long fadeInMs, long endInMs, String status, long endAt) {
            this.gen = gen; this.fadeEnd = fadeEnd; this.fadeInMs = fadeInMs; this.endInMs = endInMs;
            this.status = status; this.endAt = endAt;
        }
    }

    private int gen, minutes;
    private long endAt;
    private boolean fading;

    public synchronized Plan set(int min, long now) {
        gen++;
        boolean wasFading = fading;
        fading = false;
        minutes = min;
        if (min <= 0) {
            minutes = 0;
            endAt = 0;
            return new Plan(gen, wasFading, -1, -1, "off", 0);
        }
        endAt = now + min * 60_000L;
        long fadeIn = Math.max(0, SleepTimer.fadeStartAt(endAt) - now);
        return new Plan(gen, wasFading, fadeIn, endAt - now, Integer.toString(min), endAt);
    }

    /** Fade seconds to broadcast, or -1 when this task is stale. Marks the timer as fading. */
    public synchronized int startFade(int taskGen, long now) {
        if (taskGen != gen || minutes == 0) return -1;
        fading = true;
        long left = (endAt - now + 999) / 1000;
        return (int) Math.max(1, Math.min(SleepTimer.FADE_MS / 1000, left));
    }

    /** True when the end task is current: the timer is now off and the caller runs pause/sleep/FADE_END. */
    public synchronized boolean end(int taskGen) {
        if (taskGen != gen || minutes == 0) return false;
        gen++;
        minutes = 0;
        endAt = 0;
        fading = false;
        return true;
    }

    /** Key press during the fade: cancels the timer. True when the caller should send FADE_END + log off. */
    public synchronized boolean dismiss() {
        if (!fading) return false;
        gen++;
        minutes = 0;
        endAt = 0;
        fading = false;
        return true;
    }

    public synchronized int minutes() { return minutes; }
    public synchronized boolean fading() { return fading; }
    public synchronized int gen() { return gen; }

    /** Absolute fade time of a plan (test helper). */
    public static long fadeAt(Plan p) { return SleepTimer.fadeStartAt(p.endAt); }
}
