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
    /** Latest STATUS line per kind (the word after STATUS, e.g. "boost", "dns", "sleep_timer") -> the rest. */
    static final java.util.concurrent.ConcurrentHashMap<String, String> lastStatus = new java.util.concurrent.ConcurrentHashMap<>();
    /** Same keys -> when that line was logged (epoch ms, from the logcat timestamp; replayed history keeps its time). */
    static final java.util.concurrent.ConcurrentHashMap<String, Long> lastStatusAt = new java.util.concurrent.ConcurrentHashMap<>();
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
                    if (st > 0) {
                        String body = l.substring(st + 3).trim();
                        addStatus(l.length() > 14 ? l.substring(6, 14) : "", body);
                        putStatus(body, logTime(l));
                        continue;
                    }
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

    /** body = "STATUS <kind> [rest]". */
    static void putStatus(String body, long at) {
        String[] t = body.split("\\s+", 3);
        if (t.length < 2 || !t[0].equals("STATUS")) return;
        lastStatus.put(t[1], t.length > 2 ? t[2] : "");
        lastStatusAt.put(t[1], at);
    }

    /** Rest of the latest "STATUS <kind> ..." line, or null if none seen. */
    static String status(String kind) { return lastStatus.get(kind); }

    /** When the latest "STATUS <kind>" line was logged (epoch ms), or 0. */
    static long statusAt(String kind) {
        Long t = lastStatusAt.get(kind);
        return t == null ? 0 : t;
    }

    /** Epoch ms of a "MM-DD HH:mm:ss.mmm ..." logcat line (this year; last year if that lands in the future). */
    static long logTime(String l) {
        long now = System.currentTimeMillis();
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT);
            int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
            long t = f.parse(year + "-" + l.substring(0, 18)).getTime();
            if (t > now + 86400000L) t = f.parse((year - 1) + "-" + l.substring(0, 18)).getTime();
            return t;
        } catch (Exception e) {
            return now;
        }
    }

    static java.util.List<String> statusLines() {
        synchronized (status) { return new java.util.ArrayList<>(status); }
    }

    void shutdown() { stop = true; if (proc != null) proc.destroy(); }
}
