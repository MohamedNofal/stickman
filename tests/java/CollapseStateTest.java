import com.clean.stickman.core.CollapseState;
import com.clean.stickman.core.Treemap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CollapseStateTest {
    static final List<String> KEYS = Arrays.asList("big", "mid", "x", "y");

    /** x has share xPct, y always 2 %, mid 30 %, big the rest. */
    static List<Double> w(double xPct) { return Arrays.asList(100 - 30 - 2 - xPct, 30.0, xPct, 2.0); }

    static Set<String> hidden(Treemap.Collapsed c) { return new HashSet<>(c.hidden); }

    public void testNoFlapNearThresholdWhenHidden() {
        Treemap.Collapsed c = CollapseState.next(new HashSet<String>(), KEYS, w(3.0), 0.035, 0.045);
        T.eq(new HashSet<>(Arrays.asList("x", "y")), hidden(c), "3 % hides x");
        for (int i = 0; i < 6; i++) {
            c = CollapseState.next(hidden(c), KEYS, w(i % 2 == 0 ? 4.1 : 3.9), 0.035, 0.045);
            T.eq(new HashSet<>(Arrays.asList("x", "y")), hidden(c), "x stays hidden at tick " + i);
            T.eq(Treemap.MORE, c.keys.get(c.keys.size() - 1), "more tile kept");
        }
    }

    public void testNoFlapNearThresholdWhenShown() {
        Treemap.Collapsed c = CollapseState.next(new HashSet<String>(), KEYS, w(5.0), 0.035, 0.045);
        T.eq(new ArrayList<String>(), c.hidden, "fresh map with one small app (y) is not collapsed");
        T.yes(!c.keys.contains(Treemap.MORE), "no more tile");
        Set<String> prev = new HashSet<>();   // start uncollapsed
        Treemap.Collapsed d = CollapseState.next(prev, KEYS, w(4.1), 0.035, 0.045);
        T.eq(new ArrayList<String>(), d.hidden, "single hidden, not collapsed before: shown individually");
        for (int i = 0; i < 6; i++) {
            d = CollapseState.next(hidden(d), KEYS, w(i % 2 == 0 ? 3.9 : 4.1), 0.035, 0.045);
            T.yes(!d.hidden.contains("x"), "x stays shown at tick " + i);
            T.yes(d.keys.contains("x"), "x tile present at tick " + i);
        }
    }

    public void testClearRiseUnhidesAndClearFallHides() {
        Set<String> prev = new HashSet<>(Arrays.asList("x", "y"));
        Treemap.Collapsed c = CollapseState.next(prev, KEYS, w(4.6), 0.035, 0.045);
        T.yes(!c.hidden.contains("x") && c.keys.contains("x"), "rise above 4.5 % unhides x");
        T.yes(c.hidden.contains("y") && c.keys.contains(Treemap.MORE), "y stays collapsed (sticky single)");
        c = CollapseState.next(hidden(c), KEYS, w(3.4), 0.035, 0.045);
        T.eq(new HashSet<>(Arrays.asList("x", "y")), hidden(c), "fall below 3.5 % hides x");
    }

    public void testStickySingleUncollapsesWhenEmpty() {
        Set<String> prev = new HashSet<>(Arrays.asList("y"));
        List<Double> ws = Arrays.asList(60.0, 30.0, 5.0, 5.0);
        Treemap.Collapsed c = CollapseState.next(prev, KEYS, ws, 0.035, 0.045);
        T.eq(new ArrayList<String>(), c.hidden, "nothing hidden");
        T.yes(!c.keys.contains(Treemap.MORE), "no more tile");
        T.eq(4, c.keys.size(), "all shown");
    }

    public void testKeepTopTenFirst() {
        List<String> k = new ArrayList<>();
        List<Double> ws = new ArrayList<>();
        for (int i = 0; i < 14; i++) { k.add("p" + (char) ('a' + i)); ws.add(i < 10 ? 1.0 : 0.47); }   // last 4 ~3.96 %
        Set<String> prev = new HashSet<>(k);   // everything was hidden
        Treemap.Collapsed c = CollapseState.next(prev, k, ws, 0.035, 0.045);
        for (int i = 0; i < 10; i++) T.yes(!c.hidden.contains(k.get(i)), "top 10 forced visible: " + k.get(i));
        for (int i = 10; i < 14; i++) T.yes(c.hidden.contains(k.get(i)), "rest stay hidden (below 4.5 %): " + k.get(i));
    }

    public void testWeightsAndOrder() {
        Treemap.Collapsed c = CollapseState.next(new HashSet<String>(), KEYS, w(3.0), 0.035, 0.045);
        T.eq(Arrays.asList("big", "mid", Treemap.MORE), c.keys, "order kept, more last");
        T.eq(5.0, c.weights.get(2), "more weight = sum of hidden");
    }
}
