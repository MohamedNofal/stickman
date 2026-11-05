package com.clean.stickman.core;

/** Builds shell commands that journal original values before changing them, and the replay script. */
public final class Originals {
    private Originals() {}

    public static final String DIR = "/data/local/tmp/stickman";
    public static final String FILE = DIR + "/originals";

    public static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String record(String guardRegex, String lineExpr) {
        return "mkdir -p " + DIR + "; grep -q '" + guardRegex + "' " + FILE + " 2>/dev/null || echo \"" + lineExpr + "\" >> " + FILE + "; ";
    }

    public static String setting(String ns, String key, String value) {
        return record("^setting " + ns + " " + key + " ", "setting " + ns + " " + key + " $(settings get " + ns + " " + key + ")")
            + "settings put " + ns + " " + key + " " + value;
    }

    /** device_config put, journaling the original as "dc ns key old" (restored with put, or delete when null). */
    public static String dconfig(String ns, String key, String value) {
        return record("^dc " + ns + " " + key + " ", "dc " + ns + " " + key + " $(device_config get " + ns + " " + key + ")")
            + "device_config put " + ns + " " + key + " " + value;
    }

    /**
     * Gives back one journaled "setting"/"dc" value (the original, or delete when it was unset) and drops its line.
     * Does nothing when the key was never changed by Stickman.
     */
    public static String giveBack(String kind, String a, String b) {
        String g = "^" + kind + " " + a + " " + b + " ";
        String put = kind.equals("dc") ? "device_config put " : "settings put ";
        String del = kind.equals("dc") ? "device_config delete " : "settings delete ";
        return "if grep -q '" + g + "' " + FILE + " 2>/dev/null; then "
            + "v=$(grep '" + g + "' " + FILE + " | head -n 1 | cut -d' ' -f4-); "
            + "if [ -z \"$v\" ] || [ \"$v\" = null ]; then " + del + a + " " + b + "; else " + put + a + " " + b + " \"$v\"; fi; "
            + "grep -v '" + g + "' " + FILE + " > " + FILE + ".tmp; mv " + FILE + ".tmp " + FILE + "; fi";
    }

    static boolean validPkg(String pkg) {
        return pkg != null && pkg.matches("[A-Za-z0-9._]+");
    }

    /**
     * Disables pkg and journals it ONLY if pm reports "disabled-user" (Fire OS refuses some packages, e.g. csapp:
     * those must not show up as "disabled by Stickman"). Prints pm's output. null when pkg is not a plain name.
     */
    public static String disablePkg(String pkg) {
        if (!validPkg(pkg)) return null;
        return "o=$(pm disable-user --user 0 " + pkg + " 2>&1); echo \"$o\"; "
            + "if echo \"$o\" | grep -q disabled-user; then " + record("^pkg " + pkg + "$", "pkg " + pkg) + "fi";
    }

    public static String bucket(String pkg, String bucket) {
        if (!validPkg(pkg) || bucket == null || !bucket.matches("[a-z_]+")) return null;
        return record("^bucket " + pkg + " ", "bucket " + pkg + " $(am get-standby-bucket " + pkg + ")")
            + "am set-standby-bucket " + pkg + " " + bucket;
    }

    /** Records that the RUN_ANY_IN_BACKGROUND appop of pkg was touched (restored with 'default'), then sets it. */
    public static String appop(String pkg, String op, String mode) {
        if (!validPkg(pkg) || op == null || !op.matches("[A-Z_]+") || mode == null || !mode.matches("[a-z]+")) return null;
        return record("^appop " + pkg + " " + op + "$", "appop " + pkg + " " + op)
            + "cmd appops set " + pkg + " " + op + " " + mode;
    }

    /**
     * Shell script: give back a stay-on owned by stickd (also the documented after-uninstall recovery), then
     * replay the journal in reverse and delete it.
     */
    public static String restoreScript() {
        return EnsureScript.RELEASE_STAYON_FADE + Lean.DOZE_OFF + "; " + restoreScript(FILE);
    }

    /** Same, with an explicit journal path (host tests). */
    public static String restoreScript(String path) {
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
}
