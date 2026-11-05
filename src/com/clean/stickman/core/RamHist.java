package com.clean.stickman.core;

/**
 * The last {@link #SIZE} RSS samples (kB) of one package for the Tasks dialog's RAM trend, each with its sample's seq
 * and log time, so the "over the last N s" span is measured, not assumed. The history restarts (is cleared) when the
 * seq does not move forward (a new sampling session) or more than {@link #GAP_MS} passed since the previous sample
 * (pause, hidden page, stale feed): a trend never spans a gap. Index 0 = oldest.
 */
public final class RamHist {
    public static final int SIZE = 30;
    /** A larger gap between two samples (normally 2 s apart) restarts the history. Same as the page's stale limit. */
    public static final long GAP_MS = 6000;
    /** Nominal sample period, used for the span only when log times are unknown. */
    static final long PERIOD_MS = 2000;

    private final long[] v = new long[SIZE], at = new long[SIZE];
    private int n, head;   // head = index of the oldest sample
    private long lastSeq = -1, lastAt;

    public void add(long seq, long atMs, long kb) {
        if (n > 0 && (seq <= lastSeq || (atMs > 0 && lastAt > 0 && (atMs - lastAt > GAP_MS || atMs < lastAt)))) clear();
        int i;
        if (n < SIZE) i = (head + n++) % SIZE;
        else { i = head; head = (head + 1) % SIZE; }
        v[i] = kb;
        at[i] = atMs;
        lastSeq = seq;
        lastAt = atMs;
    }

    public void clear() { n = 0; head = 0; lastSeq = -1; lastAt = 0; }

    public int size() { return n; }

    public long get(int i) { return v[(head + i) % SIZE]; }

    /** Log time (epoch ms) of sample i, 0 = unknown. */
    public long at(int i) { return at[(head + i) % SIZE]; }

    public long min() { long m = Long.MAX_VALUE; for (int i = 0; i < n; i++) m = Math.min(m, get(i)); return m; }

    public long max() { long m = 0; for (int i = 0; i < n; i++) m = Math.max(m, get(i)); return m; }

    /** Milliseconds sample i was taken before the newest one (real log times; nominal 2 s steps if unknown). */
    public long agoMs(int i) {
        if (n == 0) return 0;
        long a = at(i), b = at(n - 1);
        return a > 0 && b > 0 ? Math.max(0, b - a) : (n - 1 - i) * PERIOD_MS;
    }

    /** Seconds from the oldest to the newest sample, rounded. */
    public long spanSec() { return n < 2 ? 0 : Math.round(agoMs(0) / 1000.0); }
}
