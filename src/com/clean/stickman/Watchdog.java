package com.clean.stickman;

import android.content.Context;
import android.util.Log;

import com.clean.stickman.core.Liveness;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Restarts the daemons when they are gone (see core/Liveness for why they can vanish). Asks stickd on its loopback
 * port, off the main thread; no answer, or remapd down, re-runs the ensure script through the bridge as a background
 * run (so another connected VPN is never displaced). Cheap enough for every periodic job and Bluetooth reconnect.
 */
final class Watchdog {
    private Watchdog() {}

    private static volatile long lastAt;

    /** At most one watchdog-started ensure per this long: a probe that can never succeed must not keep taking the VPN. */
    static final long MIN_GAP_MS = 20 * 60_000L;

    /** done (may be null) always runs, when the check is over: callers hold their receiver / job alive until then. */
    static void check(Context ctx, final String why, final Runnable done) {
        final Context c = ctx.getApplicationContext();
        long now = System.currentTimeMillis();
        if (!Store.enabled(c) || Bridge.busy || now - lastAt < 20_000L || now - Store.lastEnsureAt(c) < 30_000L) {
            if (done != null) done.run();   // off, busy, just checked or just ensured
            return;
        }
        lastAt = now;
        Thread t = new Thread(() -> {
            try {
                String reply = ask();
                if (!Liveness.needsEnsure(reply)) return;
                // the user may have switched Stickman off, or opened it, while we were asking
                if (!Store.enabled(c) || Bridge.busy) return;
                long t2 = System.currentTimeMillis();
                long last = Store.watchdogAt(c);   // persisted: the limit must survive this process being reclaimed
                if (last != 0 && t2 >= last && t2 - last < MIN_GAP_MS) { Log.i("Stickman", "watchdog (" + why + "): still no answer, waiting before another try"); return; }
                Store.setWatchdogAt(c, t2);
                Log.i("Stickman", "watchdog (" + why + "): daemons not running (" + reply + ") -> ensuring");
                Bridge.run(c, Ensure.MARKER, true);
            } finally {
                if (done != null) done.run();
            }
        }, "stickman-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /** stickd's answer line, or null. Two tries: right after a wake-up the first connect can be slow. */
    static String ask() {
        for (int i = 0; i < 2; i++) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", Liveness.PORT), 1500);
                s.setSoTimeout(1500);
                byte[] b = new byte[64];   // one short line; never read without a bound
                int n = s.getInputStream().read(b);
                if (n > 0) return new String(b, 0, n, "US-ASCII").split("\n")[0].trim();
            } catch (Exception ignored) { }
            try { Thread.sleep(400); } catch (InterruptedException e) { return null; }
        }
        return null;
    }
}
