package com.clean.stickman;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Exported only to draw/remove the sleep-timer fade overlay, the screen-off curtain and the drawn pointer's cursor;
 * accepts no other commands. Callers need DUMP (shell and system only).
 */
public class FadeReceiver extends BroadcastReceiver {
    public static final String FADE_START = "com.clean.stickman.FADE_START";
    public static final String FADE_END = "com.clean.stickman.FADE_END";
    public static final String CURTAIN_ON = "com.clean.stickman.CURTAIN_ON";
    /** A button was pressed (stickd saw remapd's KEY line): ends a curtain, leaves a sleep fade alone. */
    public static final String CURTAIN_KEY = "com.clean.stickman.CURTAIN_KEY";
    /** Drawn pointer (Fire OS 5-7): show / remove the cursor overlay; the injector sends it positions. */
    public static final String POINTER_ON = "com.clean.stickman.POINTER_ON";
    public static final String POINTER_OFF = "com.clean.stickman.POINTER_OFF";

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
        } else if (POINTER_ON.equals(i.getAction())) {
            PointerOverlay.show(ctx.getApplicationContext(), i.getStringExtra("token"));
        } else if (POINTER_OFF.equals(i.getAction())) {
            PointerOverlay.hide(ctx.getApplicationContext());
        }
    }
}
