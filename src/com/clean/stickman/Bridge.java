package com.clean.stickman;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Queue of shell commands executed as uid 2000 through the VPN self-ADB link. */
public class Bridge {
    static final Object lock = new Object();
    static String pending;                       // latest command wins (they're idempotent)
    static volatile String lastResult = "";
    static volatile long lastResultAt;
    static volatile boolean busy;

    public static void run(Context c, String cmd, boolean fromBackground) {
        synchronized (lock) { pending = cmd; }
        Intent i = new Intent(c, BridgeService.class);
        if (fromBackground && Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static String take() { synchronized (lock) { String p = pending; pending = null; return p; } }
}
