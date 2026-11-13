package com.clean.stickman.core;

import java.nio.charset.StandardCharsets;

/**
 * The idempotent "ensure" shell script (run as shell via the bridge): installs remapd, writes both confs,
 * self-grants READ_LOGS + the overlay appop, migrates away from KeyShift, (re)starts remapd and stickd.
 * Prints {@code REMAPD <pid|-> STICKD <pid|->}.
 */
public final class EnsureScript {
    private EnsureScript() {}

    public static final String DIR = Originals.DIR;
    public static final String PKG = "com.clean.stickman";
    public static final String OLD_DIR = "/data/local/tmp/remap";
    public static final String OLD_PKG = "com.clean.keyshift";
    /** Marker file in DIR: stickd turned "svc power stayon" on (media stay-awake) and owns giving it back. */
    public static final String STAYON_MARKER = "stayon.owned";

    /**
     * Shell: give back a stay-on that stickd owns, and clear any sleep-fade overlay. Needed wherever stickd is
     * stopped for good (master OFF, Restore defaults, the uninstall watchdog): SIGTERM under app_process does
     * not run JVM shutdown hooks, so stickd cannot do it itself.
     */
    public static final String RELEASE_STAYON_FADE =
        "[ -f " + DIR + "/" + STAYON_MARKER + " ] && { svc power stayon false; rm -f " + DIR + "/" + STAYON_MARKER + "; }; "
        + SleepTimer.FADE_END + " >/dev/null 2>&1; ";

    /**
     * Shell test: process $VAR is exactly NAME, by /proc comm (remapd: exe basename; stickd: app_process --nice-name)
     * or else by the first NUL-separated cmdline arg. Never a substring grep: the ensure shell's own cmdline
     * contains both names.
     */
    static String isProc(String var, String name) {
        return "{ [ \"$(cat /proc/$" + var + "/comm 2>/dev/null)\" = " + name + " ] || "
            + "[ \"$(tr '\\000' '\\n' < /proc/$" + var + "/cmdline 2>/dev/null | head -n 1)\" = " + name + " ]; }";
    }

    /** Shell function: alive NAME -> true when NAME.pid names a live NAME process; sets $P. */
    private static final String ALIVE_FN =
        "alive() { P=$(cat " + DIR + "/$1.pid 2>/dev/null); [ -n \"$P\" ] && "
        + "{ [ \"$(cat /proc/$P/comm 2>/dev/null)\" = \"$1\" ] || "
        + "[ \"$(cat /proc/$P/cmdline 2>/dev/null | tr '\\000' '\\n' | head -n 1)\" = \"$1\" ]; }; }; ";

    private static String path(String p) {
        if (p == null || !p.matches("/[A-Za-z0-9._~=+/-]+")) throw new IllegalArgumentException("bad path: " + p);
        return "'" + p + "'";
    }

    private static String b64(String s) {
        return B64.encode(s);
    }

    /** Writes NAME atomically from base64 only when the content differs; sets CH=1 on change. */
    private static String writeConf(String name, String text) {
        return "echo " + b64(text) + " | base64 -d > " + name + ".new && "
            + "{ if cmp -s " + name + ".new " + name + "; then rm -f " + name + ".new; else mv " + name + ".new " + name + "; CH=1; fi; }; ";
    }

    private static String killPid(String name) {
        return "P=$(cat " + DIR + "/" + name + ".pid 2>/dev/null); "
            + "[ -n \"$P\" ] && " + isProc("P", name) + " && kill $P; "
            + "rm -f " + DIR + "/" + name + ".pid; ";
    }

    /** Stickman's own tools (toybox and links to it), used only where the system lacks them: see {@link #tools}. */
    public static final String BIN = DIR + "/bin";

    /**
     * Put in front of every command the bridge runs: where the tools were installed, they come first in PATH.
     * The daemons inherit it from the ensure script that starts them.
     */
    public static final String PATH_PREFIX = "[ -d " + BIN + " ] && export PATH=" + BIN + ":$PATH; ";

    /**
     * Fire OS 5 (Android 5.1) has the old toolbox: no base64, setsid, timeout, pidof, stat, head, tail, cut, tr, sed,
     * sort, printf..., and its sleep only takes whole seconds. The APK ships a static toybox (0BSD) next to remapd;
     * where base64 or setsid is missing this installs it under DIR/bin with one link per tool it contains. They all
     * take precedence there: the old tools that do exist behave differently (cmp has no -s, sleep takes whole
     * seconds only, ps has no -A / -o). Uses only what toolbox has: mkdir, cat, chmod, mv, ln, rm.
     * Newer systems skip all of it.
     */
    static String tools(String toyboxPath, int version) {
        String tb = path(toyboxPath);
        return "if ! command -v base64 >/dev/null 2>&1 || ! command -v setsid >/dev/null 2>&1; then "
            + "if [ -f " + tb + " ] && [ \"$(cat " + BIN + "/.v 2>/dev/null)\" != " + version + " ]; then "
            + "mkdir -p " + BIN + " && cat " + tb + " > " + BIN + "/toybox.new && chmod 755 " + BIN + "/toybox.new && "
            + "mv " + BIN + "/toybox.new " + BIN + "/toybox && { "
            + "for a in $(" + BIN + "/toybox); do rm -f " + BIN + "/$a; ln -s toybox " + BIN + "/$a 2>/dev/null; done; "
            + "echo " + version + " > " + BIN + "/.v; }; fi; fi; "
            + PATH_PREFIX;
    }

    public static String build(String apkPath, String libPath, int version, String remapConf, String stickdConf, boolean migrate) {
        String apk = path(apkPath), lib = path(libPath);
        StringBuilder s = new StringBuilder();
        // 0. tools first: everything below uses base64, setsid, cmp...
        s.append("mkdir -p " + DIR + "; ");
        s.append(tools(libPath.substring(0, libPath.lastIndexOf('/') + 1) + "libtoybox.so", version));
        // 1. dirs (act/ is the action spool, R5)
        s.append("D=" + DIR + "; mkdir -p " + DIR + " " + Spool.DIR + " && cd " + DIR + " || exit 1; ");
        s.append(ALIVE_FN);
        s.append("NEW=0; CH=0; ");
        // 2. remapd binary
        s.append("cmp -s " + lib + " remapd || { cp " + lib + " remapd.new && chmod 755 remapd.new && mv remapd.new remapd && NEW=1; }; ");
        // 3. confs
        s.append(writeConf("remap.conf", remapConf));
        s.append(writeConf("stickd.conf", stickdConf));
        // 4. self-grants: logcat for the UI, overlay for the sleep fade
        s.append("pm grant " + PKG + " android.permission.READ_LOGS 2>/dev/null; ");
        // "cmd appops" is Android 7+; the standalone appops command covers older systems
        s.append("cmd appops set " + PKG + " SYSTEM_ALERT_WINDOW allow 2>/dev/null || appops set " + PKG + " SYSTEM_ALERT_WINDOW allow 2>/dev/null; ");
        // 4b. Doze is reset by every reboot: re-apply it when the owner turned it on
        s.append(Lean.ENSURE_LINE);
        // 5. migration from KeyShift: stop its remapd first (only one process may grab the remote)
        if (migrate) {
            s.append("if [ -e " + OLD_DIR + " ] || pm path " + OLD_PKG + " 2>/dev/null | grep -q '^package:'; then ");
            s.append("O=$(cat " + OLD_DIR + "/remapd.pid 2>/dev/null); ");
            s.append("[ -n \"$O\" ] && " + isProc("O", "remapd") + " && kill $O; ");
            s.append("for O in $(pidof remapd 2>/dev/null); do grep -q " + OLD_DIR + "/ /proc/$O/cmdline 2>/dev/null && kill $O; done; ");
            s.append("pm uninstall " + OLD_PKG + " >/dev/null 2>&1; ");
            s.append("rm -rf " + OLD_DIR + "; sleep 0.3; echo MIGRATED; fi; ");
            s.append("W=$(cat /data/local/tmp/wolfd.pid 2>/dev/null); ");
            // wolfd was a shell script (comm "sh"): match its cmdline, but never our own shell
            s.append("[ -n \"$W\" ] && [ \"$W\" != \"$$\" ] && grep -q wolfd /proc/$W/cmdline 2>/dev/null && kill -- -$W 2>/dev/null; ");
            s.append("rm -f /data/local/tmp/wolfd.pid; ");
        }
        // 6. retire Home Switch
        s.append("am force-stop com.clean.homeswitch 2>/dev/null; ");
        // 7. new version / new APK / new binary -> restart daemons
        s.append("V=" + version + "; A=" + apk + "; ");
        s.append("if [ \"$(cat version 2>/dev/null)\" != \"$V\" ] || [ \"$(cat apk 2>/dev/null)\" != \"$A\" ]; then ");
        s.append(killPid("remapd")).append(killPid("stickd")).append("sleep 0.3; ");
        s.append("elif [ $NEW = 1 ]; then ").append(killPid("remapd")).append("sleep 0.3; fi; ");
        // 9. (before starting, so stickd's "STATUS started version=" reports it)
        s.append("echo " + version + " > version; echo \"$A\" > apk; ");
        // 8. start what is not alive; a running stickd re-reads changed confs
        s.append("if ! alive remapd; then setsid nohup " + DIR + "/remapd " + DIR + "/remap.conf </dev/null >/dev/null 2>&1 & ");
        s.append("i=0; while [ $i -lt 20 ] && ! alive remapd; do sleep 0.1; i=$((i+1)); done; fi; ");
        s.append("if alive stickd; then [ $CH = 1 ] && { " + Commands.act(Actions.RELOAD, null) + "; }; ");
        // stale pid file (e.g. from the previous boot): stickd's own single-instance check must never see it
        s.append("else rm -f " + DIR + "/stickd.pid; CLASSPATH=" + apk + " setsid nohup app_process /system/bin --nice-name=stickd com.clean.stickman.daemon.Stickd " + DIR
            + " </dev/null >/dev/null 2>&1 & ");
        s.append("i=0; while [ $i -lt 40 ] && ! alive stickd; do sleep 0.1; i=$((i+1)); done; fi; ");
        // 10. report
        s.append("R=-; alive remapd && R=$P; S=-; alive stickd && S=$P; echo REMAPD $R STICKD $S");
        // 11. what remapd made of the remote (found / limited / not found), for the Status page
        s.append("; " + RemoteState.REPORT);
        return s.toString();
    }

    /**
     * Master off: stop both daemons (remapd exiting releases the remote), then give back an owned stay-on and
     * clear a fade overlay (stickd's shutdown hook does not run on SIGTERM). Prints STOPPED.
     */
    public static String stop() {
        return killPid("remapd") + killPid("stickd") + RELEASE_STAYON_FADE + "echo STOPPED";
    }

    /** True when an ensure result reports BOTH daemons alive (only then is the boot marked ensured). */
    public static boolean bothRunning(String result) {
        return result != null && result.matches("(?s).*REMAPD \\d+ STICKD \\d+.*");
    }
}
