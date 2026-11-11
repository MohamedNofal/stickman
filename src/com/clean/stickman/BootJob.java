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
        if (cur != null && (!modern || cur.getFlexMillis() == 5 * 60 * 1000L)) return;
        JobInfo.Builder b = new JobInfo.Builder(42, new ComponentName(c, BootJob.class)).setPersisted(true);
        if (modern) b.setPeriodic(15 * 60 * 1000L, 5 * 60 * 1000L); else b.setPeriodic(15 * 60 * 1000L);
        js.schedule(b.build());
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
        if (Store.enabled(this) && !Store.ensuredThisBoot(this)) {
            Log.i("Stickman", "BootJob: new boot -> ensuring daemons");
            Bridge.run(this, Ensure.MARKER, true);
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return false; }
}
