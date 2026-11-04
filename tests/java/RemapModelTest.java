import com.clean.stickman.core.*;

public class RemapModelTest {
    public void testParsesV2() {
        RemapModel m = RemapModel.parse("# stickman remap v2\nset double_ms 300\nset hold_ms 700\n"
            + "profile default\n744 tap start org.xbmc.kodi/org.xbmc.kodi.Splash\n"
            + "profile org.xbmc.kodi\n168 hold act boost\n");
        T.eq("start org.xbmc.kodi/org.xbmc.kodi.Splash", m.get("default", 744, Rule.Layer.TAP), "default tap");
        T.eq("act boost", m.get("org.xbmc.kodi", 168, Rule.Layer.HOLD), "kodi hold");
        T.eq(300, m.doubleMs, "double"); T.eq(700, m.holdMs, "hold");
        T.eq(null, m.get("default", 744, Rule.Layer.HOLD), "unset");
        T.eq("[default, org.xbmc.kodi]", m.profiles().toString(), "profiles");
    }
    public void testDefaults() {
        RemapModel m = RemapModel.parse("");
        T.eq(250, m.doubleMs, "d"); T.eq(600, m.holdMs, "h");
        T.eq("[default]", m.profiles().toString(), "p");
    }
    public void testImportsV1() {
        RemapModel m = RemapModel.parse("744 start a/b.C\n172 start w/x.Y");
        T.eq("start a/b.C", m.get("default", 744, Rule.Layer.TAP), "744");
        T.eq("start w/x.Y", m.get("default", 172, Rule.Layer.TAP), "172");
    }
    public void testRoundTrip() {
        RemapModel m = RemapModel.parse("set double_ms 300\nprofile default\n172 double act boost\n172 tap key 158\n"
            + "profile a.b\n164 hold sh echo hi there\n116 tap none\n");
        m.addProfile("c.d");
        String s = m.serialize();
        RemapModel m2 = RemapModel.parse(s);
        T.eq(m, m2, "equal");
        T.eq(s, m2.serialize(), "stable");
        T.eq(300, m2.doubleMs, "dms");
        T.eq("sh echo hi there", m2.get("a.b", 164, Rule.Layer.HOLD), "sh");
        T.yes(s.indexOf("172 tap") < s.indexOf("172 double"), "layer order");
        T.yes(s.indexOf("116 tap") < s.indexOf("164 hold"), "code order");
        T.yes(s.startsWith("# stickman remap v2\nset double_ms 300\nset hold_ms 600\nprofile default\n"), "header");
        T.yes(m2.profiles().contains("c.d"), "empty profile kept");
    }
    public void testIgnoresGarbage() {
        RemapModel m = RemapModel.parse("x y z\n999999 tap none\n744 wiggle none\nprofile\n0 tap none\n768 tap none\n"
            + "744 tap bogus\n172 tap key\n164 tap none\n");
        T.eq("none", m.get("default", 164, Rule.Layer.TAP), "valid kept");
        T.eq(null, m.get("default", 744, Rule.Layer.TAP), "744");
        T.eq(null, m.get("default", 172, Rule.Layer.TAP), "172");
        T.eq(null, m.get("default", 999999, Rule.Layer.TAP), "big");
        T.eq("[default]", m.profiles().toString(), "profiles");
    }
    public void testAppButtonsTapOnly() {
        T.yes(Keys.tapOnly(745), "745"); T.yes(Keys.tapOnly(744), "744"); T.yes(Keys.tapOnly(747), "747");
        T.yes(!Keys.tapOnly(172), "172");
        T.eq(25, Keys.ALL.length, "count");
        T.eq("Netflix", Keys.name(744), "n"); T.eq("Play/Pause", Keys.name(164), "pp");
    }
    public void testHasRulesFallsBackPerCode() {
        RemapModel m = RemapModel.parse("profile p.q\n168 hold act boost\n");
        T.yes(m.hasRules("p.q", 168), "168");
        T.yes(!m.hasRules("p.q", 744), "744");
        T.yes(!m.hasRules("nope", 168), "unknown profile");
    }
    public void testSetClearAndRemove() {
        RemapModel m = new RemapModel();
        m.set("x.y", 172, Rule.Layer.TAP, "none");
        T.yes(m.profiles().contains("x.y"), "auto-add");
        m.set("x.y", 172, Rule.Layer.TAP, null);
        T.yes(!m.hasRules("x.y", 172), "cleared");
        m.removeProfile("x.y"); m.removeProfile("default");
        T.eq("[default]", m.profiles().toString(), "removed");
    }
    public void testActions() {
        T.eq("Open Kodi", Actions.describe("start a/b.C", "Kodi"), "start");
        T.eq("Open a/b.C", Actions.describe("start a/b.C", null), "start nolabel");
        T.eq("Acts as Back", Actions.describe("key 158", null), "key");
        T.eq("Nothing", Actions.describe("none", null), "none");
        T.eq("Original", Actions.describe("orig", null), "orig");
        T.eq("Boost", Actions.describe("act boost", null), "boost");
        T.eq("Sleep timer", Actions.describe("act sleep_timer", null), "st");
        T.eq("Screen off", Actions.describe("act screen_off", null), "so");
        T.eq("Open Amazon screen", Actions.describe("act open_screen X", null), "os");
        T.eq("Shell command", Actions.describe("sh ls", null), "sh");
        T.eq("boost", Actions.BOOST, "const");
    }
}
