package com.clean.stickman;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Follows remapd's "KEY <code>" log lines so the UI can show/jump to the button just pressed. */
public class LogTail implements Runnable {
    static volatile int lastKey;
    static volatile long lastKeyAt;
    volatile boolean stop;
    Process proc;

    public void run() {
        while (!stop) {
            try {
                proc = Runtime.getRuntime().exec(new String[] { "logcat", "-v", "brief", "-T", "1", "RemapD:I", "*:S" });
                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                String l;
                long started = System.currentTimeMillis();
                while (!stop && (l = r.readLine()) != null) {
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

    void shutdown() { stop = true; if (proc != null) proc.destroy(); }
}
