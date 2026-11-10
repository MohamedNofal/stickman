package com.clean.stickman.daemon;

import com.clean.stickman.core.KeyLayout;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Key injector for Fire OS 5-7, where the shell user may not create a virtual remote (/dev/uinput is closed
 * before Android 10). remapd grabs the real remote as usual and, instead of writing to a uinput clone, pipes
 * "linuxKeyCode value" lines (1 down, 0 up) to this process, which injects the matching Android key event
 * through InputManager, the same service "input keyevent" uses and which shell may call on every version.
 *
 * Started by remapd: app_process /system/bin com.clean.stickman.daemon.Injector VENDOR PRODUCT VERSION NAME
 * (ids in hex). Prints READY once injection is set up; exits on stdin EOF (remapd gone) or when the system
 * keeps refusing events, so remapd can release the remote instead of leaving it dead.
 */
public final class Injector {
    static final int REPEAT_DELAY_MS = 500, REPEAT_EVERY_MS = 50, MAX_FAILS = 5;
    // android.view.KeyEvent / InputDevice / KeyCharacterMap constants (the daemon is built without android.jar imports)
    static final int ACTION_DOWN = 0, ACTION_UP = 1, FLAG_FROM_SYSTEM = 0x8, SOURCE_KEYBOARD = 0x101, VIRTUAL_KEYBOARD = -1;

    private final Method uptime, keyCodeFromString;
    private final java.lang.reflect.Constructor<?> newKeyEvent;

    private final Object im;
    private final Method inject;
    private final Map<Integer, Integer> keys = new HashMap<>();   // linux code -> android key code (0 = unknown)
    private final Map<Integer, String> layout = new HashMap<>(), generic = new HashMap<>();
    private final Map<Integer, Long> downAt = new HashMap<>();
    private int fails;
    private volatile int repeatCode = -1;
    private volatile long repeatGen;

    private Injector(String[] a) throws Exception {
        Class<?> c = Class.forName("android.hardware.input.InputManager");
        im = c.getMethod("getInstance").invoke(null);
        inject = c.getMethod("injectInputEvent", Class.forName("android.view.InputEvent"), int.class);
        uptime = Class.forName("android.os.SystemClock").getMethod("uptimeMillis");
        Class<?> ke = Class.forName("android.view.KeyEvent");
        keyCodeFromString = ke.getMethod("keyCodeFromString", String.class);
        newKeyEvent = ke.getConstructor(long.class, long.class, int.class, int.class, int.class, int.class,
            int.class, int.class, int.class, int.class);
        int vendor = hex(a, 0), product = hex(a, 1), version = hex(a, 2);
        String name = a.length > 3 ? a[3] : "";
        String used = null;
        for (String f : KeyLayout.candidates(vendor, product, version, name)) {
            for (String d : KeyLayout.DIRS) {
                File kl = new File(d, f);
                if (used == null && kl.canRead()) { layout.putAll(KeyLayout.parse(Stickd.read(kl))); used = kl.getPath(); }
            }
        }
        for (String d : KeyLayout.DIRS) {
            File kl = new File(d, "Generic.kl");
            if (generic.isEmpty() && kl.canRead()) generic.putAll(KeyLayout.parse(Stickd.read(kl)));
        }
        Sh.log("injector: layout " + used + " (" + layout.size() + " keys)");
    }

    private static int hex(String[] a, int i) {
        try { return Integer.parseInt(a[i], 16); } catch (Exception e) { return 0; }
    }

    private int keyCode(int linux) {
        Integer k = keys.get(linux);
        if (k != null) return k;
        String n = layout.get(linux);
        if (n == null) n = generic.get(linux);
        int code = n == null ? 0 : fromString("KEYCODE_" + n);
        if (code == 0 && n != null) code = fromString(n);
        if (code == 0) Sh.log("injector: no Android key for linux code " + linux);
        keys.put(linux, code);
        return code;
    }

    private int fromString(String n) {
        try { return (Integer) keyCodeFromString.invoke(null, n); } catch (Exception e) { return 0; }
    }

    private long now() {
        try { return (Long) uptime.invoke(null); } catch (Exception e) { return System.nanoTime() / 1000000L; }
    }

    private synchronized void send(int linux, int action, long down, int repeat) {
        int code = keyCode(linux);
        if (code == 0) return;
        boolean ok;
        try {
            Object ev = newKeyEvent.newInstance(down, now(), action, code, repeat, 0,
                VIRTUAL_KEYBOARD, linux, FLAG_FROM_SYSTEM, SOURCE_KEYBOARD);
            ok = Boolean.TRUE.equals(inject.invoke(im, ev, 0));
        } catch (Exception e) { ok = false; }
        if (ok) { fails = 0; return; }
        if (++fails >= MAX_FAILS) { Sh.log("injector: system refuses key events, giving up"); System.exit(3); }
    }

    private void onLine(int linux, int value) {
        if (value == 1) {
            long t = now();
            downAt.put(linux, t);
            send(linux, ACTION_DOWN, t, 0);
            startRepeat(linux, t);
        } else if (value == 0) {
            Long t = downAt.remove(linux);
            if (repeatCode == linux) { repeatCode = -1; repeatGen++; }
            if (t != null) send(linux, ACTION_UP, t, 0);
        }
    }

    /** The input dispatcher only auto-repeats keys from real devices: a held injected key needs its own repeats. */
    private void startRepeat(final int linux, final long down) {
        final long gen = ++repeatGen;
        repeatCode = linux;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(REPEAT_DELAY_MS);
                for (int n = 1; repeatGen == gen && repeatCode == linux; n++) {
                    send(linux, ACTION_DOWN, down, n);
                    Thread.sleep(REPEAT_EVERY_MS);
                }
            } catch (InterruptedException ignored) { }
        }, "repeat");
        t.setDaemon(true);
        t.start();
    }

    public static void main(String[] args) {
        try {
            Injector j = new Injector(args);
            System.out.println("READY");
            System.out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            for (String l; (l = in.readLine()) != null; ) {
                String[] t = l.trim().split(" ");
                if (t.length != 2) continue;
                try { j.onLine(Integer.parseInt(t[0]), Integer.parseInt(t[1])); } catch (NumberFormatException ignored) { }
            }
        } catch (Throwable t) {
            Sh.log("injector fatal: " + t);
            System.exit(2);
        }
        System.exit(0);
    }
}
