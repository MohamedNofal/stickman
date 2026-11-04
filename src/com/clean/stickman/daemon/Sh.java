package com.clean.stickman.daemon;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** Shell helpers for stickd (plain Java: runs under app_process without a Context). */
public final class Sh {
    private Sh() {}

    public static final String TAG = "Stickd";
    static final long TIMEOUT_MS = 10_000;

    /** Runs {@code /system/bin/sh -c cmd}; returns stdout ("" on error). Killed after 10 s. */
    public static String run(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/sh", "-c", cmd).start();
            p.getOutputStream().close();
            Drain out = new Drain(p.getInputStream());
            Drain err = new Drain(p.getErrorStream());
            out.start(); err.start();
            boolean done = p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
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

    /** Writes one line to logcat (tag Stickd) via the log binary; argv, not a shell, so no quoting issues. */
    public static void log(String msg) {
        System.out.println(msg);
        try {
            Process p = new ProcessBuilder("/system/bin/log", "-t", TAG, msg).redirectErrorStream(true).start();
            p.getOutputStream().close();
            new Drain(p.getInputStream()).run();
            p.waitFor(5, TimeUnit.SECONDS);
            p.destroy();
        } catch (Exception ignored) {
        }
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
