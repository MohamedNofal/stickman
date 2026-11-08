package com.clean.stickman.daemon;

import com.clean.stickman.core.Actions;
import com.clean.stickman.core.Edition;
import com.clean.stickman.core.EnsureScript;
import com.clean.stickman.core.Update;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * GitHub edition self-update (see core/Update). Every 6 h stickd asks GitHub for the latest release; a newer one
 * is downloaded to /data/local/tmp/stickman/update.apk and, with update.auto on, installed with pm install -r the
 * next time the TV is asleep and nothing plays. "Update now" (Settings) checks, downloads and installs at once.
 * After pm install, Android's package-replaced broadcast makes the app re-run ensure, which restarts both daemons
 * from the new APK. The Appstore edition never starts this.
 */
final class Updater {
    private final Stickd d;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stickd-update");
        t.setDaemon(true);
        return t;
    });
    private long lastCheck;
    /** The release whose APK sits in update.apk, verified (null = none). */
    private volatile Update.Release ready;
    private volatile String current;

    Updater(Stickd d) { this.d = d; }

    void install() {
        if (Edition.STORE) return;
        d.register(Actions.UPDATE_CHECK, a -> timer.execute(() -> safe(() -> check(true))));
        d.register(Actions.UPDATE_NOW, a -> timer.execute(() -> safe(this::updateNow)));
        timer.scheduleWithFixedDelay(() -> safe(this::tick), 3, 5, TimeUnit.MINUTES);
    }

    private void safe(Runnable r) {
        try { r.run(); } catch (Throwable t) { log("error", latestVersion(), String.valueOf(t)); }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (now - lastCheck >= Update.CHECK_EVERY_MS || lastCheck > now) check(false);
        Update.Release r = ready;
        if (r != null && d.conf().updateAuto && tvIdle()) installApk(r, "sleep");
    }

    /** The TV is asleep (or dozing) and nothing is playing: an install can't interrupt anyone. */
    private boolean tvIdle() {
        if (!AutoKill.isAsleep(Sh.run("dumpsys power | grep -m1 mWakefulness="))) return false;
        return Sh.run("dumpsys media_session | grep -c 'state=PlaybackState {state=3'").trim().equals("0");
    }

    private String installed() {
        String v = Update.installedVersion(Sh.run("dumpsys package " + EnsureScript.PKG + " | grep -m1 versionName="));
        current = v;
        return v;
    }

    private String latestVersion() { Update.Release r = ready; return r == null ? null : r.version; }

    private void log(String state, String latest, String msg) { Sh.log(Update.status(state, current, latest, msg)); }

    /** Asks GitHub; downloads a newer release when auto-update is on (or when asked to update now). */
    private Update.Release check(boolean manual) {
        lastCheck = System.currentTimeMillis();
        String cur = installed();
        String[] src = source();
        Update.Release r = Update.parse(httpText(src[0]), src[1]);
        if (r == null) { log("error", null, "GitHub didn't answer; will try again later"); return null; }
        if (cur == null || !Update.newer(r.version, cur)) {
            ready = null;
            new File(Update.FILE).delete();
            log("uptodate", r.version, null);
            return null;
        }
        if (d.conf().updateAuto || manual) download(r);
        else log("available", r.version, null);
        return r;
    }

    /** {release-JSON url, accepted asset prefix}: GitHub, or a developer test server (see Update.TEST_FILE). */
    static String[] source() {
        String[] t = Update.testSource(Stickd.read(new File(Update.TEST_FILE)));
        return t != null ? t : new String[] { Update.API, Update.URL_PREFIX };
    }

    private void updateNow() {
        Update.Release r = check(true);
        if (r != null && ready != null && ready.version.equals(r.version)) installApk(ready, "now");
    }

    private void download(Update.Release r) {
        Update.Release have = ready;
        if (have != null && have.version.equals(r.version) && new File(Update.FILE).length() > 0) {
            log("ready", r.version, null);
            return;
        }
        log("downloading", r.version, null);
        File part = new File(Update.FILE + ".part"), apk = new File(Update.FILE);
        part.delete();
        long n = httpToFile(r.url, part);
        byte[] head = new byte[4];
        try (InputStream in = new java.io.FileInputStream(part)) { if (in.read(head) != 4) head = null; } catch (Exception e) { head = null; }
        if (!Update.plausibleApk(n, r.size, head) || !manifestOk(part)) {
            part.delete();
            log("error", r.version, "the download wasn't a Stickman APK");
            return;
        }
        apk.delete();
        if (!part.renameTo(apk)) { log("error", r.version, "couldn't save the download"); return; }
        apk.setReadable(true, false);
        ready = r;
        log("ready", r.version, null);
    }

    private static boolean manifestOk(File f) {
        try (ZipFile z = new ZipFile(f)) {
            ZipEntry e = z.getEntry("AndroidManifest.xml");
            if (e == null) return false;
            try (InputStream in = z.getInputStream(e)) { return Update.manifestNames(readAll(in), EnsureScript.PKG); }
        } catch (Exception e) {
            return false;
        }
    }

    private void installApk(Update.Release r, String why) {
        log("installing", r.version, why);
        // pm verifies the signature against the installed app; a mismatch fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE
        String out = Sh.run("pm install -r " + Update.FILE + " 2>&1", 180_000);
        ready = null;
        new File(Update.FILE).delete();
        if (out.contains("Success")) {
            current = r.version;
            log("installed", r.version, null);
            // the user just pressed Update now: bring Stickman back (it shows what's new); a sleep install stays quiet
            if ("now".equals(why)) Sh.run("am start -n " + EnsureScript.PKG + "/com.clean.stickman.MainActivity >/dev/null 2>&1");
        } else {
            log("error", r.version, "install refused: " + out.trim());
        }
    }

    // ---- http ----
    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Stickman-updater");
        c.setRequestProperty("Accept", url.startsWith("https://api.github.com/") ? "application/vnd.github+json" : "application/octet-stream");
        return c;
    }

    private static String httpText(String url) {
        HttpURLConnection c = null;
        try {
            c = open(url);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) { return new String(readAll(in), StandardCharsets.UTF_8); }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Bytes written, or -1. GitHub redirects release downloads to its object store over https. */
    private static long httpToFile(String url, File out) {
        HttpURLConnection c = null;
        try {
            c = open(url);
            int code = c.getResponseCode();
            for (int hops = 0; (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && hops < 5; hops++) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || !(loc.startsWith("https://") || Update.testSource(Stickd.read(new File(Update.TEST_FILE))) != null)) return -1;
                c = open(loc);
                code = c.getResponseCode();
            }
            if (code != 200) return -1;
            long n = 0;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(out)) {
                for (int k; (k = in.read(buf)) > 0; ) {
                    o.write(buf, 0, k);
                    n += k;
                    if (n > Update.MAX_APK) return -1;
                }
            }
            return n;
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        for (int k; (k = in.read(buf)) > 0; ) b.write(buf, 0, k);
        return b.toByteArray();
    }
}
