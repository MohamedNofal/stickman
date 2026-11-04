package com.clean.stickman;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.clean.stickman.core.Keys;

/** App shell: left sidebar of sections + a content area that swaps Page views. Fully D-pad driven. */
public class MainActivity extends Activity implements Bridge.Listener {
    static final String[] SECTIONS = { "Remote", "Speed", "Sleep", "Privacy", "Debloat", "Shortcuts", "Status", "Settings" };
    static final String VPN_WARNING = "A VPN (e.g. NordVPN) is connected. Applying will disconnect it. Continue?";

    final Page[] pages = new Page[SECTIONS.length];
    final View[] views = new View[SECTIONS.length];
    final Button[] nav = new Button[SECTIONS.length];
    final Handler h = new Handler();
    LinearLayout sidebar;
    FrameLayout content;
    TextView statusLine;
    int current = -1;
    LogTail tail;
    boolean hadLogs;
    /** The open-time ensure was skipped because another VPN is up. */
    boolean vpnPaused;

    Page makePage(int i) {
        switch (SECTIONS[i]) {
            case "Remote": return new RemotePage();
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
        FadeOverlay.stop();   // clear a sleep-fade overlay left behind
        hadLogs = checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED;
        BootJob.schedule(this);

        LinearLayout root = Theme.row(this);
        root.setBackgroundColor(Theme.BG);
        root.setGravity(0);

        sidebar = Theme.column(this);
        sidebar.setBackgroundColor(Theme.PANEL);
        int p = Theme.dp(this, 20);
        sidebar.setPadding(p, Theme.dp(this, 24), Theme.dp(this, 12), p);
        TextView brand = Theme.text(this, "Stickman", 26, Theme.ACCENT);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setPadding(Theme.dp(this, 4), 0, 0, Theme.dp(this, 16));
        sidebar.addView(brand);
        for (int i = 0; i < SECTIONS.length; i++) {
            final int idx = i;
            Button bt = Theme.button(this, SECTIONS[i]);
            bt.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            ((LinearLayout.LayoutParams) bt.getLayoutParams()).setMargins(0, Theme.dp(this, 3), 0, Theme.dp(this, 3));
            bt.setOnClickListener(v -> select(idx));
            nav[i] = bt;
            sidebar.addView(bt);
        }
        View spacer = new View(this);
        sidebar.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));
        statusLine = Theme.text(this, "", 13, Theme.MUTED);
        sidebar.addView(statusLine);
        root.addView(sidebar, new LinearLayout.LayoutParams(Theme.dp(this, 220), -1));

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
        if (req == 1 && res == RESULT_OK) ensureOnOpen();
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
        h.post(this::tick);
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

    /** Right from the sidebar enters the page; Left at the page's left edge returns to the sidebar. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (current >= 0 && pages[current].onKey(e)) return true;
        if (e.getAction() == KeyEvent.ACTION_DOWN && current >= 0) {
            View f = getCurrentFocus();
            int k = e.getKeyCode();
            // the drawn remote moves its own focus first; only its left edge falls through to the sidebar rule
            if (f instanceof RemoteView && k == KeyEvent.KEYCODE_DPAD_LEFT
                && ((RemoteView) f).canMove(com.clean.stickman.core.RemoteLayout.LEFT)) return super.dispatchKeyEvent(e);
            if (f != null && k == KeyEvent.KEYCODE_DPAD_RIGHT && isIn(f, sidebar)) {
                if (f != nav[current]) select(indexOf(f));
                if (content.requestFocus(View.FOCUS_RIGHT)) return true;
                return true;   // nothing focusable on this page: stay put
            }
            if (f != null && k == KeyEvent.KEYCODE_DPAD_LEFT && isIn(f, content)) {
                View next = FocusFinder.getInstance().findNextFocus(content, f, View.FOCUS_LEFT);
                if (next == null) { nav[current].requestFocus(); return true; }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    int indexOf(View v) {
        for (int i = 0; i < nav.length; i++) if (nav[i] == v) return i;
        return current;
    }

    AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
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
            dialog().setTitle("VPN connected").setMessage(VPN_WARNING)
                .setPositiveButton("Continue", (d, w) -> go.run())
                .setNegativeButton("Cancel", null).show();
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
        if (current >= 0) pages[current].onTick();
        h.postDelayed(this::tick, 400);
    }

    void updateStatusLine() {
        String r = Bridge.lastResult;
        String s;
        int color = Theme.MUTED;
        if (Bridge.busy) s = "Working…";
        else if (vpnPaused && Store.enabled(this)) { s = "VPN active — Stickman paused"; color = Theme.ACCENT; }
        else if (Bridge.VPN_BUSY.equals(r)) { s = "Another VPN is on: not applied"; color = Theme.ACCENT; }
        else if (r.startsWith("FAIL")) { s = "Problem: " + r.substring(4).trim(); color = Theme.ACCENT; }
        else if (!Store.enabled(this)) s = "Stickman is OFF";
        else if (Store.pid(Store.lastEnsure(this), "REMAPD") != null) s = "Running ✓";
        else s = r.isEmpty() ? "Starting…" : "Not running";
        if (LogTail.lastKey != 0 && System.currentTimeMillis() - LogTail.lastKeyAt < 4000)
            s += "\nLast button: " + Keys.name(LogTail.lastKey);
        statusLine.setText(s);
        statusLine.setTextColor(color);
    }
}
