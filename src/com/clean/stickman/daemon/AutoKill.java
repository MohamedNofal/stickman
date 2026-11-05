package com.clean.stickman.daemon;

import com.clean.stickman.core.Actions;
import com.clean.stickman.core.KillPolicy;
import com.clean.stickman.core.LruParser;
import com.clean.stickman.core.MediaState;
import com.clean.stickman.core.StickdConf;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Boost action + auto-kill (on leaving an app, and on an interval). Plain Java (no android.*). */
final class AutoKill {
    static final long LEAVE_DELAY_MS = 10_000;
    private static final Pattern MEM_AVAIL = Pattern.compile("MemAvailable:\\s+(\\d+)\\s*kB");

    private final Stickd d;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stickd-autokill");
        t.setDaemon(true);
        return t;
    });
    /** Serialises kills (boost / on-leave / interval). Never taken on the foreground thread. */
    final Object killLock = new Object();
    /** Guards only pendingLeave; held for microseconds, never during a kill. */
    private final Object leaveLock = new Object();
    private ScheduledFuture<?> pendingLeave;
    private volatile long lastIntervalKill = System.currentTimeMillis();

    AutoKill(Stickd d) { this.d = d; }

    void install() {
        // Off the spool thread: a Boost with many candidates takes tens of seconds and must not delay a following
        // sleep_timer / screen_off. Runs on the auto-kill thread (kills stay serialised under killLock).
        d.register(Actions.BOOST, a -> timer.execute(this::boostSafe));
        d.addFgListener((pkg, prev) -> onForeground());
        timer.scheduleWithFixedDelay(this::intervalTick, 1, 1, TimeUnit.MINUTES);
    }

    /** Each foreground change restarts the 10 s countdown; it fires once the user stays put. */
    private void onForeground() {
        synchronized (leaveLock) {
            if (pendingLeave != null) pendingLeave.cancel(false);
            pendingLeave = null;
            StickdConf c = d.conf();
            // Always-kill (blacklist) works even with global on-leave auto-kill off.
            if (!c.onLeave && c.blacklist.isEmpty()) return;
            pendingLeave = timer.schedule(() -> {
                StickdConf now = d.conf();
                if (now.onLeave) autoKill();
                else if (!now.blacklist.isEmpty()) autoKillBlacklistOnly();
            }, LEAVE_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** True while an on-leave countdown is scheduled and not yet run (tests). */
    boolean leavePending() {
        synchronized (leaveLock) { return pendingLeave != null && !pendingLeave.isDone(); }
    }

    private void intervalTick() {
        try {
            int min = d.conf().intervalMin;
            long now = System.currentTimeMillis();
            if (min <= 0) { lastIntervalKill = now; return; }
            if (now - lastIntervalKill >= min * 60_000L) {
                lastIntervalKill = now;
                autoKill();
            }
        } catch (Throwable t) {
            Sh.log("autokill interval error: " + t);
        }
    }

    private void autoKill() {
        try {
            List<String> killed = killCandidates();
            Sh.log("STATUS autokill killed=" + String.join(",", killed));
        } catch (Throwable t) {
            Sh.log("autokill error: " + t);
        }
    }

    /** On-leave with global auto-kill off: only the always-kill (blacklisted) candidates, force-stopped. */
    private void autoKillBlacklistOnly() {
        try {
            List<String> killed;
            synchronized (killLock) { killed = killCandidatesLocked(true); }
            Sh.log("STATUS autokill killed=" + String.join(",", killed));
        } catch (Throwable t) {
            Sh.log("autokill error: " + t);
        }
    }

    private void boostSafe() {
        try { boost(); } catch (Throwable t) { Sh.log("boost error: " + t); }
    }

    void boost() {
        long before = memAvailableKb();
        List<String> killed = killCandidates();
        Sh.run("pm trim-caches 999G", 30_000);
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        long after = memAvailableKb();
        long freed = (before < 0 || after < 0) ? 0 : Math.max(0, (after - before) / 1024);
        Sh.log("killed: " + String.join(",", killed));
        Sh.log("STATUS boost freed_mb=" + freed + " killed=" + killed.size());
    }

    /** Recomputes candidates at kill time (fresh fg + media state), then trims and kills each. */
    private List<String> killCandidates() {
        synchronized (killLock) { return killCandidatesLocked(false); }
    }

    private List<String> killCandidatesLocked(boolean blacklistOnly) {
        StickdConf c = d.conf();
        List<LruParser.Proc> procs = LruParser.parse(Sh.run("dumpsys activity lru"));
        String fg = d.foreground();
        java.util.Set<String> launchable = KillPolicy.parseLaunchable(Sh.run(KillPolicy.LAUNCHABLE_CMD));
        if (launchable.isEmpty()) Sh.log("autokill: no launchable apps listed; nothing killed");
        List<String> cands = KillPolicy.candidates(procs, fg, c.whitelist,
            MediaState.protectedPackages(Sh.run("dumpsys media_session")), launchable);
        List<String> killed = new ArrayList<>();
        for (String pkg : cands) {
            if (blacklistOnly && !c.blacklist.contains(pkg)) continue;
            if (pkg.equals(d.foreground())) continue;  // user switched to it meanwhile
            // Gentle first (boost, on-leave and interval alike). Trim fails harmlessly when the process is
            // already trimmed or named differently: keep it quiet.
            Sh.run("am send-trim-memory " + pkg + " COMPLETE >/dev/null 2>&1; true");
            // am kill refuses Amazon-protected packages (non-zero exit); only count real kills.
            String r = Sh.run((c.blacklist.contains(pkg) ? "am force-stop " : "am kill ") + pkg + " && echo KILLED");
            if (r.contains("KILLED")) killed.add(pkg);
        }
        return killed;
    }

    static long memAvailableKb() {
        Matcher m = MEM_AVAIL.matcher(Stickd.read(new File("/proc/meminfo")));
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }
}
