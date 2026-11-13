package com.clean.stickman.core.cmd;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * What each supported Android version lacks, as the shell user. Measured on Android 7.1 and 9 emulators and a real
 * Fire OS 8 stick (probe of 2026-11-12), plus Fire OS 5/6/7 firmware files and AOSP source for the rest (notes:
 * fireos5-6.md, fireos7-8.md). On API 22 the basic tools come from the toybox Stickman ships (EnsureScript.tools),
 * so only Android's own commands can be missing there.
 */
public final class Tools {
    private Tools() {}

    /** Applets of the bundled toybox (native/toybox/mini.conf): present on every version once setup has run. */
    public static final Set<String> BUNDLED = set("base64 setsid nohup timeout pidof stat du df free uptime sed tr cut sort "
        + "head tail touch printf sync cat grep mkdir rm mv cp chmod echo sleep kill ls ps id date wc xargs find cmp tac seq "
        + "test true false uniq tee basename dirname readlink env top pgrep pkill");

    /** Missing on Android 5.1 (Fire OS 5), beyond what the bundled toybox covers. */
    static final Set<String> MISSING_22 = set("cmd cmd:appops cmd:package cmd:alarm cmd:media_session device_config sm sm:fstrim awk getconf "
        + "pm:disable-user pm:enable pm:suspend pm:unsuspend pm:uninstall-system-updates pm:trim-caches "
        + "am:set-standby-bucket am:get-standby-bucket am:send-trim-memory "
        + "dumpsys:deviceidle dumpsys:thermalservice dumpsys:activity-lru "
        + "settings:private_dns logcat:epoch");

    /** Missing on Android 7.1 (Fire OS 6). */
    static final Set<String> MISSING_25 = set("device_config awk getconf "
        + "pm:suspend pm:unsuspend pm:uninstall-system-updates pm:trim-caches "
        + "am:set-standby-bucket am:get-standby-bucket "
        + "cmd:alarm cmd:media_session sm:fstrim "
        + "dumpsys:thermalservice dumpsys:deviceidle-sys-whitelist dumpsys:activity-lru "
        + "settings:private_dns proc:net-tcp appops:RUN_ANY_IN_BACKGROUND");

    /** Missing on Android 9 (Fire OS 7). */
    static final Set<String> MISSING_28 = set("device_config getconf pm:suspend pm:unsuspend cmd:media_session "
        + "dumpsys:thermalservice dumpsys:activity-lru");

    /** Missing on Android 11 (Fire OS 8). */
    static final Set<String> MISSING_30 = set("media");

    /**
     * Every other name a registry entry may use: tools present on all four versions (Android's own commands that
     * exist since 5.1, per AOSP source and the firmware lists). A name that is in none of the sets is a typo or a
     * tool nobody checked, and the registry test refuses it.
     */
    static final Set<String> EVERYWHERE = set("am:broadcast am:force-stop am:kill am:start app_process appops "
        + "content:query dumpsys:activity dumpsys:activity-processes dumpsys:media_session dumpsys:package dumpsys:power "
        + "dumpsys:procstats dumpsys:wifi getprop input ln log logcat logcat:-G ping pm:grant pm:install pm:list "
        + "pm:path pm:uninstall screencap settings svc svc:power ip");

    /** Whether a tool name is known at all (bundled, present everywhere, or listed as missing somewhere). */
    public static boolean known(String tool) {
        return BUNDLED.contains(tool) || EVERYWHERE.contains(tool) || MISSING_22.contains(tool) || MISSING_25.contains(tool)
            || MISSING_28.contains(tool) || MISSING_30.contains(tool);
    }

    static Set<String> set(String s) { return new HashSet<>(Arrays.asList(s.trim().split("\\s+"))); }

    /** The tools known to be missing on this API level (the nearest supported version at or below it). */
    public static Set<String> missing(int api) {
        return api >= 30 ? MISSING_30 : api >= 28 ? MISSING_28 : api >= 25 ? MISSING_25 : MISSING_22;
    }

    /** First tool of the list that is missing on this API level, or null when all are there. */
    public static String firstMissing(String tools, int api) {
        if (tools == null || tools.trim().isEmpty()) return null;
        Set<String> m = missing(api);
        for (String t : tools.trim().split("\\s+")) {
            if (m.contains(t)) return t;
            int c = t.indexOf(':');
            if (c > 0 && m.contains(t.substring(0, c))) return t;   // the whole tool is missing
        }
        return null;
    }
}
