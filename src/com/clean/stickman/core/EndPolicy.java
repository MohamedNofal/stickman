package com.clean.stickman.core;

import java.util.Set;

/** Whether the Tasks page may end (force-stop) a package. Pure: callers pass fresh inputs. */
public final class EndPolicy {
    private EndPolicy() {}

    public static final String SELF = "com.clean.stickman";

    /**
     * Null when {@code pkg} may be ended, else the reason: "never" (KillPolicy never-kill or Stickman itself; also
     * anything that is not a plain package name), "in front", "playing" (media session playing/paused/buffering),
     * "protected" (whitelist), "system" (not launchable; a null or empty launchable set fails closed).
     */
    public static String check(String pkg, String fgPkg, Set<String> protectedMedia, Set<String> whitelist,
                               Set<String> launchable) {
        if (pkg == null || SELF.equals(pkg) || KillPolicy.isNeverKill(pkg)) return "never";
        if (pkg.equals(fgPkg)) return "in front";
        if (protectedMedia != null && protectedMedia.contains(pkg)) return "playing";
        if (whitelist != null && whitelist.contains(pkg)) return "protected";
        if (launchable == null || !launchable.contains(pkg)) return "system";
        return null;
    }
}
