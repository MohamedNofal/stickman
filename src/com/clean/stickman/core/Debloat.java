package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Debloat page model: reads enabled Amazon packages + the ones Stickman disabled; restore scripts. */
public final class Debloat {
    public static final String SEP = "---stickman-ours---";
    /** Enabled com.amazon packages, then the pkg names Stickman recorded as disabled (originals "pkg" lines). */
    public static final String READ_CMD = "pm list packages -e com.amazon; echo " + SEP + "; " + recorded("pkg");

    /** Enabled com.amazon.* packages. */
    public final Set<String> enabled = new LinkedHashSet<>();
    /** Packages Stickman disabled (from the originals journal, sorted). */
    public final List<String> disabledByUs = new ArrayList<>();

    public static Debloat parse(String out) {
        Debloat d = new Debloat();
        if (out == null) return d;
        boolean ours = false;
        for (String raw : out.split("\n")) {
            String l = raw.trim();
            if (l.equals(SEP)) { ours = true; continue; }
            if (ours) {
                if (Originals.validPkg(l) && !d.disabledByUs.contains(l)) d.disabledByUs.add(l);
            } else if (l.startsWith("package:")) {
                String p = l.substring(8).trim();
                if (p.startsWith("com.amazon.") && Originals.validPkg(p)) d.enabled.add(p);
            }
        }
        return d;
    }

    /** Catalog.DEBLOAT_RECOMMENDED entries ({pkg,label,why}) that are installed and enabled. */
    public List<String[]> recommended() {
        List<String[]> out = new ArrayList<>();
        for (String[] r : Catalog.DEBLOAT_RECOMMENDED) if (enabled.contains(r[0]) && !Catalog.isProtected(r[0])) out.add(r);
        return out;
    }

    /** Every other enabled com.amazon.* package, minus protected ones, sorted. */
    public List<String> advanced() {
        Set<String> rec = new LinkedHashSet<>();
        for (String[] r : Catalog.DEBLOAT_RECOMMENDED) rec.add(r[0]);
        List<String> out = new ArrayList<>();
        for (String p : enabled) if (!rec.contains(p) && !Catalog.isProtected(p)) out.add(p);
        Collections.sort(out);
        return out;
    }

    /** Second field of every journal line of the given kind ("pkg", "bucket", ...), unique. */
    public static String recorded(String kind) {
        return "grep '^" + kind + " ' " + Originals.FILE + " 2>/dev/null | cut -d' ' -f2 | sort -u";
    }

    /** Re-enables only the journal's "pkg" lines and removes just those lines (other originals stay). */
    public static String restoreAllScript() { return restoreAllScript(Originals.FILE); }

    public static String restoreAllScript(String path) {
        // no 'exit': callers append a refresh command to the same shell
        return "F=" + Originals.q(path) + "; n=0; if [ -f \"$F\" ]; then "
            + "for p in $(grep '^pkg ' \"$F\" | cut -d' ' -f2); do "
            + "case \"$p\" in *[!A-Za-z0-9._]*) continue;; esac; pm enable \"$p\" >/dev/null; n=$((n+1)); done; "
            + "grep -v '^pkg ' \"$F\" > \"$F.tmp\"; if [ -s \"$F.tmp\" ]; then mv \"$F.tmp\" \"$F\"; else rm -f \"$F.tmp\" \"$F\"; fi; "
            + "fi; echo RESTORED $n";
    }

    /** Re-enables one package and drops its "pkg" journal line; null for invalid names (re-enabling is always allowed). */
    public static String enableOne(String pkg) { return enableOne(pkg, Originals.FILE); }

    public static String enableOne(String pkg, String path) {
        if (!Originals.validPkg(pkg)) return null;
        return "F=" + Originals.q(path) + "; pm enable " + pkg + " >/dev/null; "
            + "if [ -f \"$F\" ]; then grep -v -x 'pkg " + pkg + "' \"$F\" > \"$F.tmp\"; "
            + "if [ -s \"$F.tmp\" ]; then mv \"$F.tmp\" \"$F\"; else rm -f \"$F.tmp\" \"$F\"; fi; fi; echo ENABLED " + pkg;
    }
}
