import com.clean.stickman.core.*;

public class DnsGuardTest {
    public void testRevertsOnlyWhenBothFail() {
        T.eq(true, DnsGuard.decide(new boolean[]{false, false}), "both fail");
        T.eq(false, DnsGuard.decide(new boolean[]{false, true}), "second ok");
        T.eq(false, DnsGuard.decide(new boolean[]{true, false}), "first ok");
        T.eq(false, DnsGuard.decide(new boolean[]{true, true}), "both ok");
        T.eq(false, DnsGuard.decide(new boolean[]{false}), "incomplete");
        T.eq(false, DnsGuard.decide(null), "null");
    }

    public void testProbeCommand() {
        T.eq("ping -c 1 -W 3 1.1.1.1 && ping -c 1 -W 3 dns.google", DnsGuard.PROBE, "probe");
    }
}
