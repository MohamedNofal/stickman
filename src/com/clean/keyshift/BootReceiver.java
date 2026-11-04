package com.clean.keyshift;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Restarts remapd right after boot. (Only delivered if the app isn't in the force-stopped state.) */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Log.i("KeyShift", "boot broadcast: " + i.getAction());
        BootJob.schedule(c);
        if (RemapConfig.enabled(c) && !RemapConfig.ensuredThisBoot(c))
            Bridge.run(c.getApplicationContext(), RemapConfig.ENSURE, true);
    }
}
