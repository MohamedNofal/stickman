package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Chooses which background packages auto-kill / boost may kill. Pure: no shell access. */
public final class KillPolicy {
    private KillPolicy() {}

    /** Never killed: launchers, Stickman, Net Filter (the proxy: killing it breaks internet), core Amazon services. */
    public static final Set<String> NEVER_KILL = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "com.wolf.firelauncher", "com.amazon.tv.launcher", "com.amazon.firehomestarter",
        "com.clean.stickman", "com.clean.netfilter", "com.android.proxyhandler",
        "com.amazon.vizzini", "com.amazon.tv.notificationcenter", "com.amazon.hedwig", "com.amazon.tv.livetv",
        "com.amazon.venezia",  // Appstore: has a launcher entry but runs downloads/updates in the background
        "system", "android")));
    private static final String[] NEVER_KILL_PREFIX = {
        "com.amazon.tv.settings", "com.amazon.device.software.ota", "com.android.bluetooth", "com.amazon.tv.ime",
    };
    private static final String[] NEVER_KILL_WORDS = {"inputmethod", ".ime", "remote", "bluetooth", "launcher"};

    /**
     * A process in any of these adj/state classes protects its whole package. Besides persistent/system/home/top/
     * visible this covers the perceptible ones (fore, prcp, prcl, psvc: e.g. audio playing from a foreground
     * service without a MediaSession), backup (bkup), heavy-weight (hvy) and the foreground-service proc states
     * (FGS, BFGS), because a blacklisted package gets {@code am force-stop}, which kills all of its processes.
     */
    private static final Set<String> PROTECTED_CLASS = new HashSet<>(Arrays.asList(
        "pers", "PER", "sys", "HOME", "home", "TOP", "fg", "vis",
        "fore", "prcp", "prcl", "psvc", "bkup", "hvy", "FGS", "BFGS"));

    private static final String[] KILL_ADJ = {"cch", "prev", "svcb", "svcl"};
    private static final Set<String> KILL_STATE = new HashSet<>(Arrays.asList("CEM", "CAC", "LAST"));

    /**
     * Never ended, not even when the user insists: Stickman itself (both editions), the system, Bluetooth (the remote
     * would drop) and anything that is not a plain package name. The rest of the never-kill list is only spared by the
     * automatic paths (End all, auto-close, Boost); the Tasks page may end those after an "Are you sure?".
     */
    public static boolean isHardNever(String pkg) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) return true;
        return pkg.equals("com.clean.stickman") || pkg.equals("com.clean.stickman.tv") || pkg.equals("system")
            || pkg.equals("android") || pkg.startsWith("com.android.bluetooth");
    }

    /**
     * Packages with a process doing something right now (persistent, visible, perceptible, foreground service,
     * backup...): closing the package would cut that off. Used by auto-kill, Boost and End all.
     */
    public static Set<String> busyPackages(List<LruParser.Proc> procs) {
        Set<String> keep = new HashSet<>();
        for (LruParser.Proc p : procs) {
            String adjClass = p.adj.replaceAll("[^A-Za-z].*$", "");
            if (PROTECTED_CLASS.contains(adjClass) || PROTECTED_CLASS.contains(p.state)) keep.add(p.pkg);
        }
        return keep;
    }

    public static boolean isNeverKill(String pkg) {
        if (pkg == null || NEVER_KILL.contains(pkg)) return true;
        for (String p : NEVER_KILL_PREFIX) if (pkg.startsWith(p)) return true;
        String l = pkg.toLowerCase();
        for (String w : NEVER_KILL_WORDS) if (l.contains(w)) return true;
        return !pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");  // only plain package names reach a shell
    }

    private static boolean killable(LruParser.Proc p) {
        if (KILL_STATE.contains(p.state)) return true;
        for (String a : KILL_ADJ) if (p.adj.startsWith(a)) return true;
        return false;
    }

    /**
     * Shell command listing user-launchable activities (TV and phone launcher entries); parse it with
     * {@link #parseLaunchable}.
     */
    public static final String LAUNCHABLE_CMD =
        "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER; "
        + "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER";

    private static final java.util.regex.Pattern COMPONENT =
        java.util.regex.Pattern.compile("^\\s*([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+\\s*$");

    /** Packages named by the "pkg/activity" lines of {@code query-activities --brief} output. */
    public static Set<String> parseLaunchable(String out) {
        Set<String> s = new HashSet<>();
        if (out == null) return s;
        for (String l : out.split("\n")) {
            java.util.regex.Matcher m = COMPONENT.matcher(l);
            if (m.find()) s.add(m.group(1));
        }
        return s;
    }

    /**
     * Packages auto-kill / Boost may kill. Only user-launchable apps ({@code launchable}: packages with a
     * launcher or leanback-launcher activity) are candidates at all, so Amazon/Android service and provider
     * processes are never churned; a null or empty launchable set fails closed (no candidates).
     */
    public static List<String> candidates(List<LruParser.Proc> procs, String fgPkg, Set<String> whitelist,
                                          Set<String> mediaProtected, Set<String> launchable) {
        if (launchable == null || launchable.isEmpty()) return new ArrayList<>();
        Set<String> keep = new HashSet<>();
        if (fgPkg != null) keep.add(fgPkg);
        if (whitelist != null) keep.addAll(whitelist);
        if (mediaProtected != null) keep.addAll(mediaProtected);
        for (LruParser.Proc p : procs) {
            String adjClass = p.adj.replaceAll("[^A-Za-z].*$", "");
            if (PROTECTED_CLASS.contains(adjClass) || PROTECTED_CLASS.contains(p.state)) keep.add(p.pkg);
        }
        Set<String> out = new LinkedHashSet<>();
        for (LruParser.Proc p : procs) {
            if (keep.contains(p.pkg) || isNeverKill(p.pkg) || !launchable.contains(p.pkg)) continue;
            if (killable(p)) out.add(p.pkg);
        }
        return new ArrayList<>(out);
    }
}
