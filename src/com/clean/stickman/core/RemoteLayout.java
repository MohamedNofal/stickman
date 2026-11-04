package com.clean.stickman.core;

/**
 * Pure geometry of the Alexa Voice Remote Enhanced, drawn by RemoteView and used for D-pad focus moves.
 * cx is a 0..1 fraction of the body width, cy a 0..1 fraction of the body height; r is in body-width units
 * (the body is ASPECT times taller than wide). Round buttons are circles of radius r; the others (app buttons)
 * are pills r wide either side of the centre and PILL_H * r tall either side.
 */
public final class RemoteLayout {
    private RemoteLayout() {}

    /** Body height / body width. */
    public static final float ASPECT = 2.55f;
    /** Pill half-height as a fraction of r. */
    public static final float PILL_H = 0.45f;

    public static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;

    // ---- drawing constants shared by RemoteView and tools/RemotePreview (dp) ----
    /** Padding around the body inside the view. */
    public static final float BODY_PAD_DP = 8;
    /** Focus ring: gap outside the button shape, and its stroke width. */
    public static final float FOCUS_OFFSET_DP = 3.5f, FOCUS_STROKE_DP = 3;
    /** Smallest body width the layout is designed for (1080p TV at density 320 gives ~190 dp). */
    public static final float MIN_BODY_DP = 180;
    /** Hint pill: gap from the body rim, height, side margin to the view edge, pointer size, text size (sp). */
    public static final float HINT_GAP_DP = 14, HINT_H_DP = 28, HINT_MARGIN_DP = 4, HINT_POINTER_DP = 7, HINT_TEXT_SP = 13;
    /** Width the view reserves right of the body for the hint pill. */
    public static final float HINT_AREA_DP = 150;

    /** How far a focus ring reaches outside its button, in body-width units (at MIN_BODY_DP). */
    public static float focusMargin() { return (FOCUS_OFFSET_DP + FOCUS_STROKE_DP / 2) / MIN_BODY_DP; }

    /**
     * Hint pill rectangle {left, top, right, bottom} in view pixels for the focused button: right of the body,
     * level with the button, clamped to the view. Never inside the body, so it never covers a button.
     */
    public static float[] hintRect(Btn b, float bx, float by, float bw, float bh, float viewW, float viewH,
                                   float textW, float dp) {
        float h = HINT_H_DP * dp, left = bx + bw + HINT_GAP_DP * dp;
        float right = Math.min(viewW - HINT_MARGIN_DP * dp, left + textW + 24 * dp);
        float cy = by + b.cy * bh;
        float top = Math.max(HINT_MARGIN_DP * dp, Math.min(viewH - HINT_MARGIN_DP * dp - h, cy - h / 2));
        return new float[] { left, top, right, top + h };
    }

    public static final class Btn {
        public final int code;
        public final String label;
        public final float cx, cy, r;
        public final boolean round;

        public Btn(int code, String label, float cx, float cy, float r, boolean round) {
            this.code = code;
            this.label = label;
            this.cx = cx;
            this.cy = cy;
            this.r = r;
            this.round = round;
        }

        /** Half extents in width units. */
        public float hw() { return r; }
        public float hh() { return round ? r : r * PILL_H; }
        /** Centre y in width units. */
        float y() { return cy * ASPECT; }
    }

    /** y given in width units (0..ASPECT) to keep the table readable. */
    private static Btn b(int code, String label, float cx, float y, float r) { return new Btn(code, label, cx, y / ASPECT, r, true); }
    private static Btn pill(int code, String label, float cx, float y, float r) { return new Btn(code, label, cx, y / ASPECT, r, false); }

    /** Centre of the D-pad ring (fraction of height) and its outer radius (width units). */
    public static final float RING_Y = 0.60f / ASPECT, RING_R = 0.32f;

    /*
     * Spacing rule: every pair of neighbours keeps a gap >= 2 * focusMargin() (~0.056 width units), so even
     * two focus rings drawn at once wouldn't touch (RemoteLayoutTest.testFocusRingsClear).
     */
    public static final Btn[] BUTTONS = {
        // 1. Power | Alexa
        b(116, "Power", 0.22f, 0.15f, 0.07f),
        b(217, "Alexa", 0.50f, 0.15f, 0.075f),
        // 2. D-pad ring around OK
        b(103, "Up", 0.50f, 0.37f, 0.07f),
        b(105, "Left", 0.27f, 0.60f, 0.07f),
        b(96, "OK", 0.50f, 0.60f, 0.095f),
        b(106, "Right", 0.73f, 0.60f, 0.07f),
        b(108, "Down", 0.50f, 0.83f, 0.07f),
        // 3. Back | Home | Menu
        b(158, "Back", 0.20f, 1.06f, 0.08f),
        b(172, "Home", 0.50f, 1.06f, 0.08f),
        b(139, "Menu", 0.80f, 1.06f, 0.08f),
        // 4. Rewind | Play/Pause | Fast-forward
        b(168, "Rewind", 0.20f, 1.29f, 0.08f),
        b(164, "Play/Pause", 0.50f, 1.29f, 0.08f),
        b(208, "Fast-forward", 0.80f, 1.29f, 0.08f),
        // 5. Volume rocker | Mute | Channel rocker
        b(115, "Vol +", 0.18f, 1.51f, 0.075f),
        b(402, "Ch +", 0.82f, 1.51f, 0.075f),
        b(113, "Mute", 0.50f, 1.62f, 0.075f),
        b(114, "Vol −", 0.18f, 1.73f, 0.075f),
        b(403, "Ch −", 0.82f, 1.73f, 0.075f),
        // 6. Recents | Live TV | Settings
        b(748, "Recents", 0.20f, 1.94f, 0.07f),
        b(362, "Live TV", 0.50f, 1.94f, 0.07f),
        b(249, "Settings", 0.80f, 1.94f, 0.07f),
        // 7. App buttons
        pill(745, "prime video", 0.29f, 2.15f, 0.16f),
        pill(744, "NETFLIX", 0.71f, 2.15f, 0.16f),
        pill(747, "amazon music", 0.29f, 2.36f, 0.16f),
        pill(746, "APPS", 0.71f, 2.36f, 0.16f),
    };

    /** Volume / channel rocker pills drawn behind the +/− buttons: {cx, top y, bottom y} as fractions. */
    public static final float[][] ROCKERS = { { 0.18f, 1.51f / ASPECT, 1.73f / ASPECT }, { 0.82f, 1.51f / ASPECT, 1.73f / ASPECT } };
    public static final String[] ROCKER_LABELS = { "VOL", "CH" };
    /** Rocker half-width in width units. */
    public static final float ROCKER_W = 0.10f;
    /** Body corner radius as a fraction of the body width. */
    public static final float CORNER = 0.30f;

    public static Btn find(int code) {
        for (Btn x : BUTTONS) if (x.code == code) return x;
        return null;
    }

    /** True when the two buttons' drawn shapes touch or overlap (all maths in width units). */
    public static boolean overlaps(Btn a, Btn b) { return overlaps(a, b, 0); }

    /** Same, with both shapes grown by m width units on every side (e.g. m = focusMargin()). */
    public static boolean overlaps(Btn a, Btn b, float m) {
        if (a.round && b.round) {
            return Math.hypot(a.cx - b.cx, a.y() - b.y()) < a.r + b.r + 2 * m;
        }
        if (!a.round && !b.round) {
            return Math.abs(a.cx - b.cx) < a.hw() + b.hw() + 2 * m && Math.abs(a.y() - b.y()) < a.hh() + b.hh() + 2 * m;
        }
        Btn c = a.round ? a : b, p = a.round ? b : a;
        float hw = p.hw() + m, hh = p.hh() + m;
        float qx = Math.max(p.cx - hw, Math.min(c.cx, p.cx + hw));
        float qy = Math.max(p.y() - hh, Math.min(c.y(), p.y() + hh));
        return Math.hypot(c.cx - qx, c.y() - qy) < c.r + m;
    }

    /**
     * Spatial focus move. dir: 0 up, 1 down, 2 left, 3 right. Candidates have their centre strictly in that
     * direction and within a cone (off-axis offset at most twice the on-axis distance); the best minimises
     * distance + 2 * off-axis offset. Returns fromCode when there is none.
     */
    public static int next(int fromCode, int dir) {
        Btn f = find(fromCode);
        if (f == null) return fromCode;
        Btn best = null;
        double bestScore = Double.MAX_VALUE;
        for (Btn c : BUTTONS) {
            if (c == f) continue;
            double dx = c.cx - f.cx, dy = c.y() - f.y();
            double along, off;
            switch (dir) {
                case UP: along = -dy; off = Math.abs(dx); break;
                case DOWN: along = dy; off = Math.abs(dx); break;
                case LEFT: along = -dx; off = Math.abs(dy); break;
                case RIGHT: along = dx; off = Math.abs(dy); break;
                default: return fromCode;
            }
            if (along <= 1e-4 || off > 2 * along) continue;
            double score = Math.hypot(dx, dy) + 2 * off;
            if (score < bestScore) { bestScore = score; best = c; }
        }
        return best == null ? fromCode : best.code;
    }
}
