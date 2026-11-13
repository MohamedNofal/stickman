package com.clean.stickman.core;

import java.util.LinkedHashMap;
import java.util.Map;

import com.clean.stickman.core.cmd.OtaCmds;

/**
 * Freezes Fire OS system updates. Fire OS refuses to disable its OTA packages ("Cannot disable a protected
 * package"), but shell may suspend them and disable the forced-updater companion, so updates stop being
 * downloaded and installed. The state is re-applied by the boot ensure script. Commands: {@link OtaCmds}.
 */
public final class Ota {
    private Ota() {}

    /** OTA packages, in the order they are frozen. All three are present on the Fire TV 4K Max. */
    public static final String[] PACKAGES = OtaCmds.PACKAGES;

    /** Printed before each package's state line by {@link #stateCmd()}. */
    public static final String MARK = OtaCmds.MARK;

    /** {@link OtaCmds#state}: per package the MARK line and its first "User 0:" dumpsys line, spaces stripped. */
    public static String stateCmd() { return OtaCmds.state(); }

    /**
     * True when every package is frozen: suspended, or disabled-user (pm reports enabled=3). False when any is
     * still live or when the dump could not be read.
     */
    public static boolean parseFrozen(String out) {
        Map<String, Boolean> states = parse(out);
        if (states.size() < PACKAGES.length) return false;
        for (String p : PACKAGES) if (!Boolean.TRUE.equals(states.get(p))) return false;
        return true;
    }

    /** pkg -> frozen for the packages found in a {@link #stateCmd()} dump (missing packages are absent). */
    public static Map<String, Boolean> parse(String out) {
        Map<String, Boolean> states = new LinkedHashMap<>();
        if (out == null) return states;
        String pkg = null;
        for (String raw : out.split("\n")) {
            String l = raw.trim();
            if (l.startsWith(MARK)) {
                pkg = l.substring(MARK.length()).trim();
                if (!Originals.validPkg(pkg)) pkg = null;
            } else if (pkg != null && !l.isEmpty()) {
                states.put(pkg, l.contains("suspended=true") || l.contains("enabled=3"));
                pkg = null;
            }
        }
        return states;
    }

    /** Suspends every OTA package and disables the forced updater; prints OTA_FROZEN. */
    public static String freezeScript() { return OtaCmds.freeze(true); }

    public static String freezeScript(boolean announce) { return OtaCmds.freeze(announce); }

    /** Unsuspends and re-enables every OTA package; prints OTA_THAWED. */
    public static String thawScript() { return OtaCmds.thaw(); }

    /** Line for the boot ensure script: re-freeze when the owner froze OTA, empty otherwise. */
    public static String ensureLine(boolean frozen) {
        return frozen ? OtaCmds.freeze(false) : "";
    }
}
