import com.clean.stickman.core.*;
import java.util.*;

public class StickdConfTest {
    public void testStickdConf() {
        StickdConf c = StickdConf.parse("# c\nautokill.on_leave=1\nautokill.interval_min=15\n"
            + "autokill.whitelist= a.b , c.d,,\nautokill.blacklist=e.f\nstayawake.media=1\nfoo.bar=9\ngarbage line\n");
        T.yes(c.onLeave, "onLeave");
        T.eq(15, c.intervalMin, "interval");
        T.eq(new TreeSet<>(Arrays.asList("a.b", "c.d")), new TreeSet<>(c.whitelist), "whitelist");
        T.eq(new TreeSet<>(Arrays.asList("e.f")), new TreeSet<>(c.blacklist), "blacklist");
        T.yes(c.stayAwakeMedia, "stayawake");
    }
    public void testBadNumbersAndDefaults() {
        StickdConf c = StickdConf.parse("autokill.interval_min=abc\nautokill.on_leave=0\n");
        T.eq(0, c.intervalMin, "bad number");
        T.yes(!c.onLeave, "off");
        T.yes(!c.stayAwakeMedia, "default stayawake");
        T.yes(c.whitelist.isEmpty() && c.blacklist.isEmpty(), "empty lists");
        StickdConf n = StickdConf.parse(null);
        T.eq(0, n.intervalMin, "null text");
    }
}
