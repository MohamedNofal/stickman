package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Who runs in the background": per-package share of the last hours spent running, from
 * {@code dumpsys procstats --hours N} ("* pkg / uid / vN:" followed by "TOTAL: 73% (min-avg-max PSS ...)"). Pure.
 */
public final class BgTime {
    private BgTime() {}

    public static final int HOURS = 3;
    public static final String CMD = "dumpsys procstats --hours " + HOURS;
    /** Below this share an app isn't worth listing. */
    public static final int MIN_PCT = 5;

    public static final class Entry {
        public final String pkg;
        public final int pct;
        public final int avgMb;
        Entry(String pkg, int pct, int avgMb) { this.pkg = pkg; this.pct = pct; this.avgMb = avgMb; }
    }

    private static final Pattern HEAD = Pattern.compile("^\\s*\\*\\s+([A-Za-z0-9_.]+)(?::[A-Za-z0-9_.]+)?\\s*/\\s*(u0a\\d+)\\s*/");
    private static final Pattern TOTAL = Pattern.compile("^\\s*TOTAL:\\s*(\\d+(?:\\.\\d+)?)%(?:\\s*\\(([0-9.]+)(KB|MB|GB)-([0-9.]+)(KB|MB|GB)-)?");

    /**
     * User apps (uid u0a*) that ran at least {@link #MIN_PCT} of the time, highest first; a package with several
     * processes keeps its highest share and adds up the average PSS. Protected packages and Stickman's own are dropped.
     */
    public static List<Entry> parse(String out) {
        Map<String, int[]> by = new LinkedHashMap<>();   // pkg -> {pct, avgMb}
        String cur = null;
        if (out != null) for (String line : out.split("\n")) {
            Matcher h = HEAD.matcher(line);
            if (h.find()) { cur = h.group(1); continue; }
            if (cur == null) continue;
            Matcher t = TOTAL.matcher(line);
            if (!t.find()) continue;
            int pct = (int) Math.round(Double.parseDouble(t.group(1)));
            int mb = t.group(4) == null ? 0 : toMb(t.group(4), t.group(5));
            int[] e = by.get(cur);
            if (e == null) by.put(cur, new int[] { pct, mb });
            else { e[0] = Math.max(e[0], pct); e[1] += mb; }
            cur = null;   // only the first TOTAL after a header belongs to it
        }
        List<Entry> r = new ArrayList<>();
        for (Map.Entry<String, int[]> e : by.entrySet()) {
            String p = e.getKey();
            if (e.getValue()[0] < MIN_PCT || !starvable(p)) continue;
            r.add(new Entry(p, e.getValue()[0], e.getValue()[1]));
        }
        r.sort((x, y) -> y.pct != x.pct ? Integer.compare(y.pct, x.pct) : Integer.compare(y.avgMb, x.avgMb));
        return r;
    }

    /** Not Android's own, not protected, not a never-kill package (launchers, the proxy, Stickman, ...). */
    static boolean starvable(String p) {
        return !p.startsWith("com.android.") && !p.startsWith("android.") && !Catalog.isProtected(p) && !KillPolicy.isNeverKill(p);
    }

    static int toMb(String n, String unit) {
        double v = Double.parseDouble(n);
        if ("KB".equals(unit)) v /= 1024; else if ("GB".equals(unit)) v *= 1024;
        return (int) Math.round(v);
    }
}
