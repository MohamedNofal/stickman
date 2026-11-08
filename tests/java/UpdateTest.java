import com.clean.stickman.core.Update;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

public class UpdateTest {
    static String fixture() throws Exception {
        return new String(Files.readAllBytes(Paths.get("tests/fixtures/github-latest.json")), StandardCharsets.UTF_8);
    }

    public void testParseRealRelease() throws Exception {
        Update.Release r = Update.parse(fixture());
        T.yes(r != null, "parsed");
        T.eq("v1.8.1", r.tag, "tag");
        T.eq("1.8.1", r.version, "version");
        T.eq("https://github.com/MohamedNofal/stickman/releases/download/v1.8.1/stickman.apk", r.url, "asset url");
        T.eq(1217668L, r.size, "size from the asset object");
    }

    public void testParseRejects() {
        T.eq(null, Update.parse(null), "null");
        T.eq(null, Update.parse("{\"tag_name\":\"nightly\",\"assets\":[]}"), "non-numeric tag");
        T.eq(null, Update.parse("{\"tag_name\":\"v2.0\",\"assets\":[{\"size\":5,\"browser_download_url\":\"https://evil.example/stickman.apk\"}]}"), "foreign host");
        T.eq(null, Update.parse("{\"tag_name\":\"v2.0\",\"assets\":[{\"size\":5,\"browser_download_url\":\"https://github.com/MohamedNofal/stickman/releases/download/v2.0/other.apk\"}]}"), "other asset");
        Update.Release r = Update.parse("{\"tag_name\":\"v2.0\",\"assets\":[{\"name\":\"stickman-appstore.apk\",\"size\":1,\"browser_download_url\":\"https://github.com/MohamedNofal/stickman/releases/download/v2.0/stickman-appstore.apk\"},"
            + "{\"name\":\"stickman.apk\",\"size\":1234567,\"browser_download_url\":\"https://github.com/MohamedNofal/stickman/releases/download/v2.0/stickman.apk\"}]}");
        T.eq(1234567L, r.size, "picks stickman.apk and its own size");
        T.yes(!Update.urlAllowed("https://github.com/MohamedNofal/stickman/releases/download/../x/stickman.apk"), "no path tricks");
        T.yes(!Update.urlAllowed("http://github.com/MohamedNofal/stickman/releases/download/v1/stickman.apk"), "https only");
    }

    public void testCompare() {
        T.yes(Update.newer("1.8.2", "1.8.1"), "patch");
        T.yes(Update.newer("1.10", "1.9"), "numeric not lexical");
        T.yes(Update.newer("1.8.1", "1.8"), "longer");
        T.yes(!Update.newer("1.8", "1.8.0"), "equal");
        T.yes(!Update.newer("1.7", "1.8.1"), "older");
        T.yes(!Update.newer(null, "1.0"), "null");
        T.eq("1.8.1", Update.installedVersion("    versionName=1.8.1"), "dumpsys");
        T.eq(null, Update.installedVersion(""), "missing");
    }

    public void testPlausibleAndManifest() {
        byte[] zip = { 'P', 'K', 3, 4 };
        T.yes(Update.plausibleApk(1217668, 1217668, zip), "ok");
        T.yes(Update.plausibleApk(1217668, -1, zip), "size unknown");
        T.yes(!Update.plausibleApk(1217667, 1217668, zip), "size mismatch");
        T.yes(!Update.plausibleApk(500, -1, zip), "too small");
        T.yes(!Update.plausibleApk(1217668, 1217668, new byte[] { '<', 'h', 't', 'm' }), "html error page");
        String pkg = "com.clean.stickman";
        byte[] s = pkg.getBytes(StandardCharsets.UTF_16LE);
        byte[] axml = new byte[10 + 2 + s.length + 2];
        axml[10] = (byte) pkg.length();
        System.arraycopy(s, 0, axml, 12, s.length);
        T.yes(Update.manifestNames(axml, pkg), "exact pool string");
        T.yes(!Update.manifestNames(axml, "com.clean.stickman.tv"), "other package");
        byte[] longer = ("com.clean.stickman.tv").getBytes(StandardCharsets.UTF_16LE);
        byte[] ax2 = new byte[2 + longer.length + 2];
        ax2[0] = 21;
        System.arraycopy(longer, 0, ax2, 2, longer.length);
        T.yes(!Update.manifestNames(ax2, pkg), "a longer name is not ours");
    }

    public void testStatus() {
        String l = Update.status("error", "1.8.1", "1.8.2", "HTTP 403\nrate limited");
        T.eq("STATUS update state=error current=1.8.1 latest=1.8.2 msg=HTTP 403 rate limited", l, "line");
        Map<String, String> m = Update.parseStatus(l);
        T.eq("error", m.get("state"), "state");
        T.eq("1.8.2", m.get("latest"), "latest");
        T.eq("HTTP 403 rate limited", m.get("msg"), "msg");
        T.eq("Version 1.8.1. Version 1.8.2 is downloaded and installs when the TV goes to sleep.",
            Update.describe(Update.parseStatus("state=ready current=1.8.1 latest=1.8.2"), "1.8.1"), "ready text");
        T.eq("Version 1.8.1. Not checked yet.", Update.describe(Update.parseStatus(null), "1.8.1"), "unchecked");
    }

    public void testNotes() throws Exception {
        String md = Update.notes(fixture());
        T.yes(md.contains("## Privacy") && md.contains("Quiet telemetry"), "body unescaped: " + md.substring(0, Math.min(80, md.length())));
        String plain = Update.plainNotes(md);
        T.yes(plain.startsWith("PRIVACY\n"), "heading line");
        T.yes(plain.contains("•  Quiet telemetry: Fire OS"), "bullet, bold removed: " + plain);
        T.yes(!plain.contains("**") && !plain.contains("`") && !plain.contains("Install:"), "markdown and footer gone");
        T.eq("a\nb \"c\" é", Update.notes("{\"body\":\"a\\nb \\\"c\\\" \\u00e9\"}"), "escapes");
        T.eq("", Update.notes("{\"tag_name\":\"v1\"}"), "no body");
        T.eq("see the docs", Update.plainNotes("see [the docs](https://x.y)"), "links keep their text");
    }

    public void testOfferAndWhatsNew() {
        T.yes(Update.shouldOffer("1.9", "1.8.1", null), "newer, never asked");
        T.yes(!Update.shouldOffer("1.9", "1.8.1", "1.9"), "said not now to this version");
        T.yes(Update.shouldOffer("1.9.1", "1.8.1", "1.9"), "a later version asks again");
        T.yes(!Update.shouldOffer("1.8.1", "1.8.1", null), "same version");
        T.yes(!Update.shouldOffer(null, "1.8.1", null), "no release");
        T.yes(Update.justUpdated("1.9", "1.8.1"), "updated");
        T.yes(!Update.justUpdated("1.9", null), "fresh install: nothing to announce");
        T.yes(!Update.justUpdated("1.9", "1.9"), "same");
    }

    public void testTestSource() {
        String[] s = Update.testSource("http://192.168.0.101:8765/rel/latest.json\n");
        T.eq("http://192.168.0.101:8765/rel/latest.json", s[0], "api");
        T.eq("http://192.168.0.101:8765/rel/", s[1], "prefix");
        Update.Release r = Update.parse("{\"tag_name\":\"v1.9\",\"assets\":[{\"size\":300000,\"browser_download_url\":\"http://192.168.0.101:8765/rel/stickman.apk\"}]}", s[1]);
        T.eq("1.9", r.version, "test release parsed");
        T.eq(null, Update.parse("{\"tag_name\":\"v1.9\",\"assets\":[{\"size\":300000,\"browser_download_url\":\"http://192.168.0.101:8765/rel/stickman.apk\"}]}"), "not from GitHub without the test file");
        T.eq(null, Update.testSource("file:///sdcard/x"), "http(s) only");
        T.eq(null, Update.testSource(null), "none");
    }
}
