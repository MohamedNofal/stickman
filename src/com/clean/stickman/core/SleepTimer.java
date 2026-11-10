package com.clean.stickman.core;

/** Sleep-timer arithmetic and the fade broadcast commands (pure; stickd runs them). */
public final class SleepTimer {
    private SleepTimer() {}

    public static final long FADE_MS = 60_000;
    public static final int MAX_MIN = 24 * 60;
    public static final String FADE_START = "am broadcast -n com.clean.stickman/.FadeReceiver -a com.clean.stickman.FADE_START --ei seconds ";
    public static final String FADE_END = "am broadcast -n com.clean.stickman/.FadeReceiver -a com.clean.stickman.FADE_END";
    /** Screen off: opaque black overlay until any button (FADE_END clears it as well). */
    public static final String CURTAIN_ON = "am broadcast -n com.clean.stickman/.FadeReceiver -a com.clean.stickman.CURTAIN_ON";
    public static final String CURTAIN_KEY = "am broadcast -n com.clean.stickman/.FadeReceiver -a com.clean.stickman.CURTAIN_KEY";

    /** Cycles 0->15->30->60->90->0; any other value goes to 15. */
    public static int next(int currentMin) {
        switch (currentMin) {
            case 0: return 15;
            case 15: return 30;
            case 30: return 60;
            case 60: return 90;
            case 90: return 0;
            default: return 15;
        }
    }

    public static long fadeStartAt(long endAtMs) { return endAtMs - FADE_MS; }

    /** Minutes for sleep_timer_set: 0..MAX_MIN, or -1 when not a valid number. */
    public static int parseMinutes(String arg) {
        if (arg == null) return -1;
        String a = arg.trim();
        if (!a.matches("\\d{1,5}")) return -1;
        int n = Integer.parseInt(a);
        return n > MAX_MIN ? -1 : n;
    }

    public static String fadeStart(int seconds) { return FADE_START + seconds; }
}
