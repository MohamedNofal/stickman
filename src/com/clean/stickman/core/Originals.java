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

    static boolean validPkg(String pkg) {
        return pkg != null && pkg.matches("[A-Za-z0-9._]+");
    }

    /** null when pkg is not a plain package name. */
    public static String disablePkg(String pkg) {
        if (!validPkg(pkg)) return null;
        return record("^pkg " + pkg + "$", "pkg " + pkg) + "pm disable-user --user 0 " + pkg;
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
        return EnsureScript.RELEASE_STAYON_FADE + restoreScript(FILE);
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
            + "esac; done; rm -f \"$F\"";
    }
}
