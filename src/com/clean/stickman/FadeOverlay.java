package com.clean.stickman;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

/**
 * Sleep-timer fade: a black, non-focusable, non-touchable TYPE_APPLICATION_OVERLAY whose alpha rises 0 -> 0.85
 * over the given seconds. Removed by FADE_END (stickd also sends it when the timer ends or is cancelled).
 * Main thread only (BroadcastReceiver.onReceive).
 */
final class FadeOverlay {
    private FadeOverlay() {}

    static final float MAX_ALPHA = 0.85f;
    private static View view;
    static final long STEP_MS = 200;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Runnable ramp;

    static void start(Context ctx, int seconds) {
        stop();
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
            lp.setTitle("StickmanFade");
            View v = new View(ctx);
            v.setBackgroundColor(Color.BLACK);
            v.setAlpha(0f);
            wm.addView(v, lp);
            view = v;
            // Manual ramp, not ValueAnimator: animator_duration_scale may be 0 (animations off) on this stick.
            final long t0 = SystemClock.uptimeMillis(), total = seconds * 1000L;
            ramp = new Runnable() {
                @Override public void run() {
                    if (view != v) return;
                    float f = Math.min(1f, (SystemClock.uptimeMillis() - t0) / (float) total);
                    v.setAlpha(MAX_ALPHA * f);
                    if (f < 1f) H.postDelayed(this, STEP_MS);
                }
            };
            H.post(ramp);
            Log.i("Stickman", "fade start " + seconds + "s");
        } catch (Throwable t) {
            view = null;
            Log.w("Stickman", "fade overlay failed: " + t);
        }
    }

    static void stop() {
        if (ramp != null) { H.removeCallbacks(ramp); ramp = null; }
        View v = view;
        view = null;
        if (v == null) return;
        try {
            ((WindowManager) v.getContext().getSystemService(Context.WINDOW_SERVICE)).removeView(v);
            Log.i("Stickman", "fade end");
        } catch (Throwable t) {
            Log.w("Stickman", "fade remove failed: " + t);
        }
    }
}
