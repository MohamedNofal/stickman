package com.clean.stickman.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Static catalogues: protected packages, debloat candidates, hidden-screen shortcuts, DNS presets. */
public final class Catalog {
    private Catalog() {}

    /** Packages (exact) that must never be disabled. Prefix rules live in {@link #isProtected}. */
    public static final Set<String> NEVER_DISABLE = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
        "com.amazon.tv.launcher", "com.amazon.firehomestarter", "com.amazon.vizzini",
        "com.amazon.tv.notificationcenter", "com.amazon.hedwig", "com.amazon.tv.livetv",
        "com.clean.netfilter", "com.wolf.firelauncher", "com.clean.stickman",
        "com.amazon.tv.settings", "com.amazon.device.software.ota")));

    /** True for exact NEVER_DISABLE entries plus the settings / ota / input / remote / bluetooth families. */
    public static boolean isProtected(String pkg) {
        if (pkg == null) return true;
        if (NEVER_DISABLE.contains(pkg)) return true;
        if (pkg.startsWith("com.amazon.tv.settings")) return true;
        if (pkg.startsWith("com.amazon.device.software.ota")) return true;
        String p = pkg.toLowerCase();
        return p.contains("input") || p.contains("remote") || p.contains("bluetooth");
    }

    /** {pkg, label, why}; installed on the Fire TV 4K Max (ags.app and alexashopping were absent, dropped). */
    public static final String[][] DEBLOAT_RECOMMENDED = {
        {"com.amazon.tv.alexaalerts", "Alexa alerts", "Alexa reminders and alarm pop-ups"},
        {"com.amazon.tv.releasenotes", "Release notes", "Post-update 'what's new' screens"},
        {"com.amazon.ods.kindleconnect", "Kindle connect", "Kindle sync service that runs in the background"},
        {"com.amazon.tv.csapp", "Customer service", "Help app; rarely used"},
    };

    /** {label, target}: an intent action, or a component (contains '/') started with am start -n. */
    public static final String[][] SHORTCUTS = {
        {"Display & Sounds", "com.amazon.device.settings.action.DISPLAY_AND_SOUNDS"},
        {"Network", "android.settings.WIFI_SETTINGS"},
        {"Applications", "com.amazon.device.settings.action.APPLICATIONS"},
        {"My Fire TV", "com.amazon.device.settings.action.DEVICE"},
        {"Remotes & Bluetooth", "com.amazon.device.settings.action.CONTROLLERS"},
        {"Developer options", "com.amazon.device.settings.action.APPLICATION_DEVELOPMENT_SETTINGS"},
        {"Inputs", "com.amazon.tv.inputpreference.action.LAUNCH_INPUTS"},
        {"Quick settings", "com.amazon.tv.quicksettings.ui.action.SETTING_ID_QS"},
        {"Profiles", "com.amazon.tv.action.LAUNCH_PROFILE_PICKER"},
        {"Ambient / screensaver", "com.amazon.device.settings.action.SCREENSAVERS"},
        {"AV sync", "com.amazon.device.settings.action.AVSYNC_TUNING_ACTION"},
        {"Notifications", "com.amazon.tv.action.NOTIFICATION_CENTER"},
        {"Troubleshooting", "com.amazon.firetv.troubleshooting/.ui.MainActivity"},
        {"Privacy & advertising", "com.amazon.device.settings.action.PRIVACY_SETTINGS"},
        {"Data monitor", "com.amazon.device.settings.action.DATA_MONITOR"},
    };

    /** {label, host}; NextDNS needs a custom id (see {@link #nextDns}); Off has a null host. */
    public static final String[][] DNS_PRESETS = {
        {"AdGuard", "dns.adguard-dns.com"},
        {"AdGuard Family", "family.adguard-dns.com"},
        {"Cloudflare Security", "security.cloudflare-dns.com"},
        {"Cloudflare Family", "family.cloudflare-dns.com"},
        {"NextDNS (custom ID)", "nextdns"},
        {"Off", null},
    };

    /** null unless id is a plain NextDNS profile id. */
    public static String nextDns(String id) {
        if (id == null || !id.matches("[A-Za-z0-9-]{1,63}")) return null;
        return id + ".dns.nextdns.io";
    }
}
