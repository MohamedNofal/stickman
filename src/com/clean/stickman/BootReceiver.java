package com.clean.stickman;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Re-runs ensure right after boot. (Only delivered if the app isn't in the force-stopped state.) */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Log.i("Stickman", "boot broadcast: " + i.getAction());
        BootJob.schedule(c);
        if (Store.enabled(c) && !Store.ensuredThisBoot(c))
            Bridge.run(c.getApplicationContext(), Ensure.MARKER, true);
    }
}
