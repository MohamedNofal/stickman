package com.clean.stickman;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.clean.stickman.core.Actions;
import com.clean.stickman.core.Catalog;
import com.clean.stickman.core.Keys;
import com.clean.stickman.core.RemapModel;
import com.clean.stickman.core.RemoteLayout;
import com.clean.stickman.core.RemotePresets;
import com.clean.stickman.core.Rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dialog editing one button's Press / Double press / Hold in one button set (the All-apps set, or one app's own).
 * Each row opens an action chooser (Original, Do nothing, an app, another button, a Stickman action); after a
 * choice the focus lands on Save, so the common single change is one more OK. Save writes remap.conf and re-runs
 * ensure. Back with unsaved changes asks instead of dropping them; newly changing what a navigation key (D-pad,
 * OK, Back) does on press warns first. App buttons can be renamed and learned buttons forgotten (per remote
 * model, in Store).
 */
public class ButtonEditor {
    public interface Callback<T> { void got(T t); }
    /** A change was saved and queued for the remote: name = the button, reset = put back rather than changed. */
    public interface Applied { void applying(String name, boolean reset); }

    static final Rule.Layer[] LAYERS = Rule.Layer.values();
    static final String DOUBLE_NOTE = "adds a short delay to this button";
    /** remapd drops action lines of 256+ bytes; keep well clear. */
    static final int MAX_ACTION = 249;
    /** What the user calls RemapModel.DEFAULT: the set of buttons that applies in every app. */
    static final String ALL_APPS = "All apps";

    final MainActivity a;
    final RemapModel m;
    final String profile;
    final int code;
    /** Replaced (withName) after a rename so titles show the new name at once. */
    RemoteLayout.Btn btn;
    final String presetId;
    final Runnable onSaved;
    final Applied onApply;
    final String[] cur = new String[LAYERS.length];
    /** The values when the editor opened: a navigation key whose press is newly remapped gets a warning. */
    final String[] orig = new String[LAYERS.length];
    final Button[] rows = new Button[LAYERS.length];
    /** App set without its own rules for this key: showing the All-apps set's values. */
    final boolean inherited;
    boolean dirty;
    /** The dialog is closing through one of its own buttons (not Back), so no "keep your changes?" prompt. */
    boolean closed;
    TextView note, title;
    ThemedDialog dlg;

    ButtonEditor(MainActivity a, RemapModel m, String profile, RemoteLayout.Btn btn, String presetId, Runnable onSaved, Applied onApply) {
        this.a = a;
        this.m = m;
        this.profile = profile;
        this.btn = btn;
        this.presetId = presetId;
        this.code = btn.code;
        this.onSaved = onSaved;
        this.onApply = onApply;
        inherited = !RemapModel.DEFAULT.equals(profile) && !m.hasRules(profile, code);
        String src = inherited ? RemapModel.DEFAULT : profile;
        for (Rule.Layer l : LAYERS) orig[l.ordinal()] = cur[l.ordinal()] = m.get(src, code, l);
    }

    /** onSaved: refresh the page (also after a rename / forget); onApply: a change was queued for the remote. */
    public static void open(MainActivity a, RemapModel m, String profile, RemoteLayout.Btn btn, String presetId, Runnable onSaved, Applied onApply) {
        new ButtonEditor(a, m, profile, btn, presetId, onSaved, onApply).show();
    }

    // ---- shared helpers (RemotePage uses them too) ----

    static final Map<String, String> labels = new HashMap<>();

    /** App label for a package, cached; the package name when it isn't installed. */
    static String appLabel(Context c, String pkg) {
        String l = labels.get(pkg);
        if (l != null) return l;
        try {
            PackageManager pm = c.getPackageManager();
            l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            l = pkg;
        }
        labels.put(pkg, l);
        return l;
    }

    /** Friendly text for a raw action string (null = Original). */
    static String describe(Context c, String action) {
        if (action == null) return "Original";
        String[] t = action.trim().split("\\s+", 3);
        if (t[0].equals("start") && t.length > 1) {
            int s = t[1].indexOf('/');
            String pkg = s < 0 ? t[1] : t[1].substring(0, s);
            if (Store.WOLF.equals(t[1])) return "Go to Wolf home";
            return Actions.describe(action, appLabel(c, pkg));
        }
        if (t[0].equals("act") && t.length > 2) {
            if (t[1].equals(Actions.OPEN_SCREEN)) {
                for (String[] sc : Catalog.SHORTCUTS) if (sc[1].equals(t[2])) return "Open " + sc[0];
                return "Open Amazon screen";
            }
            if (t[1].equals(Actions.SLEEP_TIMER_SET)) return "Sleep timer " + t[2] + " min";
        }
        return Actions.describe(action, null);
    }

    /** null when the action may be written to remap.conf, else why not. */
    static String invalid(String action) {
        if (action == null) return null;
        if (action.indexOf('\n') >= 0 || action.indexOf('\r') >= 0) return "The action contains a line break.";
        if (action.length() > MAX_ACTION) return "The action is too long (" + action.length() + " characters; the limit is " + MAX_ACTION + ").";
        return null;
    }

    static boolean validPkg(String p) { return p != null && p.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"); }

    /** Launchable apps (TV launcher entries first), one per package, sorted by label: {label, pkg, pkg/cls}. */
    static List<String[]> launchables(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, String[]> byPkg = new HashMap<>();
        for (String cat : new String[] { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER }) {
            List<ResolveInfo> ris = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
            for (ResolveInfo ri : ris) {
                String pkg = ri.activityInfo.packageName;
                if (byPkg.containsKey(pkg) || !validPkg(pkg)) continue;
                String comp = pkg + "/" + ri.activityInfo.name;
                if (!comp.matches("[A-Za-z0-9_.$/]+")) continue;
                byPkg.put(pkg, new String[] { String.valueOf(ri.loadLabel(pm)), pkg, comp });
            }
        }
        List<String[]> out = new ArrayList<>(byPkg.values());
        Collections.sort(out, (x, y) -> x[0].compareToIgnoreCase(y[0]));
        return out;
    }

    static void pickApp(MainActivity a, String title, Callback<String[]> cb) {
        final List<String[]> apps = launchables(a);
        if (apps.isEmpty()) { message(a, "No apps found", "Couldn't list the installed apps."); return; }
        String[] names = new String[apps.size()];
        for (int i = 0; i < names.length; i++) names[i] = apps.get(i)[0];
        new ThemedDialog(a).title(title).items(names, w -> cb.got(apps.get(w))).cancel("Cancel").show();
    }

    static void message(MainActivity a, String title, String msg) {
        ThemedDialog.message(a, title, msg);
    }

    static void toast(MainActivity a, String s) {
        android.widget.Toast.makeText(a, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ---- the dialog ----

    String layerName(Rule.Layer l) {
        switch (l) {
            case TAP: return "Press";
            case DOUBLE: return "Double press";
            default: return "Hold";
        }
    }

    String valueText(Rule.Layer l, String action) {
        if (action == null) {
            switch (l) {
                case DOUBLE: return "Off";
                case HOLD: return "Original (hold " + btn.name + ")";
                default: return "Original (" + btn.name + ")";
            }
        }
        return describe(a, action);
    }

    void refreshRows() {
        for (Rule.Layer l : LAYERS) {
            Button b = rows[l.ordinal()];
            if (b != null) b.setText(layerName(l) + "   ›   " + valueText(l, cur[l.ordinal()]));
        }
        if (note != null) note.setVisibility(inherited && !dirty ? View.VISIBLE : View.GONE);
    }

    void show() {
        LinearLayout col = Theme.column(a);

        title = Theme.text(a, btn.name, 26, Theme.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        boolean def = RemapModel.DEFAULT.equals(profile);
        String app = def ? "" : appLabel(a, profile);
        col.addView(Theme.text(a, def ? "In every app" : "Only while " + app + " is open", 15, Theme.WOOD));
        note = Theme.text(a, def ? "" : app + " uses the " + ALL_APPS + " setting for this button. Changing anything gives "
            + app + " its own.", 14, Theme.MUTED);
        note.setPadding(0, Theme.dp(a, 6), 0, 0);
        col.addView(note);
        View gap = new View(a);
        col.addView(gap, new LinearLayout.LayoutParams(1, Theme.dp(a, 10)));

        for (final Rule.Layer l : LAYERS) {
            if (l == Rule.Layer.HOLD && Keys.tapOnly(code)) continue;
            Button b = Theme.button(a, "");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, Theme.dp(a, 4), 0, Theme.dp(a, l == Rule.Layer.DOUBLE ? 0 : 4));
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> choose(l));
            rows[l.ordinal()] = b;
            col.addView(b);
            if (l == Rule.Layer.DOUBLE) {
                TextView n = Theme.text(a, "Double press " + DOUBLE_NOTE + " (it waits for a possible second press).", 13, Theme.MUTED);
                n.setPadding(Theme.dp(a, 16), Theme.dp(a, 2), 0, Theme.dp(a, 6));
                col.addView(n);
            }
        }
        if (Keys.tapOnly(code)) {
            TextView n = Theme.text(a, "App buttons can't be held: the remote sends press and release together.", 13, Theme.MUTED);
            n.setPadding(0, Theme.dp(a, 6), 0, 0);
            col.addView(n);
        }
        if (btn.isExtra()) {
            TextView n = Theme.text(a, "This button isn't on the drawing, but it works like any other. Give it a name you'll recognise.", 13, Theme.MUTED);
            n.setPadding(0, Theme.dp(a, 6), 0, 0);
            col.addView(n);
        }
        if (btn.isApp() || btn.isExtra()) {
            Button rn = Theme.button(a, "Rename button…");
            rn.setOnClickListener(v -> rename());
            col.addView(rn, extraLp());
        }
        if (learned()) {
            Button fg = Theme.button(a, "Forget this button…");
            fg.setOnClickListener(v -> forget());
            col.addView(fg, extraLp());
        }
        refreshRows();

        // Save first and the default when moving down into the buttons; Reset asks before it acts
        final String resetLabel = def ? "Reset to original" : "Use the " + ALL_APPS + " setting";
        dlg = new ThemedDialog(a).wide().view(col);
        dlg.buttonKeep("Save", v -> trySave());
        dlg.buttonKeep(resetLabel, v -> confirmReset(def));
        dlg.buttonKeep("Cancel", v -> close());
        dlg.focusButton(0);
        if (rows[0] != null) dlg.focus(rows[0]);
        // Back (or anything else that closes the dialog) with unsaved changes: ask, never drop them silently
        dlg.onDismiss(() -> { if (dirty && !closed) askKeep(); });
        dlg.show();
    }

    /** Close through one of the dialog's own buttons: no "keep your changes?" prompt. */
    void close() {
        closed = true;
        if (dlg != null) dlg.dismiss();
    }

    /** Save: nothing changed just closes; a navigation key's press newly changed warns first. */
    void trySave() {
        if (!dirty) { close(); return; }
        if (needsNavWarning()) { warnNav(() -> { close(); save(false); }); return; }
        close();
        save(false);
    }

    /** True when this edit makes the press of a key you get around with (D-pad, OK, Back) do something else. */
    boolean needsNavWarning() {
        int t = Rule.Layer.TAP.ordinal();
        return Keys.navigation(code) && remapped(cur[t]) && !remapped(orig[t]);
    }

    static boolean remapped(String action) { return action != null && !action.equals("orig"); }

    /** Cancel is focused: OK can't change a navigation key by accident. */
    void warnNav(Runnable go) {
        ThemedDialog.confirm(a, "Change what " + btn.name + " does?",
            "You use " + btn.name + " to get around the Fire TV's menus, Stickman included. If pressing it does "
                + "something else, you may get stuck.\n\nIf that happens, hold Back for 10 seconds: the remote works "
                + "normally again.",
            "Change it", go);
    }

    /** Back with unsaved changes: keep or drop them (Save is the safe default). */
    void askKeep() {
        new ThemedDialog(a).title("Keep your changes to " + btn.name + "?")
            .message("You changed what " + btn.name + " does but didn't save.")
            .button("Save", () -> { if (needsNavWarning()) warnNav(() -> save(false)); else save(false); })
            .button("Discard", null).focusButton(0).show();
    }

    void confirmReset(boolean def) {
        String app = def ? "" : appLabel(a, profile);
        ThemedDialog.confirm(a, def ? "Reset " + btn.name + "?" : "Use the " + ALL_APPS + " setting for " + btn.name + "?",
            def ? "Put " + btn.name + " back to what it originally does (press, double press and hold)?"
                : "Drop " + app + "'s own setting for " + btn.name + ", so the " + ALL_APPS + " setting applies in " + app + " too?",
            def ? "Reset" : "Use " + ALL_APPS, () -> { close(); save(true); });
    }

    LinearLayout.LayoutParams extraLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, Theme.dp(a, 8), 0, 0);
        return lp;
    }

    /** True when this button's code was learned in listen mode (the preset itself doesn't know it). */
    boolean learned() {
        RemoteLayout.Btn[] orig = RemotePresets.get(presetId).buttons;
        int i = RemotePresets.slotIndex(orig, btn.slot);
        return i >= 0 && orig[i].code == 0 && code != 0;
    }

    /** New name for an app button on this remote model (validated: one line, at most 24 characters). */
    void rename() {
        final android.widget.EditText in = Theme.edit(a);
        in.setText(btn.name);
        in.setSelection(in.getText().length());
        in.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(RemotePresets.MAX_LABEL) });
        LinearLayout box = Theme.column(a);
        box.addView(in);
        RemoteLayout.Btn[] orig = RemotePresets.get(presetId).buttons;
        int oi = RemotePresets.slotIndex(orig, btn.slot);
        final String dflt = oi >= 0 ? orig[oi].name : Keys.name(code);
        final TextView err = Theme.text(a, "", 13, Theme.ACCENT);
        err.setVisibility(View.GONE);
        box.addView(err);
        final ThemedDialog d = new ThemedDialog(a).title("Rename button").view(box);
        d.buttonKeep("Save", v -> {
            String s = in.getText().toString();
            String why = RemotePresets.invalidLabel(s);
            if (why != null) { err.setText(why); err.setVisibility(View.VISIBLE); in.requestFocus(); return; }
            renamed(s.trim(), s.trim());
            d.dismiss();
        });
        d.button("Default (" + dflt + ")", () -> renamed(null, dflt)).cancel("Cancel").focus(in).show();
    }

    /** Store the new label (null = default) and show name everywhere in this editor right away. */
    void renamed(String stored, String name) {
        Store.setLabel(a, presetId, btn.slot, stored);
        btn = btn.withName(name);
        title.setText(name);
        refreshRows();
        if (onSaved != null) onSaved.run();
    }

    /** Drop the learned code; its rules stay in effect unless the owner also removes them. */
    void forget() {
        new ThemedDialog(a).title("Forget " + btn.name + "?")
            .message(btn.name + " goes back to “not seen yet”; you can teach it again with Listen.\n\n"
                + "What you set for it stays in effect (for every app) unless you also remove that.")
            .button("Forget", () -> forgotten(false))
            .button("Also remove what it does", () -> forgotten(true))
            .cancel("Cancel").focusButton(2).show();
    }

    void forgotten(boolean removeRules) {
        Store.setLearned(a, presetId, btn.slot, 0);
        if (removeRules) {
            for (String p : m.profiles()) for (Rule.Layer l : LAYERS) m.set(p, code, l, null);
            Store.saveRemap(a, m);
            if (Store.enabled(a)) a.run(null, Ensure.MARKER);
        }
        close();
        if (onSaved != null) onSaved.run();
    }

    void set(Rule.Layer l, String action) {
        String why = invalid(action);
        if (why != null) { message(a, "Can't use that", why); return; }
        cur[l.ordinal()] = action;
        dirty = true;
        refreshRows();
        focusSave();
    }

    /** After a choice the usual next step is Save: put the focus there (UP goes back to the rows). */
    void focusSave() {
        if (dlg == null) return;
        final Button s = dlg.defaultButton();
        if (s != null) s.post(() -> s.requestFocus());
    }

    /** Row of the chooser that matches the current value (so the list shows what the button does now). */
    static int option(String action) {
        if (action == null || action.equals("orig")) return 0;
        if (action.equals("none")) return 1;
        if (action.startsWith("start ")) return 2;
        if (action.startsWith("key ")) return 3;
        return 4;
    }

    void choose(final Rule.Layer l) {
        String orig = l == Rule.Layer.DOUBLE ? "Off (no double press)" : "Original (what it normally does)";
        String[] opts = { orig, "Do nothing", "Open an app…", "Act as another button…", "Something else: sleep, screenshot, Boost…" };
        new ThemedDialog(a).title(layerName(l) + " · " + btn.name).items(opts, option(cur[l.ordinal()]), w -> {
            switch (w) {
                case 0: set(l, null); break;
                case 1: set(l, "none"); break;
                case 2: pickApp(a, "Open which app?", app -> set(l, "start " + app[2])); break;
                case 3: chooseKey(l); break;
                default: chooseStickman(l);
            }
        }).cancel("Cancel").show();
    }

    void chooseKey(final Rule.Layer l) {
        final List<Integer> codes = new ArrayList<>();
        for (int k : Keys.ALL) if (k != code) codes.add(k);
        String[] names = new String[codes.size()];
        for (int i = 0; i < names.length; i++) names[i] = Keys.name(codes.get(i));
        new ThemedDialog(a).title("Act as which button?").items(names, w -> set(l, "key " + codes.get(w)))
            .cancel("Cancel").show();
    }

    /** Built from core/Actions: every argument-free, non-internal id with its one-line hint, plus the open_screen submenu. */
    void chooseStickman(final Rule.Layer l) {
        final List<String> labels = new ArrayList<>(), acts = new ArrayList<>();
        for (String id : Actions.menu()) { labels.add(Actions.menuLabel(id, false)); acts.add("act " + id); }
        labels.add(Actions.menuLabel(Actions.OPEN_SCREEN, true)); acts.add("@screen");
        try {
            a.getPackageManager().getPackageInfo(Store.WOLF_PKG, 0);
            labels.add("Go to Wolf home  ·  opens the Wolf launcher"); acts.add("start " + Store.WOLF);
        } catch (Exception ignored) {}
        new ThemedDialog(a).title("Something else").items(labels.toArray(new String[0]), w -> {
            String act = acts.get(w);
            if (act.equals("@screen")) {
                String[] n = new String[Catalog.SHORTCUTS.length];
                for (int i = 0; i < n.length; i++) n[i] = Catalog.SHORTCUTS[i][0];
                new ThemedDialog(a).title("Open which screen?").items(n, w2 ->
                    set(l, "act " + Actions.OPEN_SCREEN + " " + Catalog.SHORTCUTS[w2][1])).cancel("Cancel").show();
            } else {
                set(l, act);
            }
        }).cancel("Cancel").show();
    }

    /** reset: All-apps set -> every layer Original; app set -> drop its rules so the All-apps set applies. */
    void save(boolean reset) {
        if (!reset && !dirty) return;
        if (reset) {
            for (Rule.Layer l : LAYERS) m.set(profile, code, l, null);
        } else {
            for (Rule.Layer l : LAYERS) {
                String v = cur[l.ordinal()];
                if (l == Rule.Layer.HOLD && Keys.tapOnly(code)) v = null;
                String why = invalid(v);
                if (why != null) { message(a, "Not saved", why); return; }
                m.set(profile, code, l, v);
            }
            // an app profile asking for plain Original while Default remaps the key needs an explicit rule
            if (!RemapModel.DEFAULT.equals(profile) && !m.hasRules(profile, code) && m.hasRules(RemapModel.DEFAULT, code))
                m.set(profile, code, Rule.Layer.TAP, "orig");
        }
        Store.saveRemap(a, m);
        boolean def = RemapModel.DEFAULT.equals(profile);
        String done = !reset ? "Saved." : def ? btn.name + " is back to original."
            : btn.name + " uses the " + ALL_APPS + " setting in " + appLabel(a, profile) + " again.";
        if (!Store.enabled(a)) {
            toast(a, done + " Stickman is off: turn it on in Settings to use it.");
        } else {
            // the remote gets it when the queued ensure finishes; RemotePage reports that (and a stall)
            toast(a, done + " Applying to your remote…");
            if (onApply != null) onApply.applying(btn.name, reset);
        }
        if (onSaved != null) onSaved.run();
        if (Store.enabled(a)) a.run(null, Ensure.MARKER);
    }
}
