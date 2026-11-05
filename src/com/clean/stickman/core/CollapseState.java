package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Sticky "+N more" membership for the Tasks map, so an app hovering near the minimum tile share doesn't re-layout the
 * map every sample. Pure Java.
 */
public final class CollapseState {
    private CollapseState() {}

    /** Default hysteresis band around the 4 % minimum tile share. */
    public static final double LO = 0.035, HI = 0.045;

    /**
     * Next collapse given the previous hidden set. With more than Treemap.KEEP_ABOVE entries the top Treemap.KEEP (by
     * weight) are always shown first. Of the rest, a previously hidden key stays hidden until its share rises above hi; a
     * shown (or new) key is hidden only when its share falls below lo. A lone hidden key collapses into the "+more" tile
     * only if the map was already collapsed (prevHidden non-empty); otherwise it stays an individual tile. Input order is
     * kept; Treemap.MORE (weight = sum of hidden) goes last.
     */
    public static Treemap.Collapsed next(Set<String> prevHidden, List<String> keys, List<Double> weights,
                                         double lo, double hi) {
        int n = Math.min(keys.size(), weights.size());
        final double[] cw = new double[n];
        double total = 0;
        for (int i = 0; i < n; i++) { cw[i] = Treemap.clean(weights.get(i)); total += cw[i]; }
        boolean[] keep = new boolean[n];
        if (n > Treemap.KEEP_ABOVE) {
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
                @Override public int compare(Integer p, Integer q) { return Double.compare(cw[q], cw[p]); }
            });
            for (int r = 0; r < Treemap.KEEP; r++) keep[order[r]] = true;
        }
        boolean wasCollapsed = false;
        boolean[] hide = new boolean[n];
        int hiddenCount = 0;
        for (int i = 0; i < n; i++) {
            boolean was = prevHidden != null && prevHidden.contains(keys.get(i));
            if (was) wasCollapsed = true;
            if (keep[i]) continue;
            double share = cw[i] / total;
            hide[i] = was ? share <= hi : share < lo;
            if (hide[i]) hiddenCount++;
        }
        List<String> k = new ArrayList<>();
        List<Double> w = new ArrayList<>();
        List<String> hidden = new ArrayList<>();
        if (hiddenCount == 0 || (hiddenCount == 1 && !wasCollapsed)) {
            for (int i = 0; i < n; i++) { k.add(keys.get(i)); w.add(weights.get(i)); }
            return new Treemap.Collapsed(k, w, hidden);
        }
        double more = 0;
        for (int i = 0; i < n; i++) {
            if (hide[i]) { more += cw[i]; hidden.add(keys.get(i)); continue; }
            k.add(keys.get(i)); w.add(weights.get(i));
        }
        k.add(Treemap.MORE); w.add(more);
        return new Treemap.Collapsed(k, w, hidden);
    }
}
