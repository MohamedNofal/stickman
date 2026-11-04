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
    public static final float ASPECT = 2.4f;
    /** Pill half-height as a fraction of r. */
    public static final float PILL_H = 0.45f;

    public static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;

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

    /** Centre of the D-pad ring and its outer radius (width units, y in width units). */
    public static final float RING_Y = 0.58f / ASPECT, RING_R = 0.315f;

    public static final Btn[] BUTTONS = {
        // 1. Power | Alexa
        b(116, "Power", 0.22f, 0.17f, 0.075f),
        b(217, "Alexa", 0.50f, 0.17f, 0.085f),
        // 2. D-pad ring around OK
        b(103, "Up", 0.50f, 0.37f, 0.085f),
        b(105, "Left", 0.29f, 0.58f, 0.085f),
        b(96, "OK", 0.50f, 0.58f, 0.11f),
        b(106, "Right", 0.71f, 0.58f, 0.085f),
        b(108, "Down", 0.50f, 0.79f, 0.085f),
        // 3. Back | Home | Menu
        b(158, "Back", 0.20f, 1.03f, 0.085f),
        b(172, "Home", 0.50f, 1.03f, 0.085f),
        b(139, "Menu", 0.80f, 1.03f, 0.085f),
        // 4. Rewind | Play/Pause | Fast-forward
        b(168, "Rewind", 0.20f, 1.25f, 0.085f),
        b(164, "Play/Pause", 0.50f, 1.25f, 0.085f),
        b(208, "Fast-forward", 0.80f, 1.25f, 0.085f),
        // 5. Volume rocker | Mute | Channel rocker
        b(115, "Vol +", 0.18f, 1.47f, 0.08f),
        b(402, "Ch +", 0.82f, 1.47f, 0.08f),
        b(113, "Mute", 0.50f, 1.57f, 0.08f),
        b(114, "Vol −", 0.18f, 1.67f, 0.08f),
        b(403, "Ch −", 0.82f, 1.67f, 0.08f),
        // 6. Recents | Live TV | Settings
        b(748, "Recents", 0.20f, 1.89f, 0.075f),
        b(362, "Live TV", 0.50f, 1.89f, 0.075f),
        b(249, "Settings", 0.80f, 1.89f, 0.075f),
        // 7. App buttons
        pill(745, "prime video", 0.29f, 2.06f, 0.16f),
        pill(744, "NETFLIX", 0.71f, 2.06f, 0.16f),
        pill(747, "amazon music", 0.29f, 2.24f, 0.16f),
        pill(746, "APPS", 0.71f, 2.24f, 0.16f),
    };

    /** Volume / channel rocker pills drawn behind the +/− buttons: {cx, top y, bottom y} as fractions. */
    public static final float[][] ROCKERS = { { 0.18f, 1.47f / ASPECT, 1.67f / ASPECT }, { 0.82f, 1.47f / ASPECT, 1.67f / ASPECT } };
    public static final String[] ROCKER_LABELS = { "VOL", "CH" };
    /** Body corner radius as a fraction of the body width. */
    public static final float CORNER = 0.30f;

    public static Btn find(int code) {
        for (Btn x : BUTTONS) if (x.code == code) return x;
        return null;
    }

    /** True when the two buttons' drawn shapes touch or overlap (all maths in width units). */
    public static boolean overlaps(Btn a, Btn b) {
        if (a.round && b.round) {
            return Math.hypot(a.cx - b.cx, a.y() - b.y()) < a.r + b.r;
        }
        if (!a.round && !b.round) {
            return Math.abs(a.cx - b.cx) < a.hw() + b.hw() && Math.abs(a.y() - b.y()) < a.hh() + b.hh();
        }
        Btn c = a.round ? a : b, p = a.round ? b : a;
        float qx = Math.max(p.cx - p.hw(), Math.min(c.cx, p.cx + p.hw()));
        float qy = Math.max(p.y() - p.hh(), Math.min(c.y(), p.y() + p.hh()));
        return Math.hypot(c.cx - qx, c.y() - qy) < c.r;
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
