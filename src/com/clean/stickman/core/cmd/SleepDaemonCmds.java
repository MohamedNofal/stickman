package com.clean.stickman.core.cmd;

/**
 * stickd's sleep side (daemon/Sleep): the key watcher during a fade or the screen-off curtain, stay-awake while
 * media plays, and the private-DNS guard's probe wrapper. The fade / curtain broadcasts are SleepTimer's
 * (core/SleepTimer.java), the media pause is Commands.media, the sleep key is {@link DaemonCmds#sleepKey}.
 */
public final class SleepDaemonCmds {
    private SleepDaemonCmds() {}

    /**
     * Follows fresh RemapD lines with epoch timestamps (EventParser.remapKeyEpochMs): argv for ProcessBuilder.
     * minApi 25: the {@code -v epoch} format came with the Android 7 logcat (not in the Tools tables, not probed on
     * API 22). Without it a button press during a fade does not cancel the timer; nothing else breaks.
     */
    public static final Cmd KEY_WATCH = Cmd.def("sleep.key_watch", "Sleep timer", 25, "logcat logcat:epoch");
    public static String[] keyWatch() { return new String[] { "/system/bin/logcat", "-v", "epoch", "-T", "1", "-s", "RemapD:I" }; }

    /** Stay-awake: the user's own stay-on value (empty, "0" or "null" = off, so Stickman may claim it). */
    public static final Cmd STAY_ON_GET = Cmd.def("sleep.stay_on_get", "Stay awake", 22, "settings");
    public static String stayOnGet() { return "settings get global stay_on_while_plugged_in"; }

    /** Stay-awake on / off. */
    public static final Cmd STAY_ON = Cmd.def("sleep.stay_on", "Stay awake", 22, "svc");
    public static String stayOn(boolean on) { return on ? "svc power stayon true" : "svc power stayon false"; }

    /** DNS guard: runs DnsGuard.PROBE silently and prints PROBE_OK when it succeeded. */
    public static final Cmd DNS_PROBE = Cmd.def("sleep.dns_probe", "Private DNS", 22, "ping");
    public static String dnsProbe(String probe) { return "(" + probe + ") >/dev/null 2>&1 && echo PROBE_OK"; }
}
