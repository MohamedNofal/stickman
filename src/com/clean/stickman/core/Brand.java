package com.clean.stickman.core;

import java.util.HashMap;
import java.util.Map;

/** Tile colours per package (ARGB ints, no android.graphics). */
public final class Brand {
    private Brand() {}

    /** White and Theme BG (#14110E) as the two ink choices. */
    public static final int WHITE = 0xFFFFFFFF, DARK = 0xFF14110E;
    static final float SAT = 0.55f, LIGHT = 0.42f;

    private static final Map<String, Integer> KNOWN = new HashMap<>();
    static {
        put(0xFFE50914, "com.netflix.ninja", "com.netflix.mediaclient");
        put(0xFF00A8E1, "com.amazon.avod", "com.amazon.avod.thirdpartyclient", "com.amazon.firebat");
        put(0xFFFF0000, "com.amazon.firetv.youtube", "com.google.android.youtube.tv",
                "com.google.android.youtube", "com.amazon.firetv.youtube.tv");
        put(0xFF17B2E7, "org.xbmc.kodi");
        put(0xFFF58B00, "org.videolan.vlc");
        put(0xFF1DB954, "com.spotify.tv.android", "com.spotify.music");
        put(0xFF113CCF, "com.disney.disneyplus");
        put(0xFF7E57C2, "app.wako");
        put(0xFF6C5CE7, "com.wolf.firelauncher");
        put(0xFFE5A00D, "com.plexapp.android");
    }

    private static void put(int c, String... pkgs) { for (String p : pkgs) KNOWN.put(p, c); }

    public static int color(String pkg) {
        if (pkg == null) pkg = "";
        Integer k = KNOWN.get(pkg);
        if (k != null) return k;
        int hsh = pkg.hashCode() * 0x9E3779B1;   // spread similar package names
        int h = ((hsh ^ (hsh >>> 15)) & 0x7fffffff) % 90;
        // warm 10..50 deg (40 slots) + teal/blue 170..220 deg (50 slots)
        float hue = h < 40 ? 10 + h : 170 + (h - 40);
        return hsl(hue, SAT, LIGHT);
    }

    /** HSL (h in degrees, s/l 0..1) to opaque ARGB. */
    public static int hsl(float h, float s, float l) {
        float c = (1 - Math.abs(2 * l - 1)) * s;
        float hp = ((h % 360) + 360) % 360 / 60f;
        float x = c * (1 - Math.abs(hp % 2 - 1));
        float r, g, b;
        if (hp < 1) { r = c; g = x; b = 0; }
        else if (hp < 2) { r = x; g = c; b = 0; }
        else if (hp < 3) { r = 0; g = c; b = x; }
        else if (hp < 4) { r = 0; g = x; b = c; }
        else if (hp < 5) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        float m = l - c / 2;
        return 0xFF000000 | (ch(r + m) << 16) | (ch(g + m) << 8) | ch(b + m);
    }

    private static int ch(float v) { return Math.max(0, Math.min(255, Math.round(v * 255))); }

    /** WCAG relative luminance of an ARGB colour (alpha ignored). */
    public static double luminance(int c) {
        return 0.2126 * lin((c >> 16) & 0xFF) + 0.7152 * lin((c >> 8) & 0xFF) + 0.0722 * lin(c & 0xFF);
    }

    private static double lin(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** Text colour for a tile of colour bg: white or near-black, whichever contrasts more. */
    public static int ink(int bg) {
        double l = luminance(bg);
        double vsWhite = 1.05 / (l + 0.05);
        double vsDark = (l + 0.05) / (luminance(DARK) + 0.05);
        return vsWhite >= vsDark ? WHITE : DARK;
    }
}
