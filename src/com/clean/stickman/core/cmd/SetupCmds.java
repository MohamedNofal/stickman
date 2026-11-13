package com.clean.stickman.core.cmd;

import com.clean.stickman.core.Actions;
import com.clean.stickman.core.B64;

import static com.clean.stickman.core.EnsureScript.BIN;
import static com.clean.stickman.core.EnsureScript.DIR;
import static com.clean.stickman.core.EnsureScript.OLD_DIR;
import static com.clean.stickman.core.EnsureScript.OLD_PKG;
import static com.clean.stickman.core.EnsureScript.PKG;
import static com.clean.stickman.core.EnsureScript.STAYON_MARKER;

/**
 * The pieces of the "ensure" setup script (assembled by {@code EnsureScript.build}), the master-off stop script and
 * the first-connect import. All run as shell through the bridge; on Fire OS 5 the basic tools come from the bundled
 * toybox that {@link #toybox} installs first.
 */
public final class SetupCmds {
    private SetupCmds() {}

    /** Single-quoted path; only plain path characters are accepted. */
    public static String quotePath(String p) {
        if (p == null || !p.matches("/[A-Za-z0-9._~=+/-]+")) throw new IllegalArgumentException("bad path: " + p);
        return "'" + p + "'";
    }

    /**
     * Put in front of every command the bridge runs: where the tools were installed, they come first in PATH.
     * The daemons inherit it from the ensure script that starts them.
     */
    public static final Cmd PATH_PREFIX = Cmd.def("setup.path_prefix", "Setup", 22, "");
    public static String pathPrefix() {
        return "[ -d " + BIN + " ] && export PATH=" + BIN + ":$PATH; ";
    }

    /**
     * Fire OS 5 (Android 5.1) has the old toolbox: no base64, setsid, timeout, pidof, stat, head, tail, cut, tr, sed,
     * sort, printf..., and its sleep only takes whole seconds. The APK ships a static toybox (0BSD) next to remapd;
     * where base64 or setsid is missing this installs it under DIR/bin with one link per tool it contains. They all
     * take precedence there: the old tools that do exist behave differently (cmp has no -s, sleep takes whole
     * seconds only, ps has no -A / -o). Uses only what toolbox has: mkdir, cat, chmod, mv, ln, rm.
     * Newer systems skip all of it. Ends with {@link #pathPrefix}.
     */
    public static final Cmd TOYBOX = Cmd.def("setup.toybox", "Setup", 22, "mkdir cat chmod mv ln rm");
    public static String toybox(String toyboxPath, int version) {
        String tb = quotePath(toyboxPath);
        return "if ! command -v base64 >/dev/null 2>&1 || ! command -v setsid >/dev/null 2>&1; then "
            + "if [ -f " + tb + " ] && [ \"$(cat " + BIN + "/.v 2>/dev/null)\" != " + version + " ]; then "
            + "mkdir -p " + BIN + " && cat " + tb + " > " + BIN + "/toybox.new && chmod 755 " + BIN + "/toybox.new && "
            + "mv " + BIN + "/toybox.new " + BIN + "/toybox && { "
            + "for a in $(" + BIN + "/toybox); do rm -f " + BIN + "/$a; ln -s toybox " + BIN + "/$a 2>/dev/null; done; "
            + "echo " + version + " > " + BIN + "/.v; }; fi; fi; "
            + pathPrefix();
    }

    /** First line of the script: the data dir exists before the toybox goes in. */
    public static final Cmd MAKE_DIR = Cmd.def("setup.make_dir", "Setup", 22, "mkdir");
    public static String makeDir() {
        return "mkdir -p " + DIR + "; ";
    }

    /** Data dir and the action spool (act/), then cd into the data dir (the rest of the script uses relative names). */
    public static final Cmd ENTER_DIR = Cmd.def("setup.enter_dir", "Setup", 22, "mkdir");
    public static String enterDir(String spoolDir) {
        return "D=" + DIR + "; mkdir -p " + DIR + " " + spoolDir + " && cd " + DIR + " || exit 1; ";
    }

    /**
     * Shell test: process $VAR is exactly NAME, by /proc comm (remapd: exe basename; stickd: app_process --nice-name)
     * or else by the first NUL-separated cmdline arg. Never a substring grep: the ensure shell's own cmdline
     * contains both names.
     */
    public static final Cmd IS_PROC = Cmd.def("setup.is_proc", "Setup", 22, "cat tr head");
    public static String isProc(String var, String name) {
        return "{ [ \"$(cat /proc/$" + var + "/comm 2>/dev/null)\" = " + name + " ] || "
            + "[ \"$(tr '\\000' '\\n' < /proc/$" + var + "/cmdline 2>/dev/null | head -n 1)\" = " + name + " ]; }";
    }

    /** Shell function: alive NAME -> true when NAME.pid names a live NAME process; sets $P. */
    public static final Cmd ALIVE_FN = Cmd.def("setup.alive_fn", "Setup", 22, "cat tr head");
    public static String aliveFn() {
        return "alive() { P=$(cat " + DIR + "/$1.pid 2>/dev/null); [ -n \"$P\" ] && "
            + "{ [ \"$(cat /proc/$P/comm 2>/dev/null)\" = \"$1\" ] || "
            + "[ \"$(cat /proc/$P/cmdline 2>/dev/null | tr '\\000' '\\n' | head -n 1)\" = \"$1\" ]; }; }; ";
    }

    /** Resets the change flags (NEW: new remapd binary, CH: a conf changed), then copies remapd when it differs. */
    public static final Cmd INSTALL_REMAPD = Cmd.def("setup.install_remapd", "Setup", 22, "cmp cp chmod mv");
    public static String installRemapd(String libPath) {
        return "NEW=0; CH=0; "
            + "cmp -s " + quotePath(libPath) + " remapd || { cp " + quotePath(libPath)
            + " remapd.new && chmod 755 remapd.new && mv remapd.new remapd && NEW=1; }; ";
    }

    /** Writes NAME atomically from base64 only when the content differs; sets CH=1 on change. */
    public static final Cmd WRITE_CONF = Cmd.def("setup.write_conf", "Setup", 22, "echo base64 cmp rm mv");
    public static String writeConf(String name, String text) {
        return "echo " + B64.encode(text) + " | base64 -d > " + name + ".new && "
            + "{ if cmp -s " + name + ".new " + name + "; then rm -f " + name + ".new; else mv " + name + ".new " + name + "; CH=1; fi; }; ";
    }

    /** Self-grant: logcat for the UI. */
    public static final Cmd GRANT_READ_LOGS = Cmd.def("setup.grant_read_logs", "Setup", 22, "pm:grant");
    public static String grantReadLogs() {
        return "pm grant " + PKG + " android.permission.READ_LOGS 2>/dev/null; ";
    }

    /** Self-grant: the overlay appop for the sleep fade. "cmd appops" is Android 7+; the standalone appops covers older systems. */
    public static final Cmd GRANT_OVERLAY = Cmd.def("setup.grant_overlay", "Setup", 22, "appops", 25, "cmd:appops");
    public static String grantOverlay() {
        return "cmd appops set " + PKG + " SYSTEM_ALERT_WINDOW allow 2>/dev/null || appops set " + PKG + " SYSTEM_ALERT_WINDOW allow 2>/dev/null; ";
    }

    /** Migration from KeyShift: stop its remapd first (only one process may grab the remote), uninstall it. Prints MIGRATED. */
    public static final Cmd MIGRATE_KEYSHIFT = Cmd.def("setup.migrate_keyshift", "Setup", 22,
        "pm:path pm:uninstall grep cat tr head kill pidof rm sleep");
    public static String migrateKeyShift() {
        return "if [ -e " + OLD_DIR + " ] || pm path " + OLD_PKG + " 2>/dev/null | grep -q '^package:'; then "
            + "O=$(cat " + OLD_DIR + "/remapd.pid 2>/dev/null); "
            + "[ -n \"$O\" ] && " + isProc("O", "remapd") + " && kill $O; "
            + "for O in $(pidof remapd 2>/dev/null); do grep -q " + OLD_DIR + "/ /proc/$O/cmdline 2>/dev/null && kill $O; done; "
            + "pm uninstall " + OLD_PKG + " >/dev/null 2>&1; "
            + "rm -rf " + OLD_DIR + "; sleep 0.3; echo MIGRATED; fi; ";
    }

    /** Stops the old wolfd shell script (comm "sh": matched by cmdline, but never our own shell). */
    public static final Cmd RETIRE_WOLFD = Cmd.def("setup.retire_wolfd", "Setup", 22, "cat grep kill rm");
    public static String retireWolfd() {
        return "W=$(cat /data/local/tmp/wolfd.pid 2>/dev/null); "
            + "[ -n \"$W\" ] && [ \"$W\" != \"$$\" ] && grep -q wolfd /proc/$W/cmdline 2>/dev/null && kill -- -$W 2>/dev/null; "
            + "rm -f /data/local/tmp/wolfd.pid; ";
    }

    /** Retires Home Switch, the app Stickman replaced. */
    public static final Cmd RETIRE_HOMESWITCH = Cmd.def("setup.retire_homeswitch", "Setup", 22, "am:force-stop");
    public static String retireHomeSwitch() {
        return "am force-stop com.clean.homeswitch 2>/dev/null; ";
    }

    /** Stops daemon NAME by its pid file (only when the pid really is NAME) and removes the pid file. */
    public static final Cmd KILL_DAEMON = Cmd.def("setup.kill_daemon", "Setup", 22, "cat tr head kill rm");
    public static String killDaemon(String name) {
        return "P=$(cat " + DIR + "/" + name + ".pid 2>/dev/null); "
            + "[ -n \"$P\" ] && " + isProc("P", name) + " && kill $P; "
            + "rm -f " + DIR + "/" + name + ".pid; ";
    }

    /** New version or APK: stop both daemons; only a new remapd binary: stop remapd. Sets $V and $A. */
    public static final Cmd RESTART_IF_NEW = Cmd.def("setup.restart_if_new", "Setup", 22, "cat tr head kill rm sleep");
    public static String restartIfNew(String apkPath, int version) {
        return "V=" + version + "; A=" + quotePath(apkPath) + "; "
            + "if [ \"$(cat version 2>/dev/null)\" != \"$V\" ] || [ \"$(cat apk 2>/dev/null)\" != \"$A\" ]; then "
            + killDaemon("remapd") + killDaemon("stickd") + "sleep 0.3; "
            + "elif [ $NEW = 1 ]; then " + killDaemon("remapd") + "sleep 0.3; fi; ";
    }

    /** Records the version and APK (needs $A from {@link #restartIfNew}); before starting, so stickd's "STATUS started" reports it. */
    public static final Cmd RECORD_VERSION = Cmd.def("setup.record_version", "Setup", 22, "echo");
    public static String recordVersion(int version) {
        return "echo " + version + " > version; echo \"$A\" > apk; ";
    }

    /** Starts remapd when it is not alive and waits up to 2 s for it. */
    public static final Cmd START_REMAPD = Cmd.def("setup.start_remapd", "Setup", 22, "setsid nohup sleep");
    public static String startRemapd() {
        return "if ! alive remapd; then setsid nohup " + DIR + "/remapd " + DIR + "/remap.conf </dev/null >/dev/null 2>&1 & "
            + "i=0; while [ $i -lt 20 ] && ! alive remapd; do sleep 0.1; i=$((i+1)); done; fi; ";
    }

    /**
     * A running stickd re-reads changed confs (reload action); otherwise starts it and waits up to 4 s. A stale pid
     * file (e.g. from the previous boot) is removed first: stickd's own single-instance check must never see it.
     */
    public static final Cmd START_STICKD = Cmd.def("setup.start_stickd", "Setup", 22, "rm setsid nohup app_process sleep mkdir date echo mv");
    public static String startStickd(String apkPath) {
        return "if alive stickd; then [ $CH = 1 ] && { " + RemoteCmds.act(Actions.RELOAD, null) + "; }; "
            + "else rm -f " + DIR + "/stickd.pid; CLASSPATH=" + quotePath(apkPath)
            + " setsid nohup app_process /system/bin --nice-name=stickd com.clean.stickman.daemon.Stickd " + DIR
            + " </dev/null >/dev/null 2>&1 & "
            + "i=0; while [ $i -lt 40 ] && ! alive stickd; do sleep 0.1; i=$((i+1)); done; fi; ";
    }

    /** Prints {@code REMAPD <pid|-> STICKD <pid|->}. */
    public static final Cmd REPORT = Cmd.def("setup.report", "Setup", 22, "echo");
    public static String report() {
        return "R=-; alive remapd && R=$P; S=-; alive stickd && S=$P; echo REMAPD $R STICKD $S";
    }

    /** Prints the REMOTE line (what remapd made of the remote) when remapd has written its state; parsed by RemoteState. */
    public static final Cmd REMOTE_REPORT = Cmd.def("setup.remote_report", "Setup", 22, "cat echo");
    public static String remoteReport() {
        return "[ -f remote.state ] && echo \"REMOTE $(cat remote.state)\"";
    }

    /**
     * Give back a stay-on that stickd owns, and clear any sleep-fade overlay. Needed wherever stickd is stopped for
     * good (master OFF, Restore defaults, the uninstall watchdog): SIGTERM under app_process does not run JVM
     * shutdown hooks, so stickd cannot do it itself.
     */
    public static final Cmd RELEASE_STAYON_FADE = Cmd.def("setup.release_stayon_fade", "Setup", 22, "svc:power rm am:broadcast");
    public static String releaseStayOnFade() {
        return "[ -f " + DIR + "/" + STAYON_MARKER + " ] && { svc power stayon false; rm -f " + DIR + "/" + STAYON_MARKER + "; }; "
            + SleepCmds.fadeEnd() + " >/dev/null 2>&1; ";
    }

    /**
     * Master off: stop both daemons (remapd exiting releases the remote), then give back an owned stay-on and clear a
     * fade overlay (stickd's shutdown hook does not run on SIGTERM). Prints STOPPED.
     */
    public static final Cmd STOP = Cmd.def("setup.stop", "Setup", 22, "cat tr head kill rm svc:power am:broadcast echo");
    public static String stop() {
        return killDaemon("remapd") + killDaemon("stickd") + releaseStayOnFade() + "echo STOPPED";
    }

    /** First connect: rules from KeyShift (v1) and/or an earlier Stickman (v2); echo keeps a missing final newline apart. */
    public static final Cmd IMPORT_REMAP = Cmd.def("setup.import_remap", "Setup", 22, "cat echo");
    public static String importRemap() {
        return "cat /data/local/tmp/remap/remap.conf 2>/dev/null; echo; cat /data/local/tmp/stickman/remap.conf 2>/dev/null";
    }
}
