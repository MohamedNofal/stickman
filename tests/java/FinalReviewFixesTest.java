import com.clean.stickman.core.*;
import com.clean.stickman.daemon.Stickd;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Host tests for the final whole-branch review fixes (I1-I5, M2, M3, M8; see final-fix-report.md). */
public class FinalReviewFixesTest {
    static boolean haveBash() { return new File("/bin/bash").exists(); }

    static File tmp(String p) throws Exception { return Files.createTempDirectory(p).toFile(); }

    static void exe(File f, String s) throws Exception {
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
        f.setExecutable(true);
    }

    static String read(File f) {
        try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); } catch (Exception e) { return ""; }
    }

    /** Runs script under sh with /data/local/tmp -> root, stub bins (svc/am/settings/pm/cmd) logging to root/log. */
    static String runSh(File root, String script) throws Exception {
        File bin = new File(root, "bin");
        bin.mkdirs();
        for (String n : new String[] { "svc", "am", "settings", "pm", "cmd" })
            exe(new File(bin, n), "#!/bin/sh\necho \"$(basename $0) $*\" >> " + root + "/log\nexit 0\n");
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", script.replace("/data/local/tmp", root.getPath())).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        Process p = pb.start();
        String out = new String(EnsureScriptTest.readAll(p.getInputStream()), StandardCharsets.UTF_8).trim();
        p.waitFor();
        return out;
    }

    /** A background process whose arg0 is name (like app_process --nice-name); caller destroys it. */
    static Process fake(String name) throws Exception {
        return new ProcessBuilder("bash", "-c", "exec -a " + name + " sleep 60").start();
    }

    static long pidOf(Process p) throws Exception {
        java.lang.reflect.Method m = Process.class.getMethod("pid");
        return (Long) m.invoke(p);
    }

    // ---- I1 + M9: stop / restore give back stay-on and clear the fade ----
    public void testStopReleasesOwnedStayOnAndFade() throws Exception {
        String s = EnsureScript.stop();
        T.yes(s.contains("stayon.owned") && s.contains("svc power stayon false") && s.contains("FADE_END"), s);
        T.yes(s.indexOf("stickd.pid") < s.indexOf("svc power stayon false"), "release after stickd is killed");
        File root = tmp("stop");
        new File(root, "stickman").mkdirs();
        Files.write(new File(root, "stickman/stayon.owned").toPath(), "1\n".getBytes());
        T.eq("STOPPED", runSh(root, s), "stop output");
        String log = read(new File(root, "log"));
        T.yes(log.contains("svc power stayon false"), "stayon released: " + log);
        T.yes(log.contains("am broadcast -n com.clean.stickman/.FadeReceiver -a com.clean.stickman.FADE_END"), "fade end: " + log);
        T.yes(!new File(root, "stickman/stayon.owned").exists(), "marker removed");
    }

    public void testStopLeavesUserStayOnAlone() throws Exception {
        File root = tmp("stop2");
        new File(root, "stickman").mkdirs();
        T.eq("STOPPED", runSh(root, EnsureScript.stop()), "stop output");
        T.yes(!read(new File(root, "log")).contains("svc power"), "no marker -> stay-on untouched");
    }

    public void testRestoreReleasesOwnedStayOn() throws Exception {
        String s = Originals.restoreScript();
        T.yes(s.contains("svc power stayon false") && s.indexOf("svc power stayon false") < s.indexOf("exit 0"),
            "stayon released even when there is no journal: " + s);
        File root = tmp("restore");
        new File(root, "stickman").mkdirs();
        Files.write(new File(root, "stickman/stayon.owned").toPath(), "1\n".getBytes());
        runSh(root, s);
        T.yes(read(new File(root, "log")).contains("svc power stayon false"), "released without journal");
        T.yes(!new File(root, "stickman/stayon.owned").exists(), "marker removed");
    }

    // ---- I2: exact single-instance check, stale pid removed, both daemons required ----
    public void testProcIsExact() {
        T.yes(Stickd.procIs("main\n", "stickd\0", "stickd"), "arg0 stickd (comm main under app_process)");
        T.yes(Stickd.procIs("stickd\n", "", "stickd"), "comm stickd");
        String ensureShell = "sh\0-c\0D=/data/local/tmp/stickman; ... --nice-name=stickd ... alive stickd\0";
        T.yes(!Stickd.procIs("sh\n", ensureShell, "stickd"), "ensure shell / its forks are not stickd");
        T.yes(!Stickd.procIs("stickd2\n", "stickdx\0", "stickd"), "no prefix match");
        T.yes(!Stickd.procIs(null, null, "stickd"), "nothing");
    }

    public void testEnsureRemovesStalePidBeforeLaunch() {
        String s = EnsureScript.build("/x/base.apk", "/x/lib/libremapd.so", 1, "", "", false);
        int rm = s.indexOf("rm -f /data/local/tmp/stickman/stickd.pid; CLASSPATH=");
        T.yes(rm > 0 && rm < s.indexOf("--nice-name=stickd"), "rm stale stickd.pid right before launch");
        T.yes(s.lastIndexOf("alive stickd", rm) > 0, "only in the not-alive branch");
    }

    public void testEnsureReplacesStalePidPointingAtAnotherProcess() throws Exception {
        if (!haveBash()) return;
        EnsureSmokeTest st = new EnsureSmokeTest();
        st.setUp();
        Process other = fake("sh");  // a reused pid: not stickd
        try {
            new File(st.root, "stickman").mkdirs();
            String stale = Long.toString(pidOf(other));
            Files.write(new File(st.root, "stickman/stickd.pid").toPath(), (stale + "\n").getBytes());
            String r = EnsureSmokeTest.last(st.run(st.ensure(1, "")));
            T.yes(r.matches("REMAPD \\d+ STICKD \\d+"), "both started: " + r);
            T.yes(!r.endsWith(" " + stale), "stickd is a new process, not the stale pid: " + r);
            T.yes(other.isAlive(), "the unrelated process was not killed");
        } finally {
            other.destroy();
            st.run(EnsureScript.stop());
        }
    }

    public void testBothRunning() {
        T.yes(EnsureScript.bothRunning("MIGRATED\nREMAPD 123 STICKD 456"), "both");
        T.yes(!EnsureScript.bothRunning("REMAPD 123 STICKD -"), "stickd missing -> not ensured");
        T.yes(!EnsureScript.bothRunning("REMAPD - STICKD 456"), "remapd missing");
        T.yes(!EnsureScript.bothRunning(null), "null");
    }

    // ---- I4: DNS change refused unless stickd is alive; dns_check survives the stale drop ----
    public void testPrivateDnsRefusedWithoutStickd() throws Exception {
        File root = tmp("dns");
        new File(root, "stickman").mkdirs();
        String out = runSh(root, Commands.privateDns("dns.adguard-dns.com"));
        T.yes(out.contains(Commands.DNS_REFUSED), "refused: " + out);
        T.yes(!read(new File(root, "log")).contains("settings"), "nothing changed");
        String[] act = new File(root, "stickman/act").list();
        T.yes(act == null || act.length == 0, "no dns_check spooled");
        T.yes(!runSh(root, Commands.privateDns(null)).contains(Commands.DNS_REFUSED), "turning DNS off is always allowed");
        T.yes(read(new File(root, "log")).contains("settings put global private_dns_mode off"), "off applied");
    }

    public void testPrivateDnsAppliedWithStickd() throws Exception {
        if (!haveBash()) return;
        File root = tmp("dns2");
        new File(root, "stickman").mkdirs();
        Process d = fake("stickd");
        try {
            Thread.sleep(200);
            Files.write(new File(root, "stickman/stickd.pid").toPath(), (pidOf(d) + "\n").getBytes());
            String out = runSh(root, Commands.privateDns("dns.adguard-dns.com"));
            T.yes(!out.contains(Commands.DNS_REFUSED), "applied: " + out);
            T.yes(read(new File(root, "log")).contains("settings put global private_dns_specifier dns.adguard-dns.com"), "specifier");
            String[] act = new File(root, "stickman/act").list();
            T.yes(act != null && act.length == 1 && read(new File(root, "stickman/act/" + act[0])).equals("dns_check\n"), "dns_check spooled");
        } finally {
            d.destroy();
        }
    }

    public void testDnsCheckKeptWhenStale() {
        T.yes(Spool.keepWhenStale("dns_check"), "dns_check");
        T.yes(!Spool.keepWhenStale("sleep_now"), "sleep_now dropped");
        T.yes(!Spool.keepWhenStale("boost"), "boost dropped");
        T.yes(!Spool.keepWhenStale(null), "null");
    }

    public void testStartupDrainRunsStaleDnsCheckOnly() throws Exception {
        File dir = tmp("spool");
        File act = new File(dir, "act");
        act.mkdirs();
        long old = System.currentTimeMillis() - 120_000;
        String[][] files = { { old + "-1-1.act", "sleep_now\n" }, { old + "-1-2.act", "dns_check\n" }, { (old + 1) + "-1-3.act", "boost\n" } };
        for (String[] f : files) {
            File x = new File(act, f[0]);
            Files.write(x.toPath(), f[1].getBytes());
            x.setLastModified(old);
        }
        Stickd d = new Stickd(dir.getPath());
        final java.util.List<String> ran = new java.util.ArrayList<>();
        for (String id : new String[] { "sleep_now", "dns_check", "boost" }) d.register(id, a -> ran.add(id));
        java.lang.reflect.Method m = Stickd.class.getDeclaredMethod("drainSpool", File.class, long.class);
        m.setAccessible(true);
        m.invoke(d, act, System.currentTimeMillis() - 30_000);
        T.eq(java.util.Arrays.asList("dns_check"), ran, "only the safety check survives the stale drop");
        T.eq(0, act.list().length, "all consumed");
    }

    // ---- I5 + M3: update always ensures, foreign actions ignored; uninstall watchdog ----
    public void testBootActions() {
        T.yes(BootActions.shouldEnsure(BootActions.MY_PACKAGE_REPLACED, true), "update re-ensures even when ensured");
        T.yes(BootActions.shouldEnsure(BootActions.BOOT_COMPLETED, false), "boot, not yet ensured");
        T.yes(!BootActions.shouldEnsure(BootActions.BOOT_COMPLETED, true), "boot, already ensured");
        T.yes(!BootActions.accepted("com.evil.POKE") && !BootActions.shouldEnsure("com.evil.POKE", false), "foreign action ignored");
        T.yes(!BootActions.accepted(null), "null");
    }

    public void testPmPath() {
        T.eq("/data/app/~~x==/com.clean.stickman-y==/base.apk",
            Stickd.pmPath("package:/data/app/~~x==/com.clean.stickman-y==/base.apk\n"), "path");
        T.eq(null, Stickd.pmPath(""), "uninstalled");
        T.eq(null, Stickd.pmPath(null), "null");
        T.eq(null, Stickd.pmPath("Error: package not found\n"), "error text");
    }

    public void testWatchdogSeesInstalledApk() throws Exception {
        File dir = tmp("wd");
        File apk = new File(dir, "base.apk");
        Files.write(apk.toPath(), "x".getBytes());
        Files.write(new File(dir, "apk").toPath(), (apk.getPath() + "\n").getBytes());
        T.yes(!WatchdogProbe.gone(dir.getPath()), "apk present -> not gone");
    }

    // ---- M8 ----
    public void testRemapdLog() {
        T.eq(RemapdLog.RELEASED, RemapdLog.kind("11-05 01:25:03.123 I/RemapD( 6538): BACK held 10s -> exiting, remote released"), "released");
        T.eq(RemapdLog.STARTED, RemapdLog.kind("11-05 01:26:00.000 I/RemapD( 7000): remapd starting pid 7000 device \"Amazon Fire TV Remote\""), "started");
        T.eq(RemapdLog.OTHER, RemapdLog.kind("11-05 01:26:00.000 I/Stickd( 7000): BACK held 10s -> exiting"), "other tag");
        T.yes(RemapdLog.released(200, 100), "released after start");
        T.yes(!RemapdLog.released(100, 200), "restarted since");
        T.yes(!RemapdLog.released(0, 0), "nothing seen");
    }
}
