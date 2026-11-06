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

    static String lastOut = "";
    static String run(String script, java.io.File bin, java.io.File log) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", script);
        pb.environment().put("PATH", bin.getPath() + ":" + System.getenv("PATH"));
        pb.environment().put("LOG", log.getPath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(readAll(p.getInputStream()));
        lastOut = out;
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
        T.eq("", run(Debloat.restoreAllScript(new java.io.File(d, "none").getPath()) + "; echo AFTER", bin, log), "nothing");
        T.yes(lastOut.contains("RESTORED 0") && lastOut.contains("AFTER"), "appended refresh still runs: " + lastOut);
        T.yes(!Debloat.restoreAllScript().contains("exit"), "no exit");
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
        T.yes(Debloat.enableOne("com.amazon.tv.launcher", j.getPath()) != null, "re-enabling a protected pkg is allowed");
        T.yes(Commands.debloat("com.amazon.tv.launcher", false) != null, "enable allowed");
        T.eq(null, Commands.debloat("com.amazon.tv.launcher", true), "disable refused");
        T.eq(null, Debloat.enableOne("a b", j.getPath()), "bad");
    }
    public void testOfflineLabels() {
        T.eq("Ambient screensaver", Catalog.label("com.amazon.tv.ftvambient"), "catalogue label");
        T.eq("Android system", Catalog.label("system_server"), "system process");
        T.eq(null, Catalog.label("com.netflix.ninja"), "unknown: caller falls back to the installed label");
        T.eq(null, Catalog.label(null), "null");
        T.eq("Photo screensaver; the biggest app (~100 MB)", Catalog.why("com.amazon.tv.ftvambient"), "why");
        T.eq(null, Catalog.why("system_server"), "no why for non-debloat entries");
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

    static List<String> fixture() throws Exception {
        List<String> out = new ArrayList<>();
        for (String l : java.nio.file.Files.readAllLines(java.nio.file.Paths.get("tests/fixtures/amazon_pkgs.txt")))
            if (l.startsWith("package:")) out.add(l.substring(8).trim());
        return out;
    }
    static final String[] CRITICAL = {
        "com.amazon.tv.oobe", "com.amazon.tv.ooberesource", "com.amazon.webview.chromium", "com.amazon.venezia",
        "com.amazon.android.marketplace", "com.amazon.dcp", "com.amazon.dcp.contracts.framework.library",
        "com.amazon.dcp.contracts.library", "com.amazon.identity.auth.device.authorization", "com.amazon.device.sync",
        "com.amazon.device.sync.sdk.internal", "com.amazon.securitysyncclient", "com.amazon.providers.contentsupport",
        "com.amazon.providers.tv", "com.amazon.tv.intentsupport", "com.amazon.tv.devicecontrol",
        "com.amazon.tv.devicecontrolsettings", "com.amazon.connectivitycontroller", "com.amazon.connectivitydiag",
        "com.amazon.connection_manager_neo_service", "com.amazon.wifilocker", "com.amazon.tv.ime",
        "com.amazon.tv.keypolicymanager", "com.amazon.tv.quicksettings.ui", "com.amazon.tv.quicksettings.ui.overlay",
        "com.amazon.tv.forcedotaupdater.v2", "com.amazon.tv.easyupgrade", "com.amazon.tiramisubootsupport",
        "com.amazon.device.lowstoragemanager", "com.amazon.storage.manager", "com.amazon.application.compatibility.enforcer",
        "com.amazon.application.compatibility.enforcer.sdk.library", "com.amazon.appaccesskeyprovider",
        "com.amazon.tv.routing", "com.amazon.tv.arc", "com.amazon.tv.resolutioncycler", "com.amazon.ftvcalibrationservice",
        "com.amazon.tv.sysappsinternal.resources", "com.amazon.android.devicemodelinfo.resources",
        "com.amazon.tcomm", "com.amazon.tcomm.client", "com.amazon.tcomm.jackson", "com.amazon.ssm", "com.amazon.ssmsys",
        "com.amazon.uxcontrollerservice", "com.amazon.dpcclient", "com.amazon.ftv.profilepicker",
        "com.amazon.tv.parentalcontrols", "com.amazon.tifobserver", "com.amazon.device.messaging",
        "com.amazon.device.messaging.sdk.library", "com.amazon.csm.htmlruntime", "com.amazon.whisperlink.core.android",
        "com.amazon.whisperjoin.middleware.v2.np", "com.amazon.whisperplay.contracts", "com.amazon.whisperplay.service.install",
        "com.amazon.client.metrics.api", "com.amazon.dummy.settings", "com.amazon.whasettings",
        "com.amazon.device.blepa", "com.amazon.autopairservice", "com.amazon.accessoryconfigservice",
        "com.amazon.tv.ffsprovisioneeclient", "com.amazon.alta.h2clientservice", "com.amazon.platform.fdra",
        "com.amazon.alexadirectivebrokerservice", "com.amazon.tv.consents", "com.amazon.tv.turnstile", "com.amazon.tv.launcher", "com.amazon.firehomestarter", "com.amazon.tv.settings.v2", "com.amazon.device.software.ota",
    };
    public void testAdvancedOverRealDeviceListExcludesCritical() throws Exception {
        List<String> real = fixture();
        T.eq(179, real.size(), "fixture size");
        StringBuilder sb = new StringBuilder();
        for (String p : real) sb.append("package:").append(p).append('\n');
        Debloat d = Debloat.parse(sb.toString() + Debloat.SEP + "\n");
        List<String> adv = d.advanced();
        for (String c : CRITICAL) {
            T.yes(real.contains(c), "critical pkg present on device: " + c);
            T.yes(!adv.contains(c), "offered but critical: " + c);
        }
        for (String p : real) {
            String l = p.toLowerCase();
            if (l.startsWith("com.amazon.dcp") || l.startsWith("com.amazon.device.settings") || l.startsWith("com.amazon.tv.devicecontrol"))
                T.yes(!adv.contains(p), "family offered: " + p);
        }
        T.eq(Catalog.DEBLOAT_RECOMMENDED.length, d.recommended().size(), "all recommended still offered");
        System.out.println("  advanced offered over real list: " + adv.size() + " of " + real.size());
    }
    public void testRulingFamiliesBlocked() {
        String[] fam = { "com.amazon.tv.oobe", "com.android.webview", "com.amazon.dcp.x", "com.amazon.identity.y",
            "com.amazon.account.z", "com.amazon.device.sync", "com.amazon.device.settings", "com.amazon.device.settings.foo",
            "com.amazon.providers.x", "com.amazon.tv.intentsupport", "com.amazon.tv.devicecontrol", "com.amazon.tv.cec",
            "com.amazon.connectivity", "com.amazon.network.x", "com.amazon.wifi.x", "com.android.packageinstaller", "com.amazon.venezia" };
        for (String f : fam) T.yes(Catalog.isProtected(f), f);
        for (String[] r : Catalog.DEBLOAT_RECOMMENDED) T.yes(!Catalog.isProtected(r[0]), "recommended stays offered: " + r[0]);
    }
    public void testDisableAllScript() {
        java.util.List<String[]> rec = new java.util.ArrayList<>();
        rec.add(new String[] {"com.amazon.tv.acr", "a", "x"});
        rec.add(new String[] {"com.amazon.tv.launcher", "b", "x"});   // protected: skipped
        rec.add(new String[] {"com.amazon.sneakpeek", "c", "x"});
        String s = Debloat.disableAllScript(rec);
        T.yes(s.contains("pm disable-user --user 0 com.amazon.tv.acr"), "acr");
        T.yes(s.contains("pm disable-user --user 0 com.amazon.sneakpeek"), "sneakpeek");
        T.yes(!s.contains("tv.launcher"), "protected skipped");
        T.yes(s.contains("pkg com.amazon.tv.acr"), "journaled");
        T.yes(s.trim().endsWith("echo DISABLED_ALL $n"), "count line");
        T.yes(Debloat.disableAllScript(new java.util.ArrayList<String[]>()) == null, "empty -> null");
    }
}
