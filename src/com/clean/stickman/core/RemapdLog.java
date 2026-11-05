package com.clean.stickman.core;

/** Recognises remapd's lifecycle log lines, so the UI stops saying "Running" after a Back-10 s release. */
public final class RemapdLog {
    private RemapdLog() {}

    public static final int OTHER = 0, RELEASED = 1, STARTED = 2;

    /** remapd logs "BACK held 10s -> exiting, remote released" and "remapd starting pid N device ...". */
    public static int kind(String line) {
        if (line == null || !line.contains("RemapD")) return OTHER;
        if (line.contains("BACK held 10s -> exiting")) return RELEASED;
        if (line.contains("remapd starting pid")) return STARTED;
        return OTHER;
    }

    /** The newest remapd lifecycle event is a Back-hold release (no remapd start since). */
    public static boolean released(long releasedAt, long startedAt) {
        return releasedAt > 0 && releasedAt >= startedAt;
    }
}
