package com.clean.stickman;

import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.clean.stickman.core.Keys;
import com.clean.stickman.core.RemapModel;
import com.clean.stickman.core.RemoteLayout;
import com.clean.stickman.core.Rule;

import java.util.List;

/**
 * The remote screen: a drawing of the owner's remote (D-pad focus, OK edits a button), a profile switcher
 * (Default + per-app profiles) and Listen mode (press a physical button -> it lights up and its editor opens).
 */
public class RemotePage extends Page implements RemoteView.Listener, RemoteView.Info {
    static final long LISTEN_TIMEOUT_MS = 20000;
    /** Ignore the KEY line of the OK press that switched listening on. */
    static final long LISTEN_GRACE_MS = 400;

    MainActivity a;
    RemoteView remote;
    RemapModel m = new RemapModel();
    String profile = RemapModel.DEFAULT;
    TextView profName, profInfo, btnName, btnLayers, listenInfo;
    Button prev, next, remove, listen;
    final Handler h = new Handler();
    boolean listening;
    long listenFrom, listenBaseAt;

    @Override public String title() { return "Remote"; }

    @Override public View build(MainActivity act) {
        a = act;
        LinearLayout root = Theme.row(a);
        root.setGravity(0);

        remote = new RemoteView(a);
        remote.setListener(this);
        remote.setInfo(this);
        root.addView(remote, new LinearLayout.LayoutParams(0, -1, 1f));

        LinearLayout side = Theme.column(a);
        side.addView(Theme.title(a, "Remote"));
        TextView intro = Theme.text(a, "Move around the remote with the D-pad and press OK to change what a button does. "
            + "Orange dots mark buttons you've changed.", 14, Theme.MUTED);
        intro.setPadding(0, 0, 0, Theme.dp(a, 12));
        side.addView(intro);

        LinearLayout pc = Theme.card(a);
        pc.addView(Theme.heading(a, "Profile"));
        LinearLayout pr = Theme.row(a);
        prev = Theme.button(a, "◀");
        prev.setOnClickListener(v -> stepProfile(-1));
        pr.addView(prev);
        profName = Theme.text(a, "", 18, Theme.TEXT);
        profName.setTypeface(Typeface.DEFAULT_BOLD);
        profName.setSingleLine(true);
        profName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        profName.setGravity(android.view.Gravity.CENTER);
        pr.addView(profName, new LinearLayout.LayoutParams(0, -2, 1f));
        next = Theme.button(a, "▶");
        next.setOnClickListener(v -> stepProfile(1));
        ((LinearLayout.LayoutParams) next.getLayoutParams()).setMargins(Theme.dp(a, 8), Theme.dp(a, 4), 0, Theme.dp(a, 4));
        pr.addView(next);
        pc.addView(pr);
        profInfo = Theme.text(a, "", 13, Theme.MUTED);
        pc.addView(profInfo);
        LinearLayout pr2 = Theme.row(a);
        Button add = Theme.button(a, "Add app profile");
        add.setOnClickListener(v -> addProfile());
        pr2.addView(add);
        remove = Theme.button(a, "Remove profile");
        remove.setOnClickListener(v -> removeProfile());
        pr2.addView(remove);
        pc.addView(pr2);
        side.addView(pc);

        LinearLayout bc = Theme.card(a);
        bc.addView(Theme.heading(a, "Selected button"));
        btnName = Theme.text(a, "", 20, Theme.TEXT);
        btnName.setTypeface(Typeface.DEFAULT_BOLD);
        bc.addView(btnName);
        btnLayers = Theme.text(a, "", 15, Theme.TEXT);
        btnLayers.setLineSpacing(Theme.dp(a, 3), 1f);
        bc.addView(btnLayers);
        side.addView(bc);

        LinearLayout lc = Theme.card(a);
        lc.addView(Theme.heading(a, "Listen"));
        listen = Theme.button(a, "Listen for a button");
        listen.setOnClickListener(v -> { if (!listening) startListening(); });
        lc.addView(listen);
        listenInfo = Theme.text(a, "Press Listen, then press any button on your remote to jump straight to it.", 13, Theme.MUTED);
        lc.addView(listenInfo);
        side.addView(lc);

        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -1, 1.15f);
        sp.setMargins(Theme.dp(a, 16), 0, 0, 0);
        root.addView(Theme.scroll(a, side), sp);

        reload();
        return root;
    }

    // ---- model / profile ----

    void reload() {
        m = Store.hasRemap(a) ? Store.remap(a) : new RemapModel();
        if (!m.profiles().contains(profile)) profile = RemapModel.DEFAULT;
        refresh();
    }

    void refresh() {
        boolean def = RemapModel.DEFAULT.equals(profile);
        List<String> ps = m.profiles();
        profName.setText(def ? "Default" : ButtonEditor.appLabel(a, profile));
        profInfo.setText((def ? "Every app (unless it has its own profile)" : "Only while this app is open · " + profile)
            + "\nProfile " + (ps.indexOf(profile) + 1) + " of " + ps.size());
        boolean many = ps.size() > 1;
        prev.setEnabled(many);
        next.setEnabled(many);
        remove.setVisibility(def ? View.GONE : View.VISIBLE);
        onFocusCode(remote.focused());
        remote.invalidate();
    }

    void stepProfile(int d) {
        List<String> ps = m.profiles();
        int i = ps.indexOf(profile);
        profile = ps.get(((i < 0 ? 0 : i) + d + ps.size()) % ps.size());
        refresh();
    }

    boolean ready() {
        if (Store.hasRemap(a)) return true;
        ButtonEditor.message(a, "Not connected yet", "Stickman hasn't connected yet: open it again once it shows Running.");
        return false;
    }

    void addProfile() {
        if (!ready()) return;
        ButtonEditor.pickApp(a, "Profile for which app?", app -> {
            if (!ButtonEditor.validPkg(app[1])) return;
            m = Store.remap(a);
            m.addProfile(app[1]);
            Store.saveRemap(a, m);   // an empty profile changes nothing on the stick, so no apply needed yet
            profile = app[1];
            refresh();
        });
    }

    void removeProfile() {
        if (RemapModel.DEFAULT.equals(profile) || !ready()) return;
        final String p = profile;
        a.dialog().setTitle("Remove profile?")
            .setMessage("Remove the " + ButtonEditor.appLabel(a, p) + " profile? That app goes back to the Default buttons.")
            .setPositiveButton("Remove", (d, w) -> {
                m = Store.remap(a);
                m.removeProfile(p);
                Store.saveRemap(a, m);
                profile = RemapModel.DEFAULT;
                refresh();
                if (Store.enabled(a)) a.run(null, Ensure.MARKER);
            })
            .setNegativeButton("Cancel", null).show();
    }

    /** Profile whose rules apply to code in the selected profile (app profiles fall back to Default per key). */
    String effective(int code) {
        return RemapModel.DEFAULT.equals(profile) || m.hasRules(profile, code) ? profile : RemapModel.DEFAULT;
    }

    // ---- RemoteView.Info ----

    @Override public boolean hasRules(int code) { return m.hasRules(profile, code); }

    @Override public String hint(int code) {
        String p = effective(code);
        String tap = m.get(p, code, Rule.Layer.TAP);
        return tap == null ? "Original" : ButtonEditor.describe(a, tap);
    }

    // ---- RemoteView.Listener ----

    @Override public void onFocusCode(int code) {
        if (btnName == null) return;
        String p = effective(code);
        boolean inherited = !p.equals(profile);
        btnName.setText(Keys.name(code));
        StringBuilder sb = new StringBuilder();
        String tap = m.get(p, code, Rule.Layer.TAP), dbl = m.get(p, code, Rule.Layer.DOUBLE), hold = m.get(p, code, Rule.Layer.HOLD);
        sb.append("Press:  ").append(tap == null ? "Original" : ButtonEditor.describe(a, tap));
        sb.append("\nDouble press:  ").append(dbl == null ? "Off" : ButtonEditor.describe(a, dbl));
        sb.append("\nHold:  ").append(Keys.tapOnly(code) ? "— (app buttons can't be held)" : hold == null ? "Original" : ButtonEditor.describe(a, hold));
        if (inherited && m.hasRules(p, code)) sb.append("\n(from the Default profile)");
        btnLayers.setText(sb);
    }

    @Override public void onPick(int code) {
        if (listening) stopListening(null);
        if (!ready()) return;
        m = Store.remap(a);
        ButtonEditor.open(a, m, profile, code, this::refresh);
    }

    // ---- listen mode ----

    boolean remapperRunning() {
        return Store.enabled(a) && Store.pid(Store.lastEnsure(a), "REMAPD") != null;
    }

    void startListening() {
        if (!remapperRunning()) {
            listenInfo.setText("Remapper not running. Turn Stickman on in Settings and wait for “Running ✓”.");
            listenInfo.setTextColor(Theme.ACCENT);
            return;
        }
        if (a.checkSelfPermission(android.Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            listenInfo.setText("Stickman can't see button presses yet. Reopen it once it shows “Running ✓”.");
            listenInfo.setTextColor(Theme.ACCENT);
            return;
        }
        listening = true;
        listenFrom = System.currentTimeMillis();
        listenBaseAt = LogTail.lastKeyAt;
        listen.setText("Listening…");
        listen.setSelected(true);
        listenInfo.setText("Press any button on your remote (gives up after " + LISTEN_TIMEOUT_MS / 1000 + " s). "
            + "Buttons that leave this screen, like Home or the app buttons, still do their job.");
        listenInfo.setTextColor(Theme.TEXT);
        remote.setListening(true);
        h.post(this::poll);
    }

    void poll() {
        if (!listening) return;
        long now = System.currentTimeMillis(), at = LogTail.lastKeyAt;
        int k = LogTail.lastKey;
        if (at > listenBaseAt && at - listenFrom >= LISTEN_GRACE_MS && RemoteLayout.find(k) != null) {
            stopListening("Heard " + Keys.name(k) + ".");
            remote.flash(k);
            final int code = k;
            h.postDelayed(() -> { if (a.hasWindowFocus()) onPick(code); }, 650);
            return;
        }
        if (at > listenBaseAt) listenBaseAt = at;   // grace-period or unknown key: keep listening
        if (now - listenFrom > LISTEN_TIMEOUT_MS) { stopListening("Didn't hear a button. Try again?"); return; }
        h.postDelayed(this::poll, 60);
    }

    void stopListening(String msg) {
        listening = false;
        h.removeCallbacksAndMessages(null);
        if (listen == null) return;
        listen.setText("Listen for a button");
        listen.setSelected(false);
        remote.setListening(false);
        listenInfo.setText(msg != null ? msg : "Press Listen, then press any button on your remote to jump straight to it.");
        listenInfo.setTextColor(Theme.MUTED);
    }

    /** While listening the physical press is read from remapd's log; keep it from also moving focus here. */
    @Override public boolean onKey(KeyEvent e) { return listening; }

    @Override public void onShow() {
        if (a != null) reload();
    }

    @Override public void onHide() {
        if (listening) stopListening(null);
        h.removeCallbacksAndMessages(null);
    }
}
