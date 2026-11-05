import com.clean.stickman.core.TileNav;
import com.clean.stickman.core.Treemap;

import java.util.Arrays;
import java.util.List;

public class TileNavTest {
    static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;

    public void testDirections() {
        // 0 1
        // 2 3
        List<Treemap.Rect> g = Arrays.asList(
                new Treemap.Rect("tl", 0, 0, 0.5, 0.5), new Treemap.Rect("tr", 0.5, 0, 0.5, 0.5),
                new Treemap.Rect("bl", 0, 0.5, 0.5, 0.5), new Treemap.Rect("br", 0.5, 0.5, 0.5, 0.5));
        T.eq(1, TileNav.next(g, 0, RIGHT), "tl right");
        T.eq(2, TileNav.next(g, 0, DOWN), "tl down");
        T.eq(0, TileNav.next(g, 1, LEFT), "tr left");
        T.eq(3, TileNav.next(g, 1, DOWN), "tr down");
        T.eq(0, TileNav.next(g, 2, UP), "bl up");
        T.eq(3, TileNav.next(g, 2, RIGHT), "bl right");
        T.eq(1, TileNav.next(g, 3, UP), "br up");
        T.eq(2, TileNav.next(g, 3, LEFT), "br left");
        T.eq(0, TileNav.next(g, 0, UP), "edge up");
        T.eq(0, TileNav.next(g, 0, LEFT), "edge left");
        T.eq(3, TileNav.next(g, 3, DOWN), "edge down");
        T.eq(3, TileNav.next(g, 3, RIGHT), "edge right");
        T.eq(-1, TileNav.next(g, -1, RIGHT), "bad index");
    }

    public void testTreemapLayoutNavigable() {
        // big left tile, two stacked on the right: moving right from the big one reaches a right tile
        List<Treemap.Rect> g = Arrays.asList(
                new Treemap.Rect("big", 0, 0, 0.6, 1), new Treemap.Rect("r1", 0.6, 0, 0.4, 0.5),
                new Treemap.Rect("r2", 0.6, 0.5, 0.4, 0.5));
        int r = TileNav.next(g, 0, RIGHT);
        T.yes(r == 1 || r == 2, "big right -> " + r);
        T.eq(0, TileNav.next(g, 2, LEFT), "r2 left -> big");
        T.eq(2, TileNav.next(g, 1, DOWN), "r1 down -> r2");
    }
}
