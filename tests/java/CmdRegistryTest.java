import com.clean.stickman.core.cmd.Cmd;
import com.clean.stickman.core.cmd.Tools;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CmdRegistryTest {
    static final int[] APIS = { 22, 25, 28, 30 };

    /** Files that still build shell commands outside core/cmd. Migration removes entries; nothing may be added. */
    static final Set<String> NOT_MIGRATED = new HashSet<>(Arrays.asList(
        // group A
        "core/Commands.java", "core/EnsureScript.java", "core/DeviceInfo.java", "core/KillPolicy.java", "core/Trim.java",
        "core/LruParser.java", "StatusPage.java", "ShortcutsPage.java", "SleepPage.java", "WhatsNew.java", "SettingsPage.java", "core/Actions.java", "core/LiveValues.java",
        "core/SleepTimer.java",
        // group B
        "core/Lean.java", "core/Originals.java", "core/Starve.java", "core/Ota.java", "core/AmazonUninstall.java",
        "core/BgTime.java", "core/BootStart.java", "core/DnsGuard.java", "core/Debloat.java", "core/Telemetry.java",
        "SpeedPage.java", "PrivacyPage.java", "DebloatPage.java",
        // group C
        "daemon/Stickd.java", "daemon/Sleep.java", "daemon/AutoKill.java", "daemon/TaskSampler.java", "daemon/Updater.java",
        "daemon/SoftPointer.java"));

    /** A string literal that starts a system tool: the mark of a shell command built in place. */
    static final Pattern SHELLISH = Pattern.compile(
        "\"[^\"]*\\b(dumpsys|pm|am|cmd|settings|device_config|appops|svc|sm|logcat|input|media|content|screencap|getprop|setprop|pidof|base64|setsid) [a-z-]");

    /** Loads every *Cmds class so its Cmd.def entries are registered. */
    static void loadAll() throws Exception {
        File dir = new File("src/com/clean/stickman/core/cmd");
        for (File f : dir.listFiles())
            if (f.getName().endsWith("Cmds.java"))
                Class.forName("com.clean.stickman.core.cmd." + f.getName().replace(".java", ""), true, CmdRegistryTest.class.getClassLoader());
    }

    public void testEveryCommandIsSuitableWhereItClaims() throws Exception {
        loadAll();
        StringBuilder bad = new StringBuilder();
        for (Cmd c : Cmd.all())
            for (int api : APIS) {
                if (!c.availableOn(api)) continue;
                String m = Tools.firstMissing(c.toolsOn(api), api);
                if (m != null) bad.append(c.id).append(" needs ").append(m).append(" on API ").append(api).append("; ");
            }
        T.eq("", bad.toString(), "commands declared available on a version that lacks one of their tools");
    }

    public void testToolsTable() {
        T.eq("device_config", Tools.firstMissing("settings device_config", 28), "missing binary");
        T.eq(null, Tools.firstMissing("settings device_config", 30), "there on Fire OS 8");
        T.eq("cmd:package", Tools.firstMissing("cmd:package", 22), "whole tool missing covers its subcommands");
        T.eq(null, Tools.firstMissing("base64 setsid", 22), "bundled toybox covers the basics on Fire OS 5");
        T.eq("media", Tools.firstMissing("media", 30), "Fire OS 8 has no media binary");
    }

    public void testLabels() {
        Cmd c = Cmd.def("test.label_probe", "Test", 28, "am:set-standby-bucket");
        T.eq("Needs Fire OS 7 or later", c.unavailableLabel(25), "label");
        T.eq(null, c.unavailableLabel(28), "available");
    }

    /** Shell commands may only be built in core/cmd (or in a file still waiting for its migration). */
    public void testNoShellCommandsOutsideTheRegistry() throws Exception {
        Set<String> offenders = new TreeSet<>(), stale = new TreeSet<>(NOT_MIGRATED);
        scan(new File("src/com/clean/stickman"), "", offenders, stale);
        T.eq("[]", offenders.toString(), "files building shell commands outside core/cmd");
        T.eq("[]", stale.toString(), "files listed as not migrated that no longer build shell commands: remove them from the list");
    }

    private static void scan(File dir, String rel, Set<String> offenders, Set<String> stale) throws Exception {
        for (File f : dir.listFiles()) {
            String r = rel.isEmpty() ? f.getName() : rel + "/" + f.getName();
            if (f.isDirectory()) { if (!r.equals("core/cmd")) scan(f, r, offenders, stale); continue; }
            if (!r.endsWith(".java")) continue;
            boolean hit = false;
            for (String line : new String(Files.readAllBytes(f.toPath()), "UTF-8").split("\n")) {
                String t = line.trim();
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
                Matcher m = SHELLISH.matcher(line);
                if (m.find()) { hit = true; break; }
            }
            if (hit && !NOT_MIGRATED.contains(r)) offenders.add(r);
            if (hit) stale.remove(r);
        }
    }
}
