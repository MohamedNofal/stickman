package com.clean.stickman.core.cmd;

/**
 * GitHub-edition self-update (daemon/Updater). The download is plain HTTP from Java; these are the shell steps
 * around it. The TV-asleep half of the idle test is {@link AutoKillCmds#wakefulness}.
 */
public final class UpdaterCmds {
    private UpdaterCmds() {}

    /** Number of sessions playing right now ("0" = idle; a failed dumpsys also gives 0). */
    public static final Cmd PLAYING_COUNT = Cmd.def("update.playing_count", "Update", 22, "dumpsys:media_session grep");
    public static String playingCount() { return "dumpsys media_session | grep -c 'state=PlaybackState {state=3'"; }

    /** Installed versionName (Update.installedVersion). pkg is EnsureScript.PKG. */
    public static final Cmd INSTALLED_VERSION = Cmd.def("update.installed_version", "Update", 22, "dumpsys:package grep");
    public static String installedVersion(String pkg) { return "dumpsys package " + pkg + " | grep -m1 versionName="; }

    /** Installs the verified download (180 s timeout); output contains "Success" when it worked. apk is Update.FILE. */
    public static final Cmd INSTALL = Cmd.def("update.install", "Update", 22, "pm:install");
    public static String install(String apk) { return "pm install -r " + apk + " 2>&1"; }

    /** After "Update now": brings Stickman back. pkg is EnsureScript.PKG. */
    public static final Cmd RELAUNCH = Cmd.def("update.relaunch", "Update", 22, "am:start");
    public static String relaunch(String pkg) { return "am start -n " + pkg + "/com.clean.stickman.MainActivity >/dev/null 2>&1"; }
}
