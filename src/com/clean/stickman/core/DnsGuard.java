package com.clean.stickman.core;

/** Private-DNS safety check: probe at +20 s and +60 s; revert to off only if both probes failed. */
public final class DnsGuard {
    private DnsGuard() {}

    /** IP reachable and a name resolves (through the private DNS). */
    public static final String PROBE = "ping -c 1 -W 3 1.1.1.1 && ping -c 1 -W 3 dns.google";
    public static final long[] PROBE_AT_MS = {20_000, 60_000};
    public static final String REVERT = "settings put global private_dns_mode off";

    public static boolean decide(boolean[] probes) {
        if (probes == null || probes.length < PROBE_AT_MS.length) return false;
        for (boolean ok : probes) if (ok) return false;
        return true;
    }
}
