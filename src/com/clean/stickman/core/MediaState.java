package com.clean.stickman.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code dumpsys media_session}: packages with a session that auto-kill must protect, i.e. in
 * PlaybackState PLAYING (3), PAUSED (2) or BUFFERING (6). STOPPED (1), NONE (0), null and others are not protected.
 */
public final class MediaState {
    private MediaState() {}

    private static final Pattern STATE = Pattern.compile("^state=PlaybackState \\{state=(\\d+)\\b");
    private static final Set<String> PROTECTED = new HashSet<>(Arrays.asList("2", "3", "6"));
    private static final Set<String> PLAYING = new HashSet<>(Arrays.asList("3"));

    public static Set<String> protectedPackages(String dump) { return withStates(dump, PROTECTED); }

    /** Packages whose session is PLAYING (3). */
    public static Set<String> playingPackages(String dump) { return withStates(dump, PLAYING); }

    public static boolean isPlaying(String dump) { return !playingPackages(dump).isEmpty(); }

    private static Set<String> withStates(String dump, Set<String> states) {
        Set<String> out = new LinkedHashSet<>();
        if (dump == null) return out;
        String pkg = null;
        for (String raw : dump.split("\n")) {
            String l = raw.trim();
            if (l.startsWith("package=")) pkg = l.substring("package=".length()).trim();
            else {
                Matcher m = STATE.matcher(l);
                if (m.find() && states.contains(m.group(1)) && pkg != null && !pkg.isEmpty()) out.add(pkg);
            }
        }
        return out;
    }
}
