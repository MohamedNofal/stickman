package com.clean.stickman.core.cmd;

import com.clean.stickman.core.Actions;
import com.clean.stickman.core.Originals;

/**
 * Stick settings the app changes from its pages: animations (Speed), private DNS and the privacy switches (Privacy),
 * re-enabling a debloated app, the clock and the stuck-proxy fix (Settings). Writes go through Originals.setting,
 * which journals the old value for Restore defaults (that journal line also uses mkdir, grep and echo).
 */
public final class SettingsCmds {
    private SettingsCmds() {}

    public static final Cmd ANIMATION = Cmd.def("settings.animation", "Speed", 22, "settings");
    public static String animation(float scale) {
        String v = Float.toString(scale);
        return Originals.setting("global", "window_animation_scale", v) + "; "
            + Originals.setting("global", "transition_animation_scale", v) + "; "
            + Originals.setting("global", "animator_duration_scale", v);
    }

    /** One line: "ANIM &lt;window_animation_scale&gt;"; parsed by LiveValues. */
    public static final Cmd ANIMATION_READ = Cmd.def("settings.animation_read", "Speed", 22, "settings echo");
    public static String animationRead() {
        return "echo ANIM $(settings get global window_animation_scale)";
    }

    /** Printed instead of changing DNS when stickd (which runs the DNS safety check) is not alive. */
    public static final String DNS_REFUSED = "DNS_REFUSED";

    /**
     * Turning private DNS off is always allowed. Setting a resolver is allowed only while stickd is alive, since
     * stickd's dns_check is the safety net that reverts a resolver that breaks the internet; otherwise prints
     * {@link #DNS_REFUSED} and changes nothing. null for an invalid host. Private DNS is Android 9+.
     */
    public static final Cmd PRIVATE_DNS = Cmd.def("settings.private_dns", "Privacy", 28, "settings:private_dns cat tr head mkdir date echo mv");
    public static String privateDns(String host) {
        if (host == null) return Originals.setting("global", "private_dns_mode", "off");
        if (!host.matches("[A-Za-z0-9.-]+")) return null;
        return "P=$(cat " + Originals.DIR + "/stickd.pid 2>/dev/null); "
            + "if [ -n \"$P\" ] && " + SetupCmds.isProc("P", "stickd") + "; then "
            + Originals.setting("global", "private_dns_mode", "hostname") + "; "
            + Originals.setting("global", "private_dns_specifier", host) + "; "
            + RemoteCmds.act(Actions.DNS_CHECK, null) + "; "
            + "else echo " + DNS_REFUSED + " stickd not running; fi";
    }

    /** Ad tracking, usage data and marketing switches (Amazon's Settings > Preferences > Privacy, plus marketing metrics). */
    public static final Cmd PRIVACY = Cmd.def("settings.privacy_switches", "Privacy", 22, "settings");
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
    public static final Cmd LOCATION = Cmd.def("settings.location", "Privacy", 22, "settings grep head cut mv");
    public static String location(boolean on) {
        if (!on) return Originals.setting("secure", "location_mode", "0");
        return Originals.giveBack("setting", "secure", "location_mode")
            + "; [ \"$(settings get secure location_mode)\" = 0 ] && settings put secure location_mode 3; true";
    }

    /** Re-enables an app Debloat disabled (disabling is Originals.disablePkg, which journals it). */
    public static final Cmd ENABLE_APP = Cmd.def("settings.enable_app", "Debloat", 25, "pm:enable");
    public static String enableApp(String pkg) {
        return "pm enable " + pkg;
    }

    /** Fix clock: sets the time and turns network time on with Google's NTP server. */
    public static final Cmd SET_TIME = Cmd.def("settings.set_time", "Settings", 28, "cmd:alarm settings");
    public static String setTime(long epochMs) {
        return "cmd alarm set-time " + epochMs + "; "
            + "settings put global ntp_server time.google.com; "
            + "settings put global auto_time 1";
    }

    /**
     * Clears the global proxy only when its port has no listener. Prints PROXY kept|cleared|none. Android 7 hides
     * /proc/net/tcp from shell: "can't tell" must not look like "nothing listens", so there it always keeps the proxy.
     */
    public static final Cmd CLEAR_DEAD_PROXY = Cmd.def("settings.clear_dead_proxy", "Settings", 22, "settings printf head cat grep");
    public static String clearDeadProxy() {
        return "p=$(settings get global http_proxy); "
            + "case \"$p\" in ''|null|:0) echo PROXY none;; *) "
            + "port=${p##*:}; hex=; "
            + "case \"$port\" in ''|*[!0-9]*) ;; *) hex=$(printf '%04X' \"$port\" 2>/dev/null);; esac; "
            + "if [ -z \"$hex\" ] || ! head -c 1 /proc/net/tcp >/dev/null 2>&1; then echo PROXY kept; "
            + "elif cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -q \":$hex [0-9A-Fa-f:]* 0A \"; then echo PROXY kept; "
            + "else settings put global http_proxy :0; echo PROXY cleared; fi;; esac";
    }
}
