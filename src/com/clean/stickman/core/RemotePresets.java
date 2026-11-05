package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every Fire TV remote Stickman can draw (docs/device/fire-tv-remotes.md), as rows of buttons turned into
 * RemoteLayout geometry by one shared builder. Buttons whose key code nobody knows yet (Pro custom/headphone,
 * 2026 mic/star/sliders) have code 0 until learned in listen mode; app-button labels are per-region defaults
 * the owner can rename. resolve() applies both from Store.
 */
public final class RemotePresets {
    private RemotePresets() {}

    public static final String DEFAULT = "enhanced";
    public static final int MAX_LABEL = 24;

    public static final class Preset {
        public final String id, name, years;
        /** True when the drawing is a best guess (name says "(layout approximate)"). */
        public final boolean approximate;
        /** Body height / width, and corner radius as a fraction of the body width. */
        public final float aspect, corner;
        /** D-pad ring centre (fraction of height) and outer radius (width units). */
        public final float ringY, ringR;
        /** Rocker pills drawn behind +/- buttons: {cx, top cy, bottom cy} as fractions; with their captions. */
        public final float[][] rockers;
        public final String[] rockerLabels;
        public final RemoteLayout.Btn[] buttons;

        Preset(String id, String name, String years, boolean approximate, float aspect, float corner, float ringY,
               float ringR, float[][] rockers, String[] rockerLabels, RemoteLayout.Btn[] buttons) {
            this.id = id;
            this.name = name;
            this.years = years;
            this.approximate = approximate;
            this.aspect = aspect;
            this.corner = corner;
            this.ringY = ringY;
            this.ringR = ringR;
            this.rockers = rockers;
            this.rockerLabels = rockerLabels;
            this.buttons = buttons;
        }

        /** "Name · years" for choosers. */
        public String title() { return name + " · " + years; }
    }

    // ---- builder: rows of keys -> geometry ----

    /** A key before placement. */
    static final class K {
        final int code;
        final String slot, name, label, glyph;
        K(int code, String slot, String name, String label, String glyph) {
            this.code = code; this.slot = slot; this.name = name; this.label = label; this.glyph = glyph;
        }
    }

    static K k(int code) { return new K(code, "k" + code, Keys.name(code), Keys.name(code), null); }
    /** Black mic-icon voice button that sends the Alexa code (2018 remote, Lite 1st gen). */
    static K mic() { return new K(217, "k217", "Voice (mic)", "Voice", "mic"); }
    static K unknown(String slot, String name, String label, String glyph) { return new K(0, slot, name, label, glyph); }
    static K app(int n, int code, String name) { return new K(code, "app" + n, name, name, null); }

    static final class Builder {
        static final float MIN_ASPECT = 2.3f, BOTTOM = 0.19f, PILL_R = 0.16f, PILL_PITCH = 0.21f;
        final String id, name, years;
        final boolean approximate;
        float corner = 0.30f, y, ringY = -1;
        final List<K> keys = new ArrayList<>();
        final List<float[]> at = new ArrayList<>();      // {cx, y, r, round}
        final List<float[]> rockers = new ArrayList<>(); // {cx, top y, bottom y}
        final List<String> rockerLabels = new ArrayList<>();

        Builder(String id, String name, String years, boolean approximate) {
            this.id = id;
            this.name = approximate ? name + " (layout approximate)" : name;
            this.years = years;
            this.approximate = approximate;
        }

        Builder corner(float c) { corner = c; return this; }

        void put(K k, float cx, float y, float r, boolean round) {
            if (k == null) return;
            keys.add(k);
            at.add(new float[] { cx, y, r, round ? 1 : 0 });
        }

        /** Power (left), voice (centre), extra (right) at the top. */
        Builder top(K left, K mid, K right) {
            y = 0.15f;
            put(left, 0.22f, y, 0.065f, true);
            put(mid, 0.50f, y, 0.075f, true);
            put(right, 0.78f, y, 0.065f, true);
            return this;
        }

        /** D-pad: Up/Left/Right/Down around OK, in a ring. */
        Builder ring() {
            ringY = y + 0.45f;
            put(k(103), 0.50f, ringY - 0.23f, 0.07f, true);
            put(k(105), 0.27f, ringY, 0.07f, true);
            put(k(96), 0.50f, ringY, 0.095f, true);
            put(k(106), 0.73f, ringY, 0.07f, true);
            put(k(108), 0.50f, ringY + 0.23f, 0.07f, true);
            y = ringY + 0.23f;
            return this;
        }

        /** Three keys in columns left / centre / right (nulls leave a gap). */
        Builder row(float pitch, float r, K l, K c, K rt) {
            y += pitch;
            put(l, 0.20f, y, r, true);
            put(c, 0.50f, y, r, true);
            put(rt, 0.80f, y, r, true);
            return this;
        }

        void rocker(float cx, String label, int up, int down, float top, float bot) {
            put(k(up), cx, top, 0.075f, true);
            put(k(down), cx, bot, 0.075f, true);
            rockers.add(new float[] { cx, top, bot });
            rockerLabels.add(label);
        }

        /** Volume rocker (left) and channel rocker (right) two rows tall, with two centre keys between them. */
        Builder sideRockers(K centreTop, K centreBottom) {
            float top = y + 0.22f, bot = top + 0.22f;
            rocker(0.18f, "VOL", 115, 114, top, bot);
            put(centreTop, 0.50f, top, 0.075f, true);
            put(centreBottom, 0.50f, bot, 0.075f, true);
            rocker(0.82f, "CH", 402, 403, top, bot);
            y = bot;
            return this;
        }

        /** Volume rocker in the centre column two rows tall, a key either side of its top half. */
        Builder centreRocker(K left, K right) {
            float top = y + 0.22f, bot = top + 0.22f;
            put(left, 0.20f, top, 0.075f, true);
            rocker(0.50f, "VOL", 115, 114, top, bot);
            put(right, 0.80f, top, 0.075f, true);
            y = bot;
            return this;
        }

        /** Two rows of two app pills. */
        Builder apps(K a, K b, K c, K d) {
            y += PILL_PITCH;
            put(a, 0.29f, y, PILL_R, false);
            put(b, 0.71f, y, PILL_R, false);
            y += PILL_PITCH;
            put(c, 0.29f, y, PILL_R, false);
            put(d, 0.71f, y, PILL_R, false);
            return this;
        }

        Preset build() {
            float aspect = Math.max(MIN_ASPECT, Math.round((y + BOTTOM) * 100) / 100f);
            RemoteLayout.Btn[] bs = new RemoteLayout.Btn[keys.size()];
            for (int i = 0; i < bs.length; i++) {
                K k = keys.get(i);
                float[] p = at.get(i);
                bs[i] = new RemoteLayout.Btn(k.code, k.slot, k.name, k.label, k.glyph, p[0], p[1] / aspect, p[2], p[3] > 0, aspect);
            }
            float[][] rk = new float[rockers.size()][];
            for (int i = 0; i < rk.length; i++) {
                float[] r = rockers.get(i);
                rk[i] = new float[] { r[0], r[1] / aspect, r[2] / aspect };
            }
            return new Preset(id, name, years, approximate, aspect, corner, ringY / aspect, 0.32f, rk,
                rockerLabels.toArray(new String[0]), bs);
        }
    }

    static Builder b(String id, String name, String years, boolean approximate) { return new Builder(id, name, years, approximate); }

    /** Back | Home | Menu and Rewind | Play/Pause | Fast-forward under the ring. */
    static Builder nav(Builder b) { return b.row(0.23f, 0.08f, k(158), k(172), k(139)); }
    static Builder media(Builder b) { return b.row(0.23f, 0.08f, k(168), k(164), k(208)); }

    /** US launch-photo app buttons (slot order -> codes as on the owner's remote; the real mapping varies). */
    static K[] usApps() {
        return new K[] { app(1, 745, "Prime Video"), app(2, 744, "Netflix"), app(3, 746, "Disney+"), app(4, 747, "Hulu") };
    }

    static Builder withApps(Builder b, K[] a) { return b.apps(a[0], a[1], a[2], a[3]); }

    public static final List<Preset> ALL;

    static {
        List<Preset> l = new ArrayList<>();
        // 1. Lite 1st gen (2020): mic, ring, nav, media, Live TV. No power, volume or apps.
        l.add(media(nav(b("lite1", "Alexa Voice Remote Lite (1st gen)", "2020", false).top(null, mic(), null).ring()))
            .row(0.22f, 0.075f, null, k(362), null).build());
        // 2. Lite 2nd gen (2022): Alexa, ring, nav, media, Live TV, 2x2 apps.
        l.add(withApps(media(nav(b("lite2", "Alexa Voice Remote Lite (2nd gen)", "2022", false).top(null, k(217), null).ring()))
            .row(0.22f, 0.075f, null, k(362), null), usApps()).build());
        // 3. 2nd gen (2018): power + mic, ring, nav, media, volume rocker (centre), mute.
        l.add(media(nav(b("gen2", "Alexa Voice Remote (2nd gen)", "2018", false).top(k(116), mic(), null).ring()))
            .centreRocker(null, null).row(0.22f, 0.075f, null, k(113), null).build());
        // 4. 3rd gen (2021/22): power + Alexa, ring, nav, media, Mute | VOL | Live TV, 2x2 apps.
        l.add(withApps(media(nav(b("gen3", "Alexa Voice Remote (3rd gen)", "2021–2022", false).top(k(116), k(217), null).ring()))
            .centreRocker(k(113), k(362)), usApps()).build());
        // 5. 2024 release: same buttons as 3rd gen, squarer body.
        l.add(withApps(media(nav(b("gen2024", "Alexa Voice Remote (2024 release)", "2024", false).corner(0.20f)
            .top(k(116), k(217), null).ring())).centreRocker(k(113), k(362)), usApps()).build());
        // 6. Enhanced (the owner's, UK): VOL | Live TV | CH, Mute | Settings | Recents, Prime Video Netflix / Apps Amazon Music.
        l.add(media(nav(b("enhanced", "Alexa Voice Remote Enhanced", "2020–2023", false).top(k(116), k(217), null).ring()))
            .sideRockers(null, k(362)).row(0.22f, 0.07f, k(113), k(249), k(748))
            .apps(app(1, 745, "Prime Video"), app(2, 744, "Netflix"), app(3, 746, "Apps"), app(4, 747, "Amazon Music")).build());
        // 7. Pro (2022): headphones top right, VOL | Live TV over Settings | CH, Mute | 1 | 2, no Recents.
        l.add(withApps(media(nav(b("pro", "Alexa Voice Remote Pro", "2022", false).corner(0.26f)
            .top(k(116), k(217), unknown("headphones", "Headphones", "Headphones", "headphones")).ring()))
            .sideRockers(k(362), k(249))
            .row(0.22f, 0.07f, k(113), unknown("custom1", "Custom button 1", "1", null), unknown("custom2", "Custom button 2", "2", null)),
            usApps()).build());
        // 8. Fire TV Remote 2026: no Rewind/FF/Recents; mic top right; VOL | Play over Live TV | CH; Mute | star | sliders.
        l.add(nav(b("remote2026", "Fire TV Remote", "2026", true).top(k(116), null, unknown("mic", "Voice (mic)", "Voice", "mic")).ring())
            .sideRockers(k(164), k(362))
            .row(0.22f, 0.07f, k(113), unknown("star", "Star button", "Star", "star"), unknown("sliders", "Sliders button", "Sliders", "sliders"))
            .apps(app(1, 745, "Prime Video"), app(2, 744, "Netflix"), app(3, 746, "YouTube"), app(4, 747, "Disney+")).build());
        ALL = Collections.unmodifiableList(l);
    }

    /** Preset by id; DEFAULT when unknown or null. */
    public static Preset get(String id) {
        Preset def = null;
        for (Preset p : ALL) {
            if (p.id.equals(id)) return p;
            if (p.id.equals(DEFAULT)) def = p;
        }
        return def;
    }

    public static int slotIndex(RemoteLayout.Btn[] bs, String slot) {
        for (int i = 0; i < bs.length; i++) if (bs[i].slot.equals(slot)) return i;
        return -1;
    }

    public static List<String> appSlots(Preset p) {
        List<String> out = new ArrayList<>();
        for (RemoteLayout.Btn b : p.buttons) if (b.isApp()) out.add(b.slot);
        return out;
    }

    /** Indices of buttons still waiting for their key code. */
    public static List<Integer> unlearned(RemoteLayout.Btn[] bs) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < bs.length; i++) if (bs[i].code == 0) out.add(i);
        return out;
    }

    /** null when s may be a button label, else why not. */
    public static String invalidLabel(String s) {
        if (s == null || s.trim().isEmpty()) return "The name is empty.";
        if (s.length() > MAX_LABEL) return "The name is too long (" + s.length() + " characters; the limit is " + MAX_LABEL + ").";
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) return "The name contains a line break or control character.";
        }
        return null;
    }

    /**
     * The preset's buttons with learned codes (only for code-0 slots, 1..767, not already on the drawing) and
     * renamed app buttons (valid labels only) applied. The preset itself is never changed.
     */
    public static RemoteLayout.Btn[] resolve(Preset p, Map<String, Integer> learned, Map<String, String> labels) {
        RemoteLayout.Btn[] out = Arrays.copyOf(p.buttons, p.buttons.length);
        Set<Integer> used = new HashSet<>();
        for (RemoteLayout.Btn b : out) if (b.code != 0) used.add(b.code);
        for (int i = 0; i < out.length; i++) {
            RemoteLayout.Btn b = out[i];
            Integer c = learned == null ? null : learned.get(b.slot);
            if (b.code == 0 && c != null && c > 0 && c <= 767 && used.add(c)) out[i] = b = b.withCode(c);
            String n = labels == null ? null : labels.get(b.slot);
            if (b.isApp() && n != null && invalidLabel(n) == null) out[i] = b.withName(n.trim());
        }
        return out;
    }
}
