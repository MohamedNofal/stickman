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
        T.eq("autokill.on_leave=1\nautokill.interval_min=30\nautokill.whitelist=a.b,c.d\nautokill.blacklist=e.f\nstayawake.media=1\ntrim.on_sleep=0\ntrim.interval_min=0\n", s, "text");
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

    public void testAddToListsAreExclusive() {
        StickdConf c = StickdConf.parse("autokill.whitelist=a.b,c.d\nautokill.blacklist=e.f\n");
        c.addTo("blacklist", "a.b");
        T.yes(c.blacklist.contains("a.b") && !c.whitelist.contains("a.b"), "whitelist -> blacklist moves it");
        T.yes(c.whitelist.contains("c.d"), "others kept");
        c.addTo("whitelist", "e.f");
        T.yes(c.whitelist.contains("e.f") && !c.blacklist.contains("e.f"), "blacklist -> whitelist moves it");
        c.addTo("whitelist", "e.f");
        T.eq(2, c.whitelist.size(), "idempotent");
        T.eq("autokill.on_leave=0\nautokill.interval_min=0\nautokill.whitelist=c.d,e.f\nautokill.blacklist=a.b\nstayawake.media=0\ntrim.on_sleep=0\ntrim.interval_min=0\n",
            c.serialize(), "serialized");
    }

    public void testRemoveFrom() {
        StickdConf c = StickdConf.parse("autokill.whitelist=a.b\nautokill.blacklist=e.f\n");
        c.removeFrom("blacklist", "e.f");
        T.yes(c.blacklist.isEmpty(), "removed");
        c.removeFrom("blacklist", "a.b");
        T.yes(c.whitelist.contains("a.b"), "removing from the other list leaves this one");
        c.removeFrom("whitelist", "zz.top");
        T.eq(1, c.whitelist.size(), "absent pkg: no-op");
    }

    public void testUnknownListOrBadPkgIsNoop() {
        String text = "autokill.on_leave=1\nautokill.interval_min=5\nautokill.whitelist=a.b\nautokill.blacklist=e.f\nstayawake.media=0\ntrim.on_sleep=0\ntrim.interval_min=0\n";
        T.eq(text, StickdConf.parse(text).addTo("greylist", "x.y").serialize(), "unknown list add");
        T.eq(text, StickdConf.parse(text).removeFrom("greylist", "a.b").serialize(), "unknown list remove");
        T.eq(text, StickdConf.parse(text).addTo(null, "x.y").serialize(), "null list");
        T.eq(text, StickdConf.parse(text).addTo("whitelist", "e.f;rm").serialize(), "non-package name: no-op (blacklist kept)");
        T.eq(text, StickdConf.parse(text).addTo("blacklist", null).serialize(), "null pkg");
    }

    public void testTrimKeys() {
        StickdConf c = StickdConf.parse("trim.on_sleep=1\ntrim.interval_min=15\n");
        T.yes(c.trimOnSleep, "on sleep");
        T.eq(15, c.trimIntervalMin, "interval");
        T.yes(c.serialize().contains("trim.on_sleep=1\ntrim.interval_min=15\n"), c.serialize());
        T.yes(StickdConf.DEFAULT_TEXT.contains("trim.on_sleep=0\ntrim.interval_min=0\n"), "defaults off");
    }
}
