import com.clean.stickman.core.TaskFeed;

public class TaskFeedRoutingTest {
    public void testIsFeedLine() {
        T.yes(TaskFeed.isFeedLine("tasks total=1700000 avail=800000 cpu=12 seq=3"), "totals header");
        T.yes(TaskFeed.isFeedLine("task org.videolan.vlc rss=1 cpu=0 state=bg up=1 procs=1 seq=3"), "task line");
        T.yes(TaskFeed.isFeedLine("tasks_end seq=3"), "end of sample");
        T.yes(!TaskFeed.isFeedLine("tasks stopped"), "tasks stopped stays a status");
        T.yes(!TaskFeed.isFeedLine("end org.videolan.vlc ok"), "end result stays a status");
        T.yes(!TaskFeed.isFeedLine("end_all ended=2"), "end_all stays a status");
        T.yes(!TaskFeed.isFeedLine("boost freed_mb=3 killed=1"), "other kinds");
        T.yes(!TaskFeed.isFeedLine(null), "null");
    }

    public void testListenerOnCompletedSnapshotOnly() {
        TaskFeed f = new TaskFeed();
        final int[] n = {0};
        f.setListener(new Runnable() { public void run() { n[0]++; } });
        f.onLine("tasks total=100 avail=50 cpu=1 seq=1");
        f.onLine("task a.b rss=10 cpu=0 state=bg up=1 procs=1 seq=1");
        T.eq(0, n[0], "not yet");
        f.onLine("tasks_end seq=1");
        T.eq(1, n[0], "fired once on completion");
        f.onLine("tasks_end seq=1");
        T.eq(1, n[0], "stale end does not fire");
        f.setListener(null);
        f.onLine("tasks total=100 avail=50 cpu=1 seq=2");
        f.onLine("tasks_end seq=2");
        T.eq(1, n[0], "cleared listener");
        T.eq(2L, f.latestSeq(), "still assembled");
    }

    public void testListenerRunsAfterResetAndSeesLatest() {
        final TaskFeed f = new TaskFeed();
        final long[] seen = {0};
        f.setListener(new Runnable() { public void run() {
            seen[0] = f.latestSeq();
            // a listener reacting by feeding a line must find a clean assembler (no sample in progress)
            f.onLine("task late.app rss=1 cpu=0 state=bg up=1 procs=1 seq=" + f.latestSeq());
            throw new RuntimeException("listener bug");
        } });
        f.onLine("tasks total=100 avail=50 cpu=1 seq=1");
        f.onLine("task a.b rss=10 cpu=0 state=bg up=1 procs=1 seq=1");
        f.onLine("tasks_end seq=1");
        T.eq(1L, seen[0], "listener sees the new snapshot");
        T.eq(1, f.latest().apps.size(), "late task line after reset not added to the published snapshot");
        f.onLine("tasks total=100 avail=50 cpu=1 seq=2");
        f.onLine("task c.d rss=10 cpu=0 state=bg up=1 procs=1 seq=2");
        f.onLine("tasks_end seq=2");
        T.eq(2L, f.latestSeq(), "next sample still assembles after a throwing listener");
        T.eq("c.d", f.latest().apps.get(0).pkg, "next sample content");
    }
}
