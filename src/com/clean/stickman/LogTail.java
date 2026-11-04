package com.clean.stickman;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Follows remapd's "KEY <code>" log lines (UI shows/jumps to the button just pressed) and stickd's
 * "STATUS ..." lines (Status page).
 */
public class LogTail implements Runnable {
    static final int MAX_STATUS = 12;
    /** Newest last; guarded by itself. */
    static final java.util.ArrayDeque<String> status = new java.util.ArrayDeque<>();
    static volatile long statusAt;
    static volatile int lastKey;
    static volatile long lastKeyAt;
    volatile boolean stop;
    Process proc;

    public void run() {
        while (!stop) {
            try {
                proc = Runtime.getRuntime().exec(new String[] { "logcat", "-v", "time", "-T", "100", "RemapD:I", "Stickd:I", "*:S" });
                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                synchronized (status) { status.clear(); }   // -T 100 replays recent history
                String l;
                long started = System.currentTimeMillis();
                while (!stop && (l = r.readLine()) != null) {
                    int st = l.indexOf("): STATUS ");
                    if (st > 0) { addStatus(l.length() > 14 ? l.substring(6, 14) : "", l.substring(st + 3).trim()); continue; }
                    int i = l.indexOf("): KEY ");
                    if (i > 0 && System.currentTimeMillis() - started > 500) {
                        try { lastKey = Integer.parseInt(l.substring(i + 7).trim()); lastKeyAt = System.currentTimeMillis(); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            } catch (Exception ignored) {}
            if (proc != null) proc.destroy();
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
        }
    }

    /** time = "HH:mm:ss" from logcat -v time ("MM-DD HH:mm:ss.mmm ..."). */
    static void addStatus(String time, String line) {
        synchronized (status) {
            status.addLast(time + "  " + line);
            while (status.size() > MAX_STATUS) status.removeFirst();
        }
        statusAt = System.currentTimeMillis();
    }

    static java.util.List<String> statusLines() {
        synchronized (status) { return new java.util.ArrayList<>(status); }
    }

    void shutdown() { stop = true; if (proc != null) proc.destroy(); }
}
