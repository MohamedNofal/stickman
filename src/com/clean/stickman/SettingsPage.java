package com.clean.stickman;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.clean.stickman.core.Commands;
import com.clean.stickman.core.EnsureScript;
import com.clean.stickman.core.Originals;
import com.clean.stickman.core.Ota;
import com.clean.stickman.core.RemapModel;
import com.clean.stickman.core.ResultText;
import com.clean.stickman.core.Trouble;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Master switch, restore defaults / undo everything, remote timings, fixes (clock, stuck proxy) and the last result. */
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

    @Override public String subtitle() { return "Turn Stickman off, undo what it changed, remote timing, fixes."; }

    @Override public View build(MainActivity act) {
        a = act;
        LinearLayout l = Theme.column(a);
        l.addView(Theme.title(a, title()));
        l.addView(Theme.subtitle(a, subtitle()));
        // one place for every outcome on this page (plus a toast, so it's seen wherever the focus is)
        result = Theme.autoHide(Theme.text(a, "", 15, Theme.ACCENT));
        result.setPadding(0, 0, 0, Theme.dp(a, 8));
        l.addView(result);

        LinearLayout c1 = Theme.card(a);
        c1.addView(Theme.heading(a, "Stickman"));
        masterInfo = Theme.text(a, "", 14, Theme.MUTED);
        c1.addView(masterInfo);
        master = Theme.button(a, "");
        master.setOnClickListener(v -> toggleMaster());
        c1.addView(master);
        l.addView(c1);

        LinearLayout c4 = Theme.card(a);
        c4.addView(Theme.heading(a, "Undo"));
        c4.addView(Theme.text(a, "Restore defaults puts back every setting Stickman changed on the stick (DNS, animations, sleep "
            + "and screensaver, TV control, memory and background limits, Fire OS updates) and turns the apps it disabled or "
            + "kept asleep back on. Stickman stays on and your button mappings are kept.\n"
            + "Undo everything does the same and turns Stickman off: the stick is back the way it was.", 14, Theme.MUTED));
        LinearLayout r4 = Theme.row(a);
        Button restore = Theme.button(a, "Restore defaults…");
        restore.setOnClickListener(v -> ThemedDialog.confirm(a, "Restore defaults?",
            "Put back every setting Stickman changed on the stick and turn the apps it disabled or kept asleep back on?\n\n"
            + "Stickman stays on and your button mappings are kept.", "Restore", this::restoreDefaults));
        r4.addView(restore);
        Button undo = Theme.button(a, "Undo everything…");
        undo.setOnClickListener(v -> ThemedDialog.confirm(a, "Undo everything?",
            "Stickman turns off: your remote works normally again, the apps Stickman disabled or kept asleep are turned "
            + "back on, and the stick's settings go back to Amazon's values (Fire OS updates included).\n\n"
            + "Your button mappings are kept for when you turn Stickman on again.", "Undo everything", this::undoEverything));
        r4.addView(undo);
        c4.addView(r4);
        l.addView(c4);

        LinearLayout c2 = Theme.card(a);
        c2.addView(Theme.heading(a, "Remote timing"));
        c2.addView(Theme.text(a, "Double press: how long a button waits for a second press (only buttons with a double-press action).", 14, Theme.MUTED));
        LinearLayout r1 = Theme.row(a);
        r1.setTag(Theme.CHIPS);
        for (int i = 0; i < DOUBLE_MS.length; i++) {
            final int v = DOUBLE_MS[i];
            doubleBtns[i] = Theme.button(a, v + " ms");
            doubleBtns[i].setOnClickListener(b -> setTiming(v, -1));
            r1.addView(doubleBtns[i]);
        }
        c2.addView(r1);
        c2.addView(Theme.text(a, "Hold: how long you keep a button pressed before its hold action fires.", 14, Theme.MUTED));
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
        c3.addView(Theme.text(a, "Fix clock: when the stick's date or time is wrong (apps may refuse to sign in or play). "
            + "Clear stuck proxy: when every app says there's no internet after a proxy app such as Net Filter was removed.", 14, Theme.MUTED));
        LinearLayout r3 = Theme.row(a);
        Button clock = Theme.button(a, "Fix clock");
        clock.setOnClickListener(v -> fixClock());
        r3.addView(clock);
        Button proxy = Theme.button(a, "Clear stuck proxy");
        proxy.setOnClickListener(v -> send(Commands.clearDeadProxy(), "proxy", "Checking the proxy…"));
        r3.addView(proxy);
        c3.addView(r3);
        l.addView(c3);

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
            ? "ON: your remote buttons are remapped and the background automation runs. OFF gives the remote back instantly and stops both."
            : "OFF: the remote works normally and nothing runs in the background.");
        RemapModel m = Store.hasRemap(a) ? Store.remap(a) : null;
        int dbl = m == null ? 250 : m.doubleMs, hold = m == null ? 600 : m.holdMs;
        for (int i = 0; i < DOUBLE_MS.length; i++) doubleBtns[i].setSelected(DOUBLE_MS[i] == dbl);
        for (int i = 0; i < HOLD_MS.length; i++) holdBtns[i].setSelected(HOLD_MS[i] == hold);
    }

    void say(String s) {
        result.setText(s);
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }

    void send(String cmd, String kind, String pending) {
        if (cmd == null) return;
        mine.put(cmd, kind);
        a.run(() -> result.setText(pending), cmd);
    }

    void toggleMaster() {
        if (Store.enabled(a)) {
            // OFF stops remapping and automation: ask first (Cancel focused)
            ThemedDialog.confirm(a, "Turn Stickman off?", "Your remote goes back to normal and the background automation "
                + "stops until you turn Stickman on again.\n\nWhat Stickman changed on the stick (disabled apps, DNS, …) "
                + "stays as it is: Undo everything puts that back too.", "Turn off", () -> {
                mine.put(EnsureScript.stop(), "stop");
                a.run(() -> { Store.setEnabled(a, false); refresh(); }, EnsureScript.stop());
            });
        } else {
            a.run(() -> { Store.setEnabled(a, true); refresh(); }, Ensure.MARKER);
        }
    }

    /** Replays the originals journal (and thaws OTA); Stickman stays on. */
    void restoreDefaults() {
        Store.setOtaFrozen(a, false);
        send(Ota.thawScript() + "; " + Originals.restoreScript(), "restore", "Restoring…");
    }

    /** Master OFF followed by Restore defaults, in one go: exactly the two commands those buttons send. */
    void undoEverything() {
        final String stop = EnsureScript.stop();
        final String restore = Ota.thawScript() + "; " + Originals.restoreScript();
        mine.put(stop, "undo-stop");
        mine.put(restore, "undo");
        a.run(() -> {
            Store.setOtaFrozen(a, false);
            Store.setEnabled(a, false);
            refresh();
            result.setText("Turning Stickman off and putting the stick back…");
        }, stop, restore);
    }

    void setTiming(int dbl, int hold) {
        if (!Store.hasRemap(a)) {
            say("Stickman hasn't connected to the stick yet: wait for “Running ✓” in the sidebar, then try again.");
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
                if (t <= 0) { say("Couldn't reach the time server. Is the stick online?"); return; }
                send(Commands.setTime(t), "clock", "Setting the clock to "
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
        if (!ok) { say(Trouble.shortText(output)); return; }
        switch (kind) {
            case "proxy":
                if (output.contains("PROXY cleared")) say("Stuck proxy cleared.");
                else if (output.contains("PROXY kept")) say("The proxy is in use (e.g. Net Filter), so it was left alone.");
                else if (output.contains("PROXY none")) say("No proxy is set.");
                else say(output);
                break;
            case "clock": say("Clock set. " + output); break;
            case "restore": say("Defaults restored: the stick's settings and apps are back to Amazon's. Stickman is still on."); break;
            case "stop": say("Stopped. The remote works normally."); break;
            case "undo-stop": break;   // the "undo" result reports the whole thing
            case "undo": say("Everything is undone: Stickman is off and the stick is back the way it was."); break;
            default: say(output);
        }
    }
}
