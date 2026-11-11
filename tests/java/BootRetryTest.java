import com.clean.stickman.core.BootRetry;

public class BootRetryTest {
    public void testBackoff() {
        T.eq(20_000L, BootRetry.delayMs(0), "first retry soon");
        T.eq(40_000L, BootRetry.delayMs(1), "then slower");
        T.eq(300_000L, BootRetry.delayMs(4), "cap");
        T.eq(300_000L, BootRetry.delayMs(BootRetry.MAX_TRIES - 1), "stays at the cap");
        T.eq(-1L, BootRetry.delayMs(BootRetry.MAX_TRIES), "used up: left to the periodic job");
        T.eq(-1L, BootRetry.delayMs(-1), "bad input");
    }
}
