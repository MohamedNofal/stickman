import com.clean.stickman.core.Liveness;

public class LivenessTest {
    public void testNeedsEnsure() {
        T.yes(Liveness.needsEnsure(null), "no answer: stickd is gone");
        T.yes(Liveness.needsEnsure("garbage"), "not stickd");
        T.yes(Liveness.needsEnsure("stickd anything"), "malformed answers do not count as healthy");
        T.yes(Liveness.needsEnsure(Liveness.answer(20, Liveness.DOWN)), "remapd died");
        T.yes(!Liveness.needsEnsure(Liveness.answer(20, Liveness.UP)), "all fine");
        T.yes(!Liveness.needsEnsure(Liveness.answer(20, Liveness.RELEASED)), "Back-hold exit is the user's choice");
    }

    public void testRemapdState() {
        T.eq(Liveness.UP, Liveness.remapdState("123", true, false), "alive");
        T.eq(Liveness.UP, Liveness.remapdState("123", true, true), "alive wins over a stale marker");
        T.eq(Liveness.DOWN, Liveness.remapdState("123", false, false), "pid file but no process: killed");
        T.eq(Liveness.DOWN, Liveness.remapdState("", false, false), "nothing");
        T.eq(Liveness.RELEASED, Liveness.remapdState("", false, true), "left on purpose");
    }
}
