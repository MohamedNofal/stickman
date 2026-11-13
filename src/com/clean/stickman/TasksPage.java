package com.clean.stickman;

import com.clean.stickman.core.cmd.RemoteCmds;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.StateListDrawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.clean.stickman.core.Actions;
import com.clean.stickman.core.Catalog;
import com.clean.stickman.core.EndPolicy;
import com.clean.stickman.core.KillPolicy;
import com.clean.stickman.core.OwnProfile;
import com.clean.stickman.core.RamHist;
import com.clean.stickman.core.RemapModel;
import com.clean.stickman.core.ResultText;
import com.clean.stickman.core.StickdConf;
import com.clean.stickman.core.TaskKeepalive;
import com.clean.stickman.core.TaskSnapshot;
import com.clean.stickman.core.TileNav;
import com.clean.stickman.core.Treemap;
import com.clean.stickman.core.Units;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task manager: live header (RAM, CPU, auto-kill, LIVE / PAUSED), the memory map (TaskMapView), a muted strip of
 * system packages, ⏯ pause, ☰ sort / End all, and the tile dialog (End / Always kill / Protect / Open).
 * Live data comes from stickd's STATUS feed (LogTail.FEED) while the page is shown and not paused; the page keeps
 * stickd sampling with tasks_start on show and every 60 s, and stops it with tasks_stop on hide / pause.
 */
public class TasksPage extends ApplyPage {
    static final String TAG = "Stickman";
    static final long STALE_MS = 6000, RESULT_TIMEOUT_MS = 20000;
    /** Without a fresh sample for this long the header says NO DATA and the map says where to look. */
    static final long NO_DATA_MS = 15000;
    static final String NO_DATA_HINT = "Nothing arrives from the stick. Check the Status page: is background automation running?";
    static final int STRIP_MAX = 6, STRIP_KEEP = 8;
    static final String[] SORTS = { "Sort by RAM", "Sort by CPU", "Sort by name" };
    static final Pattern KILLED = Pattern.compile("killed=(\\d+)");

    TaskMapView map;
    TextView ram, cpu, autoKill, liveText;
    Dot dot;
    LinearLayout strip;
    final LinearLayout[] cards = new LinearLayout[STRIP_MAX + 1];
    final TextView[] cardName = new TextView[STRIP_MAX + 1], cardMb = new TextView[STRIP_MAX + 1];
    /** Package (or "+") shown on each strip card. */
    final String[] cardKey = new String[STRIP_MAX + 1];
    /** System packages folded into the "+N" card (beyond the first 6), and stickd's small-package aggregate. */
    final List<TaskSnapshot.App> stripMore = new ArrayList<>();
    TaskSnapshot.App sysOther;

    TaskMapView.Sort sort = TaskMapView.Sort.RAM;
    boolean paused, shownOnce, profileChecked;
    TaskSnapshot shown;
    long lastStartAt;
    /** Per-app RAM trend; cleared on pause, hide and a stale feed so a trend never spans a gap. */
    final Map<String, RamHist> hist = new HashMap<>();
    final Map<String, String> labels = new HashMap<>();
    final Set<String> labelTried = new HashSet<>();
    Set<String> launchable = new HashSet<>();
    TaskDialog dialog;
    /** Snapshot listener → main thread, coalesced. */
    volatile boolean posted;
    final Runnable onSnap = () -> { posted = false; applySnapshot(); };
    final Runnable feedListener = () -> { if (!posted) { posted = true; h.post(onSnap); } };

    /** Pending End / End all result (0 = none). */
    long endAt, endAllAt;
    /** The pending End was already confirmed by the user ("<pkg> force"). */
    boolean endForced;
    String endPkg, endLabel;
    /** What to say when the pending stickd.conf save (Protect / Always kill) completes. */
    String savedMsg;
    /** Since when (elapsedRealtime) no fresh sample has arrived while live (0 = fresh). */
    long staleSince;

    @Override public String title() { return "Tasks"; }

    @Override public View build(MainActivity act) {
        a = act;
        LinearLayout l = Theme.column(a);

        LinearLayout head = Theme.row(a);
        TextView t = Theme.title(a, "Tasks");
        t.setPadding(0, 0, 0, 0);
        head.addView(t);
        head.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        ram = stat(head, "RAM – / – GB");
        cpu = stat(head, "CPU – %");
        autoKill = stat(head, "Auto-close –");
        dot = new Dot(a);
        int ds = Theme.dp(a, 18);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ds, ds);
        dlp.setMargins(Theme.dp(a, 6), 0, Theme.dp(a, 4), 0);
        head.addView(dot, dlp);
        liveText = Theme.text(a, "waiting…", 16, Theme.MUTED);
        liveText.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(liveText);
        head.setPadding(0, 0, 0, Theme.dp(a, 6));
        l.addView(head);
        l.addView(Theme.subtitle(a, "What is running now, sized by memory. OK on an app for details."));

        result = Theme.autoHide(Theme.text(a, "", 15, Theme.ACCENT));
        result.setPadding(0, 0, 0, Theme.dp(a, 4));
        l.addView(result);

        map = new TaskMapView(a);
        map.onPick = this::pick;
        map.setFocusable(false);   // until it has tiles: RIGHT from the sidebar then lands on the strip
        l.addView(map, new LinearLayout.LayoutParams(-1, 0, 1f));

        strip = Theme.row(a);
        strip.setWeightSum(STRIP_MAX + 1);
        strip.setPadding(0, Theme.dp(a, 4), 0, 0);
        for (int i = 0; i <= STRIP_MAX; i++) strip.addView(card(i));
        l.addView(strip, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = Theme.text(a, "OK  details      ⏯  pause / resume      ☰  sort · end all", 13, Theme.MUTED);
        hint.setPadding(Theme.dp(a, 2), Theme.dp(a, 6), 0, 0);
        l.addView(hint);
        TextView legend = Theme.text(a, "Bigger tile = more memory   ·   ★ protected, never closed   ·   ⚡ always killed when left   ·   ▶ playing",
            13, Theme.MUTED);
        legend.setPadding(Theme.dp(a, 2), Theme.dp(a, 2), 0, 0);
        l.addView(legend);
        return l;
    }

    TextView stat(LinearLayout row, String s) {
        TextView t = Theme.text(a, s, 16, Theme.TEXT);
        t.setPadding(Theme.dp(a, 18), 0, 0, 0);
        row.addView(t);
        return t;
    }

    LinearLayout card(final int i) {
        LinearLayout c = Theme.column(a);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[] { android.R.attr.state_focused }, Theme.rect(a, 0xFF2C241C, Theme.ACCENT, 3));
        sl.addState(new int[] {}, Theme.rect(a, 0xFF1E1914, 0xFF3A3028, 1));
        c.setBackground(sl);
        int p = Theme.dp(a, 8);
        c.setPadding(p, Theme.dp(a, 5), p, Theme.dp(a, 5));
        c.setFocusable(true);
        c.setClickable(true);
        cardName[i] = TaskDialog.line(a, "", 13, Theme.MUTED);
        cardMb[i] = TaskDialog.line(a, "", 12, 0xFF8A8076);
        c.addView(cardName[i]);
        c.addView(cardMb[i]);
        c.setOnClickListener(v -> stripPick(i));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(0, 0, i < STRIP_MAX ? Theme.dp(a, 6) : 0, 0);
        c.setLayoutParams(lp);
        c.setVisibility(View.GONE);
        cards[i] = c;
        return c;
    }

    // ---- lifecycle ----

    @Override public void onShow() {
        if (a == null) return;
        if (!profileChecked) { profileChecked = true; ownProfile(); }
        launchable = new HashSet<>();
        try { for (String[] app : ButtonEditor.launchables(a)) launchable.add(app[1]); } catch (RuntimeException ignored) {}
        refreshConf();
        staleSince = 0;
        LogTail.FEED.setListener(feedListener);
        if (!paused) { startSampling(); applySnapshot(); }
        updateLive();
    }

    @Override public void onHide() {
        LogTail.FEED.setListener(null);
        if (a != null && !paused) sendTasks(Actions.TASKS_STOP);
        lastStartAt = 0;
        clearHist();
    }

    /** Drops every RAM trend (pause, hide, stale feed); an open dialog shows "collecting…" again. */
    void clearHist() {
        if (hist.isEmpty()) return;
        hist.clear();
        if (dialog != null && dialog.open) dialog.update(app(dialog.pkg), null);
    }

    @Override public void onTick() {
        if (a == null) return;
        long since = lastStartAt == 0 ? Long.MAX_VALUE : SystemClock.elapsedRealtime() - lastStartAt;
        if (TaskKeepalive.shouldStart(paused, since, feedAge())) startSampling();
        updateLive();
        pollEnd();
    }

    /** First show: give Stickman its own remote profile so ⏯ / ☰ reach this page even if remapped globally. */
    void ownProfile() {
        RemapModel m = Store.remap(a);
        if (!OwnProfile.ensure(m)) return;
        Store.saveRemap(a, m);
        Log.i(TAG, "tasks: added own remote profile");
        if (Store.enabled(a)) a.run(null, Ensure.MARKER);
    }

    void startSampling() {
        lastStartAt = SystemClock.elapsedRealtime();
        sendTasks(Actions.TASKS_START);
    }

    /** tasks_start / tasks_stop: quiet (no result line), never displacing another VPN, only when stickd is meant to run. */
    void sendTasks(String id) {
        if (!Store.enabled(a) || Bridge.otherVpnActive(a)) return;
        Bridge.run(a, RemoteCmds.act(id, null), false);
    }

    void togglePause() {
        paused = !paused;
        map.setPaused(paused);
        if (paused) {
            Log.i(TAG, "tasks paused");
            sendTasks(Actions.TASKS_STOP);
            clearHist();
        } else {
            Log.i(TAG, "tasks live");
            startSampling();
            applySnapshot();
        }
        updateLive();
    }

    @Override public boolean onKey(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                if (k == KeyEvent.KEYCODE_MENU) menu(); else togglePause();
            }
            return true;
        }
        // DOWN off the map's bottom edge: the strip card under the focused tile (not just the nearest to the map's centre)
        if (k == KeyEvent.KEYCODE_DPAD_DOWN && e.getAction() == KeyEvent.ACTION_DOWN && map.hasFocus() && !map.canMove(TileNav.DOWN)) {
            View c = cardUnderFocus();
            if (c != null) { c.requestFocus(); return true; }
        }
        return false;
    }

    View cardUnderFocus() {
        int fi = map.focusedIndex();
        if (fi < 0 || fi >= map.rects.size()) return null;
        Treemap.Rect r = map.rects.get(fi);
        float x = (float) r.cx();
        int[] ml = new int[2], cl = new int[2];
        map.getLocationOnScreen(ml);
        View best = null;
        float bd = Float.MAX_VALUE;
        for (LinearLayout c : cards) {
            if (c.getVisibility() != View.VISIBLE) continue;
            c.getLocationOnScreen(cl);
            float d = Math.abs(cl[0] - ml[0] + c.getWidth() / 2f - x);
            if (d < bd) { bd = d; best = c; }
        }
        return best;
    }

    // ---- data ----

    StickdConf conf() { return StickdConf.parse(Store.stickdText(a)); }

    /** Auto-kill header and the ★ / ⚡ marks from the stored conf. */
    void refreshConf() {
        StickdConf c = conf();
        autoKill.setText("Auto-close " + (c.onLeave || c.intervalMin > 0 ? "On" : "Off"));
        map.setMarks(protectedSet(c, map.snapshot()), c.blacklist);
    }

    /** Whitelist ∪ the snapshot's never-kill packages (Wolf, Net Filter...): shown ★, End disabled. */
    static Set<String> protectedSet(StickdConf c, TaskSnapshot s) {
        Set<String> p = new HashSet<>(c.whitelist);
        if (s != null) for (TaskSnapshot.App x : s.apps) if (KillPolicy.isNeverKill(x.pkg)) p.add(x.pkg);
        return p;
    }

    void applySnapshot() {
        if (a == null || paused) return;
        TaskSnapshot s = LogTail.FEED.latest();
        if (s == null || s == shown) return;
        shown = s;
        Set<String> seen = new HashSet<>();
        boolean newLabels = false;
        for (TaskSnapshot.App x : s.apps) {
            seen.add(x.pkg);
            RamHist hh = hist.get(x.pkg);
            if (hh == null) { hh = new RamHist(); hist.put(x.pkg, hh); }
            hh.add(s.seq, s.atMs, x.rssKb);
            if (labelTried.add(x.pkg) && !x.pkg.startsWith("_")) {
                String lb = ButtonEditor.appLabel(a, x.pkg);
                // no label of its own: a name Stickman knows offline (catalogue, Android system), else the map shortens it
                if (lb == null || lb.equals(x.pkg) || lb.trim().isEmpty()) lb = Catalog.label(x.pkg);
                if (lb != null && !lb.equals(x.pkg) && !lb.trim().isEmpty()) { labels.put(x.pkg, lb); newLabels = true; }
            }
        }
        hist.keySet().retainAll(seen);
        if (newLabels) map.setLabels(labels);
        map.setSnapshot(s, sort);
        refreshConf();
        boolean had = map.isFocused();
        map.setFocusable(map.focusedKey() != null);
        if (had && !map.isFocusable()) { View c = firstCard(); if (c != null) c.requestFocus(); }

        long used = s.totalKb - s.availKb;
        ram.setText(String.format(Locale.ROOT, "RAM %.1f / %.1f GB", used / 1048576.0, s.totalKb / 1048576.0));
        cpu.setText("CPU " + s.cpuPct + " %");
        fillStrip();
        if (dialog != null && dialog.open) dialog.update(app(dialog.pkg), hist.get(dialog.pkg));
    }

    TaskSnapshot.App app(String pkg) {
        TaskSnapshot s = map.snapshot();
        if (s == null || pkg == null) return null;
        for (TaskSnapshot.App x : s.apps) if (x.pkg.equals(pkg)) return x;
        return null;
    }

    String label(String pkg) { return map.labelOf(pkg); }

    void fillStrip() {
        List<TaskSnapshot.App> sys = map.systemApps();
        stripMore.clear();
        sysOther = null;
        List<TaskSnapshot.App> ranked = new ArrayList<>();
        for (TaskSnapshot.App x : sys) {
            if (TaskMapView.SYS_OTHER.equals(x.pkg)) sysOther = x;
            else ranked.add(x);   // largest first
        }
        TaskSnapshot.App[] slots = stripSlots(ranked);
        int shownN = 0;
        Set<String> inSlots = new HashSet<>();
        for (TaskSnapshot.App x : slots) {
            if (x == null) continue;
            inSlots.add(x.pkg);
            cardKey[shownN] = x.pkg;
            cardName[shownN].setText(label(x.pkg));
            cardMb[shownN].setText(Units.mb(x.rssKb));
            shownN++;
        }
        for (TaskSnapshot.App x : ranked) if (!inSlots.contains(x.pkg)) stripMore.add(x);
        int more = stripMore.size() + (sysOther == null ? 0 : sysOther.procs);
        int last = shownN;
        if (more > 0) {
            long kb = sysOther == null ? 0 : sysOther.rssKb;
            for (TaskSnapshot.App x : stripMore) kb += x.rssKb;
            cardKey[last] = "+";
            cardName[last].setText("+" + more);
            cardMb[last].setText(Units.mb(kb));
            last++;
        }
        View f = a.getCurrentFocus();
        int focusedCard = -1;
        for (int i = 0; i < cards.length; i++) if (cards[i] == f) focusedCard = i;
        for (int i = 0; i < cards.length; i++) cards[i].setVisibility(i < last ? View.VISIBLE : View.GONE);
        if (focusedCard >= last) {
            if (last > 0) cards[last - 1].requestFocus();
            else if (map.isFocusable()) map.requestFocus();
        }
    }

    /** Packages on the strip's cards last time, by slot (null = empty). */
    final String[] prevStrip = new String[STRIP_MAX];

    /**
     * The strip's 6 cards, stable so a focused card doesn't change under the cursor every 2 s: a package keeps its
     * slot while it ranks in the top {@link #STRIP_KEEP}; freed slots take the largest packages not yet shown.
     * ranked = system packages, largest first.
     */
    TaskSnapshot.App[] stripSlots(List<TaskSnapshot.App> ranked) {
        TaskSnapshot.App[] slots = new TaskSnapshot.App[STRIP_MAX];
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) rank.put(ranked.get(i).pkg, i);
        Set<String> used = new HashSet<>();
        for (int s = 0; s < STRIP_MAX; s++) {
            Integer r = prevStrip[s] == null ? null : rank.get(prevStrip[s]);
            if (r != null && r < STRIP_KEEP) { slots[s] = ranked.get(r); used.add(prevStrip[s]); }
        }
        int next = 0;
        for (int s = 0; s < STRIP_MAX; s++) {
            if (slots[s] != null) continue;
            while (next < ranked.size() && used.contains(ranked.get(next).pkg)) next++;
            if (next >= ranked.size()) break;
            slots[s] = ranked.get(next);
            used.add(slots[s].pkg);
        }
        for (int s = 0; s < STRIP_MAX; s++) prevStrip[s] = slots[s] == null ? null : slots[s].pkg;
        return slots;
    }

    View firstCard() { return cards[0].getVisibility() == View.VISIBLE ? cards[0] : null; }

    // ---- header ----

    /** Age of the latest completed snapshot (ms), Long.MAX_VALUE if none. */
    static long feedAge() {
        long at = LogTail.lastFeedAt;
        return at == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - at;
    }

    /** Header state; the map shows the same reason in words while it has no tiles (a blank map is a dead end). */
    void updateLive() {
        if (a == null) return;
        if (paused) {
            dot.set(Theme.ACCENT, false);
            liveText.setText("PAUSED");
            liveText.setTextColor(Theme.ACCENT);
            map.setEmptyText("Paused: press ⏯ to resume.");
            return;
        }
        if (LogTail.FEED.latest() == null || feedAge() > STALE_MS) {
            clearHist();
            dot.set(0, false);
            long now = SystemClock.elapsedRealtime();
            if (staleSince == 0) staleSince = now;
            String head, why;
            int color = Theme.MUTED;
            if (!Store.enabled(a)) { head = "Stickman is OFF"; why = "Stickman is OFF: turn it on in Settings to see what's running."; }
            else if (Bridge.otherVpnActive(a)) { head = "VPN active — not sampling"; why = com.clean.stickman.core.VpnText.cantRead(Store.remapRunning(a)); }
            else if (now - staleSince > NO_DATA_MS) { head = "NO DATA"; why = NO_DATA_HINT; color = Theme.ACCENT; }
            else { head = "waiting…"; why = "Waiting for the first sample (about 2 s)…"; }
            liveText.setText(head);
            liveText.setTextColor(color);
            map.setEmptyText(why);
            return;
        }
        staleSince = 0;
        dot.set(Dot.GREEN, true);
        liveText.setText("LIVE · 2 s");
        liveText.setTextColor(Dot.GREEN);
        map.setEmptyText("Only the system is running: no apps to show.");
    }

    /** LIVE dot: soft glow, pulsing from the clock while live (no animator, nothing allocated per frame). */
    static final class Dot extends View {
        static final int GREEN = 0xFF4CD964;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int color;
        boolean pulse;

        Dot(Context c) { super(c); setFocusable(false); }

        void set(int c, boolean pulse) {
            if (c == color && pulse == this.pulse) return;
            color = c;
            this.pulse = pulse;
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            if (color == 0) return;
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
            float k = pulse ? (float) (0.5 + 0.5 * Math.sin(SystemClock.uptimeMillis() / 1600.0 * 2 * Math.PI)) : 0.5f;
            p.setColor(color);
            p.setAlpha(Math.round(40 + 60 * k));
            cv.drawCircle(cx, cy, r * (0.75f + 0.25f * k), p);   // glow
            p.setAlpha(255);
            cv.drawCircle(cx, cy, r * 0.45f, p);
            if (pulse && isShown()) postInvalidateDelayed(66);
        }
    }

    // ---- tiles / dialogs ----

    void pick(String key) {
        if (TaskMapView.MORE.equals(key)) { moreList(); return; }
        showDialog(key, false);
    }

    /** The map's "+N more" tile: plain list, choosing one opens its tile dialog. */
    void moreList() {
        final List<TaskSnapshot.App> hid = map.hiddenApps();
        if (hid.isEmpty()) return;
        String[] items = new String[hid.size()];
        for (int i = 0; i < items.length; i++) items[i] = label(hid.get(i).pkg) + "  ·  " + Units.mb(hid.get(i).rssKb);
        ThemedDialog.list(a, hid.size() + " smaller apps", items, i -> showDialog(hid.get(i).pkg, false));
    }

    void stripPick(int i) {
        String k = cardKey[i];
        if (k == null) return;
        if (!"+".equals(k)) { showDialog(k, true); return; }
        final List<TaskSnapshot.App> more = new ArrayList<>(stripMore);
        final TaskSnapshot.App other = sysOther;
        String[] items = new String[more.size() + (other == null ? 0 : 1)];
        for (int j = 0; j < more.size(); j++) items[j] = label(more.get(j).pkg) + "  ·  " + Units.mb(more.get(j).rssKb);
        if (other != null) items[more.size()] = other.procs + " more system packages  ·  " + Units.mb(other.rssKb);
        ThemedDialog.list(a, "More system packages", items, j -> {
            if (j < more.size()) showDialog(more.get(j).pkg, true);
            else ThemedDialog.message(a, "Other system", other.procs + " small system packages together use "
                + Units.mb(other.rssKb) + ". Stickman doesn't list them one by one.");
        });
    }

    /** Local version of stickd's EndPolicy check, from the latest snapshot's states and the stored whitelist. */
    String endBlocked(String pkg, StickdConf c) {
        String fg = null;
        Set<String> play = new HashSet<>();
        TaskSnapshot s = map.snapshot();
        if (s != null) for (TaskSnapshot.App x : s.apps) {
            if ("fg".equals(x.state)) fg = x.pkg;
            else if ("play".equals(x.state)) play.add(x.pkg);
        }
        return EndPolicy.check(pkg, fg, play, c.whitelist, launchable, false);
    }

    void showDialog(final String pkg, boolean system) {
        TaskSnapshot.App x = app(pkg);
        StickdConf c = conf();
        TaskDialog.Options o = new TaskDialog.Options();
        o.detailsOnly = system || (x != null && "sys".equals(x.state));
        o.endBlocked = endBlocked(pkg, c);
        // Only Stickman, the system and Bluetooth can't be ended. Every other reason (playing, protected, spared by
        // Stickman, a system service) keeps End available behind an "Are you sure?" that says why.
        final String askWhy = o.endBlocked != null && !"never".equals(o.endBlocked) ? o.endBlocked : null;
        final boolean guarded = askWhy != null;
        if (guarded) {
            o.guarded = EndPolicy.GUARDED.equals(askWhy);
            o.endBlocked = null;
            if (o.detailsOnly) { o.detailsOnly = false; o.canAlwaysKill = false; o.canProtect = false; }   // system: End only
        }
        o.protectedPkg = c.whitelist.contains(pkg);
        o.alwaysKill = c.blacklist.contains(pkg);
        boolean never = KillPolicy.isNeverKill(pkg) || EndPolicy.SELF.equals(pkg);
        boolean sysTile = system || (x != null && "sys".equals(x.state));
        o.canAlwaysKill = !never && !sysTile && !com.clean.stickman.core.Catalog.isProtected(pkg);
        o.canProtect = !never && !sysTile;
        o.canOpen = launchIntent(pkg) != null;
        final TaskDialog[] self = new TaskDialog[1];
        // the dismiss listener runs later (posted): only clear the field if it still points at this dialog
        self[0] = new TaskDialog(a, label(pkg), pkg, x, hist.get(pkg), o, new TaskDialog.Actions() {
            public void end(String p) {
                if (!guarded) { endApp(p, false); return; }
                ThemedDialog.confirm(a, "Are you sure?", EndPolicy.confirmText(askWhy, label(p)), "End app", () -> endApp(p, true));
            }
            public void alwaysKill(String p, boolean on) { setAlwaysKill(p, on); }
            public void protect(String p, boolean on) { setProtect(p, on); }
            public void open(String p) { openApp(p); }
        }, () -> { if (dialog == self[0]) dialog = null; });
        dialog = self[0];
    }

    void endApp(String pkg, boolean force) {
        if (!stickdOn()) return;
        endAt = 0;   // armed only once the command is really sent (not if the VPN warning is cancelled)
        endPkg = pkg;
        endForced = force;
        endLabel = label(pkg);
        send(RemoteCmds.act(Actions.END, force ? pkg + " force" : pkg), "end", "Ending " + endLabel + "…", () -> endAt = System.currentTimeMillis());
    }

    void setAlwaysKill(final String pkg, boolean on) {
        final String lb = label(pkg);
        if (!on) {
            savedMsg = lb + " is no longer always killed.";
            applyStickdConf(Store.removeFromList(a, "blacklist", pkg), "conf");
            refreshConf();
            return;
        }
        ThemedDialog.confirm(a, "Always kill " + lb + "?", "Close " + lb + " every time it goes to the background, even with "
            + "auto-close off? It shows ⚡ on its tile.", "Always kill", () -> {
                savedMsg = lb + " ⚡ is closed whenever you leave it.";
                applyStickdConf(Store.addToList(a, "blacklist", pkg), "conf");
                refreshConf();
            });
    }

    void setProtect(String pkg, boolean on) {
        String lb = label(pkg);
        savedMsg = on ? lb + " ★ is protected: Stickman never closes it." : lb + " is no longer protected.";
        applyStickdConf(on ? Store.addToList(a, "whitelist", pkg) : Store.removeFromList(a, "whitelist", pkg), "conf");
        refreshConf();
    }

    Intent launchIntent(String pkg) {
        if (pkg == null || pkg.startsWith("_")) return null;
        android.content.pm.PackageManager pm = a.getPackageManager();
        Intent i = null;
        try { i = pm.getLeanbackLaunchIntentForPackage(pkg); } catch (RuntimeException ignored) {}
        if (i == null) try { i = pm.getLaunchIntentForPackage(pkg); } catch (RuntimeException ignored) {}
        return i;
    }

    void openApp(String pkg) {
        Intent i = launchIntent(pkg);
        if (i == null) { say("Can't open " + label(pkg) + "."); return; }
        try { a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (RuntimeException e) { say("Can't open " + label(pkg) + "."); }
    }

    // ---- menu / End all ----

    void menu() {
        Log.i(TAG, "tasks menu");
        String[] items = new String[SORTS.length + 1];
        System.arraycopy(SORTS, 0, items, 0, SORTS.length);
        items[SORTS.length] = "End all";
        ThemedDialog.list(a, "Tasks", items, sort.ordinal(), i -> {
            if (i < SORTS.length) {
                sort = TaskMapView.Sort.values()[i];
                TaskSnapshot s = map.snapshot();
                if (s != null) map.setSnapshot(s, sort);
            } else endAll();
        }).onDismiss(() -> Log.i(TAG, "tasks menu closed"));   // Back (or a choice) closed it; devtest expects this
    }

    void endAll() {
        if (!stickdOn()) return;
        StickdConf c = conf();
        int n = 0;
        TaskSnapshot s = map.snapshot();
        if (s != null) for (TaskSnapshot.App x : s.apps) if (!"sys".equals(x.state) && endBlocked(x.pkg, c) == null) n++;
        if (n == 0) { say("Nothing to close: only protected, playing or in-front apps are running."); return; }
        ThemedDialog.confirm(a, "End all?", "Close " + n + (n == 1 ? " app" : " apps")
            + "? Protected, playing and the app in front stay.", "End all", () -> {
                endAllAt = 0;   // armed only once the command is really sent
                send(RemoteCmds.act(Actions.END_ALL, null), "end_all", "Closing apps…", () -> endAllAt = System.currentTimeMillis());
            });
    }

    /** Results of End / End all arrive as STATUS lines (matched by time after the send). */
    void pollEnd() {
        long now = System.currentTimeMillis();
        if (endAt != 0) {
            String st = LogTail.status("end");
            if (st != null && LogTail.statusAt("end") >= endAt - 1000 && st.startsWith(endPkg + " ")) {
                endAt = 0;
                int r = st.indexOf("refused=");
                String why = r >= 0 ? st.substring(r + 8).trim() : null;
                // The stick knows more than the tile (a paused video, a service): its refusal of a plain End is a
                // question for the user, not the end of it. Only "never" (Stickman, system, Bluetooth) is final.
                if (why != null && !endForced && !"never".equals(why)) {
                    final String p = endPkg;
                    ThemedDialog.confirm(a, "Are you sure?", EndPolicy.confirmText("system".equals(why) ? EndPolicy.GUARDED : why, endLabel),
                        "End app", () -> endApp(p, true));
                } else {
                    say(why != null ? "Can't end " + endLabel + ": " + ResultText.reason(why) : "Ended " + endLabel + ".");
                }
            } else if (now - endAt > RESULT_TIMEOUT_MS && !Bridge.busy) {
                endAt = 0;
                say(NO_ANSWER);
            }
        }
        if (endAllAt != 0) {
            String st = LogTail.status("end_all");
            if (st != null && LogTail.statusAt("end_all") >= endAllAt - 1000) {
                endAllAt = 0;
                Matcher m = KILLED.matcher(st);
                int k = m.find() ? Integer.parseInt(m.group(1)) : -1;
                say(k < 0 ? "End all: " + st : k == 0 ? "Nothing was closed: the apps were protected, playing or in front." : "Closed " + ResultText.apps(k) + ".");
            } else if (now - endAllAt > RESULT_TIMEOUT_MS && !Bridge.busy) {
                endAllAt = 0;
                say(NO_ANSWER);
            }
        }
    }

    static final String NO_ANSWER = "No answer from the stick within 20 s. Check the Status page: is background automation running?";

    @Override String describe(String kind, String out) {
        if (kind.equals("end") || kind.equals("end_all")) return null;   // the STATUS line reports the outcome
        if (kind.equals("conf")) {
            String m = savedMsg != null ? savedMsg : "Saved ✓";
            savedMsg = null;
            return Store.pid(out, "STICKD") != null ? m : "Saved, but background automation isn't running (see the Status page).";
        }
        return null;
    }
}
