import com.clean.stickman.core.WakeGate;

public class WakeGateTest {
    public static void testAwakeOrScreensaverReplaysTheKey() {
        T.eq(WakeGate.PASS, WakeGate.onDown(Boolean.TRUE), "awake");
    }

    public static void testAsleepWakesAndDropsThePress() {
        T.eq(WakeGate.WAKE_ONLY, WakeGate.onDown(Boolean.FALSE), "asleep");
    }

    public static void testUnknownStateNeverLeavesTheRemoteDead() {
        T.eq(WakeGate.WAKE_AND_PASS, WakeGate.onDown(null), "unknown");
    }
}
