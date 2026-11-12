package com.clean.stickman.core;

/**
 * Words for the Remote page's pointer switch, from remapd's latest "STATUS mouse ..." line (the rest after "mouse"):
 * "on (uinput)" / "on (uhid)" (Android draws its own pointer), "on (drawn)" (Fire OS 5-7: Stickman draws it),
 * "off", "unavailable: why". Null = nothing seen yet (also when Stickman can't read the log). Pure Java, host-tested.
 */
public final class PointerText {
    private PointerText() {}

    public static boolean isOn(String status) {
        return status != null && status.startsWith("on");
    }

    public static String button(String status) {
        return isOn(status) ? "Turn the pointer off" : "Turn the pointer on";
    }

    /** One line about the current state; how-to text is separate ({@link #HOW}). */
    public static String state(String status) {
        if (status == null || status.startsWith("off")) return "Off.";
        if (status.startsWith("on (drawn)"))
            return "On. This Fire OS has no mouse support for apps, so Stickman draws the pointer and taps for it.";
        if (isOn(status)) return "On.";
        if (status.startsWith("unavailable")) {
            if (status.contains("listen-only"))
                return "Couldn't turn it on: Stickman can't hold back the remote's buttons on this stick yet "
                    + "(see Status), and the pointer needs the arrows and OK for itself.";
            return "Couldn't turn it on: this Fire OS doesn't let Stickman make a pointer.";
        }
        return "Off.";
    }

    public static final String HOW = "Arrows move it, OK clicks, Rewind and Fast-forward scroll, Back turns it off. "
        + "To use it without opening Stickman, put Mouse pointer on a button: choose a button, then Press, Double press or Hold, then Something else.";
}
