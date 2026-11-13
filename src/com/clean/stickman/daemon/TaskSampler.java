package com.clean.stickman.daemon;

import com.clean.stickman.core.Actions;
import com.clean.stickman.core.EndPolicy;
import com.clean.stickman.core.KillPolicy;
import com.clean.stickman.core.LruParser;
import com.clean.stickman.core.MediaState;
import com.clean.stickman.core.ProcStats;
import com.clean.stickman.core.StickdConf;
import com.clean.stickman.core.TaskSnapshot;
import com.clean.stickman.core.cmd.DaemonCmds;
import com.clean.stickman.core.cmd.TaskCmds;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tasks page backend: while the page keeps sending {@code tasks_start}, samples /proc every 2 s and logs the
 * {@code STATUS tasks / task / tasks_end} feed; also handles {@code end <pkg>} and {@code end_all}.
 * Plain Java (no android.*).
 */
final class TaskSampler {
    static final long SAMPLE_MS = 2000;
    static final long INPUTS_MS = 10_000;
    static final long KEEPALIVE_MS = 90_000;
    static final long CLK_TCK = 100;

    /** Where samples come from: /proc reads (null when unreadable), the /proc pid list, and shell commands. */
    interface Src {
        String read(String path);
        String[] pids();
        String sh(String cmd);
    }

    /** Receives one sample's feed lines (each starting "STATUS "). */
    interface Out { void emit(List<String> lines); }

    interface Str { String get(); }
    interface Conf { StickdConf get(); }
    interface Clock { long now(); }

    private static final Pattern MEM_TOTAL = Pattern.compile("MemTotal:\\s+(\\d+)");
    private static final Pattern MEM_AVAIL = Pattern.compile("MemAvailable:\\s+(\\d+)");

    private final Src src;
    private final Out out;
    private final Str fg;
    private final Conf conf;
    private final Clock clock;

    // session state (guarded by this)
    private int gen;
    private boolean active;
    private volatile long lastKeepalive;
    private long seq;
    private long prevTotal = -1, prevIdle = -1;
    private Map<String, Long> prevTicks;
    // 10 s cached inputs (sampler thread, under this)
    private long inputsAt = Long.MIN_VALUE;
    private Set<String> playing = Collections.emptySet(), cached = Collections.emptySet(),
        launchable = Collections.emptySet();
    // fixed per device, read once
    private int ncpu = -1, pageKb = -1;

    /** end_all runs here, one at a time, off the spool thread. */
    private final java.util.concurrent.ExecutorService endAllExec =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "stickd-endall");
            t.setDaemon(true);
            return t;
        });

    TaskSampler(Src src, Out out, Str fg, Conf conf, Clock clock) {
        this.src = src;
        this.out = out;
        this.fg = fg;
        this.conf = conf;
        this.clock = clock;
    }

    /** Real sampler for stickd: /proc via java.io, shell via Sh, lines to logcat. */
    TaskSampler(Stickd d) {
        this(new ProcSrc(), Sh::logLines, d::foreground, d::conf, () -> System.nanoTime() / 1_000_000);  // monotonic
    }

    void install(Stickd d) {
        d.register(Actions.TASKS_START, a -> start());
        d.register(Actions.TASKS_STOP, a -> stop());
        // end runs dumpsys media_session + the launchable query (1-2 s on the stick): off the spool thread too
        d.register(Actions.END, this::endAsync);
        // A few force-stops plus dumpsys calls: off the spool thread so other actions are not delayed.
        d.register(Actions.END_ALL, a -> endAllExec.execute(this::endAllSafe));
    }

    /** Sampling stops when more than 90 s passed since the last keepalive. */
    static boolean shouldStop(long lastKeepaliveMs, long nowMs) { return nowMs - lastKeepaliveMs > KEEPALIVE_MS; }

    void keepalive() { lastKeepalive = clock.now(); }

    // ---- session control ----

    /** tasks_start: records the keepalive; starts a new session (seq from 1) when idle. */
    synchronized void start() {
        keepalive();
        if (active) return;
        active = true;
        final int g = ++gen;
        resetSession();
        Thread t = new Thread(() -> loop(g), "stickd-tasks");
        t.setDaemon(true);
        t.start();
        Sh.log("tasks sampling started");
    }

    /** tasks_stop. */
    void stop() {
        synchronized (this) {
            if (!active) return;
            active = false;
            gen++;
        }
        Sh.log("STATUS tasks stopped");
    }

    private void resetSession() {
        pidPkg.clear();
        seq = 0;
        prevTotal = prevIdle = -1;
        prevTicks = null;
    }

    /** logcat -G 1M was tried by this daemon (once per lifetime, best effort). */
    private static final java.util.concurrent.atomic.AtomicBoolean logBufGrown = new java.util.concurrent.atomic.AtomicBoolean();

    private void loop(int g) {
        // R7: a bigger main ring so the feed does not evict other STATUS lines as fast. Failures are ignored.
        // Only ever grows it: a device whose buffer is already >= 1M (or unparsable output) is left alone.
        if (logBufGrown.compareAndSet(false, true) && mainBufBelow1M(src.sh(TaskCmds.logBufGet())))
            src.sh(TaskCmds.logBufGrow());
        while (true) {
            long t0 = clock.now();
            int r;
            try {
                r = tick(g);
            } catch (Throwable t) {
                Sh.log("tasks sample error: " + t);
                r = 1;
            }
            if (r < 0) return;          // superseded by stop / a newer session
            if (r == 0) {               // keepalive expired
                synchronized (this) {
                    if (g != gen) return;
                    active = false;
                    gen++;
                }
                Sh.log("STATUS tasks stopped");
                return;
            }
            long wait = SAMPLE_MS - (clock.now() - t0);
            try { Thread.sleep(Math.max(100, wait)); } catch (InterruptedException ignored) {}
        }
    }

    /** One sample of the current session; false when the keepalive expired. */
    boolean tick() {
        int g;
        synchronized (this) { g = gen; }
        return tick(g) > 0;
    }

    /**
     * 1 = sampled, 0 = keepalive expired, -1 = session {@code g} is no longer current. The dumpsys refresh (up to
     * seconds) runs outside the lock so tasks_start / tasks_stop on the spool thread never wait for it.
     */
    private int tick(int g) {
        boolean refresh;
        synchronized (this) {
            if (g != gen) return -1;
            long now = clock.now();
            if (shouldStop(lastKeepalive, now)) return 0;
            refresh = inputsAt == Long.MIN_VALUE || now - inputsAt >= INPUTS_MS;
            if (refresh) inputsAt = now;
        }
        if (refresh) {
            Set<String> pl = MediaState.playingPackages(src.sh(DaemonCmds.mediaSessions()));
            Set<String> ca = cachedPackages(LruParser.read(src::sh));
            Set<String> la = KillPolicy.parseLaunchable(src.sh(KillPolicy.LAUNCHABLE_CMD));
            synchronized (this) { playing = pl; cached = ca; launchable = la; }
        }
        synchronized (this) {
            if (g != gen) return -1;
            out.emit(sample());
            return 1;
        }
    }

    /** Packages whose every lru process is cached (adj cch* or state CEM/CAC). */
    static Set<String> cachedPackages(List<LruParser.Proc> procs) {
        Map<String, Boolean> all = new HashMap<>();
        for (LruParser.Proc p : procs) {
            if (p.pkg == null) continue;
            boolean c = p.adj.startsWith("cch") || "CEM".equals(p.state) || "CAC".equals(p.state);
            Boolean prev = all.get(p.pkg);
            all.put(p.pkg, prev == null ? c : (prev && c));
        }
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, Boolean> e : all.entrySet()) if (e.getValue()) out.add(e.getKey());
        return out;
    }

    /** Reads /proc and formats one sample (seq incremented). */
    private List<String> sample() {
        if (ncpu < 0) ncpu = countCpus(src.read("/proc/cpuinfo"));
        if (pageKb < 0) {
            String ps = src.sh(TaskCmds.pageSize()).trim();
            long b = ps.matches("\\d+") ? Long.parseLong(ps) : 4096;
            pageKb = (int) Math.max(1, b / 1024);
        }
        long seqNow = ++seq;

        // totals
        long total = 0, idle = 0;
        String stat = src.read("/proc/stat");
        if (stat != null) {
            int nl = stat.indexOf('\n');
            String cpu = nl < 0 ? stat : stat.substring(0, nl);
            String[] f = cpu.trim().split("\\s+");
            total = ProcStats.totalJiffies(cpu);
            idle = (f.length > 4 ? Long.parseLong(f[4]) : 0) + (f.length > 5 ? Long.parseLong(f[5]) : 0);
        }
        long dTotal = prevTotal < 0 ? 0 : total - prevTotal;
        int cpuPct = prevTotal < 0 ? 0 : ProcStats.pct(dTotal - (idle - prevIdle), dTotal, ncpu);
        prevTotal = total;
        prevIdle = idle;
        String mem = src.read("/proc/meminfo");
        long memTotal = kb(MEM_TOTAL, mem), memAvail = kb(MEM_AVAIL, mem);
        long nowTicks = 0;
        String up = src.read("/proc/uptime");
        if (up != null) {
            try { nowTicks = (long) (Double.parseDouble(up.trim().split("\\s+")[0]) * CLK_TCK); }
            catch (RuntimeException ignored) {}
        }

        // processes
        List<ProcStats.Proc> procs = new ArrayList<>();
        String[] pids = src.pids();
        Set<String> live = new HashSet<>();
        if (pids != null) {
            for (String pid : pids) {
                if (!isDigits(pid)) continue;
                String pkg = pkgOfPid(pid, live);
                if (pkg == null || EndPolicy.SELF.equals(pkg) || OWN_DAEMONS.contains(pkg)) continue;
                ProcStats.Proc p = new ProcStats.Proc();
                p.pid = Integer.parseInt(pid);
                p.pkg = pkg;
                try {
                    String sm = src.read("/proc/" + pid + "/statm");
                    String st = src.read("/proc/" + pid + "/stat");
                    if (sm == null || st == null) throw new IllegalStateException();
                    p.rssKb = ProcStats.rssKbFromStatm(sm, pageKb);
                    long[] c = ProcStats.cpuFromStat(st);
                    p.ticks = c[0];
                    p.startTicks = c[1];
                } catch (RuntimeException e) {
                    p.rssKb = -1;                                       // vanished mid-read
                }
                procs.add(p);
            }
        }
        pidPkg.keySet().retainAll(live);  // forget exited pids (so a reused pid is read afresh)
        List<TaskSnapshot.App> apps = ProcStats.aggregate(procs, prevTicks, dTotal, ncpu, nowTicks, CLK_TCK,
            fg.get(), playing, cached, launchable);
        Map<String, Long> ticks = new HashMap<>();
        for (ProcStats.Proc p : procs) {
            if (p.rssKb < 0) continue;
            Long t = ticks.get(p.pkg);
            ticks.put(p.pkg, (t == null ? 0 : t) + p.ticks);
        }
        prevTicks = ticks;

        List<String> lines = new ArrayList<>(apps.size() + 2);
        lines.add("STATUS " + ProcStats.formatTotals(memTotal, memAvail, cpuPct, seqNow));
        for (TaskSnapshot.App a : limitSys(apps)) lines.add("STATUS " + ProcStats.formatTaskLine(a, seqNow));
        lines.add("STATUS " + ProcStats.formatEnd(seqNow));
        return lines;
    }

    /** pid -> package ("" = not a package process: kernel thread or native binary). Sampler thread only. */
    private final Map<String, String> pidPkg = new HashMap<>();

    /**
     * Package of a pid from its cmdline, cached so steady-state samples skip most cmdline reads. Zygote forks that
     * are not yet specialised ("zygote64", "&lt;pre-initialized&gt;", usap...) are never cached: they become apps.
     */
    private String pkgOfPid(String pid, Set<String> live) {
        String c = pidPkg.get(pid);
        if (c != null) {
            live.add(pid);
            return c.isEmpty() ? null : c;
        }
        String cmd = src.read("/proc/" + pid + "/cmdline");
        if (cmd == null) return null;                    // gone
        String pkg = ProcStats.pkgOf(cmd);
        boolean settled = !(cmd.contains("zygote") || cmd.startsWith("<") || cmd.startsWith("usap"));
        if (settled) {
            pidPkg.put(pid, pkg == null ? "" : pkg);     // empty cmdline = kernel thread: skipped for good
            live.add(pid);
        }
        return pkg;
    }

    /** Stickman's own daemons (process names) never appear in the feed. */
    static final Set<String> OWN_DAEMONS = new HashSet<>(java.util.Arrays.asList("stickd", "remapd"));
    /** R7: sys packages listed individually (largest by rss); the rest fold into one {@link #SYS_OTHER} line. */
    static final int SYS_SHOWN = 8;
    static final String SYS_OTHER = "_sys_other";

    /**
     * Every non-sys app, plus the {@value #SYS_SHOWN} largest sys packages by rss; the remaining sys packages become
     * one {@code _sys_other} entry (rss and cpu summed, cpu capped at 100, procs = packages folded, up=0), appended
     * last and omitted when nothing was folded. Keeps the logcat feed small (controller ruling R7).
     */
    static List<TaskSnapshot.App> limitSys(List<TaskSnapshot.App> apps) {
        List<TaskSnapshot.App> sys = new ArrayList<>(), out = new ArrayList<>();
        for (TaskSnapshot.App a : apps) {
            if ("sys".equals(a.state)) sys.add(a); else out.add(a);
        }
        Collections.sort(sys, TaskSnapshot.App.BY_RSS);
        long rss = 0;
        int cpu = 0, n = 0;
        for (int i = 0; i < sys.size(); i++) {
            TaskSnapshot.App a = sys.get(i);
            if (i < SYS_SHOWN) { out.add(a); continue; }
            rss += a.rssKb;
            cpu += a.cpuPct;
            n++;
        }
        Collections.sort(out, TaskSnapshot.App.BY_RSS);
        if (n > 0) out.add(new TaskSnapshot.App(SYS_OTHER, rss, Math.min(100, cpu), "sys", 0, n));
        return out;
    }

    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }

    private static long kb(Pattern p, String text) {
        if (text == null) return 0;
        Matcher m = p.matcher(text);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    private static final java.util.regex.Pattern RING =
        java.util.regex.Pattern.compile("main: ring buffer is (\\d+)\\s*([KMG]?)i?B", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** True if `logcat -g` output shows the main ring below 1 MiB; false when it is >= 1 MiB or can't be parsed. */
    static boolean mainBufBelow1M(String g) {
        if (g == null) return false;
        java.util.regex.Matcher m = RING.matcher(g);
        if (!m.find()) return false;
        long n = Long.parseLong(m.group(1));
        String u = m.group(2).toUpperCase(java.util.Locale.ROOT);
        long bytes = u.equals("K") ? n << 10 : u.equals("M") ? n << 20 : u.equals("G") ? n << 30 : n;
        return bytes < (1L << 20);
    }

    static int countCpus(String cpuinfo) {
        int n = 0;
        if (cpuinfo != null) for (String l : cpuinfo.split("\n")) if (l.startsWith("processor")) n++;
        return n > 0 ? n : 4;
    }

    // ---- end / end_all ----

    private StickdConf conf() {
        StickdConf c = conf.get();
        return c != null ? c : new StickdConf();
    }

    /** end &lt;pkg&gt;: fresh fg / media / launchable, then EndPolicy; force-stop only when allowed. */
    void end(String arg) {
        String pkg = arg == null ? "" : arg.trim();
        boolean force = pkg.endsWith(" force");   // the user confirmed ending an app Stickman normally spares
        if (force) pkg = pkg.substring(0, pkg.length() - 6).trim();
        Set<String> media = MediaState.protectedPackages(src.sh(DaemonCmds.mediaSessions()));
        Set<String> launch = KillPolicy.parseLaunchable(src.sh(KillPolicy.LAUNCHABLE_CMD));
        String why = EndPolicy.check(pkg, fg.get(), media, conf().whitelist, launch, force);
        if (EndPolicy.GUARDED.equals(why)) why = KillPolicy.isNeverKill(pkg) ? "never" : "system";
        // a forced end of something without a launcher entry: only a real package can be force-stopped
        // (tiles like "android.process.media" are process names shared by several packages)
        if (why == null && force && !launch.contains(pkg) && !src.sh(TaskCmds.endPmPath(pkg)).contains("package:")) why = "system";
        String shown = pkg.matches("[A-Za-z0-9_.]+") ? pkg : "?";
        if (why != null) {
            Sh.log("STATUS end " + shown + " refused=" + why);
            return;
        }
        src.sh(TaskCmds.end(pkg));
        invalidateInputs();
        Sh.log("STATUS end " + pkg + " ok");
    }

    /** end &lt;pkg&gt; on the end/end_all executor, so spooled button actions are not held up behind it. */
    void endAsync(final String arg) {
        endAllExec.execute(() -> {
            try { end(arg); }
            catch (Throwable t) { Sh.log("end error: " + t); }
        });
    }

    private void endAllSafe() {
        try { Sh.log("STATUS end_all killed=" + endAll()); }
        catch (Throwable t) { Sh.log("end_all error: " + t); }
    }

    /** end_all: every running launchable app EndPolicy allows (not fg / playing / protected), force-stopped. */
    int endAll() {
        Set<String> media = MediaState.protectedPackages(src.sh(DaemonCmds.mediaSessions()));
        Set<String> launch = KillPolicy.parseLaunchable(src.sh(KillPolicy.LAUNCHABLE_CMD));
        Set<String> running = new LinkedHashSet<>();
        java.util.List<LruParser.Proc> procs = LruParser.read(src::sh);
        for (LruParser.Proc p : procs) if (p.pkg != null) running.add(p.pkg);
        // an app with a visible window or a foreground service (a download, a VPN) is doing something: it stays
        Set<String> busy = KillPolicy.busyPackages(procs);
        Set<String> white = conf().whitelist;
        int n = 0;
        for (String pkg : running) {
            if (busy.contains(pkg)) continue;
            if (EndPolicy.check(pkg, fg.get(), media, white, launch) != null) continue;  // fg re-read per app
            if (src.sh(TaskCmds.endAllOne(pkg)).contains("KILLED")) n++;  // confirmed kills only
        }
        invalidateInputs();
        return n;
    }

    private synchronized void invalidateInputs() { inputsAt = Long.MIN_VALUE; }

    // ---- real /proc source ----

    static final class ProcSrc implements Src {
        private byte[] buf = new byte[8192];

        @Override public String read(String path) {
            try (FileInputStream in = new FileInputStream(path)) {
                int n = 0, r;
                while ((r = in.read(buf, n, buf.length - n)) > 0) {
                    n += r;
                    if (n == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                }
                return new String(buf, 0, n, StandardCharsets.ISO_8859_1);
            } catch (Exception e) {
                return null;
            }
        }

        @Override public String[] pids() { return new File("/proc").list(); }

        @Override public String sh(String cmd) { return Sh.run(cmd); }
    }
}
