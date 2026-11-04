package com.clean.stickman;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.clean.stickman.core.CommandQueue;

/** FIFO queue of shell commands executed as uid 2000 through the VPN self-ADB link. */
public class Bridge {
    public interface Listener { void onResult(String cmd, String output, boolean ok); }

    public static final String VPN_BUSY = "FAIL vpn_busy";

    static final Object lock = new Object();
    static final CommandQueue queue = new CommandQueue();   // guarded by lock
    static volatile String lastResult = "";
    static volatile long lastResultAt;
    static volatile boolean busy;
    static volatile Listener listener;
    private static final Handler main = new Handler(Looper.getMainLooper());

    public static void setListener(Listener l) { listener = l; }

    public static void run(Context c, String cmd, boolean fromBackground) {
        if (fromBackground && otherVpnActive(c)) {
            synchronized (lock) { queue.clear(); }
            lastResult = VPN_BUSY;
            lastResultAt = System.currentTimeMillis();
            notifyResult(cmd, VPN_BUSY, false);
            return;
        }
        synchronized (lock) { queue.add(cmd); }
        Intent i = new Intent(c, BridgeService.class);
        if (fromBackground && Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    /** True if some VPN other than ours is up (we would displace it by establishing ours). */
    public static boolean otherVpnActive(Context c) {
        if (BridgeService.running) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network[] ns = cm.getAllNetworks();
            if (ns == null) return false;
            for (Network n : ns) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (RuntimeException e) { // incl. SecurityException; courtesy check only, fail open
            android.util.Log.w("Stickman", "otherVpnActive failed: " + e);
        }
        return false;
    }

    static String take() { synchronized (lock) { return queue.poll(); } }

    static boolean hasPending() { synchronized (lock) { return !queue.isEmpty(); } }

    static void notifyResult(final String cmd, final String output, final boolean ok) {
        final Listener l = listener;
        if (l == null) return;
        main.post(new Runnable() { public void run() { l.onResult(cmd, output, ok); } });
    }
}
