package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** /proc parsing, CPU maths, per-package aggregation and feed-line formatting. */
public final class ProcStats {
    private ProcStats() {}

    /** One process sample; rssKb &lt; 0 marks an unreadable (vanished) pid. */
    public static final class Proc {
        public int pid;
        public String pkg;
        public long rssKb;
        public long ticks;
        public long startTicks;
    }

    public static long rssKbFromStatm(String statm, int pageKb) {
        return Long.parseLong(statm.trim().split("\\s+")[1]) * pageKb;
    }

    /** {utime+stime, starttime} in clock ticks. */
    public static long[] cpuFromStat(String stat) {
        String rest = stat.substring(stat.lastIndexOf(')') + 2).trim();
        String[] f = rest.split("\\s+"); // f[0] is field 3 (state)
        return new long[] {Long.parseLong(f[11]) + Long.parseLong(f[12]), Long.parseLong(f[19])};
    }

    public static long totalJiffies(String line) {
        String[] f = line.trim().split("\\s+");
        long sum = 0;
        for (int i = 1; i < f.length; i++) sum += Long.parseLong(f[i]);
        return sum;
    }

    public static String pkgOf(String cmdline) {
        if (cmdline == null) return null;
        int end = cmdline.length();
        for (int i = 0; i < cmdline.length(); i++) {
            char c = cmdline.charAt(i);
            if (c == ':' || c == 0) { end = i; break; }
        }
        String p = cmdline.substring(0, end).trim();
        return p.matches("[A-Za-z0-9._]+") ? p : null;
    }

    public static int pct(long deltaTicks, long deltaTotalJiffies, int ncpu) {
        if (deltaTotalJiffies <= 0 || deltaTicks <= 0) return 0;
        long v = Math.round(100.0 * deltaTicks / deltaTotalJiffies);
        return (int) Math.max(0, Math.min(100, v));
    }

    public static List<TaskSnapshot.App> aggregate(List<Proc> procs, Map<String, Long> prevTicks, long deltaTotal, int ncpu,
            long nowTicks, long clkTck, String fgPkg, Set<String> playing, Set<String> cached, Set<String> launchable) {
        Map<String, long[]> by = new LinkedHashMap<>(); // rss, ticks, procs, minStart
        for (Proc p : procs) {
            if (p.rssKb < 0 || p.pkg == null) continue;
            long[] a = by.get(p.pkg);
            if (a == null) { a = new long[] {0, 0, 0, Long.MAX_VALUE}; by.put(p.pkg, a); }
            a[0] += p.rssKb; a[1] += p.ticks; a[2]++; a[3] = Math.min(a[3], p.startTicks);
        }
        List<TaskSnapshot.App> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : by.entrySet()) {
            String pkg = e.getKey();
            long[] a = e.getValue();
            Long prev = prevTicks == null ? null : prevTicks.get(pkg);
            int cpu = prev == null ? 0 : pct(a[1] - prev, deltaTotal, ncpu);
            String state;
            if (!launchable.contains(pkg)) state = "sys";
            else if (pkg.equals(fgPkg)) state = "fg";
            else if (playing.contains(pkg)) state = "play";
            else if (cached.contains(pkg)) state = "cached";
            else state = "bg";
            long up = Math.max(0, (nowTicks - a[3]) / clkTck);
            out.add(new TaskSnapshot.App(pkg, a[0], cpu, state, up, (int) a[2]));
        }
        Collections.sort(out, new Comparator<TaskSnapshot.App>() {
            public int compare(TaskSnapshot.App x, TaskSnapshot.App y) {
                if (x.rssKb != y.rssKb) return x.rssKb > y.rssKb ? -1 : 1;
                return x.pkg.compareTo(y.pkg);
            }
        });
        return out;
    }

    public static String formatTaskLine(TaskSnapshot.App a, long seq) {
        return "task " + a.pkg + " rss=" + a.rssKb + " cpu=" + a.cpuPct + " state=" + a.state
            + " up=" + a.upSec + " procs=" + a.procs + " seq=" + seq;
    }

    public static String formatTotals(long totalKb, long availKb, int cpuPct, long seq) {
        return "tasks total=" + totalKb + " avail=" + availKb + " cpu=" + cpuPct + " seq=" + seq;
    }

    public static String formatEnd(long seq) { return "tasks_end seq=" + seq; }
}
