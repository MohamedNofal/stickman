import com.clean.stickman.core.*;

public class TaskKeepaliveTest {
    static final long NEVER = Long.MAX_VALUE;

    public void testRegularKeepalive() {
        T.yes(!TaskKeepalive.shouldStart(false, 59999, 1000), "fresh feed: wait for 60 s");
        T.yes(TaskKeepalive.shouldStart(false, 60000, 1000), "60 s keepalive");
        T.yes(TaskKeepalive.shouldStart(false, NEVER, NEVER), "never started");
    }

    public void testStaleFeedRetriesEvery10s() {
        T.yes(!TaskKeepalive.shouldStart(false, 5000, NEVER), "just sent: give stickd 10 s");
        T.yes(TaskKeepalive.shouldStart(false, 10000, NEVER), "no feed 10 s after a start: resend");
        T.yes(TaskKeepalive.shouldStart(false, 15000, 12000), "stale 12 s (stickd restarted): resend");
        T.yes(!TaskKeepalive.shouldStart(false, 15000, 4000), "live: no extra start");
        T.yes(!TaskKeepalive.shouldStart(false, 15000, 10000), "exactly 10 s old is not stale yet");
    }

    public void testPausedNeverStarts() {
        T.yes(!TaskKeepalive.shouldStart(true, NEVER, NEVER), "paused");
    }
}
