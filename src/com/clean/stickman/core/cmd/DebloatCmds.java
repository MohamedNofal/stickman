package com.clean.stickman.core.cmd;

import com.clean.stickman.core.B64;

import java.util.List;

/**
 * Debloat page: the app list, disable / re-enable (journaled), the after-update check and fix, Free up space, Clear
 * caches, and Amazon's own uninstall list and downloaded updates. Policy (which packages are protected) stays in
 * core.Debloat / core.Starve / core.AmazonUninstall; the builders here take names those already checked.
 */
public final class DebloatCmds {
    private DebloatCmds() {}

    public static final String SEP = "---stickman-ours---";

    /** Enabled com.amazon packages, then the pkg names Stickman recorded as disabled (parser: core.Debloat.parse). */
    public static final Cmd READ = Cmd.def("debloat.read", "Debloat", 22, "pm:list grep");

    public static final String READ_CMD = "pm list packages -e com.amazon; echo " + SEP + "; " + JournalCmds.recorded("pkg");

    /** cmd, then {@link #READ_CMD} so the page refreshes in the same round trip; null when cmd is null. */
    public static String thenRead(String cmd) { return cmd == null ? null : cmd + "; " + READ_CMD; }

    /** Disable every given package (journaled like single disables, see {@link JournalCmds#disablePkg}). */
    public static final Cmd DISABLE_ALL = Cmd.def("debloat.disable_all", "Debloat", 25, "pm:disable-user grep");

    /** "DISABLED_ALL <n>" with how many are now disabled; pkgs must be valid and unprotected. null if none. */
    public static String disableAll(List<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String p : pkgs) {
            String c = JournalCmds.disablePkg(p);
            if (c == null) continue;
            sb.append("{ ").append(c).append("; } | grep -q disabled-user && n=$((n+1)); ");
            n++;
        }
        if (n == 0) return null;
        return "n=0; " + sb + "echo DISABLED_ALL $n";
    }

    /** Re-enable every journaled "pkg" (pm enable: missing on 22). Counts attempts, not successes. */
    public static final Cmd RESTORE_ALL = Cmd.def("debloat.restore_all", "Debloat", 25, "pm:enable grep");

    /** Re-enables only the journal's "pkg" lines and removes just those lines (other originals stay). */
    public static String restoreAll(String path) {
        // no 'exit': callers append a refresh command to the same shell
        return "F=" + JournalCmds.q(path) + "; n=0; if [ -f \"$F\" ]; then "
            + "for p in $(grep '^pkg ' \"$F\" | cut -d' ' -f2); do "
            + "case \"$p\" in *[!A-Za-z0-9._]*) continue;; esac; pm enable \"$p\" >/dev/null; n=$((n+1)); done; "
            + "grep -v '^pkg ' \"$F\" > \"$F.tmp\"; if [ -s \"$F.tmp\" ]; then mv \"$F.tmp\" \"$F\"; else rm -f \"$F.tmp\" \"$F\"; fi; "
            + "fi; echo RESTORED $n";
    }

    public static final Cmd ENABLE_ONE = Cmd.def("debloat.enable_one", "Debloat", 25, "pm:enable grep");

    /** Re-enables one package and drops its "pkg" journal line; null for invalid names. */
    public static String enableOne(String pkg, String path) {
        if (!JournalCmds.validPkg(pkg)) return null;
        return "F=" + JournalCmds.q(path) + "; pm enable " + pkg + " >/dev/null; "
            + "if [ -f \"$F\" ]; then grep -v -x 'pkg " + pkg + "' \"$F\" > \"$F.tmp\"; "
            + "if [ -s \"$F.tmp\" ]; then mv \"$F.tmp\" \"$F\"; else rm -f \"$F.tmp\" \"$F\"; fi; fi; echo ENABLED " + pkg;
    }

    /**
     * After-update check (parser: core.Starve.parseDrift / checkedCount). The pkg half needs only pm list; the appop
     * half needs cmd:appops (25), but appop lines are only journaled by features that need 28.
     */
    public static final Cmd CHECK = Cmd.def("debloat.after_update_check", "Debloat", 22, "pm:list grep");

    public static String check(String path) {
        return "F=" + JournalCmds.q(path) + "; n=0; if [ -f \"$F\" ]; then "
            + "A=$(pm list packages 2>/dev/null); D=$(pm list packages -d 2>/dev/null); "
            + "for p in $(grep '^pkg ' \"$F\" | cut -d' ' -f2 | sort -u); do "
            + "case \"$p\" in *[!A-Za-z0-9._]*) continue;; esac; n=$((n+1)); "
            + "echo \"$A\" | grep -qx \"package:$p\" || continue; "
            + "echo \"$D\" | grep -qx \"package:$p\" || echo \"DRIFT pkg $p\"; done; "
            + "for l in $(grep '^appop ' \"$F\" | cut -d' ' -f2,3 | tr ' ' ':' | sort -u); do "
            + "p=${l%%:*}; op=${l#*:}; case \"$p$op\" in *[!A-Za-z0-9._]*) continue;; esac; n=$((n+1)); "
            + "echo \"$A\" | grep -qx \"package:$p\" || continue; "
            + "cmd appops get $p $op 2>/dev/null | grep -q ignore || echo \"DRIFT appop $p $op\"; done; "
            + "fi; echo \"CHECKED $n\"";
    }

    /** Switch drifted items off again: pm disable-user (25) for packages, cmd appops (25) for appops. */
    public static final Cmd FIX = Cmd.def("debloat.after_update_fix", "Debloat", 25, "pm:disable-user cmd:appops grep");

    /**
     * drift: {kind, pkg, op-or-null} items already filtered for protected packages. Prints "FIXED n"; null when no
     * item gives a command.
     */
    public static String fix(List<String[]> drift) {
        StringBuilder s = new StringBuilder("n=0; ");
        int k = 0;
        for (String[] d : drift) {
            String c = "pkg".equals(d[0]) ? "{ " + JournalCmds.disablePkg(d[1]) + "; } | grep -q disabled-user"
                : JournalCmds.appop(d[1], d[2], "ignore");
            if (c == null) continue;
            s.append("{ ").append(c).append("; } && n=$((n+1)); ");
            k++;
        }
        return k == 0 ? null : s.append("echo FIXED $n").toString();
    }

    /** Runs script detached; every line it prints becomes "STATUS <tag> <line>" in logcat; prints started. */
    static String detached(String script, String file, String tag, String started) {
        String f = JournalCmds.DIR + "/" + file;
        return "mkdir -p " + JournalCmds.DIR + "; echo " + B64.encode(script)
            + " | base64 -d > " + f + " && setsid nohup sh -c 'sh " + f
            + " | while read l; do log -t Stickd -p i \"STATUS " + tag + " $l\"; done' </dev/null >/dev/null 2>&1 & echo " + started;
    }

    /**
     * Free up space: needs pm uninstall-system-updates (28). Below that every uninstall fails silently and the result
     * reads "nothing to free". The detached form also needs "log" (not probed on old versions).
     */
    public static final Cmd FREE_SPACE = Cmd.def("debloat.free_space", "Storage", 28,
        "pm:uninstall-system-updates pm:disable-user pm:list pm:path df base64 setsid nohup log");

    /**
     * For every journaled (Stickman-disabled) package that is disabled and has updates in /data/app, uninstall the
     * updates and disable it again. Prints "FREED <kB> <n>".
     */
    public static String freeSpace(String path) {
        return "F=" + JournalCmds.q(path) + "; n=0; b=$(df /data | tail -n 1 | tr -s ' ' | cut -d' ' -f4); "
            + "if [ -f \"$F\" ]; then D=$(pm list packages -d 2>/dev/null); "
            + "for p in $(grep '^pkg ' \"$F\" | cut -d' ' -f2 | sort -u); do "
            + "case \"$p\" in *[!A-Za-z0-9._]*) continue;; esac; "
            + "echo \"$D\" | grep -qx \"package:$p\" || continue; "
            + "pm path $p 2>/dev/null | head -n 1 | grep -q '^package:/data/app/' || continue; "
            // pm's exit code is not reliable here (non-zero after "Success"): count by where the app lives afterwards
            + "pm uninstall-system-updates $p >/dev/null 2>&1; "
            + "pm path $p 2>/dev/null | head -n 1 | grep -q '^package:/data/app/' || n=$((n+1)); "
            + "pm disable-user --user 0 $p >/dev/null 2>&1; done; fi; "
            + "a=$(df /data | tail -n 1 | tr -s ' ' | cut -d' ' -f4); echo \"FREED $((a-b)) $n\"";
    }

    /** Detached (it can outlast the bridge's 20 s timeout): "STATUS freed FREED <kB> <n>" in logcat; prints FREEING. */
    public static String freeSpaceDetached() { return detached(freeSpace(JournalCmds.FILE), "freespace.sh", "freed", "FREEING"); }

    /**
     * Clear caches: pm trim-caches (28 by the tools table). sm fstrim is also 28 and silenced. Detached like
     * {@link #FREE_SPACE}.
     */
    public static final Cmd CLEAR_CACHES = Cmd.def("debloat.clear_caches", "Storage", 28,
        "pm:trim-caches sm:fstrim df base64 setsid nohup log");

    /** Prints "CLEARED <kB>". */
    public static String clearCaches() {
        return "b=$(df /data | tail -n 1 | tr -s ' ' | cut -d' ' -f4); pm trim-caches 999G >/dev/null 2>&1; "
            + "sm fstrim >/dev/null 2>&1; sleep 1; a=$(df /data | tail -n 1 | tr -s ' ' | cut -d' ' -f4); echo \"CLEARED $((a-b))\"";
    }

    /** Reports "STATUS cleared CLEARED <kB>"; prints CLEARING. */
    public static String clearCachesDetached() { return detached(clearCaches(), "clearcaches.sh", "cleared", "CLEARING"); }

    public static final String AMAZON_URI = "content://com.amazon.provider.SystemAppUninstall/uninstallSupportedApps";

    /**
     * Amazon's "uninstallable system apps" list (parser: core.AmazonUninstall.parse). The tools exist on every
     * version, but the provider is only known on Fire OS 8 (verified on 8.1.8.5); elsewhere the query prints nothing
     * and the page says Amazon lists no apps. Declared 30 for that reason, not because of a tool.
     */
    public static final Cmd AMAZON_LIST = Cmd.def("debloat.amazon_list", "Debloat", 30, "content:query sed pm:path du");

    public static final String AMAZON_LIST_CMD =
        "for p in $(content query --uri " + AMAZON_URI + " 2>/dev/null | sed -n 's/.*uninstallSupportedAppsKey=\\([A-Za-z0-9._]*\\).*/\\1/p'); do "
        + "path=$(pm path \"$p\" 2>/dev/null | head -n 1); path=${path#package:}; "
        + "case \"$path\" in /data/*) s=$(du -sk \"${path%/*}\" 2>/dev/null | cut -f1); echo \"UAPP $p full ${s:-0}\";; "
        + "'') echo \"UAPP $p missing 0\";; *) echo \"UAPP $p stub 0\";; esac; done; echo UAPP_END";

    /** Opens Amazon's app page for pkg (its Uninstall button is there). */
    public static final Cmd AMAZON_OPEN = Cmd.def("debloat.amazon_open", "Debloat", 22, "am:start");

    /** null for a bad name. */
    public static String amazonOpen(String pkg) {
        if (!JournalCmds.validPkg(pkg)) return null;
        return "am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:" + pkg;
    }

    /**
     * Amazon system apps carrying a downloaded update in /data/app (parser: core.AmazonUninstall.parseUpdates).
     * Listing works everywhere; removing ({@link #REMOVE_UPDATE}) needs 28.
     */
    public static final Cmd UPDATES_LIST = Cmd.def("debloat.updates_list", "Debloat", 22, "pm:list sed du");

    public static final String UPDATES_LIST_CMD =
        "D=$(pm list packages -d 2>/dev/null); pm list packages -f -s 2>/dev/null | sed -n 's/^package:\\(\\/data\\/app\\/.*\\)\\/base\\.apk=\\(com\\.amazon\\.[A-Za-z0-9._]*\\)$/\\2 \\1/p' "
        + "| while read p d; do s=$(du -sk \"$d\" 2>/dev/null | cut -f1); "
        + "if echo \"$D\" | grep -qx \"package:$p\"; then x=1; else x=0; fi; echo \"UPD $p ${s:-0} $x\"; done; echo UPD_END";

    public static final Cmd REMOVE_UPDATE = Cmd.def("debloat.remove_update", "Debloat", 28,
        "pm:uninstall-system-updates pm:disable-user pm:path");

    /** Back to the built-in version and kept disabled; prints UPDATE_REMOVED or UPDATE_KEPT. pkg already checked. */
    public static String removeUpdate(String pkg) {
        return "pm uninstall-system-updates " + pkg + " >/dev/null 2>&1; pm disable-user --user 0 " + pkg + " >/dev/null 2>&1; "
            + "pm path " + pkg + " 2>/dev/null | head -n 1 | grep -q '^package:/data/app/' && echo UPDATE_KEPT || echo UPDATE_REMOVED";
    }

    /** "Switch off & remove": journaled disable, then {@link #removeUpdate}, then {@link #READ_CMD}. */
    public static final Cmd SWITCH_OFF_REMOVE_UPDATE = Cmd.def("debloat.switch_off_remove_update", "Debloat", 28,
        "pm:uninstall-system-updates pm:disable-user pm:path pm:list");

    public static String switchOffAndRemoveUpdate(String pkg) {
        return JournalCmds.disablePkg(pkg) + "; " + removeUpdate(pkg) + "; " + READ_CMD;
    }
}
