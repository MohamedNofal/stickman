package com.clean.stickman.core;

/**
 * The two daemons keep each other alive (pure rules; stickd's half is daemon/Stickd.guardRemapd, remapd's half is
 * guard_stickd in native/remapd.c, which mirrors these numbers). Both run as the shell user, so either can start the
 * other the way the setup script does, in seconds and without the app or its VPN. That covers one of them crashing
 * or being killed; when both go at once (the system restarted adbd, whose process group they live in) only the app
 * can bring them back: see Watchdog.
 *
 * Never against the owner's wish: not while DIR/stopped exists (written first by the stop script and by the
 * uninstall watchdog, removed by the setup script), and stickd leaves remapd alone after Back was held 10 s
 * (DIR/released).
 */
public final class Buddy {
    private Buddy() {}

    /** Marker file in DIR: the daemons were stopped on purpose. */
    public static final String STOPPED = "stopped";

    /** stickd looks at remapd this often. */
    public static final long CHECK_MS = 3000;

    /** Missing this many checks in a row before a restart: a planned restart (update, new binary) has a short gap. */
    public static final int MISSES = 2;

    /** The miss count after one check. */
    public static int misses(int before, boolean alive, boolean stopped, boolean released) {
        return alive || stopped || released ? 0 : before + 1;
    }

    public static boolean revive(int misses) { return misses >= MISSES; }

    /** Extra pause after restart number tries (0-based) in a row that did not last: up to a minute. */
    public static long pauseMs(int tries) {
        return 5000L * Math.min(Math.max(tries, 0), 12);
    }
}
