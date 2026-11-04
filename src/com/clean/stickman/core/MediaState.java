package com.clean.stickman.core;

import java.util.LinkedHashSet;
import java.util.Set;

/** Reads {@code dumpsys media_session}: which packages have a session in state PLAYING (3). */
public final class MediaState {
    private MediaState() {}

    public static Set<String> playingPackages(String dump) {
        Set<String> out = new LinkedHashSet<>();
        if (dump == null) return out;
        String pkg = null;
        for (String raw : dump.split("\n")) {
            String l = raw.trim();
            if (l.startsWith("package=")) pkg = l.substring("package=".length()).trim();
            else if (l.startsWith("state=PlaybackState {state=3,") || l.startsWith("state=PlaybackState {state=3}")) {
                if (pkg != null && !pkg.isEmpty()) out.add(pkg);
            }
        }
        return out;
    }
}
