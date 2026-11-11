package com.clean.stickman.daemon;

import com.clean.stickman.core.Actions;
import com.clean.stickman.core.Commands;
import com.clean.stickman.core.EventParser;
import com.clean.stickman.core.KillPolicy;
import com.clean.stickman.core.Originals;
import com.clean.stickman.core.RemapModel;
import com.clean.stickman.core.Spool;
import com.clean.stickman.core.StickdConf;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * stickd: automation daemon run as shell via
 * {@code CLASSPATH=<apk> app_process /system/bin --nice-name=stickd com.clean.stickman.daemon.Stickd <dir>}.
 * Plain Java only (no android.*): there is no Context under app_process.
 */
public final class Stickd {
    public static final int VERSION = 1;

    /** One spooled action (act/*.act). {@code arg} is "" when the line has none. */
    public interface Handler { void run(String arg); }

    /** Foreground-change hook for later features (auto-kill, stay-awake...). */
    public interface FgListener { void onForeground(String pkg, String prevPkg); }

    private static final Pattern SCREEN_ARG = Pattern.compile("[A-Za-z0-9_.$/]+");

    public final String dir;
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final List<FgListener> fgListeners = new CopyOnWriteArrayList<>();
    private volatile StickdConf conf = new StickdConf();
    private volatile List<String> profiles = Collections.singletonList(RemapModel.DEFAULT);
    private volatile String fgPkg;
    private volatile String activeProfile;

    public Stickd(String dir) { this.dir = dir; }

    public void register(String id, Handler h) { handlers.put(id, h); }
    public void addFgListener(FgListener l) { fgListeners.add(l); }
    public StickdConf conf() { return conf; }
    public String foreground() { return fgPkg; }
    public File file(String name) { return new File(dir, name); }

    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : "/data/local/tmp/stickman";
        Stickd d = new Stickd(dir);
        try {
            d.start();
        } catch (Throwable t) {
            Sh.log("fatal: " + t);
            System.exit(1);
        }
    }

    void start() throws Exception {
        new File(dir).mkdirs();
        String pid = new File("/proc/self").getCanonicalFile().getName();
        String other = read(file("stickd.pid")).trim();
        // Exact match (comm or arg0), like the ensure script: a substring test hits the ensure shell's own forks,
        // whose cmdline is the whole script, when a stale pid from the previous boot is reused by one of them.
        if (!other.isEmpty() && !other.equals(pid) && isProc(other, "stickd")) {
            Sh.log("already running pid=" + other + "; exiting");
            return;
        }
        writeAtomic(file("stickd.pid"), pid + "\n");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (pid.equals(read(file("stickd.pid")).trim())) file("stickd.pid").delete();
        }));
        file("act").mkdirs();
        activeProfile = read(file("profile")).trim();
        loadConfs();
        registerCore();
        // Later tasks register their handlers / fg listeners here.
        new AutoKill(this).install();
        new Sleep(this).install();
        new TaskSampler(this).install(this);
        new Updater(this).install();

        // The events buffer may hold no resume line yet: seed the foreground from dumpsys.
        String seed = EventParser.dumpsysResumed(Sh.run("dumpsys activity activities | grep -m1 mResumedActivity"));
        if (seed != null) onForeground(seed);

        Thread w = new Thread(this::watchApk, "stickd-watchdog");
        w.setDaemon(true);
        w.start();
        Thread a = new Thread(this::followForeground, "stickd-fg");
        Thread b = new Thread(this::readSpool, "stickd-act");
        a.start(); b.start();
        String v = read(file("version")).trim();
        Sh.log("STATUS started version=" + (v.matches("\\d+") ? v : Integer.toString(VERSION)));
        a.join(); b.join();
    }

    /** /proc/PID is exactly NAME: comm equals it, or the first NUL-separated cmdline field does. */
    static boolean isProc(String pid, String name) {
        if (pid == null || !pid.matches("\\d+")) return false;
        return procIs(read(new File("/proc/" + pid + "/comm")), read(new File("/proc/" + pid + "/cmdline")), name);
    }

    /** Pure part of {@link #isProc}: comm (may end in a newline) or arg0 of a NUL-separated cmdline equals name. */
    public static boolean procIs(String comm, String cmdline, String name) {
        if (comm != null && comm.trim().equals(name)) return true;
        if (cmdline == null) return false;
        int z = cmdline.indexOf('\0');
        return (z < 0 ? cmdline : cmdline.substring(0, z)).equals(name);
    }

    // ---- uninstall watchdog ----
    static final long WATCHDOG_MS = 60_000;
    static final long WATCHDOG_RECHECK_MS = 5_000;
    static final long WATCHDOG_CONFIRM_MS = 25_000;

    /** True when the APK stickd was started from is gone and package manager no longer knows Stickman. */
    boolean apkGone() {
        String apk = read(file("apk")).trim();
        if (!apk.isEmpty() && new File(apk).exists()) return false;
        String now = pmPath(Sh.run("pm path " + com.clean.stickman.core.EnsureScript.PKG + " 2>/dev/null"));
        if (now == null) return true;
        if (!now.equals(apk)) Sh.log("watchdog: Stickman APK replaced (" + now + "); waiting for the app to re-run ensure");
        return false;
    }

    /** The path from {@code pm path} output ("package:/data/app/.../base.apk"), or null when there is none. */
    public static String pmPath(String out) {
        if (out == null) return null;
        for (String l : out.split("\n")) {
            l = l.trim();
            if (l.startsWith("package:") && l.length() > 8) return l.substring(8).trim();
        }
        return null;
    }

    /** Every 60 s: if Stickman was uninstalled (confirmed again after 5 s and 30 s), stop both daemons for good. */
    private void watchApk() {
        while (true) {
            sleep(WATCHDOG_MS);
            try {
                if (!apkGone()) continue;
                sleep(WATCHDOG_RECHECK_MS);  // pm can be briefly blind during an install
                if (!apkGone()) continue;
                sleep(WATCHDOG_CONFIRM_MS);
                if (!apkGone()) continue;
                shutdownForUninstall();
            } catch (Throwable t) {
                Sh.log("watchdog error: " + t);
            }
        }
    }

    /** Uninstalled: kill remapd (exact name), release stay-on + fade, remove both pid files, exit. */
    void shutdownForUninstall() {
        Sh.log("watchdog: Stickman is uninstalled; stopping remapd and stickd");
        String rp = read(file("remapd.pid")).trim();
        if (isProc(rp, "remapd")) Sh.run("kill " + rp);
        file("remapd.pid").delete();
        Sh.run(com.clean.stickman.core.EnsureScript.RELEASE_STAYON_FADE + "true");
        file("stickd.pid").delete();
        System.exit(0);
    }

    void loadConfs() {
        conf = StickdConf.parse(read(file("stickd.conf")));
        profiles = RemapModel.parse(read(file("remap.conf"))).profiles();
    }

    // ---- thread A: foreground tracking ----
    /**
     * Shell: kill logcat followers a previous stickd left behind. stickd is stopped with SIGTERM (no shutdown hook
     * under app_process), so its children are re-parented to init and only die at their next write.
     */
    static final String REAP_FOLLOWERS =
        "for P in $(pidof logcat); do [ \"$(awk '{print $4}' /proc/$P/stat 2>/dev/null)\" = 1 ] "
        + "&& grep -qE 'set_resumed_activity|RemapD:I' /proc/$P/cmdline 2>/dev/null && kill $P; done; true";

    private void followForeground() {
        Sh.run(REAP_FOLLOWERS);
        while (true) {
            Process p = null;
            try {
                p = new ProcessBuilder("/system/bin/logcat", "-b", "events", "-T", "1", "-s",
                    EventParser.RESUMED_TAGS[0], EventParser.RESUMED_TAGS[1], EventParser.RESUMED_TAGS[2])
                    .redirectErrorStream(true).start();
                p.getOutputStream().close();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    String pkg = EventParser.resumedPackage(line);
                    if (pkg != null) onForeground(pkg);
                }
            } catch (Exception e) {
                Sh.log("logcat follow error: " + e);
            } finally {
                if (p != null) p.destroy();
            }
            sleep(2000);  // logcat died; restart it
        }
    }

    /** Only the state update + profile write happen under the Stickd lock; listeners run after it is released. */
    void onForeground(String pkg) {
        String prev, p;
        synchronized (this) {
            prev = fgPkg;
            if (pkg.equals(prev)) return;
            fgPkg = pkg;
            p = applyProfile();
        }
        Sh.log("STATUS fg " + pkg + " profile=" + p);
        for (FgListener l : fgListeners) {
            try { l.onForeground(pkg, prev); } catch (Throwable t) { Sh.log("fg listener error: " + t); }
        }
    }

    /** Selects the profile for the current fg pkg and writes {@code profile} if it changed. */
    synchronized String applyProfile() {
        String p = EventParser.selectProfile(fgPkg, profiles);
        if (!p.equals(activeProfile)) {
            try {
                writeAtomic(file("profile"), p + "\n");
                activeProfile = p;
            } catch (Exception e) {
                Sh.log("profile write error: " + e);
            }
        }
        return p;
    }

    // ---- thread B: action dispatch (spool dir act/, see core.Spool) ----
    static final long POLL_MS = 100;
    static final long STALE_TMP_MS = 10_000;
    static final long STALE_ACT_AT_START_MS = 30_000;

    private void readSpool() {
        File d = file("act");
        // Actions queued long before stickd started (e.g. a sleep_now while it was down) are dropped, not replayed.
        long dropBefore = System.currentTimeMillis() - STALE_ACT_AT_START_MS;
        while (true) {
            try {
                drainSpool(d, dropBefore);
                dropBefore = 0;  // only the startup drain drops stale actions
            } catch (Throwable t) {
                Sh.log("spool error: " + t);
            }
            sleep(POLL_MS);
        }
    }

    private void drainSpool(File d, long dropOlderThan) {
        String[] names = d.list();
        if (names == null) { d.mkdirs(); return; }
        long now = System.currentTimeMillis();
        for (String n : names) {
            if (!n.endsWith(Spool.TMP)) continue;
            File f = new File(d, n);
            if (now - f.lastModified() > STALE_TMP_MS) f.delete();  // crashed writer
        }
        for (String n : Spool.pending(names)) {
            File f = new File(d, n);
            String line = Spool.parse(read(f));
            long mtime = f.lastModified();
            // Only the reader that deleted the file runs it (two stickds must never both dispatch one action).
            if (!f.delete()) { if (f.exists()) Sh.log("spool: cannot delete " + n); continue; }
            if (dropOlderThan > 0 && mtime < dropOlderThan && !Spool.keepWhenStale(line)) {
                Sh.log("spool: dropped stale " + line);
                continue;
            }
            dispatch(line);
        }
    }

    void dispatch(String line) {
        String l = line.trim();
        if (l.isEmpty()) return;
        int sp = l.indexOf(' ');
        String id = sp < 0 ? l : l.substring(0, sp);
        String arg = sp < 0 ? "" : l.substring(sp + 1).trim();
        Handler h = handlers.get(id);
        if (h == null) { Sh.log("unknown action: " + id); return; }
        try { h.run(arg); } catch (Throwable t) { Sh.log("action " + id + " error: " + t); }
    }

    private void registerCore() {
        register(Actions.SCREENSAVER_TOGGLE, a -> {
            boolean on = !"0".equals(Sh.run("settings get secure screensaver_enabled").trim());
            Sh.run(Commands.screensaver(!on));
            Sh.log("screensaver " + (on ? "off" : "on"));
        });
        register(Actions.SCREENSHOT, a -> {
            String path = "/sdcard/Pictures/Stickman/shot_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
            Sh.run("mkdir -p /sdcard/Pictures/Stickman && screencap -p " + path);
            Sh.log("screenshot " + path);
        });
        // remapd owns the remote and the pointer; a button rule reaches it directly, the app's own switch comes this way
        register(Actions.MOUSE_TOGGLE, a -> Sh.run("echo toggle > " + com.clean.stickman.core.EnsureScript.DIR + "/mouse.cmd.tmp && mv "
            + com.clean.stickman.core.EnsureScript.DIR + "/mouse.cmd.tmp " + com.clean.stickman.core.EnsureScript.DIR + "/mouse.cmd"));
        register(Actions.PLAY_PAUSE, a -> Sh.run(Commands.media("play-pause")));
        register(Actions.NEXT, a -> Sh.run(Commands.media("next")));
        register(Actions.PREVIOUS, a -> Sh.run(Commands.media("previous")));
        register(Actions.KILL_CURRENT, a -> {
            String pkg = fgPkg;
            if (KillPolicy.isNeverKill(pkg)) {
                Sh.log("kill_current skipped: " + pkg);
                return;
            }
            Sh.run("am force-stop " + pkg);
            Sh.log("kill_current " + pkg);
        });
        register(Actions.OPEN_SCREEN, a -> {
            if (!SCREEN_ARG.matcher(a).matches()) { Sh.log("open_screen bad arg: " + a); return; }
            Sh.run((a.indexOf('/') >= 0 ? "am start -n " : "am start -a ") + Originals.q(a));
        });
        register(Actions.SLEEP_NOW, a -> Sh.run("input keyevent KEYCODE_SLEEP"));
        register(Actions.RELOAD, a -> {
            loadConfs();
            String p = applyProfile();
            Sh.log("reloaded profile=" + p);
        });
    }

    // ---- file helpers ----
    static String read(File f) {
        // java.nio.file is Android 8+; plain streams work back to Fire OS 5
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }

    static void writeAtomic(File f, String text) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(text.getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(f)) throw new Exception("rename failed: " + f);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
