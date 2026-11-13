package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.clean.stickman.core.cmd.CecCmds;
import com.clean.stickman.core.cmd.DebloatCmds;
import com.clean.stickman.core.cmd.SpeedCmds;

/**
 * Keep asleep / Starve (appops), the after-update check and fix, and the HDMI-CEC switch: policy and parsers. The
 * commands are in {@link SpeedCmds}, {@link DebloatCmds} and {@link CecCmds}.
 *
 * Verified on the stick (AFTMA08C15, Fire OS 8 / API 30, 2026-11-05): all four appops below set and reset cleanly.
 * Standby buckets are a no-op there (usagestats mAppIdleEnabled=false: set-standby-bucket leaves the bucket at 10),
 * so they are no longer used; old "bucket" journal lines are still given back on wake and by Restore.
 * cmd netpolicy restrict-background only applies to metered networks, so it is not used either.
 */
public final class Starve {
    private Starve() {}

    public static final String[] STARVE_OPS = SpeedCmds.STARVE_OPS;

    /** null for invalid names and protected packages (home, Net Filter, Stickman, updates, ...). */
    public static String apply(String pkg, boolean starve) {
        if (!Originals.validPkg(pkg) || Catalog.isProtected(pkg)) return null;
        return SpeedCmds.keepAsleep(pkg, starve);
    }

    /** Gives back every journaled appop (and legacy bucket) of pkg and drops its journal lines. Always allowed. */
    public static String wake(String pkg) { return SpeedCmds.wake(pkg); }

    /** pkg -> true when starved (any op beyond RUN_ANY_IN_BACKGROUND), false when only kept asleep. Journal order. */
    public static Map<String, Boolean> parseList(String out) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        if (out == null) return m;
        for (String line : out.split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 1 || !Originals.validPkg(p[0])) continue;
            boolean st = p.length > 1 && p[1].matches("[A-Z_]+") && !p[1].equals("RUN_ANY_IN_BACKGROUND");
            Boolean was = m.get(p[0]);
            m.put(p[0], (was != null && was) || st);
        }
        return m;
    }

    /**
     * After-update check: for every journaled "pkg" that is still installed but no longer disabled, prints
     * "DRIFT pkg P"; for every journaled appop that is no longer "ignore", prints "DRIFT appop P OP". Ends with
     * "CHECKED n" (n = journaled packages + appops).
     */
    public static String checkScript() { return checkScript(Originals.FILE); }

    public static String checkScript(String path) { return DebloatCmds.check(path); }

    /** {kind, pkg, op-or-null} per DRIFT line; kinds other than pkg/appop and bad names are dropped. */
    public static List<String[]> parseDrift(String out) {
        List<String[]> r = new ArrayList<>();
        if (out == null) return r;
        for (String line : out.split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 3 || !p[0].equals("DRIFT") || !Originals.validPkg(p[2])) continue;
            if (p[1].equals("pkg")) r.add(new String[] { "pkg", p[2], null });
            else if (p[1].equals("appop") && p.length > 3 && p[3].matches("[A-Z_]+")) r.add(new String[] { "appop", p[2], p[3] });
        }
        return r;
    }

    /** -1 when there was no CHECKED line. */
    public static int checkedCount(String out) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("CHECKED (\\d+)").matcher(out == null ? "" : out);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Switches every drifted item off again (protected packages are skipped); prints "FIXED n". null if nothing. */
    public static String fixScript(List<String[]> drift) {
        List<String[]> ok = new ArrayList<>();
        for (String[] d : drift) if (!Catalog.isProtected(d[1])) ok.add(d);
        return DebloatCmds.fix(ok);
    }

    /** HDMI-CEC: lets the stick and the TV turn each other on and switch inputs. */
    public static String hdmiCec(boolean on) { return CecCmds.set(on); }

    public static final String HDMI_READ = CecCmds.READ_CMD;

    /** true = on (1 or unset: Android's default is on), false = 0, null = no CEC line. */
    public static Boolean parseCec(String out) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("CEC (\\S+)").matcher(out == null ? "" : out);
        String v = null;
        while (m.find()) v = m.group(1);
        return v == null ? null : !"0".equals(v);
    }
}
