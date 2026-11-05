package com.clean.stickman.core;

/**
 * Stickman's own remote profile: the Tasks page needs Play/Pause (164) and Menu (139) as plain keys. If the default
 * profile puts any layer (tap/double/hold) on them, a {@code com.clean.stickman} profile with {@code tap orig} for
 * those codes is added (or completed, if it exists but lacks one of them); per remapd's lookup rule that overrides every
 * layer of the code while Stickman is in front.
 */
public final class OwnProfile {
    private OwnProfile() {}

    public static final String PKG = "com.clean.stickman";
    /** KEYCODE_MEDIA_PLAY_PAUSE and KEYCODE_MENU as remapd sees them (Linux input codes). */
    public static final int[] CODES = { 164, 139 };

    /**
     * Adds {@code tap orig} for each of {@link #CODES} that the default profile remaps (any layer) and Stickman's
     * profile doesn't define yet, creating the profile if needed; true if the model changed. Whatever the owner already
     * put in Stickman's profile for a code (any layer) is theirs and left alone.
     */
    public static boolean ensure(RemapModel m) {
        if (m == null) return false;
        boolean changed = false;
        for (int c : CODES) {
            if (!m.hasRules(RemapModel.DEFAULT, c) || m.hasRules(PKG, c)) continue;
            m.set(PKG, c, Rule.Layer.TAP, "orig");
            changed = true;
        }
        return changed;
    }
}
