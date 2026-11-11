package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Amazon's own "uninstallable system apps" (Silk, Amazon Music, miniTV, ...): Fire OS refuses pm uninstall for them,
 * but Amazon's app page has an Uninstall button that removes the downloaded app and keeps only its tiny built-in
 * placeholder. Stickman reads Amazon's list from Settings' provider (readable by shell), shows each app's state and
 * size, and opens that page; the user presses Uninstall there. Verified on Fire OS 8.1.8.5 (Silk: 175 MB -> stub).
 */
public final class AmazonUninstall {
    private AmazonUninstall() {}

    public static final String URI = "content://com.amazon.provider.SystemAppUninstall/uninstallSupportedApps";

    /**
     * Prints one "UAPP pkg state kb" line per app Amazon lists, then "UAPP_END". state: full (the downloaded app is
     * installed, kb = its size), stub (only the built-in placeholder is left: already removed), missing.
     */
    public static final String CMD =
        "for p in $(content query --uri " + URI + " 2>/dev/null | sed -n 's/.*uninstallSupportedAppsKey=\\([A-Za-z0-9._]*\\).*/\\1/p'); do "
        + "path=$(pm path \"$p\" 2>/dev/null | head -n 1); path=${path#package:}; "
        + "case \"$path\" in /data/*) s=$(du -sk \"${path%/*}\" 2>/dev/null | cut -f1); echo \"UAPP $p full ${s:-0}\";; "
        + "'') echo \"UAPP $p missing 0\";; *) echo \"UAPP $p stub 0\";; esac; done; echo UAPP_END";

    public static final class App {
        public final String pkg, state;
        public final long kb;
        App(String pkg, String state, long kb) { this.pkg = pkg; this.state = state; this.kb = kb; }
        public boolean installed() { return "full".equals(state); }
    }

    public static List<App> parse(String out) {
        List<App> r = new ArrayList<>();
        if (out == null) return r;
        for (String l : out.split("\n")) {
            String[] p = l.trim().split("\\s+");
            if (p.length != 4 || !p[0].equals("UAPP") || !Originals.validPkg(p[1])) continue;
            if (!p[2].matches("full|stub|missing") || !p[3].matches("\\d{1,12}")) continue;
            r.add(new App(p[1], p[2], Long.parseLong(p[3])));
        }
        return r;
    }

    /** True when the list command ran to the end (an empty list then means Amazon lists nothing here). */
    public static boolean complete(String out) { return out != null && out.contains("UAPP_END"); }

    /**
     * Amazon system apps carrying a downloaded update in /data/app: one "UPD pkg kb disabled(0|1)" line each, then
     * "UPD_END". Removing the update ({@link #removeUpdate}) returns the app to its small built-in version.
     */
    public static final String UPDATES_CMD =
        "D=$(pm list packages -d 2>/dev/null); pm list packages -f -s 2>/dev/null | sed -n 's/^package:\\(\\/data\\/app\\/.*\\)\\/base\\.apk=\\(com\\.amazon\\.[A-Za-z0-9._]*\\)$/\\2 \\1/p' "
        + "| while read p d; do s=$(du -sk \"$d\" 2>/dev/null | cut -f1); "
        + "if echo \"$D\" | grep -qx \"package:$p\"; then x=1; else x=0; fi; echo \"UPD $p ${s:-0} $x\"; done; echo UPD_END";

    public static final class Update {
        public final String pkg; public final long kb; public final boolean disabled;
        Update(String pkg, long kb, boolean disabled) { this.pkg = pkg; this.kb = kb; this.disabled = disabled; }
    }

    /** Biggest first. */
    public static List<Update> parseUpdates(String out) {
        List<Update> r = new ArrayList<>();
        if (out == null) return r;
        for (String l : out.split("\n")) {
            String[] p = l.trim().split("\\s+");
            if (p.length != 4 || !p[0].equals("UPD") || !Originals.validPkg(p[1]) || !p[2].matches("\\d{1,12}") || !p[3].matches("[01]")) continue;
            r.add(new Update(p[1], Long.parseLong(p[2]), p[3].equals("1")));
        }
        java.util.Collections.sort(r, (x, y) -> Long.compare(y.kb, x.kb));   // List.sort is API 24
        return r;
    }

    /**
     * Back to the built-in version and kept disabled (the same steps as Free up space); prints "UPDATE_REMOVED" when
     * the downloaded copy is gone. Only for disabled, unprotected packages; null otherwise.
     */
    public static String removeUpdate(String pkg) {
        if (!Originals.validPkg(pkg) || Catalog.isProtected(pkg)) return null;
        return "pm uninstall-system-updates " + pkg + " >/dev/null 2>&1; pm disable-user --user 0 " + pkg + " >/dev/null 2>&1; "
            + "pm path " + pkg + " 2>/dev/null | head -n 1 | grep -q '^package:/data/app/' && echo UPDATE_KEPT || echo UPDATE_REMOVED";
    }

    /** Opens Amazon's app page for pkg (its Uninstall button is there); null for a bad name. */
    public static String open(String pkg) {
        if (!Originals.validPkg(pkg)) return null;
        return "am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:" + pkg;
    }
}
