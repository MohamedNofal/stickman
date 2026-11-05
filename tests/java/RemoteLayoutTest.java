import com.clean.stickman.core.*;

import java.util.Arrays;

/** Geometry helpers, exercised on the owner's Enhanced preset (RemotePresetsTest covers every preset). */
public class RemoteLayoutTest {
    static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;

    static RemoteLayout.Btn[] B = RemoteLayout.forPreset("enhanced");

    static int i(int code) { return RemoteLayout.indexOf(B, code); }
    static int nx(int code, int dir) { int n = RemoteLayout.next(B, i(code), dir); return B[n].code; }

    public void testAllRemoteKeysPresent() {
        int[] got = new int[B.length];
        for (int k = 0; k < got.length; k++) got[k] = B[k].code;
        int[] exp = Keys.ALL.clone();
        Arrays.sort(got); Arrays.sort(exp);
        T.eq(Arrays.toString(exp), Arrays.toString(got), "codes == Keys.ALL");
    }

    public void testNoOverlap() {
        for (int a = 0; a < B.length; a++)
            for (int b = a + 1; b < B.length; b++)
                T.yes(!RemoteLayout.overlaps(B[a], B[b]), B[a].name + " overlaps " + B[b].name);
    }

    /** The hint pill sits right of the body for every button, so it can never cover one. */
    public void testHintOutsideBody() {
        for (RemotePresets.Preset p : RemotePresets.ALL) {
            float dp = 2f, viewW = 367 * dp, viewH = 500 * dp;
            float[] body = RemoteLayout.body(viewW, viewH, dp, p.aspect);
            float bx = body[0], by = body[1], bw = body[2], bh = body[3];
            T.yes(by >= 0 && by + bh <= viewH, p.id + " body fits vertically");
            T.yes(bw >= RemoteLayout.MIN_BODY_DP * dp * 0.95f, p.id + " body wide enough: " + bw / dp);
            T.yes(bx + bw + (RemoteLayout.HINT_GAP_DP + RemoteLayout.HINT_AREA_DP) * dp <= viewW, "hint area fits");
            for (RemoteLayout.Btn x : p.buttons) {
                float[] r = RemoteLayout.hintRect(x, bx, by, bw, bh, viewW, viewH, 400 * dp, dp);
                T.yes(r[0] >= bx + bw + 4 * dp, x.name + " hint left of rim");
                T.yes(r[2] <= viewW && r[2] > r[0], x.name + " hint inside view");
                T.yes(r[1] >= 0 && r[3] <= viewH, x.name + " hint vertical");
                float cy = by + x.cy * bh;
                T.yes(cy >= r[1] && cy <= r[3], x.name + " hint level with button");
            }
        }
    }

    public void testOverlapDetects() {
        RemoteLayout.Btn a = new RemoteLayout.Btn(1, "a", 0.5f, 0.5f, 0.1f, true);
        RemoteLayout.Btn b = new RemoteLayout.Btn(2, "b", 0.55f, 0.5f, 0.1f, false);
        T.yes(RemoteLayout.overlaps(a, b), "close buttons overlap");
    }

    public void testDpadNeighbours() {
        T.eq(103, nx(96, UP), "OK up");
        T.eq(96, nx(103, DOWN), "Up down");
        T.eq(105, nx(96, LEFT), "OK left");
        T.eq(106, nx(96, RIGHT), "OK right");
        T.eq(108, nx(96, DOWN), "OK down");
    }

    public void testAppGrid() {
        T.eq(744, nx(745, RIGHT), "Prime right");
        T.eq(746, nx(745, DOWN), "Prime down");
        T.eq(747, nx(744, DOWN), "Netflix down");
        T.eq(746, nx(747, LEFT), "Music left");
    }

    public void testEdgeStays() {
        T.eq(116, nx(116, UP), "Power up");
        T.eq(116, nx(116, LEFT), "Power left");
        T.eq(747, nx(747, DOWN), "Music down");
    }

    public void testRowsReachable() {
        T.eq(172, nx(108, DOWN), "Down -> Home");
        T.eq(164, nx(172, DOWN), "Home -> Play");
        T.eq(362, nx(164, DOWN), "Play -> Live TV");
        T.eq(249, nx(362, DOWN), "Live TV -> Settings");
        T.eq(116, nx(217, LEFT), "Alexa -> Power");
    }

    public void testIndexOf() {
        T.eq(96, B[RemoteLayout.indexOf(B, 96)].code, "indexOf");
        T.eq(-1, RemoteLayout.indexOf(B, 1), "missing");
        T.eq(-1, RemoteLayout.indexOf(RemotePresets.get("pro").buttons, 0), "code 0 never matches");
    }
}
