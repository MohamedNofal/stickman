package com.clean.stickman.daemon;

import com.clean.stickman.core.Actions;
import com.clean.stickman.core.DnsGuard;
import com.clean.stickman.core.EventParser;
import com.clean.stickman.core.MediaState;
import com.clean.stickman.core.SleepSchedule;
import com.clean.stickman.core.SleepTimer;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    /** Sleep-timer decisions (own lock). Shell work for the timer runs on {@link #timer} only, in order. */
    private final SleepSchedule sched = new SleepSchedule();
    /** RemapD KEY watcher during a fade; touched only on the timer thread. */
    private Process keyWatcher;
    // DNS guard; guarded by this.
    private ScheduledFuture<?> dnsTask;
    private int dnsGen;

    Sleep(Stickd d) { this.d = d; }

    void install() {
        d.register(Actions.SLEEP_TIMER, a -> set(SleepTimer.next(sched.minutes())));
        d.register(Actions.SLEEP_TIMER_SET, a -> {
            int m = SleepTimer.parseMinutes(a);
            if (m < 0) { Sh.log("sleep_timer_set bad arg: " + a); return; }
            set(m);
        });
        d.register(Actions.DNS_CHECK, a -> dnsCheck());
        // A previous stickd may have died mid-fade: clear any overlay it left behind.
        timer.execute(() -> Sh.run(SleepTimer.FADE_END));
        // A previous stickd may have died while owning stayon: give it back.
        if (d.file(STAYON_MARKER).exists()) stayOn(false);
        timer.scheduleWithFixedDelay(this::stayAwakeTick, STAY_AWAKE_TICK_S, STAY_AWAKE_TICK_S, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (d.file(STAYON_MARKER).exists()) stayOn(false);
        }));
    }

    // ---- sleep timer ----
    void set(int min) {
        long now = System.currentTimeMillis();
        SleepSchedule.Plan p = sched.set(min, now);  // bumps the generation: older fade/end tasks become no-ops
        timer.execute(() -> {
            stopKeyWatcher();
            if (p.fadeEnd) Sh.run(SleepTimer.FADE_END);
        });
        if (p.endInMs >= 0) {
            timer.schedule(() -> startFade(p.gen), p.fadeInMs, TimeUnit.MILLISECONDS);
            timer.schedule(() -> fireEnd(p.gen), p.endInMs, TimeUnit.MILLISECONDS);
        }
        Sh.log("STATUS sleep_timer " + p.status);
    }

    /** Timer thread. */
    private void startFade(int gen) {
        int secs = sched.startFade(gen, System.currentTimeMillis());
        if (secs < 0) return;  // stale: the timer was re-set or cancelled
        startKeyWatcher(gen);
        Sh.run(SleepTimer.fadeStart(secs));
    }

    /** Timer thread. */
    private void fireEnd(int gen) {
        if (!sched.end(gen)) return;
        stopKeyWatcher();
        Sh.run("cmd media_session dispatch pause");
        Sh.run("input keyevent KEYCODE_SLEEP");
        Sh.run(SleepTimer.FADE_END);
        Sh.log("STATUS sleep_timer off");
    }

    /** Timer thread. A remote key press during the fade (remapd logs "KEY <code>") cancels the timer. */
    private void onKeyDuringFade(int gen) {
        if (sched.gen() != gen || !sched.dismiss()) return;
        stopKeyWatcher();
        Sh.run(SleepTimer.FADE_END);
        Sh.log("STATUS sleep_timer off");
    }

    /** Timer thread. Follows fresh RemapD lines; lines older than the watcher start (logcat replay) are ignored. */
    private void startKeyWatcher(int gen) {
        stopKeyWatcher();
        final long since = System.currentTimeMillis();
        final Process p;
        try {
            p = new ProcessBuilder("/system/bin/logcat", "-v", "epoch", "-T", "1", "-s", "RemapD:I")
                .redirectErrorStream(true).start();
            p.getOutputStream().close();
        } catch (Exception e) {
            Sh.log("key watcher error: " + e);
            return;
        }
        keyWatcher = p;
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    long ms = EventParser.remapKeyEpochMs(line);
                    if (ms >= since) {
                        timer.execute(() -> onKeyDuringFade(gen));
                        break;
                    }
                }
            } catch (Exception ignored) {
                // destroyed by stopKeyWatcher
            } finally {
                p.destroy();
            }
        }, "stickd-fadekeys");
        t.setDaemon(true);
        t.start();
    }

    /** Timer thread. */
    private void stopKeyWatcher() {
        if (keyWatcher != null) { keyWatcher.destroy(); keyWatcher = null; }
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
    private void dnsCheck() {
        Sh.log("dns_check started");
        final boolean[] probes = new boolean[DnsGuard.PROBE_AT_MS.length];
        final long t0 = System.currentTimeMillis();
        synchronized (this) {
            if (dnsTask != null) dnsTask.cancel(false);  // restart: the latest change is the one to judge
            final int gen = ++dnsGen;
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
}
