import com.clean.stickman.core.StuckKey;

public class StuckKeyTest {
    public void testNothingDown() {
        T.eq(0, StuckKey.keysDown("      KeyDowns: 0 keys currently down\n      KeyDowns: 0 keys currently down\n"), "all up");
        T.eq(0, StuckKey.keysDown(""), "no keyboards");
        T.eq(0, StuckKey.keysDown(null), "no output");
    }

    public void testCountsEveryDevice() {
        // the stick on 2026-11-15: the real remote had one key down after a grab taken mid-press
        T.eq(1, StuckKey.keysDown("      KeyDowns: 0 keys currently down\n      KeyDowns: 1 keys currently down\n      KeyDowns: 0 keys currently down\n"), "one stuck");
        T.eq(3, StuckKey.keysDown("KeyDowns: 2 keys currently down\nKeyDowns: 1 keys currently down"), "summed");
    }
}
