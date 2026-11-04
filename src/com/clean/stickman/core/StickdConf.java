package com.clean.stickman.core;

import java.util.LinkedHashSet;
import java.util.Set;

/** stickd.conf: key=value lines; unknown keys and bad values are ignored. */
public final class StickdConf {
    public boolean onLeave;
    public int intervalMin;
    public Set<String> whitelist = new LinkedHashSet<>();
    public Set<String> blacklist = new LinkedHashSet<>();
    public boolean stayAwakeMedia;

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
                default: break;
            }
        }
        return c;
    }

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
}
