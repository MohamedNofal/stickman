import com.clean.stickman.core.Treemap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TreemapTest {
    static final double E = 1e-6;

    static List<String> keys(int n) {
        List<String> k = new ArrayList<>();
        for (int i = 0; i < n; i++) k.add(String.valueOf((char) ('a' + i)) + (i >= 26 ? i : ""));
        return k;
    }

    static void inside(Treemap.Rect r, double x, double y, double w, double h) {
        T.yes(!Double.isNaN(r.x) && !Double.isNaN(r.y) && !Double.isNaN(r.w) && !Double.isNaN(r.h), "no NaN " + r);
        T.yes(r.w >= 0 && r.h >= 0, "non-negative " + r);
        T.yes(r.x >= x - E && r.y >= y - E && r.x + r.w <= x + w + E && r.y + r.h <= y + h + E, "inside " + r);
    }

    /** Invariants every layout must hold: no NaN, inside the target, pairwise disjoint, exact total area. */
    static void check(List<Treemap.Rect> rs, int n, double x, double y, double w, double h, String msg) {
        T.eq(n, rs.size(), msg + " count");
        double total = 0;
        for (Treemap.Rect r : rs) { inside(r, x, y, w, h); total += r.w * r.h; }
        T.yes(Math.abs(total - w * h) < E * Math.max(1, w * h), msg + " total area " + total);
        for (int i = 0; i < rs.size(); i++)
            for (int j = i + 1; j < rs.size(); j++)
                T.yes(overlap(rs.get(i), rs.get(j)) < 1e-9 * Math.max(1, w * h), msg + " overlap " + rs.get(i) + " " + rs.get(j));
    }

    static double overlap(Treemap.Rect a, Treemap.Rect b) {
        double ox = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
        double oy = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
        return ox > 0 && oy > 0 ? ox * oy : 0;
    }

    public void testCoversAndNoOverlap() {
        List<Double> w = Arrays.asList(6.0, 6.0, 4.0, 3.0, 2.0, 1.0);
        List<Treemap.Rect> rs = Treemap.layout(keys(6), w, 0, 0, 1, 1);
        T.eq(6, rs.size(), "count");
        double total = 0, sum = 22;
        for (int i = 0; i < rs.size(); i++) {
            Treemap.Rect r = rs.get(i);
            T.eq(keys(6).get(i), r.key, "order preserved");
            inside(r, 0, 0, 1, 1);
            total += r.w * r.h;
            T.yes(Math.abs(r.w * r.h - w.get(i) / sum) < E, "proportional " + r);
        }
        T.yes(Math.abs(total - 1) < E, "total area " + total);
        for (int i = 0; i < rs.size(); i++)
            for (int j = i + 1; j < rs.size(); j++)
                T.yes(overlap(rs.get(i), rs.get(j)) < 1e-9, "overlap " + rs.get(i) + " " + rs.get(j));
    }

    public void testNonSquareAreaAndOffset() {
        List<Double> w = Arrays.asList(5.0, 3.0, 1.0, 1.0);
        List<Treemap.Rect> rs = Treemap.layout(keys(4), w, 100, 50, 1600, 900);
        double total = 0;
        for (Treemap.Rect r : rs) { inside(r, 100, 50, 1600, 900); total += r.w * r.h; }
        T.yes(Math.abs(total - 1600.0 * 900) < 1e-3, "total area " + total);
    }

    public void testDegenerateInputs() {
        T.eq(0, Treemap.layout(new ArrayList<String>(), new ArrayList<Double>(), 0, 0, 1, 1).size(), "empty");
        List<Treemap.Rect> one = Treemap.layout(keys(1), Arrays.asList(3.0), 0, 0, 1, 1);
        T.eq(1, one.size(), "one");
        Treemap.Rect r = one.get(0);
        T.yes(Math.abs(r.x) < E && Math.abs(r.y) < E && Math.abs(r.w - 1) < E && Math.abs(r.h - 1) < E, "full " + r);
        List<Treemap.Rect> z = Treemap.layout(keys(4), Arrays.asList(0.0, 0.0, -1.0, 0.0), 0, 0, 1, 1);
        for (Treemap.Rect q : z) {
            inside(q, 0, 0, 1, 1);
            T.yes(Math.abs(q.w * q.h - 0.25) < E, "equal " + q);
        }
        List<Double> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many.add((double) (i * 37 % 11 + 1));
        check(Treemap.layout(keys(40), many, 0, 0, 1, 1), 40, 0, 0, 1, 1, "40 apps");

        // (a) one dominant weight + 39 zeros
        List<Double> dom = new ArrayList<>();
        dom.add(1000.0);
        for (int i = 0; i < 39; i++) dom.add(0.0);
        check(Treemap.layout(keys(40), dom, 0, 0, 1, 1), 40, 0, 0, 1, 1, "dominant");

        // Infinity / NaN / huge weights never produce NaN rects
        check(Treemap.layout(keys(4), Arrays.asList(Double.POSITIVE_INFINITY, 1.0, 2.0, Double.NaN), 0, 0, 1, 1),
                4, 0, 0, 1, 1, "infinity");
        check(Treemap.layout(keys(3), Arrays.asList(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN), 0, 0, 1, 1),
                3, 0, 0, 1, 1, "all non-finite");
        check(Treemap.layout(keys(3), Arrays.asList(Double.MAX_VALUE, Double.MAX_VALUE, 1.0), 0, 0, 1, 1),
                3, 0, 0, 1, 1, "overflowing sum");

        // (b) w or h <= 0 -> 0x0 rects, no NaN
        double[][] bad = {{0, 1}, {1, 0}, {-5, 1}, {1, -5}, {0, 0}, {Double.NaN, 1}};
        for (double[] d : bad) {
            List<Treemap.Rect> rs = Treemap.layout(keys(3), Arrays.asList(1.0, 2.0, 3.0), 7, 9, d[0], d[1]);
            T.eq(3, rs.size(), "degenerate size count");
            for (Treemap.Rect q : rs) {
                T.yes(q.w == 0 && q.h == 0 && q.x == 7 && q.y == 9, "0x0 at origin " + q);
                T.yes(!Double.isNaN(q.x + q.y + q.w + q.h), "no NaN " + q);
            }
        }

        // (c) collapse -> layout on 40 apps of random weights
        java.util.Random rnd = new java.util.Random(42);
        for (int round = 0; round < 20; round++) {
            List<Double> rw = new ArrayList<>();
            for (int i = 0; i < 40; i++) rw.add(rnd.nextInt(4) == 0 ? 0.0 : rnd.nextDouble() * 500000);
            Treemap.Collapsed c = Treemap.collapse(keys(40), rw, 0.04);
            check(Treemap.layout(c.keys, c.weights, 0, 0, 1920, 1080), c.keys.size(), 0, 0, 1920, 1080, "collapsed " + round);
        }
    }

    public void testCollapse() {
        Treemap.Collapsed c = Treemap.collapse(keys(6), Arrays.asList(0.5, 0.3, 0.1, 0.05, 0.03, 0.02), 0.04);
        T.eq(Arrays.asList("a", "b", "c", "d", Treemap.MORE), c.keys, "keys");
        T.eq(Arrays.asList("e", "f"), c.hidden, "hidden");
        T.eq(5, c.weights.size(), "weights");
        T.yes(Math.abs(c.weights.get(4) - 0.05) < E, "MORE weight " + c.weights.get(4));
        Treemap.Collapsed u = Treemap.collapse(keys(2), Arrays.asList(0.9, 0.1), 0.04);
        T.eq(Arrays.asList("a", "b"), u.keys, "unchanged keys");
        T.eq(Arrays.asList(0.9, 0.1), u.weights, "unchanged weights");
        T.eq(0, u.hidden.size(), "nothing hidden");
        Treemap.Collapsed single = Treemap.collapse(keys(3), Arrays.asList(0.6, 0.38, 0.02), 0.04);
        T.eq(Arrays.asList("a", "b", "c"), single.keys, "one small entry is not collapsed");
        T.eq(0, single.hidden.size(), "nothing hidden (single)");
    }

    public void testCollapseKeepsTopTen() {
        List<Double> eq = new ArrayList<>();
        for (int i = 0; i < 40; i++) eq.add(1.0);
        Treemap.Collapsed c = Treemap.collapse(keys(40), eq, 0.04);
        T.eq(11, c.keys.size(), "10 individual + MORE");
        T.eq(keys(40).subList(0, 10), c.keys.subList(0, 10), "first ten (ties in input order)");
        T.eq(Treemap.MORE, c.keys.get(10), "MORE last");
        T.eq(30, c.hidden.size(), "30 hidden");
        T.yes(Math.abs(c.weights.get(10) - 30.0) < E, "MORE weight");

        // the ten kept are the ten heaviest, not the first ten
        List<Double> w = new ArrayList<>();
        for (int i = 0; i < 20; i++) w.add((double) (i + 1));
        Treemap.Collapsed d = Treemap.collapse(keys(20), w, 0.5);
        T.eq(keys(20).subList(10, 20), d.keys.subList(0, 10), "heaviest ten kept");
        T.eq(keys(20).subList(0, 10), d.hidden, "lightest hidden");

        // n <= 12: no minimum
        List<Double> twelve = new ArrayList<>();
        for (int i = 0; i < 12; i++) twelve.add(1.0);
        T.eq(Arrays.asList(Treemap.MORE), Treemap.collapse(keys(12), twelve, 0.5).keys, "n=12 folds everything");
    }

    public void testNeedsRelayout() {
        List<String> k = keys(2);
        List<Treemap.Rect> prev = Treemap.layout(k, Arrays.asList(0.5, 0.5), 0, 0, 1, 1);
        T.yes(!Treemap.needsRelayout(prev, k, Arrays.asList(0.54, 0.46), 0.05), "4 % -> no relayout");
        T.yes(Treemap.needsRelayout(prev, k, Arrays.asList(0.56, 0.44), 0.05), "6 % -> relayout");
        T.yes(Treemap.needsRelayout(prev, keys(3), Arrays.asList(0.5, 0.4, 0.1), 0.05), "key added");
        T.yes(Treemap.needsRelayout(prev, Arrays.asList("a", "z"), Arrays.asList(0.5, 0.5), 0.05), "key swapped");
        T.yes(Treemap.needsRelayout(null, k, Arrays.asList(0.5, 0.5), 0.05), "no previous layout");
    }

    /** Sort by name: rows fill left to right, then top to bottom (reading order), and tile the area exactly. */
    public void testGridFillsRows() {
        List<Treemap.Rect> g = Treemap.grid(keys(7), 0, 0, 1600, 600);
        T.eq(7, g.size(), "count");
        double area = 0;
        for (Treemap.Rect r : g) { inside(r, 0, 0, 1600, 600); area += r.area(); }
        T.yes(Math.abs(area - 1600 * 600) < 1e-3, "tiles exactly");
        // reading order: y never goes back up; within a row x increases
        for (int i = 1; i < g.size(); i++) {
            Treemap.Rect p = g.get(i - 1), r = g.get(i);
            T.yes(r.y > p.y + E || (Math.abs(r.y - p.y) < E && r.x > p.x + E), "reading order at " + i + ": " + p + " -> " + r);
        }
        T.yes(Math.abs(g.get(0).y - g.get(1).y) < E, "a and b share the first row (rows, not columns)");
        T.eq("a", g.get(0).key, "first top-left");
        T.yes(g.get(0).x < E && g.get(0).y < E, "top-left");
    }

    public void testGridShapes() {
        T.eq(1, Treemap.grid(keys(1), 0, 0, 100, 100).size(), "one");
        Treemap.Rect one = Treemap.grid(keys(1), 5, 5, 100, 50).get(0);
        T.yes(Math.abs(one.w - 100) < E && Math.abs(one.h - 50) < E, "one fills");
        List<Treemap.Rect> four = Treemap.grid(keys(4), 0, 0, 200, 200);
        T.yes(Math.abs(four.get(0).w - 100) < E && Math.abs(four.get(0).h - 100) < E, "2x2 on a square");
        T.yes(Math.abs(four.get(2).y - 100) < E && four.get(2).x < E, "c starts row 2");
        T.eq(0, Treemap.grid(new ArrayList<String>(), 0, 0, 10, 10).size(), "empty");
        for (Treemap.Rect r : Treemap.grid(keys(3), 0, 0, 0, 10)) T.yes(r.w == 0 && r.h == 0, "zero area");
    }

    public void testGridChanged() {
        List<Treemap.Rect> g = Treemap.grid(Arrays.asList("a", "b", "c"), 0, 0, 300, 100);
        T.yes(!Treemap.gridChanged(g, Arrays.asList("a", "b", "c")), "same list: no relayout");
        T.yes(Treemap.gridChanged(g, Arrays.asList("a", "c", "b")), "order changed");
        T.yes(Treemap.gridChanged(g, Arrays.asList("a", "b")), "app gone");
        T.yes(Treemap.gridChanged(null, Arrays.asList("a")), "no previous");
    }
}
