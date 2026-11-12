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
        String r = check(pkg, fgPkg, protectedMedia, whitelist, launchable, false);
        return GUARDED.equals(r) ? (KillPolicy.isNeverKill(pkg) ? "never" : "system") : r;
    }

    /** Stickman spares this app by itself, but the user may end it from the Tasks page after confirming. */
    public static final String GUARDED = "guarded";

    /**
     * As above for one app the user picked: an app on Stickman's own never-kill list (Appstore, launchers, Amazon
     * services) or without a launcher entry (background and system services) gives "guarded" until {@code force}
     * (the user confirmed "Are you sure?"). Stickman, the system and Bluetooth stay "never"; in front, playing and the
     * user's own protection still apply.
     */
    public static String check(String pkg, String fgPkg, Set<String> protectedMedia, Set<String> whitelist,
                               Set<String> launchable, boolean force) {
        if (pkg == null || SELF.equals(pkg) || KillPolicy.isHardNever(pkg)) return "never";
        boolean guarded = KillPolicy.isNeverKill(pkg);
        if (pkg.equals(fgPkg)) return "in front";
        if (protectedMedia != null && protectedMedia.contains(pkg)) return "playing";
        if (whitelist != null && whitelist.contains(pkg)) return "protected";
        // spared by Stickman itself, or a background/system service with no launcher entry
        if (guarded || launchable == null || !launchable.contains(pkg)) return force ? null : GUARDED;
        return null;
    }

    /** What ending a guarded app does, for the "Are you sure?" question. */
    public static String guardedWarning(String label) {
        return "Stickman normally leaves " + label + " alone: it is a system or background service, and things that "
            + "depend on it (downloads and updates, the home screen, notifications, sound or the remote) can stop until "
            + "it starts again. Most restart by themselves; restarting the stick brings everything back.\n\nEnd it anyway?";
    }
}
