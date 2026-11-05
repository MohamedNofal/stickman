package com.clean.stickman.core;

/** Pure choice of the control to focus when entering a page: never one the user can't see. */
public final class FocusPick {
    private FocusPick() {}

    /** Rows whose tops differ by less than this (px) count as one line, read left to right. */
    public static final int ROW_SLOP = 12;

    /**
     * rects[i] = {left, top, right, bottom} on screen; view = visible area of the page.
     * Returns the first fully visible rect in reading order (top, then left); when none is fully visible,
     * the one closest to the visible area's top (the caller scrolls it into view); -1 when rects is empty.
     */
    public static int first(int[][] rects, int[] view) {
        int best = -1;
        for (int i = 0; i < rects.length; i++) {
            int[] r = rects[i];
            boolean in = r[0] >= view[0] && r[1] >= view[1] && r[2] <= view[2] && r[3] <= view[3];
            if (in && (best < 0 || before(r, rects[best]))) best = i;
        }
        if (best >= 0) return best;
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            int[] r = rects[i];
            long d = r[3] < view[1] ? (long) (view[1] - r[3]) + 1 : r[1] > view[3] ? (long) (r[1] - view[3]) : 0;
            if (d < bestD || d == bestD && before(r, rects[best])) { bestD = d; best = i; }
        }
        return best;
    }

    static boolean before(int[] a, int[] b) {
        if (Math.abs(a[1] - b[1]) >= ROW_SLOP) return a[1] < b[1];
        return a[0] < b[0];
    }
}
