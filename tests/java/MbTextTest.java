import com.clean.stickman.core.Units;

public class MbTextTest {
    public void testMegabytes() {
        T.eq("312 MB", Units.mb(312L * 1024), "312 MB");
        T.eq("0 MB", Units.mb(0), "zero");
        T.eq("0 MB", Units.mb(-5), "negative clamps");
        T.eq("1 MB", Units.mb(1000), "rounds to nearest MB");
        T.eq("1023 MB", Units.mb(1023L * 1024), "just under 1 GB");
    }

    public void testGigabytes() {
        T.eq("1.0 GB", Units.mb(1024L * 1024), "exactly 1 GB");
        T.eq("1.2 GB", Units.mb(1229L * 1024), "1.2 GB");
        T.eq("1.7 GB", Units.mb(1740L * 1024), "1.7 GB");
        T.eq("12.5 GB", Units.mb(12800L * 1024), "two-digit GB");
    }
}
