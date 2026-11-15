package com.clean.stickman;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * Fire OS never delivers BOOT_COMPLETED to sideloaded apps, but it does restore PERSISTED jobs
 * after a reboot. This periodic job re-runs the ensure script (remapd + stickd) once per boot (no-op otherwise).
 */
public class BootJob extends JobService {
    /** getSystemService(Class) is API 23; the string form works on Fire OS 5 (API 22). */
    static JobScheduler scheduler(Context c) {
        return (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    static void schedule(Context c) {
        JobScheduler js = scheduler(c);
        // getPendingJob, getFlexMillis and setPeriodic(interval, flex) are API 24: older Fire OS gets the plain form
        JobInfo cur = null;
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == 42) cur = j;
        boolean modern = Build.VERSION.SDK_INT >= 24;
        armQuick(c);
        if (cur != null && (!modern || cur.getFlexMillis() == 5 * 60 * 1000L)) return;
        JobInfo.Builder b = new JobInfo.Builder(42, new ComponentName(c, BootJob.class)).setPersisted(true);
        if (modern) b.setPeriodic(15 * 60 * 1000L, 5 * 60 * 1000L); else b.setPeriodic(15 * 60 * 1000L);
        js.schedule(b.build());
    }

    static final int QUICK_ID = 44;
    static final long QUICK_MS = 3 * 60_000L;

    /**
     * The quick check: a one-off job every 3 minutes that only asks stickd whether it is alive (a loopback connect,
     * about 0.1 s) and re-arms itself. The periodic job cannot run more often than every 15 minutes, which is how
     * long dead daemons used to go unnoticed. Jobs do not run while the stick sleeps and run as soon as it wakes, so
     * this is also the check on wake-up for remotes that do not reconnect over Bluetooth. The periodic job re-arms
     * the chain if it ever breaks.
     */
    static void armQuick(Context c) {
        if (!Store.enabled(c)) return;
        try {
            JobScheduler js = scheduler(c);
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == QUICK_ID) return;
            js.schedule(new JobInfo.Builder(QUICK_ID, new ComponentName(c, BootJob.class))
                .setMinimumLatency(QUICK_MS).setOverrideDeadline(QUICK_MS + 15_000L).setPersisted(true).build());
        } catch (RuntimeException e) {
            Log.w("Stickman", "BootJob: quick check not scheduled: " + e);
        }
    }

    /** After this run has ended: scheduling a job id that is still running would cancel the run. */
    private void rearm(JobParameters p) {
        final Context app = getApplicationContext();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> armQuick(app), p.getJobId() == QUICK_ID ? 1000 : 0);
    }

    /** A start that did not bring both daemons up is tried again soon (see BootRetry), not in 15-30 minutes. */
    static void retrySoon(Context c) {
        if (!Store.enabled(c) || Store.ensuredThisBoot(c)) return;
        try {
            for (JobInfo j : scheduler(c).getAllPendingJobs()) if (j.getId() == 43) return;   // one already waiting: keep it
        } catch (RuntimeException ignored) {
        }
        int n = Store.bootRetries(c);
        long delay = com.clean.stickman.core.BootRetry.delayMs(n);
        if (delay < 0) return;
        try {
            JobScheduler js = scheduler(c);
            js.schedule(new JobInfo.Builder(43, new ComponentName(c, BootJob.class))
                .setMinimumLatency(delay).setOverrideDeadline(delay + 10_000L).build());
            Store.setBootRetries(c, n + 1);
            Log.i("Stickman", "BootJob: start not complete, retry " + (n + 1) + " in " + delay / 1000 + " s");
        } catch (RuntimeException e) {
            Log.w("Stickman", "BootJob: retry not scheduled: " + e);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        // the quick check always goes through the watchdog, whose restarts back off when they keep failing: a setup
        // that cannot work (ADB off) must not take the VPN every 3 minutes
        if (params.getJobId() != QUICK_ID && Store.enabled(this) && !Store.ensuredThisBoot(this)) {
            Log.i("Stickman", "BootJob: new boot -> ensuring daemons");
            Bridge.run(this, Ensure.MARKER, true);
            rearm(params);
        } else {
            // started this boot, but still there? (adbd restarts kill the daemons). The job stays alive until answered.
            final JobParameters p = params;
            Watchdog.check(this, p.getJobId() == QUICK_ID ? "quick" : "periodic", () -> { jobFinished(p, false); rearm(p); });
            return true;
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return false; }
}
