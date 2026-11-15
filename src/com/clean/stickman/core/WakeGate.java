package com.clean.stickman.core;

/**
 * What the key injector does with a button press, by power state (pure; used by daemon/Injector on Fire OS 5-7).
 *
 * A press of the real remote wakes a sleeping stick and is then dropped: the button that wakes never also acts.
 * An injected key is the opposite on Android: it never wakes (only Back and Menu count as wake keys), yet it is still
 * handed to the app in front, unseen. With the remote grabbed, every button is an injected key, so a sleeping stick
 * ignored its remote (GitHub issue #5). The injector therefore restores the real behaviour itself.
 */
public final class WakeGate {
    private WakeGate() {}

    /** Screen on (awake, or the screensaver: it ends on the key it is given): replay the key as it is. */
    public static final int PASS = 0;
    /** Asleep: wake the stick and drop this press, release included. */
    public static final int WAKE_ONLY = 1;
    /** Power state unknown: wake (harmless when awake) and replay; a button that does too much beats a dead remote. */
    public static final int WAKE_AND_PASS = 2;

    /** interactive: PowerManager.isInteractive(), or null when it could not be read. */
    public static int onDown(Boolean interactive) {
        if (interactive == null) return WAKE_AND_PASS;
        return interactive ? PASS : WAKE_ONLY;
    }
}
