package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keep asleep / Starve (appops), the after-update check and fix, and the HDMI-CEC switch.
 *
 * Verified on the stick (AFTMA08C15, Fire OS 8 / API 30, 2026-11-05): all four appops below set and reset cleanly.
 * Standby buckets are a no-op there (usagestats mAppIdleEnabled=false: set-standby-bucket leaves the bucket at 10),
 * so they are no longer used; old "bucket" journal lines are still given back on wake and by Restore.
 * cmd netpolicy restrict-background only applies to metered networks, so it is not used either.
 */
public final class Starve {
    private Starve() {}

    /** Keep asleep: can't start in the background (alarms, broadcasts, jobs) until opened. */
    public static final String[] ASLEEP_OPS = { "RUN_ANY_IN_BACKGROUND" };
    /** Starve: also no legacy background work, no wake locks, no foreground service to dodge kills. */
    public static final String[] STARVE_OPS = { "RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND", "WAKE_LOCK", "START_FOREGROUND" };

    /** null for invalid names and protected packages (home, Net Filter, Stickman, updates, ...). */
    public static String apply(String pkg, boolean starve) {
        if (!Originals.validPkg(pkg) || Catalog.isProtected(pkg)) return null;
        StringBuilder s = new StringBuilder();
        for (String op : starve ? STARVE_OPS : ASLEEP_OPS) s.append(Originals.appop(pkg, op, "ignore")).append("; ");
        // am kill only ends a process that is in the background, so a starved app is closed now, never the one in use
        if (starve) s.append("am kill ").append(pkg).append(" >/dev/null 2>&1; ");
        return s.append("true").toString();
    }

    /** Gives back every journaled appop (and legacy bucket) of pkg and drops its journal lines. Always allowed. */
    public static String wake(String pkg) {
        if (!Originals.validPkg(pkg)) return null;
        String g = pkg.replace(".", "\\.");
        return "F=" + Originals.FILE + "; if [ -f $F ]; then "
            + "for op in $(grep '^appop " + g + " ' $F | cut -d' ' -f3); do "
            + "case \"$op\" in *[!A-Z_]*) continue;; esac; cmd appops set " + pkg + " $op default; done; "
            + "b=$(grep '^bucket " + g + " ' $F | head -n 1 | cut -d' ' -f3); "
            + "case \"$b\" in ''|null|*[!a-z0-9_]*) ;; *) am set-standby-bucket " + pkg + " $b;; esac; "
            + "grep -v -e '^bucket " + g + " ' -e '^appop " + g + " ' $F > $F.tmp; mv $F.tmp $F; fi; "
            + "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND default";
    }

    /** Prints "pkg OP" (appop lines) and "pkg n" (legacy bucket lines) from the journal. */
    public static final String LIST_CMD = "grep -E '^(appop|bucket) ' " + Originals.FILE + " 2>/dev/null | cut -d' ' -f2,3";

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

    public static String checkScript(String path) {
        return "F=" + Originals.q(path) + "; n=0; if [ -f \"$F\" ]; then "
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
        StringBuilder s = new StringBuilder("n=0; ");
        int k = 0;
        for (String[] d : drift) {
            if (Catalog.isProtected(d[1])) continue;
            String c = "pkg".equals(d[0]) ? "{ " + Originals.disablePkg(d[1]) + "; } | grep -q disabled-user"
                : Originals.appop(d[1], d[2], "ignore");
            if (c == null) continue;
            s.append("{ ").append(c).append("; } && n=$((n+1)); ");
            k++;
        }
        return k == 0 ? null : s.append("echo FIXED $n").toString();
    }

    /** HDMI-CEC: lets the stick and the TV turn each other on and switch inputs. */
    public static String hdmiCec(boolean on) {
        return Originals.setting("global", "hdmi_control_enabled", on ? "1" : "0") + "; " + HDMI_READ;
    }

    public static final String HDMI_READ = "echo CEC $(settings get global hdmi_control_enabled)";

    /** true = on (1 or unset: Android's default is on), false = 0, null = no CEC line. */
    public static Boolean parseCec(String out) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("CEC (\\S+)").matcher(out == null ? "" : out);
        String v = null;
        while (m.find()) v = m.group(1);
        return v == null ? null : !"0".equals(v);
    }
}
