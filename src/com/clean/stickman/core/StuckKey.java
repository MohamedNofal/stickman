package com.clean.stickman.core;

/**
 * Stuck key check (pure part; stickd's watchStuckKey runs it). When a remote is grabbed in the middle of a press,
 * Android has seen the button go down and never sees it come up: it then believes the button is held, and that
 * button stops working in every app until the remote disconnects. remapd never grabs mid-press any more (set_grab in
 * native/remapd.c), but the symptom is baffling when it does happen ("Back is dead"), so stickd looks for it and says
 * so in the log. Android's input reader prints one "KeyDowns: N keys currently down" line per keyboard device.
 */
public final class StuckKey {
    private StuckKey() {}

    /** One look a minute; a key counts as stuck when it is still down on CONFIRMS looks CONFIRM_MS apart. */
    public static final long EVERY_MS = 60_000, CONFIRM_MS = 10_000;
    public static final int CONFIRMS = 3;

    /** Keys Android believes are down, over all devices, from {@code dumpsys input | grep KeyDowns}. */
    public static int keysDown(String out) {
        if (out == null) return 0;
        int n = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("KeyDowns:\\s*(\\d+)").matcher(out);
        while (m.find()) {
            try { n += Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) { }
        }
        return n;
    }
}
