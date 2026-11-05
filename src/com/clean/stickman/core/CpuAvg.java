package com.clean.stickman.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-app CPU moving average over the last {@link #WINDOW} snapshots (10 s at the 2 s cadence) for Sort by CPU tile
 * sizes: single-sample CPU % on an idle stick hops between 0 and a few % every sample, which re-laid the map out
 * ~27 times a minute. Each snapshot counts once (by seq); a lower seq (new session) restarts every average.
 */
public final class CpuAvg {
    public static final int WINDOW = 5;

    private final Map<String, int[]> ring = new HashMap<>();
    private final Map<String, Integer> count = new HashMap<>();
    private long lastSeq = -1;

    public void add(TaskSnapshot s) {
        if (s == null || s.seq == lastSeq) return;
        if (s.seq < lastSeq) { ring.clear(); count.clear(); }
        lastSeq = s.seq;
        Map<String, int[]> keepR = new HashMap<>();
        Map<String, Integer> keepC = new HashMap<>();
        for (TaskSnapshot.App a : s.apps) {
            int[] r = ring.get(a.pkg);
            Integer n = count.get(a.pkg);
            if (r == null) { r = new int[WINDOW]; n = 0; }
            r[n % WINDOW] = a.cpuPct;
            keepR.put(a.pkg, r);
            keepC.put(a.pkg, n + 1);
        }
        ring.clear(); ring.putAll(keepR);   // apps gone from the snapshot are forgotten
        count.clear(); count.putAll(keepC);
    }

    /** Mean CPU % of pkg over its last (up to) WINDOW samples; fallback if never seen. */
    public double avg(String pkg, int fallback) {
        int[] r = ring.get(pkg);
        Integer n = count.get(pkg);
        if (r == null || n == null || n == 0) return fallback;
        int k = Math.min(n, WINDOW);
        long sum = 0;
        for (int i = 0; i < k; i++) sum += r[i];
        return sum / (double) k;
    }

    public void clear() { ring.clear(); count.clear(); lastSeq = -1; }
}
