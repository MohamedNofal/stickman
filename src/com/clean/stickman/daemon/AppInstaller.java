package com.clean.stickman.daemon;

import com.clean.stickman.core.Actions;
import com.clean.stickman.core.Apps;
import com.clean.stickman.core.Axml;
import com.clean.stickman.core.Edition;
import com.clean.stickman.core.cmd.AppsCmds;
import com.clean.stickman.core.cmd.AutoKillCmds;
import com.clean.stickman.core.cmd.UpdaterCmds;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The app installer (the rules and the big picture are in core/Apps; the screen is AppsPage). GitHub edition only.
 *
 * Everything runs on one worker thread, one job at a time: a catalogue install, a typed link / GitHub project /
 * Downloader code (downloaded, then held until the user says yes), an update, an uninstall, and every five minutes
 * the housekeeping: look for newer files every six hours, and install the ones found while the TV is asleep and
 * nothing plays. A user's action never waits behind an automatic download: that one gives way and is taken up again
 * at the next idle moment.
 *
 * Progress goes to the log as "STATUS app:KEY ..." lines (KEY = the package, or "link" for typed input until it is
 * confirmed), which the screen follows; "STATUS apps list=..." says what is being kept up to date.
 */
final class AppInstaller {
    static final long INSTALL_TIMEOUT_MS = 300_000, UNINSTALL_TIMEOUT_MS = 120_000, LIST_TIMEOUT_MS = 30_000;

    private final Stickd d;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stickd-apps");
        t.setDaemon(true);
        return t;
    });
    /** User actions queued or running: while there are any, an automatic download gives way. */
    private final AtomicInteger userWaiting = new AtomicInteger();
    /** Packages with a user action queued or running: a second press on the same tile is not a second job. */
    private final Set<String> inFlight = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** Bumped by every new typed link and every cancel: a link download that is no longer wanted stops. */
    private volatile int linkGen;

    // ---- everything below is touched on the worker thread only ----
    private Map<String, Apps.Tracked> tracked = new LinkedHashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private List<Apps.Entry> catalog = new ArrayList<>();
    private long catalogAt, lastCheck;
    private boolean checked;
    private Staged staged;
    private List<String> abis;
    private int sdk;

    /** A newer file known for a tracked app, waiting for the TV to be idle (or for the user). */
    private static final class Pending {
        final String pkg;
        /** A catalogue file's link and checksum; null for a link source, whose file is looked up when it is fetched. */
        String url, sha;
        /** A link source's new tag (see Apps.Tracked.tag). */
        String tag;
        long vc = -1;
        /** Downloaded and checked, but the TV was in use by then: installed at the next idle moment. */
        File file;
        int failures;
        long notBefore;
        Pending(String pkg) { this.pkg = pkg; }
    }

    /** A typed link's download, held until the user has seen what it is. */
    private static final class Staged {
        final File file;
        final Axml.Info info;
        final Apps.Source source;
        final String tag;
        final long at;
        Staged(File file, Axml.Info info, Apps.Source source, String tag, long at) { this.file = file; this.info = info; this.source = source; this.tag = tag; this.at = at; }
    }

    /** Where a source's file is right now. */
    private static final class Where {
        String url, tag = "", error;
    }

    /** What a download gave. */
    private static final class Got {
        Axml.Info info;
        String tag = "", error;
        boolean stopped, otherApp;
    }

    AppInstaller(Stickd d) { this.d = d; }

    void install() {
        if (Edition.STORE) return;
        worker.execute(() -> safe(this::start));   // before any action can be queued
        d.register(Actions.APP_INSTALL, a -> {
            String[] t = Apps.installArg(a);
            user(t != null && t[1] != null ? t[1] : Apps.LINK_KEY, () -> installFromCatalog(a));
        });
        d.register(Actions.APP_REPLACE, a -> {
            String[] t = Apps.installArg(a);
            user(t != null && t[1] != null ? t[1] : Apps.LINK_KEY, () -> replace(a));
        });
        // handlers run on the one spool thread: only it ever bumps linkGen
        d.register(Actions.APP_GET, a -> { final int gen = ++linkGen; user(Apps.LINK_KEY, () -> get(a, gen)); });
        d.register(Actions.APP_CONFIRM, a -> user(Apps.LINK_KEY, this::confirm));
        d.register(Actions.APP_CANCEL, a -> { linkGen++; worker.execute(() -> safe(this::cancel)); });
        d.register(Actions.APP_UPDATE, a -> user(Apps.validPkg(a) ? a : null, () -> update(a)));
        d.register(Actions.APP_REMOVE, a -> user(Apps.validPkg(a) ? a : null, () -> remove(a)));
        d.register(Actions.APP_AUTO, a -> worker.execute(() -> safe(() -> setAuto(a))));
        d.register(Actions.APPS_REFRESH, a -> worker.execute(() -> safe(this::refresh)));
        worker.scheduleWithFixedDelay(() -> safe(this::tick), 4, 5, TimeUnit.MINUTES);
    }

    /** Monotonic: the stick's clock jumps by days shortly after boot. */
    private static long now() { return System.nanoTime() / 1_000_000L; }

    private void safe(Runnable r) {
        try { r.run(); } catch (Throwable t) { Sh.log("apps error: " + t); }
    }

    /** A user's action: said to be queued at once (the tile reacts), then run on the worker. */
    private void user(final String key, final Runnable task) {
        final boolean pkgKey = key != null && !Apps.LINK_KEY.equals(key);
        if (pkgKey && !inFlight.add(key)) return;   // already on its way
        userWaiting.incrementAndGet();
        if (key != null) status(key, Apps.QUEUED, -1, null, -1, -1, null);
        worker.execute(() -> {
            try {
                safe(task);
            } finally {
                userWaiting.decrementAndGet();
                if (pkgKey) inFlight.remove(key);
            }
        });
    }

    private static void log(String line) { Sh.logLines(Collections.singletonList(line)); }

    private static void status(String key, String state, int pct, String pkg, long vc, long size, String msg) {
        log(Apps.status(key, state, pct, pkg, vc, size, msg));
    }

    private static void error(String key, String msg) { status(key, Apps.ERROR, -1, null, -1, -1, msg); }

    private void report() { log(Apps.trackedStatus(tracked, pending.keySet())); }

    // ---- what the worker knows ----

    /** First job: the tracked list, and no leftovers of a download a previous run did not finish. */
    private void start() {
        tracked = Apps.parseTracked(Stickd.read(new File(Apps.LIST)));
        File[] old = new File(Apps.DIR).listFiles();
        if (old != null) for (File f : old) f.delete();
    }

    private void facts() {
        if (abis != null) return;
        abis = Apps.abis(Sh.run(AppsCmds.abilist()));
        try {
            sdk = Integer.parseInt(Sh.run(AppsCmds.sdk()).trim());
        } catch (NumberFormatException e) {
            sdk = Integer.MAX_VALUE;   // unknown: refuse nothing here, the Fire TV itself still does
        }
    }

    private void refreshCatalog(boolean force) {
        long now = now();
        if (!force && !catalog.isEmpty() && now - catalogAt < Apps.CATALOG_FRESH_MS) return;
        String prop = Sh.run(AppsCmds.catalogProp(Apps.CATALOG_PROP));
        String url = Apps.catalogUrl(prop);
        // plain http only for a developer's own test server, named by a property only shell can set
        List<Apps.Entry> c = Apps.parseCatalog(Http.text(url, Apps.catalogOverride(prop) != null));
        if (!c.isEmpty()) { catalog = c; catalogAt = now; }
    }

    private long installedVc(String pkg) {
        return Apps.validPkg(pkg) ? Apps.installedVersionCode(Sh.run(AppsCmds.installed(pkg))) : -1;
    }

    private static File apkFile(String pkg) { return new File(Apps.DIR, pkg + ".apk"); }

    private void save() {
        try {
            Stickd.writeAtomic(new File(Apps.LIST), Apps.serializeTracked(tracked));
        } catch (Exception e) {
            Sh.log("apps: could not save the list: " + e);
        }
    }

    /** Forgets a waiting update (it was installed, replaced, or its app is gone). */
    private void drop(String pkg) {
        Pending p = pending.remove(pkg);
        if (p != null && p.file != null) p.file.delete();
    }

    /** Remembers where pkg came from, so it is kept up to date from there; a switch set to off stays off. */
    private void track(String pkg, Apps.Source source, String tag) {
        Apps.Tracked old = tracked.get(pkg);
        tracked.put(pkg, new Apps.Tracked(pkg, source, old == null || old.auto, tag));
        drop(pkg);
        save();
    }

    private static boolean tvIdle() {
        return AutoKill.isAsleep(Sh.run(AutoKillCmds.wakefulness())) && Sh.run(UpdaterCmds.playingCount()).trim().equals("0");
    }

    // ---- download, check, install ----

    /**
     * Downloads url into out and checks the file (ApkFile.check). automatic: gives way as soon as a user's action is
     * waiting. link: stops when the user asked for another link or cancelled (gen = linkGen when it was started).
     */
    private Got download(final String key, String url, File out, String sha, String pkg, final boolean automatic, final int gen) {
        final Got g = new Got();
        File dir = new File(Apps.DIR);
        dir.mkdirs();
        dir.setReadable(true, false);
        dir.setExecutable(true, false);   // the system reads the file from here when it installs it
        final String[] refused = { null };
        final long[] last = { 0 };
        // a file with a known SHA-256 (a catalogue file) may come from an http mirror: it is checked before install
        Http.Result r = Http.toFile(url, out, Apps.MAX_APK, false, sha != null,
            total -> {
                long free = new File(Apps.DIR).getUsableSpace(), need = Apps.spaceNeeded(total);
                if (free >= need) return true;
                refused[0] = Apps.spaceMessage(need, free);
                return false;
            },
            (done, total) -> {
                if (automatic && userWaiting.get() > 0) return false;
                if (gen >= 0 && gen != linkGen) return false;
                long t = now();
                if (last[0] == 0 || t - last[0] >= Apps.PROGRESS_MS) {
                    last[0] = t;
                    status(key, Apps.DOWNLOADING, Apps.pct(done, total), null, -1, total, null);
                }
                return true;
            });
        if (!r.ok()) {
            out.delete();
            if (Http.STOPPED.equals(r.why)) g.stopped = true;
            else g.error = Http.REFUSED.equals(r.why) && refused[0] != null ? refused[0] : "The download failed: " + r.why + ".";
            return g;
        }
        g.tag = r.tag;
        ApkFile.Check c = ApkFile.check(out, sha, pkg);
        g.info = c.info;
        g.error = c.error;
        g.otherApp = c.otherApp;
        if (g.error != null) out.delete();
        else out.setReadable(true, false);
        return g;
    }

    /** pm install; the file is gone afterwards either way. quiet: an automatic update's refusal is not the user's news. */
    private boolean installFile(String key, File f, String pkg, long vc, boolean quiet) {
        status(key, Apps.INSTALLING, -1, pkg, vc, -1, null);
        String out = Sh.run(AppsCmds.install(f.getPath()), INSTALL_TIMEOUT_MS);
        f.delete();
        if (out.contains("Success")) return true;
        if (quiet) Sh.log("apps: automatic update of " + pkg + " not installed: " + Apps.installError(out));
        else error(key, Apps.installError(out));
        return false;
    }

    /** Where a typed source's file is right now (asks the network). */
    private Where resolve(Apps.Source s) {
        Where w = new Where();
        if (Apps.URL.equals(s.kind)) {
            w.url = s.value;
        } else if (Apps.CODE.equals(s.kind)) {
            String html = Http.text(Apps.codeUrl(s.value), false);
            if (html == null) { w.error = "Couldn't look up code " + s.value + ". Is the Fire TV online?"; return w; }
            w.url = Apps.codeTarget(html);
            if (w.url == null) { w.error = "No app behind code " + s.value + "."; return w; }
            // a code that points at a GitHub project page means that project's latest release
            Apps.Source inner = Apps.classify(w.url);
            if (inner != null && Apps.REPO.equals(inner.kind)) return resolve(inner);
        } else if (Apps.REPO.equals(s.kind)) {
            String json = Http.text(Apps.repoApi(s.value), false);
            if (json == null) { w.error = "Couldn't find a release of " + s.value + " on GitHub. Check the name, or try again later."; return w; }
            Apps.Asset a = Apps.pickAsset(Apps.assets(json), abis);
            if (a == null) { w.error = "No app file for this Fire TV in the latest release of " + s.value + "."; return w; }
            w.url = a.url;
            w.tag = Apps.repoTag(a);
        } else {
            w.error = "Stickman does not know where this app comes from.";
        }
        return w;
    }

    // ---- the user's actions ----

    /** app_install "ID PKG": a catalogue app, installed or brought up to date now. */
    private void installFromCatalog(String arg) {
        facts();
        String[] a = Apps.installArg(arg);
        refreshCatalog(false);
        Apps.Entry e = a == null ? null : Apps.byId(catalog, a[0]);
        String key = e != null ? e.pkg : a != null && a[1] != null ? a[1] : Apps.LINK_KEY;
        if (e == null) { error(key, "Not in the catalogue. Is the Fire TV online?"); return; }
        Apps.FileRef f = Apps.pick(e, abis);
        if (f == null) { error(key, "No build of this app for this Fire TV's processor."); return; }
        if (e.minSdk > sdk) { error(key, "This app needs a newer Fire OS."); return; }
        Apps.Source source = new Apps.Source(Apps.CATALOG, e.id);
        long cur = installedVc(e.pkg);
        if (cur >= f.versionCode) {
            track(e.pkg, source, "");   // already here: from now on kept up to date from the catalogue
            status(key, Apps.UPTODATE, -1, e.pkg, cur, -1, null);
            report();
            return;
        }
        File out = apkFile(e.pkg);
        Pending p = pending.get(e.pkg);
        long vc = f.versionCode;
        if (p != null && p.file != null && p.file.isFile() && p.vc == f.versionCode && f.sha256.equals(p.sha)) {
            pending.remove(e.pkg);      // fetched and checked on an earlier idle tick: use it (installFile deletes it)
        } else {
            drop(e.pkg);
            Got g = download(key, f.url, out, f.sha256, e.pkg, false, -1);
            if (g.error != null) { error(key, g.error); return; }
            vc = g.info.versionCode;
        }
        if (!installFile(key, out, e.pkg, vc, false)) return;
        track(e.pkg, source, "");
        status(key, Apps.INSTALLED, -1, e.pkg, vc, -1, null);
        report();
    }

    /**
     * app_replace "ID PKG": the copy on the Fire TV is another build of a catalogue app (from the Appstore, or signed
     * by someone else), which Android will not let the catalogue's file update. The new file is fetched and checked
     * first, so a download that fails costs nothing; only then is the old copy uninstalled and the new one installed.
     */
    private void replace(String arg) {
        facts();
        String[] a = Apps.installArg(arg);
        refreshCatalog(false);
        Apps.Entry e = a == null ? null : Apps.byId(catalog, a[0]);
        String key = e != null ? e.pkg : a != null && a[1] != null ? a[1] : Apps.LINK_KEY;
        if (e == null) { error(key, "Not in the catalogue. Is the Fire TV online?"); return; }
        if (Apps.neverRemove(e.pkg)) { error(key, "Stickman never removes this app."); return; }
        Apps.FileRef f = Apps.pick(e, abis);
        if (f == null) { error(key, "No build of this app for this Fire TV's processor."); return; }
        if (e.minSdk > sdk) { error(key, "This app needs a newer Fire OS."); return; }
        File out = apkFile(e.pkg);
        drop(e.pkg);
        Got g = download(key, f.url, out, f.sha256, e.pkg, false, -1);
        if (g.error != null) { error(key, g.error); return; }
        if (installedVc(e.pkg) >= 0) {
            status(key, Apps.INSTALLING, -1, e.pkg, g.info.versionCode, -1, null);
            String gone = Sh.run(AppsCmds.uninstall(e.pkg), UNINSTALL_TIMEOUT_MS);
            if (!gone.contains("Success")) { out.delete(); error(key, Apps.removeError(gone)); return; }
        }
        if (!installFile(key, out, e.pkg, g.info.versionCode, false)) return;
        track(e.pkg, new Apps.Source(Apps.CATALOG, e.id), "");
        status(key, Apps.INSTALLED, -1, e.pkg, g.info.versionCode, -1, null);
        report();
    }

    /** app_get: a typed link, GitHub project or Downloader code is downloaded and held for the user's yes. */
    private void get(String typed, int gen) {
        if (gen != linkGen) return;   // the user already asked for another one, or cancelled
        facts();
        cancel();
        Apps.Source s = Apps.classify(typed);
        if (s == null) { error(Apps.LINK_KEY, Apps.whyNot(typed)); return; }
        status(Apps.LINK_KEY, Apps.LOOKUP, -1, null, -1, -1, null);
        Where w = resolve(s);
        if (w.error != null) { error(Apps.LINK_KEY, w.error); return; }
        File out = new File(Apps.DIR, "link.apk");
        Got g = download(Apps.LINK_KEY, w.url, out, null, null, false, gen);
        if (g.stopped) return;   // the user asked for something else meanwhile
        if (g.error != null) { error(Apps.LINK_KEY, g.error); return; }
        if (Apps.isStickman(g.info.pkg)) { out.delete(); error(Apps.LINK_KEY, Apps.OWN_UPDATER); return; }
        staged = new Staged(out, g.info, s, Apps.tagOf(w.tag, g.tag), now());
        long cur = installedVc(g.info.pkg);
        status(Apps.LINK_KEY, Apps.CONFIRM, -1, g.info.pkg, g.info.versionCode, out.length(), Apps.confirmMsg(cur, g.info.versionName));
    }

    /** app_confirm: the user has seen what the download is and wants it. */
    private void confirm() {
        Staged s = staged;
        staged = null;
        if (s == null || !s.file.isFile()) { error(Apps.LINK_KEY, "That download is gone. Get it again."); return; }
        if (!installFile(Apps.LINK_KEY, s.file, s.info.pkg, s.info.versionCode, false)) return;
        track(s.info.pkg, s.source, s.tag);
        status(Apps.LINK_KEY, Apps.INSTALLED, -1, s.info.pkg, s.info.versionCode, -1, null);
        status(s.info.pkg, Apps.INSTALLED, -1, s.info.pkg, s.info.versionCode, -1, null);
        report();
    }

    /** app_cancel (or a download nobody answered within 15 minutes). */
    private void cancel() {
        Staged s = staged;
        staged = null;
        if (s != null) s.file.delete();
    }

    /** app_update PKG: a tracked app, from wherever it came. */
    private void update(String pkg) {
        if (!Apps.validPkg(pkg)) return;
        facts();
        Apps.Tracked t = tracked.get(pkg);
        if (t != null && !Apps.CATALOG.equals(t.source.kind)) {
            linkUpdate(t, pending.get(pkg), false);
            report();
            return;
        }
        refreshCatalog(false);
        Apps.Entry e = t != null ? Apps.byId(catalog, t.source.value) : Apps.byPackage(catalog, pkg);
        if (e == null || !e.pkg.equals(pkg)) { error(pkg, "Stickman does not know where this app comes from."); return; }
        installFromCatalog(e.id + " " + pkg);
    }

    /** app_remove PKG. */
    private void remove(String pkg) {
        if (Apps.neverRemove(pkg)) {
            if (Apps.validPkg(pkg)) error(pkg, "Stickman never removes this app.");
            return;
        }
        String out = Sh.run(AppsCmds.uninstall(pkg), UNINSTALL_TIMEOUT_MS);
        if (!out.contains("Success")) { error(pkg, Apps.removeError(out)); return; }
        if (tracked.remove(pkg) != null) save();
        drop(pkg);
        status(pkg, Apps.REMOVED, -1, pkg, -1, -1, null);
        report();
    }

    /** app_auto "PKG 0|1". */
    private void setAuto(String arg) {
        String[] a = Apps.autoArg(arg);
        Apps.Tracked t = a == null ? null : tracked.get(a[0]);
        if (t == null) return;
        t.auto = a[1].equals("1");
        save();
        report();
    }

    /** apps_refresh: the screen was opened. */
    private void refresh() {
        report();
        if (!checked || now() - lastCheck > Apps.RECHECK_MS) check();
    }

    // ---- keeping tracked apps up to date ----

    private void tick() {
        Staged s = staged;
        if (s != null && Apps.stagedExpired(now() - s.at)) cancel();
        if (!checked || now() - lastCheck >= Apps.CHECK_EVERY_MS) check();
        installWaiting();
    }

    private static boolean same(String a, String b) { return a == null ? b == null : a.equals(b); }

    /** A newer file is known for pkg; one that was already known (and perhaps already fetched) stays as it is. */
    private void offer(String pkg, String url, String sha, long vc, String tag) {
        Pending p = pending.get(pkg);
        if (p != null && same(p.url, url) && same(p.sha, sha) && same(p.tag, tag)) return;
        drop(pkg);
        p = new Pending(pkg);
        p.url = url;
        p.sha = sha;
        p.vc = vc;
        p.tag = tag;
        pending.put(pkg, p);
    }

    /** Looks for newer files of every tracked app. Installs nothing: see installWaiting. */
    private void check() {
        facts();
        lastCheck = now();
        checked = true;
        refreshCatalog(true);
        Set<String> present = Apps.packages(Sh.run(AppsCmds.listPackages(), LIST_TIMEOUT_MS));
        boolean changed = false;
        for (Iterator<Apps.Tracked> it = tracked.values().iterator(); it.hasNext(); ) {
            if (userWaiting.get() > 0) { checked = false; break; }   // the user first; the rest at the next tick
            Apps.Tracked t = it.next();
            // an empty list is a call that failed, not a stick without apps
            if (!present.isEmpty() && !present.contains(t.pkg)) {
                it.remove();
                drop(t.pkg);
                changed = true;
                continue;
            }
            long cur = installedVc(t.pkg);
            if (cur < 0) continue;
            if (Apps.CATALOG.equals(t.source.kind)) {
                if (catalog.isEmpty()) continue;   // offline: what was known stays
                Apps.Entry e = Apps.byId(catalog, t.source.value);
                Apps.FileRef f = e != null && e.pkg.equals(t.pkg) && e.minSdk <= sdk ? Apps.pick(e, abis) : null;
                if (f != null && f.versionCode > cur) offer(t.pkg, f.url, f.sha256, f.versionCode, null);
                else drop(t.pkg);
            } else {
                Where w = resolve(t.source);
                if (w.error != null) continue;     // offline, or gone for now: next time
                String tag = w.tag.isEmpty() ? Http.signature(w.url, false) : w.tag;
                if (Apps.newTag(t.tag, tag)) offer(t.pkg, null, null, -1, tag);
                else if (!tag.isEmpty()) drop(t.pkg);
            }
        }
        if (changed) save();
        report();
    }

    /** Installs what check() found, for apps whose automatic updates are on, while nobody is watching. */
    private void installWaiting() {
        long now = now();
        boolean any = false;
        for (Pending p : pending.values()) {
            Apps.Tracked t = tracked.get(p.pkg);
            if (t != null && t.auto && now >= p.notBefore) any = true;
        }
        if (!any || userWaiting.get() > 0 || !tvIdle()) return;
        for (Pending p : new ArrayList<>(pending.values())) {
            if (userWaiting.get() > 0) break;
            Apps.Tracked t = tracked.get(p.pkg);
            if (t == null) { drop(p.pkg); continue; }
            if (!t.auto || now() < p.notBefore) continue;
            if (Apps.CATALOG.equals(t.source.kind)) catalogUpdate(t, p);
            else linkUpdate(t, p, true);
        }
        report();
    }

    private static void failed(Pending p) {
        if (p == null) return;
        p.failures++;
        p.notBefore = now() + Apps.retryAfterMs(p.failures);
    }

    /** An automatic update of a catalogue app. */
    private void catalogUpdate(Apps.Tracked t, Pending p) {
        File out = apkFile(t.pkg);
        if (p.file == null || !p.file.isFile()) {
            p.file = null;
            Got g = download(t.pkg, p.url, out, p.sha, t.pkg, true, -1);
            if (g.stopped) return;
            if (g.error != null) {
                Sh.log("apps: automatic update of " + t.pkg + " not fetched: " + g.error);
                failed(p);
                return;
            }
            if (g.info.versionCode <= installedVc(t.pkg)) { out.delete(); drop(t.pkg); return; }
            p.file = out;
            p.vc = g.info.versionCode;
        }
        if (!tvIdle()) return;   // someone woke the TV meanwhile: the file waits for the next idle moment
        long vc = p.vc;
        if (!installFile(t.pkg, out, t.pkg, vc, true)) { p.file = null; failed(p); return; }
        drop(t.pkg);
        status(t.pkg, Apps.INSTALLED, -1, t.pkg, vc, -1, null);
    }

    /**
     * Fetches the current file of an app that came from a link, a GitHub project or a Downloader code, and installs
     * it when it is newer. Only the download tells: such a source has no version to compare beforehand. automatic:
     * quiet, and only while the TV is idle; otherwise the user asked (app_update) and is told how it went.
     */
    private void linkUpdate(Apps.Tracked t, Pending p, boolean automatic) {
        String key = t.pkg;
        File out = apkFile(t.pkg);
        long vc;
        String tag;
        if (p != null && p.file != null && p.file.isFile()) {
            vc = p.vc;        // fetched and checked on an earlier idle tick
            tag = p.tag;
        } else {
            if (p != null) p.file = null;
            if (!automatic) status(key, Apps.LOOKUP, -1, null, -1, -1, null);
            Where w = resolve(t.source);
            if (w.error != null) {
                if (automatic) failed(p); else error(key, w.error);
                return;
            }
            Got g = download(key, w.url, out, null, t.pkg, automatic, -1);
            if (g.stopped) return;
            tag = Apps.tagOf(w.tag, g.tag);
            if (g.error != null) {
                if (g.otherApp) {            // the link leads to another app now: say so once, never fetch that file again
                    t.tag = tag;
                    save();
                    drop(t.pkg);
                    error(key, g.error);
                } else if (automatic) {
                    Sh.log("apps: automatic update of " + t.pkg + " not fetched: " + g.error);
                    failed(p);
                } else {
                    error(key, g.error);
                }
                return;
            }
            vc = g.info.versionCode;
            long cur = installedVc(t.pkg);
            if (vc <= cur) {                 // a new file, but not a newer app: remember it and leave it
                out.delete();
                t.tag = tag;
                save();
                drop(t.pkg);
                if (!automatic) status(key, Apps.UPTODATE, -1, t.pkg, cur, -1, null);
                return;
            }
            if (p != null) { p.file = out; p.vc = vc; p.tag = tag; }
        }
        if (automatic && !tvIdle()) return;   // someone woke the TV meanwhile: the file waits for the next idle moment
        if (!installFile(key, out, t.pkg, vc, automatic)) {
            if (p != null) { p.file = null; failed(p); }
            return;
        }
        t.tag = tag == null ? "" : tag;
        save();
        drop(t.pkg);
        status(key, Apps.INSTALLED, -1, t.pkg, vc, -1, null);
    }
}
