package com.clean.keyshift;

/** Pumps packets from the tun fd into MiniTcp. Top-level class (no inner/anon classes). */
public class TunReader implements Runnable {
    final MiniTcp tcp;
    volatile boolean stop;

    public TunReader(MiniTcp tcp) { this.tcp = tcp; }

    public void run() {
        byte[] buf = new byte[32767];
        while (!stop) {
            int n;
            try { n = tcp.in.read(buf); } catch (Exception e) { break; }
            if (n > 0) tcp.onPacket(buf, n);
        }
    }
}
