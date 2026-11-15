package com.clean.stickman.core;

/**
 * The daemons' liveness answer. They run in adbd's process group, so whenever the system restarts adbd (ADB toggled,
 * a USB mode change, on some devices around standby) both are killed, and the app is not told. stickd therefore
 * answers one line on a loopback TCP port: "stickd <version> remapd=<up|down|released>". No answer = stickd is gone.
 * "released" = remapd left on purpose (Back held 10 s): only reopening the app restarts it. Pure Java, host-tested.
 */
public final class Liveness {
    private Liveness() {}

    public static final int PORT = 38642;
    public static final String UP = "up", DOWN = "down", RELEASED = "released";

    public static String answer(int version, String remapd) { return "stickd " + version + " remapd=" + remapd; }

    /** Whether the watchdog should run the ensure script again; reply = the line read, or null for no answer. */
    public static boolean needsEnsure(String reply) {
        if (reply == null || !reply.trim().matches("stickd \\d+ remapd=(up|down|released)")) return true;
        return reply.trim().endsWith("remapd=" + DOWN);
    }

    private static final long[] RETRY_GAP_MS = { 60_000, 120_000, 300_000, 600_000, 1_200_000 };

    /**
     * How long the watchdog waits before starting the setup again, after failures restarts in a row that did not
     * bring both daemons up. None after a restart that worked: the next death is repaired at once. A setup that
     * cannot work (ADB switched off, another VPN in the way) must not keep taking the VPN, hence the backoff.
     */
    public static long retryGapMs(int failures) {
        if (failures <= 0) return 0;
        return RETRY_GAP_MS[Math.min(failures, RETRY_GAP_MS.length) - 1];
    }

    /** remapd's state for the answer: pid = content of remapd.pid ("" if missing), alive = that process exists. */
    public static String remapdState(String pid, boolean alive, boolean releasedMarker) {
        if (pid != null && !pid.trim().isEmpty() && alive) return UP;
        return releasedMarker ? RELEASED : DOWN;
    }
}
