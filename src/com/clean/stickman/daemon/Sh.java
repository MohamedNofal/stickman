package com.clean.stickman.daemon;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Shell helpers for stickd (plain Java: runs under app_process without a Context). */
public final class Sh {
    private Sh() {}

    public static final String TAG = "Stickd";
    static final long TIMEOUT_MS = 10_000;

    /** Runs {@code /system/bin/sh -c cmd}; returns stdout ("" on error). Killed after 10 s. */
    public static String run(String cmd) { return run(cmd, TIMEOUT_MS); }

    /** Same with an explicit timeout. */
    public static String run(String cmd, long timeoutMs) {
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/sh", "-c", cmd).start();
            p.getOutputStream().close();
            Drain out = new Drain(p.getInputStream());
            Drain err = new Drain(p.getErrorStream());
            out.start(); err.start();
            boolean done = waitFor(p, timeoutMs);
            if (!done) {
                p.destroy();
            }
            out.join(1000); err.join(1000);
            if (!done || p.exitValue() != 0) {
                String e = err.text().trim().replaceAll("\\s*\n\\s*", " | ");
                if (e.length() > 300) e = e.substring(0, 300);
                log("sh " + (done ? "exit " + p.exitValue() : "timeout") + ": " + cmd + (e.isEmpty() ? "" : " stderr: " + e));
            }
            return out.text();
        } catch (Exception e) {
            log("sh error: " + e);
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** Process.waitFor(timeout) and String.join only exist from Android 8 (API 26); these run on Fire OS 5/6 too. */
    static boolean waitFor(Process p, long timeoutMs) throws InterruptedException {
        long end = System.nanoTime() + timeoutMs * 1000000L;
        while (true) {
            try { p.exitValue(); return true; } catch (IllegalThreadStateException running) { }
            long left = (end - System.nanoTime()) / 1000000L;
            if (left <= 0) return false;
            Thread.sleep(Math.min(left, 20));
        }
    }

    static String join(String sep, Iterable<? extends CharSequence> parts) {
        StringBuilder b = new StringBuilder();
        for (CharSequence c : parts) { if (b.length() > 0) b.append(sep); b.append(c); }
        return b.toString();
    }

    /** Writes one line to logcat (tag Stickd) via the log binary; argv, not a shell, so no quoting issues. */
    public static void log(String msg) {
        System.out.println(msg);
        try {
            Process p = new ProcessBuilder(com.clean.stickman.core.cmd.DaemonCmds.log(TAG, msg)).redirectErrorStream(true).start();
            p.getOutputStream().close();
            new Drain(p.getInputStream()).run();
            waitFor(p, 5000);
            p.destroy();
        } catch (Exception ignored) {
        }
    }

    /** toybox {@code log} cuts a message at 1024 bytes ("log cut at 1024 bytes"): chunks stay under that. */
    static final int LOG_CHUNK = 1000;

    /**
     * Logs many lines with few {@code log} processes: lines are joined with newlines into chunks of at most
     * {@link #LOG_CHUNK} chars; logcat prints each line of a multi-line entry with its own header, so readers still
     * see one "Stickd: STATUS ..." line per line.
     */
    public static void logLines(java.util.List<String> lines) {
        java.lang.reflect.Method m = nativeLog();
        int done = 0;  // lines already written natively: the fallback must not log them again
        if (m != null) {
            try {
                for (; done < lines.size(); done++) {
                    m.invoke(null, 4 /* Log.INFO */, TAG, lines.get(done));
                    System.out.println(lines.get(done));
                }
                return;
            } catch (Exception e) {
                nativeLogFailed = true;
            }
        }
        for (String c : chunks(lines.subList(done, lines.size()), LOG_CHUNK)) log(c);
    }

    private static volatile java.lang.reflect.Method nativeLog;
    private static volatile boolean nativeLogFailed;

    /**
     * android.util.Log.println(int, String, String) via reflection, or null (host JVM). Under app_process the
     * framework classes are on the boot classpath and Log needs no Context; calling it writes straight to logd's
     * socket, so the 2 s task feed costs no process spawns (a {@code log} fork per entry was ~1.5 % of all CPUs).
     * Reflection keeps daemon/ free of android.* imports and host-compilable.
     */
    private static java.lang.reflect.Method nativeLog() {
        if (nativeLogFailed) return null;
        if (nativeLog == null) {
            try {
                nativeLog = Class.forName("android.util.Log").getMethod("println", int.class, String.class, String.class);
            } catch (Throwable t) {
                nativeLogFailed = true;
                return null;
            }
        }
        return nativeLog;
    }

    /** Lines joined by newlines into chunks of at most max chars (a single longer line is its own chunk). */
    static java.util.List<String> chunks(java.util.List<String> lines, int max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder b = new StringBuilder();
        for (String l : lines) {
            if (b.length() > 0 && b.length() + 1 + l.length() > max) {
                out.add(b.toString());
                b.setLength(0);
            }
            if (b.length() > 0) b.append('\n');
            b.append(l);
        }
        if (b.length() > 0) out.add(b.toString());
        return out;
    }

    static final class Drain extends Thread {
        private final InputStream in;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Drain(InputStream in) { this.in = in; setDaemon(true); }
        @Override public void run() {
            byte[] b = new byte[4096];
            try { int n; while ((n = in.read(b)) > 0) synchronized (buf) { buf.write(b, 0, n); } }
            catch (Exception ignored) {}
        }
        String text() { synchronized (buf) { return buf.toString(); } }
    }
}
