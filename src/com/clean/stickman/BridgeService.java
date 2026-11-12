package com.clean.stickman;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

/**
 * A VPN that routes ONLY 10.99.0.2/32 (a fake host) into our tun, for our app and the shell
 * uid only. Normal traffic on the stick is untouched. Torn down as soon as the queue is empty.
 */
public class BridgeService extends VpnService {
    static volatile boolean running;
    /** Android took our VPN away during this run (another VPN app was prepared/connected). Reset per tun. */
    static volatile boolean revoked;
    /** The worker's connection while a run is active, so a revoke can fail it at once instead of after a timeout. */
    static volatile MiniTcp activeTcp;

    @Override
    public void onRevoke() {
        revoked = true;
        android.util.Log.w("Stickman", "VPN revoked: another VPN app took over");
        MiniTcp t = activeTcp;
        if (t != null) t.abort();
        super.onRevoke();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("bridge", "Stickman", NotificationManager.IMPORTANCE_MIN));
            startForeground(1, new Notification.Builder(this, "bridge")
                .setContentTitle("Stickman").setContentText("Applying button mappings")
                .setSmallIcon(android.R.drawable.ic_menu_manage).build());
        }
        if (!running) {
            running = true;
            new Thread(new BridgeWorker(this), "stickman").start();
        }
        return START_NOT_STICKY;
    }

    /**
     * wide = no allowed-apps list, so the VPN's one route (the fake peer) applies to every uid. Needed where adbd does
     * not run as the shell user (rooted / unlocked sticks, where it stays root): its replies are routed by the uid
     * of its socket, and uid 0 belongs to no package that could be put on the allowed list. Normal traffic still does
     * not enter the tun (only 10.99.0.2 is routed), but every app sees a VPN for those seconds, so it is the fallback.
     */
    ParcelFileDescriptor establishTun(boolean wide) throws Exception { return establishTun(wide, true); }

    /**
     * prepare = false for the wide retry inside one bridge run: prepare() would silently take the VPN back from an
     * app that took over in between, and the revoked flag of this run must survive. Without it, establish() simply
     * returns null when we are no longer the prepared VPN app.
     */
    ParcelFileDescriptor establishTun(boolean wide, boolean prepare) throws Exception {
        // After a reboot no VPN is "prepared"; once the owner accepted the VPN request (ACTIVATE_VPN) this silently makes us
        // the prepared VPN app (returns null). Needed when started from boot/job, not just the UI.
        if (prepare) {
            revoked = false;
            Intent consent = VpnService.prepare(this);
            if (consent != null) throw new Exception("VPN permission missing: open Stickman and accept its VPN request");
        }
        Builder b = new Builder();
        b.setSession("Stickman");
        b.addAddress("10.99.0.1", 32);
        b.addRoute("10.99.0.2", 32);
        b.setMtu(1500);
        b.setBlocking(true);
        if (!wide) {
            b.addAllowedApplication(getPackageName());
            b.addAllowedApplication("com.android.shell");
        } else {
            // every app is "in" this VPN for a few seconds: keep it harmless for them. Without these, IPv6 would be
            // blocked for all apps (no v6 address here) and Android 11 closes their open connections (secure VPN).
            b.allowFamily(android.system.OsConstants.AF_INET6);
            b.allowBypass();
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);   // or other apps' downloads pause as "metered"
        }
        return b.establish();
    }

    void finished() {
        running = false;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(true);
        stopSelf();
    }
}
