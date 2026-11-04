import com.clean.stickman.core.*;

public class SleepScheduleTest {
    public void testSetSchedulesFadeAndEnd() {
        SleepSchedule s = new SleepSchedule();
        SleepSchedule.Plan p = s.set(2, 1_000_000);
        T.eq(60_000L, p.fadeInMs, "fade in");
        T.eq(120_000L, p.endInMs, "end in");
        T.eq(false, p.fadeEnd, "nothing to clear");
        T.eq("2", p.status, "status");
        T.eq(2, s.minutes(), "minutes");
        SleepSchedule.Plan one = s.set(1, 1_000_000);
        T.eq(0L, one.fadeInMs, "1-min fade starts now");
        SleepSchedule.Plan off = s.set(0, 1_000_000);
        T.eq(-1L, off.endInMs, "off: no end");
        T.eq("off", off.status, "off status");
    }

    public void testStaleTasksNoOp() {
        SleepSchedule s = new SleepSchedule();
        SleepSchedule.Plan a = s.set(2, 0);
        SleepSchedule.Plan b = s.set(30, 0);  // re-set while a's tasks might already be running
        T.eq(-1, s.startFade(a.gen, 60_000), "stale fade");
        T.eq(false, s.end(a.gen), "stale end");
        T.eq(60, s.startFade(b.gen, SleepSchedule.fadeAt(b)), "fresh fade 60s");
        T.eq(true, s.end(b.gen), "fresh end");
        T.eq(false, s.end(b.gen), "end only once");
        T.eq(0, s.minutes(), "off after end");
    }

    public void testFadeSecondsClampToRemaining() {
        SleepSchedule s = new SleepSchedule();
        SleepSchedule.Plan p = s.set(1, 0);
        T.eq(60, s.startFade(p.gen, 0), "full minute");
        p = s.set(1, 0);
        T.eq(true, p.fadeEnd, "re-set while fading clears overlay");
        T.eq(20, s.startFade(p.gen, 40_000), "late start");
    }

    public void testDismissOnlyWhileFading() {
        SleepSchedule s = new SleepSchedule();
        SleepSchedule.Plan p = s.set(15, 0);
        T.eq(false, s.dismiss(), "not fading");
        T.eq(15, s.minutes(), "still set");
        s.startFade(p.gen, 840_000);
        T.eq(true, s.fading(), "fading");
        T.eq(true, s.dismiss(), "dismissed");
        T.eq(0, s.minutes(), "off");
        T.eq(false, s.fading(), "no longer fading");
        T.eq(false, s.end(p.gen), "end task is stale now");
        T.eq(false, s.dismiss(), "second dismiss no-op");
    }
}
