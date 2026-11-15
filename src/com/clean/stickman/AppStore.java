package com.clean.stickman;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.clean.stickman.core.Apps;
import com.clean.stickman.core.cmd.AppsCmds;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * What the Apps page shows, fetched off the main thread: the catalogue (over https, the last good copy kept in the
 * app's files for when the Fire TV is offline), its art (cached for a week), and what is installed (PackageManager).
 * The daemon reads the same catalogue itself and trusts nothing from here: this copy only decides what is drawn.
 * Every change ends in {@link Listener#changed} on the main thread.
 */
final class AppStore {
    interface Listener {
        /** The catalogue or the installed list changed. */
        void changed();
        /** The art of one tile arrived (key = catalogue id, or "pkg:" + package for an app's own icon). */
        void art(String key);
    }

    /** One installed package, as far as the page needs it. */
    static final class Installed {
        String pkg, label, version = "", installer;
        long vc;
        boolean system, canOpen;
        /** SHA-256 (hex) of the certificates the app is signed with; only read for catalogue apps (Apps.sameBuild). */
        Set<String> signers = Collections.emptySet();
    }

    static final String TAG = "Stickman";
    static final String SAVED = "apps-catalog.json", ART_DIR = "apps-art";
    static final long ART_FRESH_MS = 7 * 24 * 3600_000L;
    static final int MAX_JSON = 2 * 1024 * 1024, MAX_ART = 1024 * 1024;
    /** The app's bridge is a VPN that takes this app in: while it comes up or goes down our connections are cut. */
    static final int TRIES = 3;
    static final long RETRY_MS = 2500;

    final Context c;
    final Listener listener;
    final Handler main = new Handler(Looper.getMainLooper());
    /** Idle threads end after a while: a pool that keeps its threads would keep this store, the page and its activity. */
    final ExecutorService data = pool(1, "apps-data"), pics = pool(3, "apps-art");

    volatile List<Apps.Entry> catalog = Collections.emptyList();
    volatile String catalogUrl = Apps.CATALOG_URL;
    volatile Map<String, Installed> installed = Collections.emptyMap();
    /** A catalogue fetch has ended (well or not); saved = what is shown is the copy kept from an earlier visit. */
    volatile boolean loaded, saved;
    volatile long catalogAt;
    final Map<String, Bitmap> art = new ConcurrentHashMap<>();
    final Map<String, Drawable> icons = new ConcurrentHashMap<>();
    private final Set<String> asked = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    AppStore(Context ctx, Listener l) {
        c = ctx.getApplicationContext();
        listener = l;
    }

    private static ExecutorService pool(int threads, final String name) {
        ThreadPoolExecutor p = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
        p.allowCoreThreadTimeOut(true);
        return p;
    }

    /** This Fire TV's CPU types, best first. */
    static List<String> abis() { return Apps.abis(join(Build.SUPPORTED_ABIS)); }

    private static String join(String[] a) {
        StringBuilder b = new StringBuilder();
        if (a != null) for (String s : a) { if (b.length() > 0) b.append(','); b.append(s); }
        return b.toString();
    }

    // ---- the catalogue ----

    /** Fetches the catalogue when the one in hand is older than Apps.CATALOG_FRESH_MS (or force), then rescans. */
    void refresh(final boolean force) {
        data.execute(() -> {
            try {
                if (force || catalog.isEmpty() || System.currentTimeMillis() - catalogAt > Apps.CATALOG_FRESH_MS) loadCatalog();
                for (Apps.Entry e : catalog) art(e);   // whatever did not arrive last time (offline, or a cut connection)
                installed = scan();
            } catch (Throwable t) {
                Log.w(TAG, "apps refresh: " + t);
            }
            loaded = true;
            main.post(listener::changed);
        });
    }

    /** Only the installed list (after an install or uninstall). */
    void rescan() {
        data.execute(() -> {
            try {
                installed = scan();
            } catch (Throwable t) {
                Log.w(TAG, "apps scan: " + t);
            }
            main.post(listener::changed);
        });
    }

    private void loadCatalog() {
        String url = Apps.catalogUrl(prop(Apps.CATALOG_PROP));
        File keep = new File(c.getFilesDir(), SAVED);
        byte[] raw = fetch(url, MAX_JSON);
        List<Apps.Entry> fresh = raw == null ? new ArrayList<Apps.Entry>() : Apps.parseCatalog(new String(raw, StandardCharsets.UTF_8));
        if (!fresh.isEmpty()) {
            write(keep, raw);
            catalog = fresh;
            catalogUrl = url;
            catalogAt = System.currentTimeMillis();
            saved = false;
        } else if (catalog.isEmpty()) {
            byte[] old = read(keep, MAX_JSON);
            List<Apps.Entry> kept = old == null ? new ArrayList<Apps.Entry>() : Apps.parseCatalog(new String(old, StandardCharsets.UTF_8));
            if (!kept.isEmpty()) { catalog = kept; catalogUrl = url; saved = true; }
        }
    }

    /** A system property (debug.* ones are readable by every app), or "". */
    private static String prop(String name) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(AppsCmds.catalogPropArgv(name));
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l = r.readLine();
                return l == null ? "" : l.trim();
            }
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }

    // ---- art ----

    private void art(final Apps.Entry e) {
        if (art.containsKey(e.id) || !asked.add(e.id)) return;
        final String url = Apps.artUrl(catalogUrl, e.id);
        pics.execute(() -> {
            try {
                File dir = new File(c.getCacheDir(), ART_DIR);
                dir.mkdirs();
                File f = new File(dir, e.id + ".png");
                boolean have = f.isFile() && f.length() > 0;
                if (!have || System.currentTimeMillis() - f.lastModified() > ART_FRESH_MS) {
                    byte[] raw = fetch(url, MAX_ART);
                    if (raw != null && BitmapFactory.decodeByteArray(raw, 0, raw.length) != null) { write(f, raw); have = true; }
                }
                Bitmap b = have ? BitmapFactory.decodeFile(f.getPath()) : null;
                if (b == null) { asked.remove(e.id); return; }   // offline: asked for again at the next refresh
                art.put(e.id, b);
                main.post(() -> listener.art(e.id));
            } catch (Throwable t) {
                asked.remove(e.id);
            }
        });
    }

    /** The app's own banner (TV launcher art) or icon, for an installed app that is not in the catalogue. */
    void icon(final String pkg) {
        final String key = "pkg:" + pkg;
        if (icons.containsKey(key) || !asked.add(key)) return;
        pics.execute(() -> {
            try {
                PackageManager pm = c.getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                Drawable d = pm.getApplicationBanner(ai);
                if (d == null) d = pm.getApplicationIcon(ai);
                if (d == null) return;
                icons.put(key, d);
                main.post(() -> listener.art(key));
            } catch (Throwable t) {
                asked.remove(key);
            }
        });
    }

    // ---- what is installed ----

    private Map<String, Installed> scan() {
        PackageManager pm = c.getPackageManager();
        Map<String, Installed> out = new HashMap<>();
        Set<String> known = new java.util.HashSet<>();
        for (Apps.Entry e : catalog) known.add(e.pkg);
        for (PackageInfo pi : pm.getInstalledPackages(0)) {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null || pi.packageName == null) continue;
            Installed i = new Installed();
            i.pkg = pi.packageName;
            i.vc = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            if (pi.versionName != null) i.version = pi.versionName;
            i.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            try {
                i.installer = pm.getInstallerPackageName(i.pkg);
            } catch (Exception e) {
                i.installer = null;
            }
            i.label = i.pkg;
            // names and launch intents cost a round trip each: only for the apps the page can show
            if (known.contains(i.pkg) || Apps.sideloaded(i.system, i.installer, i.pkg)) {
                try {
                    CharSequence l = pm.getApplicationLabel(ai);
                    if (l != null && l.length() > 0) i.label = l.toString();
                } catch (Exception ignored) {
                }
                i.canOpen = launchIntent(pm, i.pkg) != null;
            }
            if (known.contains(i.pkg)) i.signers = signers(pm, i.pkg);
            out.put(i.pkg, i);
        }
        return out;
    }

    /**
     * The certificates pkg is signed with, as SHA-256 hex: what apksigner calls "certificate SHA-256 digest", which
     * is what the catalogue records. Both the classic list and, from Android 9, the current signers and their
     * history (read by reflection: those members do not exist on Fire OS 5 and 6).
     */
    @SuppressWarnings("deprecation")
    private static Set<String> signers(PackageManager pm, String pkg) {
        Set<String> out = new java.util.HashSet<>();
        try {
            add(out, pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures);
        } catch (Exception ignored) {
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                Object info = PackageInfo.class.getField("signingInfo").get(pm.getPackageInfo(pkg, 0x08000000 /* GET_SIGNING_CERTIFICATES */));
                if (info != null) {
                    add(out, (android.content.pm.Signature[]) info.getClass().getMethod("getApkContentsSigners").invoke(info));
                    add(out, (android.content.pm.Signature[]) info.getClass().getMethod("getSigningCertificateHistory").invoke(info));
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static void add(Set<String> out, android.content.pm.Signature[] sigs) {
        if (sigs == null) return;
        for (android.content.pm.Signature s : sigs) {
            try {
                out.add(Apps.hex(java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));
            } catch (Exception ignored) {
            }
        }
    }

    /** The TV launcher entry of pkg, else its ordinary one, else null (an app with nothing to open). */
    static Intent launchIntent(PackageManager pm, String pkg) {
        try {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            return i != null ? i : pm.getLaunchIntentForPackage(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- https and files ----

    /** The body of url (https only), at most max bytes, or null. Tried again when a connection is cut. */
    static byte[] fetch(String url, int max) {
        for (int i = 0; i < TRIES; i++) {
            HttpURLConnection h = null;
            try {
                h = (HttpURLConnection) new URL(url).openConnection();
                h.setConnectTimeout(12_000);
                h.setReadTimeout(20_000);
                h.setRequestProperty("User-Agent", "Stickman-apps");
                int code = h.getResponseCode();
                if (code == 404 || code == 403 || code == 410) return null;
                if (code == 200) {
                    try (InputStream in = h.getInputStream()) {
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        byte[] buf = new byte[16 * 1024];
                        for (int k; (k = in.read(buf)) > 0; ) {
                            b.write(buf, 0, k);
                            if (b.size() > max) return null;
                        }
                        return b.toByteArray();
                    }
                }
            } catch (Exception e) {
                // cut or offline: try again
            } finally {
                if (h != null) h.disconnect();
            }
            if (i < TRIES - 1) {
                try { Thread.sleep(RETRY_MS); } catch (InterruptedException e) { return null; }
            }
        }
        return null;
    }

    private static byte[] read(File f, int max) {
        if (!f.isFile() || f.length() > max) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int k; (k = in.read(buf)) > 0; ) b.write(buf, 0, k);
            return b.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(File f, byte[] data) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(data);
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }
}
