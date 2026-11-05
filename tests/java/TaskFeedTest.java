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
        // no sample in progress and the seq went down: logcat order is time order, so this is a restarted session
        f.onLine("tasks total=10 avail=5 cpu=1 seq=3");
        T.eq(null, f.latest(), "lower seq = new session: old snapshot dropped");
        f.onLine("task com.q rss=1 cpu=0 state=bg up=5 procs=1 seq=3");
        f.onLine("tasks_end seq=3");
        T.eq(3L, f.latestSeq(), "new session accepted");
        T.eq("com.q", f.latest().apps.get(0).pkg, "new data");
    }

    static void sample(TaskFeed f, long seq, String pkg, long at) {
        f.onLine("tasks total=10 avail=5 cpu=1 seq=" + seq, at);
        f.onLine("task " + pkg + " rss=1 cpu=0 state=bg up=5 procs=1 seq=" + seq, 0);
        f.onLine("tasks_end seq=" + seq, at + 50);
    }

    /** I1: the new session's seq=1 header was missed; its later samples must still replace the old session. */
    public void testMissedSeqOneNewSessionFromSeqTwo() {
        TaskFeed f = new TaskFeed();
        long t = 1_000_000;
        for (int i = 1; i <= 300; i++) sample(f, i, "old.app", t + i * 2000L);
        T.eq(300L, f.latestSeq(), "old session at 300");
        long t2 = t + 400 * 2000L;
        sample(f, 2, "new.app", t2);   // seq=1 missed (logcat re-exec / tail hand-over)
        T.eq(2L, f.latestSeq(), "new session from seq 2");
        T.eq("new.app", f.latest().apps.get(0).pkg, "new data shown, not the frozen old snapshot");
        T.eq(t2 + 50, f.latestAt(), "latestAt = the completed sample's end time");
        sample(f, 3, "new.app", t2 + 2000);
        T.eq(3L, f.latestSeq(), "continues");
    }

    /** Replayed history (logcat -T after a resume) older than what is shown must not reset or refresh anything. */
    public void testReplayedHistoryIgnored() {
        TaskFeed f = new TaskFeed();
        long t = 5_000_000;
        for (int i = 1; i <= 10; i++) sample(f, i, "a.b", t + i * 2000L);
        long at = f.latestAt();
        sample(f, 4, "replay.x", t + 4 * 2000L);   // an old line again
        T.eq(10L, f.latestSeq(), "replay ignored");
        T.eq(at, f.latestAt(), "freshness unchanged by replay");
        sample(f, 1, "x.y", t + 30 * 2000L);       // a real restart (newer time)
        T.eq(1L, f.latestSeq(), "real restart accepted");
    }

    /** An interrupted sample (its tasks_end never arrived) must not block a newer, lower-seq session forever. */
    public void testAbandonedSampleDoesNotBlockNewSession() {
        TaskFeed f = new TaskFeed();
        long t = 9_000_000;
        sample(f, 299, "old.app", t);
        f.onLine("tasks total=10 avail=5 cpu=1 seq=300", t + 2000);   // end never comes
        sample(f, 2, "new.app", t + 60000);
        T.eq(2L, f.latestSeq(), "new session despite the half sample");
    }

    /** latestAt only moves when a snapshot completes, never on a discarded tasks_end. */
    public void testLatestAtOnlyOnCompletion() {
        TaskFeed f = new TaskFeed();
        T.eq(0L, f.latestAt(), "none");
        f.onLine("tasks_end seq=7", 123456);
        T.eq(0L, f.latestAt(), "stray end ignored");
        sample(f, 1, "a.b", 200000);
        T.eq(200050L, f.latestAt(), "completed");
        f.onLine("tasks_end seq=1", 300000);   // duplicate end
        T.eq(200050L, f.latestAt(), "duplicate end does not refresh");
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
