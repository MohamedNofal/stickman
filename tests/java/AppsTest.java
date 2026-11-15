import com.clean.stickman.core.Apps;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AppsTest {
    static String fixture(String n) throws Exception { return new String(Files.readAllBytes(Paths.get("tests/fixtures/" + n)), StandardCharsets.UTF_8); }

    static final List<String> STICK = Arrays.asList("armeabi-v7a", "armeabi"), CUBE64 = Arrays.asList("arm64-v8a", "armeabi-v7a", "armeabi");

    // ---- the catalogue ----
    public void testRealCatalogueParses() throws Exception {
        List<Apps.Entry> c = Apps.parseCatalog(fixture("apps-catalog.json"));
        T.yes(c.size() >= 15, "the hand-picked list: " + c.size());
        Apps.Entry kodi = Apps.byId(c, "kodi");
        T.eq("org.xbmc.kodi", kodi.pkg, "package");
        T.eq(kodi, Apps.byPackage(c, "org.xbmc.kodi"), "by package");
        T.yes(kodi.signer.matches("[0-9a-f]{64}"), "signing key recorded");
        for (Apps.Entry e : c) {
            T.yes(Apps.pick(e, STICK) != null, e.id + " has a file for a 32-bit stick");
            T.yes(Apps.pick(e, CUBE64) != null, e.id + " has a file for a 64-bit box");
            for (Apps.FileRef f : e.files.values()) T.yes(f.url.startsWith("https://") && f.size > Apps.MIN_APK && f.size < Apps.MAX_APK, e.id + " file sane");
        }
    }

    public void testPicksTheSticksOwnCpuType() throws Exception {
        Apps.Entry vlc = Apps.byId(Apps.parseCatalog(fixture("apps-catalog.json")), "vlc");
        T.yes(Apps.pick(vlc, STICK).url.contains("armeabi-v7a"), "32-bit stick gets the 32-bit file");
        T.yes(Apps.pick(vlc, CUBE64).url.contains("arm64-v8a"), "64-bit box gets the 64-bit file");
        T.eq(STICK.subList(0, 1), Apps.abis(""), "a Fire TV that does not say is 32-bit ARM");
        T.eq(CUBE64, Apps.abis("arm64-v8a,armeabi-v7a,armeabi\n"), "abilist property");
        T.eq(null, Apps.pick(vlc, Arrays.asList("x86")), "nothing for another processor");
    }

    public void testBadCatalogueEntriesAreLeftOut() {
        String ok = "{\"id\":\"a\",\"name\":\"A\",\"package\":\"x.y\",\"files\":{\"any\":{\"url\":\"https://e.org/a.apk\",\"size\":100000,\"sha256\":\"" + rep("a", 64) + "\",\"versionCode\":3}}}";
        T.eq(1, Apps.parseCatalog("{\"apps\":[" + ok + "]}").size(), "a good one");
        T.eq(0, Apps.parseCatalog("{\"apps\":[" + ok.replace("https://", "http://") + "]}").size(), "plain http file");
        T.eq(0, Apps.parseCatalog("{\"apps\":[" + ok.replace(rep("a", 64), "abc") + "]}").size(), "no checksum");
        T.eq(0, Apps.parseCatalog("{\"apps\":[" + ok.replace("x.y", "x y; rm") + "]}").size(), "not a package name");
        T.eq(0, Apps.parseCatalog("{\"apps\":[" + ok.replace("\"id\":\"a\"", "\"id\":\"../a\"") + "]}").size(), "id used in file names");
        T.eq(0, Apps.parseCatalog("not json").size(), "garbage");
        T.eq(0, Apps.parseCatalog(null).size(), "nothing");
    }

    public void testVersionsAreShortenedForATile() {
        T.eq("1.1.1", Apps.shortVersion("1.1.1-0-g159edc87"), "git describe");
        T.eq("2026-09-17", Apps.shortVersion("2026-09-17-release"), "date version");
        T.eq("6.6.5", Apps.shortVersion("6.6.5-20261111.1224"), "build stamp");
        T.eq("21.3", Apps.shortVersion("v21.3"), "v prefix");
        T.eq("", Apps.shortVersion(null), "none");
    }

    // ---- what the user typed ----
    public void testClassifyWhatWasTyped() {
        T.eq("code:54794", String.valueOf(Apps.classify(" 54794 ")), "digits are a Downloader code");
        T.eq("repo:yuliskov/SmartTube", String.valueOf(Apps.classify("yuliskov/SmartTube")), "owner/name");
        T.eq("repo:yuliskov/SmartTube", String.valueOf(Apps.classify("https://github.com/yuliskov/SmartTube")), "project page");
        T.eq("repo:yuliskov/SmartTube", String.valueOf(Apps.classify("github.com/yuliskov/SmartTube/releases/latest")), "releases page");
        T.eq("url:https://example.org/app.apk", String.valueOf(Apps.classify("example.org/app.apk")), "https is added");
        T.eq("url:https://github.com/a/b/releases/download/v1/app.apk", String.valueOf(Apps.classify("https://github.com/a/b/releases/download/v1/app.apk")), "a file on GitHub is a link");
        T.eq("url:https://example.org/app.apk", String.valueOf(Apps.classify("HTTPS://example.org/app.apk")), "scheme case");
        T.eq("repo:yuliskov/SmartTube", String.valueOf(Apps.classify("https://github.com/yuliskov/SmartTube/releases/tag/32.71s")), "any page of the project means the project");
        T.eq("repo:yuliskov/SmartTube", String.valueOf(Apps.classify("github.com/yuliskov/SmartTube.git")), "clone address");
        T.eq("url:https://github.com/a/b/raw/main/app.apk", String.valueOf(Apps.classify("github.com/a/b/raw/main/app.apk")), "but a file in the project is that file");
        T.eq("code:54794", String.valueOf(Apps.classify("aftv.news/54794")), "a Downloader short link is its code");
        T.eq("code:54794", String.valueOf(Apps.classify("https://go.aftvnews.com/54794")), "its long form too");
        T.eq("repo:owner/name", String.valueOf(Apps.classify("  owner / name ")), "spaces a TV keyboard leaves behind");
    }

    public void testRefusedInput() {
        T.eq(null, Apps.classify("http://example.org/app.apk"), "plain http: the file could be swapped on the way");
        T.eq(null, Apps.classify(""), "empty");
        T.eq(null, Apps.classify("12"), "too short for a code");
        T.eq(null, Apps.classify("hello"), "a word");
        T.eq(null, Apps.classify("https://exa mple.org/$(reboot).apk`x`"), "shell characters");
        T.eq(null, Apps.classify("https://localhost/app.apk"), "no dot in the host");
        T.eq(null, Apps.classify("file:///sdcard/a.apk"), "not a web link");
        T.yes(!Apps.validUrl("https://example.org/a'b.apk"), "quote");
        T.yes(!Apps.validUrl("https://example.org/a b.apk"), "space");
    }

    // ---- Downloader codes ----
    public void testDownloaderCodePage() throws Exception {
        T.eq("https://go.aftvnews.com/6684947", Apps.codeUrl("6684947"), "where a code is looked up");
        T.eq("https://github.com/MohamedNofal/stickman/releases/latest/download/stickman.apk", Apps.codeTarget(fixture("aftv-code.html")), "the link behind our own code");
        T.eq(null, Apps.codeTarget(fixture("aftv-code-unknown.html")), "unknown code: no link");
        T.eq("https://e.org/a.apk?x=1&y=2", Apps.codeTarget("Redirecting to: <a href=\"http://e.org/a.apk?x=1&amp;y=2\">x</a>"), "entities undone, plain http upgraded");
        T.eq(null, Apps.codeTarget("to: <a href=\"https://e.org/$(id)\">"), "a hostile target is refused");
        T.eq(null, Apps.codeTarget(null), "no page");
    }

    // ---- GitHub releases ----
    public void testPicksTheRightReleaseFile() throws Exception {
        List<Apps.Asset> w = Apps.assets(fixture("github-wholphin.json"));
        T.yes(w.size() >= 4, "APK assets only: " + w.size());
        T.eq("Wholphin-armeabi-v7a.apk", Apps.pickAsset(w, STICK).name, "32-bit stick, shortest name of the matching ones");
        T.eq("Wholphin-arm64-v8a.apk", Apps.pickAsset(w, CUBE64).name, "64-bit box");
        List<Apps.Asset> j = Apps.assets(fixture("github-justplayer.json"));
        T.yes(!Apps.pickAsset(j, STICK).name.contains("legacy"), "the normal build, not the legacy one: " + Apps.pickAsset(j, STICK).name);
        T.yes(!Apps.tag(fixture("github-justplayer.json")).isEmpty(), "tag");
    }

    public void testReleaseFileRules() {
        T.eq("app-arm32.apk", name(STICK, "app-x86_64.apk", "app-arm64.apk", "app-arm32.apk"), "by CPU mark");
        T.eq("app-universal.apk", name(STICK, "app-x86.apk", "app-arm64-v8a.apk", "app-universal.apk"), "no 32-bit build: the one-for-all file");
        T.eq("app-armeabi-v7a.apk", name(CUBE64, "app-x86.apk", "app-armeabi-v7a.apk"), "a 64-bit box runs 32-bit files");
        T.eq(null, name(STICK, "app-arm64-v8a.apk", "app-x86.apk"), "64-bit only: nothing for a 32-bit stick");
        T.eq("app-release.apk", name(STICK, "app-debug.apk", "app-release.apk"), "release over debug");
        T.eq("app-tv-release.apk", name(STICK, "app-phone-release.apk", "app-tv-release.apk"), "the TV build");
        T.eq(null, name(STICK), "no APKs");
        T.eq(0, Apps.assets("{\"assets\":[{\"name\":\"notes.txt\",\"browser_download_url\":\"https://e.org/notes.txt\"}]}").size(), "not an APK");
    }

    static String name(List<String> abis, String... files) {
        List<Apps.Asset> l = new ArrayList<>();
        for (String f : files) l.add(new Apps.Asset(f, "https://e.org/" + f, 1));
        Apps.Asset a = Apps.pickAsset(l, abis);
        return a == null ? null : a.name;
    }

    // ---- tracked apps ----
    public void testTrackedListRoundTrip() {
        Map<String, Apps.Tracked> m = new java.util.LinkedHashMap<>();
        m.put("org.xbmc.kodi", new Apps.Tracked("org.xbmc.kodi", Apps.source("catalog:kodi"), true, ""));
        m.put("a.b", new Apps.Tracked("a.b", Apps.source("url:https://e.org/a.apk?x=1:2"), false, "https://e.org/a.apk|123|Mon"));
        Map<String, Apps.Tracked> back = Apps.parseTracked(Apps.serializeTracked(m));
        T.eq(2, back.size(), "both");
        T.eq("url:https://e.org/a.apk?x=1:2", back.get("a.b").source.toString(), "a link with colons survives");
        T.eq("https://e.org/a.apk|123|Mon", back.get("a.b").tag, "tag");
        T.yes(back.get("org.xbmc.kodi").auto && !back.get("a.b").auto, "auto flags");
        T.eq(0, Apps.parseTracked("garbage\n\tx\n; rm -rf\tcatalog:x\t1\n").size(), "bad lines are dropped");
        Map<String, String[]> st = Apps.parseTrackedStatus(Apps.trackedStatus(m, java.util.Collections.singleton("a.b")).substring("STATUS apps ".length()));
        T.eq("1", st.get("org.xbmc.kodi")[0], "auto on, for the screen");
        T.eq("url", st.get("a.b")[1], "kind, for the screen");
        T.eq("1", st.get("a.b")[2], "an update is waiting");
        T.eq("0", st.get("org.xbmc.kodi")[2], "nothing waiting");
        T.eq(0, Apps.parseTrackedStatus(Apps.trackedStatus(new java.util.LinkedHashMap<String, Apps.Tracked>(), null).substring("STATUS apps ".length())).size(), "nothing tracked");
    }

    // ---- progress lines and tiles ----
    public void testStatusLineRoundTrip() {
        String l = Apps.status("org.xbmc.kodi", Apps.DOWNLOADING, 43, "org.xbmc.kodi", 2103000, 68000000, null);
        T.yes(l.startsWith("STATUS app:org.xbmc.kodi state=downloading pct=43"), l);
        Map<String, String> m = Apps.parseStatus(l.substring(l.indexOf(' ', 7) + 1));
        T.eq("43", m.get("pct"), "pct");
        T.eq("2103000", m.get("vc"), "vc");
        Map<String, String> e = Apps.parseStatus("state=error msg=Not enough free storage: pct=1 is not a field");
        T.eq("error", e.get("state"), "state");
        T.eq("Not enough free storage: pct=1 is not a field", e.get("msg"), "msg keeps its text");
        T.eq(null, e.get("pct"), "nothing after msg= is parsed as a field");
    }

    public void testHousekeepingLinesStayOutOfTheActivityList() {
        T.yes(Apps.housekeeping("STATUS app:org.xbmc.kodi state=downloading pct=3"), "progress");
        T.yes(Apps.housekeeping("app:org.xbmc.kodi state=installing pkg=org.xbmc.kodi"), "without the prefix too");
        T.yes(Apps.housekeeping("STATUS app:org.xbmc.kodi state=uptodate"), "nothing happened");
        T.yes(Apps.housekeeping("STATUS apps list=a.b:1:url:0"), "tracked list");
        T.yes(!Apps.housekeeping("STATUS app:org.xbmc.kodi state=installed pkg=org.xbmc.kodi"), "an outcome");
        T.yes(!Apps.housekeeping("STATUS app:org.xbmc.kodi state=removed"), "an outcome");
        T.yes(!Apps.housekeeping("STATUS app:link state=error msg=No app behind code 1."), "what went wrong");
        T.yes(Apps.housekeeping("STATUS app:link state=installed pkg=a.b"), "said twice otherwise");
        T.yes(!Apps.housekeeping("STATUS boost freed_mb=3 killed=1"), "other features' lines are not ours");
        T.yes(!Apps.housekeeping("STATUS appsomething else"), "nor a kind that only starts alike");
        T.yes(!Apps.housekeeping(null), "nothing");
    }

    public void testTileLine() {
        Map<String, String> none = new HashMap<>();
        T.eq("Not installed", Apps.tileLine(-1, null, 10, "1.0", none, -1), "new");
        T.eq("Installed · 21.3", Apps.tileLine(2103000, "21.3", 2103000, "21.3", none, -1), "current");
        T.eq("Update to 21.4", Apps.tileLine(2103000, "21.3", 2104000, "21.4-Omega", none, -1), "newer in the catalogue");
        T.eq("Installed · 22.0", Apps.tileLine(2200000, "22.0", 2103000, "21.3", none, -1), "the stick has a newer one: leave it");
        Map<String, String> dl = Apps.parseStatus("state=downloading pct=43");
        T.eq("Downloading 43%", Apps.tileLine(-1, null, 10, "1.0", dl, 2000), "live progress");
        T.eq("Not installed", Apps.tileLine(-1, null, 10, "1.0", dl, Apps.BUSY_FRESH_MS + 1), "a stale progress line is a dead download");
        T.eq("Installing…", Apps.tileLine(5, "1", 10, "2", Apps.parseStatus("state=installing"), 100), "installing");
        T.eq("Installed", Apps.tileLine(5, "", -1, null, none, -1), "no version name");
    }

    public void testLineTonesAndWhatCannotBeInstalled() {
        T.eq(Apps.TONE_ACTIVE, Apps.lineTone("Update to 21.4"), "something to do");
        T.eq(Apps.TONE_ACTIVE, Apps.lineTone("Downloading 43%"), "going on");
        T.eq(Apps.TONE_GOOD, Apps.lineTone("Installed · 21.3"), "fine");
        T.eq(Apps.TONE_OFF, Apps.lineTone(Apps.NEEDS_NEWER), "cannot be had");
        T.eq(Apps.TONE_QUIET, Apps.lineTone("Not installed"), "plain");
        T.eq(Apps.TONE_QUIET, Apps.lineTone(null), "nothing");
        T.eq(Apps.NEEDS_NEWER, Apps.cannotInstall(-1, true, 24, 22), "Fire OS 5 and an app for Android 7");
        T.eq(null, Apps.cannotInstall(-1, true, 21, 22), "old enough");
        T.eq(Apps.NO_BUILD, Apps.cannotInstall(-1, false, 21, 30), "no file for this processor");
        T.eq(null, Apps.cannotInstall(5, false, 99, 22), "an installed app keeps its normal line");
    }

    public void testInstallErrorsInPlainWords() {
        T.yes(Apps.installError("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package x signatures do not match]").contains("different publisher"), "other key");
        T.yes(Apps.installError("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]").contains("newer"), "downgrade");
        T.yes(Apps.installError("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]").contains("storage"), "storage");
        T.yes(Apps.installError("Failure [INSTALL_FAILED_NO_MATCHING_ABIS: Failed to extract native libraries, res=-113]").contains("processor"), "wrong CPU type");
        T.yes(Apps.installError("something else entirely").contains("something else entirely"), "unknown refusals are shown as they are");
        T.yes(Apps.installError(null).endsWith("."), "nothing said");
    }

    public void testStickmanItselfAndTags() {
        T.yes(Apps.isStickman("com.clean.stickman"), "the GitHub edition");
        T.yes(Apps.isStickman("com.clean.stickman.tv"), "the Appstore edition");
        T.yes(!Apps.isStickman("org.xbmc.kodi"), "another app");
        T.yes(!Apps.isStickman(null), "nothing");
        T.eq("https://github.com/o/n/releases/download/v2/a.apk|100", Apps.tagOf("https://github.com/o/n/releases/download/v2/a.apk|100", "100|Mon"), "a release file's own tag wins");
        T.eq("100|Mon", Apps.tagOf("", "100|Mon"), "else the file's signature");
        T.eq("", Apps.tagOf(null, null), "nothing known");
    }

    public void testNeverRemove() {
        T.yes(Apps.neverRemove("com.clean.stickman"), "itself");
        T.yes(Apps.neverRemove("com.amazon.tv.launcher"), "Amazon system parts");
        T.yes(Apps.neverRemove("android"), "the system");
        T.yes(Apps.neverRemove("x; rm -rf /"), "not a package name");
        T.yes(!Apps.neverRemove("org.xbmc.kodi"), "an app the user installed");
    }

    public void testSpaceNeededAndVersionCode() {
        T.yes(Apps.spaceNeeded(68_000_000) > 2 * 68_000_000L, "the file, the installed copy, and room");
        T.eq(2103000L, Apps.installedVersionCode("    versionCode=2103000 minSdk=21 targetSdk=34"), "dumpsys line");
        T.eq(-1L, Apps.installedVersionCode(""), "not installed");
    }

    public void testPopularityOrder() throws Exception {
        List<Apps.Entry> c = Apps.parseCatalog(fixture("apps-catalog.json"));
        List<Apps.Entry> p = Apps.byPopularity(c);
        T.eq(c.size(), p.size(), "same apps");
        T.yes(Apps.categories(c).size() >= 4, "categories: " + Apps.categories(c));
    }

    // ---- the catalogue's address ----
    public void testCatalogueAddress() {
        T.eq(Apps.CATALOG_URL, Apps.catalogUrl(""), "normally the published catalogue");
        T.eq(Apps.CATALOG_URL, Apps.catalogUrl(null), "no property");
        T.eq("http://10.0.2.2:8000/catalog.json", Apps.catalogUrl(" http://10.0.2.2:8000/catalog.json\n"), "a developer's test server");
        T.eq(null, Apps.catalogOverride("https://e.org/c.json; reboot"), "anything but a plain URL is ignored");
        T.eq(null, Apps.catalogOverride("file:///sdcard/c.json"), "web only");
        T.eq("https://raw.githubusercontent.com/MohamedNofal/stickman/catalog/icons/kodi.png", Apps.artUrl(Apps.CATALOG_URL, "kodi"), "art next to the catalogue");
    }

    // ---- action arguments ----
    public void testActionArguments() {
        T.eq("[kodi, org.xbmc.kodi]", java.util.Arrays.toString(Apps.installArg("kodi org.xbmc.kodi")), "id and package");
        T.eq("[kodi, null]", java.util.Arrays.toString(Apps.installArg("kodi")), "id alone");
        T.eq("[kodi, null]", java.util.Arrays.toString(Apps.installArg("kodi x;y")), "a bad package is dropped, the id stays");
        T.eq(null, Apps.installArg("../etc"), "not an id");
        T.eq(null, Apps.installArg(""), "nothing");
        T.eq("[a.b, 1]", java.util.Arrays.toString(Apps.autoArg("a.b 1")), "on");
        T.eq("[a.b, 0]", java.util.Arrays.toString(Apps.autoArg(" a.b  0 ")), "off");
        T.eq(null, Apps.autoArg("a.b 2"), "not a switch");
        T.eq(null, Apps.autoArg("a.b"), "no value");
        T.eq(null, Apps.autoArg("a;b 1"), "not a package");
    }

    // ---- downloads ----
    public void testDownloadChecks() {
        byte[] zip = { 'P', 'K', 3, 4 }, html = { '<', '!', 'D', 'O' };
        T.eq(null, Apps.looksLikeApk(5_000_000, zip), "a zip of app size");
        T.eq(Apps.NOT_AN_APP, Apps.looksLikeApk(5_000_000, html), "a web page");
        T.eq(Apps.NOT_AN_APP, Apps.looksLikeApk(900, zip), "too small to be an app");
        T.eq(Apps.NOT_AN_APP, Apps.looksLikeApk(5_000_000, null), "unreadable");
        T.eq("00ff10ab", Apps.hex(new byte[] { 0, (byte) 0xff, 0x10, (byte) 0xab }), "hex");
        T.eq(43, Apps.pct(43, 100), "percent");
        T.eq(100, Apps.pct(130, 100), "never above 100");
        T.eq(-1, Apps.pct(5, -1), "size unknown");
        T.yes(Apps.spaceMessage(300_000_000, 120_000_000).contains("300 MB") && Apps.spaceMessage(300_000_000, 120_000_000).contains("120 MB"), "both numbers");
        T.yes(Apps.stagedExpired(Apps.STAGED_MS + 1) && !Apps.stagedExpired(60_000), "a download waits 15 minutes for the yes");
        T.yes(Apps.whyNot("http://e.org/a.apk").contains("https"), "http gets its own explanation");
        T.eq(Apps.NOT_TYPED, Apps.whyNot("hello"), "the general one");
    }

    public void testSignatures() {
        T.eq("123|Mon, 02 Nov 2026 10:00:00 GMT", Apps.signature(123, "Mon, 02 Nov 2026 10:00:00 GMT", "\"abc\""), "the date wins: it is the same on every mirror");
        T.eq("123|\"abc\"", Apps.signature(123, null, "\"abc\""), "else the ETag");
        T.eq("123|", Apps.signature(123, null, null), "length alone still tells a new file");
        T.eq("", Apps.signature(-1, null, null), "a server that says nothing");
        T.eq("|x_y", Apps.signature(-1, "x|y", null), "the separator cannot appear inside");
        T.yes(Apps.newTag("123|a", "124|b"), "a new file");
        T.yes(!Apps.newTag("123|a", "123|a"), "the same file");
        T.yes(!Apps.newTag("123|a", ""), "nothing known now: no reason to fetch");
        T.yes(Apps.newTag("", "123|a"), "nothing was recorded at install time: look once");
        T.yes(Apps.newTag(null, "123|a"), "null stored");
        Apps.Asset a = new Apps.Asset("app-release.apk", "https://github.com/o/n/releases/download/latest/app-release.apk", 100);
        Apps.Asset b = new Apps.Asset("app-release.apk", "https://github.com/o/n/releases/download/latest/app-release.apk", 101);
        T.yes(Apps.newTag(Apps.repoTag(a), Apps.repoTag(b)), "a project that reuses one tag: the size tells");
        T.eq("", Apps.repoTag(null), "no file");
    }

    public void testRangeStart() {
        T.eq(500L, Apps.rangeStart("bytes 500-999/1000"), "where the part begins");
        T.eq(0L, Apps.rangeStart(" bytes 0-0/12345"), "first byte");
        T.eq(-1L, Apps.rangeStart(null), "no header");
        T.eq(-1L, Apps.rangeStart("bytes */1000"), "an unsatisfied range");
        T.eq(1000L, Apps.rangeTotal("bytes 500-999/1000"), "the whole file's size");
        T.eq(12345L, Apps.rangeTotal("bytes 0-0/12345"), "from a one-byte part");
        T.eq(-1L, Apps.rangeTotal("bytes 0-0/*"), "a server that does not know");
        T.eq(-1L, Apps.rangeTotal(null), "no header");
    }

    public void testAutomaticRetryBackoff() {
        T.eq(3600_000L, Apps.retryAfterMs(1), "an hour after the first failure");
        T.eq(3 * 3600_000L, Apps.retryAfterMs(3), "growing");
        T.eq(6 * 3600_000L, Apps.retryAfterMs(40), "at most six hours");
        T.eq(3600_000L, Apps.retryAfterMs(0), "never zero: a broken source must not be fetched every five minutes");
        T.yes(Apps.otherApp("x.y").contains("x.y"), "names the app that was found");
    }

    public void testPackageList() {
        java.util.Set<String> s = Apps.packages("package:org.xbmc.kodi\npackage:com.amazon.tv.launcher\r\n\nWARNING: something\npackage:bad name\n");
        T.eq(2, s.size(), "two packages");
        T.yes(s.contains("org.xbmc.kodi"), "kodi");
        T.eq(0, Apps.packages("Error: could not access the package manager").size(), "a failed call is no list, not an empty stick");
        T.eq(0, Apps.packages(null).size(), "no output");
    }

    public void testRemoveErrorsInPlainWords() {
        T.yes(Apps.removeError("Failure [DELETE_FAILED_INTERNAL_ERROR]").contains("part of the system"), "system app");
        T.yes(Apps.removeError("Failure [not installed for 0]").contains("not installed"), "gone already");
        T.yes(Apps.removeError("weird").contains("weird"), "unknown refusals are shown");
    }

    // ---- the screen ----
    public void testSideloaded() {
        T.yes(Apps.sideloaded(false, null, "org.xbmc.kodi"), "installed by hand");
        T.yes(Apps.sideloaded(false, "com.android.shell", "org.xbmc.kodi"), "installed over adb (or by Stickman)");
        T.yes(!Apps.sideloaded(false, "com.amazon.venezia", "com.netflix.ninja"), "the Appstore looks after its own");
        T.yes(!Apps.sideloaded(true, null, "com.amazon.tv.launcher"), "system");
        T.yes(!Apps.sideloaded(false, null, "com.clean.stickman"), "not Stickman itself");
    }

    public void testWhereItComesFrom() {
        T.eq("kodi.tv", Apps.host("https://kodi.tv/download"), "host");
        T.eq("videolan.org", Apps.host("https://www.videolan.org/vlc/"), "www dropped");
        T.eq("", Apps.host("nope"), "none");
        T.eq("GitHub project yuliskov/SmartTube", Apps.sourceText(Apps.source("repo:yuliskov/SmartTube")), "project");
        T.eq("Downloader code 54794", Apps.sourceText(Apps.source("code:54794")), "code");
        T.eq("a link on example.org", Apps.sourceText(Apps.source("url:https://example.org/a.apk")), "link");
        T.eq("Stickman's catalogue", Apps.sourceText(Apps.source("catalog:kodi")), "catalogue");
        T.eq("a GitHub project", Apps.kindText("repo"), "kind word");
        T.eq("", Apps.kindText("?"), "unknown kind");
    }

    public void testDialogButtons() {
        T.eq("[Install]", Apps.buttons(-1, false, false, false, false, false).toString(), "new");
        T.eq("[]", Apps.buttons(-1, false, false, false, true, false).toString(), "cannot run on this Fire OS");
        T.eq("[Update, Open, Uninstall]", Apps.buttons(5, true, true, true, false, false).toString(), "installed with an update");
        T.eq("[Open, Uninstall]", Apps.buttons(5, false, true, true, false, false).toString(), "current");
        T.eq("[Open]", Apps.buttons(5, false, true, false, false, false).toString(), "a system app is not removable");
        T.eq("[Uninstall]", Apps.buttons(5, false, false, true, false, false).toString(), "nothing to open (a service)");
    }

    public void testAnotherBuildIsNeverOfferedAnUpdate() {
        // the owner's stick, 2026-11-15: VLC 3.6.5 from the Amazon Appstore, VideoLAN's own 3.7.1 in the catalogue
        String videolan = rep("c8", 32), amazon = rep("1f", 32);
        T.eq(Boolean.TRUE, Apps.sameBuild(videolan, java.util.Arrays.asList(videolan)), "the publisher's own build");
        T.eq(Boolean.FALSE, Apps.sameBuild(videolan, java.util.Arrays.asList(amazon)), "the Appstore's copy is signed with Amazon's key");
        T.eq(Boolean.TRUE, Apps.sameBuild(videolan, java.util.Arrays.asList(amazon, videolan)), "a rotated key: the catalogue's is among them");
        T.eq(null, Apps.sameBuild(videolan, new java.util.ArrayList<String>()), "certificates not readable: unknown, not 'other'");
        T.eq(null, Apps.sameBuild(null, java.util.Arrays.asList(amazon)), "an old catalogue without keys: unknown");
        T.eq("[Open, Replace, Uninstall]", Apps.buttons(5, true, true, true, false, true).toString(), "never Update; Replace is not the first button");
        T.eq("[Open, Uninstall]", Apps.buttons(5, false, true, true, false, true).toString(), "nothing newer: nothing to replace it with");
        T.eq("[Open]", Apps.buttons(5, true, true, false, false, true).toString(), "a copy that cannot be removed cannot be replaced");
        T.eq("[Open, Uninstall]", Apps.buttons(5, true, true, true, true, true).toString(), "the catalogue's file cannot run here");
        T.eq("Installed · 3.6.5 · Appstore", Apps.otherBuildLine("3.6.5-1", Apps.STORE_INSTALLER), "the tile says where it is from");
        T.eq("Installed · 2.0 · other build", Apps.otherBuildLine("2.0", null), "sideloaded from elsewhere");
        T.eq(Apps.TONE_GOOD, Apps.lineTone(Apps.otherBuildLine("3.6.5-1", Apps.STORE_INSTALLER)), "installed, nothing to do");
        T.yes(Apps.otherBuildText(Apps.STORE_INSTALLER, true).contains("Appstore") && Apps.otherBuildText(Apps.STORE_INSTALLER, true).contains("lost"), "Replace costs the app's data: said before it is offered");
        T.yes(!Apps.otherBuildText(null, false).contains("Replace"), "no Replace mentioned when there is none");
        T.yes(Apps.replaceText("VLC", "3.7.1").contains("VLC 3.7.1") && Apps.replaceText("VLC", "3.7.1").contains("everything it saved"), "the question names what is lost");
    }

    public void testAnnouncements() {
        T.eq("Kodi is installed.", Apps.announce(Apps.parseStatus("state=installed pkg=org.xbmc.kodi"), "Kodi"), "installed");
        T.eq("Kodi is uninstalled.", Apps.announce(Apps.parseStatus("state=removed"), "Kodi"), "removed");
        T.eq("Kodi is already up to date.", Apps.announce(Apps.parseStatus("state=uptodate"), "Kodi"), "nothing to do");
        T.eq("Not enough free storage.", Apps.announce(Apps.parseStatus("state=error msg=Not enough free storage."), "Kodi"), "the reason itself");
        T.eq(null, Apps.announce(Apps.parseStatus("state=downloading pct=3"), "Kodi"), "still going");
        T.eq("The app is installed.", Apps.announce(Apps.parseStatus("state=installed"), null), "no name known");
        T.eq("Downloading 43%…", Apps.linkProgress(Apps.parseStatus("state=downloading pct=43")), "link progress");
        T.eq("Looking it up…", Apps.linkProgress(Apps.parseStatus("state=lookup")), "lookup");
        T.eq(null, Apps.linkProgress(Apps.parseStatus("state=confirm pkg=a.b")), "the question is a dialog, not a line");
    }

    public void testConfirmQuestion() {
        String fresh = Apps.confirmText("SmartTube", "org.smarttube.stable", 2461, 26_000_000, -1);
        T.yes(fresh.startsWith("SmartTube  (org.smarttube.stable)\nVersion code 2461  ·  26 MB"), fresh);
        T.yes(fresh.contains("not on this Fire TV yet"), "new");
        T.yes(Apps.confirmText(null, "a.b", 10, 0, 7).startsWith("a.b\nVersion code 10\n"), "no name, no size");
        T.yes(Apps.confirmText(null, "a.b", 10, 0, 7).contains("updates the copy"), "update");
        T.yes(Apps.confirmText(null, "a.b", 10, 0, 10).contains("same version"), "same");
        T.yes(Apps.confirmText(null, "a.b", 10, 0, 12).contains("will refuse"), "older than what is installed");
        T.eq(123L, Apps.confirmInstalled("installed=123"), "installed version from the line");
        T.eq(-1L, Apps.confirmInstalled("installed=-"), "not installed");
        T.eq(-1L, Apps.confirmInstalled(null), "no message");
        String msg = Apps.confirmMsg(34, "1.11.0");
        T.eq(34L, Apps.confirmInstalled(msg), "installed, with a version name after it");
        T.eq("1.11.0", Apps.confirmVersion(msg), "version name");
        T.eq(null, Apps.confirmVersion(Apps.confirmMsg(-1, "1.0 beta; rm")), "a name that is not plain text is left out");
        T.yes(Apps.confirmText(null, "com.aistra.hail", "1.11.0", 35, 5_760_575, 34).startsWith("com.aistra.hail\nVersion 1.11.0  (code 35)  ·  6 MB"), "version name first");
    }

    public void testTileLineForAnUpdateWithoutAVersion() {
        T.eq("Update available", Apps.tileLine(5, "1.0", 6, "", new HashMap<String, String>(), -1), "a typed link: the version is only known after the download");
    }

    static String rep(String s, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(s); return b.toString(); }
}
