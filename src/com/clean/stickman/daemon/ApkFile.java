package com.clean.stickman.daemon;

import com.clean.stickman.core.Apps;
import com.clean.stickman.core.Axml;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What a downloaded file really is, before anything is installed (plain Java, host-tested). A link, a file name and a
 * catalogue entry are claims; the file's own manifest says which app and version it is, and for a catalogue file the
 * SHA-256 says whether it is the very file that was vetted.
 */
public final class ApkFile {
    private ApkFile() {}

    static final int MAX_MANIFEST = 8 * 1024 * 1024;

    /** The outcome of {@link #check}: info is set when error is null. */
    public static final class Check {
        public Axml.Info info;
        public String error;
        /** A valid app, but not the package that was expected. */
        public boolean otherApp;
    }

    public static String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            for (int k; (k = in.read(buf)) > 0; ) md.update(buf, 0, k);
            return Apps.hex(md.digest());
        } catch (Exception e) {
            return "";
        }
    }

    /** The package and version code the file's manifest declares, or null when it is not an APK. */
    public static Axml.Info info(File f) {
        try (ZipFile z = new ZipFile(f)) {
            ZipEntry e = z.getEntry("AndroidManifest.xml");
            if (e == null || e.getSize() > MAX_MANIFEST) return null;
            try (InputStream in = z.getInputStream(e)) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                for (int k; (k = in.read(buf)) > 0; ) {
                    b.write(buf, 0, k);
                    if (b.size() > MAX_MANIFEST) return null;
                }
                return Axml.read(b.toByteArray());
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Checks a finished download: plausible size and zip magic, the catalogue's checksum when there is one (sha,
     * lower-case hex), a readable manifest, and the expected package when one is expected (pkg).
     */
    public static Check check(File f, String sha, String pkg) {
        Check c = new Check();
        byte[] head = new byte[4];
        try (InputStream in = new FileInputStream(f)) {
            if (in.read(head) != 4) head = null;
        } catch (Exception e) {
            head = null;
        }
        c.error = Apps.looksLikeApk(f.length(), head);
        if (c.error != null) return c;
        if (sha != null && !sha.equals(sha256(f))) { c.error = Apps.MISMATCH; return c; }
        c.info = info(f);
        if (c.info == null) { c.error = Apps.NOT_AN_APP; return c; }
        if (pkg != null && !pkg.equals(c.info.pkg)) { c.error = Apps.otherApp(c.info.pkg); c.otherApp = true; }
        return c;
    }
}
