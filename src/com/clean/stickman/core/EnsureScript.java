package com.clean.stickman.core;

import com.clean.stickman.core.cmd.SetupCmds;

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

    /** Delegates to {@link SetupCmds#releaseStayOnFade} (Originals, daemon/Stickd, daemon/Sleep). */
    public static final String RELEASE_STAYON_FADE = SetupCmds.releaseStayOnFade();

    /** Stickman's own tools (toybox and links to it), used only where the system lacks them: see {@link SetupCmds#toybox}. */
    public static final String BIN = DIR + "/bin";

    /** Assembles the ensure script from the pieces in {@link SetupCmds}, in this order. */
    public static String build(String apkPath, String libPath, int version, String remapConf, String stickdConf, boolean migrate) {
        StringBuilder s = new StringBuilder();
        // 0. tools first: everything below uses base64, setsid, cmp...
        s.append(SetupCmds.makeDir());
        s.append(SetupCmds.toybox(libPath.substring(0, libPath.lastIndexOf('/') + 1) + "libtoybox.so", version));
        // 1. dirs (act/ is the action spool, R5)
        s.append(SetupCmds.enterDir(Spool.DIR));
        s.append(SetupCmds.aliveFn());
        // 2. remapd binary
        s.append(SetupCmds.installRemapd(libPath));
        // 3. confs
        s.append(SetupCmds.writeConf("remap.conf", remapConf));
        s.append(SetupCmds.writeConf("stickd.conf", stickdConf));
        // 4. self-grants: logcat for the UI, overlay for the sleep fade
        s.append(SetupCmds.grantReadLogs());
        s.append(SetupCmds.grantOverlay());
        // 4b. Doze is reset by every reboot: re-apply it when the owner turned it on
        s.append(Lean.ENSURE_LINE);
        // 5. migration from KeyShift and wolfd
        if (migrate) {
            s.append(SetupCmds.migrateKeyShift());
            s.append(SetupCmds.retireWolfd());
        }
        // 6. retire Home Switch
        s.append(SetupCmds.retireHomeSwitch());
        // 7. new version / new APK / new binary -> restart daemons
        s.append(SetupCmds.restartIfNew(apkPath, version));
        // 9. (before starting, so stickd's "STATUS started version=" reports it)
        s.append(SetupCmds.recordVersion(version));
        // 8. start what is not alive; a running stickd re-reads changed confs
        s.append(SetupCmds.startRemapd());
        s.append(SetupCmds.startStickd(apkPath));
        // 10. report
        s.append(SetupCmds.report());
        // 11. what remapd made of the remote (found / limited / not found), for the Status page
        s.append("; ").append(SetupCmds.remoteReport());
        return s.toString();
    }

    /** Delegates to {@link SetupCmds#stop} (daemon/Sleep). */
    public static String stop() {
        return SetupCmds.stop();
    }

    /** True when an ensure result reports BOTH daemons alive (only then is the boot marked ensured). */
    public static boolean bothRunning(String result) {
        return result != null && result.matches("(?s).*REMAPD \\d+ STICKD \\d+.*");
    }
}
