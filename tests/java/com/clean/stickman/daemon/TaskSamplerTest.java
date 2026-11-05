package com.clean.stickman.daemon;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Pure parts of TaskSampler, driven by a fake /proc and clock (daemon package: TaskSampler is package-private). */
public class TaskSamplerTest {
    static void yes(boolean c, String m) { if (!c) throw new AssertionError(m); }

    static class FakeSrc implements TaskSampler.Src {
        long jiffies = 1000, ticks = 10;
        final Map<String, String> files = new HashMap<>();
        FakeSrc() {
            files.put("/proc/meminfo", "MemTotal:        1900000 kB\nMemFree:  100 kB\nMemAvailable:     700000 kB\n");
            files.put("/proc/uptime", "5000.50 9000.00\n");
            files.put("/proc/cpuinfo", "processor\t: 0\nprocessor\t: 1\n");
            files.put("/proc/10/cmdline", "org.videolan.vlc\0");
            files.put("/proc/10/statm", "1000 500 100 1 0 200 0\n");
            files.put("/proc/11/cmdline", "org.videolan.vlc:remote\0");
            files.put("/proc/11/statm", "1000 250 100 1 0 200 0\n");
            files.put("/proc/12/cmdline", "com.clean.stickman\0");
            files.put("/proc/12/statm", "1000 9999 100 1 0 200 0\n");
            files.put("/proc/13/cmdline", "");  // kernel thread
            files.put("/proc/14/cmdline", "com.gone.app\0");  // statm/stat unreadable: vanished
            files.put("/proc/16/cmdline", "stickd\0");         // Stickman's own daemons: never in the feed
            files.put("/proc/16/statm", "1000 300 100 1 0 200 0\n");
            files.put("/proc/17/cmdline", "remapd\0");
            files.put("/proc/17/statm", "1000 30 10 1 0 20 0\n");
        }
        public String read(String path) {
            if (path.equals("/proc/stat")) return "cpu  " + (jiffies / 2) + " 0 0 " + (jiffies / 2) + " 0 0 0 0 0 0\ncpu0 1 1 1 1\n";
            if (path.endsWith("/stat") && path.startsWith("/proc/1")) {
                String pid = path.split("/")[2];
                if (files.get("/proc/" + pid + "/statm") == null) return null;
                return pid + " (x) S 1 1 0 0 -1 0 0 0 0 0 " + ticks + " 0 0 0 20 0 1 0 100000 1 1";
            }
            return files.get(path);
        }
        public String[] pids() { return new String[] {"10", "11", "12", "13", "14", "16", "17", "self"}; }
        public String sh(String cmd) {
            if (cmd.contains("query-activities")) return "org.videolan.vlc/.StartActivity\n";
            return "";
        }
    }

    static TaskSampler sampler(FakeSrc src, AtomicLong clock, List<List<String>> out) {
        return new TaskSampler(src, out::add, () -> "com.x", () -> null, clock::get);
    }

    public void testStopsWithoutKeepalive() {
        yes(!TaskSampler.shouldStop(0, 90_000), "exactly 90 s is still live");
        yes(TaskSampler.shouldStop(0, 90_001), "90001 ms stops");
        AtomicLong clock = new AtomicLong(1_000_000);
        List<List<String>> out = new ArrayList<>();
        TaskSampler t = sampler(new FakeSrc(), clock, out);
        t.keepalive();
        clock.addAndGet(60_000);
        yes(t.tick(), "live after 60 s");
        t.keepalive();
        clock.addAndGet(90_000);
        yes(t.tick(), "keepalive reset the 90 s window");
        clock.addAndGet(1);
        yes(!t.tick(), "stops 90001 ms after the last keepalive");
    }

    public void testSeqIncrements() {
        AtomicLong clock = new AtomicLong(1_000_000);
        List<List<String>> out = new ArrayList<>();
        FakeSrc src = new FakeSrc();
        TaskSampler t = sampler(src, clock, out);
        t.keepalive();
        for (int i = 1; i <= 3; i++) {
            yes(t.tick(), "tick " + i);
            src.jiffies += 200;
            src.ticks += 10;
            clock.addAndGet(2000);
        }
        yes(out.size() == 3, "three samples, got " + out.size());
        for (int i = 0; i < 3; i++) {
            List<String> l = out.get(i);
            String seq = "seq=" + (i + 1);
            yes(l.get(0).startsWith("STATUS tasks total=1900000 avail=700000 cpu=") && l.get(0).endsWith(" " + seq), l.get(0));
            yes(l.get(l.size() - 1).equals("STATUS tasks_end " + seq), l.get(l.size() - 1));
            for (String s : l) {
                yes(s.endsWith(" " + seq), "seq on every line: " + s);
                yes(!s.contains("com.clean.stickman"), "Stickman itself excluded: " + s);
                yes(!s.contains("com.gone.app"), "vanished pid dropped: " + s);
                yes(!s.contains("stickd") && !s.contains("remapd"), "own daemons dropped: " + s);
                yes(!s.contains("_sys_other"), "nothing folded, no _sys_other line: " + s);
            }
            yes(l.size() == 3, "totals + vlc + end: " + l);
        }
        String vlc = out.get(2).get(1);
        // 2 procs, (500+250) pages * 4 kB; up = 5000 s - 1000 s start; cpu = 2*10 ticks / 200 jiffies = 10 %
        yes(vlc.equals("STATUS task org.videolan.vlc rss=3000 cpu=10 state=bg up=4000 procs=2 seq=3"), vlc);
        yes(out.get(0).get(1).contains(" cpu=0 "), "no previous sample: cpu 0: " + out.get(0).get(1));
        yes(out.get(1).get(0).contains(" cpu=50 "), "half the jiffies are idle: " + out.get(1).get(0));
    }

    /** Feed lines are batched into log entries under toybox log's 1024-byte cut, never splitting a line. */
    public void testLogChunks() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 40; i++) lines.add("STATUS task com.example.app" + i + " rss=123456 cpu=3 state=bg up=999 procs=2 seq=7");
        List<String> c = Sh.chunks(lines, Sh.LOG_CHUNK);
        yes(c.size() > 1, "split into several entries");
        List<String> back = new ArrayList<>();
        for (String s : c) {
            yes(s.getBytes().length < 1024, "chunk under 1024 bytes: " + s.length());
            back.addAll(Arrays.asList(s.split("\n")));
        }
        yes(back.equals(lines), "lines survive intact and in order");
    }

    /** cmdlines are cached per pid, except unspecialised zygote forks, which turn into apps. */
    public void testZygoteForkBecomesApp() {
        AtomicLong clock = new AtomicLong(1_000_000);
        List<List<String>> out = new ArrayList<>();
        FakeSrc src = new FakeSrc() {
            @Override public String[] pids() { return new String[] {"10", "15"}; }
        };
        src.files.put("/proc/15/cmdline", "<pre-initialized>\0");
        src.files.put("/proc/15/statm", "1000 100 100 1 0 200 0\n");
        TaskSampler t = sampler(src, clock, out);
        t.keepalive();
        t.tick();
        src.files.put("/proc/15/cmdline", "com.new.app\0");
        t.tick();
        yes(String.join("|", out.get(1)).contains("task com.new.app "), "specialised fork shows: " + out.get(1));
    }

    static com.clean.stickman.core.TaskSnapshot.App app(String pkg, long rss, int cpu, String state) {
        return new com.clean.stickman.core.TaskSnapshot.App(pkg, rss, cpu, state, 5, 1);
    }

    /** R7: launchable apps all listed; only the 8 largest sys packages listed, the rest folded into _sys_other. */
    public void testSysFoldedIntoOther() {
        List<com.clean.stickman.core.TaskSnapshot.App> in = new ArrayList<>();
        in.add(app("org.videolan.vlc", 50, 1, "bg"));
        in.add(app("com.tiny.app", 1, 0, "cached"));
        for (int i = 1; i <= 11; i++) in.add(app("sys.p" + i, 1000 - i, i == 10 ? 60 : (i == 11 ? 70 : 2), "sys"));
        List<com.clean.stickman.core.TaskSnapshot.App> out = TaskSampler.limitSys(in);
        List<String> names = new ArrayList<>();
        for (com.clean.stickman.core.TaskSnapshot.App a : out) names.add(a.pkg);
        yes(names.contains("org.videolan.vlc") && names.contains("com.tiny.app"), "launchable apps kept: " + names);
        for (int i = 1; i <= 8; i++) yes(names.contains("sys.p" + i), "largest sys kept: " + names);
        yes(!names.contains("sys.p9") && !names.contains("sys.p10") && !names.contains("sys.p11"), "rest folded: " + names);
        com.clean.stickman.core.TaskSnapshot.App o = out.get(out.size() - 1);
        String line = com.clean.stickman.core.ProcStats.formatTaskLine(o, 4);
        yes(line.equals("task _sys_other rss=" + (991 + 990 + 989) + " cpu=100 state=sys up=0 procs=3 seq=4"), line);
        yes(out.size() == 2 + 8 + 1, "count " + out.size());

        List<com.clean.stickman.core.TaskSnapshot.App> few = new ArrayList<>();
        for (int i = 1; i <= 8; i++) few.add(app("sys.q" + i, i, 0, "sys"));
        for (com.clean.stickman.core.TaskSnapshot.App a : TaskSampler.limitSys(few))
            yes(!a.pkg.equals("_sys_other"), "8 sys packages: nothing folded");
    }

    /** Fake shell for end / end_all: canned dumpsys + launchable output; records every command. */
    static class EndSrc extends FakeSrc {
        final List<String> cmds = new ArrayList<>();
        @Override public String sh(String cmd) {
            cmds.add(cmd);
            if (cmd.equals("dumpsys activity lru")) return "ACTIVITY MANAGER LRU PROCESSES (dumpsys activity lru)\n"
                + "  #9: fg     TOP  LCM 100:com.front.app/u0a1 act:activities\n"
                + "  #8: cch    CAC  --- 101:com.playing.app/u0a2 act:activities\n"
                + "  #7: cch    CAC  --- 102:com.white.app/u0a3 act:activities\n"
                + "  #6: cch    CAC  --- 103:com.amazon.service/u0a4 act:activities\n"
                + "  #5: cch    CAC  --- 104:com.clean.stickman/u0a5 act:activities\n"
                + "  #4: prev   LAST --- 105:com.wolf.firelauncher/u0a6 act:activities\n"
                + "  #3: cch    CAC  --- 106:org.videolan.vlc/u0a7 act:activities\n";
            if (cmd.equals("dumpsys media_session")) return "    package=com.playing.app\n"
                + "      state=PlaybackState {state=3, position=1, buffered position=0, speed=1.0}\n";
            if (cmd.contains("query-activities")) return "com.front.app/.A\ncom.playing.app/.A\ncom.white.app/.A\n"
                + "com.clean.stickman/.A\ncom.wolf.firelauncher/.A\norg.videolan.vlc/.StartActivity\n";
            if (cmd.startsWith("am force-stop")) return "KILLED\n";
            return "";
        }
        List<String> stops() {
            List<String> o = new ArrayList<>();
            for (String c : cmds) if (c.contains("force-stop")) o.add(c);
            return o;
        }
    }

    static TaskSampler endSampler(EndSrc src) {
        com.clean.stickman.core.StickdConf c = new com.clean.stickman.core.StickdConf();
        c.whitelist.add("com.white.app");
        return new TaskSampler(src, l -> { }, () -> "com.front.app", () -> c, () -> 0L);
    }

    /** end_all force-stops only what EndPolicy allows: fg, playing, protected, system, never all survive. */
    public void testEndAllOnlyAllowed() {
        EndSrc src = new EndSrc();
        int n = endSampler(src).endAll();
        yes(src.stops().equals(Collections.singletonList("am force-stop org.videolan.vlc && echo KILLED")), "stops: " + src.stops());
        yes(n == 1, "killed=" + n);
    }

    /** end_all counts confirmed kills only. */
    public void testEndAllCountsConfirmedKills() {
        EndSrc src = new EndSrc() {
            @Override public String sh(String cmd) { return cmd.startsWith("am force-stop") ? "" : super.sh(cmd); }
        };
        yes(endSampler(src).endAll() == 0, "unconfirmed force-stop is not counted");
    }

    /** end issues no force-stop when it refuses, one when allowed. */
    public void testEndRefusesWithoutKilling() {
        for (String pkg : new String[] {"com.front.app", "com.playing.app", "com.white.app", "com.amazon.service",
                "com.clean.stickman", "com.wolf.firelauncher", "x;reboot"}) {
            EndSrc src = new EndSrc();
            endSampler(src).end(pkg);
            yes(src.stops().isEmpty(), pkg + " refused but issued " + src.stops());
        }
        EndSrc src = new EndSrc();
        endSampler(src).end("org.videolan.vlc");
        yes(src.stops().equals(Collections.singletonList("am force-stop org.videolan.vlc")), "allowed: " + src.stops());
    }

    /** M5: logcat -G 1M only grows the main ring; bigger or unknown buffers are left alone. */
    public void testLogBufferOnlyGrows() {
        yes(TaskSampler.mainBufBelow1M("main: ring buffer is 256Kb (237Kb consumed), max entry is 5120b, max payload is 4068b\n"
            + "system: ring buffer is 256Kb (10Kb consumed)\n"), "256Kb: grow");
        yes(TaskSampler.mainBufBelow1M("main: ring buffer is 256 KiB (237 KiB consumed, 237 KiB readable), max entry is 5120 B"), "256 KiB (new format)");
        yes(!TaskSampler.mainBufBelow1M("main: ring buffer is 1Mb (900Kb consumed)"), "1Mb: leave");
        yes(!TaskSampler.mainBufBelow1M("main: ring buffer is 4 MiB (1 MiB consumed)"), "4 MiB: never shrink");
        yes(!TaskSampler.mainBufBelow1M("system: ring buffer is 64Kb (1Kb consumed)"), "no main line: leave");
        yes(!TaskSampler.mainBufBelow1M(""), "empty");
        yes(!TaskSampler.mainBufBelow1M(null), "null");
    }

    /** M6: end runs on the end/end_all executor, not on the caller's (spool) thread. */
    public void testEndRunsOffTheCallerThread() throws Exception {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final String[] thread = new String[1];
        EndSrc src = new EndSrc() {
            @Override public String sh(String cmd) {
                if (cmd.startsWith("am force-stop")) { thread[0] = Thread.currentThread().getName(); done.countDown(); }
                return super.sh(cmd);
            }
        };
        endSampler(src).endAsync("org.videolan.vlc");
        yes(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "end ran");
        yes("stickd-endall".equals(thread[0]), "ran on " + thread[0]);
        yes(!Thread.currentThread().getName().equals(thread[0]), "not the caller thread");
    }
}
