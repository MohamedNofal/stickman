package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Trim: ask every process that isn't in front to give back the memory it doesn't need (onTrimMemory COMPLETE) without
 * closing anything. Measured on the stick (2026-11-06): Wolf 63 -> 41 MB, Live TV 104 -> 73, keyboard 40 -> 25,
 * Amazon home 115 -> 107, tcomm 53 -> 48, vizzini 47 -> 44; ~60 MB back with every app still open.
 * Pure: the daemon runs {@link #cmd} for each of {@link #targets}.
 */
public final class Trim {
    private Trim() {}

    /** Never trimmed: the system itself and Stickman's own processes (the proxy must not drop its buffers). */
    static final Set<String> SKIP = new HashSet<>(Arrays.asList("system", "android", "com.clean.stickman", "com.clean.netfilter"));

    /** Process names (pkg or pkg:proc) to trim: everything but the app in front, top/foreground and system processes. */
    public static List<String> targets(List<LruParser.Proc> procs, String fgPkg) {
        Set<String> out = new LinkedHashSet<>();
        for (LruParser.Proc p : procs) {
            if (p.pkg.equals(fgPkg) || SKIP.contains(p.pkg)) continue;
            if ("TOP".equals(p.state) || p.adj.startsWith("fg") || p.adj.startsWith("sys")) continue;
            if (!p.process.matches("[A-Za-z0-9_.]+(:[A-Za-z0-9_.]+)?")) continue;
            out.add(p.process);
        }
        return new ArrayList<>(out);
    }

    /** Delegates to core.cmd.StatusCmds.trimMemory (daemon/AutoKill). */
    public static String cmd(String process) { return com.clean.stickman.core.cmd.StatusCmds.trimMemory(process); }

    /** {freed MB, trimmed count} from a "STATUS trim freed_mb=N trimmed=M" line, or null. */
    public static long[] parseStatus(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("freed_mb=(-?\\d+)\\s+trimmed=(\\d+)").matcher(s == null ? "" : s);
        return m.find() ? new long[] { Long.parseLong(m.group(1)), Long.parseLong(m.group(2)) } : null;
    }
}
