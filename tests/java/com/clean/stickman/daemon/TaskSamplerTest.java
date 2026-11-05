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
        public String[] pids() { return new String[] {"10", "11", "12", "13", "14", "self"}; }
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
}
