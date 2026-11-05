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
        List<Treemap.Rect> m = Treemap.layout(keys(40), many, 0, 0, 1, 1);
        T.eq(40, m.size(), "40 apps");
        double total = 0;
        for (Treemap.Rect q : m) { inside(q, 0, 0, 1, 1); total += q.w * q.h; }
        T.yes(Math.abs(total - 1) < E, "40 total " + total);
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

    public void testNeedsRelayout() {
        List<String> k = keys(2);
        List<Treemap.Rect> prev = Treemap.layout(k, Arrays.asList(0.5, 0.5), 0, 0, 1, 1);
        T.yes(!Treemap.needsRelayout(prev, k, Arrays.asList(0.54, 0.46), 0.05), "4 % -> no relayout");
        T.yes(Treemap.needsRelayout(prev, k, Arrays.asList(0.56, 0.44), 0.05), "6 % -> relayout");
        T.yes(Treemap.needsRelayout(prev, keys(3), Arrays.asList(0.5, 0.4, 0.1), 0.05), "key added");
        T.yes(Treemap.needsRelayout(prev, Arrays.asList("a", "z"), Arrays.asList(0.5, 0.5), 0.05), "key swapped");
        T.yes(Treemap.needsRelayout(null, k, Arrays.asList(0.5, 0.5), 0.05), "no previous layout");
    }
}
