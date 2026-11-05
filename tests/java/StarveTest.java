import com.clean.stickman.core.Commands;
import com.clean.stickman.core.Originals;
import com.clean.stickman.core.Starve;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class StarveTest {
    /** Runs script with fake pm/cmd/am/settings in PATH; returns stdout. Fakes log their argv to calls.log. */
    static String sh(File dir, String script) throws Exception {
        File bin = new File(dir, "bin");
        bin.mkdirs();
        String log = new File(dir, "calls.log").getPath();
        // pm list packages: a b c (installed); -d: a only. cmd appops get: "OP: ignore" for x.y WAKE_LOCK only.
        fake(bin, "pm", "echo \"pm $*\" >> " + log + "\n"
            + "if [ \"$1 $2\" = \"list packages\" ]; then if [ \"$3\" = -d ]; then echo package:com.a; "
            + "else printf 'package:com.a\\npackage:com.b\\npackage:x.y\\n'; fi; exit 0; fi\n"
            + "if [ \"$1\" = disable-user ]; then echo \"Package $4 new state: disabled-user\"; fi\n");
        fake(bin, "cmd", "echo \"cmd $*\" >> " + log + "\n"
            + "if [ \"$2\" = get ]; then if [ \"$3 $4\" = \"x.y WAKE_LOCK\" ]; then echo \"$4: ignore\"; else echo \"$4: allow\"; fi; fi\n");
        fake(bin, "am", "echo \"am $*\" >> " + log + "\n");
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true);
        pb.environment().put("PATH", bin.getPath() + ":" + System.getenv("PATH"));
        Process q = pb.start();
        q.getOutputStream().close();
        String out = new String(readAll(q.getInputStream()));
        q.waitFor();
        return out;
    }

    static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static void fake(File bin, String name, String body) throws Exception {
        File f = new File(bin, name);
        Files.write(f.toPath(), ("#!/bin/sh\n" + body).getBytes());
        f.setExecutable(true);
    }

    static File tmp() throws Exception { return Files.createTempDirectory("starve").toFile(); }

    public void testApplyLevels() {
        String a = Starve.apply("com.netflix.ninja", false);
        T.yes(a.contains("cmd appops set com.netflix.ninja RUN_ANY_IN_BACKGROUND ignore"), a);
        T.yes(!a.contains("WAKE_LOCK") && !a.contains("am kill") && !a.contains("standby-bucket"), "asleep is gentle: " + a);
        String s = Starve.apply("com.netflix.ninja", true);
        for (String op : Starve.STARVE_OPS) T.yes(s.contains("cmd appops set com.netflix.ninja " + op + " ignore"), op);
        T.yes(s.contains("echo \"appop com.netflix.ninja WAKE_LOCK\" >> "), "journaled for Restore");
        T.yes(s.contains("am kill com.netflix.ninja"), "closed now if in background");
        T.yes(!s.contains("force-stop"), "never force-stops the app in use");
    }

    public void testRefusesProtectedAndBadNames() {
        for (String p : new String[] { "com.clean.netfilter", "com.clean.stickman", "com.amazon.tv.launcher",
                "com.amazon.device.software.ota", "com.amazon.device.software.ota.override", "com.wolf.firelauncher" }) {
            T.eq(null, Starve.apply(p, true), p);
            T.eq(null, Starve.apply(p, false), p);
        }
        T.eq(null, Starve.apply("a;rm -rf /", true), "bad name");
        T.eq(null, Starve.wake("a$(x)"), "bad wake");
        T.yes(Starve.wake("com.clean.netfilter") != null, "waking is always allowed");
        T.eq(Starve.apply("com.x.y", false), Commands.keepAsleep("com.x.y", true), "keepAsleep = asleep level");
    }

    public void testParseList() {
        Map<String, Boolean> m = Starve.parseList("com.a RUN_ANY_IN_BACKGROUND\ncom.b RUN_ANY_IN_BACKGROUND\ncom.b WAKE_LOCK\n"
            + "com.c 10\nbad;name X\n");
        T.eq(3, m.size(), "three apps");
        T.eq(false, m.get("com.a"), "asleep");
        T.eq(true, m.get("com.b"), "starved");
        T.eq(false, m.get("com.c"), "legacy bucket = asleep");
    }

    public void testWakeGivesBackEveryOpAndOnlyThatAppsLines() throws Exception {
        File d = tmp();
        File j = new File(d, "originals");
        Files.write(j.toPath(), ("setting secure a 1\nbucket com.x.y 10\nappop com.x.y RUN_ANY_IN_BACKGROUND\n"
            + "appop com.x.y WAKE_LOCK\nappop comXxXy START_FOREGROUND\npkg com.z\n").getBytes());
        String w = Starve.wake("com.x.y").replace(Originals.FILE, j.getPath());
        sh(d, w);
        String calls = new String(Files.readAllBytes(new File(d, "calls.log").toPath()));
        T.yes(calls.contains("cmd appops set com.x.y RUN_ANY_IN_BACKGROUND default"), calls);
        T.yes(calls.contains("cmd appops set com.x.y WAKE_LOCK default"), calls);
        T.yes(calls.contains("am set-standby-bucket com.x.y 10"), "legacy bucket given back: " + calls);
        T.yes(!calls.contains("START_FOREGROUND"), "dots are literal, other apps untouched: " + calls);
        T.eq("setting secure a 1\nappop comXxXy START_FOREGROUND\npkg com.z\n", new String(Files.readAllBytes(j.toPath())), "lines");
    }

    public void testCheckFindsDrift() throws Exception {
        File d = tmp();
        File j = new File(d, "originals");
        // com.a still disabled; com.b back on; com.gone uninstalled (skipped);
        // x.y WAKE_LOCK still ignore; x.y START_FOREGROUND back to allow; com.gone appop skipped
        Files.write(j.toPath(), ("pkg com.a\npkg com.b\npkg com.gone\nappop x.y WAKE_LOCK\nappop x.y START_FOREGROUND\n"
            + "appop com.gone WAKE_LOCK\nsetting global k 1\n").getBytes());
        String out = sh(d, Starve.checkScript(j.getPath()));
        List<String[]> dr = Starve.parseDrift(out);
        T.eq(2, dr.size(), out);
        T.eq("pkg", dr.get(0)[0], out);
        T.eq("com.b", dr.get(0)[1], out);
        T.eq("appop", dr.get(1)[0], out);
        T.eq("x.y", dr.get(1)[1], out);
        T.eq("START_FOREGROUND", dr.get(1)[2], out);
        T.eq(6, Starve.checkedCount(out), out);
    }

    public void testCheckWithoutJournal() throws Exception {
        File d = tmp();
        String out = sh(d, Starve.checkScript(new File(d, "none").getPath()));
        T.eq(0, Starve.checkedCount(out), out);
        T.eq(0, Starve.parseDrift(out).size(), out);
    }

    public void testFixScript() throws Exception {
        List<String[]> dr = new ArrayList<>();
        dr.add(new String[] { "pkg", "com.b", null });
        dr.add(new String[] { "appop", "x.y", "START_FOREGROUND" });
        dr.add(new String[] { "pkg", "com.amazon.tv.launcher", null });   // protected: skipped
        File d = tmp();
        String out = sh(d, Starve.fixScript(dr).replace(Originals.FILE, new File(d, "j").getPath())
            .replace(Originals.DIR, d.getPath()));
        T.yes(out.contains("FIXED 2"), out);
        String calls = new String(Files.readAllBytes(new File(d, "calls.log").toPath()));
        T.yes(calls.contains("pm disable-user --user 0 com.b"), calls);
        T.yes(calls.contains("cmd appops set x.y START_FOREGROUND ignore"), calls);
        T.yes(!calls.contains("launcher"), calls);
        List<String[]> none = new ArrayList<>();
        none.add(new String[] { "pkg", "com.clean.netfilter", null });
        T.eq(null, Starve.fixScript(none), "nothing to fix");
    }

    public void testParseDriftIgnoresJunk() {
        T.eq(0, Starve.parseDrift("DRIFT pkg a;b\nDRIFT appop com.x lower\nDRIFT what com.x\nhello\n").size(), "junk");
        T.eq(-1, Starve.checkedCount("nothing"), "no CHECKED");
    }

    public void testHdmiCec() {
        T.yes(Starve.hdmiCec(false).contains("settings put global hdmi_control_enabled 0"), "off");
        T.yes(Starve.hdmiCec(true).contains("settings put global hdmi_control_enabled 1"), "on");
        T.yes(Starve.hdmiCec(true).contains("setting global hdmi_control_enabled $(settings get"), "journaled");
        T.eq(true, Starve.parseCec("CEC 1"), "1");
        T.eq(true, Starve.parseCec("CEC null"), "unset = Android default on");
        T.eq(false, Starve.parseCec("x\nCEC 1\nCEC 0\n"), "last line wins");
        T.eq(null, Starve.parseCec("nothing"), "none");
    }
}
