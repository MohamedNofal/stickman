import com.clean.stickman.core.Apps;
import com.clean.stickman.daemon.ApkFile;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ApkFileTest {
    /** A zip with the real Moonlight manifest (com.limelight, version code 315) and padding up to app size. */
    static File apk(String manifestFixture) throws Exception {
        File f = File.createTempFile("stickman-apk", ".apk");
        f.deleteOnExit();
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            if (manifestFixture != null) {
                z.putNextEntry(new ZipEntry("AndroidManifest.xml"));
                z.write(Files.readAllBytes(Paths.get("tests/fixtures/axml/" + manifestFixture)));
                z.closeEntry();
            }
            z.setLevel(0);
            z.putNextEntry(new ZipEntry("classes.dex"));
            z.write(new byte[(int) Apps.MIN_APK + 1000]);
            z.closeEntry();
        }
        return f;
    }

    public void testARealManifestInsideAZip() throws Exception {
        File f = apk("real-utf16.axml");
        ApkFile.Check c = ApkFile.check(f, null, null);
        T.eq(null, c.error, "a plausible app");
        T.eq("com.limelight", c.info.pkg, "package from the file itself");
        T.eq(315L, c.info.versionCode, "version code from the file itself");
    }

    public void testCatalogueChecksum() throws Exception {
        File f = apk("real-utf16.axml");
        String sha = Apps.hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath())));
        T.eq(sha, ApkFile.sha256(f), "streamed digest equals the whole-file digest");
        T.eq(null, ApkFile.check(f, sha, "com.limelight").error, "the vetted file");
        T.eq(Apps.MISMATCH, ApkFile.check(f, sha.replace(sha.charAt(0), sha.charAt(0) == 'a' ? 'b' : 'a'), "com.limelight").error, "one digit off");
        T.eq("", ApkFile.sha256(new File("/nonexistent/x.apk")), "no file: no digest (never equal to a real one)");
    }

    public void testAnotherAppThanExpected() throws Exception {
        ApkFile.Check c = ApkFile.check(apk("real-utf16.axml"), null, "org.xbmc.kodi");
        T.yes(c.otherApp, "flagged so the link is not fetched again");
        T.yes(c.error.contains("com.limelight"), "says which app it is");
    }

    public void testNotAnApp() throws Exception {
        T.eq(Apps.NOT_AN_APP, ApkFile.check(apk(null), null, null).error, "a zip without a manifest");
        File html = File.createTempFile("stickman-page", ".apk");
        html.deleteOnExit();
        try (FileOutputStream o = new FileOutputStream(html)) {
            byte[] page = "<!DOCTYPE html><html><body>Download starting…</body></html>".getBytes("UTF-8");
            for (int i = 0; i < 2000; i++) o.write(page);
        }
        T.eq(Apps.NOT_AN_APP, ApkFile.check(html, null, null).error, "a web page saved under an .apk name");
        File tiny = File.createTempFile("stickman-tiny", ".apk");
        tiny.deleteOnExit();
        T.eq(Apps.NOT_AN_APP, ApkFile.check(tiny, null, null).error, "an empty file");
        T.eq(null, ApkFile.info(html), "no manifest to read");
    }
}
