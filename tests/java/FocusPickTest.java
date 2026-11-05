import com.clean.stickman.core.FocusPick;

public class FocusPickTest {
    static final int[] VIEW = { 0, 100, 1000, 600 };

    public void testFirstVisibleInReadingOrder() {
        int[][] r = {
            { 0, 20, 100, 60 },      // scrolled off the top
            { 300, 200, 400, 240 },  // same line as the next one, but to its right
            { 100, 205, 200, 245 },
            { 0, 400, 100, 440 },
        };
        T.eq(2, FocusPick.first(r, VIEW), "top-left visible, off-screen one skipped");
    }

    public void testPartlyVisibleIsNotVisible() {
        int[][] r = { { 0, 80, 100, 130 }, { 0, 300, 100, 340 } };
        T.eq(1, FocusPick.first(r, VIEW), "half-hidden control is skipped");
    }

    public void testNoneVisiblePicksNearest() {
        int[][] r = { { 0, 0, 100, 40 }, { 0, 700, 100, 740 }, { 0, 620, 100, 660 } };
        T.eq(2, FocusPick.first(r, VIEW), "closest to the visible area");
        T.eq(-1, FocusPick.first(new int[0][], VIEW), "nothing focusable");
    }
}
