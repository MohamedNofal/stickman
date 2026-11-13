package com.clean.stickman.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.clean.stickman.core.cmd.PrivacyCmds;

/**
 * "Quiet telemetry": Amazon's reporting apps that Fire OS refuses to disable or suspend still accept appops.
 * Verified on the stick (AFTMA08C15, Fire OS 8 / API 30, 2026-11-06): RUN_ANY_IN_BACKGROUND ignore sets and resets
 * cleanly on every package below, GET_USAGE_STATS ignore on the usage-stats proxy too. com.amazon.device.logmanager is
 * left out on purpose: it shares the system uid (1000) and appops are per uid; Deep sleep already drops it from the
 * doze whitelist. The native Minerva / telemetry-emitter services (children of neo-init) can't be touched from the
 * shell: only an ad-blocking DNS reaches those. Undo is Starve.wake (the appops are journaled like Keep asleep).
 * Commands: {@link PrivacyCmds}. Pure.
 */
public final class Telemetry {
    private Telemetry() {}

    /** What each of {@link PrivacyCmds#TELEMETRY_PKGS} reports, same order. */
    private static final String[] WHAT = { "device metrics", "app metrics", "crash reports", "app-usage statistics",
        "ad links", "remote support", "remote config and metrics", "remote diagnostics" };

    /** {package, what it reports}, in display order. */
    public static final String[][] APPS = new String[WHAT.length][];
    static {
        for (int i = 0; i < WHAT.length; i++) APPS[i] = new String[] { PrivacyCmds.TELEMETRY_PKGS[i], WHAT[i] };
    }
    public static final String USAGE_PROXY = PrivacyCmds.USAGE_PROXY;
    public static final String OP = PrivacyCmds.OP;

    public static String[] packages() {
        String[] p = new String[APPS.length];
        for (int i = 0; i < p.length; i++) p[i] = APPS[i][0];
        return p;
    }

    /** "device metrics, app metrics, ..." for the page text. */
    public static String summary() {
        StringBuilder s = new StringBuilder();
        for (String[] a : APPS) s.append(s.length() == 0 ? "" : ", ").append(a[1]);
        return s.toString();
    }

    /**
     * On: every app loses "run in the background" (journaled, so Restore defaults gives it back), the usage-stats
     * proxy also loses usage access, and whatever is running in the background is closed. Off: Starve.wake for each.
     */
    public static String quiet(boolean on) { return on ? PrivacyCmds.telemetryQuiet() : PrivacyCmds.telemetryWake(); }

    /** Shell expression printing how many of the apps can't run in the background right now. */
    public static final String COUNT_EXPR = PrivacyCmds.COUNT_EXPR;

    private static final Pattern COUNT = Pattern.compile("QUIET (\\d+)");

    /** The last "QUIET n" in out, or -1. */
    public static int count(String out) {
        Matcher m = COUNT.matcher(out == null ? "" : out);
        int r = -1;
        while (m.find()) r = Integer.parseInt(m.group(1));
        return r;
    }

    /** "Quiet", "Running", "Partly quiet (3 of 8)" or "not read yet". */
    public static String state(int n) {
        if (n < 0) return "not read yet";
        if (n == 0) return "Running";
        if (n >= APPS.length) return "Quiet";
        return "Partly quiet (" + n + " of " + APPS.length + ")";
    }
}
