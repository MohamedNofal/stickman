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

    /** True for exact NEVER_DISABLE entries plus the settings / ota / input (incl. IME, key policy) / remote / bluetooth families. */
    public static boolean isProtected(String pkg) {
        if (pkg == null) return true;
        if (NEVER_DISABLE.contains(pkg)) return true;
        if (pkg.startsWith("com.amazon.tv.settings")) return true;
        if (pkg.startsWith("com.amazon.device.software.ota")) return true;
        String p = pkg.toLowerCase();
        if (p.startsWith("com.amazon.device.settings") || p.equals("com.amazon.venezia") || p.endsWith(".ime")
            || p.contains(".ime.") || p.endsWith(".arc") || p.equals("com.amazon.ssm") || p.equals("com.amazon.ssmsys")) return true;
        for (String f : CRITICAL_PARTS) if (p.contains(f)) return true;
        return false;
    }

    /**
     * Substrings of system-critical families (lower case): home/launch, settings, input, connectivity,
     * identity/account, rendering/runtime, installer/store, update/boot, storage/media providers, shared libraries.
     */
    static final String[] CRITICAL_PARTS = {
        // input / remote / key routing
        "input", "remote", "bluetooth", "keypolicy", "keymap", "tifobserver", "blepa", "autopair", "accessoryconfig",
        // home, setup, settings, profiles, parental controls
        "launcher", "home", "oobe", "settings", "profilepicker", "parentalcontrol", "uxcontroller",
        // connectivity
        "connectivity", "connection", "network", "wifi", "ffsprovision", "whisperjoin", "whisperlink", "cec", "routing",
        // identity / account / device messaging
        "identity", "account", "auth", "dcp", "dpcclient", "tcomm", "messaging", "sync",
        // registration / consent / sign-in gates and cloud plumbing the home + sign-in flow depends on
        "h2client", "fdra", "directivebroker", "consents", "turnstile",
        // rendering / runtimes / display
        "webview", "htmlruntime", "resolution", "calibration",
        // installers, store, app compatibility, intents
        "packageinstaller", "install", "marketplace", "compatibility", "appaccesskey", "intentsupport", "devicecontrol",
        // updates, boot, storage, providers
        "ota", "update", "upgrade", "bootsupport", "storage", "providers",
        // shared libraries / resource + overlay packages / contracts
        "library", ".sdk", "contracts", "resources", "ooberesource", "overlay", "metrics.api",
    };

    /**
     * {pkg, label, why}. All present on the Fire TV 4K Max (tests/fixtures/amazon_pkgs.txt). Curated from the
     * tv-tweak 4k-max-2023 profile (ads/promo/cruft/smarthome tiers) minus dumbtv's never-disable list, minus anything
     * {@link #isProtected} and minus its "aggressive" tier (Silk, install manager, remote config) which stays in Advanced.
     */
    public static final String[][] DEBLOAT_RECOMMENDED = {
        // ads & tracking
        {"com.amazon.tv.acr", "Content recognition", "Identifies what's on screen to target ads"},
        {"com.amazon.aca", "Content recognition helper", "Watches HDMI and app switches for ad targeting"},
        {"com.amazon.hybridadidservice", "Advertising ID", "Ad-tracking identifier service"},
        {"com.amazon.device.telemetry.emitter", "Telemetry", "Sends usage data to Amazon"},
        {"com.amazon.wirelessmetrics.service", "Wi-Fi metrics", "Reports network statistics to Amazon"},
        {"com.amazon.perfc", "Performance logger", "Background performance reporting"},
        {"com.amazon.perfcollection", "Performance collector", "Background performance reporting"},
        {"com.amazon.dp.logger", "Device logger", "Background usage logging"},
        // promos
        {"com.amazon.tv.ftvambient", "Ambient screensaver", "Photo screensaver; the biggest app (~100 MB)"},
        {"com.amazon.ftv.screensaver", "Screensaver", "Amazon screensaver host"},
        {"com.amazon.sneakpeek", "Sneak peek", "Autoplaying trailers on the Amazon home"},
        {"com.amazon.media.recommendations", "Recommendations", "Feeds sponsored rows on the Amazon home"},
        {"com.amazon.shoptv.client", "Shopping", "Amazon shopping on TV"},
        {"com.amazon.shoptv.firetv.client", "Shopping (Fire TV)", "Amazon shopping on TV"},
        {"com.amazon.minitv.android.app", "miniTV", "Amazon miniTV promo app"},
        {"com.amazon.gamehub", "Game hub", "Games promo page"},
        {"com.amazon.bueller.music", "Amazon Music", "Amazon Music app"},
        {"com.amazon.bueller.photos", "Amazon Photos", "Amazon Photos app (~27 MB)"},
        // leftovers
        {"com.amazon.tv.alexaalerts", "Alexa alerts", "Alexa reminders and alarm pop-ups"},
        {"com.amazon.tv.alexanotifications", "Alexa notifications", "Alexa notification pop-ups"},
        {"com.amazon.tv.releasenotes", "Release notes", "Post-update 'what's new' screens"},
        {"com.amazon.tmm.tutorial", "Tutorials", "First-run tutorial videos"},
        {"com.amazon.firetv.troubleshooting", "Troubleshooting", "Amazon troubleshooting app"},
        {"com.amazon.tv.support", "Support videos", "Help and how-to videos"},
        {"com.amazon.tv.csapp", "Customer service", "Help app; rarely used"},
        {"com.amazon.tahoe", "Kids mode", "Amazon Kids profiles (keep if you use them)"},
        {"com.amazon.tv.legal.notices", "Legal notices", "Licence text viewer (~6 MB)"},
        {"com.amazon.ods.kindleconnect", "Kindle connect", "Kindle sync service that runs in the background"},
        {"com.amazon.uxnotification", "UX notifications", "Promo notification pop-ups"},
        {"com.amazon.systemnotices", "System notices", "Amazon notice pop-ups"},
        {"com.amazon.dummy.alarmclock", "Placeholder: clock", "Empty stub app"},
        {"com.amazon.dummy.calendar", "Placeholder: calendar", "Empty stub app"},
        {"com.amazon.dummy.contacts", "Placeholder: contacts", "Empty stub app"},
        {"com.amazon.dummy.gallery", "Placeholder: gallery", "Empty stub app"},
        {"com.amazon.dummy.music", "Placeholder: music", "Empty stub app"},
        {"com.amazon.dummy.soundpicker", "Placeholder: sounds", "Empty stub app"},
        // smart home
        {"com.amazon.tv.matter", "Matter", "Smart-home Matter controller (keep if you use smart home)"},
        {"com.amazon.tv.mattercohost", "Matter co-host", "Smart-home Matter hub (keep if you use smart home)"},
        {"com.amazon.whad", "Echo home audio", "Wireless home-audio device service (~15 MB)"},
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
