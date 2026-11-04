package com.clean.stickman;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Tiny adb wire-protocol client over MiniTcp: CNXN + AUTH, then one-shot shell: commands. */
public class AdbClient {
    static final String TAG = "Stickman";
    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    static final int VERSION = 0x01000000, MAXDATA = 4096;

    final MiniTcp tcp;
    int nextLocalId = 1;

    int mCmd, mA0, mA1;
    byte[] mData;

    public AdbClient(MiniTcp tcp) { this.tcp = tcp; }

    void send(int cmd, int a0, int a1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xff;
        byte[] h = new byte[24 + len];
        le(h, 0, cmd); le(h, 4, a0); le(h, 8, a1); le(h, 12, len); le(h, 16, sum); le(h, 20, cmd ^ 0xffffffff);
        if (len > 0) System.arraycopy(data, 0, h, 24, len);
        tcp.write(h, 0, h.length);
    }

    void recv(long timeoutMs) throws IOException {
        byte[] h = new byte[24];
        tcp.readFully(h, 0, 24, timeoutMs);
        mCmd = gle(h, 0); mA0 = gle(h, 4); mA1 = gle(h, 8);
        int len = gle(h, 12);
        if (len < 0 || len > 1 << 20) throw new IOException("bad adb length " + len);
        mData = new byte[len];
        if (len > 0) tcp.readFully(mData, 0, len, timeoutMs);
    }

    static void le(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24); }
    static int gle(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24); }

    /** Returns normally once adbd sent CNXN (authorized). Waits up to authWaitMs for the TV prompt. */
    public void connect(AdbKey key, long authWaitMs) throws Exception {
        send(CNXN, VERSION, MAXDATA, "host::stickman\0".getBytes("UTF-8"));
        boolean sentSig = false, sentPub = false;
        while (true) {
            recv(sentPub ? authWaitMs : 5000);
            if (mCmd == CNXN) { Log.i(TAG, "adb: connected: " + new String(mData, "UTF-8").trim()); return; }
            if (mCmd != AUTH || mA0 != 1) throw new IOException("unexpected adb msg " + Integer.toHexString(mCmd));
            if (!sentSig) {
                send(AUTH, 2, 0, key.sign(mData)); sentSig = true;
                Log.i(TAG, "adb: sent signature");
            } else if (!sentPub) {
                send(AUTH, 3, 0, key.publicKeyPayload("stickman@firetv")); sentPub = true;
                Log.w(TAG, "adb: key not yet trusted -> sent public key; ACCEPT THE PROMPT ON THE TV");
            } else {
                throw new IOException("adb auth rejected");
            }
        }
    }

    /** Runs `shell:<cmd>` and returns its combined output (until adbd closes the stream). */
    public String exec(String cmd, long timeoutMs) throws Exception {
        int local = nextLocalId++;
        send(OPEN, local, 0, ("shell:" + cmd + "\0").getBytes("UTF-8"));
        int remote = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long end = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        while (true) {
            long w = end - android.os.SystemClock.elapsedRealtime();
            if (w <= 0) throw new IOException("exec timeout");
            recv(w);
            if (mA1 != local && mCmd != CNXN) continue;
            if (mCmd == OKAY) { remote = mA0; }
            else if (mCmd == WRTE) { out.write(mData); send(OKAY, local, mA0, null); }
            else if (mCmd == CLSE) { if (remote != 0) send(CLSE, local, remote, null); break; }
        }
        return out.toString("UTF-8");
    }
}
