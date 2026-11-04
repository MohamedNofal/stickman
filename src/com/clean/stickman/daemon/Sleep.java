package com.clean.stickman.daemon;

import com.clean.stickman.core.Actions;
import com.clean.stickman.core.DnsGuard;
import com.clean.stickman.core.MediaState;
import com.clean.stickman.core.SleepTimer;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Sleep timer (+ fade overlay broadcasts), media stay-awake and the private-DNS guard. Plain Java (no android.*). */
final class Sleep {
    static final long STAY_AWAKE_TICK_S = 30;
    /** Marker: Stickman turned "svc power stayon" on (survives a stickd crash so the next start restores it). */
    static final String STAYON_MARKER = "stayon.owned";

    private final Stickd d;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stickd-sleep");
        t.setDaemon(true);
        return t;
    });
    /** Probes block up to ~10 s: keep them off the sleep/stay-awake thread. */
    private final ScheduledExecutorService dnsTimer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stickd-dns");
        t.setDaemon(true);
        return t;
    });
    // Sleep-timer state; guarded by this.
    private int minutes;
    private long endAt;
    private ScheduledFuture<?> fadeTask, endTask;
    private boolean fading;
    // DNS guard; guarded by this.
    private ScheduledFuture<?> dnsTask;
    private int dnsGen;

    Sleep(Stickd d) { this.d = d; }

    void install() {
        d.register(Actions.SLEEP_TIMER, a -> {
            int cur;
            synchronized (this) { cur = minutes; }
            set(SleepTimer.next(cur));
        });
        d.register(Actions.SLEEP_TIMER_SET, a -> {
            int m = SleepTimer.parseMinutes(a);
            if (m < 0) { Sh.log("sleep_timer_set bad arg: " + a); return; }
            set(m);
        });
        d.register(Actions.DNS_CHECK, a -> dnsCheck());
        // A previous stickd may have died while owning stayon: give it back.
        if (d.file(STAYON_MARKER).exists()) stayOn(false);
        timer.scheduleWithFixedDelay(this::stayAwakeTick, STAY_AWAKE_TICK_S, STAY_AWAKE_TICK_S, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (d.file(STAYON_MARKER).exists()) stayOn(false);
        }));
    }

    // ---- sleep timer ----
    synchronized void set(int min) {
        if (fadeTask != null) fadeTask.cancel(false);
        if (endTask != null) endTask.cancel(false);
        fadeTask = endTask = null;
        if (fading) { fading = false; Sh.run(SleepTimer.FADE_END); }
        minutes = min;
        if (min == 0) {
            endAt = 0;
            Sh.log("STATUS sleep_timer off");
            return;
        }
        long now = System.currentTimeMillis();
        endAt = now + min * 60_000L;
        long fadeIn = Math.max(0, SleepTimer.fadeStartAt(endAt) - now);
        fadeTask = timer.schedule(this::startFade, fadeIn, TimeUnit.MILLISECONDS);
        endTask = timer.schedule(this::fireEnd, endAt - now, TimeUnit.MILLISECONDS);
        Sh.log("STATUS sleep_timer " + min);
    }

    private void startFade() {
        synchronized (this) {
            if (minutes == 0) return;
            fading = true;
            long left = (endAt - System.currentTimeMillis() + 999) / 1000;
            int secs = (int) Math.max(1, Math.min(SleepTimer.FADE_MS / 1000, left));
            Sh.run(SleepTimer.fadeStart(secs));
        }
    }

    private void fireEnd() {
        synchronized (this) {
            if (minutes == 0) return;
            minutes = 0;
            endAt = 0;
            fadeTask = endTask = null;
            fading = false;
        }
        Sh.run("cmd media_session dispatch pause");
        Sh.run("input keyevent KEYCODE_SLEEP");
        Sh.run(SleepTimer.FADE_END);
        Sh.log("STATUS sleep_timer off");
    }

    // ---- stay-awake while media plays ----
    private void stayAwakeTick() {
        try {
            boolean owned = d.file(STAYON_MARKER).exists();
            boolean want = d.conf().stayAwakeMedia && MediaState.isPlaying(Sh.run("dumpsys media_session"));
            if (want && !owned) {
                // Only claim it when it is currently off; if the user already has stay-on, leave it alone.
                String cur = Sh.run("settings get global stay_on_while_plugged_in").trim();
                if (cur.isEmpty() || "0".equals(cur) || "null".equals(cur)) stayOn(true);
            } else if (!want && owned) {
                stayOn(false);
            }
        } catch (Throwable t) {
            Sh.log("stay-awake error: " + t);
        }
    }

    private void stayOn(boolean on) {
        File m = d.file(STAYON_MARKER);
        if (on) {
            try { Stickd.writeAtomic(m, "1\n"); } catch (Exception e) { Sh.log("stayon marker: " + e); return; }
            Sh.run("svc power stayon true");
        } else {
            Sh.run("svc power stayon false");
            m.delete();
        }
        Sh.log("stay-awake " + (on ? "on" : "off"));
    }

    // ---- DNS guard ----
    private synchronized void dnsCheck() {
        if (dnsTask != null) dnsTask.cancel(false);  // restart: the latest change is the one to judge
        final boolean[] probes = new boolean[DnsGuard.PROBE_AT_MS.length];
        final long t0 = System.currentTimeMillis();
        final int gen = ++dnsGen;
        Sh.log("dns_check started");
        dnsTask = dnsTimer.schedule(new Runnable() {
            int i = 0;
            @Override public void run() {
                try {
                    synchronized (Sleep.this) { if (gen != dnsGen) return; }
                    probes[i] = Sh.run("(" + DnsGuard.PROBE + ") >/dev/null 2>&1 && echo PROBE_OK").contains("PROBE_OK");
                    i++;
                    if (i < probes.length) {
                        long wait = Math.max(0, t0 + DnsGuard.PROBE_AT_MS[i] - System.currentTimeMillis());
                        synchronized (Sleep.this) {
                            if (gen == dnsGen) dnsTask = dnsTimer.schedule(this, wait, TimeUnit.MILLISECONDS);
                        }
                        return;
                    }
                    synchronized (Sleep.this) { if (gen != dnsGen) return; }
                    if (DnsGuard.decide(probes)) {
                        Sh.run(DnsGuard.REVERT);
                        Sh.log("STATUS dns reverted");
                    } else {
                        Sh.log("STATUS dns ok");
                    }
                } catch (Throwable t) {
                    Sh.log("dns_check error: " + t);
                }
            }
        }, DnsGuard.PROBE_AT_MS[0], TimeUnit.MILLISECONDS);
    }
}
