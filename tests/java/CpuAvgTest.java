import com.clean.stickman.core.*;
import java.util.*;

public class CpuAvgTest {
    static TaskSnapshot snap(long seq, Object... pkgCpu) {
        List<TaskSnapshot.App> l = new ArrayList<>();
        for (int i = 0; i < pkgCpu.length; i += 2) l.add(new TaskSnapshot.App((String) pkgCpu[i], 1000, (Integer) pkgCpu[i + 1], "bg", 1, 1));
        return new TaskSnapshot(seq, 10, 5, 1, l);
    }

    public void testMovingAverage() {
        CpuAvg c = new CpuAvg();
        T.eq(7.0, c.avg("a.b", 7), "unknown: fallback");
        c.add(snap(1, "a.b", 0));
        c.add(snap(2, "a.b", 4));
        T.eq(2.0, c.avg("a.b", 0), "mean of 0,4");
        for (int s = 3; s <= 7; s++) c.add(snap(s, "a.b", 10));
        T.eq(10.0, c.avg("a.b", 0), "window of 5: the 0 and 4 dropped out");
    }

    public void testSameSnapshotCountsOnce() {
        CpuAvg c = new CpuAvg();
        c.add(snap(1, "a.b", 0));
        TaskSnapshot s2 = snap(2, "a.b", 6);
        c.add(s2); c.add(s2); c.add(s2);   // sort change / resize re-apply the same snapshot
        T.eq(3.0, c.avg("a.b", 0), "counted once");
    }

    public void testDampsZeroToTwoHopping() {
        CpuAvg c = new CpuAvg();
        int[] raw = {0, 2, 0, 3, 0, 2, 0, 2};
        double lo = 99, hi = -1;
        for (int i = 0; i < raw.length; i++) {
            c.add(snap(i + 1, "x.y", raw[i]));
            if (i >= CpuAvg.WINDOW - 1) { double a = c.avg("x.y", 0); lo = Math.min(lo, a); hi = Math.max(hi, a); }
        }
        T.yes(hi - lo <= 0.6 + 1e-9, "smoothed swing " + (hi - lo) + " vs raw 3");
    }

    public void testGoneAppAndNewSession() {
        CpuAvg c = new CpuAvg();
        c.add(snap(1, "a.b", 50, "c.d", 5));
        c.add(snap(2, "c.d", 5));
        T.eq(-1.0, c.avg("a.b", -1), "gone app forgotten");
        c.add(snap(1, "c.d", 9));   // new session
        T.eq(9.0, c.avg("c.d", 0), "restarted");
    }
}
