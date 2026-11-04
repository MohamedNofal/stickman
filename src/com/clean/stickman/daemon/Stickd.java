package com.clean.stickman.daemon;

import com.clean.stickman.core.Actions;
import com.clean.stickman.core.Commands;
import com.clean.stickman.core.EventParser;
import com.clean.stickman.core.RemapModel;
import com.clean.stickman.core.Spool;
import com.clean.stickman.core.StickdConf;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
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

    /** Home/launcher packages plus Stickman itself: never force-stopped. */
    public static final Set<String> NEVER_KILL = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "com.wolf.firelauncher", "com.amazon.tv.launcher", "com.amazon.firehomestarter", "com.clean.stickman")));

    private static final Pattern PKG = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");
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
        if (!other.isEmpty() && !other.equals(pid) && read(new File("/proc/" + other + "/cmdline")).contains("stickd")) {
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

        // The events buffer may hold no resume line yet: seed the foreground from dumpsys.
        String seed = EventParser.dumpsysResumed(Sh.run("dumpsys activity activities | grep -m1 mResumedActivity"));
        if (seed != null) onForeground(seed);

        Thread a = new Thread(this::followForeground, "stickd-fg");
        Thread b = new Thread(this::readSpool, "stickd-act");
        a.start(); b.start();
        String v = read(file("version")).trim();
        Sh.log("STATUS started version=" + (v.matches("\\d+") ? v : Integer.toString(VERSION)));
        a.join(); b.join();
    }

    void loadConfs() {
        conf = StickdConf.parse(read(file("stickd.conf")));
        profiles = RemapModel.parse(read(file("remap.conf"))).profiles();
    }

    // ---- thread A: foreground tracking ----
    private void followForeground() {
        while (true) {
            Process p = null;
            try {
                p = new ProcessBuilder("/system/bin/logcat", "-b", "events", "-T", "1", "-s", "wm_set_resumed_activity")
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

    synchronized void onForeground(String pkg) {
        String prev = fgPkg;
        if (pkg.equals(prev)) return;
        fgPkg = pkg;
        String p = applyProfile();
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
        drainSpool(d, System.currentTimeMillis() - STALE_ACT_AT_START_MS);
        while (true) {
            try { drainSpool(d, 0); } catch (Throwable t) { Sh.log("spool error: " + t); }
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
            if (!f.delete() && f.exists()) { Sh.log("spool: cannot delete " + n); continue; }
            if (dropOlderThan > 0 && mtime < dropOlderThan) { Sh.log("spool: dropped stale " + line); continue; }
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
        register(Actions.SCREEN_OFF, a -> Sh.run("am start -a com.amazon.tv.settings.action.SHOW_BLACK_CURTAIN"));
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
        register(Actions.PLAY_PAUSE, a -> Sh.run("cmd media_session dispatch play-pause"));
        register(Actions.NEXT, a -> Sh.run("cmd media_session dispatch next"));
        register(Actions.PREVIOUS, a -> Sh.run("cmd media_session dispatch previous"));
        register(Actions.KILL_CURRENT, a -> {
            String pkg = fgPkg;
            if (pkg == null || NEVER_KILL.contains(pkg) || !PKG.matcher(pkg).matches()) {
                Sh.log("kill_current skipped: " + pkg);
                return;
            }
            Sh.run("am force-stop " + pkg);
            Sh.log("kill_current " + pkg);
        });
        register(Actions.OPEN_SCREEN, a -> {
            if (!SCREEN_ARG.matcher(a).matches()) { Sh.log("open_screen bad arg: " + a); return; }
            Sh.run(a.indexOf('/') >= 0 ? "am start -n " + a : "am start -a " + a);
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
        try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
        catch (Exception e) { return ""; }
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
