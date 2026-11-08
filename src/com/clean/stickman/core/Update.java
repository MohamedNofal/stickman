package com.clean.stickman.core;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-update for the GitHub edition (the Appstore edition is updated by Amazon and never runs this). stickd asks
 * GitHub for the latest release, downloads its stickman.apk next to the daemons and installs it as shell with
 * {@code pm install -r}: Android refuses an APK that isn't signed with the installed app's key, so only our own
 * builds can ever replace Stickman. Pure: the daemon (daemon/Updater) does the network and the install.
 */
public final class Update {
    private Update() {}

    public static final String REPO = "MohamedNofal/stickman";
    public static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    public static final String ASSET = "stickman.apk";
    public static final String URL_PREFIX = "https://github.com/" + REPO + "/releases/download/";
    public static final String FILE = Originals.DIR + "/update.apk";
    public static final String TAG_FILE = Originals.DIR + "/update.tag";
    /** How often stickd asks GitHub (a release page is a few KB). */
    public static final long CHECK_EVERY_MS = 6 * 3600_000L;
    /** A real Stickman APK is ~1.2 MB; anything far outside this range is not one. */
    public static final long MIN_APK = 200_000, MAX_APK = 50_000_000;

    public static final class Release {
        public final String tag, version, url;
        public final long size;
        Release(String tag, String version, String url, long size) { this.tag = tag; this.version = version; this.url = url; this.size = size; }
    }

    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DL = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SIZE = Pattern.compile("\"size\"\\s*:\\s*(\\d+)");

    /**
     * The latest release from GitHub's releases/latest JSON: its tag ("v1.8.2"), version ("1.8.2") and the
     * stickman.apk asset of this repo, or null when there is none or anything looks off. Drafts and pre-releases
     * never appear in releases/latest.
     */
    public static Release parse(String json) { return parse(json, URL_PREFIX); }

    /** As {@link #parse(String)}, accepting assets under prefix (GitHub's, or a test server's from {@link #TEST_FILE}). */
    public static Release parse(String json, String prefix) {
        if (json == null) return null;
        Matcher t = TAG.matcher(json);
        if (!t.find()) return null;
        String tag = t.group(1);
        String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        if (!version.matches("\\d+(\\.\\d+){0,3}")) return null;
        Matcher d = DL.matcher(json);
        while (d.find()) {
            String url = d.group(1);
            if (!urlAllowed(url, prefix)) continue;
            // the asset object's "size" sits before its download url
            int open = json.lastIndexOf('{', d.start());
            Matcher s = SIZE.matcher(json.substring(Math.max(0, open), d.start()));
            long size = s.find() ? Long.parseLong(s.group(1)) : -1;
            return new Release(tag, version, url, size);
        }
        return null;
    }

    public static final String TAG_API = "https://api.github.com/repos/" + REPO + "/releases/tags/";

    private static final Pattern BODY = Pattern.compile("\"body\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** The release notes (GitHub's "body", Markdown) of a release JSON, unescaped; "" when there are none. */
    public static String notes(String json) {
        if (json == null) return "";
        Matcher m = BODY.matcher(json);
        return m.find() ? unescape(m.group(1)) : "";
    }

    static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { b.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': b.append('\n'); break;
                case 'r': break;
                case 't': b.append(' '); break;
                case 'u':
                    if (i + 4 < s.length()) { try { b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; } catch (NumberFormatException e) { b.append('u'); } }
                    break;
                default: b.append(n);
            }
        }
        return b.toString();
    }

    /**
     * Release notes as plain text for a TV dialog: headings become their own lines, list items get a bullet,
     * Markdown emphasis, code ticks and links are reduced to their text, and the install footer is dropped.
     */
    public static String plainNotes(String md) {
        if (md == null) return "";
        StringBuilder out = new StringBuilder();
        for (String raw : md.split("\n")) {
            String l = raw.trim();
            if (l.startsWith("Install:")) continue;
            if (l.startsWith("#")) { l = l.replaceFirst("^#+\\s*", ""); if (out.length() > 0) out.append('\n'); out.append(clean(l).toUpperCase(java.util.Locale.ROOT)).append('\n'); continue; }
            if (l.startsWith("- ") || l.startsWith("* ")) { out.append("•  ").append(clean(l.substring(2))).append('\n'); continue; }
            if (l.isEmpty()) continue;
            out.append(clean(l)).append('\n');
        }
        return out.toString().trim();
    }

    static String clean(String s) {
        return s.replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1").replace("**", "").replace("`", "").replaceAll("(?<![A-Za-z0-9])\\*([^*]+)\\*(?![A-Za-z0-9])", "$1");
    }

    /** Only this repo's release downloads named stickman.apk, over https. */
    public static boolean urlAllowed(String url) { return urlAllowed(url, URL_PREFIX); }

    static boolean urlAllowed(String url, String prefix) {
        return url != null && prefix != null && url.startsWith(prefix) && url.endsWith("/" + ASSET)
            && url.substring(prefix.length()).matches("([A-Za-z0-9._-]+/)?" + Pattern.quote(ASSET))
            && !url.substring(prefix.length()).startsWith("..");
    }

    /**
     * Developer testing only: if this file exists (only shell can create it) its first line is a release-JSON URL
     * used instead of GitHub's, and assets under the same folder are accepted. pm's signature check still applies.
     */
    public static final String TEST_FILE = Originals.DIR + "/update.test";

    /** {api, assetPrefix} for a test file's content, or null when it isn't a usable http(s) URL. */
    public static String[] testSource(String content) {
        if (content == null) return null;
        String u = content.trim().split("\n")[0].trim();
        if (!u.matches("https?://[A-Za-z0-9.:-]+/[A-Za-z0-9._/-]*")) return null;
        return new String[] { u, u.substring(0, u.lastIndexOf('/') + 1) };
    }

    /** Numeric dotted compare: "1.10" > "1.9", "1.8.1" > "1.8", unparsable parts count as 0. */
    public static int compare(String a, String b) {
        String[] x = a == null ? new String[0] : a.trim().split("\\."), y = b == null ? new String[0] : b.trim().split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    static int num(String s) {
        Matcher m = Pattern.compile("^(\\d{1,6})").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    public static boolean newer(String latest, String current) { return latest != null && compare(latest, current) > 0; }

    /** A download is plausible: right size range, matches GitHub's reported size, and starts like a zip. */
    public static boolean plausibleApk(long length, long expected, byte[] head) {
        if (length < MIN_APK || length > MAX_APK) return false;
        if (expected > 0 && length != expected) return false;
        return head != null && head.length >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
    }

    /**
     * The binary AndroidManifest.xml declares pkg: its string pool holds pkg as an exact UTF-16 string (length
     * prefix, then the characters, then a 0 terminator). pm then checks the signing key matches the installed app.
     */
    public static boolean manifestNames(byte[] axml, String pkg) {
        if (axml == null || pkg == null) return false;
        byte[] want = new byte[2 + pkg.length() * 2 + 2];
        want[0] = (byte) pkg.length();
        want[1] = 0;
        byte[] s = pkg.getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(s, 0, want, 2, s.length);
        outer:
        for (int i = 0; i + want.length <= axml.length; i++) {
            for (int j = 0; j < want.length; j++) if (axml[i + j] != want[j]) continue outer;
            return true;
        }
        return false;
    }

    /** Ask about latest once: it's newer than what's installed and the user hasn't said "Not now" to it. */
    public static boolean shouldOffer(String latest, String installed, String declined) {
        return newer(latest, installed) && !latest.equals(declined);
    }

    /** Show "what's new" after an update: the app now runs a newer version than the last one it saw (not a fresh install). */
    public static boolean justUpdated(String installed, String lastSeen) {
        return lastSeen != null && newer(installed, lastSeen);
    }

    /** "versionName=1.8.1" (dumpsys package) -> "1.8.1", else null. */
    public static String installedVersion(String dumpsysLine) {
        Matcher m = Pattern.compile("versionName=(\\d+(?:\\.\\d+){0,3})").matcher(dumpsysLine == null ? "" : dumpsysLine);
        return m.find() ? m.group(1) : null;
    }

    /**
     * stickd's status line, "STATUS update state=S current=C latest=L msg=...". States: uptodate, available,
     * downloading, ready (downloaded, waiting for the TV to sleep), installing, installed, error.
     */
    public static String status(String state, String current, String latest, String msg) {
        return "STATUS update state=" + state + " current=" + (current == null ? "-" : current)
            + " latest=" + (latest == null ? "-" : latest) + (msg == null || msg.isEmpty() ? "" : " msg=" + msg.replace('\n', ' '));
    }

    /** key -> value of a status line (or its tail after "STATUS update"); msg is everything after "msg=". */
    public static Map<String, String> parseStatus(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null) return m;
        int mi = s.indexOf(" msg=");
        String head = mi >= 0 ? s.substring(0, mi) : s;
        if (mi >= 0) m.put("msg", s.substring(mi + 5).trim());
        Matcher kv = Pattern.compile("(state|current|latest)=(\\S+)").matcher(head);
        while (kv.find()) m.put(kv.group(1), kv.group(2));
        return m;
    }

    /** The Settings line for a status, in plain words. */
    public static String describe(Map<String, String> st, String installed) {
        String state = st.get("state"), latest = st.get("latest");
        String v = "Version " + (installed == null ? "?" : installed);
        if (state == null) return v + ". Not checked yet.";
        switch (state) {
            case "uptodate": return v + " is the latest.";
            case "available": return v + ". Version " + latest + " is available.";
            case "downloading": return v + ". Downloading " + latest + "…";
            case "ready": return v + ". Version " + latest + " is downloaded and installs when the TV goes to sleep.";
            case "installing": return "Installing " + latest + "… Stickman restarts by itself.";
            case "installed": return v + ". Updated to " + latest + ".";
            case "error": return v + ". Update check failed: " + (st.get("msg") == null ? "unknown error" : st.get("msg"));
            default: return v + ".";
        }
    }
}
