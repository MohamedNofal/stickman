package com.clean.stickman.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Quiet telemetry": Amazon's reporting apps that Fire OS refuses to disable or suspend still accept appops.
 * Verified on the stick (AFTMA08C15, Fire OS 8 / API 30, 2026-11-06): RUN_ANY_IN_BACKGROUND ignore sets and resets
 * cleanly on every package below, GET_USAGE_STATS ignore on the usage-stats proxy too. com.amazon.device.logmanager is
 * left out on purpose: it shares the system uid (1000) and appops are per uid; Deep sleep already drops it from the
 * doze whitelist. The native Minerva / telemetry-emitter services (children of neo-init) can't be touched from the
 * shell: only an ad-blocking DNS reaches those. Undo is Starve.wake (the appops are journaled like Keep asleep). Pure.
 */
public final class Telemetry {
    private Telemetry() {}

    /** {package, what it reports}, in display order. */
    public static final String[][] APPS = {
        { "com.amazon.device.metrics", "device metrics" },
        { "com.amazon.client.metrics.api", "app metrics" },
        { "com.amazon.device.crashmanager", "crash reports" },
        { "com.fireos.usagestats.proxy", "app-usage statistics" },
        { "com.amazon.ftvads.deeplinking", "ad links" },
        { "com.amazon.tv.csapp", "remote support" },
        { "com.amazon.ceviche", "remote config and metrics" },
        { "com.amazon.diode", "remote diagnostics" },
    };
    public static final String USAGE_PROXY = "com.fireos.usagestats.proxy";
    public static final String OP = "RUN_ANY_IN_BACKGROUND";

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
    public static String quiet(boolean on) {
        StringBuilder s = new StringBuilder();
        for (String[] a : APPS) {
            String p = a[0];
            if (on) {
                s.append(Originals.appop(p, OP, "ignore")).append("; ");
                if (p.equals(USAGE_PROXY)) s.append(Originals.appop(p, "GET_USAGE_STATS", "ignore")).append("; ");
                // Fire OS refuses am kill for its protected packages; the restriction ends their background work anyway
                s.append("am kill ").append(p).append(" >/dev/null 2>&1; ");
            } else {
                s.append(Starve.wake(p)).append("; ");
            }
        }
        return s.append("true").toString();
    }

    /** Shell expression printing how many of the apps can't run in the background right now. */
    public static final String COUNT_EXPR;
    static {
        StringBuilder s = new StringBuilder("$(n=0; for p in");
        for (String[] a : APPS) s.append(' ').append(a[0]);
        s.append("; do cmd appops get $p ").append(OP).append(" 2>/dev/null | grep -q ignore && n=$((n+1)); done; echo $n)");
        COUNT_EXPR = s.toString();
    }

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
