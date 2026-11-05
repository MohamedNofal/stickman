package com.clean.stickman.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Freezes Fire OS system updates. Fire OS refuses to disable its OTA packages ("Cannot disable a protected
 * package"), but shell may suspend them and disable the forced-updater companion, so updates stop being
 * downloaded and installed. The state is re-applied by the boot ensure script.
 */
public final class Ota {
    private Ota() {}

    /** OTA packages, in the order they are frozen. All three are present on the Fire TV 4K Max. */
    public static final String[] PACKAGES = {
        "com.amazon.device.software.ota",
        "com.amazon.device.software.ota.override",
        "com.amazon.tv.forcedotaupdater.v2",
    };

    /** Printed before each package's state line by {@link #stateCmd()}. */
    public static final String MARK = "---stickman-ota---";

    /**
     * Shell: for every package print {@link #MARK} + its name, then the first "User 0:" line of dumpsys with the
     * spaces stripped (so "suspended=true" / "enabled=3" can be matched without parsing the whole dump).
     */
    public static String stateCmd() {
        StringBuilder s = new StringBuilder();
        for (String p : PACKAGES) {
            s.append("echo ").append(MARK).append(' ').append(p).append("; ");
            s.append("dumpsys package ").append(p).append(" 2>/dev/null | grep -m1 'User 0:' | tr -d ' '; ");
        }
        return s.toString();
    }

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

    /** Suspends every OTA package and disables the forced updater; prints OTA_FROZEN when announce. */
    public static String freezeScript() { return freezeScript(true); }

    public static String freezeScript(boolean announce) {
        StringBuilder s = new StringBuilder();
        for (String p : PACKAGES) {
            // suspend works on Fire OS protected packages; disable-user only succeeds on the companion
            s.append("pm suspend --user 0 ").append(p).append(" >/dev/null 2>&1; ");
            s.append("pm disable-user --user 0 ").append(p).append(" >/dev/null 2>&1; ");
        }
        if (announce) s.append("echo OTA_FROZEN");
        return s.toString();
    }

    /** Unsuspends and re-enables every OTA package; prints OTA_THAWED. */
    public static String thawScript() {
        StringBuilder s = new StringBuilder();
        for (String p : PACKAGES) {
            s.append("pm unsuspend --user 0 ").append(p).append(" >/dev/null 2>&1; ");
            s.append("pm enable ").append(p).append(" >/dev/null 2>&1; ");
        }
        s.append("echo OTA_THAWED");
        return s.toString();
    }

    /** Line for the boot ensure script: re-freeze when the owner froze OTA, empty otherwise. */
    public static String ensureLine(boolean frozen) {
        return frozen ? freezeScript(false) : "";
    }
}
