package com.clean.stickman.core.cmd;

/**
 * Privacy page: its read-back line, Quiet telemetry (appops on Amazon's reporting apps; the list and reasons are in
 * core.Telemetry) and the private-DNS safety probe stickd runs (core.DnsGuard decides). The ad-tracking, location
 * and private-DNS setters are core.Commands' (another group's).
 */
public final class PrivacyCmds {
    private PrivacyCmds() {}

    /** The reporting apps Quiet telemetry restricts, in display order (labels: core.Telemetry.APPS). */
    public static final String[] TELEMETRY_PKGS = {
        "com.amazon.device.metrics", "com.amazon.client.metrics.api", "com.amazon.device.crashmanager",
        "com.fireos.usagestats.proxy", "com.amazon.ftvads.deeplinking", "com.amazon.tv.csapp", "com.amazon.ceviche",
        "com.amazon.diode",
    };
    public static final String USAGE_PROXY = "com.fireos.usagestats.proxy";
    public static final String OP = "RUN_ANY_IN_BACKGROUND";

    /** How many of the apps can't run in the background right now (RUN_ANY_IN_BACKGROUND: 28). */
    public static final Cmd TELEMETRY_COUNT = Cmd.def("privacy.telemetry_count", "Privacy", 28,
        "cmd:appops appops:RUN_ANY_IN_BACKGROUND");

    /** Shell expression printing the count. */
    public static final String COUNT_EXPR;
    static {
        StringBuilder s = new StringBuilder("$(n=0; for p in");
        for (String p : TELEMETRY_PKGS) s.append(' ').append(p);
        s.append("; do cmd appops get $p ").append(OP).append(" 2>/dev/null | grep -q ignore && n=$((n+1)); done; echo $n)");
        COUNT_EXPR = s.toString();
    }

    /**
     * "DNS mode specifier ADS v QUIET n LOC v" (parser: PrivacyPage.STATE). Every part is a settings get, so the line
     * is printed on every version; below 28 DNS reads "null null" (no private DNS) and QUIET 0 (see
     * {@link #TELEMETRY_COUNT}).
     */
    public static final Cmd READ = Cmd.def("privacy.read", "Privacy", 22, "settings");

    public static final String READ_CMD = "echo DNS $(settings get global private_dns_mode) $(settings get global private_dns_specifier)"
        + " ADS $(settings get secure limit_ad_tracking) QUIET " + COUNT_EXPR + " LOC $(settings get secure location_mode)";

    /** cmd, then {@link #READ_CMD}. */
    public static String thenRead(String cmd) { return cmd + "; " + READ_CMD; }

    /**
     * Quiet telemetry on: RUN_ANY_IN_BACKGROUND ignore (journaled) per app, GET_USAGE_STATS ignore for the usage
     * proxy, am kill. Ends in "true"; the page verifies with {@link #TELEMETRY_COUNT}.
     */
    public static final Cmd TELEMETRY_QUIET = Cmd.def("privacy.telemetry_quiet", "Privacy", 28,
        "cmd:appops appops:RUN_ANY_IN_BACKGROUND am:kill");

    public static String telemetryQuiet() {
        StringBuilder s = new StringBuilder();
        for (String p : TELEMETRY_PKGS) {
            s.append(JournalCmds.appop(p, OP, "ignore")).append("; ");
            if (p.equals(USAGE_PROXY)) s.append(JournalCmds.appop(p, "GET_USAGE_STATS", "ignore")).append("; ");
            // Fire OS refuses am kill for its protected packages; the restriction ends their background work anyway
            s.append("am kill ").append(p).append(" >/dev/null 2>&1; ");
        }
        return s.append("true").toString();
    }

    /** Quiet telemetry off: {@link SpeedCmds#wake} for each app. */
    public static final Cmd TELEMETRY_WAKE = Cmd.def("privacy.telemetry_wake", "Privacy", 25, "cmd:appops grep");

    public static String telemetryWake() {
        StringBuilder s = new StringBuilder();
        for (String p : TELEMETRY_PKGS) s.append(SpeedCmds.wake(p)).append("; ");
        return s.append("true").toString();
    }

    /** IP reachable and a name resolves (through the private DNS). Run by stickd at +20 s and +60 s. */
    public static final Cmd DNS_PROBE = Cmd.def("privacy.dns_probe", "Privacy", 22, "ping");

    public static final String DNS_PROBE_CMD = "ping -c 1 -W 3 1.1.1.1 && ping -c 1 -W 3 dns.google";

    /** Private DNS off after both probes failed. Private DNS is Android 9+. */
    public static final Cmd DNS_REVERT = Cmd.def("privacy.dns_revert", "Privacy", 28, "settings:private_dns");

    public static final String DNS_REVERT_CMD = "settings put global private_dns_mode off";
}
