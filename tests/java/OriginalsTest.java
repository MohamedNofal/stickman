import com.clean.stickman.core.Originals;

public class OriginalsTest {
    public void testRecordsOnce() {
        String c = Originals.setting("secure", "screensaver_enabled", "0");
        T.yes(c.contains("grep -q '^setting secure screensaver_enabled '"), "grep guard: " + c);
        T.yes(c.indexOf("grep -q") < c.indexOf(">>"), "guard before append");
        T.yes(c.trim().endsWith("settings put secure screensaver_enabled 0"), "ends with put: " + c);
    }
    public void testRestoreReplaysReverse() {
        String s = Originals.restoreFrom("setting secure a 1\npkg com.x\nsetting global b null");
        int i1 = s.indexOf("settings delete global b");
        int i2 = s.indexOf("pm enable com.x");
        int i3 = s.indexOf("settings put secure a 1");
        T.yes(i1 >= 0 && i2 > i1 && i3 > i2, "order: " + s);
    }
    public void testPkgAndBucketRecorded() {
        T.yes(Originals.disablePkg("com.x.y").contains("grep -q '^pkg com.x.y\\$'") || Originals.disablePkg("com.x.y").contains("^pkg com.x.y"), "pkg guard");
        T.yes(Originals.disablePkg("com.x.y").contains("pm disable-user --user 0 com.x.y"), "disable");
        T.yes(Originals.bucket("com.x.y", "rare").contains("am set-standby-bucket com.x.y rare"), "bucket");
    }
    public void testRestoreScriptDeletesFile() {
        String s = Originals.restoreScript();
        T.yes(s.contains("/data/local/tmp/stickman/originals"), "path");
        T.yes(s.contains("settings delete") && s.contains("pm enable") && s.contains("rm -f"), "pieces");
    }
}
