package com.clean.stickman.core.cmd;

/**
 * stickd's own commands (daemon/Stickd, daemon/Sh): foreground seed and follower, the uninstall watchdog, the spool
 * actions it handles itself (screensaver, screenshot, pointer toggle, kill current, open screen, sleep key) and the
 * shared {@code dumpsys media_session} read. Pure Java: stickd runs under app_process and is tested on the host.
 */
public final class DaemonCmds {
    private DaemonCmds() {}

    /** Seeds the foreground package before the first resume event arrives (parsed by EventParser.dumpsysResumed). */
    public static final Cmd FG_SEED = Cmd.def("stickd.fg_seed", "Foreground", 22, "dumpsys:activity grep");
    public static String fgSeed() { return "dumpsys activity activities | grep -m1 mResumedActivity"; }

    /**
     * Foreground follower: argv for ProcessBuilder (no shell). The tags are EventParser.RESUMED_TAGS.
     * {@code -T} and the events buffer exist on every supported version; the event tag names are not probed below 30.
     */
    public static final Cmd FG_FOLLOW = Cmd.def("stickd.fg_follow", "Foreground", 22, "logcat");
    public static String[] fgFollow(String tag1, String tag2, String tag3) {
        return new String[] { "/system/bin/logcat", "-b", "events", "-T", "1", "-s", tag1, tag2, tag3 };
    }

    /**
     * Kills logcat followers a previous stickd left behind. stickd is stopped with SIGTERM (no shutdown hook under
     * app_process), so its children are re-parented to init and only die at their next write.
     */
    public static final Cmd REAP_FOLLOWERS = Cmd.def("stickd.reap_followers", "Foreground", 22, "pidof cut grep kill");
    public static String reapFollowers() {
        return "for P in $(pidof logcat); do [ \"$(cut -d' ' -f4 /proc/$P/stat 2>/dev/null)\" = 1 ] "
            + "&& grep -qE 'set_resumed_activity|RemapD:I' /proc/$P/cmdline 2>/dev/null && kill $P; done; true";
    }

    /** Uninstall watchdog: is Stickman still installed (parsed by Stickd.pmPath). pkg is EnsureScript.PKG. */
    public static final Cmd WATCHDOG_PM_PATH = Cmd.def("stickd.watchdog_pm_path", "Uninstall watchdog", 22, "pm:path");
    public static String watchdogPmPath(String pkg) { return "pm path " + pkg + " 2>/dev/null"; }

    /** Uninstall watchdog: stops remapd. pid: digits only (checked by Stickd.isProc before the call). */
    public static final Cmd KILL_REMAPD = Cmd.def("stickd.kill_remapd", "Uninstall watchdog", 22, "kill");
    public static String killRemapd(String pid) { return "kill " + pid; }

    /** Uninstall watchdog: the release script (EnsureScript.RELEASE_STAYON_FADE, ends in "; ") closed with "true". */
    public static final Cmd UNINSTALL_RELEASE = Cmd.def("stickd.uninstall_release", "Uninstall watchdog", 22, "svc rm am:broadcast");
    public static String uninstallRelease(String releaseScript) { return releaseScript + "true"; }

    /** Screensaver toggle: current value ("0" = off; anything else, empty included, counts as on). */
    public static final Cmd SCREENSAVER_GET = Cmd.def("stickd.screensaver_get", "Screensaver", 22, "settings");
    public static String screensaverGet() { return "settings get secure screensaver_enabled"; }

    /** Screenshot to path (built by Stickd under /sdcard/Pictures/Stickman, digits and "_" only). */
    public static final Cmd SCREENSHOT = Cmd.def("stickd.screenshot", "Screenshot", 22, "mkdir screencap");
    public static String screenshot(String path) { return "mkdir -p /sdcard/Pictures/Stickman && screencap -p " + path; }

    /** The app's pointer switch: tells remapd (which consumes DIR/mouse.cmd) to toggle the mouse. */
    public static final Cmd MOUSE_TOGGLE = Cmd.def("stickd.mouse_toggle", "Mouse", 22, "echo mv");
    public static String mouseToggle(String dir) {
        return "echo toggle > " + dir + "/mouse.cmd.tmp && mv " + dir + "/mouse.cmd.tmp " + dir + "/mouse.cmd";
    }

    /** Kill current: force-stops the foreground app. pkg: checked by KillPolicy.isNeverKill (plain package names only). */
    public static final Cmd KILL_CURRENT = Cmd.def("stickd.kill_current", "Kill current", 22, "am:force-stop");
    public static String killCurrent(String pkg) { return "am force-stop " + pkg; }

    /**
     * Open a settings screen: a component ("pkg/cls", with -n) or an intent action (-a). quotedArg is the argument
     * already shell-quoted (Originals.q) after the SCREEN_ARG check in Stickd.
     */
    public static final Cmd OPEN_SCREEN = Cmd.def("stickd.open_screen", "Shortcuts", 22, "am:start");
    public static String openScreen(boolean component, String quotedArg) {
        return (component ? "am start -n " : "am start -a ") + quotedArg;
    }

    /** Sleep key (sleep_now action, and the end of the sleep timer). */
    public static final Cmd SLEEP_KEY = Cmd.def("stickd.sleep_key", "Sleep", 22, "input");
    public static String sleepKey() { return "input keyevent KEYCODE_SLEEP"; }

    /** Media sessions dump (MediaState parses it): stay-awake, auto-close, Boost, End and End all, the task feed. */
    public static final Cmd MEDIA_SESSIONS = Cmd.def("stickd.media_sessions", "Media state", 22, "dumpsys:media_session");
    public static String mediaSessions() { return "dumpsys media_session"; }

    /** One logcat line through the log binary: argv for ProcessBuilder (no shell), fallback of Sh.log. */
    public static final Cmd LOG = Cmd.def("stickd.log", "Daemon log", 22, "log");
    public static String[] log(String tag, String msg) { return new String[] { "/system/bin/log", "-t", tag, msg }; }
}
