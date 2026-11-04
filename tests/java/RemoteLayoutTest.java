import com.clean.stickman.core.*;

import java.util.Arrays;

public class RemoteLayoutTest {
    static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;

    public void testAllRemoteKeysPresent() {
        int[] got = new int[RemoteLayout.BUTTONS.length];
        for (int i = 0; i < got.length; i++) got[i] = RemoteLayout.BUTTONS[i].code;
        int[] exp = Keys.ALL.clone();
        Arrays.sort(got); Arrays.sort(exp);
        T.eq(Arrays.toString(exp), Arrays.toString(got), "codes == Keys.ALL");
    }

    public void testNoOverlap() {
        RemoteLayout.Btn[] b = RemoteLayout.BUTTONS;
        for (int i = 0; i < b.length; i++) {
            T.yes(b[i].cx - b[i].r >= 0 && b[i].cx + b[i].r <= 1, Keys.name(b[i].code) + " inside width");
            float ry = b[i].r / RemoteLayout.ASPECT;
            T.yes(b[i].cy - ry >= 0 && b[i].cy + ry <= 1, Keys.name(b[i].code) + " inside height");
            for (int j = i + 1; j < b.length; j++)
                T.yes(!RemoteLayout.overlaps(b[i], b[j]), Keys.name(b[i].code) + " overlaps " + Keys.name(b[j].code));
        }
    }

    public void testOverlapDetects() {
        RemoteLayout.Btn a = new RemoteLayout.Btn(1, "a", 0.5f, 0.5f, 0.1f, true);
        RemoteLayout.Btn b = new RemoteLayout.Btn(2, "b", 0.55f, 0.5f, 0.1f, false);
        T.yes(RemoteLayout.overlaps(a, b), "close buttons overlap");
    }

    public void testDpadNeighbours() {
        T.eq(103, RemoteLayout.next(96, UP), "OK up");
        T.eq(96, RemoteLayout.next(103, DOWN), "Up down");
        T.eq(105, RemoteLayout.next(96, LEFT), "OK left");
        T.eq(106, RemoteLayout.next(96, RIGHT), "OK right");
        T.eq(108, RemoteLayout.next(96, DOWN), "OK down");
    }

    public void testAppGrid() {
        T.eq(744, RemoteLayout.next(745, RIGHT), "Prime right");
        T.eq(747, RemoteLayout.next(745, DOWN), "Prime down");
        T.eq(746, RemoteLayout.next(744, DOWN), "Netflix down");
        T.eq(747, RemoteLayout.next(746, LEFT), "Apps left");
    }

    public void testEdgeStays() {
        T.eq(116, RemoteLayout.next(116, UP), "Power up");
        T.eq(116, RemoteLayout.next(116, LEFT), "Power left");
        T.eq(747, RemoteLayout.next(747, DOWN), "Music down");
    }

    public void testRowsReachable() {
        T.eq(172, RemoteLayout.next(108, DOWN), "Down -> Home");
        T.eq(164, RemoteLayout.next(172, DOWN), "Home -> Play");
        T.eq(113, RemoteLayout.next(164, DOWN), "Play -> Mute");
        T.eq(116, RemoteLayout.next(217, LEFT), "Alexa -> Power");
    }

    public void testEveryButtonReachable() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        q.add(96); seen.add(96);
        while (!q.isEmpty()) {
            int c = q.poll();
            for (int d = 0; d < 4; d++) { int n = RemoteLayout.next(c, d); if (seen.add(n)) q.add(n); }
        }
        T.eq(Keys.ALL.length, seen.size(), "reachable from OK");
    }

    public void testFind() {
        T.eq(96, RemoteLayout.find(96).code, "find");
        T.eq(null, RemoteLayout.find(1), "missing");
    }
}
