package com.clean.stickman;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Exported only to draw/remove the sleep-timer fade overlay; accepts no other commands. */
public class FadeReceiver extends BroadcastReceiver {
    public static final String FADE_START = "com.clean.stickman.FADE_START";
    public static final String FADE_END = "com.clean.stickman.FADE_END";

    @Override
    public void onReceive(Context ctx, Intent i) {
        if (i == null) return;
        if (FADE_START.equals(i.getAction())) {
            int s = i.getIntExtra("seconds", 60);
            FadeOverlay.start(ctx.getApplicationContext(), Math.max(1, Math.min(600, s)));
        } else if (FADE_END.equals(i.getAction())) {
            FadeOverlay.stop();
        }
    }
}
