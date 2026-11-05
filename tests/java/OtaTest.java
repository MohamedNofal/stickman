import com.clean.stickman.core.Ota;

import java.util.Map;

public class OtaTest {
    static String line(boolean suspended, int enabled) {
        return "User0:ceDataInode=90702installed=truehidden=falsesuspended=" + suspended
            + "distractionFlags=0stopped=falsenotLaunched=falseenabled=" + enabled + "instant=falsevirtual=false";
    }

    static String state(String a, String b, String c) {
        return Ota.MARK + " com.amazon.device.software.ota\n" + a + "\n"
            + Ota.MARK + " com.amazon.device.software.ota.override\n" + b + "\n"
            + Ota.MARK + " com.amazon.tv.forcedotaupdater.v2\n" + c + "\n";
    }

    public void testStateCmdReadsEveryPackage() {
        String s = Ota.stateCmd();
        for (String p : Ota.PACKAGES) {
            T.yes(s.contains("echo " + Ota.MARK + " " + p), p);
            T.yes(s.contains("dumpsys package " + p), p);
        }
        T.yes(s.contains("grep -m1 'User 0:'"), s);
        T.yes(s.contains("tr -d ' '"), s);
    }

    public void testFrozenWhenAllSuspended() {
        T.yes(Ota.parseFrozen(state(line(true, 0), line(true, 0), line(true, 0))), "all suspended");
    }

    public void testFrozenWhenDisabledUser() {
        T.yes(Ota.parseFrozen(state(line(false, 3), line(false, 3), line(false, 3))), "disabled-user counts");
    }

    public void testNotFrozenWhenOneLive() {
        T.yes(!Ota.parseFrozen(state(line(true, 0), line(false, 0), line(true, 0))), "one live");
    }

    public void testNotFrozenWhenShortOrMissing() {
        T.yes(!Ota.parseFrozen(null), "null");
        T.yes(!Ota.parseFrozen(""), "empty");
        T.yes(!Ota.parseFrozen(Ota.MARK + " com.amazon.device.software.ota\n" + line(true, 0)), "one package only");
    }

    public void testParseMap() {
        Map<String, Boolean> m = Ota.parse(state(line(true, 0), line(false, 0), line(true, 3)));
        T.eq(3, m.size(), "size");
        T.eq(Boolean.TRUE, m.get("com.amazon.device.software.ota"), "ota");
        T.eq(Boolean.FALSE, m.get("com.amazon.device.software.ota.override"), "override");
        T.eq(Boolean.TRUE, m.get("com.amazon.tv.forcedotaupdater.v2"), "forced");
    }

    public void testFreezeScript() {
        String s = Ota.freezeScript();
        for (String p : Ota.PACKAGES) T.yes(s.contains("pm suspend --user 0 " + p), p);
        T.yes(s.contains("pm disable-user --user 0 com.amazon.tv.forcedotaupdater.v2"), s);
        T.yes(s.endsWith("echo OTA_FROZEN"), s);
        T.yes(!Ota.freezeScript(false).contains("OTA_FROZEN"), "quiet");
    }

    public void testThawScript() {
        String s = Ota.thawScript();
        for (String p : Ota.PACKAGES) {
            T.yes(s.contains("pm unsuspend --user 0 " + p), p);
            T.yes(s.contains("pm enable " + p), p);
        }
        T.yes(s.endsWith("echo OTA_THAWED"), s);
    }

    public void testEnsureLine() {
        T.eq("", Ota.ensureLine(false), "off");
        String s = Ota.ensureLine(true);
        T.yes(s.contains("pm suspend --user 0 com.amazon.device.software.ota"), s);
        T.yes(!s.contains("OTA_FROZEN"), "quiet in ensure");
    }
}
