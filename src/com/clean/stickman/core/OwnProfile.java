package com.clean.stickman.core;

/**
 * Stickman's own remote profile: the Tasks page needs Play/Pause (164) and Menu (139) as plain keys. If the default
 * profile puts any layer (tap/double/hold) on them, a {@code com.clean.stickman} profile with {@code tap orig} for
 * those codes is added; per remapd's lookup rule that overrides every layer of the code while Stickman is in front.
 */
public final class OwnProfile {
    private OwnProfile() {}

    public static final String PKG = "com.clean.stickman";
    /** KEYCODE_MEDIA_PLAY_PAUSE and KEYCODE_MENU as remapd sees them (Linux input codes). */
    public static final int[] CODES = { 164, 139 };

    /** Adds the profile when needed; true if the model changed. An existing profile is the owner's: left alone. */
    public static boolean ensure(RemapModel m) {
        if (m == null || m.profiles().contains(PKG)) return false;
        boolean changed = false;
        for (int c : CODES) {
            if (!m.hasRules(RemapModel.DEFAULT, c)) continue;
            m.set(PKG, c, Rule.Layer.TAP, "orig");
            changed = true;
        }
        return changed;
    }
}
