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
import com.clean.stickman.core.ListenDecision;
import com.clean.stickman.core.RemapModel;
import com.clean.stickman.core.RemoteLayout;
import com.clean.stickman.core.RemotePresets;
import com.clean.stickman.core.Rule;

import java.util.List;

/**
 * The remote screen: a drawing of the chosen remote model (D-pad focus, OK edits a button), a profile switcher
 * (Default + per-app profiles), Listen mode (press a physical button -> it lights up and its editor opens;
 * a key not on the drawing can be assigned to a not-yet-learned button) and the Remote model chooser.
 */
public class RemotePage extends Page implements RemoteView.Listener, RemoteView.Info {
    static final long LISTEN_TIMEOUT_MS = 20000;
    /** Ignore the KEY line of the OK press that switched listening on. */
    static final long LISTEN_GRACE_MS = 400;
    /** Linux code of Back: cancels listening instead of being "heard". */
    static final int BACK = 158;
    static final String CANCELLED = "Listening cancelled.";
    static final String IDLE = "Press Listen, then press any button on your remote to jump straight to it.";

    MainActivity a;
    RemoteView remote;
    RemapModel m = new RemapModel();
    String profile = RemapModel.DEFAULT;
    TextView profName, profInfo, btnName, btnLayers, listenInfo, modelInfo;
    Button prev, next, add, remove, listen, model;
    String presetId = RemotePresets.DEFAULT;
    /** Slot being taught when listening was started from an unlearned button, else null. */
    String learnSlot;
    /** Off-drawing key waiting to be offered ("Assign to…") once the window has focus again; 0 = none. */
    int pendingOffer;
    final Handler h = new Handler();
    boolean listening;
    /** Until when a Back release (from the press that cancelled listening) is swallowed. */
    long swallowBackUntil;
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
        TextView intro = Theme.text(a, "Pick a button with the D-pad and press OK to change it. Orange dots mark changed buttons.", 14, Theme.MUTED);
        intro.setPadding(0, 0, 0, Theme.dp(a, 12));
        side.addView(intro);

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
        listen.setId(View.generateViewId());
        listen.setOnClickListener(v -> { if (!listening) startListening(null); });
        lc.addView(listen);
        listenInfo = Theme.text(a, IDLE, 13, Theme.MUTED);
        lc.addView(listenInfo);
        side.addView(lc);

        LinearLayout pc = Theme.card(a);
        pc.addView(Theme.heading(a, "Profile"));
        LinearLayout pr = Theme.row(a);
        prev = Theme.button(a, "◀");
        prev.setId(View.generateViewId());
        prev.setGravity(android.view.Gravity.CENTER);
        prev.setOnClickListener(v -> stepProfile(-1));
        pr.addView(prev);
        profName = Theme.text(a, "", 18, Theme.TEXT);
        profName.setTypeface(Typeface.DEFAULT_BOLD);
        profName.setSingleLine(true);
        profName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        profName.setGravity(android.view.Gravity.CENTER);
        pr.addView(profName, new LinearLayout.LayoutParams(0, -2, 1f));
        next = Theme.button(a, "▶");
        next.setId(View.generateViewId());
        next.setGravity(android.view.Gravity.CENTER);
        next.setOnClickListener(v -> stepProfile(1));
        ((LinearLayout.LayoutParams) next.getLayoutParams()).setMargins(Theme.dp(a, 8), Theme.dp(a, 4), 0, Theme.dp(a, 4));
        pr.addView(next);
        pc.addView(pr);
        profInfo = Theme.text(a, "", 13, Theme.MUTED);
        pc.addView(profInfo);
        LinearLayout pr2 = Theme.row(a);
        add = Theme.button(a, "Add app profile");
        add.setId(View.generateViewId());
        add.setOnClickListener(v -> addProfile());
        pr2.addView(add);
        remove = Theme.button(a, "Remove profile");
        remove.setOnClickListener(v -> removeProfile());
        pr2.addView(remove);
        pc.addView(pr2);
        side.addView(pc);

        LinearLayout mc = Theme.card(a);
        mc.addView(Theme.heading(a, "Remote model"));
        model = Theme.button(a, "");
        model.setOnClickListener(v -> chooseModel());
        mc.addView(model);
        modelInfo = Theme.text(a, "", 13, Theme.MUTED);
        mc.addView(modelInfo);
        side.addView(mc);

        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -1, 1.1f);
        sp.setMargins(Theme.dp(a, 16), 0, 0, 0);
        root.addView(Theme.scroll(a, side), sp);

        reload();
        return root;
    }

    // ---- model / profile ----

    void reload() {
        loadRemote();
        m = Store.hasRemap(a) ? Store.remap(a) : new RemapModel();
        if (!m.profiles().contains(profile)) profile = RemapModel.DEFAULT;
        refresh();
    }

    /** Re-read the remote model, learned codes and button names from Store into the drawing. */
    void loadRemote() {
        presetId = Store.remoteModel(a);
        RemotePresets.Preset p = RemotePresets.get(presetId);
        RemoteLayout.Btn[] bs = Store.remoteButtons(a);
        remote.setRemote(p, bs);
        model.setText(p.title());
        int open = RemotePresets.unlearned(bs).size();
        modelInfo.setText(open == 0 ? "The drawing matches this remote. Pick yours if it looks different."
            : open + " button" + (open == 1 ? "" : "s") + " on this remote send a key Stickman doesn't know yet: select one and press OK to teach it.");
    }

    void chooseModel() {
        final List<RemotePresets.Preset> ps = RemotePresets.ALL;
        String[] names = new String[ps.size()];
        int cur = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = ps.get(i).title();
            if (ps.get(i).id.equals(presetId)) cur = i;
        }
        new ThemedDialog(a).title("Which remote do you have?").items(names, cur, w -> {
            Store.setRemoteModel(a, ps.get(w).id);
            loadRemote();
            refresh();
        }).cancel("Cancel").show();
    }

    void refresh() {
        boolean def = RemapModel.DEFAULT.equals(profile);
        List<String> ps = m.profiles();
        profName.setText(def ? "Default" : ButtonEditor.appLabel(a, profile));
        profInfo.setText((def ? "Every app (unless it has its own profile)" : "Only while this app is open · " + profile)
            + "\nProfile " + (ps.indexOf(profile) + 1) + " of " + ps.size());
        boolean many = ps.size() > 1;
        // with one profile the arrows are shown dimmed (disabled, so focus never lands on a dead control)
        prev.setEnabled(many);
        next.setEnabled(many);
        prev.setFocusable(many);
        next.setFocusable(many);
        remote.setNextFocusRightId(listen.getId());
        add.setNextFocusUpId(many ? prev.getId() : listen.getId());
        remove.setVisibility(def ? View.GONE : View.VISIBLE);
        onFocusButton(remote.focused());
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
        ThemedDialog.confirm(a, "Remove profile?",
            "Remove the " + ButtonEditor.appLabel(a, p) + " profile? That app goes back to the Default buttons.", "Remove", () -> {
                m = Store.remap(a);
                m.removeProfile(p);
                Store.saveRemap(a, m);
                profile = RemapModel.DEFAULT;
                refresh();
                if (Store.enabled(a)) a.run(null, Ensure.MARKER);
            });
    }

    /** Profile whose rules apply to code in the selected profile (app profiles fall back to Default per key). */
    String effective(int code) {
        return RemapModel.DEFAULT.equals(profile) || m.hasRules(profile, code) ? profile : RemapModel.DEFAULT;
    }

    // ---- RemoteView.Info ----

    @Override public boolean hasRules(RemoteLayout.Btn b) { return b.code != 0 && m.hasRules(profile, b.code); }

    /** The pill names the button; what it does is in the Selected button card (no duplicate). */
    @Override public String hint(RemoteLayout.Btn b) {
        return b.code == 0 ? b.name + " · not learned" : b.name;
    }

    // ---- RemoteView.Listener ----

    @Override public void onFocusButton(RemoteLayout.Btn b) {
        if (btnName == null) return;
        btnName.setText(b.name);
        if (b.code == 0) {
            btnLayers.setText("Stickman doesn't know which key this button sends yet. Press OK, then press it on your remote.");
            return;
        }
        int code = b.code;
        String p = effective(code);
        boolean inherited = !p.equals(profile);
        StringBuilder sb = new StringBuilder();
        String tap = m.get(p, code, Rule.Layer.TAP), dbl = m.get(p, code, Rule.Layer.DOUBLE), hold = m.get(p, code, Rule.Layer.HOLD);
        sb.append("Press:  ").append(tap == null ? "Original" : ButtonEditor.describe(a, tap));
        sb.append("\nDouble press:  ").append(dbl == null ? "Off" : ButtonEditor.describe(a, dbl));
        sb.append("\nHold:  ").append(Keys.tapOnly(code) ? "— (app buttons can't be held)" : hold == null ? "Original" : ButtonEditor.describe(a, hold));
        if (inherited && m.hasRules(p, code)) sb.append("\n(from the Default profile)");
        btnLayers.setText(sb);
    }

    @Override public void onPick(RemoteLayout.Btn b) {
        if (listening) stopListening(null);
        if (b.code == 0) { teach(b); return; }
        if (!ready()) return;
        m = Store.remap(a);
        ButtonEditor.open(a, m, profile, b, presetId, () -> { loadRemote(); refresh(); });
    }

    /** Unlearned button picked: listen for it and assign whatever key arrives. */
    void teach(RemoteLayout.Btn b) {
        final String slot = b.slot;
        new ThemedDialog(a).title("Teach “" + b.name + "”")
            .message("Stickman doesn't know which key this button sends yet. Choose Listen, then press "
                + b.name + " on your remote.")
            .button("Listen", () -> { if (!listening) startListening(slot); })
            .cancel("Cancel").show();
    }

    /** Store code as slot's key on this remote model and show it. */
    void assign(String slot, int code) {
        Store.setLearned(a, presetId, slot, code);
        loadRemote();
        refresh();
        remote.flash(code);
        listenInfo.setText("Learned: " + remote.focused().name + " sends key " + code + ". Press OK on it to change what it does.");
        listenInfo.setTextColor(Theme.MUTED);
    }

    /** Show the pending "Assign to…" once the window has focus (e.g. after the app button's app is left). */
    void tryOffer() {
        if (pendingOffer <= 0 || listening) return;
        if (!a.hasWindowFocus()) { h.postDelayed(this::tryOffer, 250); return; }
        int code = pendingOffer;
        pendingOffer = 0;
        offerAssign(code);
    }

    /** A key that isn't on the drawing arrived while listening: offer the unlearned buttons. */
    void offerAssign(final int code) {
        final RemoteLayout.Btn[] bs = remote.buttons();
        final List<Integer> open = RemotePresets.unlearned(bs);
        String[] names = new String[open.size()];
        for (int i = 0; i < names.length; i++) names[i] = bs[open.get(i)].name;
        new ThemedDialog(a).title("Heard " + Keys.name(code) + ". Assign to:")
            .items(names, w -> assign(bs[open.get(w)].slot, code))
            .cancel("Cancel").show();
    }

    // ---- listen mode ----

    boolean remapperRunning() {
        return Store.enabled(a) && !LogTail.remoteReleased() && Store.pid(Store.lastEnsure(a), "REMAPD") != null;
    }

    /**
     * Start listening; slot is the unlearned button being taught (null = plain Listen). learnSlot is set only
     * when listening really starts, so a failed teach never leaks into a later plain Listen.
     */
    boolean startListening(String slot) {
        learnSlot = null;
        if (!remapperRunning()) {
            listenInfo.setText("Remapper not running. Turn Stickman on in Settings and wait for “Running ✓”.");
            listenInfo.setTextColor(Theme.ACCENT);
            return false;
        }
        if (a.checkSelfPermission(android.Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            listenInfo.setText("Stickman can't see button presses yet. Reopen it once it shows “Running ✓”.");
            listenInfo.setTextColor(Theme.ACCENT);
            return false;
        }
        pendingOffer = 0;
        learnSlot = slot;
        listening = true;
        listenFrom = System.currentTimeMillis();
        listenBaseAt = LogTail.lastKeyAt;
        listen.setText("Listening… " + LISTEN_TIMEOUT_MS / 1000 + " s");
        listen.setSelected(true);
        listenInfo.setText("Press any button on your remote now. Back cancels. "
            + "(Buttons that leave this screen, like Home or the app buttons, still do their job.)");
        listenInfo.setTextColor(Theme.TEXT);
        remote.setListening(true);
        h.post(this::poll);
        return true;
    }

    void poll() {
        if (!listening) return;
        long now = System.currentTimeMillis(), at = LogTail.lastKeyAt;
        int k = LogTail.lastKey;
        if (at > listenBaseAt && at - listenFrom >= LISTEN_GRACE_MS && k == BACK) {
            swallowBackUntil = System.currentTimeMillis() + 1500;   // its release may still reach the app
            stopListening(CANCELLED);   // Back cancels (remapd logs it even when Back is remapped)
            return;
        }
        boolean heard = at > listenBaseAt && at - listenFrom >= LISTEN_GRACE_MS;
        int what = heard ? ListenDecision.decide(k, remote.buttons(), learnSlot) : ListenDecision.IGNORE;
        if (what == ListenDecision.ASSIGN) {
            String slot = learnSlot;
            stopListening(null);
            assign(slot, k);
            return;
        }
        if (what == ListenDecision.OFFER) {
            stopListening("Heard " + Keys.name(k) + ", which isn't on this drawing. Choose which button it is.");
            pendingOffer = k;
            h.postDelayed(this::tryOffer, 300);
            return;
        }
        if (what == ListenDecision.PICK || what == ListenDecision.ALREADY) {
            final RemoteLayout.Btn b = remote.buttons()[RemoteLayout.indexOf(remote.buttons(), k)];
            boolean teaching = what == ListenDecision.ALREADY;
            stopListening(teaching ? "That key is already " + b.name + " on this drawing. Try again?" : "Heard " + b.name + ".");
            remote.flash(k);
            if (!teaching) h.postDelayed(() -> { if (a.hasWindowFocus()) onPick(b); }, 650);
            return;
        }
        if (at > listenBaseAt) listenBaseAt = at;   // grace-period or unknown key: keep listening
        if (now - listenFrom > LISTEN_TIMEOUT_MS) { stopListening("Didn't hear a button. Try again?"); return; }
        String c = "Listening… " + Math.max(1, (LISTEN_TIMEOUT_MS - (now - listenFrom) + 999) / 1000) + " s";
        if (!c.contentEquals(listen.getText())) listen.setText(c);
        h.postDelayed(this::poll, 60);
    }

    void stopListening(String msg) {
        listening = false;
        learnSlot = null;
        h.removeCallbacksAndMessages(null);
        if (listen == null) return;
        listen.setText("Listen for a button");
        listen.setSelected(false);
        remote.setListening(false);
        listenInfo.setText(msg != null ? msg : IDLE);
        listenInfo.setTextColor(Theme.MUTED);
    }

    /**
     * While listening the physical press is read from remapd's log; keep it from also moving focus here.
     * Back cancels listening (no editor opens).
     */
    @Override public boolean onKey(KeyEvent e) {
        boolean back = e.getKeyCode() == KeyEvent.KEYCODE_BACK;
        if (!listening) {
            // swallow the release of a Back press that cancelled listening (else it would close the app)
            if (back && System.currentTimeMillis() < swallowBackUntil) {
                if (e.getAction() == KeyEvent.ACTION_UP) swallowBackUntil = 0;
                return true;
            }
            return false;
        }
        if (back) {
            swallowBackUntil = e.getAction() == KeyEvent.ACTION_DOWN ? System.currentTimeMillis() + 1500 : 0;
            stopListening(CANCELLED);
        }
        return true;
    }

    @Override public void onShow() {
        if (a != null) reload();
        if (a != null && pendingOffer > 0) h.postDelayed(this::tryOffer, 300);
    }

    @Override public void onHide() {
        if (listening) stopListening(null);
        h.removeCallbacksAndMessages(null);
    }
}
