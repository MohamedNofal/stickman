package com.clean.stickman;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.clean.stickman.core.Commands;
import com.clean.stickman.core.EnsureScript;
import com.clean.stickman.core.Originals;
import com.clean.stickman.core.RemapModel;
import com.clean.stickman.core.ResultText;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Master switch, restore defaults, remote timings, fixes (clock, stuck proxy) and the last bridge result. */
public class SettingsPage extends Page {
    static final int[] DOUBLE_MS = { 200, 250, 300, 400 };
    static final int[] HOLD_MS = { 500, 600, 800 };

    MainActivity a;
    Button master;
    TextView masterInfo, result, last;
    final Button[] doubleBtns = new Button[DOUBLE_MS.length], holdBtns = new Button[HOLD_MS.length];
    /** command -> how to describe its result */
    final Map<String, String> mine = new HashMap<>();
    String lastShown;

    @Override public String title() { return "Settings"; }

    @Override public View build(MainActivity act) {
        a = act;
        LinearLayout l = Theme.column(a);
        l.addView(Theme.title(a, "Settings"));

        LinearLayout c1 = Theme.card(a);
        c1.addView(Theme.heading(a, "Stickman"));
        masterInfo = Theme.text(a, "", 14, Theme.MUTED);
        c1.addView(masterInfo);
        master = Theme.button(a, "");
        master.setOnClickListener(v -> toggleMaster());
        c1.addView(master);
        l.addView(c1);

        LinearLayout c2 = Theme.card(a);
        c2.addView(Theme.heading(a, "Remote timing"));
        c2.addView(Theme.text(a, "Double-press window (keys with a double-press layer wait this long for a second press)", 14, Theme.MUTED));
        LinearLayout r1 = Theme.row(a);
        r1.setTag(Theme.CHIPS);
        for (int i = 0; i < DOUBLE_MS.length; i++) {
            final int v = DOUBLE_MS[i];
            doubleBtns[i] = Theme.button(a, v + " ms");
            doubleBtns[i].setOnClickListener(b -> setTiming(v, -1));
            r1.addView(doubleBtns[i]);
        }
        c2.addView(r1);
        c2.addView(Theme.text(a, "Hold time", 14, Theme.MUTED));
        LinearLayout r2 = Theme.row(a);
        r2.setTag(Theme.CHIPS);
        for (int i = 0; i < HOLD_MS.length; i++) {
            final int v = HOLD_MS[i];
            holdBtns[i] = Theme.button(a, v + " ms");
            holdBtns[i].setOnClickListener(b -> setTiming(-1, v));
            r2.addView(holdBtns[i]);
        }
        c2.addView(r2);
        l.addView(c2);

        LinearLayout c3 = Theme.card(a);
        c3.addView(Theme.heading(a, "Fixes"));
        LinearLayout r3 = Theme.row(a);
        Button clock = Theme.button(a, "Fix clock");
        clock.setOnClickListener(v -> fixClock());
        r3.addView(clock);
        Button proxy = Theme.button(a, "Clear stuck proxy");
        proxy.setOnClickListener(v -> send(Commands.clearDeadProxy(), "proxy", "Checking proxy…"));
        r3.addView(proxy);
        c3.addView(r3);
        result = Theme.autoHide(Theme.text(a, "", 15, Theme.TEXT));
        c3.addView(result);
        l.addView(c3);

        LinearLayout c4 = Theme.card(a);
        c4.addView(Theme.heading(a, "Restore defaults"));
        c4.addView(Theme.text(a, "Puts back every system change Stickman made: DNS, animations, sleep and screensaver values, "
            + "standby buckets and disabled apps. Your button remaps stay.", 14, Theme.MUTED));
        Button restore = Theme.button(a, "Restore defaults…");
        restore.setOnClickListener(v -> ThemedDialog.confirm(a, "Restore defaults?",
            "Put back every system setting Stickman changed?", "Restore",
            () -> send(Originals.restoreScript(), "restore", "Restoring…")));
        c4.addView(restore);
        l.addView(c4);

        LinearLayout c5 = Theme.card(a);
        c5.addView(Theme.heading(a, "Last change"));
        last = Theme.text(a, "", 14, Theme.TEXT);
        c5.addView(last);
        l.addView(c5);

        refresh();
        return Theme.scroll(a, l);
    }

    void refresh() {
        boolean on = Store.enabled(a);
        master.setText(on ? "Turn Stickman OFF" : "Turn Stickman ON");
        masterInfo.setText(on
            ? "ON: Stickman remaps your remote's buttons and runs its background automation. OFF gives the remote back instantly and stops both."
            : "OFF: the remote works normally and nothing runs in the background.");
        RemapModel m = Store.hasRemap(a) ? Store.remap(a) : null;
        int dbl = m == null ? 250 : m.doubleMs, hold = m == null ? 600 : m.holdMs;
        for (int i = 0; i < DOUBLE_MS.length; i++) doubleBtns[i].setSelected(DOUBLE_MS[i] == dbl);
        for (int i = 0; i < HOLD_MS.length; i++) holdBtns[i].setSelected(HOLD_MS[i] == hold);
    }

    void send(String cmd, String kind, String pending) {
        if (cmd == null) return;
        mine.put(cmd, kind);
        a.run(() -> result.setText(pending), cmd);
    }

    void toggleMaster() {
        if (Store.enabled(a)) {
            mine.put(EnsureScript.stop(), "stop");
            a.run(() -> { Store.setEnabled(a, false); refresh(); }, EnsureScript.stop());
        } else {
            a.run(() -> { Store.setEnabled(a, true); refresh(); }, Ensure.MARKER);
        }
    }

    void setTiming(int dbl, int hold) {
        if (!Store.hasRemap(a)) {
            result.setText("Stickman hasn't connected yet: open it again once it shows Running.");
            return;
        }
        RemapModel m = Store.remap(a);
        if (dbl > 0) m.doubleMs = dbl;
        if (hold > 0) m.holdMs = hold;
        Store.saveRemap(a, m);
        refresh();
        if (Store.enabled(a)) a.run(null, Ensure.MARKER);
    }

    void fixClock() {
        result.setText("Fetching the time from google.com…");
        new Thread(() -> {
            final long t = fetchTime();
            a.runOnUiThread(() -> {
                if (t <= 0) { result.setText("Couldn't reach time server"); return; }
                send(Commands.setTime(t), "clock", "Setting clock to "
                    + new SimpleDateFormat("d MMM yyyy HH:mm", Locale.UK).format(new Date(t)) + "…");
            });
        }, "fixclock").start();
    }

    /** Date header of an https HEAD request (https only: a plain-http Date could be spoofed). */
    static long fetchTime() {
        for (String u : new String[] { "https://www.google.com" }) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setRequestMethod("HEAD");
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.connect();
                long t0 = System.nanoTime();
                long d = c.getHeaderFieldDate("Date", 0);
                if (d > 0) return d + (System.nanoTime() - t0) / 1000000;
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return -1;
    }

    @Override public void onShow() { if (a != null) refresh(); }

    @Override public void onTick() {
        if (a == null) return;
        String s = Bridge.busy ? "Working…" : Bridge.lastResult.isEmpty() ? ResultText.human("")
            : ResultText.human(Bridge.lastResult) + "  ·  " + new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(Bridge.lastResultAt));
        if (!s.equals(lastShown)) { lastShown = s; last.setText(s); }
    }

    @Override public void onResult(String cmd, String output, boolean ok) {
        if (a == null) return;
        String kind = mine.remove(cmd);
        if (kind == null) return;
        if (!ok) { result.setText("Failed: " + output); return; }
        switch (kind) {
            case "proxy":
                if (output.contains("PROXY cleared")) result.setText("Stuck proxy cleared.");
                else if (output.contains("PROXY kept")) result.setText("The proxy is in use (e.g. Net Filter), so it was left alone.");
                else if (output.contains("PROXY none")) result.setText("No proxy is set.");
                else result.setText(output);
                break;
            case "clock": result.setText("Clock set. " + output); break;
            case "restore": result.setText("Defaults restored."); break;
            case "stop": result.setText("Stopped. The remote works normally."); break;
            default: result.setText(output);
        }
    }
}
