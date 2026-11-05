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

    ParcelFileDescriptor establishTun() throws Exception {
        // After a reboot no VPN is "prepared"; once the owner accepted the VPN request (ACTIVATE_VPN) this silently makes us
        // the prepared VPN app (returns null). Needed when started from boot/job, not just the UI.
        Intent consent = VpnService.prepare(this);
        if (consent != null) throw new Exception("VPN permission missing: open Stickman and accept its VPN request");
        Builder b = new Builder();
        b.setSession("Stickman");
        b.addAddress("10.99.0.1", 32);
        b.addRoute("10.99.0.2", 32);
        b.setMtu(1500);
        b.setBlocking(true);
        b.addAllowedApplication(getPackageName());
        b.addAllowedApplication("com.android.shell");
        return b.establish();
    }

    void finished() {
        running = false;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(true);
        stopSelf();
    }
}
