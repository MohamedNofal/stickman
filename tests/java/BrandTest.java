import com.clean.stickman.core.Brand;

public class BrandTest {
    static double lum(int c) { return Brand.luminance(c); }

    static double contrast(int a, int b) {
        double la = lum(a), lb = lum(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    public void testKnownAndStable() {
        T.eq(0xFFE50914, Brand.color("com.netflix.ninja"), "Netflix exact");
        T.eq(0xFF17B2E7, Brand.color("org.xbmc.kodi"), "Kodi exact");
        T.eq(0xFFF58B00, Brand.color("org.videolan.vlc"), "VLC exact");
        T.eq(0xFF6C5CE7, Brand.color("com.wolf.firelauncher"), "Wolf exact");
        int a = Brand.color("com.example.unknown"), b = Brand.color("com.example.unknown");
        T.eq(a, b, "stable");
        T.eq(0xFF, (a >>> 24) & 0xFF, "opaque");
        T.yes(Brand.color("com.example.other") != a || Brand.color("org.another.app") != a, "varies");
        T.eq(0xFFFFFFFF, Brand.ink(0xFF14110E), "white on BG");
        T.yes(Brand.ink(0xFFF3EDE6) != 0xFFFFFFFF, "dark on TEXT");
        T.yes(Brand.ink(0xFFFFFF00) != 0xFFFFFFFF, "dark on yellow");
        for (String p : new String[]{"com.netflix.ninja", "org.xbmc.kodi", "org.videolan.vlc", "com.spotify.tv.android",
                "com.disney.disneyplus", "com.plexapp.android", "com.example.unknown", "x", ""}) {
            int c = Brand.color(p);
            T.yes(contrast(c, Brand.ink(c)) >= 3.0, "ink contrast for " + p);
        }
    }

    public void testFallbackDistinct() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) seen.add(Brand.color("com.vendor" + i + ".app"));
        T.yes(seen.size() >= 20, "distinct colours " + seen.size());
    }

    public void testFallbackNotGrey() {
        for (int i = 0; i < 200; i++) {
            int c = Brand.color("pkg.n" + i);
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, bl = c & 0xFF;
            int spread = Math.max(r, Math.max(g, bl)) - Math.min(r, Math.min(g, bl));
            T.yes(spread > 60, "saturated " + Integer.toHexString(c));
        }
    }
}
