package com.clean.stickman.core.cmd;

/**
 * The restore-defaults journal ("originals"): every change Stickman makes to a system value first records the old
 * value as one line of {@link #FILE} ("setting ns key old", "dc ns key old", "pkg p", "bucket p old", "appop p OP"),
 * then sets the new one; {@link #restore} replays the journal backwards. Callers: core.Originals (and through it
 * Commands, Lean, Starve, Telemetry, Debloat).
 */
public final class JournalCmds {
    private JournalCmds() {}

    public static final String DIR = "/data/local/tmp/stickman";
    public static final String FILE = DIR + "/originals";

    /** Single-quotes s for the shell. */
    public static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** A plain package name (also the only thing allowed into pm / am / cmd appops arguments). */
    public static boolean validPkg(String pkg) {
        return pkg != null && pkg.matches("[A-Za-z0-9._]+");
    }

    /** Appends lineExpr to the journal unless a line matching guardRegex is already there. */
    private static String record(String guardRegex, String lineExpr) {
        return "mkdir -p " + DIR + "; grep -q '" + guardRegex + "' " + FILE + " 2>/dev/null || echo \"" + lineExpr + "\" >> " + FILE + "; ";
    }

    public static final Cmd SETTING = Cmd.def("journal.setting", "Journal", 22, "settings mkdir grep");

    /** settings put, journaling the original as "setting ns key old". */
    public static String setting(String ns, String key, String value) {
        return record("^setting " + ns + " " + key + " ", "setting " + ns + " " + key + " $(settings get " + ns + " " + key + ")")
            + "settings put " + ns + " " + key + " " + value;
    }

    /**
     * Below API 30 device_config is missing: the journal line is still written (with an empty old value, since the
     * record runs before the put) and the put fails.
     */
    public static final Cmd DCONFIG = Cmd.def("journal.dconfig", "Journal", 30, "device_config mkdir grep");

    /** device_config put, journaling the original as "dc ns key old" (restored with put, or delete when null). */
    public static String dconfig(String ns, String key, String value) {
        return record("^dc " + ns + " " + key + " ", "dc " + ns + " " + key + " $(device_config get " + ns + " " + key + ")")
            + "device_config put " + ns + " " + key + " " + value;
    }

    /** "settings delete" was not probed on old versions (assumed present: it is in Android 5.1's settings tool). */
    public static final Cmd GIVE_BACK_SETTING = Cmd.def("journal.give_back_setting", "Journal", 22, "settings grep");

    /**
     * Gives back one journaled "setting" value (the original, or delete when it was unset) and drops its line.
     * Does nothing when the key was never changed by Stickman.
     */
    public static String giveBackSetting(String ns, String key) { return giveBack("setting", ns, key); }

    /** Only acts when a "dc" line exists, so it is a harmless no-op below 30 unless such a line was journaled there. */
    public static final Cmd GIVE_BACK_DC = Cmd.def("journal.give_back_dc", "Journal", 30, "device_config grep");

    /** Same for a journaled "dc" (device_config) value. */
    public static String giveBackDc(String ns, String key) { return giveBack("dc", ns, key); }

    /** kind "dc" gives back a device_config value, anything else a setting. */
    public static String giveBack(String kind, String a, String b) {
        String g = "^" + kind + " " + a + " " + b + " ";
        String put = kind.equals("dc") ? "device_config put " : "settings put ";
        String del = kind.equals("dc") ? "device_config delete " : "settings delete ";
        return "if grep -q '" + g + "' " + FILE + " 2>/dev/null; then "
            + "v=$(grep '" + g + "' " + FILE + " | head -n 1 | cut -d' ' -f4-); "
            + "if [ -z \"$v\" ] || [ \"$v\" = null ]; then " + del + a + " " + b + "; else " + put + a + " " + b + " \"$v\"; fi; "
            + "grep -v '" + g + "' " + FILE + " > " + FILE + ".tmp; mv " + FILE + ".tmp " + FILE + "; fi";
    }

    public static final Cmd DISABLE_PKG = Cmd.def("journal.disable_pkg", "Debloat", 25, "pm:disable-user grep");

    /**
     * Disables pkg and journals it ONLY if pm reports "disabled-user" (Fire OS refuses some packages, e.g. csapp:
     * those must not show up as "disabled by Stickman"). Prints pm's output. null when pkg is not a plain name.
     */
    public static String disablePkg(String pkg) {
        if (!validPkg(pkg)) return null;
        return "o=$(pm disable-user --user 0 " + pkg + " 2>&1); echo \"$o\"; "
            + "if echo \"$o\" | grep -q disabled-user; then " + record("^pkg " + pkg + "$", "pkg " + pkg) + "fi";
    }

    /** Standby buckets (no longer used by any feature: a no-op on Fire OS 8; kept for old journal lines). */
    public static final Cmd BUCKET = Cmd.def("journal.bucket", "Journal", 28, "am:get-standby-bucket am:set-standby-bucket");

    public static String bucket(String pkg, String bucket) {
        if (!validPkg(pkg) || bucket == null || !bucket.matches("[a-z_]+")) return null;
        return record("^bucket " + pkg + " ", "bucket " + pkg + " $(am get-standby-bucket " + pkg + ")")
            + "am set-standby-bucket " + pkg + " " + bucket;
    }

    /**
     * Any appop: "cmd appops" is API 25+. Which ops exist depends on the version (RUN_ANY_IN_BACKGROUND is 28+): the
     * features using this declare their own minApi. The line is journaled before the set, whether or not it works.
     */
    public static final Cmd APPOP = Cmd.def("journal.appop", "Journal", 25, "cmd:appops mkdir grep");

    /** Records that the appop op of pkg was touched (restored with 'default'), then sets it. */
    public static String appop(String pkg, String op, String mode) {
        if (!validPkg(pkg) || op == null || !op.matches("[A-Z_]+") || mode == null || !mode.matches("[a-z]+")) return null;
        return record("^appop " + pkg + " " + op + "$", "appop " + pkg + " " + op)
            + "cmd appops set " + pkg + " " + op + " " + mode;
    }

    public static final Cmd RECORDED = Cmd.def("journal.recorded", "Journal", 22, "grep cut sort");

    /** Second field of every journal line of the given kind ("pkg", "bucket", ...), unique. */
    public static String recorded(String kind) {
        return "grep '^" + kind + " ' " + FILE + " 2>/dev/null | cut -d' ' -f2 | sort -u";
    }

    /**
     * Replays the journal backwards and deletes it. Declared with the tools it cannot work without (settings; sed is
     * the fallback when tac is missing). Each other branch only runs for journal lines a feature wrote, and needs
     * that feature's tool: pkg -> pm:enable (25), appop -> cmd:appops (25), bucket -> am:set-standby-bucket (28),
     * dc -> device_config (30). Exception: Lean memory / Fewer wake-ups write "dc" lines below 30 too (see
     * {@link #DCONFIG}), and their device_config replay then fails silently.
     */
    public static final Cmd RESTORE = Cmd.def("journal.restore", "Restore defaults", 22, "settings sed rm");

    /** The replay script for the journal at path. */
    public static String restore(String path) {
        return "F=" + q(path) + "; [ -f \"$F\" ] || exit 0; "
            + "(tac \"$F\" 2>/dev/null || sed '1!G;h;$!d' \"$F\") | while read k a b c; do "
            + "case $k in "
            + "setting) if [ \"$c\" = null ] || [ -z \"$c\" ]; then settings delete $a $b; else settings put $a $b \"$c\"; fi;; "
            + "pkg) pm enable $a;; "
            + "bucket) [ \"$b\" != null ] && [ -n \"$b\" ] && am set-standby-bucket $a $b;; "
            + "appop) cmd appops set $a $b default;; "
            + "dc) if [ \"$c\" = null ] || [ -z \"$c\" ]; then device_config delete $a $b; else device_config put $a $b \"$c\"; fi;; "
            + "esac; done; rm -f \"$F\"";
    }

    /**
     * Restore defaults: first releaseStayOn (the stay-on/fade release, built by the ensure script's owner), then
     * Deep sleep off ({@link SpeedCmds#DOZE_OFF}: only acts when doze.sh exists, i.e. on 25+), then the replay.
     * Same requirements as {@link #RESTORE}.
     */
    public static final Cmd RESTORE_DEFAULTS = Cmd.def("journal.restore_defaults", "Restore defaults", 22, "settings sed rm");

    public static String restoreDefaults(String releaseStayOn) {
        return releaseStayOn + SpeedCmds.DOZE_OFF + "; " + restore(FILE);
    }
}
