package com.clean.stickman.core;

import java.nio.charset.StandardCharsets;

/** adb packet-size bookkeeping (pure; AdbClient uses it). */
public final class AdbLimits {
    private AdbLimits() {}

    /** What we advertise in CNXN arg1. */
    public static final int OURS = 256 * 1024;

    /** min(ours, remote); a non-positive remote (not reported) falls back to the legacy 4096. */
    public static int effective(int ours, int remote) {
        int r = remote > 0 ? remote : 4096;
        return Math.min(ours, r);
    }

    public static boolean fits(int payloadLen, int ours, int remote) {
        return payloadLen <= effective(ours, remote);
    }

    /** Bytes of the OPEN payload {@code shell:<cmd>\0}. */
    public static int shellPayloadLen(String cmd) {
        return ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8).length;
    }
}
