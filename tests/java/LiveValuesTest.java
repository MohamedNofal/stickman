import com.clean.stickman.core.LiveValues;

public class LiveValuesTest {
    static final long[] SLEEP = { -1, 15 * 60000L, 30 * 60000L, 60 * 60000L, 120 * 60000L };
    static final long[] OFF = { 5 * 60000L, 10 * 60000L, 15 * 60000L, 30 * 60000L, Integer.MAX_VALUE };

    public void testValue() {
        String out = "SAVER 0 SLEEP -1 OFF 300000";
        T.eq("0", LiveValues.value(out, "SAVER"), "saver");
        T.eq("-1", LiveValues.value(out, "SLEEP"), "sleep");
        T.eq("300000", LiveValues.value(out, "OFF"), "off");
        T.eq(null, LiveValues.value("SAVER null SLEEP null OFF null", "SLEEP"), "null is unknown");
        T.eq(null, LiveValues.value("", "OFF"), "missing");
        T.eq(null, LiveValues.value(null, "OFF"), "no output");
    }

    public void testIndexOfMs() {
        T.eq(0, LiveValues.indexOfMs(SLEEP, "-1"), "never");
        T.eq(2, LiveValues.indexOfMs(SLEEP, "1800000"), "30 min");
        T.eq(-1, LiveValues.indexOfMs(SLEEP, "2700000"), "45 min isn't a chip");
        T.eq(0, LiveValues.indexOfMs(OFF, "300000"), "5 min");
        T.eq(4, LiveValues.indexOfMs(OFF, "2147483647"), "max int = never");
        T.eq(-1, LiveValues.indexOfMs(OFF, null), "unknown");
        T.eq(-1, LiveValues.indexOfMs(OFF, "abc"), "garbage");
    }

    public void testSaverAndScale() {
        T.eq(0, LiveValues.saver("1"), "on");
        T.eq(1, LiveValues.saver("0"), "off");
        T.eq(-1, LiveValues.saver(null), "unknown");
        float[] anim = { 1f, 0.5f, 0f };
        T.eq(0, LiveValues.indexOfScale(anim, "1.0"), "1x");
        T.eq(1, LiveValues.indexOfScale(anim, "0.5"), "0.5x");
        T.eq(2, LiveValues.indexOfScale(anim, "0"), "off");
        T.eq(0, LiveValues.indexOfScale(anim, null), "unset = default 1x");
        T.eq(-1, LiveValues.indexOfScale(anim, "1.5"), "other");
    }

    public void testDescribeScale() {
        T.eq("1.5×", LiveValues.describeScale("1.5"), "unmatched scale shown");
        T.eq("2×", LiveValues.describeScale("2.0"), "whole number");
        T.eq("Off", LiveValues.describeScale("0.0"), "zero");
        T.eq("1×", LiveValues.describeScale(null), "unset is default");
        T.eq("unknown", LiveValues.describeScale("abc"), "garbage");
        float[] anim = { 1f, 0.5f, 0f };
        T.eq(-1, LiveValues.indexOfScale(anim, "1.5"), "1.5 selects no chip");
    }

    public void testDescribeMs() {
        T.eq("Never", LiveValues.describeMs("-1"), "never");
        T.eq("Never", LiveValues.describeMs("2147483647"), "max");
        T.eq("45 min", LiveValues.describeMs("2700000"), "min");
        T.eq("2 h", LiveValues.describeMs("7200000"), "hours");
        T.eq("90 s", LiveValues.describeMs("90000"), "seconds");
        T.eq("unknown", LiveValues.describeMs(null), "unknown");
    }
}
