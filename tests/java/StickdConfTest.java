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
    public void testSerializeRoundTrip() {
        StickdConf c = new StickdConf();
        c.onLeave = true; c.intervalMin = 30; c.stayAwakeMedia = true;
        c.whitelist.add("a.b"); c.whitelist.add("c.d"); c.blacklist.add("e.f");
        String s = c.serialize();
        T.eq("autokill.on_leave=1\nautokill.interval_min=30\nautokill.whitelist=a.b,c.d\nautokill.blacklist=e.f\nstayawake.media=1\n", s, "text");
        StickdConf r = StickdConf.parse(s);
        T.yes(r.onLeave && r.stayAwakeMedia && r.intervalMin == 30, "flags");
        T.eq(c.whitelist, r.whitelist, "wl");
        T.eq(c.blacklist, r.blacklist, "bl");
        T.eq(StickdConf.DEFAULT_TEXT, new StickdConf().serialize(), "defaults match the shipped default text");
        T.yes(StickdConf.DEFAULT_TEXT.contains("autokill.on_leave=0") && StickdConf.DEFAULT_TEXT.contains("stayawake.media=0"), "default keys");
    }
    public void testSerializeDropsBadPackages() {
        StickdConf c = new StickdConf();
        c.whitelist.add("ok.pkg"); c.whitelist.add("bad,pkg"); c.blacklist.add("x\ny=1");
        String s = c.serialize();
        T.yes(s.contains("autokill.whitelist=ok.pkg\n"), s);
        T.yes(s.contains("autokill.blacklist=\n"), s);
    }
}
