package com.clean.stickman.core;

/**
 * Pure geometry of a drawn Fire TV remote (one of RemotePresets), used by RemoteView and for D-pad focus moves.
 * cx is a 0..1 fraction of the body width, cy a 0..1 fraction of the body height; r is in body-width units
 * (the body is Btn.aspect times taller than wide). Round buttons are circles of radius r; the others (app
 * buttons) are pills r wide either side of the centre and PILL_H * r tall either side. Buttons are addressed by
 * their index in the preset's array: unlearned buttons all have code 0.
 */
public final class RemoteLayout {
    private RemoteLayout() {}

    /** Default body height / body width (the Enhanced remote). */
    public static final float ASPECT = 2.55f;
    /** Tallest preset body; at MIN_BODY_DP wide it still fits a 1080p TV page. */
    public static final float MAX_ASPECT = 2.6f;
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
     * Body rectangle {x, y, w, h} in a w x h view: as tall as the view allows, but never wider than a
     * MAX_ASPECT body would be (so short remotes keep the same button scale) nor than the space left of the
     * hint strip.
     */
    public static float[] body(float viewW, float viewH, float dp, float aspect) {
        float pad = BODY_PAD_DP * dp, side = (HINT_GAP_DP + HINT_AREA_DP) * dp;
        float bw = Math.min((viewH - 2 * pad) / MAX_ASPECT, viewW - pad - side);
        bw = Math.max(1, bw);
        float bh = bw * aspect;
        float bx = Math.max(pad, (viewW - bw - side) / 2f), by = (viewH - bh) / 2f;
        return new float[] { bx, by, bw, bh };
    }

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
        /** Linux key code; 0 = not known yet (learned in listen mode). */
        public final int code;
        /** Stable id within its preset ("k<code>" for known keys, "app1".."app4", "custom1", ...). */
        public final String slot;
        /** Human name (editor titles, side card). */
        public final String name;
        /** Text drawn on the button when it has no glyph. */
        public final String label;
        /** Glyph id for buttons whose icon isn't keyed by code (mic, star, sliders, headphones), else null. */
        public final String glyph;
        public final float cx, cy, r;
        public final boolean round;
        /** Body height / width of the preset this button belongs to. */
        public final float aspect;

        public Btn(int code, String slot, String name, String label, String glyph, float cx, float cy, float r,
                   boolean round, float aspect) {
            this.code = code;
            this.slot = slot;
            this.name = name;
            this.label = label;
            this.glyph = glyph;
            this.cx = cx;
            this.cy = cy;
            this.r = r;
            this.round = round;
            this.aspect = aspect;
        }

        /** Plain button on the default-aspect body (tests). */
        public Btn(int code, String label, float cx, float cy, float r, boolean round) {
            this(code, "k" + code, label, label, null, cx, cy, r, round, ASPECT);
        }

        public Btn withCode(int c) { return new Btn(c, slot, name, label, glyph, cx, cy, r, round, aspect); }
        public Btn withName(String n) { return new Btn(code, slot, n, n, glyph, cx, cy, r, round, aspect); }

        /** App buttons (pills) carry a brand / user label. */
        public boolean isApp() { return slot.startsWith("app"); }

        /** Half extents in width units. */
        public float hw() { return r; }
        public float hh() { return round ? r : r * PILL_H; }
        /** Centre y in width units. */
        float y() { return cy * aspect; }
    }

    /** Rocker half-width in width units. */
    public static final float ROCKER_W = 0.10f;

    /** Buttons of a preset (RemotePresets.DEFAULT when the id is unknown). */
    public static Btn[] forPreset(String id) { return RemotePresets.get(id).buttons; }

    /** Index of the button sending code (never matches 0, the unlearned code), or -1. */
    public static int indexOf(Btn[] bs, int code) {
        if (code == 0) return -1;
        for (int i = 0; i < bs.length; i++) if (bs[i].code == code) return i;
        return -1;
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
     * Spatial focus move between buttons of bs. dir: 0 up, 1 down, 2 left, 3 right. Candidates have their centre
     * strictly in that direction and within a cone (off-axis offset at most twice the on-axis distance); the best
     * minimises distance + 2 * off-axis offset. Returns from when there is none.
     */
    public static int next(Btn[] bs, int from, int dir) {
        if (from < 0 || from >= bs.length) return from;
        Btn f = bs[from];
        int best = from;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < bs.length; i++) {
            if (i == from) continue;
            Btn c = bs[i];
            double dx = c.cx - f.cx, dy = c.y() - f.y();
            double along, off;
            switch (dir) {
                case UP: along = -dy; off = Math.abs(dx); break;
                case DOWN: along = dy; off = Math.abs(dx); break;
                case LEFT: along = -dx; off = Math.abs(dy); break;
                case RIGHT: along = dx; off = Math.abs(dy); break;
                default: return from;
            }
            if (along <= 1e-4 || off > 2 * along) continue;
            double score = Math.hypot(dx, dy) + 2 * off;
            if (score < bestScore) { bestScore = score; best = i; }
        }
        return best;
    }
}
