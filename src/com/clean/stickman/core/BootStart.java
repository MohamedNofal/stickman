package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Apps that start at boot (BOOT_COMPLETED receivers), for the "Starts at boot" picker (command: cmd.SpeedCmds.BOOT_RECEIVERS). Pure. */
public final class BootStart {
    private BootStart() {}

    /**
     * Packages with a boot receiver that Keep asleep / Starve may touch: valid names, not Android's own, not
     * protected (home, settings, remotes, updates, Net Filter, Stickman). Third-party apps first, then Amazon's,
     * each A-Z.
     */
    public static List<String> parse(String out) {
        TreeSet<String> third = new TreeSet<>(), amazon = new TreeSet<>();
        if (out != null) for (String raw : out.split("\n")) {
            String l = raw.trim();
            int s = l.indexOf('/');
            if (s <= 0) continue;
            String p = l.substring(0, s);
            if (!Originals.validPkg(p) || p.equals("android") || !BgTime.starvable(p)) continue;
            (p.startsWith("com.amazon.") ? amazon : third).add(p);
        }
        List<String> r = new ArrayList<>(third);
        r.addAll(amazon);
        return r;
    }
}
