package com.clean.stickman;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.clean.stickman.core.Actions;
import com.clean.stickman.core.Apps;
import com.clean.stickman.core.cmd.RemoteCmds;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Apps: a small app store. "Add an app" takes a Downloader code, a link or a GitHub project; "On this Fire TV" lists
 * what was put on the stick outside the Appstore (open, update, uninstall); below that the hand-picked catalogue,
 * one row of banner tiles per category. OK on a tile opens what can be done with it.
 *
 * The page draws; the daemon (daemon/AppInstaller) does. Every action is one spool line; what happens next arrives as
 * "STATUS app:PACKAGE ..." log lines (LogTail), and the installed list (AppStore, from PackageManager) is read again
 * when a job ends. All rules and wording that can be tested without a screen live in core/Apps.
 */
public class AppsPage extends ApplyPage implements AppStore.Listener {
    static final int COLS = 4;
    /** The daemon is asked what it tracks at most this often; the installed list is re-read this often while a job runs. */
    static final long REFRESH_GAP_MS = 60_000, SCAN_GAP_MS = 4000, LINES_GAP_MS = 2000;
    /** A job that has not ended after this long is no longer waited for. */
    static final long ACT_WAIT_MS = 20 * 60_000L;
    static final String ACT = "act";

    AppStore store;
    LinearLayout sections;
    TextView note;
    Button retry;
    final List<Tile> tiles = new ArrayList<>();
    /** The sections and tiles laid out now; a different shape means the grid is built again. */
    String shape = "";
    List<String> abis;
    /** package -> {auto, kind, update waiting}: what the daemon keeps up to date (its "STATUS apps" line). */
    Map<String, String[]> tracked = new HashMap<>();
    long trackedSeen = -1, refreshSentAt, lastScanAt, lastLinesAt;
    /** package -> when this page last asked for something about it: only newer progress lines are its news. */
    final Map<String, Long> acted = new HashMap<>();
    /** When the typed link now on its way was sent (0 = none), and the last progress line of it that was handled. */
    long linkSince, linkSeen = -1;
    ThemedDialog question;

    /** One app on the page. An installed catalogue app has two: under "On this Fire TV" and in its category. */
    static final class Tile {
        String key, pkg, name;
        /** Null for an installed app that is not in the catalogue. */
        Apps.Entry entry;
        LinearLayout view;
        ImageView image;
        TextView initials, line;
    }

    /** A 16:9 box, the shape of TV launcher banners, whatever width the grid gives it. */
    static final class Banner extends FrameLayout {
        Banner(Context c) { super(c); }

        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    @Override public String title() { return "Apps"; }

    @Override public String subtitle() { return "Install apps and keep them up to date, without the usual prompts."; }

    @Override public View build(MainActivity act) {
        LinearLayout l = start(act);
        l.setClipChildren(false);
        LinearLayout add = card(l, "Add an app", "By Downloader code, by link, or from a GitHub project. Stickman downloads it, "
            + "tells you what it is, and installs it for you.");
        button(add, "Type a code, a link or a project…", v -> addDialog());
        note = Theme.autoHide(Theme.text(a, "Loading the catalogue…", 14, Theme.MUTED));
        note.setPadding(Theme.dp(a, 6), 0, 0, Theme.dp(a, 4));
        l.addView(note);
        retry = Theme.button(a, "Try again");
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> { note.setText("Loading the catalogue…"); retry.setVisibility(View.GONE); store.refresh(true); });
        l.addView(retry);
        sections = Theme.column(a);
        sections.setClipChildren(false);
        l.addView(sections, new LinearLayout.LayoutParams(-1, -2));
        store = new AppStore(a, this);
        return Theme.scroll(a, l);
    }

    @Override String describe(String kind, String out) { return null; }   // what happened arrives through the log

    // ---- data in ----

    @Override public void onShow() {
        if (a == null || store == null) return;
        store.refresh(false);
        long now = System.currentTimeMillis();
        // one quiet question to the daemon; never one that would make the user choose between VPNs just for looking
        if (Store.enabled(a) && !Bridge.otherVpnActive(a) && now - refreshSentAt > REFRESH_GAP_MS) {
            refreshSentAt = now;
            a.run(null, RemoteCmds.act(Actions.APPS_REFRESH, null));
        }
        onTick();
    }

    @Override public void changed() {
        if (a == null) return;
        render();
    }

    @Override public void art(String key) {
        for (Tile t : tiles) if (key.equals(artKey(t))) paint(t);
    }

    @Override public void onTick() {
        if (a == null || store == null) return;
        long now = System.currentTimeMillis();
        long ta = LogTail.statusAt("apps");
        if (ta != trackedSeen) {
            trackedSeen = ta;
            tracked = Apps.parseTrackedStatus(LogTail.status("apps"));
            render();
        }
        link();
        boolean waiting = false;
        for (Iterator<Map.Entry<String, Long>> it = acted.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long> e = it.next();
            String kind = "app:" + e.getKey();
            String news = LogTail.statusAt(kind) >= e.getValue() ? Apps.announce(Apps.parseStatus(LogTail.status(kind)), nameOf(e.getKey())) : null;
            if (news != null) {
                it.remove();
                say(news);
                store.rescan();
            } else if (now - e.getValue() > ACT_WAIT_MS) {
                it.remove();
            } else {
                waiting = true;
            }
        }
        // without log access (first run) the installed list still shows when a job is done
        if (waiting && now - lastScanAt > SCAN_GAP_MS) { lastScanAt = now; store.rescan(); }
        if (waiting || now - lastLinesAt > LINES_GAP_MS) lines();
    }

    /** The typed link on its way: progress in the result line, the question when it has arrived, the outcome. */
    void link() {
        if (linkSince == 0) return;
        String kind = "app:" + Apps.LINK_KEY;
        long at = LogTail.statusAt(kind);
        if (at == linkSeen || at < linkSince) return;
        linkSeen = at;
        Map<String, String> st = Apps.parseStatus(LogTail.status(kind));
        String progress = Apps.linkProgress(st);
        if (progress != null) { result.setText(progress); return; }
        if (Apps.CONFIRM.equals(st.get("state"))) { ask(st); return; }
        String news = Apps.announce(st, nameOf(st.get("pkg")));
        if (news == null) return;
        linkSince = 0;
        say(news);
        store.rescan();
    }

    // ---- drawing ----

    String nameOf(String pkg) {
        if (pkg == null) return null;
        Apps.Entry e = Apps.byPackage(store.catalog, pkg);
        if (e != null) return e.name;
        AppStore.Installed i = store.installed.get(pkg);
        return i != null && i.label != null ? i.label : pkg;
    }

    boolean updateWaiting(String pkg) {
        String[] t = tracked.get(pkg);
        return t != null && "1".equals(t[2]);
    }

    /** The copy on the Fire TV is another build than the catalogue's (the Appstore's, say): it cannot be updated. */
    boolean otherBuild(Tile t, AppStore.Installed i) {
        return t.entry != null && i != null && Boolean.FALSE.equals(Apps.sameBuild(t.entry.signer, i.signers));
    }

    /** The catalogue has a newer file than the installed copy. */
    boolean newer(Tile t, AppStore.Installed i) {
        Apps.FileRef f = t.entry == null || i == null ? null : Apps.pick(t.entry, abis);
        return f != null && f.versionCode > i.vc;
    }

    /** Whether a newer file is known that can be installed over the copy here: the catalogue's, or one the daemon found behind its link. */
    boolean hasUpdate(Tile t, AppStore.Installed i) {
        if (i == null || otherBuild(t, i)) return false;
        return newer(t, i) || updateWaiting(t.pkg);
    }

    /** The installed apps this page is about: catalogue apps, tracked ones and sideloaded ones; updates first. */
    List<Tile> here() {
        List<Tile> out = new ArrayList<>();
        for (AppStore.Installed i : store.installed.values()) {
            Apps.Entry e = Apps.byPackage(store.catalog, i.pkg);
            if (e == null && !tracked.containsKey(i.pkg) && !Apps.sideloaded(i.system, i.installer, i.pkg)) continue;
            Tile t = new Tile();
            t.pkg = i.pkg;
            t.entry = e;
            t.name = e != null ? e.name : i.label;
            t.key = "here:" + i.pkg + (e != null ? "@" + e.id : "");   // a catalogue app gets the catalogue's name and art
            out.add(t);
        }
        final Map<String, AppStore.Installed> inst = store.installed;
        Collections.sort(out, (x, y) -> {
            boolean ux = hasUpdate(x, inst.get(x.pkg)), uy = hasUpdate(y, inst.get(y.pkg));
            if (ux != uy) return ux ? -1 : 1;
            return x.name.toLowerCase(Locale.ROOT).compareTo(y.name.toLowerCase(Locale.ROOT));
        });
        return out;
    }

    List<Tile> category(String cat) {
        List<Tile> out = new ArrayList<>();
        for (Apps.Entry e : store.catalog) {
            if (!cat.equals(e.category)) continue;
            Tile t = new Tile();
            t.pkg = e.pkg;
            t.entry = e;
            t.name = e.name;
            t.key = "cat:" + e.id;
            out.add(t);
        }
        return out;
    }

    /** Lays the page out for what is known now: builds the grid again only when its shape changed. */
    void render() {
        if (abis == null) abis = AppStore.abis();
        List<String> titles = new ArrayList<>();
        List<List<Tile>> groups = new ArrayList<>();
        List<Tile> here = here();
        if (!here.isEmpty()) { titles.add("On this Fire TV"); groups.add(here); }
        for (String cat : Apps.categories(store.catalog)) { titles.add(cat); groups.add(category(cat)); }
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < groups.size(); i++) {
            s.append('|').append(titles.get(i)).append(':');
            for (Tile t : groups.get(i)) s.append(t.key).append(',');
        }
        if (!store.loaded) {
            note.setText("Loading the catalogue…");
        } else if (store.catalog.isEmpty()) {
            note.setText("Couldn't load the catalogue. Is the Fire TV online? You can still add an app by code or link.");
        } else if (store.saved) {
            note.setText("The Fire TV seems to be offline: this is the catalogue as it was last time.");
        } else if (!Store.enabled(a)) {
            note.setText("Stickman is OFF: turn it on in Settings to install or update apps.");
        } else {
            note.setText("");
        }
        retry.setVisibility(store.loaded && (store.catalog.isEmpty() || store.saved) ? View.VISIBLE : View.GONE);
        if (!s.toString().equals(shape)) {
            shape = s.toString();
            rebuild(titles, groups);
        } else {
            // the same tiles, but the catalogue may have been read again: each tile looks at its current entry
            for (Tile t : tiles) {
                Apps.Entry e = t.entry == null ? null : Apps.byId(store.catalog, t.entry.id);
                if (e != null) t.entry = e;
            }
        }
        lines();
    }

    void rebuild(List<String> titles, List<List<Tile>> groups) {
        boolean had = a.focusInPage();
        View f = a.getCurrentFocus();
        String keep = null;
        for (Tile t : tiles) if (t.view == f) keep = t.key;
        sections.removeAllViews();
        tiles.clear();
        for (int i = 0; i < groups.size(); i++) {
            boolean own = i == 0 && titles.get(i).equals("On this Fire TV");
            section(titles.get(i), own ? "Apps from outside the Appstore. Stickman can update the ones it installed." : null, groups.get(i));
        }
        Tile again = null;
        for (Tile t : tiles) if (t.key.equals(keep)) again = t;
        if (again != null) {
            final View v = again.view;
            v.post(v::requestFocus);
        } else {
            a.keepFocusInPage(had);
        }
    }

    void section(String title, String hint, List<Tile> ts) {
        int pad = Theme.dp(a, 6);
        TextView h = Theme.text(a, title, 17, Theme.TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(pad, Theme.dp(a, sections.getChildCount() == 0 ? 6 : 18), 0, Theme.dp(a, hint == null ? 4 : 0));
        sections.addView(h);
        if (hint != null) {
            TextView s = Theme.text(a, hint, 13, Theme.MUTED);
            s.setPadding(pad, 0, 0, Theme.dp(a, 4));
            sections.addView(s);
        }
        LinearLayout row = null;
        int gap = Theme.dp(a, 5);
        for (int i = 0; i < ts.size(); i++) {
            if (i % COLS == 0) {
                row = Theme.row(a);
                row.setGravity(Gravity.TOP);
                row.setClipChildren(false);
                row.setPadding(Theme.dp(a, 1), 0, Theme.dp(a, 1), 0);
                sections.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            Tile t = ts.get(i);
            tile(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(gap, gap, gap, gap);
            row.addView(t.view, lp);
            tiles.add(t);
        }
        // a short last row keeps its tiles the width of the others
        while (row != null && row.getChildCount() < COLS) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
            lp.setMargins(gap, gap, gap, gap);
            row.addView(new View(a), lp);
        }
    }

    StateListDrawable ring() {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[] { android.R.attr.state_focused }, Theme.rect(a, 0xFF2C241C, Theme.ACCENT, 3));
        sl.addState(new int[] { android.R.attr.state_pressed }, Theme.rect(a, 0xFF2C241C, Theme.ACCENT, 3));
        sl.addState(new int[] {}, Theme.rect(a, Theme.PANEL, 0xFF3A3028, 1));
        return sl;
    }

    /** Builds a tile's views: banner art (or the name on a panel until art is there), the name, one status line. */
    void tile(final Tile t) {
        LinearLayout v = Theme.column(a);
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(ring());
        int p = Theme.dp(a, 5);
        v.setPadding(p, p, p, Theme.dp(a, 7));
        Banner art = new Banner(a);
        art.setBackground(Theme.rect(a, 0xFF2A231C, 0, 0));
        art.setClipToOutline(true);
        t.image = new ImageView(a);
        art.addView(t.image, new FrameLayout.LayoutParams(-1, -1));
        t.initials = Theme.text(a, t.name, 15, Theme.WOOD);
        t.initials.setTypeface(Typeface.DEFAULT_BOLD);
        t.initials.setGravity(Gravity.CENTER);
        t.initials.setMaxLines(2);
        t.initials.setEllipsize(TextUtils.TruncateAt.END);
        t.initials.setPadding(Theme.dp(a, 8), 0, Theme.dp(a, 8), 0);
        art.addView(t.initials, new FrameLayout.LayoutParams(-1, -1));
        v.addView(art, new LinearLayout.LayoutParams(-1, -2));
        TextView name = Theme.text(a, t.name, 14, Theme.TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(Theme.dp(a, 3), Theme.dp(a, 6), Theme.dp(a, 3), 0);
        v.addView(name);
        t.line = Theme.text(a, "", 12, Theme.MUTED);
        t.line.setSingleLine(true);
        t.line.setEllipsize(TextUtils.TruncateAt.END);
        t.line.setPadding(Theme.dp(a, 3), Theme.dp(a, 1), Theme.dp(a, 3), 0);
        v.addView(t.line);
        v.setOnClickListener(x -> open(t));
        // the focused tile comes forward a little, as on the Fire TV home screen
        v.setOnFocusChangeListener((x, on) -> {
            x.animate().scaleX(on ? 1.04f : 1f).scaleY(on ? 1.04f : 1f).setDuration(120).start();
            x.setElevation(on ? Theme.dp(a, 6) : 0);
        });
        t.view = v;
        paint(t);
    }

    static String artKey(Tile t) { return t.entry != null ? t.entry.id : "pkg:" + t.pkg; }

    /** Puts the tile's art in, when it has arrived; asks for an installed app's own icon otherwise. */
    void paint(Tile t) {
        Bitmap b = t.entry == null ? null : store.art.get(t.entry.id);
        if (b != null) {
            boolean banner = b.getWidth() > b.getHeight() * 1.3f;
            t.image.setScaleType(banner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            int pad = banner ? 0 : Theme.dp(a, 8);
            t.image.setPadding(pad, pad, pad, pad);
            t.image.setImageBitmap(b);
            t.initials.setVisibility(View.GONE);
            return;
        }
        if (t.entry != null) return;   // the catalogue's art is on its way (AppStore asks for it with the catalogue)
        Drawable d = store.icons.get(artKey(t));
        if (d == null) { store.icon(t.pkg); return; }
        boolean banner = d.getIntrinsicWidth() > d.getIntrinsicHeight() * 1.3f;
        t.image.setScaleType(banner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        int pad = banner ? 0 : Theme.dp(a, 8);
        t.image.setPadding(pad, pad, pad, pad);
        t.image.setImageDrawable(d);
        t.initials.setVisibility(View.GONE);
    }

    /** Every tile's status line, from the installed list, the catalogue and the app's latest progress line. */
    void lines() {
        lastLinesAt = System.currentTimeMillis();
        if (abis == null) return;
        for (Tile t : tiles) {
            String text = lineOf(t);
            if (!text.contentEquals(t.line.getText())) t.line.setText(text);
            int tone = Apps.lineTone(text);
            t.line.setTextColor(tone == Apps.TONE_ACTIVE ? Theme.ACCENT : tone == Apps.TONE_GOOD ? Theme.WOOD : tone == Apps.TONE_OFF ? Theme.DIM : Theme.MUTED);
        }
    }

    String lineOf(Tile t) {
        AppStore.Installed i = store.installed.get(t.pkg);
        long vc = i == null ? -1 : i.vc;
        Apps.FileRef f = t.entry == null ? null : Apps.pick(t.entry, abis);
        if (t.entry != null) {
            String cannot = Apps.cannotInstall(vc, f != null, t.entry.minSdk, Build.VERSION.SDK_INT);
            if (cannot != null) return cannot;
        }
        long latestVc = -1;
        String latest = "";
        if (f != null) { latestVc = f.versionCode; latest = t.entry.version; }
        if (vc >= 0 && latestVc <= vc && updateWaiting(t.pkg)) { latestVc = vc + 1; latest = ""; }   // found behind a link: version unknown until fetched
        boolean other = otherBuild(t, i);
        String kind = "app:" + t.pkg;
        long at = LogTail.statusAt(kind);
        String line = Apps.tileLine(vc, i == null ? "" : i.version, other ? -1 : latestVc, latest, Apps.parseStatus(LogTail.status(kind)), at == 0 ? -1 : System.currentTimeMillis() - at);
        // another build: say where it is from instead of offering an update that cannot work (progress still shows)
        return other && line.startsWith("Installed") ? Apps.otherBuildLine(i.version, i.installer) : line;
    }

    // ---- what the user does ----

    /** One spool line for the daemon; pkg (may be null) is remembered so its outcome is announced. */
    void act(String id, String arg, String pkg, String pending) {
        if (!stickdOn()) return;
        if (pkg != null) acted.put(pkg, System.currentTimeMillis());
        send(RemoteCmds.act(id, arg), ACT, pending);
    }

    void addDialog() {
        final EditText in = Theme.edit(a);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        in.setImeOptions(EditorInfo.IME_ACTION_GO);
        in.setHint("54794   or   yuliskov/SmartTube");
        final TextView why = Theme.autoHide(Theme.text(a, "", 14, Theme.ACCENT));
        why.setPadding(0, Theme.dp(a, 6), 0, 0);
        LinearLayout box = Theme.column(a);
        box.addView(in, new LinearLayout.LayoutParams(-1, -2));
        box.addView(why);
        final ThemedDialog d = new ThemedDialog(a).title("Add an app")
            .message("Type one of these:\n•  a Downloader code\n•  a link to an app file\n•  a GitHub project, as owner/name\n\n"
                + "Stickman downloads it and asks before it installs anything.")
            .view(box);
        final Runnable go = () -> {
            String typed = in.getText().toString();
            Apps.Source s = Apps.classify(typed);
            if (s == null) { why.setText(Apps.whyNot(typed)); return; }
            if (!Store.enabled(a)) { why.setText("Stickman is OFF: turn it on in Settings first."); return; }
            d.dismiss();
            linkSince = System.currentTimeMillis();
            linkSeen = -1;
            act(Actions.APP_GET, s.value, null, "Looking it up…");
        };
        d.buttonKeep("Get it", v -> go.run());
        in.setOnEditorActionListener((v, action, ev) -> {
            // the on-screen keyboard's Go, or Enter on a paired keyboard / the phone app (no action, a key event)
            boolean enter = action == EditorInfo.IME_NULL && ev != null && ev.getAction() == android.view.KeyEvent.ACTION_DOWN
                && (ev.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER || ev.getKeyCode() == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER);
            if (action != EditorInfo.IME_ACTION_GO && action != EditorInfo.IME_ACTION_DONE && !enter) return false;
            go.run();
            return true;
        });
        d.cancel("Cancel").focus(in).show();
    }

    /** The typed link has arrived and been looked at: say what it is, and install only on a yes. */
    void ask(Map<String, String> st) {
        if (question != null) question.dismiss();
        String pkg = st.get("pkg");
        Apps.Entry e = Apps.byPackage(store.catalog, pkg);
        final boolean[] yes = { false };
        result.setText("");
        question = new ThemedDialog(a).title("Install this app?")
            .message(Apps.confirmText(e == null ? null : e.name, pkg, Apps.confirmVersion(st.get("msg")), num(st.get("vc")), num(st.get("size")), Apps.confirmInstalled(st.get("msg"))))
            .button("Install", () -> {
                yes[0] = true;
                linkSince = System.currentTimeMillis();
                act(Actions.APP_CONFIRM, null, null, "Installing…");
            })
            .cancel("Cancel")
            .onDismiss(() -> {
                question = null;
                if (yes[0]) return;
                linkSince = 0;   // Cancel or Back: the download is thrown away
                if (Store.enabled(a)) a.run(null, RemoteCmds.act(Actions.APP_CANCEL, null));
            })
            .show();
    }

    static long num(String s) {
        try { return s == null ? -1 : Long.parseLong(s); } catch (NumberFormatException e) { return -1; }
    }

    /** OK on a tile: what the app is, and what can be done with it right now. */
    void open(final Tile t) {
        final AppStore.Installed i = store.installed.get(t.pkg);
        final long vc = i == null ? -1 : i.vc;
        final Apps.FileRef f = t.entry == null ? null : Apps.pick(t.entry, abis);
        final String[] tr = tracked.get(t.pkg);
        boolean cannot = t.entry != null && (f == null || t.entry.minSdk > Build.VERSION.SDK_INT);
        StringBuilder m = new StringBuilder();
        if (t.entry != null) {
            if (!t.entry.summary.isEmpty()) m.append(t.entry.summary).append("\n\n");
            m.append("Version ").append(Apps.shortVersion(t.entry.version));
            if (f != null) m.append("  ·  ").append(Apps.mb(f.size));
            String host = Apps.host(t.entry.homepage);
            if (!host.isEmpty()) m.append("  ·  ").append(host);
            m.append('\n');
        }
        if (i != null) m.append("On this Fire TV: ").append(Apps.shortVersion(i.version).isEmpty() ? "installed" : "version " + Apps.shortVersion(i.version)).append('\n');
        if (t.entry == null) m.append(t.pkg).append('\n');
        if (cannot && i == null) m.append('\n').append(f == null ? "There is no build of this app for this Fire TV's processor." : "This app needs a newer Fire OS than this Fire TV has.").append('\n');
        final boolean other = otherBuild(t, i);
        final boolean removable = i != null && !i.system && !Apps.neverRemove(t.pkg);
        final boolean canReplace = other && newer(t, i) && removable && !cannot;
        if (other) m.append('\n').append(Apps.otherBuildText(i.installer, canReplace)).append('\n');
        else if (tr != null) m.append('\n').append("Kept up to date from ").append(Apps.kindText(tr[1])).append('.').append('\n');
        else if (i != null && t.entry == null) m.append('\n').append("Stickman did not install this one, so it cannot update it.").append('\n');
        final ThemedDialog d = new ThemedDialog(a).title(t.name).message(m.toString().trim());
        if (tr != null && !other) {
            final boolean[] on = { "1".equals(tr[0]) };
            final Button[] row = new Button[1];
            row[0] = d.row(autoLabel(on[0]), v -> {
                if (!stickdOn()) return;
                on[0] = !on[0];
                tr[0] = on[0] ? "1" : "0";
                row[0].setText(autoLabel(on[0]));
                a.run(null, RemoteCmds.act(Actions.APP_AUTO, t.pkg + " " + tr[0]));
            });
            if (!Apps.CATALOG.equals(tr[1]) && !updateWaiting(t.pkg))
                d.row("Look for a newer version now", v -> { d.dismiss(); act(Actions.APP_UPDATE, t.pkg, t.pkg, "Looking for a newer " + t.name + "…"); });
        }
        for (String b : Apps.buttons(vc, other ? newer(t, i) : hasUpdate(t, i), i != null && i.canOpen, removable, cannot, other)) {
            switch (b) {
                case Apps.B_REPLACE:
                    d.button(b, () -> ThemedDialog.confirm(a, "Replace " + t.name + "?", Apps.replaceText(t.name, t.entry.version),
                        "Replace", () -> act(Actions.APP_REPLACE, t.entry.id + " " + t.pkg, t.pkg, "Getting " + t.name + "…")));
                    break;
                case Apps.B_INSTALL:
                    d.button(b, () -> act(Actions.APP_INSTALL, t.entry.id + " " + t.pkg, t.pkg, "Getting " + t.name + "…"));
                    break;
                case Apps.B_UPDATE:
                    d.button(b, () -> {
                        if (t.entry != null) act(Actions.APP_INSTALL, t.entry.id + " " + t.pkg, t.pkg, "Updating " + t.name + "…");
                        else act(Actions.APP_UPDATE, t.pkg, t.pkg, "Updating " + t.name + "…");
                    });
                    break;
                case Apps.B_OPEN:
                    d.button(b, () -> launch(t.pkg));
                    break;
                default:
                    d.button(b, () -> ThemedDialog.confirm(a, "Uninstall " + t.name + "?", "The app and everything it saved are removed from this Fire TV.",
                        "Uninstall", () -> act(Actions.APP_REMOVE, t.pkg, t.pkg, "Uninstalling " + t.name + "…")));
            }
        }
        d.cancel("Close").focusButton(0).show();
    }

    static String autoLabel(boolean on) {
        return on ? "✓  Updates by itself while the TV is asleep" : "      Updates by itself while the TV is asleep  (off)";
    }

    void launch(String pkg) {
        Intent in = AppStore.launchIntent(a.getPackageManager(), pkg);
        if (in == null) { say("This app has nothing to open."); return; }
        try {
            a.startActivity(in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            say("Couldn't open it.");
        }
    }
}
