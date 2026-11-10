package com.clean.stickman;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Exported only to draw/remove the sleep-timer fade overlay and the screen-off curtain; accepts no other commands. */
public class FadeReceiver extends BroadcastReceiver {
    public static final String FADE_START = "com.clean.stickman.FADE_START";
    public static final String FADE_END = "com.clean.stickman.FADE_END";
    public static final String CURTAIN_ON = "com.clean.stickman.CURTAIN_ON";
    /** A button was pressed (stickd saw remapd's KEY line): ends a curtain, leaves a sleep fade alone. */
    public static final String CURTAIN_KEY = "com.clean.stickman.CURTAIN_KEY";

    @Override
    public void onReceive(Context ctx, Intent i) {
        if (i == null) return;
        if (FADE_START.equals(i.getAction())) {
            int s = i.getIntExtra("seconds", 60);
            FadeOverlay.start(ctx.getApplicationContext(), Math.max(1, Math.min(600, s)));
        } else if (FADE_END.equals(i.getAction())) {
            FadeOverlay.stop();
        } else if (CURTAIN_ON.equals(i.getAction())) {
            FadeOverlay.curtain(ctx.getApplicationContext());
        } else if (CURTAIN_KEY.equals(i.getAction())) {
            FadeOverlay.dismiss();
        }
    }
}
