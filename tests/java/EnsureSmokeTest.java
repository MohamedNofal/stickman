import com.clean.stickman.core.EnsureScript;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Runs the real ensure/stop scripts under sh with /data/local/tmp rewritten to a temp dir and stub
 * pm/cmd/am/app_process on PATH; fake daemons are `exec -a <name> sleep` (cmdline arg0 = name).
 */
public class EnsureSmokeTest {
    File root;
    String path;

    void setUp() throws Exception {
        root = Files.createTempDirectory("ensure").toFile();
        File bin = new File(root, "bin"), lib = new File(root, "lib");
        bin.mkdirs(); lib.mkdirs();
        String stub = "#!/bin/sh\necho \"$(basename $0) $*\" >> " + root + "/log\nexit 0\n";
        for (String n : new String[] { "pm", "cmd", "am" }) exe(new File(bin, n), stub);
        exe(new File(lib, "libremapd.so"),
            "#!/bin/bash\necho $$ > " + root + "/stickman/remapd.pid; exec -a remapd sleep 60\n");
        exe(new File(bin, "app_process"),
            "#!/bin/bash\necho $$ > " + root + "/stickman/stickd.pid; exec -a stickd sleep 60\n");
        path = bin + ":" + System.getenv("PATH");
    }

    static void exe(File f, String s) throws Exception {
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
        f.setExecutable(true);
    }

    String run(String script) throws Exception {
        String s = script.replace("/data/local/tmp", root.getPath());
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", s).redirectErrorStream(true);
        pb.environment().put("PATH", path);
        Process p = pb.start();
        String out = new String(EnsureScriptTest.readAll(p.getInputStream()), StandardCharsets.UTF_8).trim();
        p.waitFor();
        return out;
    }

    String ensure(int version, String remap) {
        return EnsureScript.build("/x/base.apk", root + "/lib/libremapd.so", version, remap, "stayawake.media=0\n", true)
            .replace(root.getPath(), "/data/local/tmp");   // lib path gets rewritten back by run()
    }

    static String last(String out) { String[] l = out.split("\n"); return l[l.length - 1]; }

    public void testEnsureLifecycle() throws Exception {
        if (!new File("/bin/bash").exists()) { System.out.println("  (skipped: no bash)"); return; }
        setUp();
        try {
            String r1 = last(run(ensure(3, "172 tap orig\n")));
            T.yes(r1.matches("REMAPD \\d+ STICKD \\d+"), "first run starts both: " + r1);

            String r2 = last(run(ensure(3, "172 tap orig\n")));
            T.eq(r1, r2, "second run is a no-op (same pids)");
            String[] act = new File(root, "stickman/act").list();
            T.eq(0, act == null ? 0 : act.length, "no reload spooled without a change");

            String r3 = last(run(ensure(3, "172 tap none\n")));
            T.eq(r1, r3, "conf change keeps the daemons");
            act = new File(root, "stickman/act").list();
            T.eq(1, act.length, "one spooled action");
            T.yes(act[0].endsWith(".act"), "spooled as .act: " + act[0]);
            T.eq("reload\n", new String(Files.readAllBytes(new File(root, "stickman/act/" + act[0]).toPath()), StandardCharsets.UTF_8), "reload");
            T.eq("172 tap none\n", new String(Files.readAllBytes(new File(root, "stickman/remap.conf").toPath()), StandardCharsets.UTF_8), "conf written");

            String r4 = last(run(ensure(4, "172 tap none\n")));
            T.yes(r4.matches("REMAPD \\d+ STICKD \\d+"), "both running after bump: " + r4);
            String[] a = r1.split(" "), b = r4.split(" ");
            T.yes(!a[1].equals(b[1]) && !a[3].equals(b[3]), "version bump restarts both: " + r1 + " -> " + r4);
            T.eq("4\n", new String(Files.readAllBytes(new File(root, "stickman/version").toPath()), StandardCharsets.UTF_8), "version file");

            String log = new String(Files.readAllBytes(new File(root, "log").toPath()), StandardCharsets.UTF_8);
            T.yes(!log.contains("pm uninstall"), "no migration when neither old dir nor package exists");

            T.eq("STOPPED", last(run(EnsureScript.stop())), "stop");
            T.yes(!new File("/proc/" + b[1]).exists() || !alive(b[1]), "remapd killed");
            T.yes(!new File("/proc/" + b[3]).exists() || !alive(b[3]), "stickd killed");
        } finally {
            run(EnsureScript.stop());
        }
    }

    public void testMigrationRunsWhenOldDirExists() throws Exception {
        if (!new File("/bin/bash").exists()) { System.out.println("  (skipped: no bash)"); return; }
        setUp();
        try {
            new File(root, "remap").mkdirs();
            String out = run(ensure(1, ""));
            T.yes(out.contains("MIGRATED"), "migrated: " + out);
            T.yes(!new File(root, "remap").exists(), "old dir removed");
            String log = new String(Files.readAllBytes(new File(root, "log").toPath()), StandardCharsets.UTF_8);
            T.yes(log.contains("pm uninstall com.clean.keyshift"), "keyshift uninstalled");
        } finally {
            run(EnsureScript.stop());
        }
    }

    static boolean alive(String pid) {
        try {
            String st = new String(Files.readAllBytes(new File("/proc/" + pid + "/stat").toPath()));
            return !st.contains(") Z");
        } catch (Exception e) { return false; }
    }
}
