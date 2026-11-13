package com.clean.stickman.core.cmd;

import com.clean.stickman.core.Originals;

/**
 * Sleep page settings (screensaver, sleep and screen-off timeouts, journaled for Restore defaults), the read of their
 * current values, and the broadcasts stickd sends to the app's FadeReceiver (sleep-timer fade, screen-off curtain).
 */
public final class SleepCmds {
    private SleepCmds() {}

    public static final Cmd SCREENSAVER = Cmd.def("sleep.screensaver", "Sleep", 22, "settings");
    public static String screensaver(boolean on) {
        return Originals.setting("secure", "screensaver_enabled", on ? "1" : "0");
    }

    public static final Cmd SLEEP_TIMEOUT = Cmd.def("sleep.sleep_timeout", "Sleep", 22, "settings");
    public static String sleepTimeout(long ms) {
        return Originals.setting("secure", "sleep_timeout", Long.toString(ms));
    }

    public static final Cmd SCREEN_OFF_TIMEOUT = Cmd.def("sleep.screen_off_timeout", "Sleep", 22, "settings");
    public static String screenOffTimeout(long ms) {
        return Originals.setting("system", "screen_off_timeout", Long.toString(ms));
    }

    /** One line: "SAVER &lt;v&gt; SLEEP &lt;v&gt; OFF &lt;v&gt;" (each value may be "null"); parsed by LiveValues. */
    public static final Cmd READ = Cmd.def("sleep.read", "Sleep", 22, "settings echo");
    public static String read() {
        return "echo SAVER $(settings get secure screensaver_enabled)"
            + " SLEEP $(settings get secure sleep_timeout) OFF $(settings get system screen_off_timeout)";
    }

    /** Sleep-timer fade: starts the overlay fading to black over SECONDS. */
    public static final Cmd FADE_START = Cmd.def("sleep.fade_start", "Sleep", 22, "am:broadcast");
    public static String fadeStart(int seconds) {
        return "am broadcast -n com.clean.stickman/.FadeReceiver -a com.clean.stickman.FADE_START --ei seconds " + seconds;
    }

    /** Clears the fade overlay (and the screen-off curtain). */
    public static final Cmd FADE_END = Cmd.def("sleep.fade_end", "Sleep", 22, "am:broadcast");
    public static String fadeEnd() {
        return "am broadcast -n com.clean.stickman/.FadeReceiver -a com.clean.stickman.FADE_END";
    }

    /** Screen off: opaque black overlay until any button (fade end clears it as well). */
    public static final Cmd CURTAIN_ON = Cmd.def("sleep.curtain_on", "Sleep", 22, "am:broadcast");
    public static String curtainOn() {
        return "am broadcast -n com.clean.stickman/.FadeReceiver -a com.clean.stickman.CURTAIN_ON";
    }

    /** A button was pressed while the curtain is up. */
    public static final Cmd CURTAIN_KEY = Cmd.def("sleep.curtain_key", "Sleep", 22, "am:broadcast");
    public static String curtainKey() {
        return "am broadcast -n com.clean.stickman/.FadeReceiver -a com.clean.stickman.CURTAIN_KEY";
    }
}
