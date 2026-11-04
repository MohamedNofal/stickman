import com.clean.stickman.core.*;
import java.util.*;

public class DebloatTest {
    static final String OUT = "package:com.amazon.tv.releasenotes\npackage:com.amazon.tv.launcher\n"
        + "package:com.amazon.tv.settings.v2\npackage:com.amazon.weather\npackage:com.amazon.tv.inputpreference.service\n"
        + "package:com.amazon.aaa\npackage:org.notcom.amazon.x\n" + Debloat.SEP + "\ncom.amazon.tv.csapp\ncom.amazon.ods.kindleconnect\n";

    public void testParse() {
        Debloat d = Debloat.parse(OUT);
        T.yes(d.enabled.contains("com.amazon.weather"), "enabled");
        T.yes(!d.enabled.contains("org.notcom.amazon.x"), "non-amazon dropped");
        T.eq(Arrays.asList("com.amazon.tv.csapp", "com.amazon.ods.kindleconnect"), d.disabledByUs, "ours");
    }
    public void testRecommendedOnlyInstalledEnabled() {
        List<String[]> r = Debloat.parse(OUT).recommended();
        T.eq(1, r.size(), "one");
        T.eq("com.amazon.tv.releasenotes", r.get(0)[0], "releasenotes");
    }
    public void testAdvancedExcludesProtectedAndRecommended() {
        T.eq(Arrays.asList("com.amazon.aaa", "com.amazon.weather"), Debloat.parse(OUT).advanced(), "advanced");
    }
    public void testParseWithoutSeparatorIsSafe() {
        Debloat d = Debloat.parse(null);
        T.yes(d.enabled.isEmpty() && d.disabledByUs.isEmpty(), "empty");
    }
    public void testReadCmd() {
        T.yes(Debloat.READ_CMD.contains("pm list packages -e com.amazon"), Debloat.READ_CMD);
        T.yes(Debloat.READ_CMD.contains("^pkg ") && Debloat.READ_CMD.contains(Originals.FILE), Debloat.READ_CMD);
    }

    static String run(String script, java.io.File bin, java.io.File log) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", script);
        pb.environment().put("PATH", bin.getPath() + ":" + System.getenv("PATH"));
        pb.environment().put("LOG", log.getPath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(readAll(p.getInputStream()));
        T.eq(0, p.waitFor(), "exit: " + out);
        return log.exists() ? new String(java.nio.file.Files.readAllBytes(log.toPath())) : "";
    }
    static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }
    static java.io.File stubs(java.io.File d) throws Exception {
        java.io.File bin = new java.io.File(d, "bin"); bin.mkdir();
        for (String n : new String[]{"settings", "pm", "am", "cmd"}) {
            java.io.File f = new java.io.File(bin, n);
            java.nio.file.Files.write(f.toPath(), ("#!/bin/sh\necho \"" + n + " $*\" >> \"$LOG\"\n").getBytes());
            f.setExecutable(true);
        }
        return bin;
    }

    public void testRestoreAllEnablesOnlyPkgLinesAndKeepsOthers() throws Exception {
        java.io.File d = java.nio.file.Files.createTempDirectory("rall").toFile();
        java.io.File bin = stubs(d), log = new java.io.File(d, "log"), j = new java.io.File(d, "journal");
        java.nio.file.Files.write(j.toPath(), ("setting secure a 1\npkg com.x\nbucket com.y rare\npkg com.z\n").getBytes());
        T.eq("pm enable com.x\npm enable com.z\n", run(Debloat.restoreAllScript(j.getPath()), bin, log), "enables");
        T.eq("setting secure a 1\nbucket com.y rare\n", new String(java.nio.file.Files.readAllBytes(j.toPath())), "others kept");
    }
    public void testRestoreAllOnlyPkgsRemovesEmptyJournal() throws Exception {
        java.io.File d = java.nio.file.Files.createTempDirectory("rall").toFile();
        java.io.File bin = stubs(d), log = new java.io.File(d, "log"), j = new java.io.File(d, "journal");
        java.nio.file.Files.write(j.toPath(), "pkg com.x\n".getBytes());
        T.eq("pm enable com.x\n", run(Debloat.restoreAllScript(j.getPath()), bin, log), "enable");
        T.yes(!j.exists(), "empty journal removed");
    }
    public void testRestoreAllNoJournal() throws Exception {
        java.io.File d = java.nio.file.Files.createTempDirectory("rall").toFile();
        java.io.File bin = stubs(d), log = new java.io.File(d, "log");
        T.eq("", run(Debloat.restoreAllScript(new java.io.File(d, "none").getPath()), bin, log), "nothing");
        T.yes(!new java.io.File(d, "none").exists(), "not created");
    }
    public void testRestoreAllSkipsProtectedAndGarbage() throws Exception {
        java.io.File d = java.nio.file.Files.createTempDirectory("rall").toFile();
        java.io.File bin = stubs(d), log = new java.io.File(d, "log"), j = new java.io.File(d, "journal");
        java.nio.file.Files.write(j.toPath(), "pkg a;touch /tmp/x\npkg com.ok\n".getBytes());
        T.eq("pm enable com.ok\n", run(Debloat.restoreAllScript(j.getPath()), bin, log), "only valid");
    }
    public void testEnableOnePrunesItsLine() throws Exception {
        java.io.File d = java.nio.file.Files.createTempDirectory("one").toFile();
        java.io.File bin = stubs(d), log = new java.io.File(d, "log"), j = new java.io.File(d, "journal");
        java.nio.file.Files.write(j.toPath(), "pkg com.x\npkg com.xy\nsetting a b c\n".getBytes());
        T.eq("pm enable com.x\n", run(Debloat.enableOne("com.x", j.getPath()), bin, log), "enable");
        T.eq("pkg com.xy\nsetting a b c\n", new String(java.nio.file.Files.readAllBytes(j.toPath())), "pruned only com.x");
        T.eq(null, Debloat.enableOne("com.amazon.tv.launcher", j.getPath()), "protected");
        T.eq(null, Debloat.enableOne("a b", j.getPath()), "bad");
    }
    public void testListRecorded() {
        String c = Debloat.recorded("bucket");
        T.yes(c.contains("^bucket ") && c.contains(Originals.FILE), c);
    }
    public void testInputMethodAndKeyPolicyProtected() {
        T.yes(Catalog.isProtected("com.amazon.tv.ime"), "ime");
        T.yes(Catalog.isProtected("com.amazon.tv.keypolicymanager"), "keypolicy");
        T.yes(!Catalog.isProtected("com.amazon.tv.releasenotes"), "releasenotes ok");
        T.yes(!Catalog.isProtected("com.amazon.imp"), "imp is not an ime");
    }
}
