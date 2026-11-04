package com.clean.shellbridge;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;

/** VPN up -> userspace TCP to adbd -> adb auth -> run queued shell commands -> VPN down. */
public class BridgeWorker implements Runnable {
    static final String TAG = "ShellBridge";
    final BridgeService svc;

    public BridgeWorker(BridgeService svc) { this.svc = svc; }

    public void run() {
        Bridge.busy = true;
        ParcelFileDescriptor tun = null;
        TunReader reader = null;
        MiniTcp tcp = null;
        String result = "";
        try {
            tun = svc.establishTun();
            if (tun == null) throw new Exception("VPN not allowed (appops ACTIVATE_VPN)");
            tcp = new MiniTcp(new FileInputStream(tun.getFileDescriptor()), new FileOutputStream(tun.getFileDescriptor()), 5555);
            reader = new TunReader(tcp);
            Thread rt = new Thread(reader, "tunreader"); rt.setDaemon(true); rt.start();
            tcp.connect();
            AdbClient adb = new AdbClient(tcp);
            adb.connect(AdbKey.loadOrCreate(svc.getFilesDir()), 90000);
            String cmd;
            while ((cmd = Bridge.take()) != null) {
                result = adb.exec(cmd, 20000).trim();
                Log.i(TAG, "shell result: " + result);
                if (result.matches("(?s).*REMAPD \\d+.*")) RemapConfig.markEnsured(svc);
            }
            result = "OK " + result;
        } catch (Throwable e) {
            result = "FAIL " + e.getMessage();
            Log.e(TAG, "bridge failed", e);
        } finally {
            if (tcp != null) tcp.close();
            if (reader != null) reader.stop = true;
            try { if (tun != null) tun.close(); } catch (Exception ignored) {}
        }
        Bridge.lastResult = result;
        Bridge.lastResultAt = System.currentTimeMillis();
        Bridge.busy = false;
        Log.i(TAG, "done: " + result);
        svc.finished();
        if (Bridge.pending != null) Bridge.run(svc.getApplicationContext(), Bridge.take(), true);
    }
}
