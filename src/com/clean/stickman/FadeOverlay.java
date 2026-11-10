package com.clean.stickman;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Sleep-timer fade: a black, non-focusable, non-touchable TYPE_APPLICATION_OVERLAY whose alpha rises 0 -> 0.85
 * over the given seconds. Removed by FADE_END (stickd also sends it when the timer ends or is cancelled).
 * <p>
 * Screen off ("curtain"): the same window, opaque at once and focusable, so the first button press lands on it,
 * removes it and goes no further. FLAG_KEEP_SCREEN_ON keeps the screensaver from lighting the screen again.
 * Home never reaches a window, so ACTION_CLOSE_SYSTEM_DIALOGS removes it too; stickd also ends it on any
 * remapd KEY line (buttons remapd swallows). HDMI stays up, which is why audio keeps playing: powering the
 * display off (SurfaceControl.setDisplayPowerMode) unplugs HDMI audio as well on Fire OS 8.
 * Main thread only (BroadcastReceiver.onReceive, key dispatch).
 */
final class FadeOverlay {
    private FadeOverlay() {}

    static final float MAX_ALPHA = 0.85f;
    private static View view;
    static final long STEP_MS = 200;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Runnable ramp;
    private static boolean curtain;
    private static BroadcastReceiver homeWatch;
    /** uptimeMillis of the last curtain removal by a button; a show request right after it is that same press. */
    private static long dismissedAt = -10_000;
    static final long REPRESS_MS = 1500;
    /** Ignore key-ups of the press that asked for the curtain. */
    static final long ARM_MS = 400;

    /** Screen off: opaque black until any button. A request within REPRESS_MS of a dismissal is ignored. */
    static void curtain(Context ctx) {
        if (curtain) return;
        if (SystemClock.uptimeMillis() - dismissedAt < REPRESS_MS) { Log.i("Stickman", "curtain: same press, ignored"); return; }
        stop();
        try {
            final Context app = ctx.getApplicationContext();
            WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
            lp.setTitle("StickmanCurtain");
            final long shownAt = SystemClock.uptimeMillis();
            View v = new View(app) {
                @Override public boolean dispatchKeyEvent(KeyEvent e) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN && SystemClock.uptimeMillis() - shownAt >= ARM_MS) dismiss();
                    return true;  // the press that brings the picture back does nothing else
                }
            };
            v.setBackgroundColor(Color.BLACK);
            v.setFocusable(true);
            v.setFocusableInTouchMode(true);
            wm.addView(v, lp);
            v.requestFocus();
            view = v;
            curtain = true;
            homeWatch = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) { if (curtain) dismiss(); }
            };
            app.registerReceiver(homeWatch, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
            Log.i("Stickman", "STATUS curtain on");
        } catch (Throwable t) {
            stop();
            Log.w("Stickman", "STATUS curtain failed: " + t);
        }
    }

    /** A button ended the curtain. */
    static void dismiss() {
        if (!curtain) return;
        dismissedAt = SystemClock.uptimeMillis();
        stop();
    }

    static boolean curtainOn() { return curtain; }

    static void start(Context ctx, int seconds) {
        if (curtain) return;  // already black: a sleep-timer fade must not brighten it
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
        boolean wasCurtain = curtain;
        curtain = false;
        if (v == null) return;
        if (homeWatch != null) {
            try { v.getContext().unregisterReceiver(homeWatch); } catch (Throwable ignored) { }
            homeWatch = null;
        }
        try {
            ((WindowManager) v.getContext().getSystemService(Context.WINDOW_SERVICE)).removeView(v);
            Log.i("Stickman", wasCurtain ? "STATUS curtain off" : "fade end");
        } catch (Throwable t) {
            Log.w("Stickman", "fade remove failed: " + t);
        }
    }
}
