import com.clean.stickman.core.Originals;
import com.clean.stickman.core.Commands;

public class OriginalsTest {
    public void testRecordsOnce() {
        String c = Originals.setting("secure", "screensaver_enabled", "0");
        T.yes(c.contains("grep -q '^setting secure screensaver_enabled '"), "grep guard: " + c);
        T.yes(c.indexOf("grep -q") < c.indexOf(">>"), "guard before append");
        T.yes(c.trim().endsWith("settings put secure screensaver_enabled 0"), "ends with put: " + c);
    }
    public void testRestoreScriptExecutesInReverse() throws Exception {
        java.io.File d = java.nio.file.Files.createTempDirectory("orig").toFile();
        java.io.File bin = new java.io.File(d, "bin"); bin.mkdir();
        java.io.File log = new java.io.File(d, "log");
        java.io.File j = new java.io.File(d, "journal");
        for (String n : new String[]{"settings", "pm", "am", "cmd"}) {
            java.io.File f = new java.io.File(bin, n);
            java.nio.file.Files.write(f.toPath(), ("#!/bin/sh\necho \"" + n + " $*\" >> \"$LOG\"\n").getBytes());
            f.setExecutable(true);
        }
        java.nio.file.Files.write(j.toPath(), "setting secure a 1\npkg com.x\nsetting global b null\n".getBytes());
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", Originals.restoreScript(j.getPath()));
        pb.environment().put("PATH", bin.getPath() + ":" + System.getenv("PATH"));
        pb.environment().put("LOG", log.getPath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().close();
        T.eq(0, p.waitFor(), "exit");
        T.eq("settings delete global b\npm enable com.x\nsettings put secure a 1\n",
            new String(java.nio.file.Files.readAllBytes(log.toPath())), "log order");
        T.yes(!j.exists(), "journal removed");
    }
    public void testPkgAndBucketRecorded() {
        T.yes(Originals.disablePkg("com.x.y").contains("grep -q '^pkg com.x.y$' /data/local/tmp/stickman/originals"), "pkg guard");
        T.yes(Originals.disablePkg("com.x.y").contains("pm disable-user --user 0 com.x.y"), "disable");
        T.yes(Originals.bucket("com.x.y", "rare").contains("am set-standby-bucket com.x.y rare"), "bucket");
    }
    public void testRejectsBadPackageNames() {
        T.eq(null, Originals.disablePkg("a;rm -rf /"), "disable");
        T.eq(null, Originals.bucket("a b", "rare"), "bucket");
        T.eq(null, Commands.keepAsleep("a$(x)", true), "keepAsleep");
        T.eq(null, Commands.debloat("x y", false), "enable");
    }
    public void testRestoreScriptDeletesFile() {
        String s = Originals.restoreScript();
        T.yes(s.contains("/data/local/tmp/stickman/originals"), "path");
        T.yes(s.contains("settings delete") && s.contains("pm enable") && s.contains("rm -f"), "pieces");
    }
}
