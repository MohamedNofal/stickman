package com.clean.stickman.daemon;

import com.clean.stickman.core.Apps;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Downloads for the app installer (plain Java: runs under app_process on every Fire OS; host-tested against a server
 * that cuts connections).
 *
 * The app's bridge is a VPN that includes the shell user: whenever it comes up or goes down (to deliver the very
 * "install this" that got us here, or any other page's command), Android cuts shell's open connections. A 90 MB
 * download would never finish if each cut meant starting over, so a broken download is continued from where it
 * stopped (HTTP Range, tied to the file's ETag / date so a file that changed in between is fetched afresh).
 *
 * Redirects are followed by hand, so that the rule is the same on every Android version: only to https, unless the
 * caller allows plain http for a developer's test server, or the file is pinned. A pinned file is one whose SHA-256
 * the caller knows (a catalogue file): it is checked before anything is installed, so where the bytes came from
 * does not matter, and mirror networks do send some downloads to plain-http mirrors (VideoLAN's sent 1 in 5 of
 * them to one on 2026-11-15, so VLC failed to install). Even then https is preferred: the redirector is asked again
 * (it picks another mirror each time) before an http mirror is accepted. Anything else that is sent on to http is
 * refused, not silently followed.
 */
public final class Http {
    private Http() {}

    static final int TRIES = 10, MAX_HOPS = 6;
    /** Pause before another try; not final so the host tests need not wait. */
    public static long RETRY_MS = 2500;
    static final int MAX_TEXT = 4 * 1024 * 1024;

    /** Told the size once it is known (-1 = the server did not say); false stops the download. */
    public interface Gate { boolean start(long total); }

    /** Called as bytes arrive; false stops the download. */
    public interface Progress { boolean at(long done, long total); }

    /** What a download ended with. bytes &lt; 0: failed (why says so). tag: the file's signature, as {@link #signature} gives it. */
    public static final class Result {
        public final long bytes;
        public final String tag, why;
        Result(long bytes, String tag, String why) { this.bytes = bytes; this.tag = tag; this.why = why; }
        public boolean ok() { return bytes >= 0; }
    }

    public static final String STOPPED = "stopped", REFUSED = "refused";
    static final String INSECURE = "the site sent it on to an insecure (http) address";

    /** What to do with a redirect: follow it, ask the same address again (another mirror may come up), or refuse. */
    public static final int FOLLOW = 0, ASK_AGAIN = 1, REFUSE = 2;
    /** How many times a redirector is asked again for an https mirror before a pinned file takes an http one. */
    public static final int HTTPS_ASKS = 3;

    /**
     * The redirect rule (pure). from / to = the schemes of the address asked and of the redirect's target; asks = how
     * often this redirector was asked again already.
     */
    public static int hop(String from, String to, boolean allowHttp, boolean pinned, int asks) {
        if ("https".equals(to) || (allowHttp && "http".equals(to))) return FOLLOW;
        if (!"http".equals(to) || !pinned) return REFUSE;
        // already on plain http (an accepted mirror): asking it again would not help
        return "https".equals(from) && asks < HTTPS_ASKS ? ASK_AGAIN : FOLLOW;
    }

    /** A link, or a redirect on the way, that is not https: an answer, not something to try again. */
    static final class Insecure extends IOException {
        Insecure() { super(INSECURE); }
    }

    private static boolean allowed(URL u, boolean allowHttp) {
        return "https".equals(u.getProtocol()) || (allowHttp && "http".equals(u.getProtocol()));
    }

    /**
     * GET url and return the connection of the final answer (its status is not a redirect). range / ifRange may be
     * null; raw = ask for the bytes as they are stored (no transparent gzip: sizes and ranges must be the file's).
     */
    private static HttpURLConnection get(String url, String accept, boolean raw, String range, String ifRange, boolean allowHttp) throws IOException {
        return get(url, accept, raw, range, ifRange, allowHttp, false);
    }

    /** As above; pinned = the caller checks the file's SHA-256 before using it (see the class comment). */
    private static HttpURLConnection get(String url, String accept, boolean raw, String range, String ifRange, boolean allowHttp, boolean pinned) throws IOException {
        String u = url;
        int asks = 0;
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            URL parsed = new URL(u);
            // the address the caller gave must be allowed as it is; every later one passed hop() on the way here
            if (hop == 0 && !allowed(parsed, allowHttp)) throw new Insecure();
            HttpURLConnection c = (HttpURLConnection) parsed.openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Stickman-apps");
            c.setRequestProperty("Accept", accept);
            if (raw) c.setRequestProperty("Accept-Encoding", "identity");
            if (range != null) {
                c.setRequestProperty("Range", range);
                if (ifRange != null) c.setRequestProperty("If-Range", ifRange);
            }
            int code = c.getResponseCode();
            if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) return c;
            String to = c.getHeaderField("Location");
            c.disconnect();
            if (to == null) throw new IOException("a redirect without a target");
            URL next = new URL(parsed, to);       // a relative target is relative to where we are
            int d = hop(parsed.getProtocol(), next.getProtocol(), allowHttp, pinned, asks);
            if (d == REFUSE) throw new Insecure();
            if (d == ASK_AGAIN) { asks++; hop--; continue; }   // the same address again; asks is bounded, so is this
            u = next.toString();
        }
        throw new IOException("too many redirects");
    }

    private static void pause() {
        try { Thread.sleep(RETRY_MS); } catch (InterruptedException ignored) { }
    }

    /** A page or JSON answer as text, or null. Retried: see the class comment. */
    public static String text(String url, boolean allowHttp) {
        for (int i = 0; i < 4; i++) {
            HttpURLConnection c = null;
            try {
                c = get(url, url.startsWith("https://api.github.com/") ? "application/vnd.github+json" : "*/*", false, null, null, allowHttp);
                int code = c.getResponseCode();
                if (code == 404 || code == 403 || code == 410) return null;   // an answer, just not the one hoped for
                if (code == 200) {
                    try (InputStream in = c.getInputStream()) {
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        byte[] buf = new byte[16 * 1024];
                        for (int k; (k = in.read(buf)) > 0; ) {
                            b.write(buf, 0, k);
                            if (b.size() > MAX_TEXT) return null;
                        }
                        return new String(b.toByteArray(), StandardCharsets.UTF_8);
                    }
                }
            } catch (Insecure e) {
                return null;
            } catch (Exception e) {
                // connection cut or no network: try again
            } finally {
                if (c != null) c.disconnect();
            }
            if (i < 3) pause();
        }
        return null;
    }

    /**
     * What identifies the file behind a link right now, without downloading it (see Apps.signature). Two different
     * answers mean the publisher put a new file there. "" when the server tells nothing usable.
     */
    public static String signature(String url, boolean allowHttp) {
        for (int i = 0; i < 3; i++) {
            HttpURLConnection c = null;
            try {
                c = get(url, "application/octet-stream", true, "bytes=0-0", null, allowHttp);
                int code = c.getResponseCode();
                if (code == 200 || code == 206) return sig(c, code);
                if (code >= 400 && code < 500) return "";
            } catch (Insecure e) {
                return "";
            } catch (Exception e) {
                // retry
            } finally {
                if (c != null) c.disconnect();
            }
            pause();
        }
        return "";
    }

    private static String sig(HttpURLConnection c, int code) {
        long len = code == 206 ? Apps.rangeTotal(c.getHeaderField("Content-Range")) : longHeader(c, "Content-Length");
        String e = c.getHeaderField("ETag");
        return Apps.signature(len, c.getHeaderField("Last-Modified"), e != null && e.length() < 200 ? e : null);
    }

    private static long longHeader(HttpURLConnection c, String name) {
        try { String h = c.getHeaderField(name); return h == null ? -1 : Long.parseLong(h.trim()); } catch (NumberFormatException e) { return -1; }
    }

    /** A strong ETag, else the Last-Modified date: what a continued download proves "the same file as before" with. */
    private static String validator(HttpURLConnection c) {
        String e = c.getHeaderField("ETag");
        if (e != null && !e.startsWith("W/") && e.length() < 200) return e;
        return c.getHeaderField("Last-Modified");
    }

    /** Downloads url into out (replacing it), continuing after cut connections. */
    public static Result toFile(String url, File out, long maxBytes, boolean allowHttp, Gate gate, Progress progress) {
        return toFile(url, out, maxBytes, allowHttp, false, gate, progress);
    }

    /** As above; pinned = the caller checks the file's SHA-256 before it is used, so an http mirror is acceptable. */
    public static Result toFile(String url, File out, long maxBytes, boolean allowHttp, boolean pinned, Gate gate, Progress progress) {
        out.delete();
        long have = 0;
        String validator = null, tag = "";
        boolean gated = false;
        int stalls = 0;
        while (stalls < TRIES) {
            HttpURLConnection c = null;
            long before = have;
            try {
                if (have > 0 && validator == null) have = 0;   // nothing to prove it is the same file: start over
                c = get(url, "application/octet-stream", true, have > 0 ? "bytes=" + have + "-" : null, have > 0 ? validator : null, allowHttp, pinned);
                int code = c.getResponseCode();
                if (code == 416) { have = 0; out.delete(); stalls++; continue; }
                if (code >= 400 && code < 500) return new Result(-1, "", "the server answered " + code + (code == 404 ? " (no such file)" : ""));
                if (code != 200 && code != 206) { stalls++; pause(); continue; }
                boolean append = code == 206 && have > 0;
                if (append && Apps.rangeStart(c.getHeaderField("Content-Range")) != have) {
                    // not the part that was asked for: nothing to build on, fetch the whole file
                    have = 0;
                    out.delete();
                    validator = null;
                    stalls++;
                    continue;
                }
                if (!append) have = 0;
                long body = longHeader(c, "Content-Length");
                long total = body < 0 ? -1 : have + body;
                if (total > maxBytes) return new Result(-1, "", "the file is too large to be an app");
                if (!append) { validator = validator(c); tag = sig(c, 200); }
                if (!gated) {
                    gated = true;
                    if (gate != null && !gate.start(total)) return new Result(-1, "", REFUSED);
                }
                byte[] buf = new byte[64 * 1024];
                try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(out, append)) {
                    for (int k; (k = in.read(buf)) > 0; ) {
                        o.write(buf, 0, k);
                        have += k;
                        if (have > maxBytes) return new Result(-1, "", "the file is too large to be an app");
                        if (progress != null && !progress.at(have, total)) return new Result(-1, "", STOPPED);
                    }
                }
                if (total < 0 || have >= total) return new Result(have, tag, null);
                // the server closed early: go on from here
            } catch (Insecure e) {
                return new Result(-1, "", INSECURE);
            } catch (Exception e) {
                have = out.length();   // what reached the disk is what counts
            } finally {
                if (c != null) c.disconnect();
            }
            if (have > before) stalls = 0; else stalls++;   // only tries that brought nothing count against the limit
            pause();
        }
        return new Result(-1, "", "the download kept breaking off");
    }
}
