package com.clean.stickman.core;

/** Builds shell commands that journal original values before changing them, and the replay script. */
public final class Originals {
    private Originals() {}

    public static final String DIR = "/data/local/tmp/stickman";
    public static final String FILE = DIR + "/originals";

    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String record(String guardRegex, String lineExpr) {
        return "mkdir -p " + DIR + "; grep -q '" + guardRegex + "' " + FILE + " 2>/dev/null || echo \"" + lineExpr + "\" >> " + FILE + "; ";
    }

    public static String setting(String ns, String key, String value) {
        return record("^setting " + ns + " " + key + " ", "setting " + ns + " " + key + " $(settings get " + ns + " " + key + ")")
            + "settings put " + ns + " " + key + " " + value;
    }

    public static String disablePkg(String pkg) {
        return record("^pkg " + pkg + "$", "pkg " + pkg) + "pm disable-user --user 0 " + pkg;
    }

    public static String bucket(String pkg, String bucket) {
        return record("^bucket " + pkg + " ", "bucket " + pkg + " $(am get-standby-bucket " + pkg + ")")
            + "am set-standby-bucket " + pkg + " " + bucket;
    }

    /** Records that the RUN_ANY_IN_BACKGROUND appop of pkg was touched (restored with 'default'), then sets it. */
    public static String appop(String pkg, String op, String mode) {
        return record("^appop " + pkg + " " + op + "$", "appop " + pkg + " " + op)
            + "cmd appops set " + pkg + " " + op + " " + mode;
    }

    /** Restore command for one journal line (null if unrecognised). */
    static String restoreLine(String line) {
        String[] t = line.trim().split(" ", 4);
        if (t.length < 2) return null;
        switch (t[0]) {
            case "setting":
                if (t.length < 3) return null;
                String old = t.length > 3 ? t[3] : "";
                if (old.equals("null") || old.length() == 0) return "settings delete " + t[1] + " " + t[2];
                return "settings put " + t[1] + " " + t[2] + " " + (old.matches("[A-Za-z0-9_.:/,@=+-]+") ? old : q(old));
            case "pkg": return "pm enable " + t[1];
            case "bucket":
                if (t.length < 3 || t[2].equals("null")) return null;
                return "am set-standby-bucket " + t[1] + " " + t[2];
            case "appop":
                if (t.length < 3) return null;
                return "cmd appops set " + t[1] + " " + t[2] + " default";
            default: return null;
        }
    }

    /** Literal replay (reverse order) of journal text, for tests and previews. */
    public static String restoreFrom(String text) {
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = lines.length - 1; i >= 0; i--) {
            String c = restoreLine(lines[i]);
            if (c != null) sb.append(c).append('\n');
        }
        return sb.toString();
    }

    /** Shell script: replay the journal in reverse, then delete it. */
    public static String restoreScript() {
        return "F=" + FILE + "; [ -f $F ] || exit 0; "
            + "(tac $F 2>/dev/null || sed '1!G;h;$!d' $F) | while read k a b c; do "
            + "case $k in "
            + "setting) if [ \"$c\" = null ] || [ -z \"$c\" ]; then settings delete $a $b; else settings put $a $b \"$c\"; fi;; "
            + "pkg) pm enable $a;; "
            + "bucket) [ \"$b\" != null ] && [ -n \"$b\" ] && am set-standby-bucket $a $b;; "
            + "appop) cmd appops set $a $b default;; "
            + "esac; done; rm -f $F";
    }
}
