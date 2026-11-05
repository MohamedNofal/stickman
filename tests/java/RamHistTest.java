import com.clean.stickman.core.*;

public class RamHistTest {
    public void testSpanFromRealTimes() {
        RamHist h = new RamHist();
        long t = 1_000_000;
        h.add(1, t, 100);
        h.add(2, t + 2000, 110);
        h.add(4, t + 6000, 130);   // seq 3 dropped by the coalesced listener: kept, span still true
        T.eq(3, h.size(), "kept across a skipped sample");
        T.eq(6L, h.spanSec(), "span = 6 s from log times, not (n-1)*2 = 4 s");
        T.eq(100L, h.get(0), "oldest");
        T.eq(130L, h.get(2), "newest");
        T.eq(6000L, h.agoMs(0), "ago");
    }

    public void testGapClears() {
        RamHist h = new RamHist();
        long t = 2_000_000;
        for (int i = 1; i <= 10; i++) h.add(i, t + i * 2000L, i);
        T.eq(10, h.size(), "ten");
        h.add(11, t + 11 * 2000L + 3_600_000L, 999);   // an hour later (paused / hidden / stale)
        T.eq(1, h.size(), "history restarted after the gap");
        T.eq(0L, h.spanSec(), "no span from one sample");
        T.eq(999L, h.get(0), "only the new sample");
    }

    public void testNewSessionClears() {
        RamHist h = new RamHist();
        long t = 3_000_000;
        h.add(299, t, 5);
        h.add(300, t + 2000, 6);
        h.add(1, t + 4000, 7);   // stickd restarted the session
        T.eq(1, h.size(), "seq went down: cleared");
        h.add(1, t + 4000, 7);
        T.eq(1, h.size(), "same seq again: not a step forward, restart");
    }

    public void testRingAndMinMax() {
        RamHist h = new RamHist();
        for (int i = 1; i <= 40; i++) h.add(i, 1000L * 2 * i, i * 10);
        T.eq(RamHist.SIZE, h.size(), "capped");
        T.eq(110L, h.get(0), "oldest = sample 11");
        T.eq(400L, h.get(RamHist.SIZE - 1), "newest");
        T.eq(110L, h.min(), "min");
        T.eq(400L, h.max(), "max");
        T.eq(58L, h.spanSec(), "29 steps of 2 s");
    }

    public void testUnknownTimesNominal() {
        RamHist h = new RamHist();
        h.add(1, 0, 1); h.add(2, 0, 2); h.add(3, 0, 3);
        T.eq(4L, h.spanSec(), "nominal 2 s steps without times");
        h.clear();
        T.eq(0, h.size(), "clear");
    }
}
