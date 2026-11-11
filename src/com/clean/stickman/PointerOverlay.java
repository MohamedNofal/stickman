package com.clean.stickman;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * The cursor of the drawn pointer (Fire OS 5-7, see daemon/SoftPointer): a full-screen, transparent overlay that is
 * neither focusable nor touchable, so the taps the injector makes at the pointer reach the app underneath. The
 * injector (shell) connects to 127.0.0.1:{@link #PORT}, presents the session token it handed over in the
 * DUMP-protected POINTER_ON broadcast, then sends "x y" lines (at least one a second); this draws a ring there.
 * When the injector's connection ends and none follows (injector or remapd gone without a POINTER_OFF), the overlay
 * removes itself, so a dead pointer never stays on screen. Main thread for the view, one thread for the socket.
 */
final class PointerOverlay {
    private PointerOverlay() {}

    static final int PORT = 38641;
    /** No position (or heartbeat) for this long: the injector is gone. */
    static final int IDLE_MS = 4000;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Cursor view;
    private static volatile ServerSocket server;
    private static volatile Socket client;
    private static volatile String token;
    private static volatile long lastDataAt;

    /** Ring cursor at (px, py) in screen pixels; draws nothing until the first position arrives. */
    static final class Cursor extends View {
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float r;
        float px = -1, py = -1;

        Cursor(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            r = 13 * d;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(3.5f * d);
            ring.setColor(0xFFFFFFFF);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(7f * d);
            edge.setColor(0xCC000000);
        }

        void moveTo(float x, float y) {
            px = x;
            py = y;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            if (px < 0) return;
            c.drawCircle(px, py, r, edge);
            c.drawCircle(px, py, r, ring);
        }
    }

    static void show(Context ctx, String tok) {
        if (tok == null || tok.isEmpty()) return;   // only the injector's broadcast carries one
        token = tok;                                 // a re-announce after we were killed brings the same session's
        lastDataAt = System.currentTimeMillis();
        if (view != null) return;
        final Context app = ctx.getApplicationContext();
        try {
            WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
            lp.setTitle("StickmanPointer");
            Cursor v = new Cursor(app);
            wm.addView(v, lp);
            view = v;
            listen();
            watchdog(app);
            Log.i("Stickman", "STATUS pointer overlay on");
        } catch (Throwable t) {
            Log.w("Stickman", "STATUS pointer overlay failed: " + t);
            hide(app);
        }
    }

    static void hide(Context ctx) {
        token = null;
        closeQuietly(client);
        client = null;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null;
        if (view == null) return;
        try {
            WindowManager wm = (WindowManager) ctx.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
            wm.removeView(view);
        } catch (Throwable ignored) { }
        view = null;
        Log.i("Stickman", "STATUS pointer overlay off");
    }

    /** Removes the overlay once the injector has been silent for IDLE_MS (it sends a heartbeat every second). */
    private static void watchdog(final Context app) {
        H.postDelayed(new Runnable() {
            @Override public void run() {
                if (view == null) return;
                if (System.currentTimeMillis() - lastDataAt > IDLE_MS) {
                    Log.i("Stickman", "pointer: injector silent, removing the cursor");
                    hide(app);
                    return;
                }
                H.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private static void closeQuietly(Socket s) {
        try { if (s != null) s.close(); } catch (Exception ignored) { }
    }

    /**
     * Loopback only, one connection at a time. A connection must present the session token first and then keep
     * talking: one that stays silent is dropped, so another app can't hold the slot or move the cursor.
     */
    private static void listen() {
        Thread t = new Thread(() -> {
            ServerSocket ss = null;
            for (int i = 0; i < 20 && ss == null && view != null; i++) {
                try {
                    ServerSocket s = new ServerSocket();
                    s.setReuseAddress(true);   // before bind: a quick off/on finds the old port in TIME_WAIT
                    s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 1);
                    ss = s;
                } catch (Exception e) {
                    try { Thread.sleep(300); } catch (InterruptedException ie) { return; }
                }
            }
            if (ss == null) { Log.w("Stickman", "pointer: port " + PORT + " unavailable"); return; }
            server = ss;
            while (!ss.isClosed()) {
                try (Socket s = ss.accept()) {
                    s.setSoTimeout(IDLE_MS);
                    client = s;
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "US-ASCII"));
                    String first = in.readLine();
                    String tok = token;
                    if (first == null || tok == null || !first.equals("token " + tok)) continue;
                    for (String l; (l = in.readLine()) != null; ) {
                        String[] p = l.trim().split(" ");
                        if (p.length != 2) continue;
                        final float x, y;
                        try { x = Float.parseFloat(p[0]); y = Float.parseFloat(p[1]); } catch (NumberFormatException e) { continue; }
                        lastDataAt = System.currentTimeMillis();
                        H.post(() -> { if (view != null) view.moveTo(x, y); });
                    }
                } catch (Exception e) {
                    if (ss.isClosed()) break;
                } finally {
                    client = null;
                }
            }
        }, "pointer-listen");
        t.setDaemon(true);
        t.start();
    }
}
