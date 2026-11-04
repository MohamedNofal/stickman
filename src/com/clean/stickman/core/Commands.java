package com.clean.stickman.core;

/** Pure builders of shell command strings (run later as shell uid 2000 via /system/bin/sh). */
public final class Commands {
    private Commands() {}

    static final String FIFO = Originals.DIR + "/act.fifo";

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

    public static String privateDns(String host) {
        if (host == null) return Originals.setting("global", "private_dns_mode", "off");
        if (!host.matches("[A-Za-z0-9.-]+")) return null;
        return Originals.setting("global", "private_dns_mode", "hostname") + "; "
            + Originals.setting("global", "private_dns_specifier", host) + "; "
            + act(Actions.DNS_CHECK, null);
    }

    public static String privacy(boolean limit) {
        String on = limit ? "1" : "0";
        String off = limit ? "0" : "1"; // value for 'enabled' style keys
        return Originals.setting("secure", "amz_limit_ad_tracking", on) + "; "
            + Originals.setting("secure", "limit_ad_tracking", on) + "; "
            + Originals.setting("secure", "advertisingIdApp:interestBasedAds:value", off) + "; "
            + Originals.setting("secure", "USAGE_METRICS_UPLOAD_ENABLED", off) + "; "
            + Originals.setting("global", "com.amazon.tv.settings.featured_item_video_enabled", off);
    }

    public static String keepAsleep(String pkg, boolean on) {
        if (!Originals.validPkg(pkg)) return null;
        if (on) {
            return Originals.bucket(pkg, "rare") + "; " + Originals.appop(pkg, "RUN_ANY_IN_BACKGROUND", "ignore");
        }
        String f = Originals.FILE;
        return "b=$(grep '^bucket " + pkg + " ' " + f + " 2>/dev/null | head -n 1 | cut -d' ' -f3); "
            + "case \"$b\" in ''|null) b=active;; esac; am set-standby-bucket " + pkg + " $b; "
            + "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND default; "
            + "if [ -f " + f + " ]; then grep -v -e '^bucket " + pkg + " ' -e '^appop " + pkg + " ' " + f + " > " + f + ".tmp; mv " + f + ".tmp " + f + "; fi";
    }

    /** null when pkg is protected. */
    public static String debloat(String pkg, boolean disable) {
        if (!Originals.validPkg(pkg) || Catalog.isProtected(pkg)) return null;
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
        return "echo " + Originals.q(line) + " > " + FIFO;
    }
}
