package com.clean.stickman;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;

import com.clean.stickman.core.Update;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * "What's new": the release notes of a version, fetched from its GitHub release (tag v&lt;version&gt;) and shown as
 * plain text in a dialog. Used by Settings (before updating) and once after an update (MainActivity).
 */
final class WhatsNew {
    private WhatsNew() {}

    /** Fetch the notes off the UI thread; show them, or onMissing (may be null) when there are none / no network. */
    static void show(final MainActivity a, final String version, final String title, final Runnable onMissing) {
        new Thread(() -> {
            String[] test = testSource();
            String notes = Update.plainNotes(Update.notes(get(test != null ? test[0] : Update.TAG_API + "v" + version)));
            new Handler(Looper.getMainLooper()).post(() -> {
                if (a.isFinishing()) return;
                if (notes.isEmpty()) { if (onMissing != null) onMissing.run(); return; }
                TextView t = Theme.text(a, notes, 16, Theme.TEXT);
                t.setLineSpacing(0, 1.15f);
                ScrollView s = Theme.scroll(a, t);
                s.setFocusable(true);   // the remote's D-pad scrolls long notes
                new ThemedDialog(a).wide().title(title).view(s).cancel("Close").show();
            });
        }, "whatsnew").start();
    }

    static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("User-Agent", "Stickman");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int k; (k = in.read(buf)) > 0; ) b.write(buf, 0, k);
                return new String(b.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * On app start (GitHub edition): after an update, show what's new in the version now running, once. Otherwise
     * ask GitHub for the latest release and, if it's newer and not declined, ask: Update now / Always update
     * automatically / Not now, with the release notes above the question.
     */
    static void onStart(final MainActivity a) {
        if (com.clean.stickman.core.Edition.STORE) return;
        final String installed = installedVersion(a);
        if (installed == null) return;
        android.content.SharedPreferences p = Store.prefs(a);
        String seen = p.getString("seenVersion", null);
        p.edit().putString("seenVersion", installed).apply();
        if (Update.justUpdated(installed, seen)) {
            show(a, installed, "Updated to " + installed + ": what's new", null);
            return;
        }
        final String declined = p.getString("declinedVersion", null);
        new Thread(() -> {
            String[] test = testSource();
            String json = get(test != null ? test[0] : Update.API);
            Update.Release r = Update.parse(json, test != null ? test[1] : Update.URL_PREFIX);
            if (r != null) lastKnownLatest = r.version;
            android.util.Log.i("Stickman", "update: startup check found " + (r == null ? "nothing" : r.version)
                + " (installed " + installed + ", test source " + (test != null) + ")");
            if (r == null || !Update.shouldOffer(r.version, installed, declined)) return;
            String notes = Update.plainNotes(Update.notes(json));
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!a.isFinishing() && claim(r.version)) offer(a, r.version, installed, notes);
            });
        }, "updatecheck").start();
    }

    /** The latest version the app's own startup check saw on GitHub (null = not checked / no answer). */
    static volatile String lastKnownLatest;

    /** The version already offered in this process: the startup check and stickd's report never ask twice. */
    private static String offered;

    static synchronized boolean claim(String version) {
        if (version.equals(offered)) return false;
        offered = version;
        return true;
    }

    /**
     * stickd's own 6-hourly check found a newer version (its STATUS update line): ask, with that version's notes
     * from GitHub. Skipped when automatic updates are on (it installs while the TV sleeps) or the user declined it.
     */
    static void offerFromStatus(final MainActivity a, java.util.Map<String, String> st) {
        if (com.clean.stickman.core.Edition.STORE) return;
        String state = st.get("state"), latest = st.get("latest");
        String installed = installedVersion(a);
        String declined = Store.prefs(a).getString("declinedVersion", null);
        boolean auto = com.clean.stickman.core.StickdConf.parse(Store.stickdText(a)).updateAuto;
        android.util.Log.i("Stickman", "update: stickd says " + state + " " + latest + " (installed " + installed
            + ", declined " + declined + ", auto " + auto + ")");
        if (!"available".equals(state) && !"ready".equals(state)) return;
        if (!Update.shouldOffer(latest, installed, declined)) return;
        if (auto) return;
        if (!claim(latest)) return;
        new Thread(() -> {
            String notes = Update.plainNotes(Update.notes(get(Update.TAG_API + "v" + latest)));
            new Handler(Looper.getMainLooper()).post(() -> { if (!a.isFinishing()) offer(a, latest, installed, notes); });
        }, "updatenotes").start();
    }

    static void offer(final MainActivity a, final String latest, String installed, String notes) {
        android.util.Log.i("Stickman", "update: asking about " + latest + " (" + notes.length() + " chars of notes)");
        android.widget.LinearLayout col = new android.widget.LinearLayout(a);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        if (!notes.isEmpty()) {
            col.addView(Theme.text(a, "What's new:", 15, Theme.MUTED));
            TextView t = Theme.text(a, notes, 15, Theme.TEXT);
            t.setLineSpacing(0, 1.12f);
            ScrollView s = Theme.scroll(a, t);
            s.setFocusable(true);
            col.addView(s, new android.widget.LinearLayout.LayoutParams(-1, Theme.dp(a, 260)));
        }
        col.addView(Theme.text(a, "\nYou have " + installed + ". Install " + latest + " now? Stickman closes for a moment and "
            + "restarts by itself; your settings" + " and buttons are kept.\n\n\u201cAlways\u201d also installs future versions "
            + "automatically while the TV is asleep.", 15, Theme.TEXT));
        new ThemedDialog(a).wide().title("Stickman " + latest + " is available").view(col)
            .button("Update now", () -> update(a, false))
            .button("Always update automatically", () -> update(a, true))
            .button("Not now", () -> Store.prefs(a).edit().putString("declinedVersion", latest).apply())
            .focusButton(0).show();
    }

    /** Hands the install to stickd (it runs as shell); with always, auto-update is switched on first. */
    static void update(MainActivity a, boolean always) {
        if (!Store.enabled(a)) {
            ThemedDialog.message(a, "Turn Stickman on first", "Stickman's background automation installs the update. "
                + "Turn Stickman on in Settings, then use Settings \u2192 Updates \u2192 Update now.");
            return;
        }
        if (always) {
            com.clean.stickman.core.StickdConf c = com.clean.stickman.core.StickdConf.parse(Store.stickdText(a));
            c.updateAuto = true;
            Store.saveStickdText(a, c.serialize());
        }
        android.widget.Toast.makeText(a, "Updating\u2026 Stickman closes and restarts by itself.", android.widget.Toast.LENGTH_LONG).show();
        String act = com.clean.stickman.core.cmd.RemoteCmds.act(com.clean.stickman.core.Actions.UPDATE_NOW, null);
        if (always) a.run(null, Ensure.MARKER, act); else a.run(null, act);
    }

    /** The developer test source (Update.TEST_FILE, world-readable when present), or null. */
    static String[] testSource() {
        try (java.io.FileInputStream in = new java.io.FileInputStream(Update.TEST_FILE)) {   // java.nio.file is API 26
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            return Update.testSource(new String(o.toByteArray(), StandardCharsets.UTF_8));
        }
        catch (Throwable t) { return null; }
    }

    /** This app's versionName ("1.8.1"), or null. */
    static String installedVersion(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return null; }
    }
}
