package com.clean.stickman.core.cmd;

/**
 * Freezing Fire OS system updates (see core.Ota for why it works and for the state parser). Fire OS refuses to
 * disable its OTA packages, so the freeze rests on pm suspend, which is missing / refused below Android 11.
 */
public final class OtaCmds {
    private OtaCmds() {}

    /** OTA packages, in the order they are frozen. All three are present on the Fire TV 4K Max. */
    public static final String[] PACKAGES = {
        "com.amazon.device.software.ota",
        "com.amazon.device.software.ota.override",
        "com.amazon.tv.forcedotaupdater.v2",
    };

    /** Printed before each package's state line by {@link #state()}. */
    public static final String MARK = "---stickman-ota---";

    public static final Cmd STATE = Cmd.def("ota.state", "OTA freeze", 22, "dumpsys:package grep tr");

    /**
     * For every package print {@link #MARK} + its name, then the first "User 0:" line of dumpsys with the spaces
     * stripped (so "suspended=true" / "enabled=3" can be matched without parsing the whole dump).
     */
    public static String state() {
        StringBuilder s = new StringBuilder();
        for (String p : PACKAGES) {
            s.append("echo ").append(MARK).append(' ').append(p).append("; ");
            s.append("dumpsys package ").append(p).append(" 2>/dev/null | grep -m1 'User 0:' | tr -d ' '; ");
        }
        return s.toString();
    }

    /** Also the boot re-freeze line of the ensure script (announce false). */
    public static final Cmd FREEZE = Cmd.def("ota.freeze", "OTA freeze", 30, "pm:suspend pm:disable-user");

    /** Suspends every OTA package and disables the forced updater; prints OTA_FROZEN when announce. */
    public static String freeze(boolean announce) {
        StringBuilder s = new StringBuilder();
        for (String p : PACKAGES) {
            // suspend works on Fire OS protected packages; disable-user only succeeds on the companion
            s.append("pm suspend --user 0 ").append(p).append(" >/dev/null 2>&1; ");
            s.append("pm disable-user --user 0 ").append(p).append(" >/dev/null 2>&1; ");
        }
        if (announce) s.append("echo OTA_FROZEN");
        return s.toString();
    }

    /** Also run first by Restore defaults / Undo everything on every version (silenced; harmless where it fails). */
    public static final Cmd THAW = Cmd.def("ota.thaw", "OTA freeze", 30, "pm:unsuspend pm:enable");

    /** Unsuspends and re-enables every OTA package; prints OTA_THAWED. */
    public static String thaw() {
        StringBuilder s = new StringBuilder();
        for (String p : PACKAGES) {
            s.append("pm unsuspend --user 0 ").append(p).append(" >/dev/null 2>&1; ");
            s.append("pm enable ").append(p).append(" >/dev/null 2>&1; ");
        }
        s.append("echo OTA_THAWED");
        return s.toString();
    }

    /** {@link #freeze} then {@link #state}, so the page sees the result in the same answer. */
    public static String freezeThenState() { return freeze(true) + "; " + state(); }

    /** {@link #thaw} then {@link #state}. */
    public static String thawThenState() { return thaw() + "; " + state(); }
}
