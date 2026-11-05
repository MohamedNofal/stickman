package com.clean.stickman.core;

import java.util.List;

/** D-pad spatial navigation between treemap tiles; same scoring as RemoteLayout.next. */
public final class TileNav {
    private TileNav() {}

    public static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;

    /** Nearest rect whose centre lies in direction dir from rects[from]; from if none. */
    public static int next(List<Treemap.Rect> rects, int from, int dir) {
        if (rects == null || from < 0 || from >= rects.size()) return from;
        Treemap.Rect f = rects.get(from);
        int best = from;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < rects.size(); i++) {
            if (i == from) continue;
            Treemap.Rect c = rects.get(i);
            double dx = c.cx() - f.cx(), dy = c.cy() - f.cy();
            double along, off;
            switch (dir) {
                case UP: along = -dy; off = Math.abs(dx); break;
                case DOWN: along = dy; off = Math.abs(dx); break;
                case LEFT: along = -dx; off = Math.abs(dy); break;
                case RIGHT: along = dx; off = Math.abs(dy); break;
                default: return from;
            }
            if (along <= 1e-9 || off > 2 * along) continue;
            double score = Math.hypot(dx, dy) + 2 * off;
            if (score < bestScore) { bestScore = score; best = i; }
        }
        return best;
    }
}
