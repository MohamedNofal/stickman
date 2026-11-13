package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.clean.stickman.core.cmd.DebloatCmds;
import com.clean.stickman.core.cmd.JournalCmds;

/** Debloat page model: reads enabled Amazon packages + the ones Stickman disabled. Commands: {@link DebloatCmds}. */
public final class Debloat {
    public static final String SEP = DebloatCmds.SEP;
    /** Enabled com.amazon packages, then the pkg names Stickman recorded as disabled (originals "pkg" lines). */
    public static final String READ_CMD = DebloatCmds.READ_CMD;

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

    /**
     * One script disabling every given recommended package (journaled like single disables), then
     * "DISABLED_ALL <n>" with how many are now disabled. Protected/invalid entries are skipped. null if none.
     */
    public static String disableAllScript(List<String[]> rec) {
        List<String> pkgs = new ArrayList<>();
        for (String[] r : rec) if (Originals.validPkg(r[0]) && !Catalog.isProtected(r[0])) pkgs.add(r[0]);
        return DebloatCmds.disableAll(pkgs);
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
    public static String recorded(String kind) { return JournalCmds.recorded(kind); }

    /** Re-enables only the journal's "pkg" lines and removes just those lines (other originals stay). */
    public static String restoreAllScript() { return restoreAllScript(Originals.FILE); }

    public static String restoreAllScript(String path) { return DebloatCmds.restoreAll(path); }

    /** Re-enables one package and drops its "pkg" journal line; null for invalid names (re-enabling is always allowed). */
    public static String enableOne(String pkg) { return enableOne(pkg, Originals.FILE); }

    public static String enableOne(String pkg, String path) { return DebloatCmds.enableOne(pkg, path); }
}
