package com.clean.stickman.daemon;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Drawn pointer for Fire OS 5-7, where shell can create neither a virtual mouse (/dev/uinput) nor a HID one
 * (/dev/uhid, closed before Android 9). remapd still turns the arrows / OK / Rewind / Fast-forward into pointer
 * moves (same acceleration as the real mouse) but pipes them here as "P dx dy wheel button" lines. This keeps the
 * position, injects what a mouse would cause (a touch tap or drag at the pointer for the button, mouse hover moves,
 * mouse scroll for the wheel) and tells the Stickman app where to draw the cursor: Android only draws its own
 * pointer for real mouse devices, not for injected events.
 * <p>
 * The app draws a cursor in an overlay window (FadeReceiver POINTER_ON / POINTER_OFF) and listens on
 * 127.0.0.1:{@link #PORT} for "x y" lines. Its overlay is not touchable, so the injected taps reach the app below.
 * Reflection only, like the rest of the daemon (no compile-time android.* dependencies).
 */
final class SoftPointer {
    static final int PORT = 38641;
    /** remapd's pointer units are mouse counts; on screen they map 1:1 to pixels of a 1080p display. */
    static final float SCALE = 1.0f;
    static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2, ACTION_HOVER_MOVE = 7, ACTION_SCROLL = 8;
    static final int ACTION_HOVER_ENTER = 9, ACTION_HOVER_EXIT = 10;
    static final int SOURCE_TOUCHSCREEN = 0x1002, SOURCE_MOUSE = 0x2002, TOOL_TYPE_MOUSE = 3, AXIS_VSCROLL = 9;
    static final String OVERLAY = "com.clean.stickman/.FadeReceiver";

    private final Object im;
    private final Method inject, uptime;
    private Method obtainSimple, obtainFull, setSource;
    private Class<?> propsClass, coordsClass;
    private final int w, h;
    private float x, y;
    private boolean on, button;
    private long downAt;
    private volatile String pending;   // latest "x y" for the app, sent by the reporter thread
    private Thread reporter;
    /** Per-session secret: handed to the app in the DUMP-protected broadcast, presented first on the socket. */
    private volatile String token;

    SoftPointer(Object im, Method inject, Method uptime) {
        this.im = im;
        this.inject = inject;
        this.uptime = uptime;
        int[] size = displaySize();
        w = size[0];
        h = size[1];
        try {
            Class<?> me = Class.forName("android.view.MotionEvent");
            obtainSimple = me.getMethod("obtain", long.class, long.class, int.class, float.class, float.class, int.class);
            propsClass = Class.forName("android.view.MotionEvent$PointerProperties");
            coordsClass = Class.forName("android.view.MotionEvent$PointerCoords");
            Object propsArr = java.lang.reflect.Array.newInstance(propsClass, 0);
            Object coordsArr = java.lang.reflect.Array.newInstance(coordsClass, 0);
            obtainFull = me.getMethod("obtain", long.class, long.class, int.class, int.class, propsArr.getClass(),
                coordsArr.getClass(), int.class, int.class, float.class, float.class, int.class, int.class, int.class, int.class);
            setSource = me.getMethod("setSource", int.class);
        } catch (Exception e) {
            Sh.log("pointer: MotionEvent unavailable: " + e);
        }
    }

    /** Logical size of the default display (DisplayManagerGlobal, API 17+), else 1920x1080. */
    static int[] displaySize() {
        try {
            Class<?> g = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object dm = g.getMethod("getInstance").invoke(null);
            Object info = g.getMethod("getDisplayInfo", int.class).invoke(dm, 0);
            int lw = info.getClass().getField("logicalWidth").getInt(info);
            int lh = info.getClass().getField("logicalHeight").getInt(info);
            if (lw > 0 && lh > 0) return new int[] { lw, lh };
        } catch (Exception ignored) { }
        return new int[] { 1920, 1080 };
    }

    /** One "P ..." line from remapd: "P on", "P off" or "P dx dy wheel button". */
    synchronized void onLine(String[] t) {
        if (t.length == 2 && t[1].equals("on")) { show(); return; }
        if (t.length == 2 && t[1].equals("off")) { hide(); return; }
        if (t.length != 5 || !on) return;
        int dx, dy, wheel, btn;
        try {
            dx = Integer.parseInt(t[1]); dy = Integer.parseInt(t[2]);
            wheel = Integer.parseInt(t[3]); btn = Integer.parseInt(t[4]);
        } catch (NumberFormatException e) { return; }
        if (dx != 0 || dy != 0) {
            x = clamp(x + dx * SCALE * w / 1920f, 0, w - 1);
            y = clamp(y + dy * SCALE * h / 1080f, 0, h - 1);
            if (button) touch(ACTION_MOVE); else mouse(ACTION_HOVER_MOVE, 0);
            report();
        }
        if (btn != 0 && !button) { button = true; downAt = now(); touch(ACTION_DOWN); }
        else if (btn == 0 && button) { touch(ACTION_UP); button = false; }
        if (wheel != 0) mouse(ACTION_SCROLL, wheel);
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : v > hi ? hi : v; }

    private void show() {
        if (on) return;
        on = true;
        x = w / 2f;
        y = h / 2f;
        button = false;
        token = Long.toHexString(new java.security.SecureRandom().nextLong() & Long.MAX_VALUE);
        overlay(true);
        report();
        startReporter();
        mouse(ACTION_HOVER_ENTER, 0);
        Sh.log("STATUS pointer drawn on");
    }

    private void hide() {
        if (!on) return;
        if (button) { touch(ACTION_UP); button = false; }
        mouse(ACTION_HOVER_EXIT, 0);
        on = false;
        pending = null;
        if (reporter != null) reporter.interrupt();
        reporter = null;
        overlay(false);
        Sh.log("STATUS pointer drawn off");
    }

    private void report() { pending = Math.round(x) + " " + Math.round(y); }

    /** Show / remove the app's cursor overlay. In the background: am broadcast can take seconds, and this runs on the
     * injector's input loop, which must keep replaying keys meanwhile. */
    private void overlay(boolean show) {
        final String cmd = "am broadcast -f 32 -n " + OVERLAY + " -a com.clean.stickman.POINTER_" + (show ? "ON --es token " + token : "OFF")
            + " >/dev/null 2>&1";
        Thread t = new Thread(() -> Sh.run(cmd), "pointer-overlay");
        t.setDaemon(true);
        t.start();
    }

    /** Sends the latest position to the app's overlay at most every 16 ms; reconnects while the app starts. */
    private void startReporter() {
        final Thread t = new Thread(() -> {
            Socket s = null;
            OutputStream out = null;
            String sent = null;
            long nextTry = 0, sentAt = 0, failingSince = 0, reannounced = System.currentTimeMillis();
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (out == null && System.currentTimeMillis() >= nextTry) {
                        try {
                            s = new Socket();
                            s.connect(new InetSocketAddress("127.0.0.1", PORT), 500);
                            s.setTcpNoDelay(true);
                            out = s.getOutputStream();
                            out.write(("token " + token + "\n").getBytes("US-ASCII"));
                            sent = null;
                            failingSince = 0;
                        } catch (Exception e) {
                            closeQuietly(s); s = null; out = null;
                            long now = System.currentTimeMillis();
                            nextTry = now + 250;
                            if (failingSince == 0) failingSince = now;
                            // the app's overlay is gone (process killed, or not up yet): ask for it again, now and then
                            if (now - failingSince > 1500 && now - reannounced > 3000) { reannounced = now; overlay(true); }
                        }
                    }
                    String p = pending;
                    long now = System.currentTimeMillis();
                    // a changed position at once; the same one again every second, as a heartbeat
                    if (out != null && p != null && (!p.equals(sent) || now - sentAt >= 1000)) {
                        try {
                            out.write((p + "\n").getBytes("US-ASCII"));
                            out.flush();
                            sent = p;
                            sentAt = now;
                        } catch (Exception e) {
                            closeQuietly(s); s = null; out = null;
                        }
                    }
                    Thread.sleep(16);
                } catch (InterruptedException e) {
                    break;
                }
            }
            closeQuietly(s);
        }, "pointer-report");
        t.setDaemon(true);
        reporter = t;
        t.start();
    }

    private static void closeQuietly(Socket s) {
        try { if (s != null) s.close(); } catch (Exception ignored) { }
    }

    private long now() {
        try { return (Long) uptime.invoke(null); } catch (Exception e) { return System.nanoTime() / 1000000L; }
    }

    /** A finger at the pointer: taps and drags work in every app, mouse button events only in some. */
    private void touch(int action) {
        if (obtainSimple == null) return;
        try {
            Object ev = obtainSimple.invoke(null, downAt, now(), action, x, y, 0);
            setSource.invoke(ev, SOURCE_TOUCHSCREEN);
            inject.invoke(im, ev, 0);
        } catch (Exception e) {
            Sh.log("pointer: touch failed: " + e);
        }
    }

    /** Mouse-sourced hover / scroll at the pointer (hover highlights, scroll wheel). */
    private void mouse(int action, int vscroll) {
        if (obtainFull == null) return;
        try {
            Object props = propsClass.getConstructor().newInstance();
            propsClass.getField("id").setInt(props, 0);
            propsClass.getField("toolType").setInt(props, TOOL_TYPE_MOUSE);
            Object coords = coordsClass.getConstructor().newInstance();
            coordsClass.getField("x").setFloat(coords, x);
            coordsClass.getField("y").setFloat(coords, y);
            if (vscroll != 0) coordsClass.getMethod("setAxisValue", int.class, float.class).invoke(coords, AXIS_VSCROLL, (float) vscroll);
            Object pa = java.lang.reflect.Array.newInstance(propsClass, 1);
            java.lang.reflect.Array.set(pa, 0, props);
            Object ca = java.lang.reflect.Array.newInstance(coordsClass, 1);
            java.lang.reflect.Array.set(ca, 0, coords);
            long t = now();
            Object ev = obtainFull.invoke(null, t, t, action, 1, pa, ca, 0, 0, 1f, 1f, 0, 0, SOURCE_MOUSE, 0);
            inject.invoke(im, ev, 0);
        } catch (Exception e) {
            Sh.log("pointer: mouse event failed: " + e);
        }
    }
}
