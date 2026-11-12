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

    /** remapd's state for the answer: pid = content of remapd.pid ("" if missing), alive = that process exists. */
    public static String remapdState(String pid, boolean alive, boolean releasedMarker) {
        if (pid != null && !pid.trim().isEmpty() && alive) return UP;
        return releasedMarker ? RELEASED : DOWN;
    }
}
