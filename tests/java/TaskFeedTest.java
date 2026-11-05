import com.clean.stickman.core.*;

public class TaskFeedTest {
    public void testAssemblesSnapshot() {
        TaskFeed f = new TaskFeed();
        f.onLine("tasks total=1710032 avail=730800 cpu=12 seq=1");
        f.onLine("task com.a rss=100 cpu=1 state=bg up=5 procs=1 seq=1");
        f.onLine("task com.b rss=900 cpu=7 state=fg up=50 procs=3 seq=1");
        T.eq(null, f.latest(), "not yet");
        f.onLine("tasks_end seq=1");
        TaskSnapshot s = f.latest();
        T.eq(1L, f.latestSeq(), "seq");
        T.eq(1710032L, s.totalKb, "total");
        T.eq(730800L, s.availKb, "avail");
        T.eq(12, s.cpuPct, "cpu");
        T.eq(2, s.apps.size(), "apps");
        T.eq("com.b", s.apps.get(0).pkg, "sorted desc");
        T.eq(3, s.apps.get(0).procs, "procs");
    }

    public void testIgnoresIncompleteAndOlderSeq() {
        TaskFeed f = new TaskFeed();
        f.onLine("tasks total=10 avail=5 cpu=1 seq=5");
        f.onLine("task com.a rss=1 cpu=0 state=bg up=5 procs=1 seq=5");
        f.onLine("tasks total=10 avail=5 cpu=1 seq=4");
        f.onLine("task com.z rss=1 cpu=0 state=bg up=5 procs=1 seq=4");
        f.onLine("tasks_end seq=4");
        T.eq(null, f.latest(), "latest stays null");
        f.onLine("task com.a rss=2 cpu=0 state=bg up=5 procs=1 seq=5");
        f.onLine("tasks_end seq=5");
        T.eq(5L, f.latestSeq(), "seq 5");
        f.onLine("tasks total=10 avail=5 cpu=1 seq=3");
        f.onLine("task com.q rss=1 cpu=0 state=bg up=5 procs=1 seq=3");
        f.onLine("tasks_end seq=3");
        T.eq(5L, f.latestSeq(), "older ignored");
        T.eq("com.a", f.latest().apps.get(0).pkg, "unchanged");
    }

    public void testRoundTrip() {
        TaskSnapshot.App a = new TaskSnapshot.App("com.x.y", 1234, 17, "play", 99, 4);
        TaskFeed f = new TaskFeed();
        f.onLine(ProcStats.formatTotals(100, 50, 9, 7));
        f.onLine(ProcStats.formatTaskLine(a, 7));
        f.onLine(ProcStats.formatEnd(7));
        TaskSnapshot.App b = f.latest().apps.get(0);
        T.eq(a.pkg, b.pkg, "pkg"); T.eq(a.rssKb, b.rssKb, "rss"); T.eq(a.cpuPct, b.cpuPct, "cpu");
        T.eq(a.state, b.state, "state"); T.eq(a.upSec, b.upSec, "up"); T.eq(a.procs, b.procs, "procs");
        T.eq("task com.x.y rss=1234 cpu=17 state=play up=99 procs=4 seq=7", ProcStats.formatTaskLine(a, 7), "exact");
    }

    public void testSeqOneStartsNewSession() {
        TaskFeed f = new TaskFeed();
        f.onLine("tasks total=10 avail=5 cpu=1 seq=7");
        f.onLine("task com.a rss=1 cpu=0 state=bg up=5 procs=1 seq=7");
        f.onLine("tasks_end seq=7");
        T.eq(7L, f.latestSeq(), "seq 7");
        f.onLine("tasks total=10 avail=5 cpu=1 seq=1");
        f.onLine("task com.b rss=1 cpu=0 state=bg up=5 procs=1 seq=1");
        f.onLine("tasks_end seq=1");
        T.eq(1L, f.latest().seq, "restarted");
        T.eq("com.b", f.latest().apps.get(0).pkg, "new data");
    }
}
