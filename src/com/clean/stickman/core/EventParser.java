package com.clean.stickman.core;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses events-log lines for stickd's foreground tracking. */
public final class EventParser {
    private EventParser() {}

    private static final Pattern RESUMED =
        Pattern.compile("wm_set_resumed_activity: \\[\\d+,([A-Za-z0-9_.]+)/[^,\\]]*");

    /** Package from a {@code wm_set_resumed_activity: [0,<pkg>/<act>,<reason>]} line, or null. */
    public static String resumedPackage(String logLine) {
        if (logLine == null) return null;
        Matcher m = RESUMED.matcher(logLine);
        return m.find() ? m.group(1) : null;
    }

    private static final Pattern DUMPSYS =
        Pattern.compile("mResumedActivity: ActivityRecord\\{\\S+ u\\d+ ([A-Za-z0-9_.]+)/");

    /** Package from {@code dumpsys activity activities}' mResumedActivity line (seeds fg at start), or null. */
    public static String dumpsysResumed(String dump) {
        if (dump == null) return null;
        Matcher m = DUMPSYS.matcher(dump);
        return m.find() ? m.group(1) : null;
    }

    /** {@code pkg} if it has a profile, else {@code "default"}. */
    public static String selectProfile(String pkg, List<String> profiles) {
        if (pkg != null && profiles != null && profiles.contains(pkg)) return pkg;
        return RemapModel.DEFAULT;
    }
}
