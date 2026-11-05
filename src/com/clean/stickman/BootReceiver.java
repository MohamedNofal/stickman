package com.clean.stickman;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Re-runs ensure right after boot, and always after an app update (so the daemons restart from the new APK).
 * (Only delivered if the app isn't in the force-stopped state.) The receiver is exported for the system boot
 * broadcasts; explicit broadcasts with any other action are ignored.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        Log.i("Stickman", "boot broadcast: " + a);
        if (!com.clean.stickman.core.BootActions.accepted(a)) return;
        BootJob.schedule(c);
        if (Store.enabled(c) && com.clean.stickman.core.BootActions.shouldEnsure(a, Store.ensuredThisBoot(c)))
            Bridge.run(c.getApplicationContext(), Ensure.MARKER, true);
    }
}
