package com.clean.stickman;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.graphics.Rect;
import android.view.FocusFinder;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.clean.stickman.core.FocusPick;
import com.clean.stickman.core.Keys;
import com.clean.stickman.core.RemoteLayout;
import com.clean.stickman.core.Trouble;

import java.util.ArrayList;

/** App shell: left sidebar of sections + a content area that swaps Page views. Fully D-pad driven. */
public class MainActivity extends Activity implements Bridge.Listener {
    static final String[] ALL_SECTIONS = { "Remote", "Tasks", "Speed", "Sleep", "Privacy", "Debloat", "Shortcuts", "Status", "Settings" };
    static final String[] SECTIONS = visibleSections();

    static String[] visibleSections() {
        java.util.List<String> l = new ArrayList<>();
        for (String x : ALL_SECTIONS) if (!com.clean.stickman.core.Edition.hidden(x)) l.add(x);
        return l.toArray(new String[0]);
    }
    static final String VPN_WARNING = "A VPN (e.g. NordVPN) is connected. Applying will disconnect it. Continue?";

    final Page[] pages = new Page[SECTIONS.length];
    final View[] views = new View[SECTIONS.length];
    final Button[] nav = new Button[SECTIONS.length];
    final Handler h = new Handler();
    LinearLayout sidebar;
    ScrollView sideScroll;
    /** A Back press in the content moved focus to the sidebar: swallow its release too (don't exit). */
    boolean backToSidebar;
    FrameLayout content;
    TextView statusLine;
    int current = -1;
    LogTail tail;
    boolean hadLogs;
    /** The open-time ensure was skipped because another VPN is up. */
    boolean vpnPaused;
    /** When this activity was created: only a bridge failure newer than this gets the "here's the fix" dialog. */
    long createdAt;
    /** That dialog is shown once per activity (again after the user went to fix it in Developer options). */
    boolean troubleShown;
    /** The update question / what's-new note was considered for this activity (once). */
    boolean updateChecked;
    /** The stickd update status already looked at (LogTail.statusAt), so each report is considered once. */
    long updateStatusSeen = -1;
    /** The user left for Developer options from the dialog: re-check when they come back. */
    boolean retryOnResume;

    Page makePage(int i) {
        switch (SECTIONS[i]) {
            case "Remote": return new RemotePage();
            case "Tasks": return new TasksPage();
            case "Status": return new StatusPage();
            case "Speed": return new SpeedPage();
            case "Sleep": return new SleepPage();
            case "Privacy": return new PrivacyPage();
            case "Debloat": return new DebloatPage();
            case "Shortcuts": return new ShortcutsPage();
            case "Settings": return new SettingsPage();
            default: throw new IllegalArgumentException(SECTIONS[i]);
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        createdAt = System.currentTimeMillis();
        FadeOverlay.stop();   // clear a sleep-fade overlay left behind
        hadLogs = checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED;
        BootJob.schedule(this);

        LinearLayout root = Theme.row(this);
        root.setBackgroundColor(Theme.BG);
        root.setGravity(0);

        LinearLayout side = Theme.column(this);
        side.setBackgroundColor(Theme.PANEL);
        int p = Theme.dp(this, 20);
        side.setPadding(p, Theme.dp(this, 20), Theme.dp(this, 12), Theme.dp(this, 12));
        TextView brand = Theme.text(this, "Stickman", 26, Theme.ACCENT);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setPadding(Theme.dp(this, 4), 0, 0, Theme.dp(this, 10));
        side.addView(brand);
        // the items scroll (the focused one is always brought into view), so every section is reachable at any size
        sidebar = Theme.column(this);
        for (int i = 0; i < SECTIONS.length; i++) {
            final int idx = i;
            Button bt = Theme.button(this, SECTIONS[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, Theme.dp(this, 3), 0, Theme.dp(this, 3));
            bt.setLayoutParams(lp);
            bt.setOnClickListener(v -> select(idx));
            nav[i] = bt;
            sidebar.addView(bt);
        }
        sideScroll = new ScrollView(this);
        sideScroll.setVerticalScrollBarEnabled(false);
        sideScroll.setFocusable(false);
        sideScroll.addView(sidebar);
        side.addView(sideScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        statusLine = Theme.text(this, "", 13, Theme.MUTED);
        statusLine.setPadding(Theme.dp(this, 4), Theme.dp(this, 6), 0, 0);
        side.addView(statusLine);
        root.addView(side, new LinearLayout.LayoutParams(Theme.dp(this, 220), -1));

        content = new FrameLayout(this);
        int cp = Theme.dp(this, 32);
        content.setPadding(cp, Theme.dp(this, 24), cp, Theme.dp(this, 16));
        root.addView(content, new LinearLayout.LayoutParams(0, -1, 1f));

        setContentView(root);
        select(0);
        nav[0].requestFocus();

        Bridge.setListener(this);
        Intent consent = VpnService.prepare(this);
        if (consent != null) startActivityForResult(consent, 1);
        else ensureOnOpen();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != 1) return;
        if (res == RESULT_OK) { ensureOnOpen(); return; }
        // declined: nothing works without it, so say why and ask again (the system dialog is the only way in)
        new ThemedDialog(this).title("Stickman needs the VPN permission")
            .message("Stickman talks to your Fire TV's own debugging service through a tiny private VPN that only "
                + "carries its own commands. Your normal internet traffic never goes through it.\n\nWithout it, "
                + "Stickman can't remap buttons or change anything.")
            .button("Ask again", () -> {
                Intent c = VpnService.prepare(this);
                if (c != null) startActivityForResult(c, 1); else ensureOnOpen();
            })
            .cancel("Not now").focusButton(0).show();
    }

    /** Not user-initiated: with another VPN up, skip silently (status line says paused); no dialog on every open. */
    void ensureOnOpen() {
        if (!Store.enabled(this)) return;
        if (Bridge.otherVpnActive(this)) { vpnPaused = true; updateStatusLine(); return; }
        Bridge.run(this, Ensure.MARKER, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        FadeOverlay.stop();
        Bridge.setListener(this);
        tail = new LogTail();
        new Thread(tail, "logtail").start();
        if (current >= 0) pages[current].onShow();
        if (retryOnResume) {
            // back from Developer options: check again, and say again what's wrong if it still fails
            retryOnResume = false;
            troubleShown = false;
            if (Store.enabled(this) && !Bridge.busy && !Bridge.otherVpnActive(this)) Bridge.run(this, Ensure.MARKER, false);
        }
        h.post(this::tick);
    }

    /** Show a section by name (e.g. "Settings") and put the focus on its first control. */
    void goTo(String section) {
        for (int i = 0; i < SECTIONS.length; i++) {
            if (!SECTIONS[i].equals(section)) continue;
            select(i);
            enterPage();
            return;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (tail != null) tail.shutdown();
        if (current >= 0) pages[current].onHide();
        h.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (Bridge.listener == this) Bridge.setListener(null);
    }

    void select(int i) {
        if (i == current) return;
        if (current >= 0) {
            pages[current].onHide();
            nav[current].setSelected(false);
        }
        if (pages[i] == null) {
            pages[i] = makePage(i);
            views[i] = pages[i].build(this);
        }
        content.removeAllViews();
        content.addView(views[i], new FrameLayout.LayoutParams(-1, -1));
        current = i;
        nav[i].setSelected(true);
        pages[i].onShow();
    }

    static boolean isIn(View v, View parent) {
        for (ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == parent) return true;
        return false;
    }

    /**
     * Focus rules (D-pad):
     * - Right from the sidebar enters the page on its first VISIBLE control.
     * - Left at the page's left edge returns to the ACTIVE page's sidebar item.
     * - Up/Down never leave their region: the sidebar and the page each keep focus at their ends.
     * - Entering a row of choice chips vertically lands on its selected chip.
     * - Back in the page returns to the sidebar; Back on the sidebar leaves the app.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        // a pending "swallow the BACK release" never outlives another key
        if (e.getAction() == KeyEvent.ACTION_DOWN && k != KeyEvent.KEYCODE_BACK) backToSidebar = false;
        // first, before any other handling: e.g. the Tasks page's ⏯ (MEDIA_PLAY_PAUSE) and ☰ (MENU)
        if (current >= 0 && pages[current].onKey(e)) return true;
        if (k == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP && backToSidebar) {
            backToSidebar = false;
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN || current < 0) return super.dispatchKeyEvent(e);
        View f = getCurrentFocus();
        if (f == null) return super.dispatchKeyEvent(e);
        boolean inSide = isIn(f, sidebar), inContent = isIn(f, content);
        if (k == KeyEvent.KEYCODE_BACK && inContent) {
            if (e.getRepeatCount() == 0) nav[current].requestFocus();
            backToSidebar = true;
            return true;
        }
        int dir = k == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : k == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN
            : k == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : k == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT : 0;
        if (dir == 0) return super.dispatchKeyEvent(e);
        if (inSide) {
            if (dir == View.FOCUS_RIGHT) {
                if (f != nav[current]) select(indexOf(f));
                enterPage();
                return true;
            }
            if (dir == View.FOCUS_LEFT) return true;
            View n = FocusFinder.getInstance().findNextFocus(sidebar, f, dir);
            if (n == null) return true;   // top/bottom of the sidebar: stay
            return super.dispatchKeyEvent(e);
        }
        if (!inContent) return super.dispatchKeyEvent(e);
        // the drawn remote moves its own focus first
        if (f instanceof RemoteView && ((RemoteView) f).canMove(rlDir(dir))) return super.dispatchKeyEvent(e);
        if (f instanceof TaskMapView && ((TaskMapView) f).canMove(rlDir(dir))) return super.dispatchKeyEvent(e);   // same constants as TileNav
        View n = FocusFinder.getInstance().findNextFocus(content, f, dir);
        if (dir == View.FOCUS_LEFT) {
            if (n == null || !visible(n) || !overlapsVertically(n, f)) { nav[current].requestFocus(); return true; }
            return super.dispatchKeyEvent(e);
        }
        if (dir == View.FOCUS_RIGHT) {
            if (n == null) return true;
            return super.dispatchKeyEvent(e);
        }
        // up / down
        if (n == null) {
            reveal(f, dir);   // show trailing text, but never leave the page
            return true;
        }
        View chip = chipTarget(f, n);
        View t = chip != null ? chip : n;
        t.requestFocus(dir);   // its ScrollView scrolls it into view
        return true;
    }

    /** At the page's top/bottom control: scroll towards the remaining text while f stays fully visible. */
    void reveal(View f, int dir) {
        ScrollView sv = scrollOf(f);
        if (sv == null) return;
        Rect r = new Rect(0, 0, f.getWidth(), f.getHeight());
        sv.offsetDescendantRectToMyCoords(f, r);
        int top = sv.getScrollY(), bottom = top + sv.getHeight() - sv.getPaddingBottom();
        int max = sv.getChildAt(0).getHeight() - sv.getHeight();
        int delta = dir == View.FOCUS_DOWN ? Math.min(max - top, r.top - top) : -Math.min(top, bottom - r.bottom);
        if (delta != 0) sv.smoothScrollBy(0, delta);
    }

    static int rlDir(int dir) {
        switch (dir) {
            case View.FOCUS_UP: return RemoteLayout.UP;
            case View.FOCUS_DOWN: return RemoteLayout.DOWN;
            case View.FOCUS_LEFT: return RemoteLayout.LEFT;
            default: return RemoteLayout.RIGHT;
        }
    }

    ScrollView scrollOf(View v) {
        for (ViewParent p = v.getParent(); p instanceof View && p != content; p = p.getParent())
            if (p instanceof ScrollView) return (ScrollView) p;
        return null;
    }

    /** Moving vertically into a chip row from outside it: its selected chip (or its first), else null. */
    static View chipTarget(View from, View n) {
        ViewParent row = n.getParent();
        if (!(row instanceof ViewGroup) || !Theme.CHIPS.equals(((View) row).getTag()) || from.getParent() == row) return null;
        ViewGroup g = (ViewGroup) row;
        View first = null;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (!c.isFocusable() || !c.isEnabled() || c.getVisibility() != View.VISIBLE) continue;
            if (c.isSelected()) return c == n ? null : c;
            if (first == null) first = c;
        }
        return first == n ? null : first;
    }

    static boolean visible(View v) {
        Rect r = new Rect();
        return v.isShown() && v.getGlobalVisibleRect(r) && r.height() >= v.getHeight() * 0.9f && r.width() >= v.getWidth() * 0.9f;
    }

    static boolean overlapsVertically(View a, View b) {
        int[] la = new int[2], lb = new int[2];
        a.getLocationOnScreen(la);
        b.getLocationOnScreen(lb);
        return la[1] < lb[1] + b.getHeight() && lb[1] < la[1] + a.getHeight();
    }

    /** True if the D-pad focus is on the current page (not the sidebar). */
    boolean focusInPage() {
        View f = getCurrentFocus();
        return f != null && isIn(f, content);
    }

    /** A page rebuilt its views: if that dropped the focus out of the page, put it back on a visible control. */
    void keepFocusInPage(boolean had) {
        if (!had) return;
        content.post(() -> { if (!focusInPage() && current >= 0) enterPage(); });
    }

    /** Focus the page's first visible control, once it has been laid out. */
    void enterPage() {
        final View pv = views[current];
        if (pv.getWidth() > 0 && !pv.isLayoutRequested()) { focusFirstVisible(); return; }
        pv.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                pv.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                focusFirstVisible();
            }
        });
    }

    void focusFirstVisible() {
        ArrayList<View> fs = new ArrayList<>();
        content.addFocusables(fs, View.FOCUS_FORWARD);
        ArrayList<View> ok = new ArrayList<>();
        for (View v : fs) if (v.isShown() && v.isEnabled() && v.getWidth() > 0) ok.add(v);
        if (ok.isEmpty()) return;   // nothing focusable on this page: stay on the sidebar
        Rect cv = new Rect();
        content.getGlobalVisibleRect(cv);
        int[][] rects = new int[ok.size()][];
        int[] loc = new int[2];
        for (int i = 0; i < rects.length; i++) {
            View v = ok.get(i);
            v.getLocationOnScreen(loc);
            rects[i] = new int[] { loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight() };
        }
        View v = ok.get(FocusPick.first(rects, new int[] { cv.left, cv.top, cv.right, cv.bottom }));
        v.requestFocus();
        v.requestRectangleOnScreen(new Rect(0, 0, v.getWidth(), v.getHeight()), true);
    }

    /** A dialog opened (or the window lost focus): its keys aren't ours, so drop a pending BACK swallow. */
    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        backToSidebar = false;
    }

    int indexOf(View v) {
        for (int i = 0; i < nav.length; i++) if (nav[i] == v) return i;
        return current;
    }

    /**
     * User-initiated apply: runs onGo (may be null) then queues each non-null command as its own bridge
     * command. When another VPN is up, asks first (applying would disconnect it).
     */
    void run(final Runnable onGo, final String... cmds) {
        final Runnable go = () -> {
            vpnPaused = false;
            if (onGo != null) onGo.run();
            for (String c : cmds) if (c != null) Bridge.run(this, c, false);
        };
        if (Bridge.otherVpnActive(this)) {
            ThemedDialog.confirm(this, "VPN connected", VPN_WARNING, "Continue", go);
        } else {
            go.run();
        }
    }

    @Override
    public void onResult(String cmd, String output, boolean ok) {
        for (Page p : pages) if (p != null) p.onResult(cmd, output, ok);
        updateStatusLine();
    }

    void tick() {
        // READ_LOGS is granted (by the shell, during the first ensure) while we're running, but it only takes
        // effect in a fresh process -> restart ourselves once so the live displays work.
        if (!hadLogs && !Bridge.busy
                && checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) {
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            android.os.Process.killProcess(android.os.Process.myPid());
            return;
        }
        updateStatusLine();
        maybeShowTrouble();
        // a few seconds in, and only with no other dialog up: what's new after an update, or the update question
        if (!updateChecked && !Bridge.busy && hasWindowFocus() && System.currentTimeMillis() - createdAt > 5000) {
            updateChecked = true;
            WhatsNew.onStart(this);
        }
        // stickd's periodic check found a new version since: ask (once per version per run, see WhatsNew.claim)
        long ua = LogTail.statusAt("update");
        if (updateChecked && ua != updateStatusSeen && hasWindowFocus() && !Bridge.busy) {
            updateStatusSeen = ua;
            WhatsNew.offerFromStatus(this, com.clean.stickman.core.Update.parseStatus(LogTail.status("update")));
        }
        if (current >= 0) pages[current].onTick();
        h.postDelayed(this::tick, 400);
    }

    /**
     * Sidebar status line: one honest line about whether Stickman works right now. Bridge failures are said in
     * plain words (the raw reason stays on the Status page); the first connection explains the TV's own prompt.
     */
    void updateStatusLine() {
        String r = Bridge.lastResult;
        String s;
        int color = Theme.MUTED;
        if (Bridge.busy) s = Store.lastEnsure(this).isEmpty()
            ? "Connecting…\nIf the TV asks “Allow USB debugging?”, tick Always allow." : "Working…";
        else if (vpnPaused && Store.enabled(this)) { s = "Paused: another VPN is on"; color = Theme.ACCENT; }
        else if (r.startsWith("FAIL")) { s = Trouble.shortText(r); color = Theme.ACCENT; }
        else if (!Store.enabled(this)) s = "Stickman is OFF: remote works normally";
        else if (LogTail.remoteReleased()) { s = "Remote released (Back held 10 s): reopen Stickman to restart"; color = Theme.ACCENT; }
        else if (Store.pid(Store.lastEnsure(this), "REMAPD") != null) s = "Running ✓";
        else s = r.isEmpty() ? "Starting…" : "Not running · see Status";
        if (LogTail.lastKey != 0 && System.currentTimeMillis() - LogTail.lastKeyAt < 4000)
            s += "\nLast button: " + Keys.name(LogTail.lastKey);
        statusLine.setText(s);
        statusLine.setTextColor(color);
    }

    /**
     * A failure the user has to fix (ADB debugging off, "Allow USB debugging?" declined, VPN permission) gets one
     * dialog with the fix, so a fresh install never dead-ends on a cryptic status line. Once per activity, only for
     * a failure newer than this activity, and never on top of another dialog (no window focus).
     */
    void maybeShowTrouble() {
        if (troubleShown || Bridge.busy || isFinishing() || !hasWindowFocus()) return;
        String r = Bridge.lastResult;
        if (!r.startsWith("FAIL") || Bridge.lastResultAt < createdAt) return;
        if (!Trouble.askUser(r, Store.lastEnsure(this).isEmpty())) return;
        troubleShown = true;
        showTrouble(r);
    }

    void showTrouble(String r) {
        int k = Trouble.kind(r);
        ThemedDialog d = new ThemedDialog(this).title(Trouble.title(r)).message(Trouble.advice(r));
        if (k == Trouble.ADB_OFF) d.button("Developer options", this::openDeveloperOptions);
        if (k == Trouble.VPN) {
            d.button("Allow the VPN", () -> {
                Intent c = VpnService.prepare(this);
                if (c != null) startActivityForResult(c, 1); else ensureOnOpen();
            });
        } else {
            d.button("Try again", () -> { troubleShown = false; run(null, Ensure.MARKER); });
        }
        d.cancel("Close").focusButton(0).show();
    }

    /** Fire OS's Developer options screen (same action the Shortcuts page uses), else the stock one, else directions. */
    void openDeveloperOptions() {
        for (String action : new String[] { "com.amazon.device.settings.action.APPLICATION_DEVELOPMENT_SETTINGS",
                android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS }) {
            try {
                startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                retryOnResume = true;
                return;
            } catch (RuntimeException ignored) {   // ActivityNotFound / SecurityException: try the next one
            }
        }
        android.widget.Toast.makeText(this, "Open Settings → My Fire TV → Developer options, then come back.",
            android.widget.Toast.LENGTH_LONG).show();
        retryOnResume = true;
    }
}
