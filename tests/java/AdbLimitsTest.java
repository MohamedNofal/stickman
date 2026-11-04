import com.clean.stickman.core.AdbLimits;

public class AdbLimitsTest {
    public void testEffectiveIsMin() {
        T.eq(256 * 1024, AdbLimits.OURS, "we advertise 256K");
        T.eq(4096, AdbLimits.effective(AdbLimits.OURS, 4096), "old adbd");
        T.eq(256 * 1024, AdbLimits.effective(AdbLimits.OURS, 1024 * 1024), "new adbd (1M)");
        T.eq(4096, AdbLimits.effective(AdbLimits.OURS, 0), "unknown remote -> legacy 4096");
    }

    public void testFits() {
        T.yes(AdbLimits.fits(4096, AdbLimits.OURS, 4096), "exactly at limit fits");
        T.yes(!AdbLimits.fits(4097, AdbLimits.OURS, 4096), "one over does not");
        T.yes(AdbLimits.fits(200000, AdbLimits.OURS, 1024 * 1024), "200K fits 256K");
        T.yes(!AdbLimits.fits(300000, AdbLimits.OURS, 1024 * 1024), "300K exceeds ours");
    }

    public void testShellPayloadLen() {
        T.eq(9, AdbLimits.shellPayloadLen("ab"), "shell: + ab + NUL");
        T.eq(11, AdbLimits.shellPayloadLen("é°"), "utf-8 bytes counted");
    }
}
