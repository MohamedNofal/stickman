package com.clean.stickman.daemon;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

/** In the daemon package to reach package-private AutoKill/Stickd; asserts by throwing (T is in the default package). */
public class AutoKillLockTest {
    static void yes(boolean c, String m) { if (!c) throw new AssertionError(m); }

    static Stickd daemon(String conf) throws Exception {
        File dir = Files.createTempDirectory("stickd").toFile();
        try (FileOutputStream o = new FileOutputStream(new File(dir, "stickd.conf"))) { o.write(conf.getBytes("UTF-8")); }
        Stickd d = new Stickd(dir.getPath());
        d.loadConfs();
        return d;
    }

    /** A boost/auto-kill in progress (kill lock held) must not delay the foreground thread. */
    public void testForegroundNotBlockedByKill() throws Exception {
        Stickd d = daemon("autokill.on_leave=1\n");
        AutoKill ak = new AutoKill(d);
        ak.install();
        Thread killer = new Thread(() -> {
            synchronized (ak.killLock) { try { Thread.sleep(3000); } catch (InterruptedException ignored) {} }
        });
        killer.start();
        Thread.sleep(200);
        long t0 = System.nanoTime();
        d.onForeground("a.b");
        d.onForeground("c.d");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        killer.interrupt();
        yes(ms < 1000, "onForeground blocked " + ms + " ms behind the kill lock");
    }

    /** Listeners run outside Stickd's monitor: a slow listener must not block reload/applyProfile. */
    public void testListenersRunOutsideStickdLock() throws Exception {
        Stickd d = daemon("");
        d.addFgListener((pkg, prev) -> { try { Thread.sleep(3000); } catch (InterruptedException ignored) {} });
        Thread fg = new Thread(() -> d.onForeground("a.b"));
        fg.start();
        Thread.sleep(300);
        long t0 = System.nanoTime();
        d.applyProfile();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        fg.interrupt();
        yes(ms < 1000, "applyProfile blocked " + ms + " ms behind a listener");
    }

    /** Always-kill works without global auto-kill: a non-empty blacklist alone arms the on-leave timer. */
    public void testBlacklistOnlyRunsWithoutOnLeave() throws Exception {
        Stickd d = daemon("autokill.on_leave=0\nautokill.blacklist=com.x\n");
        yes(!d.conf().onLeave && d.conf().blacklist.contains("com.x"), "conf parsed: " + d.conf().serialize());
        AutoKill ak = new AutoKill(d);
        ak.install();
        d.onForeground("a.b");
        yes(ak.leavePending(), "blacklist-only: leave timer scheduled");

        Stickd e = daemon("autokill.on_leave=0\n");
        AutoKill ak2 = new AutoKill(e);
        ak2.install();
        e.onForeground("a.b");
        yes(!ak2.leavePending(), "nothing to do: no leave timer");
    }
}
