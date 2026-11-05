package com.clean.stickman;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

/**
 * Base for the settings-style pages: a title, a result line, and send() = one bridge command per user action
 * whose result is shown as a toast + the result line (via {@link #describe}).
 */
public abstract class ApplyPage extends Page {
    MainActivity a;
    TextView result;
    /** queued command -> what it was (passed back to describe) */
    final Map<String, String> mine = new HashMap<>();

    /** Title + result line; subclasses add cards to the returned column. */
    LinearLayout start(MainActivity act) {
        a = act;
        LinearLayout l = Theme.column(a);
        l.addView(Theme.title(a, title()));
        result = Theme.autoHide(Theme.text(a, "", 15, Theme.ACCENT));
        result.setPadding(0, 0, 0, Theme.dp(a, 8));
        l.addView(result);
        return l;
    }

    /** Queues cmd (null = refused by the builder: nothing is sent). */
    void send(String cmd, String kind, String pending) {
        if (cmd == null) { say("Not allowed."); return; }
        mine.put(cmd, kind);
        a.run(() -> {
            result.setText(pending);
            pendingSince = System.currentTimeMillis();
            h.removeCallbacks(timeout);
            h.postDelayed(timeout, PENDING_TIMEOUT_MS);
        }, cmd);
    }

    static final long PENDING_TIMEOUT_MS = 30000;
    /** Own handler: MainActivity clears its handler on pause. */
    final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    /** When the newest command was sent (0 = nothing pending). */
    long pendingSince;

    /** Never stay "pending" forever: after 30 s with no answer show the bridge's last result. */
    final Runnable timeout = new Runnable() {
        public void run() {
            if (pendingSince == 0 || mine.isEmpty()) return;
            if (Bridge.busy && System.currentTimeMillis() - pendingSince < 3 * PENDING_TIMEOUT_MS) {
                h.postDelayed(this, 5000);   // still connecting/running: give it a little longer
                return;
            }
            pendingSince = 0;
            mine.clear();
            String r = Bridge.lastResult;
            say(r.startsWith("FAIL") ? "Problem: " + r.substring(4).trim() : r.isEmpty() ? "No response" : "No response (last: " + r + ")");
        }
    };

    /** Applies the edited stickd.conf: saved locally, then the ensure rewrites it on the stick and spools reload. */
    void applyStickdConf(String text, String kind) {
        Store.saveStickdText(a, text);
        if (!Store.enabled(a)) { say("Saved. It applies when Stickman is turned on."); return; }
        send(Ensure.MARKER, kind, "Saving…");
    }

    /** True if stickd is meant to be running (actions go through it); otherwise says why not. */
    boolean stickdOn() {
        if (Store.enabled(a)) return true;
        say("Stickman is OFF: turn it on in Settings first.");
        return false;
    }

    void say(String s) {
        result.setText(s);
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }

    /** Message for a finished command of this page, or null to show nothing. */
    abstract String describe(String kind, String output);

    @Override public void onResult(String cmd, String output, boolean ok) {
        if (a == null) return;
        String kind = mine.remove(cmd);
        if (kind == null) return;
        if (mine.isEmpty()) pendingSince = 0;
        if (!ok) { say("Failed: " + output); return; }
        String s = describe(kind, output);
        if (s != null) say(s);
    }

    // ---- small view helpers ----

    /** A row of choice buttons; returns them (index-aligned with labels). */
    Button[] choices(LinearLayout parent, String[] labels, Choice cb) {
        LinearLayout r = Theme.row(a);
        r.setTag(Theme.CHIPS);
        Button[] bs = new Button[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            bs[i] = Theme.button(a, labels[i]);
            bs[i].setOnClickListener(v -> cb.chose(idx));
            r.addView(bs[i]);
        }
        parent.addView(r);
        return bs;
    }

    interface Choice { void chose(int i); }

    static void select(Button[] bs, int i) {
        for (int k = 0; k < bs.length; k++) bs[k].setSelected(k == i);
    }

    LinearLayout card(LinearLayout parent, String heading, String note) {
        LinearLayout c = Theme.card(a);
        c.addView(Theme.heading(a, heading));
        if (note != null) c.addView(Theme.text(a, note, 14, Theme.MUTED));
        parent.addView(c);
        return c;
    }

    Button button(LinearLayout parent, String label, View.OnClickListener l) {
        Button b = Theme.button(a, label);
        b.setOnClickListener(l);
        parent.addView(b);
        return b;
    }

    int pref(String key, int def) { return Store.prefs(a).getInt(key, def); }

    void putPref(String key, int v) { Store.prefs(a).edit().putInt(key, v).apply(); }
}
