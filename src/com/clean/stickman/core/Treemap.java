package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Squarified treemap (Bruls, Huizing, van Wijk). Pure Java, no android.* imports.
 * Rows are laid along the shorter side of the remaining rectangle; input order is the
 * placement order. The last rect of every row (and the last row) absorbs rounding, so
 * the rects tile the target area exactly.
 */
public final class Treemap {
    private Treemap() {}

    /** Key of the collapsed "+N more" tile. Package names never contain '+'. */
    public static final String MORE = "+more";
    /** Weight given to entries whose weight is <= 0 (or NaN). */
    static final double EPS = 1e-9;

    public static final class Rect {
        public final String key;
        public final double x, y, w, h;

        public Rect(String key, double x, double y, double w, double h) {
            this.key = key; this.x = x; this.y = y; this.w = w; this.h = h;
        }

        public double cx() { return x + w / 2; }
        public double cy() { return y + h / 2; }
        public double area() { return w * h; }

        @Override public String toString() {
            return key + "[" + x + "," + y + " " + w + "x" + h + "]";
        }
    }

    public static final class Collapsed {
        public final List<String> keys;
        public final List<Double> weights;
        public final List<String> hidden;

        Collapsed(List<String> keys, List<Double> weights, List<String> hidden) {
            this.keys = keys; this.weights = weights; this.hidden = hidden;
        }
    }

    static double clean(Double w) {
        return w == null || Double.isNaN(w) || Double.isInfinite(w) || w <= 0 ? EPS : w;
    }

    public static List<Rect> layout(List<String> keys, List<Double> weights,
                                    double x, double y, double w, double h) {
        int n = Math.min(keys.size(), weights.size());
        List<Rect> out = new ArrayList<>(n);
        if (n == 0) return out;
        if (!(w > 0) || !(h > 0)) {
            for (int i = 0; i < n; i++) out.add(new Rect(keys.get(i), x, y, 0, 0));
            return out;
        }
        double total = 0;
        double[] a = new double[n];
        for (int i = 0; i < n; i++) { a[i] = clean(weights.get(i)); total += a[i]; }
        if (Double.isInfinite(total)) {   // huge finite weights overflowing the sum
            total = 0;
            for (int i = 0; i < n; i++) { a[i] /= n; total += a[i]; }
        }
        double scale = w * h / total;
        for (int i = 0; i < n; i++) a[i] *= scale;

        double rx = x, ry = y, rw = w, rh = h;
        int i = 0;
        while (i < n) {
            double side = Math.min(rw, rh);
            int j = i + 1;
            double sum = a[i];
            double worst = worst(a, i, j, sum, side);
            while (j < n) {
                double s2 = sum + a[j];
                double w2 = worst(a, i, j + 1, s2, side);
                if (w2 > worst) break;
                sum = s2; worst = w2; j++;
            }
            boolean last = j == n;
            if (rw >= rh) {
                // vertical column on the left, items stacked top to bottom
                double cw = last ? rw : Math.min(rw, sum / rh);
                double cy = ry;
                for (int k = i; k < j; k++) {
                    double ih = k == j - 1 ? ry + rh - cy : a[k] / cw;
                    out.add(new Rect(keys.get(k), rx, cy, cw, Math.max(0, ih)));
                    cy += ih;
                }
                rx += cw; rw = Math.max(0, rw - cw);
            } else {
                // horizontal row on top, items left to right
                double ch = last ? rh : Math.min(rh, sum / rw);
                double cx = rx;
                for (int k = i; k < j; k++) {
                    double iw = k == j - 1 ? rx + rw - cx : a[k] / ch;
                    out.add(new Rect(keys.get(k), cx, ry, Math.max(0, iw), ch));
                    cx += iw;
                }
                ry += ch; rh = Math.max(0, rh - ch);
            }
            i = j;
        }
        return out;
    }

    /** Worst aspect ratio of the row a[from..to) of total area sum laid along side. */
    static double worst(double[] a, int from, int to, double sum, double side) {
        double max = 0, min = Double.MAX_VALUE;
        for (int k = from; k < to; k++) { max = Math.max(max, a[k]); min = Math.min(min, a[k]); }
        double s2 = side * side, sum2 = sum * sum;
        return Math.max(s2 * max / sum2, sum2 / (s2 * min));
    }

    /** Below this many entries no minimum of individual tiles is enforced. */
    static final int KEEP_ABOVE = 12;
    /** With more than KEEP_ABOVE entries, the top KEEP entries (by weight) always stay individual. */
    static final int KEEP = 10;

    public static Collapsed collapse(List<String> keys, List<Double> weights, double minFraction) {
        int n = Math.min(keys.size(), weights.size());
        final double[] cw = new double[n];
        double total = 0;
        for (int i = 0; i < n; i++) { cw[i] = clean(weights.get(i)); total += cw[i]; }
        boolean[] hide = new boolean[n];
        for (int i = 0; i < n; i++) hide[i] = cw[i] / total < minFraction;
        if (n > KEEP_ABOVE) {
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            // stable sort: ties keep input order
            java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
                @Override public int compare(Integer p, Integer q) { return Double.compare(cw[q], cw[p]); }
            });
            for (int r = 0; r < KEEP; r++) hide[order[r]] = false;
        }
        List<String> hidden = new ArrayList<>();
        for (int i = 0; i < n; i++) if (hide[i]) hidden.add(keys.get(i));
        List<String> k = new ArrayList<>();
        List<Double> w = new ArrayList<>();
        if (hidden.size() <= 1) {
            for (int i = 0; i < n; i++) { k.add(keys.get(i)); w.add(weights.get(i)); }
            return new Collapsed(k, w, new ArrayList<String>());
        }
        double more = 0;
        for (int i = 0; i < n; i++) {
            if (hide[i]) { more += cw[i]; continue; }
            k.add(keys.get(i)); w.add(weights.get(i));
        }
        k.add(MORE); w.add(more);
        return new Collapsed(k, w, hidden);
    }

    public static boolean needsRelayout(List<Rect> prev, List<String> keys, List<Double> weights,
                                        double threshold) {
        int n = Math.min(keys.size(), weights.size());
        if (prev == null || prev.size() != n) return true;
        Map<String, Double> prevArea = new HashMap<>();
        double prevTotal = 0;
        for (Rect r : prev) { prevArea.put(r.key, r.area()); prevTotal += r.area(); }
        if (!prevArea.keySet().equals(new HashSet<>(keys.subList(0, n)))) return true;
        if (n == 0) return false;
        double total = 0;
        for (int i = 0; i < n; i++) total += clean(weights.get(i));
        if (!(prevTotal > 0)) return true;
        for (int i = 0; i < n; i++) {
            double now = clean(weights.get(i)) / total;
            double was = prevArea.get(keys.get(i)) / prevTotal;
            if (Math.abs(now - was) > threshold) return true;
        }
        return false;
    }

    /**
     * Equal-size grid in reading order: rows filled left to right, top to bottom (Sort by name). The column count makes
     * the cells closest to square; the last row's cells stretch to fill the width, so the grid tiles the area exactly.
     */
    public static List<Rect> grid(List<String> keys, double x, double y, double w, double h) {
        int n = keys.size();
        List<Rect> out = new ArrayList<>(n);
        if (n == 0) return out;
        if (!(w > 0) || !(h > 0)) {
            for (String k : keys) out.add(new Rect(k, x, y, 0, 0));
            return out;
        }
        int cols = gridCols(n, w, h);
        int rows = (n + cols - 1) / cols;
        double ch = h / rows;
        for (int i = 0; i < n; i++) {
            int r = i / cols, c = i % cols;
            int inRow = r == rows - 1 ? n - r * cols : cols;
            double cw = w / inRow;
            double rx = x + c * cw, ry = y + r * ch;
            double rw = c == inRow - 1 ? x + w - rx : cw;   // last cell absorbs rounding
            double rh = r == rows - 1 ? y + h - ry : ch;
            out.add(new Rect(keys.get(i), rx, ry, rw, rh));
        }
        return out;
    }

    /** Columns for n equal cells in w x h whose full-row cells are closest to square. */
    static int gridCols(int n, double w, double h) {
        int best = 1;
        double bestScore = Double.MAX_VALUE;
        for (int c = 1; c <= n; c++) {
            int r = (n + c - 1) / c;
            double cw = w / c, ch = h / r;
            double score = Math.abs(Math.log(cw / ch));
            if (score < bestScore - 1e-9) { bestScore = score; best = c; }
        }
        return best;
    }

    /** True if the grid's keys differ from prev's, in order (Sort by name relayouts only when the app list changes). */
    public static boolean gridChanged(List<Rect> prev, List<String> keys) {
        if (prev == null || prev.size() != keys.size()) return true;
        for (int i = 0; i < keys.size(); i++) if (!prev.get(i).key.equals(keys.get(i))) return true;
        return false;
    }
}
