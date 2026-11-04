package com.clean.shellbridge;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Minimal single-connection TCP *client* that speaks raw IPv4/TCP over a VPN tun fd.
 *
 * We are 10.99.0.2 (an address assigned to NO interface), talking to 10.99.0.1:5555
 * (the tun's own address, where adbd listens on [::]). Because our source address is
 * not local, Fire OS adbd's "bind(peer) succeeds => local => deny" check passes.
 *
 * Deliberately simple: stop-and-wait sending, in-order-only receiving (anything out of
 * order gets a duplicate ACK and adbd retransmits). Plenty for an adb control channel.
 */
public class MiniTcp {
    static final int SRC_IP = 0x0A630002; // 10.99.0.2  (us, fake)
    static final int DST_IP = 0x0A630001; // 10.99.0.1  (tun address -> adbd)
    static final int MSS = 1400;

    final FileInputStream in;
    final FileOutputStream out;
    final int srcPort, dstPort;

    final Object lock = new Object();
    int sndNxt, sndUna, rcvNxt;
    boolean established, finRcvd, rst;
    int ipId = 1;

    byte[] rx = new byte[65536];
    int rxLen = 0;

    public MiniTcp(FileInputStream in, FileOutputStream out, int dstPort) {
        this.in = in; this.out = out; this.dstPort = dstPort;
        this.srcPort = 40000 + new Random().nextInt(20000);
    }

    // ---------- packet building ----------
    static int csum(byte[] b, int off, int len, int init) {
        long s = init & 0xffffffffL;
        int i = off;
        while (len > 1) { s += ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff); i += 2; len -= 2; }
        if (len > 0) s += (b[i] & 0xff) << 8;
        while ((s >> 16) != 0) s = (s & 0xffff) + (s >> 16);
        return (int) (~s & 0xffff);
    }

    static void put16(byte[] b, int o, int v) { b[o] = (byte) (v >> 8); b[o + 1] = (byte) v; }
    static void put32(byte[] b, int o, int v) { b[o] = (byte) (v >> 24); b[o + 1] = (byte) (v >> 16); b[o + 2] = (byte) (v >> 8); b[o + 3] = (byte) v; }
    static int get16(byte[] b, int o) { return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); }
    static int get32(byte[] b, int o) { return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff); }

    static final int FIN = 1, SYN = 2, RST = 4, PSH = 8, ACK = 16;

    void sendSeg(int flags, int seq, byte[] data, int off, int len) throws IOException {
        boolean syn = (flags & SYN) != 0;
        int tcpHdr = syn ? 24 : 20;
        int total = 20 + tcpHdr + len;
        byte[] p = new byte[total];
        // IPv4
        p[0] = 0x45; put16(p, 2, total); put16(p, 4, ipId++); put16(p, 6, 0x4000);
        p[8] = 64; p[9] = 6; put32(p, 12, SRC_IP); put32(p, 16, DST_IP);
        put16(p, 10, csum(p, 0, 20, 0));
        // TCP
        int t = 20;
        put16(p, t, srcPort); put16(p, t + 2, dstPort);
        put32(p, t + 4, seq); put32(p, t + 8, (flags & ACK) != 0 ? rcvNxt : 0);
        p[t + 12] = (byte) ((tcpHdr / 4) << 4); p[t + 13] = (byte) flags;
        put16(p, t + 14, 65535);
        if (syn) { p[t + 20] = 2; p[t + 21] = 4; put16(p, t + 22, MSS); }
        if (len > 0) System.arraycopy(data, off, p, t + tcpHdr, len);
        // pseudo-header sum
        int ph = (SRC_IP >>> 16) + (SRC_IP & 0xffff) + (DST_IP >>> 16) + (DST_IP & 0xffff) + 6 + (tcpHdr + len);
        put16(p, t + 16, csum(p, t, tcpHdr + len, ph));
        synchronized (out) { out.write(p, 0, total); }
    }

    // ---------- packet receiving (called by TunReader thread) ----------
    void onPacket(byte[] p, int n) {
        if (n < 40 || (p[0] & 0xf0) != 0x40 || p[9] != 6) return;
        if (get32(p, 12) != DST_IP || get32(p, 16) != SRC_IP) return;
        int ihl = (p[0] & 0x0f) * 4;
        int t = ihl;
        if (get16(p, t) != dstPort || get16(p, t + 2) != srcPort) return;
        int seq = get32(p, t + 4), ack = get32(p, t + 8);
        int doff = ((p[t + 12] & 0xff) >> 4) * 4;
        int flags = p[t + 13] & 0xff;
        int dataOff = t + doff;
        int dlen = Math.min(get16(p, 2), n) - dataOff;
        boolean needAck = false;
        synchronized (lock) {
            if ((flags & RST) != 0) { rst = true; lock.notifyAll(); return; }
            if (!established) {
                if ((flags & (SYN | ACK)) == (SYN | ACK) && ack == sndNxt) {
                    rcvNxt = seq + 1; sndUna = ack; established = true; needAck = true;
                }
            } else {
                if ((flags & ACK) != 0 && ack - sndUna > 0) sndUna = ack;
                if (dlen > 0) {
                    if (seq == rcvNxt) {
                        if (rxLen + dlen > rx.length) {
                            byte[] nb = new byte[Math.max(rx.length * 2, rxLen + dlen)];
                            System.arraycopy(rx, 0, nb, 0, rxLen); rx = nb;
                        }
                        System.arraycopy(p, dataOff, rx, rxLen, dlen);
                        rxLen += dlen; rcvNxt += dlen;
                    }
                    needAck = true;
                }
                if ((flags & FIN) != 0 && seq + Math.max(dlen, 0) == rcvNxt) {
                    rcvNxt += 1; finRcvd = true; needAck = true;
                }
            }
            lock.notifyAll();
        }
        if (needAck) { try { sendSeg(ACK, sndNxt, null, 0, 0); } catch (IOException ignored) {} }
    }

    // ---------- public API ----------
    public void connect() throws IOException {
        int iss = new Random().nextInt();
        synchronized (lock) { sndNxt = iss + 1; sndUna = iss; }
        for (int attempt = 0; attempt < 6; attempt++) {
            sendSeg(SYN, iss, null, 0, 0);
            synchronized (lock) {
                long end = android.os.SystemClock.elapsedRealtime() + 700;
                while (!established && !rst) {
                    long w = end - android.os.SystemClock.elapsedRealtime();
                    if (w <= 0) break;
                    try { lock.wait(w); } catch (InterruptedException e) { throw new IOException(e); }
                }
                if (rst) throw new IOException("RST during connect (adbd refused)");
                if (established) return;
            }
        }
        throw new IOException("no SYN-ACK from 10.99.0.1:" + dstPort);
    }

    public void write(byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            int n = Math.min(len, MSS);
            int seq;
            synchronized (lock) { seq = sndNxt; sndNxt += n; }
            boolean acked = false;
            for (int attempt = 0; attempt < 12 && !acked; attempt++) {
                sendSeg(PSH | ACK, seq, b, off, n);
                synchronized (lock) {
                    long end = android.os.SystemClock.elapsedRealtime() + 250 + attempt * 100;
                    while (sndUna - (seq + n) < 0 && !rst) {
                        long w = end - android.os.SystemClock.elapsedRealtime();
                        if (w <= 0) break;
                        try { lock.wait(w); } catch (InterruptedException e) { throw new IOException(e); }
                    }
                    if (rst) throw new IOException("connection reset");
                    acked = sndUna - (seq + n) >= 0;
                }
            }
            if (!acked) throw new IOException("write not acked");
            off += n; len -= n;
        }
    }

    /** Read exactly len bytes, or throw on timeout / EOF. */
    public void readFully(byte[] b, int off, int len, long timeoutMs) throws IOException {
        long end = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (lock) {
            while (rxLen < len) {
                if (rst) throw new IOException("connection reset");
                if (finRcvd) throw new IOException("EOF");
                long w = end - android.os.SystemClock.elapsedRealtime();
                if (w <= 0) throw new IOException("read timeout");
                try { lock.wait(w); } catch (InterruptedException e) { throw new IOException(e); }
            }
            System.arraycopy(rx, 0, b, off, len);
            System.arraycopy(rx, len, rx, 0, rxLen - len);
            rxLen -= len;
        }
    }

    public void close() {
        try {
            int seq; synchronized (lock) { seq = sndNxt; sndNxt += 1; }
            sendSeg(FIN | ACK, seq, null, 0, 0);
            Thread.sleep(150);
            sendSeg(RST | ACK, seq + 1, null, 0, 0);
        } catch (Exception ignored) {}
    }
}
