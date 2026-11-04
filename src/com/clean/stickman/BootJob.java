package com.clean.stickman;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * Fire OS never delivers BOOT_COMPLETED to sideloaded apps, but it does restore PERSISTED jobs
 * after a reboot. This periodic job re-runs the ensure script (remapd + stickd) once per boot (no-op otherwise).
 */
public class BootJob extends JobService {
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        JobInfo cur = js.getPendingJob(42);
        if (cur != null && cur.getFlexMillis() == 5 * 60 * 1000L) return;
        js.schedule(new JobInfo.Builder(42, new ComponentName(c, BootJob.class))
            .setPeriodic(15 * 60 * 1000L, 5 * 60 * 1000L).setPersisted(true).build());
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
