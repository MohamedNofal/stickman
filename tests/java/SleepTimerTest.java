import com.clean.stickman.core.*;

public class SleepTimerTest {
    public void testCycle() {
        T.eq(15, SleepTimer.next(0), "0->15");
        T.eq(30, SleepTimer.next(15), "15->30");
        T.eq(60, SleepTimer.next(30), "30->60");
        T.eq(90, SleepTimer.next(60), "60->90");
        T.eq(0, SleepTimer.next(90), "90->0");
        T.eq(15, SleepTimer.next(7), "unknown->15");
        T.eq(15, SleepTimer.next(2), "unknown(2)->15");
    }

    public void testFadeStart() {
        T.eq(940000L, SleepTimer.fadeStartAt(1_000_000), "fade");
    }

    public void testParseMinutes() {
        T.eq(0, SleepTimer.parseMinutes("0"), "0");
        T.eq(45, SleepTimer.parseMinutes(" 45 "), "45");
        T.eq(-1, SleepTimer.parseMinutes("x"), "bad");
        T.eq(-1, SleepTimer.parseMinutes("-3"), "neg");
        T.eq(-1, SleepTimer.parseMinutes(""), "empty");
        T.eq(-1, SleepTimer.parseMinutes("100000"), "too big");
    }

    public void testFadeCommands() {
        T.eq("am broadcast -n com.clean.stickman/.FadeReceiver -a com.clean.stickman.FADE_START --ei seconds 60", SleepTimer.fadeStart(60), "start");
        T.eq("am broadcast -n com.clean.stickman/.FadeReceiver -a com.clean.stickman.FADE_END", SleepTimer.FADE_END, "end");
    }
}
