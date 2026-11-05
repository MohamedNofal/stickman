package com.clean.stickman.core;

import java.util.List;

/** One complete sample of the stick's memory/CPU and its apps, as published on the status feed. */
public final class TaskSnapshot {
    public final long seq, totalKb, availKb;
    public final int cpuPct;
    /** Sorted by rssKb descending, ties by pkg. */
    public final List<App> apps;

    public TaskSnapshot(long seq, long totalKb, long availKb, int cpuPct, List<App> apps) {
        this.seq = seq; this.totalKb = totalKb; this.availKb = availKb; this.cpuPct = cpuPct; this.apps = apps;
    }

    public static final class App {
        /** rssKb descending, ties by pkg. */
        public static final java.util.Comparator<App> BY_RSS = new java.util.Comparator<App>() {
            public int compare(App a, App b) {
                if (a.rssKb != b.rssKb) return a.rssKb > b.rssKb ? -1 : 1;
                return a.pkg.compareTo(b.pkg);
            }
        };
        public final String pkg;
        public final long rssKb;
        public final int cpuPct;
        /** fg, play, bg, cached or sys. */
        public final String state;
        public final long upSec;
        public final int procs;

        public App(String pkg, long rssKb, int cpuPct, String state, long upSec, int procs) {
            this.pkg = pkg; this.rssKb = rssKb; this.cpuPct = cpuPct; this.state = state; this.upSec = upSec; this.procs = procs;
        }
    }
}
