package com.clean.stickman.core;

import java.util.LinkedHashSet;
import java.util.Set;

/** stickd.conf: key=value lines; unknown keys and bad values are ignored. */
public final class StickdConf {
    /** The stickd.conf the app ships with (everything off). */
    public static final String DEFAULT_TEXT =
        "autokill.on_leave=0\nautokill.interval_min=0\nautokill.whitelist=\nautokill.blacklist=\nstayawake.media=0\n"
        + "trim.on_sleep=0\ntrim.interval_min=0\n";

    public boolean onLeave;
    public int intervalMin;
    public Set<String> whitelist = new LinkedHashSet<>();
    public Set<String> blacklist = new LinkedHashSet<>();
    public boolean stayAwakeMedia;
    /** Trim every background app when the TV goes to sleep / every N minutes (0 = off). */
    public boolean trimOnSleep;
    public int trimIntervalMin;

    public static StickdConf parse(String text) {
        StickdConf c = new StickdConf();
        if (text == null) return c;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            switch (k) {
                case "autokill.on_leave": c.onLeave = "1".equals(v); break;
                case "autokill.interval_min": c.intervalMin = intOr0(v); break;
                case "autokill.whitelist": c.whitelist = csv(v); break;
                case "autokill.blacklist": c.blacklist = csv(v); break;
                case "stayawake.media": c.stayAwakeMedia = "1".equals(v); break;
                case "trim.on_sleep": c.trimOnSleep = "1".equals(v); break;
                case "trim.interval_min": c.trimIntervalMin = intOr0(v); break;
                default: break;
            }
        }
        return c;
    }

    /**
     * Adds pkg to list ("whitelist" = protect / never close, "blacklist" = always kill). The lists are exclusive:
     * adding to one removes pkg from the other. Unknown list names and empty / non-package names are a no-op.
     * Returns this.
     */
    public StickdConf addTo(String list, String pkg) {
        if (!isPkg(pkg)) return this;
        if ("whitelist".equals(list)) { whitelist.add(pkg); blacklist.remove(pkg); }
        else if ("blacklist".equals(list)) { blacklist.add(pkg); whitelist.remove(pkg); }
        return this;
    }

    /** Removes pkg from list ("whitelist" / "blacklist"); unknown list names are a no-op. Returns this. */
    public StickdConf removeFrom(String list, String pkg) {
        if ("whitelist".equals(list)) whitelist.remove(pkg);
        else if ("blacklist".equals(list)) blacklist.remove(pkg);
        return this;
    }

    private static boolean isPkg(String p) { return p != null && p.matches("[A-Za-z0-9._]+"); }

    private static int intOr0(String v) {
        try { int n = Integer.parseInt(v); return n < 0 ? 0 : n; }
        catch (NumberFormatException e) { return 0; }
    }

    private static Set<String> csv(String v) {
        Set<String> s = new LinkedHashSet<>();
        for (String p : v.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) s.add(t);
        }
        return s;
    }

    /** key=value text (all keys, fixed order); list entries that aren't plain package names are dropped. */
    public String serialize() {
        return "autokill.on_leave=" + (onLeave ? 1 : 0) + "\n"
            + "autokill.interval_min=" + Math.max(0, intervalMin) + "\n"
            + "autokill.whitelist=" + join(whitelist) + "\n"
            + "autokill.blacklist=" + join(blacklist) + "\n"
            + "stayawake.media=" + (stayAwakeMedia ? 1 : 0) + "\n"
            + "trim.on_sleep=" + (trimOnSleep ? 1 : 0) + "\n"
            + "trim.interval_min=" + Math.max(0, trimIntervalMin) + "\n";
    }

    private static String join(Set<String> s) {
        StringBuilder b = new StringBuilder();
        for (String p : s) {
            if (!isPkg(p)) continue;
            if (b.length() > 0) b.append(',');
            b.append(p);
        }
        return b.toString();
    }
}
