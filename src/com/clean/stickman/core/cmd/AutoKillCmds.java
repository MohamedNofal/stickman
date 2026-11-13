package com.clean.stickman.core.cmd;

/**
 * Boost, auto-close and trim (daemon/AutoKill). The process list is LruParser.read, launchable apps
 * KillPolicy.LAUNCHABLE_CMD and the trim of each process Trim.cmd: those builders live with their own classes.
 */
public final class AutoKillCmds {
    private AutoKillCmds() {}

    /** TV sleep state ("mWakefulness=Asleep/Dozing", AutoKill.isAsleep): trim on sleep, and the updater's idle test. */
    public static final Cmd WAKEFULNESS = Cmd.def("autokill.wakefulness", "Trim", 22, "dumpsys:power grep");
    public static String wakefulness() { return "dumpsys power | grep -m1 mWakefulness="; }

    /** Boost: frees the app caches (30 s timeout). Best effort: below API 28 pm has no trim-caches and it fails silently. */
    public static final Cmd TRIM_CACHES = Cmd.def("autokill.trim_caches", "Boost", 28, "pm:trim-caches");
    public static String trimCaches() { return "pm trim-caches 999G"; }

    /**
     * Gentle first: asks a candidate to give back memory before it is closed. Best effort (ends in "; true"): on
     * API 22 am has no send-trim-memory and the kill below still runs. pkg: a KillPolicy.candidates entry.
     */
    public static final Cmd TRIM_APP = Cmd.def("autokill.trim_app", "Boost", 25, "am:send-trim-memory");
    public static String trimApp(String pkg) { return "am send-trim-memory " + pkg + " COMPLETE >/dev/null 2>&1; true"; }

    /** Closes a candidate (am kill), printing KILLED when am accepted it. */
    public static final Cmd KILL = Cmd.def("autokill.kill", "Boost", 22, "am:kill");
    /** Same with am force-stop, for always-kill (blacklisted) apps. */
    public static final Cmd FORCE_STOP = Cmd.def("autokill.force_stop", "Auto-close", 22, "am:force-stop");
    /** pkg: a KillPolicy.candidates entry (plain package names only, KillPolicy.isNeverKill). */
    public static String kill(String pkg, boolean forceStop) {
        return (forceStop ? "am force-stop " : "am kill ") + pkg + " && echo KILLED";
    }
}
