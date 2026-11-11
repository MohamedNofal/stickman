package com.clean.stickman;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.FileDescriptor;

/**
 * Pumps packets from the tun fd into MiniTcp. Top-level class (no inner/anon classes). Waits with poll() and a short
 * timeout instead of a bare blocking read, so it notices stop and the tun can really be closed: a thread stuck in
 * read() keeps the interface alive, and after our VPN is revoked nothing ever arrives to wake it.
 */
public class TunReader implements Runnable {
    static final int POLL_MS = 200;
    final MiniTcp tcp;
    final FileDescriptor fd;
    volatile boolean stop;

    public TunReader(MiniTcp tcp, FileDescriptor fd) { this.tcp = tcp; this.fd = fd; }

    public void run() {
        byte[] buf = new byte[32767];
        StructPollfd p = new StructPollfd();
        p.fd = fd;
        p.events = (short) OsConstants.POLLIN;
        StructPollfd[] ps = { p };
        while (!stop) {
            int n;
            try {
                p.revents = 0;
                if (Os.poll(ps, POLL_MS) <= 0) continue;
                if ((p.revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0) break;
                n = tcp.in.read(buf);
            } catch (android.system.ErrnoException e) {
                if (e.errno == OsConstants.EINTR) continue;
                break;
            } catch (Exception e) { break; }
            if (n > 0) tcp.onPacket(buf, n);
        }
    }
}
