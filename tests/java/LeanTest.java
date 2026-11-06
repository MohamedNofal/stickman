import com.clean.stickman.core.EnsureScript;
import com.clean.stickman.core.Lean;
import com.clean.stickman.core.Originals;

import java.io.File;
import java.nio.file.Files;

public class LeanTest {
    /** Fake device_config / settings backed by files in dir/kv (one file per "ns.key"); calls logged to calls.log. */
    static String sh(File dir, String script) throws Exception {
        File bin = new File(dir, "bin");
        File kv = new File(dir, "kv");
        bin.mkdirs();
        kv.mkdirs();
        String log = new File(dir, "calls.log").getPath();
        String store = "echo \"$(basename $0) $*\" >> " + log + "\n"
            + "f=" + kv.getPath() + "/$2.$3\n"
            + "case $1 in get) [ -f $f ] && cat $f || echo null;; put) echo \"$4\" > $f;; delete) rm -f $f;; esac\n";
        StarveTest.fake(bin, "device_config", store);
        StarveTest.fake(bin, "settings", store);
        StarveTest.fake(bin, "dumpsys", "echo \"dumpsys $*\" >> " + log + "\n");
        String j = new File(dir, "originals").getPath();
        String s = script.replace(Originals.FILE, j).replace(Originals.DIR, dir.getPath());
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", s).redirectErrorStream(true);
        pb.environment().put("PATH", bin.getPath() + ":" + System.getenv("PATH"));
        Process q = pb.start();
        q.getOutputStream().close();
        String out = new String(StarveTest.readAll(q.getInputStream()));
        q.waitFor();
        return out;
    }

    static String kv(File dir, String k) throws Exception {
        File f = new File(dir, "kv/" + k);
        return f.exists() ? new String(Files.readAllBytes(f.toPath())).trim() : null;
    }

    public void testLevelsApplyAndOffGivesBackOriginals() throws Exception {
        File d = StarveTest.tmp();
        new File(d, "kv").mkdirs();
        Files.write(new File(d, "kv/activity_manager.use_compaction").toPath(), "false\n".getBytes());   // pre-existing
        sh(d, Lean.memory(2));
        T.eq("4", kv(d, "activity_manager.max_cached_processes"), "cap 4");
        T.eq("true", kv(d, "activity_manager.use_compaction"), "compaction");
        T.eq(Lean.RESTART, kv(d, "global.activity_manager_constants"), "slow restarts");
        sh(d, Lean.memory(1));
        T.eq("8", kv(d, "activity_manager.max_cached_processes"), "cap 8");
        T.eq(null, kv(d, "global.activity_manager_constants"), "balanced gives the restart timing back");
        sh(d, Lean.memory(2));
        sh(d, Lean.memory(0));
        T.eq(null, kv(d, "activity_manager.max_cached_processes"), "cap deleted (was unset)");
        T.eq("false", kv(d, "activity_manager.use_compaction"), "compaction back to its original");
        T.eq(null, kv(d, "global.activity_manager_constants"), "restarts back");
        File j = new File(d, "originals");
        T.yes(!j.exists() || new String(Files.readAllBytes(j.toPath())).trim().isEmpty(), "journal lines dropped");
    }

    public void testRestoreReplaysDeviceConfig() throws Exception {
        File d = StarveTest.tmp();
        sh(d, Lean.memory(2));
        sh(d, Originals.restoreScript(new File(d, "originals").getPath()));
        T.eq(null, kv(d, "activity_manager.max_cached_processes"), "restore deletes cap");
        T.eq(null, kv(d, "activity_manager.use_compaction"), "restore deletes compaction");
        T.eq(null, kv(d, "global.activity_manager_constants"), "restore deletes restart timing");
        T.yes(Originals.restoreScript().contains(Lean.DOZE_OFF), "Restore also turns Doze off");
    }

    public void testDoze() throws Exception {
        File d = StarveTest.tmp();
        sh(d, Lean.dozeOn());
        File f = new File(d, "doze.sh");
        T.yes(f.exists(), "doze.sh written");
        String calls = new String(Files.readAllBytes(new File(d, "calls.log").toPath()));
        T.yes(calls.contains("dumpsys deviceidle enable all"), calls);
        T.yes(calls.contains("dumpsys deviceidle whitelist +com.clean.netfilter"), calls);
        T.yes(calls.contains("dumpsys deviceidle sys-whitelist -com.amazon.device.logmanager"), calls);
        for (String p : Lean.UNEXEMPT) T.yes(!p.contains("ota") && !p.contains("software"), "never the updater: " + p);
        sh(d, Lean.dozeOff());
        T.yes(!f.exists(), "doze.sh removed");
        calls = new String(Files.readAllBytes(new File(d, "calls.log").toPath()));
        T.yes(calls.contains("dumpsys deviceidle disable all") && calls.contains("sys-whitelist reset"), calls);
        T.yes(EnsureScript.build("/data/app/x/base.apk", "/data/app/x/lib/arm/libremapd.so", 1, "", "", false)
            .contains(Lean.ENSURE_LINE), "ensure re-applies doze after boot");
    }

    public void testParse() {
        T.eq(0, Lean.parse("LEAN null null off DOZE off false")[0], "off");
        T.eq(1, Lean.parse("LEAN 8 true off DOZE off false")[0], "balanced");
        T.eq(2, Lean.parse("x\nLEAN 4 true on DOZE on true\n")[0], "max");
        int[] full = Lean.parse("LEAN 4 true on DOZE on true CPU on QUIET off");
        T.eq(2, full[0], "level with the new fields");
        T.eq(1, full[2], "cpu limit on");
        T.eq(0, full[3], "quiet off");
        T.eq(0, Lean.parse("LEAN 4 true on DOZE on true")[2], "old line: cpu off");
        T.eq(1, Lean.parse("LEAN 4 true on DOZE on true")[1], "doze on");
        T.eq(0, Lean.parse("LEAN 4 true on DOZE on false")[1], "file but deep idle reset = off");
        T.eq(-1, Lean.parse("LEAN 12 true off DOZE off false")[0], "custom");
        T.eq(null, Lean.parse("nothing"), "none");
        long[] f = Lean.parseFreed("FREED 136000 2");
        T.eq(136000L, f[0], "kB");
        T.eq(2L, f[1], "apps");
        T.eq(null, Lean.parseFreed("x"), "no FREED");
    }

    public void testFreeSpaceOnlyTouchesDisabledUpdatedJournaledApps() throws Exception {
        File d = StarveTest.tmp();
        File bin = new File(d, "bin");
        bin.mkdirs();
        String log = new File(d, "calls.log").getPath();
        // disabled: com.a (updated), com.b (factory only); com.c journaled but enabled again
        StarveTest.fake(bin, "pm", "echo \"pm $*\" >> " + log + "\n"
            + "case \"$1 $2\" in 'list packages') printf 'package:com.a\\npackage:com.b\\n';; "
            + "'path com.a') [ -f " + d + "/aun ] && echo package:/system/app/a-stub.apk || echo package:/data/app/~~x/com.a-y/base.apk;; "
            + "'uninstall-system-updates com.a') touch " + d + "/aun; exit 1;; 'path com.b') echo package:/system/app/b.apk;; "
            + "'path com.c') echo package:/data/app/~~x/com.c/base.apk;; esac\n");
        StarveTest.fake(bin, "df", "c=$(cat " + d + "/dfc 2>/dev/null || echo 0); echo $((c+1)) > " + d + "/dfc\n"
            + "echo hdr; echo \"/dev/x 100 50 $((1000 + c * 500)) 50% /data\"\n");
        File j = new File(d, "originals");
        Files.write(j.toPath(), "pkg com.a\npkg com.b\npkg com.c\nsetting global k 1\n".getBytes());
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", Lean.freeSpaceScript(j.getPath())).redirectErrorStream(true);
        pb.environment().put("PATH", bin.getPath() + ":" + System.getenv("PATH"));
        Process q = pb.start();
        String out = new String(StarveTest.readAll(q.getInputStream()));
        q.waitFor();
        T.yes(out.contains("FREED 500 1"), out);
        String calls = new String(Files.readAllBytes(new File(log).toPath()));
        T.yes(calls.contains("pm uninstall-system-updates com.a"), calls);
        T.yes(calls.contains("pm disable-user --user 0 com.a"), "disabled again: " + calls);
        T.yes(!calls.contains("uninstall-system-updates com.b") && !calls.contains("uninstall-system-updates com.c"), calls);
    }

    public void testFreeSpaceDetachedReportsViaLogcat() {
        String s = Lean.freeSpaceDetached();
        T.yes(s.contains("setsid nohup") && s.contains("log -t Stickd -p i \"STATUS freed $l\"") && s.endsWith("echo FREEING"), s);
        String b64 = s.substring(s.indexOf("echo ") + 5, s.indexOf(" | base64"));
        T.eq(Lean.freeSpaceScript(), new String(java.util.Base64.getDecoder().decode(b64)), "same script");
    }

    public void testCpuLimitAndQuietShareSettingsWithLean() throws Exception {
        File d = StarveTest.tmp();
        sh(d, Lean.memory(2, true));
        T.eq(Lean.RESTART + "," + Lean.CPU_LIMIT, kv(d, "global.activity_manager_constants"), "max + cpu limit in one setting");
        sh(d, Lean.cpuLimit(2, false));
        T.eq(Lean.RESTART, kv(d, "global.activity_manager_constants"), "cpu off keeps max's slow restarts");
        sh(d, Lean.memory(1, true));
        T.eq(Lean.CPU_LIMIT, kv(d, "global.activity_manager_constants"), "balanced + cpu");
        sh(d, Lean.memory(0, false));
        T.eq(null, kv(d, "global.activity_manager_constants"), "all off: original (unset) back");
        sh(d, Lean.quiet(true));
        T.eq(Lean.QUIET_ALARM, kv(d, "global.alarm_manager_constants"), "alarms spaced");
        T.eq("2", kv(d, "jobscheduler.max_job_max_bg_on_normal"), "jobs");
        T.eq("10", kv(d, "jobscheduler.min_ready_non_active_jobs_count"), "batching");
        sh(d, Lean.quiet(false));
        T.eq(null, kv(d, "global.alarm_manager_constants"), "alarms back");
        T.eq(null, kv(d, "jobscheduler.max_job_max_bg_on_normal"), "jobs back");
        T.yes(Lean.READ.contains("power_check_interval=60000") && Lean.READ.contains("min_interval=900000"), "READ reports both");
    }

    public void testRecommendedIsExactlyTheChipCommands() {
        String[] r = Lean.recommended();
        T.eq(3, r.length, "three bridge commands");
        T.eq(Lean.memory(1, true), r[0], "Balanced with the CPU limit kept on");
        T.eq(Lean.dozeOn(), r[1], "deep sleep on");
        T.eq(Lean.quiet(true), r[2], "fewer wake-ups on");
        T.eq(5, Lean.RECOMMENDED_STEPS.length, "every step is named for the confirm dialog");
        for (String s : Lean.RECOMMENDED_STEPS) T.yes(!s.contains("device_config") && !s.contains("doze"), "plain words: " + s);
    }

    public void testClearCaches() {
        String s = Lean.clearCachesScript();
        T.yes(s.contains("pm trim-caches 999G") && s.contains("sm fstrim") && s.endsWith("echo \"CLEARED $((a-b))\""), s);
        String det = Lean.clearCachesDetached();
        T.yes(det.contains("STATUS cleared $l") && det.endsWith("echo CLEARING"), det);
        T.eq(163000L, (long) Lean.parseCleared("CLEARED 163000"), "kB");
        T.eq(null, Lean.parseCleared("nothing"), "none");
    }
}
