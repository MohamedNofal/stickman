import com.clean.stickman.core.Buddy;
import com.clean.stickman.core.EnsureScript;
import com.clean.stickman.core.Liveness;
import com.clean.stickman.core.cmd.DaemonCmds;

public class BuddyTest {
    public void testRevivesOnlyAfterTwoMissesInARow() {
        int m = Buddy.misses(0, false, false, false);
        T.yes(!Buddy.revive(m), "one miss can be a planned restart");
        T.yes(Buddy.revive(Buddy.misses(m, false, false, false)), "two in a row");
        T.eq(0, Buddy.misses(m, true, false, false), "alive again resets");
    }

    public void testNeverAgainstTheOwner() {
        T.eq(0, Buddy.misses(5, false, true, false), "switched off");
        T.eq(0, Buddy.misses(5, false, false, true), "Back held 10 s");
    }

    public void testPauseGrowsToAMinute() {
        T.eq(0L, Buddy.pauseMs(0), "first restart: no extra pause");
        T.eq(60000L, Buddy.pauseMs(50), "capped");
    }

    public void testStopScriptMarksBeforeItKills() {
        String s = EnsureScript.stop();
        int mark = s.indexOf("/" + Buddy.STOPPED), kill = s.indexOf("kill ");
        T.yes(mark >= 0 && kill > mark, "marker first, or the daemons would restart each other");
    }

    public void testSetupClearsTheMarker() {
        String s = EnsureScript.build("/a/base.apk", "/a/lib/libremapd.so", 1, "", "", false);
        T.yes(s.contains("rm -f " + Buddy.STOPPED + "; "), "switched on again");
    }

    public void testReviveUsesTheSetupStartStep() {
        String s = DaemonCmds.reviveRemapd();
        T.yes(s.contains("if ! alive remapd; then setsid nohup"), "no second remapd when one is alive");
    }

    public void testWatchdogBacksOffOnlyOnFailure() {
        T.eq(0L, Liveness.retryGapMs(0), "after a restart that worked the next one is immediate");
        T.eq(60000L, Liveness.retryGapMs(1), "first failure");
        T.eq(1200000L, Liveness.retryGapMs(99), "capped at 20 minutes");
        T.yes(Liveness.retryGapMs(2) > Liveness.retryGapMs(1), "grows");
    }
}
