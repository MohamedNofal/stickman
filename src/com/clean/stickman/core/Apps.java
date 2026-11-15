package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apps: install, update and remove other apps from Stickman (pure rules; daemon/AppInstaller does the work on the
 * stick, AppsPage is the screen). GitHub edition only.
 *
 * Where an app comes from, one of four "sources":
 *   catalog:ID   a hand-picked app from the catalogue (catalog/sources.json in this repo, resolved by
 *                catalog/resolve.py into catalog.json with version, link, size, SHA-256 and signing key per CPU type)
 *   url:LINK     a link to an APK the user typed
 *   repo:OWNER/NAME   the latest release of a GitHub project
 *   code:DIGITS  an AFTVnews Downloader code (their short link is looked up, then treated like a link)
 * Whatever the source says, the file itself decides what gets installed: its manifest names the package and version
 * (core/Axml), a catalogue file must match the catalogue's SHA-256, and Android refuses an update that is not signed
 * with the installed app's key. Installs run as the shell user (pm install), so there is no "unknown sources" prompt.
 *
 * Apps installed through Stickman are remembered in DIR/apps.list ("tracked") and, unless switched off per app,
 * updated by themselves while the TV is asleep and nothing plays, like Stickman's own updates.
 */
public final class Apps {
    private Apps() {}

    /** The catalogue lives on its own branch, rewritten by a scheduled job; the app only ever reads these. */
    public static final String CATALOG_URL = "https://raw.githubusercontent.com/MohamedNofal/stickman/catalog/catalog.json";
    /** Developer testing: shell may set this property to another catalogue URL (art is read from next to it). */
    public static final String CATALOG_PROP = "debug.stickman.catalog";
    /** Downloads in progress. */
    public static final String DIR = Originals.DIR + "/apps";
    public static final String LIST = Originals.DIR + "/apps.list";
    public static final long CHECK_EVERY_MS = 6 * 3600_000L;
    /** A real app is somewhere between a few hundred KB and a few hundred MB. */
    public static final long MIN_APK = 50_000, MAX_APK = 600_000_000L;
    /** Free space wanted for a download of the given size: the file, the installed copy, and room to breathe. */
    public static long spaceNeeded(long apkSize) { return 2 * Math.max(apkSize, 0) + 150_000_000L; }

    public static final String CATALOG = "catalog", URL = "url", REPO = "repo", CODE = "code";

    // ---- the catalogue ----

    public static final class FileRef {
        public final String url, sha256;
        public final long size, versionCode;
        FileRef(String url, String sha256, long size, long versionCode) { this.url = url; this.sha256 = sha256; this.size = size; this.versionCode = versionCode; }
    }

    public static final class Entry {
        public String id, name, category, summary, homepage, pkg, version, signer, art;
        public int minSdk;
        public long stars, downloads;
        /** CPU type ("armeabi-v7a", "arm64-v8a", or "any" for one file that runs everywhere) -> file. */
        public final Map<String, FileRef> files = new LinkedHashMap<>();
    }

    /** The apps of a catalog.json, in its order; entries that miss something Stickman needs are left out. */
    public static List<Entry> parseCatalog(String json) {
        List<Entry> out = new ArrayList<>();
        Map<String, Object> root = Json.obj(Json.parse(json));
        List<Object> apps = root == null ? null : Json.arr(root.get("apps"));
        if (apps == null) return out;
        for (Object o : apps) {
            Map<String, Object> m = Json.obj(o);
            if (m == null) continue;
            Entry e = new Entry();
            e.id = Json.str(m, "id"); e.name = Json.str(m, "name"); e.category = Json.str(m, "category");
            e.summary = Json.str(m, "summary"); e.homepage = Json.str(m, "homepage"); e.pkg = Json.str(m, "package");
            e.version = Json.str(m, "version"); e.signer = Json.str(m, "signer"); e.art = Json.str(m, "art");
            e.minSdk = (int) Json.num(m, "minSdk", 1); e.stars = Json.num(m, "stars", 0); e.downloads = Json.num(m, "downloads", 0);
            Map<String, Object> files = Json.obj(m.get("files"));
            if (files != null) for (Map.Entry<String, Object> f : files.entrySet()) {
                Map<String, Object> fm = Json.obj(f.getValue());
                String url = Json.str(fm, "url"), sha = Json.str(fm, "sha256");
                long size = Json.num(fm, "size", -1), vc = Json.num(fm, "versionCode", -1);
                if (url == null || !url.startsWith("https://") || sha == null || !sha.matches("[0-9a-f]{64}") || size <= 0 || vc < 0) continue;
                e.files.put(f.getKey(), new FileRef(url, sha, size, vc));
            }
            if (e.id == null || !e.id.matches("[a-z0-9_-]{1,40}") || e.name == null || !validPkg(e.pkg) || e.files.isEmpty()) continue;
            if (e.category == null) e.category = "Other";
            if (e.summary == null) e.summary = "";
            if (e.version == null) e.version = "";
            out.add(e);
        }
        return out;
    }

    public static Entry byId(List<Entry> catalog, String id) {
        if (catalog != null && id != null) for (Entry e : catalog) if (id.equals(e.id)) return e;
        return null;
    }

    public static Entry byPackage(List<Entry> catalog, String pkg) {
        if (catalog != null && pkg != null) for (Entry e : catalog) if (pkg.equals(e.pkg)) return e;
        return null;
    }

    /** The system's CPU types, best first ("ro.product.cpu.abilist"); a Fire TV that does not say is 32-bit ARM. */
    public static List<String> abis(String abilist) {
        List<String> out = new ArrayList<>();
        if (abilist != null) for (String a : abilist.trim().split(",")) if (a.trim().matches("[a-z0-9_-]{2,20}")) out.add(a.trim());
        if (out.isEmpty()) out.add("armeabi-v7a");
        return out;
    }

    /** The file for this stick: its best CPU type the app is built for, else the one-for-all file; null = none fits. */
    public static FileRef pick(Entry e, List<String> abis) {
        if (e == null) return null;
        for (String a : abis) if (e.files.containsKey(a)) return e.files.get(a);
        return e.files.get("any");
    }

    /** Catalogue versions as publishers write them ("1.1.1-0-g159edc87", "2026-09-17-release") cut down for a tile. */
    public static String shortVersion(String v) {
        if (v == null) return "";
        String s = v.trim();
        Matcher date = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})").matcher(s);
        if (date.find()) return date.group(1);
        Matcher m = Pattern.compile("^v?(\\d+(?:\\.\\d+){0,3})").matcher(s);
        return m.find() ? m.group(1) : (s.length() > 14 ? s.substring(0, 14) : s);
    }

    /** "68 MB" for a tile. */
    public static String mb(long bytes) { return Math.max(1, Math.round(bytes / 1e6)) + " MB"; }

    // ---- what the user typed ----

    public static final class Source {
        public final String kind, value;
        public Source(String kind, String value) { this.kind = kind; this.value = value; }
        @Override public String toString() { return kind + ":" + value; }
    }

    /** "kind:value" back into a Source, or null. */
    public static Source source(String s) {
        if (s == null) return null;
        int c = s.indexOf(':');
        if (c <= 0) return null;
        String k = s.substring(0, c), v = s.substring(c + 1);
        if (!(k.equals(CATALOG) || k.equals(URL) || k.equals(REPO) || k.equals(CODE)) || v.isEmpty() || v.indexOf('\t') >= 0 || v.indexOf('\n') >= 0) return null;
        return new Source(k, v);
    }

    private static final Pattern GITHUB = Pattern.compile("^(?:https?://)?(?:www\\.)?github\\.com/([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?(?:[/?#].*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE_LINK = Pattern.compile("^(?:https?://)?(?:www\\.)?(?:aftv\\.news|go\\.aftvnews\\.com)/(\\d{3,12})/?$", Pattern.CASE_INSENSITIVE);

    /**
     * What a typed line means: digits (or a Downloader short link) are a Downloader code; "owner/name" or any page of
     * a github.com project is that GitHub project, unless it is a link to an .apk file itself; anything else with a
     * dot in its host is a link (https:// is added when missing). Null = not something Stickman can fetch. Plain
     * http links are refused: an app file must not be swappable on the way.
     */
    public static Source classify(String typed) {
        if (typed == null) return null;
        String t = typed.trim().replaceAll("\\s+", "");
        if (t.isEmpty() || t.length() > 600) return null;
        if (t.matches("\\d{3,12}")) return new Source(CODE, t);
        Matcher c = CODE_LINK.matcher(t);
        if (c.matches()) return new Source(CODE, c.group(1));
        Matcher g = GITHUB.matcher(t);
        if (g.matches() && !t.toLowerCase(Locale.ROOT).endsWith(".apk")) return new Source(REPO, g.group(1) + "/" + g.group(2));
        if (t.matches("[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}") && !t.startsWith("-")) return new Source(REPO, t);
        if (t.regionMatches(true, 0, "http://", 0, 7)) return null;
        String u = t.regionMatches(true, 0, "https://", 0, 8) ? "https://" + t.substring(8) : "https://" + t;
        return validUrl(u) ? new Source(URL, u) : null;
    }

    /**
     * An https link to a host with a dot in its name. Only letters, digits and . _ ~ : / ? # @ &amp; + , = % - after
     * the host: a link travels through a quoted shell line, a spool file and log lines on its way to the daemon, and
     * although none of them runs it, nothing a shell gives meaning to ($ ` ( ) ; ' " etc.) is let in at all. A link that
     * needs such a character can be written with %XX.
     */
    public static boolean validUrl(String u) {
        return u != null && u.length() <= 600 && u.matches("https://[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+(:\\d{1,5})?(/[A-Za-z0-9._~:/?#@&+,=%-]*)?");
    }

    public static boolean validPkg(String p) {
        return p != null && p.length() <= 200 && p.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    }

    /** Stickman itself (either edition): it has its own updater, and is not an app to add through the Apps page. */
    public static boolean isStickman(String pkg) { return EnsureScript.PKG.equals(pkg) || Edition.STORE_PKG.equals(pkg); }

    public static final String OWN_UPDATER = "That is Stickman itself. It updates itself: see Settings.";

    /** Packages Stickman never uninstalls: itself, and system parts (pm would revert those to factory state). */
    public static boolean neverRemove(String pkg) {
        return !validPkg(pkg) || pkg.equals(EnsureScript.PKG) || pkg.equals(Edition.STORE_PKG) || pkg.equals("android")
            || pkg.startsWith("com.android.") || pkg.startsWith("com.amazon.") || pkg.startsWith("amazon.");
    }

    // ---- Downloader codes ----

    /** Where AFTVnews answers a Downloader code. */
    public static String codeUrl(String code) { return "https://go.aftvnews.com/" + code; }

    private static final Pattern CODE_TO = Pattern.compile("to:\\s*<a\\s+href=\"(https?://[^\"]+)\""),
        CODE_JS = Pattern.compile("window\\.location\\s*=\\s*\"(https?://[^\"]+)\"");

    /** The link a Downloader code leads to, from AFTVnews's page for it; null when the code is unknown. */
    public static String codeTarget(String html) {
        if (html == null) return null;
        Matcher m = CODE_TO.matcher(html);
        if (!m.find()) { m = CODE_JS.matcher(html); if (!m.find()) return null; }
        String u = m.group(1).replace("&amp;", "&").replace("&#038;", "&").trim();
        if (u.regionMatches(true, 0, "http://", 0, 7)) u = "https://" + u.substring(7);   // try the safe form of a plain link
        return validUrl(u) ? u : null;
    }

    // ---- GitHub releases ----

    public static String repoApi(String repo) { return "https://api.github.com/repos/" + repo + "/releases/latest"; }

    public static final class Asset {
        public final String name, url;
        public final long size;
        public Asset(String name, String url, long size) { this.name = name; this.url = url; this.size = size; }
    }

    /** The APK files of a releases/latest answer. */
    public static List<Asset> assets(String json) {
        List<Asset> out = new ArrayList<>();
        Map<String, Object> root = Json.obj(Json.parse(json));
        List<Object> as = root == null ? null : Json.arr(root.get("assets"));
        if (as == null) return out;
        for (Object o : as) {
            Map<String, Object> m = Json.obj(o);
            String n = Json.str(m, "name"), u = Json.str(m, "browser_download_url");
            if (n != null && u != null && n.toLowerCase(Locale.ROOT).endsWith(".apk") && validUrl(u)) out.add(new Asset(n, u, Json.num(m, "size", -1)));
        }
        return out;
    }

    /** The release's tag ("v1.2.3"), or "". */
    public static String tag(String json) {
        Map<String, Object> root = Json.obj(Json.parse(json));
        String t = Json.str(root, "tag_name");
        return t == null ? "" : t;
    }

    private static final String[] ARM32 = { "armeabi-v7a", "armeabi_v7a", "armv7", "arm32", "armeabi", "v7a" },
        ARM64 = { "arm64-v8a", "arm64_v8a", "arm64", "aarch64", "v8a" }, X86 = { "x86_64", "x86-64", "x86", "amd64", "x64" };

    private static boolean has(String name, String[] marks) {
        for (String m : marks) if (name.contains(m)) return true;
        return false;
    }

    /**
     * Which of a release's APKs suits this stick. Projects name their files freely, so this goes by the usual marks:
     * a file built for the stick's CPU type first (a 64-bit system also runs 32-bit files, never the reverse), else
     * one with no CPU mark at all (one file for every device). Debug builds lose to release builds, a "tv" build wins
     * over a phone one, and among equals the shortest name. Null when nothing fits.
     */
    public static Asset pickAsset(List<Asset> assets, List<String> abis) {
        boolean dev64 = abis.contains("arm64-v8a");
        Asset best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Asset a : assets) {
            String n = a.name.toLowerCase(Locale.ROOT);
            boolean f64 = has(n, ARM64), f32 = !f64 && has(n, ARM32), fx = has(n, X86);
            int score;
            if (fx && !f64 && !f32) continue;
            if (f64) { if (!dev64) continue; score = 300; }
            else if (f32) score = dev64 ? 100 : 300;
            else score = 200;                                   // no mark: universal
            if (n.contains("debug")) score -= 50;
            if (n.contains("legacy") || n.contains("beta") || n.contains("alpha")) score -= 10;
            if (n.matches(".*(^|[^a-z])(tv|androidtv|leanback|firetv)([^a-z]|$).*")) score += 20;
            if (n.contains("universal")) score += 1;
            if (score > bestScore || (score == bestScore && best != null && a.name.length() < best.name.length())) { best = a; bestScore = score; }
        }
        return best;
    }

    // ---- tracked apps (DIR/apps.list, owned by the daemon) ----

    public static final class Tracked {
        public final String pkg;
        public Source source;
        public boolean auto;
        /** What was installed last, as the source describes it (asset link, or link|length|date), to spot a new one. */
        public String tag;
        public Tracked(String pkg, Source source, boolean auto, String tag) { this.pkg = pkg; this.source = source; this.auto = auto; this.tag = tag == null ? "" : tag; }
    }

    public static Map<String, Tracked> parseTracked(String text) {
        Map<String, Tracked> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String[] t = line.split("\t", -1);
            if (t.length < 3 || !validPkg(t[0])) continue;
            Source s = source(t[1]);
            if (s == null) continue;
            out.put(t[0], new Tracked(t[0], s, "1".equals(t[2]), t.length > 3 ? t[3] : ""));
        }
        return out;
    }

    public static String serializeTracked(Map<String, Tracked> m) {
        StringBuilder b = new StringBuilder();
        for (Tracked t : m.values())
            b.append(t.pkg).append('\t').append(t.source).append('\t').append(t.auto ? 1 : 0).append('\t')
             .append(t.tag.replace('\t', ' ').replace('\n', ' ')).append('\n');
        return b.toString();
    }

    /**
     * "STATUS apps list=pkg:auto:kind:update,..." : what Stickman keeps up to date, for the screen. update = 1 when
     * a newer file is known and waiting (for the TV to sleep, or for the user).
     */
    public static String trackedStatus(Map<String, Tracked> m, java.util.Set<String> pending) {
        StringBuilder b = new StringBuilder("STATUS apps list=");
        boolean first = true;
        for (Tracked t : m.values()) {
            if (!first) b.append(',');
            first = false;
            b.append(t.pkg).append(':').append(t.auto ? 1 : 0).append(':').append(t.source.kind)
             .append(':').append(pending != null && pending.contains(t.pkg) ? 1 : 0);
        }
        return b.toString();
    }

    /** pkg -> {auto ("1"/"0"), kind, update ("1"/"0")} from the rest of a "STATUS apps" line. */
    public static Map<String, String[]> parseTrackedStatus(String rest) {
        Map<String, String[]> out = new HashMap<>();
        if (rest == null) return out;
        int i = rest.indexOf("list=");
        if (i < 0) return out;
        for (String item : rest.substring(i + 5).trim().split(",")) {
            String[] t = item.split(":");
            if (t.length == 4 && validPkg(t[0])) out.put(t[0], new String[] { t[1], t[2], t[3] });
        }
        return out;
    }

    // ---- progress lines ----

    public static final String QUEUED = "queued", LOOKUP = "lookup", DOWNLOADING = "downloading", CONFIRM = "confirm",
        INSTALLING = "installing", INSTALLED = "installed", REMOVED = "removed", AVAILABLE = "available",
        UPTODATE = "uptodate", ERROR = "error";
    /** Key of the progress lines of a typed link / code / project, until its package is known. */
    public static final String LINK_KEY = "link";

    /** "STATUS app:KEY state=S pct=N pkg=P vc=N size=N msg=..." (KEY = package, or LINK_KEY). */
    public static String status(String key, String state, int pct, String pkg, long vc, long size, String msg) {
        return "STATUS app:" + key + " state=" + state + (pct >= 0 ? " pct=" + pct : "") + (pkg == null ? "" : " pkg=" + pkg)
            + (vc >= 0 ? " vc=" + vc : "") + (size >= 0 ? " size=" + size : "")
            + (msg == null || msg.isEmpty() ? "" : " msg=" + msg.replace('\n', ' '));
    }

    /** The fields of the rest of a status line; msg is everything after " msg=" (or a leading "msg="). */
    public static Map<String, String> parseStatus(String rest) {
        Map<String, String> m = new HashMap<>();
        if (rest == null) return m;
        int mi = rest.startsWith("msg=") ? 0 : rest.indexOf(" msg=");
        String head = mi >= 0 ? rest.substring(0, mi) : rest;
        if (mi >= 0) m.put("msg", rest.substring(rest.indexOf("msg=", mi) + 4).trim());
        Matcher kv = Pattern.compile("(state|pct|pkg|vc|size)=(\\S+)").matcher(head);
        while (kv.find()) m.put(kv.group(1), kv.group(2));
        return m;
    }

    /**
     * A log line ("STATUS app:..." / "STATUS apps ...", prefix optional) that is plumbing between the daemon and the
     * Apps page, not something that happened: progress, the question before a typed link is installed, the list of
     * tracked apps, and a typed link's "installed" (the package's own line says the same). The Status page's list of
     * what Stickman has been doing keeps only outcomes: installed, removed, and what went wrong.
     */
    public static boolean housekeeping(String line) {
        String b = line == null ? "" : line.trim();
        if (b.startsWith("STATUS ")) b = b.substring(7).trim();
        if (b.equals("apps") || b.startsWith("apps ")) return true;
        if (!b.startsWith("app:")) return false;
        int sp = b.indexOf(' ');
        String key = sp < 0 ? b.substring(4) : b.substring(4, sp);
        String state = parseStatus(sp < 0 ? "" : b.substring(sp + 1)).get("state");
        if (ERROR.equals(state) || REMOVED.equals(state)) return false;
        return !(INSTALLED.equals(state) && !LINK_KEY.equals(key));
    }

    /** A status that says work is going on right now (as long as it is fresh: see {@link #BUSY_FRESH_MS}). */
    public static boolean busy(String state) {
        return QUEUED.equals(state) || LOOKUP.equals(state) || DOWNLOADING.equals(state) || INSTALLING.equals(state);
    }

    /** A "busy" line older than this is a daemon that died mid-way: the tile goes back to Install / Update. */
    public static final long BUSY_FRESH_MS = 4 * 60_000L;

    /**
     * The line under a tile's name. installedVc &lt; 0 = not installed; latestVc &lt; 0 = Stickman knows no newer
     * file for this stick; st / ageMs = the app's latest progress line and its age (st may be empty).
     */
    public static String tileLine(long installedVc, String installedVersion, long latestVc, String latestVersion, Map<String, String> st, long ageMs) {
        String state = st == null ? null : st.get("state");
        if (busy(state) && ageMs >= 0 && ageMs < BUSY_FRESH_MS) {
            if (DOWNLOADING.equals(state)) return "Downloading" + (st.get("pct") == null ? "…" : " " + st.get("pct") + "%");
            if (INSTALLING.equals(state)) return "Installing…";
            return "Starting…";
        }
        if (installedVc < 0) return "Not installed";
        if (latestVc > installedVc) return shortVersion(latestVersion).isEmpty() ? "Update available" : "Update to " + shortVersion(latestVersion);
        String v = shortVersion(installedVersion);
        return v.isEmpty() ? "Installed" : "Installed · " + v;
    }

    public static final int TONE_QUIET = 0, TONE_ACTIVE = 1, TONE_GOOD = 2, TONE_OFF = 3;
    public static final String NEEDS_NEWER = "Needs a newer Fire OS", NO_BUILD = "No build for this Fire TV";

    /** How a tile's line is coloured: something to do or going on, installed and current, cannot be had, or plain. */
    public static int lineTone(String line) {
        String l = line == null ? "" : line;
        if (l.startsWith("Update") || l.startsWith("Downloading") || l.startsWith("Installing") || l.startsWith("Starting")) return TONE_ACTIVE;
        if (l.startsWith("Installed")) return TONE_GOOD;
        if (l.equals(NEEDS_NEWER) || l.equals(NO_BUILD)) return TONE_OFF;
        return TONE_QUIET;
    }

    /**
     * A tile's line for a catalogue app that cannot be installed here, or null when it can: no file for this CPU
     * type (hasFile false), or the app needs a newer Android than this Fire TV has. An installed app keeps its
     * normal line either way.
     */
    public static String cannotInstall(long installedVc, boolean hasFile, int minSdk, int sdk) {
        if (installedVc >= 0) return null;
        if (!hasFile) return NO_BUILD;
        return minSdk > sdk ? NEEDS_NEWER : null;
    }

    /** pm's refusal in plain words. */
    public static String installError(String out) {
        String o = out == null ? "" : out;
        if (o.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") || o.contains("signatures do not match"))
            return "A copy from a different publisher build is installed. Uninstall it first, then install again (its data is lost).";
        if (o.contains("INSTALL_FAILED_VERSION_DOWNGRADE")) return "The installed version is newer than this one.";
        if (o.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE")) return "Not enough free storage on the Fire TV.";
        if (o.contains("INSTALL_FAILED_NO_MATCHING_ABIS")) return "This file is built for a different kind of processor.";
        if (o.contains("INSTALL_FAILED_OLDER_SDK")) return "This app needs a newer Fire OS.";
        if (o.contains("INSTALL_PARSE_FAILED") || o.contains("INSTALL_FAILED_INVALID_APK")) return "The file is not a valid app.";
        String t = o.trim().replaceAll("\\s+", " ");
        return "The Fire TV refused the install" + (t.isEmpty() ? "." : ": " + (t.length() > 140 ? t.substring(0, 140) : t));
    }

    /** "versionCode=2103000 minSdk=21 ..." (dumpsys package) -> 2103000; -1 when the package is not installed. */
    public static long installedVersionCode(String dumpsys) {
        Matcher m = Pattern.compile("versionCode=(\\d+)").matcher(dumpsys == null ? "" : dumpsys);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** Categories in the order the screen shows them (the catalogue's own order of first appearance). */
    public static List<String> categories(List<Entry> catalog) {
        List<String> out = new ArrayList<>();
        for (Entry e : catalog) if (!out.contains(e.category)) out.add(e.category);
        return out;
    }

    /** Most used first: GitHub's download count of the release file, then stars; unknown counts keep catalogue order. */
    public static List<Entry> byPopularity(List<Entry> catalog) {
        List<Entry> out = new ArrayList<>(catalog);
        final List<Entry> order = catalog;
        Collections.sort(out, (a, b) -> {
            long x = Math.max(a.downloads, a.stars), y = Math.max(b.downloads, b.stars);
            if (x != y && x > 0 && y > 0) return Long.compare(y, x);
            return Integer.compare(order.indexOf(a), order.indexOf(b));
        });
        return out;
    }

    // ---- the catalogue's address ----

    /** A developer's catalogue URL from the debug property (shell sets it), or null when it is not a plain URL. */
    public static String catalogOverride(String prop) {
        String p = prop == null ? "" : prop.trim();
        return p.matches("https?://[A-Za-z0-9.:-]+/[A-Za-z0-9._/-]*") ? p : null;
    }

    public static String catalogUrl(String prop) {
        String o = catalogOverride(prop);
        return o != null ? o : CATALOG_URL;
    }

    /** Art lives next to the catalogue: FOLDER/icons/ID.png. */
    public static String artUrl(String catalogUrl, String id) {
        return catalogUrl.substring(0, catalogUrl.lastIndexOf('/') + 1) + "icons/" + id + ".png";
    }

    // ---- arguments of the spool actions ----

    /** "ID PKG" (app_install) -> {id, pkg}; pkg is null when missing or not a package name; null = no usable id. */
    public static String[] installArg(String arg) {
        String[] t = (arg == null ? "" : arg.trim()).split("\\s+");
        if (t.length < 1 || !t[0].matches("[a-z0-9_-]{1,40}")) return null;
        return new String[] { t[0], t.length > 1 && validPkg(t[1]) ? t[1] : null };
    }

    /** "PKG 0|1" (app_auto) -> {pkg, "0" or "1"}, or null. */
    public static String[] autoArg(String arg) {
        String[] t = (arg == null ? "" : arg.trim()).split("\\s+");
        if (t.length != 2 || !validPkg(t[0]) || !(t[1].equals("0") || t[1].equals("1"))) return null;
        return t;
    }

    // ---- downloads ----

    public static final String NOT_AN_APP = "That is a web page or another kind of file, not an app.";
    public static final String MISMATCH = "The download does not match the catalogue. Try again later.";
    public static final String NOT_TYPED = "That is not a link, a GitHub project or a Downloader code.";
    /** A downloaded link waits this long for the user's yes; the catalogue is re-read when older than CATALOG_FRESH_MS. */
    public static final long STAGED_MS = 15 * 60_000L, CATALOG_FRESH_MS = 10 * 60_000L, RECHECK_MS = 30 * 60_000L;
    /** At most one progress line per this long. */
    public static final long PROGRESS_MS = 2000;

    /** Why a finished download cannot be an app file (size, zip magic), or null when it can. */
    public static String looksLikeApk(long size, byte[] head) {
        if (size < MIN_APK || size > MAX_APK) return NOT_AN_APP;
        return head != null && head.length >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4 ? null : NOT_AN_APP;
    }

    /** Why a typed line was refused, in a way the user can act on. */
    public static String whyNot(String typed) {
        String t = typed == null ? "" : typed.trim();
        if (t.regionMatches(true, 0, "http://", 0, 7)) return "Plain http links are not accepted: the file could be swapped on the way. Use an https link.";
        return NOT_TYPED;
    }

    public static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        if (b != null) for (byte x : b) { s.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16)); }
        return s.toString();
    }

    /** 0-100, or -1 when the server did not say how big the file is. */
    public static int pct(long done, long total) {
        if (total <= 0 || done < 0) return -1;
        return (int) Math.min(100, done * 100 / total);
    }

    public static String spaceMessage(long needed, long free) {
        return "Not enough free storage: needs about " + mb(needed) + ", " + mb(Math.max(free, 0)) + " free.";
    }

    public static boolean stagedExpired(long ageMs) { return ageMs > STAGED_MS; }

    /**
     * What identifies the file behind a link without downloading it: "length|date" (the date is the same on every
     * mirror of a download site, unlike ETags and host names), else "length|etag". "" when the server says nothing.
     */
    public static String signature(long length, String lastModified, String etag) {
        String v = lastModified != null && !lastModified.trim().isEmpty() ? lastModified.trim()
            : etag != null && !etag.trim().isEmpty() ? etag.trim() : null;
        if (length < 0 && v == null) return "";
        return (length < 0 ? "" : Long.toString(length)) + "|" + (v == null ? "" : v.replace('|', '_').replace('\t', ' ').replace('\n', ' '));
    }

    /** Where the part a server sends begins: 500 for "bytes 500-999/1000"; -1 when the header is missing or odd. */
    public static long rangeStart(String contentRange) {
        Matcher m = Pattern.compile("^\\s*bytes\\s+(\\d{1,18})-").matcher(contentRange == null ? "" : contentRange);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** The whole file's size from a part's header: 1000 for "bytes 500-999/1000"; -1 when it is missing or unknown ("*"). */
    public static long rangeTotal(String contentRange) {
        Matcher m = Pattern.compile("/\\s*(\\d{1,18})\\s*$").matcher(contentRange == null ? "" : contentRange);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** What identifies a GitHub release file: its link (new for each tagged release) and its size (for fixed tags). */
    public static String repoTag(Asset a) { return a == null ? "" : a.url + "|" + a.size; }

    /**
     * The tag of what a source offers now: the release file's own (a GitHub project: where is set by the lookup),
     * else the downloaded or looked-at file's signature.
     */
    public static String tagOf(String where, String signature) {
        return where != null && !where.isEmpty() ? where : signature == null ? "" : signature;
    }

    /** The source now offers something other than what was installed. Nothing known now = no reason to fetch. */
    public static boolean newTag(String stored, String now) {
        return now != null && !now.isEmpty() && !now.equals(stored == null ? "" : stored);
    }

    /** The file behind a tracked app's link turned out to be another app. */
    public static String otherApp(String found) { return "The file is a different app (" + found + ") than expected."; }

    /** How long a failed automatic update waits before the next try: an hour more each time, at most six. */
    public static long retryAfterMs(int failures) { return 3600_000L * Math.min(Math.max(failures, 1), 6); }

    /** "package:a.b" lines of "pm list packages" as names; empty when the answer is not a package list at all. */
    public static java.util.Set<String> packages(String pmList) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (pmList != null) for (String l : pmList.split("\n")) {
            String t = l.trim();
            if (t.startsWith("package:") && validPkg(t.substring(8).trim())) out.add(t.substring(8).trim());
        }
        return out;
    }

    /** pm's uninstall refusal in plain words. */
    public static String removeError(String out) {
        String o = out == null ? "" : out;
        if (o.contains("DELETE_FAILED_INTERNAL_ERROR") || o.contains("DELETE_FAILED_DEVICE_POLICY_MANAGER") || o.contains("DELETE_FAILED_OWNER_BLOCKED"))
            return "The Fire TV refused: apps that are part of the system cannot be removed.";
        if (o.contains("not installed") || o.contains("Unknown package")) return "That app is not installed.";
        String t = o.trim().replaceAll("\\s+", " ");
        return "The Fire TV refused to uninstall it" + (t.isEmpty() ? "." : ": " + (t.length() > 140 ? t.substring(0, 140) : t));
    }

    // ---- the screen ----

    /** Amazon's Appstore: apps it installed are updated by it, so they are not Stickman's business. */
    public static final String STORE_INSTALLER = "com.amazon.venezia";

    /** An app the user put on the Fire TV outside the Appstore (and not Stickman itself): listed under "On this Fire TV". */
    public static boolean sideloaded(boolean system, String installer, String pkg) {
        return !system && !STORE_INSTALLER.equals(installer) && validPkg(pkg) && !pkg.equals(EnsureScript.PKG) && !pkg.equals(Edition.STORE_PKG);
    }

    /** "kodi.tv" from "https://kodi.tv/download", for "from kodi.tv"; "" when there is no host. */
    public static String host(String url) {
        Matcher m = Pattern.compile("^https?://(?:www\\.)?([A-Za-z0-9.-]+)").matcher(url == null ? "" : url);
        return m.find() ? m.group(1) : "";
    }

    /** Where a tracked app comes from, for its dialog. */
    public static String sourceText(Source s) {
        if (s == null) return "";
        if (CATALOG.equals(s.kind)) return "Stickman's catalogue";
        if (REPO.equals(s.kind)) return "GitHub project " + s.value;
        if (CODE.equals(s.kind)) return "Downloader code " + s.value;
        String h = host(s.value);
        return h.isEmpty() ? "a link" : "a link on " + h;
    }

    /** The same for the short kind word of a "STATUS apps" line. */
    public static String kindText(String kind) {
        if (CATALOG.equals(kind)) return "Stickman's catalogue";
        if (REPO.equals(kind)) return "a GitHub project";
        if (CODE.equals(kind)) return "a Downloader code";
        return URL.equals(kind) ? "a link" : "";
    }

    public static final String B_INSTALL = "Install", B_UPDATE = "Update", B_OPEN = "Open", B_UNINSTALL = "Uninstall", B_REPLACE = "Replace";

    /**
     * The action buttons of an app's dialog, most likely first. installedVc &lt; 0 = not installed; update = a newer
     * file is known; removable = not part of the system; needsNewerOs = the catalogue file cannot run here;
     * otherBuild = the copy on the Fire TV is signed by someone else than the catalogue's (see {@link #sameBuild}):
     * it cannot be updated, only replaced, which costs the app's data and is therefore never the first button.
     */
    public static List<String> buttons(long installedVc, boolean update, boolean canOpen, boolean removable, boolean needsNewerOs, boolean otherBuild) {
        List<String> out = new ArrayList<>();
        if (installedVc < 0) {
            if (!needsNewerOs) out.add(B_INSTALL);
            return out;
        }
        if (otherBuild) {
            if (canOpen) out.add(B_OPEN);
            if (update && removable && !needsNewerOs) out.add(B_REPLACE);
            if (removable) out.add(B_UNINSTALL);
            return out;
        }
        if (update && !needsNewerOs) out.add(B_UPDATE);
        if (canOpen) out.add(B_OPEN);
        if (removable) out.add(B_UNINSTALL);
        return out;
    }

    /**
     * Whether the copy of an app on the Fire TV comes from the same publisher build as the catalogue's file: its
     * signing certificates (SHA-256, lower-case hex) include the catalogue's. Null when either side is unknown.
     * Android only lets a file replace an installed app signed with the same key. The Amazon Appstore signs the apps
     * it delivers with its own, so VLC from the Appstore can never be updated with VideoLAN's own file: offering
     * that update would only download it to be refused (seen on the first real try, 47 MB).
     */
    public static Boolean sameBuild(String catalogSigner, java.util.Collection<String> installedSigners) {
        if (catalogSigner == null || !catalogSigner.matches("[0-9a-f]{64}") || installedSigners == null || installedSigners.isEmpty()) return null;
        return installedSigners.contains(catalogSigner);
    }

    /** A tile's line for an installed app that is another build than the catalogue's: where it is from, no update. */
    public static String otherBuildLine(String installedVersion, String installer) {
        String v = shortVersion(installedVersion);
        return "Installed" + (v.isEmpty() ? "" : " · " + v) + (STORE_INSTALLER.equals(installer) ? " · Appstore" : " · other build");
    }

    /** The dialog's explanation for such an app; canReplace = a newer catalogue file exists and the copy can be removed. */
    public static String otherBuildText(String installer, boolean canReplace) {
        String from = STORE_INSTALLER.equals(installer)
            ? "The copy on this Fire TV comes from the Amazon Appstore, which keeps it up to date itself."
            : "The copy on this Fire TV is a different build of this app, signed by someone else.";
        return from + " Android does not let one build update another, so Stickman leaves it alone."
            + (canReplace ? " Replace uninstalls it and installs this version instead: what the app saved is lost." : "");
    }

    /** The question before a Replace. */
    public static String replaceText(String name, String version) {
        String v = shortVersion(version);
        return "The copy on this Fire TV is uninstalled, with everything it saved, and " + name + (v.isEmpty() ? "" : " " + v)
            + " from this catalogue is installed in its place. From then on Stickman keeps it up to date.";
    }

    /** What to tell the user when an app's work ended (name = what the screen calls the app); null = still going. */
    public static String announce(Map<String, String> st, String name) {
        String state = st == null ? null : st.get("state");
        String n = name == null || name.isEmpty() ? "The app" : name;
        if (INSTALLED.equals(state)) return n + " is installed.";
        if (REMOVED.equals(state)) return n + " is uninstalled.";
        if (UPTODATE.equals(state)) return n + " is already up to date.";
        if (ERROR.equals(state)) return st.get("msg") == null ? "Something went wrong with " + n + "." : st.get("msg");
        return null;
    }

    /** The result line while a typed link is on its way (state lookup / downloading / installing), else null. */
    public static String linkProgress(Map<String, String> st) {
        String state = st == null ? null : st.get("state");
        if (QUEUED.equals(state)) return "Starting…";
        if (LOOKUP.equals(state)) return "Looking it up…";
        if (DOWNLOADING.equals(state)) return "Downloading" + (st.get("pct") == null ? "…" : " " + st.get("pct") + "%…");
        if (INSTALLING.equals(state)) return "Installing…";
        return null;
    }

    /** The question before a typed link is installed. name = the catalogue's name for the package, or null. */
    public static String confirmText(String name, String pkg, long vc, long size, long installedVc) {
        return confirmText(name, pkg, null, vc, size, installedVc);
    }

    /** As above, with the version name the file declares ("1.11.0"; null when it does not say). */
    public static String confirmText(String name, String pkg, String versionName, long vc, long size, long installedVc) {
        StringBuilder b = new StringBuilder();
        b.append(name == null || name.isEmpty() ? pkg : name + "  (" + pkg + ")");
        if (versionName != null && !versionName.isEmpty()) b.append("\nVersion ").append(versionName).append("  (code ").append(vc < 0 ? "?" : Long.toString(vc)).append(')');
        else b.append("\nVersion code ").append(vc < 0 ? "?" : Long.toString(vc));
        if (size > 0) b.append("  ·  ").append(mb(size));
        b.append("\n\n");
        if (installedVc < 0) b.append("It is not on this Fire TV yet.");
        else if (vc > installedVc) b.append("This updates the copy on this Fire TV (version code ").append(installedVc).append(").");
        else if (vc == installedVc) b.append("The same version is already on this Fire TV.");
        else b.append("The copy on this Fire TV is newer (version code ").append(installedVc).append("): the Fire TV will refuse this one.");
        b.append("\n\nStickman keeps it up to date from the same place while the TV is asleep. You can turn that off on its tile.");
        return b.toString();
    }

    /** The msg of a confirm line: what is installed now, and the file's version name when it has one. */
    public static String confirmMsg(long installedVc, String versionName) {
        return "installed=" + (installedVc < 0 ? "-" : Long.toString(installedVc))
            + (versionName != null && versionName.matches("[A-Za-z0-9._+()-]{1,40}") ? " version=" + versionName : "");
    }

    /** "version=1.11.0" from a confirm line's msg, or null. */
    public static String confirmVersion(String msg) {
        Matcher m = Pattern.compile("version=([A-Za-z0-9._+()-]{1,40})").matcher(msg == null ? "" : msg);
        return m.find() ? m.group(1) : null;
    }

    /** "installed=123" / "installed=-" from a confirm line's msg -> 123 / -1. */
    public static long confirmInstalled(String msg) {
        Matcher m = Pattern.compile("installed=(\\d+)").matcher(msg == null ? "" : msg);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }
}
