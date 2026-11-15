import com.clean.stickman.core.Apps;
import com.clean.stickman.daemon.Http;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * daemon/Http against a small server that can cut connections, the way Android cuts the shell user's connections
 * whenever the app's VPN bridge comes up or goes down in the middle of a download.
 */
public class HttpTest {
    static final String DATE = "Mon, 02 Nov 2026 10:00:00 GMT";

    /** One scripted answer per request. */
    static final class Reply {
        int code = 200;
        byte[] body = new byte[0];
        /** Bytes of the body to send before closing the connection; -1 = all of it. */
        int cutAfter = -1;
        String etag, lastModified, contentRange, location;
        boolean noLength;
        /** Answer a "Range: bytes=N-" request with body[N..] and the matching Content-Range (code 206). */
        boolean fromRange;
    }

    static final class Server implements AutoCloseable, Runnable {
        final ServerSocket ss;
        final List<Reply> script = new ArrayList<>();
        /** The request headers of each request seen, lower-cased. */
        final List<String> requests = new ArrayList<>();

        Server() throws Exception {
            ss = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(this, "http-test-server");
            t.setDaemon(true);
            t.start();
        }

        String url(String path) { return "http://127.0.0.1:" + ss.getLocalPort() + path; }

        @Override public void run() {
            while (!ss.isClosed()) {
                try (Socket s = ss.accept()) {
                    InputStream in = s.getInputStream();
                    ByteArrayOutputStream head = new ByteArrayOutputStream();
                    int state = 0;
                    for (int c; state < 4 && (c = in.read()) >= 0; ) {
                        head.write(c);
                        state = (c == '\r' && (state == 0 || state == 2)) || (c == '\n' && (state == 1 || state == 3)) ? state + 1 : (c == '\r' ? 1 : 0);
                    }
                    Reply r;
                    synchronized (this) {
                        requests.add(head.toString("ISO-8859-1").toLowerCase());
                        r = requests.size() <= script.size() ? script.get(requests.size() - 1) : null;
                    }
                    OutputStream o = s.getOutputStream();
                    if (r == null) { o.write("HTTP/1.1 500 no script\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("ISO-8859-1")); continue; }
                    byte[] body = r.body;
                    String contentRange = r.contentRange;
                    if (r.fromRange) {
                        String req = head.toString("ISO-8859-1").toLowerCase();
                        int at = req.indexOf("range: bytes=");
                        int from = at < 0 ? 0 : Integer.parseInt(req.substring(at + 13, req.indexOf('-', at + 13)));
                        body = Arrays.copyOfRange(r.body, from, r.body.length);
                        contentRange = "bytes " + from + "-" + (r.body.length - 1) + "/" + r.body.length;
                    }
                    StringBuilder h = new StringBuilder("HTTP/1.1 " + r.code + " X\r\nConnection: close\r\n");
                    if (!r.noLength) h.append("Content-Length: ").append(body.length).append("\r\n");
                    if (r.etag != null) h.append("ETag: ").append(r.etag).append("\r\n");
                    if (r.lastModified != null) h.append("Last-Modified: ").append(r.lastModified).append("\r\n");
                    if (contentRange != null) h.append("Content-Range: ").append(contentRange).append("\r\n");
                    if (r.location != null) h.append("Location: ").append(r.location).append("\r\n");
                    o.write(h.append("\r\n").toString().getBytes("ISO-8859-1"));
                    o.write(body, 0, r.cutAfter < 0 ? body.length : Math.min(r.cutAfter, body.length));
                    o.flush();
                    if (r.cutAfter >= 0) s.setSoLinger(true, 0);   // reset, like a connection the system tore down
                } catch (Exception e) {
                    // the client went away, or the server is closing
                }
            }
        }

        synchronized String request(int i) { return requests.get(i); }
        synchronized int count() { return requests.size(); }

        Reply add() { Reply r = new Reply(); synchronized (this) { script.add(r); } return r; }

        @Override public void close() throws Exception { ss.close(); }
    }

    static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    static File tmp() throws Exception {
        File f = File.createTempFile("stickman-http", ".bin");
        f.deleteOnExit();
        return f;
    }

    static { Http.RETRY_MS = 30; }

    public void testPlainDownload() throws Exception {
        try (Server sv = new Server()) {
            byte[] file = bytes(300_000, 1);
            Reply r = sv.add(); r.body = file; r.etag = "\"v1\""; r.lastModified = DATE;
            File out = tmp();
            final long[] seen = { 0, 0 };
            Http.Result res = Http.toFile(sv.url("/a.apk"), out, 1_000_000, true, total -> { seen[0] = total; return true; }, (done, total) -> { seen[1] = done; return true; });
            T.yes(res.ok(), "downloaded: " + res.why);
            T.eq(300_000L, res.bytes, "all bytes");
            T.yes(Arrays.equals(file, Files.readAllBytes(out.toPath())), "the same bytes");
            T.eq(300_000L, seen[0], "the gate was told the size");
            T.eq(300_000L, seen[1], "progress reached the end");
            T.eq(Apps.signature(300_000, DATE, "\"v1\""), res.tag, "the file's signature comes with it");
            T.yes(sv.request(0).contains("accept-encoding: identity"), "no transparent gzip: sizes must be the file's");
            T.yes(!sv.request(0).contains("range:"), "a fresh download asks for everything");
        }
    }

    public void testACutDownloadIsContinuedNotRestarted() throws Exception {
        try (Server sv = new Server()) {
            byte[] file = bytes(400_000, 2);
            Reply a = sv.add(); a.body = file; a.cutAfter = 150_000; a.etag = "\"v1\"";
            // a reset can drop bytes the client had not read yet, so the server serves from wherever the client asks
            Reply b = sv.add(); b.code = 206; b.body = file; b.fromRange = true; b.etag = "\"v1\"";
            File out = tmp();
            Http.Result res = Http.toFile(sv.url("/a.apk"), out, 1_000_000, true, null, null);
            T.yes(res.ok(), "finished after the cut: " + res.why);
            T.yes(Arrays.equals(file, Files.readAllBytes(out.toPath())), "the two parts make the file");
            T.eq(2, sv.count(), "two requests");
            String second = sv.request(1);
            T.yes(second.contains("if-range: \"v1\""), "the rest is asked for only if it is still the same file");
            String range = second.substring(second.indexOf("range: bytes=") + 13);
            long from = Long.parseLong(range.substring(0, range.indexOf('-')));
            T.yes(from > 0 && from <= 150_000, "continued from what reached the disk (" + from + "), not from zero");
        }
    }

    public void testSeveralCutsInARow() throws Exception {
        try (Server sv = new Server()) {
            byte[] file = bytes(500_000, 12);
            Reply a = sv.add(); a.body = file; a.cutAfter = 100_000; a.lastModified = DATE;   // a date serves as the proof too
            for (int i = 0; i < 3; i++) { Reply r = sv.add(); r.code = 206; r.body = file; r.fromRange = true; r.cutAfter = 90_000; r.lastModified = DATE; }
            Reply last = sv.add(); last.code = 206; last.body = file; last.fromRange = true; last.lastModified = DATE;
            File out = tmp();
            Http.Result res = Http.toFile(sv.url("/a.apk"), out, 1_000_000, true, null, null);
            T.yes(res.ok(), "finished after four cuts: " + res.why);
            T.yes(Arrays.equals(file, Files.readAllBytes(out.toPath())), "every part in its place");
            T.yes(sv.request(1).contains("if-range: " + DATE.toLowerCase()), "the date as the proof when there is no ETag");
        }
    }

    public void testAFileThatChangedInBetweenIsFetchedAfresh() throws Exception {
        try (Server sv = new Server()) {
            byte[] old = bytes(200_000, 3), fresh = bytes(220_000, 4);
            Reply a = sv.add(); a.body = old; a.cutAfter = 80_000; a.etag = "\"v1\"";
            Reply b = sv.add(); b.body = fresh; b.etag = "\"v2\"";   // If-Range did not match: the server sends the new file whole
            File out = tmp();
            Http.Result res = Http.toFile(sv.url("/a.apk"), out, 1_000_000, true, null, null);
            T.yes(res.ok(), "downloaded: " + res.why);
            T.yes(Arrays.equals(fresh, Files.readAllBytes(out.toPath())), "the new file, with nothing of the old one in front");
            T.eq(Apps.signature(220_000, null, "\"v2\""), res.tag, "and its signature");
        }
    }

    public void testWithoutAValidatorACutDownloadStartsOver() throws Exception {
        try (Server sv = new Server()) {
            byte[] file = bytes(120_000, 5);
            Reply a = sv.add(); a.body = file; a.cutAfter = 60_000;
            Reply b = sv.add(); b.body = file;
            File out = tmp();
            Http.Result res = Http.toFile(sv.url("/a.apk"), out, 1_000_000, true, null, null);
            T.yes(res.ok(), "downloaded: " + res.why);
            T.yes(Arrays.equals(file, Files.readAllBytes(out.toPath())), "whole");
            T.yes(!sv.request(1).contains("range:"), "nothing proves it is the same file: no partial request");
        }
    }

    public void testAWrongPartIsNotBuiltOn() throws Exception {
        try (Server sv = new Server()) {
            byte[] file = bytes(100_000, 6);
            Reply a = sv.add(); a.body = file; a.cutAfter = 40_000; a.etag = "\"v1\"";
            Reply b = sv.add(); b.code = 206; b.body = Arrays.copyOfRange(file, 0, 1000); b.contentRange = "bytes 0-999/100000";   // not what was asked
            Reply c = sv.add(); c.body = file;
            File out = tmp();
            Http.Result res = Http.toFile(sv.url("/a.apk"), out, 1_000_000, true, null, null);
            T.yes(res.ok(), "downloaded: " + res.why);
            T.yes(Arrays.equals(file, Files.readAllBytes(out.toPath())), "whole and in order");
        }
    }

    public void testMissingFileIsAnAnswerNotARetry() throws Exception {
        try (Server sv = new Server()) {
            Reply a = sv.add(); a.code = 404;
            File out = tmp();
            Http.Result res = Http.toFile(sv.url("/gone.apk"), out, 1_000_000, true, null, null);
            T.yes(!res.ok() && res.why.contains("404"), "says what the server said: " + res.why);
            T.eq(1, sv.count(), "asked once");
        }
    }

    public void testGateAndProgressCanStop() throws Exception {
        try (Server sv = new Server()) {
            byte[] file = bytes(300_000, 7);
            Reply a = sv.add(); a.body = file;
            File out = tmp();
            Http.Result res = Http.toFile(sv.url("/a.apk"), out, 1_000_000, true, total -> false, null);
            T.eq(Http.REFUSED, res.why, "the gate said no (not enough space)");
            Reply b = sv.add(); b.body = file;
            res = Http.toFile(sv.url("/a.apk"), out, 1_000_000, true, null, (done, total) -> false);
            T.eq(Http.STOPPED, res.why, "the caller no longer wants it");
            Reply c = sv.add(); c.body = file;
            res = Http.toFile(sv.url("/a.apk"), out, 100_000, true, null, null);
            T.yes(!res.ok() && res.why.contains("too large"), "bigger than any app: " + res.why);
        }
    }

    public void testPlainHttpIsRefusedUnlessAllowed() throws Exception {
        try (Server sv = new Server()) {
            Reply a = sv.add(); a.body = bytes(1000, 8);
            File out = tmp();
            Http.Result res = Http.toFile(sv.url("/a.apk"), out, 1_000_000, false, null, null);
            T.yes(!res.ok() && res.why.contains("insecure"), "only a developer's test server may be plain http: " + res.why);
            T.eq(null, Http.text(sv.url("/x"), false), "the same for pages");
        }
    }

    public void testRedirectsAreFollowedByHand() throws Exception {
        try (Server sv = new Server()) {
            byte[] file = bytes(90_000, 20);
            Reply a = sv.add(); a.code = 302; a.location = "/mirror/a.apk";               // relative, as download sites send them
            Reply b = sv.add(); b.code = 307; b.location = sv.url("/files/a.apk");        // absolute
            Reply c = sv.add(); c.body = file; c.etag = "\"v1\"";
            File out = tmp();
            Http.Result res = Http.toFile(sv.url("/a.apk"), out, 1_000_000, true, null, null);
            T.yes(res.ok(), "followed two redirects: " + res.why);
            T.yes(Arrays.equals(file, Files.readAllBytes(out.toPath())), "the file at the end");
            T.yes(sv.request(1).startsWith("get /mirror/a.apk "), "the relative target, on the same server");
            T.yes(sv.request(2).startsWith("get /files/a.apk "), "the absolute target");
        }
    }

    public void testARedirectToAnythingButTheAllowedSchemesIsRefusedAtOnce() throws Exception {
        try (Server sv = new Server()) {
            // on the stick this is an https link sent on to a plain http mirror: an answer, not something to retry
            Reply a = sv.add(); a.code = 302; a.location = "ftp://example.org/a.apk";
            File out = tmp();
            Http.Result res = Http.toFile(sv.url("/a.apk"), out, 1_000_000, true, null, null);
            T.yes(!res.ok() && res.why.contains("insecure"), "refused: " + res.why);
            T.eq(1, sv.count(), "not tried again");
            Reply b = sv.add(); b.code = 302; b.location = "ftp://example.org/x";
            T.eq(null, Http.text(sv.url("/x"), true), "the same for pages");
            T.eq(2, sv.count(), "asked once");
            Reply c = sv.add(); c.code = 302; c.location = "ftp://example.org/x";
            T.eq("", Http.signature(sv.url("/x"), true), "and for a look at a file");
        }
    }

    public void testEndlessRedirectsEnd() throws Exception {
        try (Server sv = new Server()) {
            for (int i = 0; i < 200; i++) { Reply r = sv.add(); r.code = 302; r.location = "/again"; }
            File out = tmp();
            Http.Result res = Http.toFile(sv.url("/a.apk"), out, 1_000_000, true, null, null);
            T.yes(!res.ok(), "gave up: " + res.why);
            T.yes(sv.count() <= 100, "after a bounded number of requests: " + sv.count());
        }
    }

    public void testAContinuedDownloadFollowsRedirectsToo() throws Exception {
        try (Server sv = new Server()) {
            byte[] file = bytes(200_000, 21);
            Reply a = sv.add(); a.code = 302; a.location = "/m/a.apk";
            Reply b = sv.add(); b.body = file; b.cutAfter = 70_000; b.etag = "\"v1\"";
            Reply c = sv.add(); c.code = 302; c.location = "/m2/a.apk";                  // another mirror for the second try
            Reply d = sv.add(); d.code = 206; d.body = file; d.fromRange = true; d.etag = "\"v1\"";
            File out = tmp();
            Http.Result res = Http.toFile(sv.url("/a.apk"), out, 1_000_000, true, null, null);
            T.yes(res.ok(), "finished: " + res.why);
            T.yes(Arrays.equals(file, Files.readAllBytes(out.toPath())), "whole");
            T.yes(sv.request(2).contains("range: bytes="), "the range is asked of the redirecting server");
            T.yes(sv.request(3).contains("range: bytes=") && sv.request(3).contains("if-range: \"v1\""), "and carried to where it points");
        }
    }

    public void testTextAndSignature() throws Exception {
        try (Server sv = new Server()) {
            Reply a = sv.add(); a.body = "{\"apps\":[]}".getBytes("UTF-8");
            T.eq("{\"apps\":[]}", Http.text(sv.url("/catalog.json"), true), "a page");
            Reply b = sv.add(); b.code = 404;
            T.eq(null, Http.text(sv.url("/none"), true), "no such page");
            T.eq(2, sv.count(), "a 404 is not retried");
            Reply c = sv.add(); c.code = 206; c.body = new byte[1]; c.contentRange = "bytes 0-0/300000"; c.etag = "\"v1\""; c.lastModified = DATE;
            String sig = Http.signature(sv.url("/a.apk"), true);
            T.eq(Apps.signature(300_000, DATE, "\"v1\""), sig, "the file's signature without downloading it");
            T.yes(sv.request(2).contains("range: bytes=0-0"), "one byte is asked for");
            // the invariant the update check rests on: what a download records equals what a later look gives
            Reply d = sv.add(); d.body = bytes(300_000, 9); d.etag = "\"v1\""; d.lastModified = DATE;
            T.eq(sig, Http.toFile(sv.url("/a.apk"), tmp(), 1_000_000, true, null, null).tag, "same file, same signature, however it was asked");
            Reply e = sv.add(); e.body = new byte[10];   // a server that ignores ranges and says nothing about the file
            T.eq(Apps.signature(10, null, null), Http.signature(sv.url("/b.apk"), true), "length alone");
        }
    }

    public void testRedirectRule() {
        // VideoLAN's redirector, 2026-11-15: 1 in 5 answers sent VLC to http://mirror.lchost.net/...
        T.eq(Http.FOLLOW, Http.hop("https", "https", false, false, 0), "https to https");
        T.eq(Http.REFUSE, Http.hop("https", "http", false, false, 0), "a typed link sent to http: refused");
        T.eq(Http.ASK_AGAIN, Http.hop("https", "http", false, true, 0), "a pinned file: ask the redirector again first");
        T.eq(Http.ASK_AGAIN, Http.hop("https", "http", false, true, Http.HTTPS_ASKS - 1), "still asking");
        T.eq(Http.FOLLOW, Http.hop("https", "http", false, true, Http.HTTPS_ASKS), "then the http mirror is taken: the checksum decides");
        T.eq(Http.FOLLOW, Http.hop("http", "http", false, true, 0), "already on an accepted http mirror: no point asking it again");
        T.eq(Http.REFUSE, Http.hop("https", "ftp", false, true, 0), "never anything but http(s)");
        T.eq(Http.REFUSE, Http.hop("https", "file", true, true, 0), "never a local file");
        T.eq(Http.FOLLOW, Http.hop("http", "http", true, false, 0), "a developer's test server");
    }
}
