package com.clean.stickman.core;

/** Pure builders of shell command strings (run later as shell uid 2000 via /system/bin/sh). */
public final class Commands {
    private Commands() {}

    /** Pointer mode on / off: remapd reads and removes the command file (written atomically). */
    public static String pointer(boolean on) {
        String f = EnsureScript.DIR + "/mouse.cmd";
        return "echo " + (on ? "on" : "off") + " > " + f + ".tmp && mv " + f + ".tmp " + f;
    }


    public static String screensaver(boolean on) {
        return Originals.setting("secure", "screensaver_enabled", on ? "1" : "0");
    }

    public static String sleepTimeout(long ms) {
        return Originals.setting("secure", "sleep_timeout", Long.toString(ms));
    }

    public static String screenOffTimeout(long ms) {
        return Originals.setting("system", "screen_off_timeout", Long.toString(ms));
    }

    public static String animation(float scale) {
        String v = Float.toString(scale);
        return Originals.setting("global", "window_animation_scale", v) + "; "
            + Originals.setting("global", "transition_animation_scale", v) + "; "
            + Originals.setting("global", "animator_duration_scale", v);
    }

    /** Printed instead of changing DNS when stickd (which runs the DNS safety check) is not alive. */
    public static final String DNS_REFUSED = "DNS_REFUSED";

    /**
     * Turning private DNS off is always allowed. Setting a resolver is allowed only while stickd is alive, since
     * stickd's dns_check is the safety net that reverts a resolver that breaks the internet; otherwise prints
     * {@link #DNS_REFUSED} and changes nothing.
     */
    public static String privateDns(String host) {
        if (host == null) return Originals.setting("global", "private_dns_mode", "off");
        if (!host.matches("[A-Za-z0-9.-]+")) return null;
        return "P=$(cat " + Originals.DIR + "/stickd.pid 2>/dev/null); "
            + "if [ -n \"$P\" ] && " + EnsureScript.isProc("P", "stickd") + "; then "
            + Originals.setting("global", "private_dns_mode", "hostname") + "; "
            + Originals.setting("global", "private_dns_specifier", host) + "; "
            + act(Actions.DNS_CHECK, null) + "; "
            + "else echo " + DNS_REFUSED + " stickd not running; fi";
    }

    public static String privacy(boolean limit) {
        String on = limit ? "1" : "0";
        String off = limit ? "0" : "1"; // value for 'enabled' style keys
        return Originals.setting("secure", "amz_limit_ad_tracking", on) + "; "
            + Originals.setting("secure", "limit_ad_tracking", on) + "; "
            + Originals.setting("secure", "advertisingIdApp:interestBasedAds:value", off) + "; "
            + Originals.setting("secure", "USAGE_METRICS_UPLOAD_ENABLED", off) + "; "
            // the three switches under Amazon's Settings > Preferences > Privacy, plus marketing metrics
            + Originals.setting("secure", "settingsApp:deviceUsageData:value", off) + "; "
            + Originals.setting("secure", "settingsApp:appUsageData:value", off) + "; "
            + Originals.setting("secure", "usage_metrics_marketing_enabled", off) + "; "
            + Originals.setting("secure", "pact_usage_collection_enabled", off) + "; "
            + Originals.setting("global", "com.amazon.tv.settings.featured_item_video_enabled", off);
    }

    /**
     * Location off journals the current mode (3, "high accuracy", on the stick) and sets 0; on gives the journaled
     * value back, or sets 3 when Stickman never changed it.
     */
    public static String location(boolean on) {
        if (!on) return Originals.setting("secure", "location_mode", "0");
        return Originals.giveBack("setting", "secure", "location_mode")
            + "; [ \"$(settings get secure location_mode)\" = 0 ] && settings put secure location_mode 3; true";
    }

    /** Keep asleep (on) / wake (off): see {@link Starve}. null for invalid names and, when on, protected packages. */
    public static String keepAsleep(String pkg, boolean on) {
        return on ? Starve.apply(pkg, false) : Starve.wake(pkg);
    }

    /** null for invalid names, and when disabling a protected pkg (enabling is always allowed). */
    public static String debloat(String pkg, boolean disable) {
        if (!Originals.validPkg(pkg) || (disable && Catalog.isProtected(pkg))) return null;
        if (disable) return Originals.disablePkg(pkg);
        return "pm enable " + pkg;
    }

    public static String setTime(long epochMs) {
        return "cmd alarm set-time " + epochMs + "; "
            + "settings put global ntp_server time.google.com; "
            + "settings put global auto_time 1";
    }

    /** Clears the global proxy only when its port has no listener. Prints PROXY kept|cleared|none. */
    public static String clearDeadProxy() {
        return "p=$(settings get global http_proxy); "
            + "case \"$p\" in ''|null|:0) echo PROXY none;; *) "
            + "port=${p##*:}; hex=; "
            + "case \"$port\" in ''|*[!0-9]*) ;; *) hex=$(printf '%04X' \"$port\" 2>/dev/null);; esac; "
            + "if [ -z \"$hex\" ]; then echo PROXY kept; "
            + "elif cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -q \":$hex [0-9A-Fa-f:]* 0A \"; then echo PROXY kept; "
            + "else settings put global http_proxy :0; echo PROXY cleared; fi;; esac";
    }

    public static String act(String id, String arg) {
        String line = (arg == null || arg.length() == 0) ? id : id + " " + arg;
        // Spool protocol (see Spool): write <name>.tmp then rename to <name>.act; <name> = <epoch-ms>-<pid>-<random>.
        return "d=" + Spool.DIR + "; mkdir -p $d; t=$(date +%s%N); t=${t%??????}; n=$d/$t-$$-$RANDOM; "
            + "echo " + Originals.q(line) + " > $n.tmp && mv $n.tmp $n.act";
    }

    /**
     * Media key to the active session. {@code cmd media_session dispatch} is Android 11+; older systems (Fire OS 5-7) have
     * the standalone {@code media} command with the same verbs.
     */
    public static String media(String verb) {
        return "cmd media_session dispatch " + verb + " 2>/dev/null || media dispatch " + verb;
    }
}
