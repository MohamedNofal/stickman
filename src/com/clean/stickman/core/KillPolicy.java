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
        "system", "android")));
    private static final String[] NEVER_KILL_PREFIX = {
        "com.amazon.tv.settings", "com.amazon.device.software.ota", "com.android.bluetooth", "com.amazon.tv.ime",
    };
    private static final String[] NEVER_KILL_WORDS = {"inputmethod", ".ime", "remote", "bluetooth", "launcher"};

    /** A process in any of these adj/state classes protects its whole package. */
    private static final Set<String> PROTECTED_CLASS = new HashSet<>(Arrays.asList(
        "pers", "PER", "sys", "HOME", "home", "TOP", "fg", "vis"));

    private static final String[] KILL_ADJ = {"cch", "prev", "svcb", "svcl"};
    private static final Set<String> KILL_STATE = new HashSet<>(Arrays.asList("CEM", "CAC", "LAST"));

    public static boolean neverKill(String pkg) {
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

    public static List<String> candidates(List<LruParser.Proc> procs, String fgPkg, Set<String> whitelist, Set<String> playing) {
        Set<String> keep = new HashSet<>();
        if (fgPkg != null) keep.add(fgPkg);
        if (whitelist != null) keep.addAll(whitelist);
        if (playing != null) keep.addAll(playing);
        for (LruParser.Proc p : procs) {
            String adjClass = p.adj.replaceAll("[^A-Za-z].*$", "");
            if (PROTECTED_CLASS.contains(adjClass) || PROTECTED_CLASS.contains(p.state)) keep.add(p.pkg);
        }
        Set<String> out = new LinkedHashSet<>();
        for (LruParser.Proc p : procs) {
            if (keep.contains(p.pkg) || neverKill(p.pkg)) continue;
            if (killable(p)) out.add(p.pkg);
        }
        return new ArrayList<>(out);
    }
}
