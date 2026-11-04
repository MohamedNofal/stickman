package com.clean.stickman;

import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.clean.stickman.core.Actions;
import com.clean.stickman.core.Commands;
import com.clean.stickman.core.Debloat;
import com.clean.stickman.core.StickdConf;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Boost, auto-kill (stickd.conf), keep-asleep apps and animation speed. */
public class SpeedPage extends ApplyPage {
    static final int[] INTERVALS = { 0, 5, 15, 30, 60 };
    static final float[] ANIM = { 1f, 0.5f, 0f };
    static final long BOOST_TIMEOUT_MS = 20000;
    static final Pattern BOOST = Pattern.compile("freed_mb=(\\d+)\\s+killed=(\\d+)");

    Button onLeave;
    Button[] interval, anim;
    TextView boostInfo, lists;
    /** When Boost was pressed (0 = not waiting for a STATUS boost line). */
    long boostAt;

    @Override public String title() { return "Speed"; }

    @Override public android.view.View build(MainActivity act) {
        LinearLayout l = start(act);

        LinearLayout b = card(l, "Boost", "Closes background apps and trims memory. The app you're using, home, "
            + "playing media and your never-close apps are left alone.");
        button(b, "Boost now", v -> boost());
        boostInfo = Theme.text(a, "", 15, Theme.TEXT);
        b.addView(boostInfo);

        LinearLayout k = card(l, "Auto-close apps", null);
        onLeave = button(k, "", v -> { StickdConf c = conf(); c.onLeave = !c.onLeave; save(c); });
        k.addView(Theme.text(a, "Also every…", 14, Theme.MUTED));
        String[] il = new String[INTERVALS.length];
        for (int i = 0; i < il.length; i++) il[i] = INTERVALS[i] == 0 ? "Off" : INTERVALS[i] + " min";
        interval = choices(k, il, i -> { StickdConf c = conf(); c.intervalMin = INTERVALS[i]; save(c); });
        LinearLayout r = Theme.row(a);
        button(r, "Never close…", v -> pickList(true));
        button(r, "Always force-stop…", v -> pickList(false));
        k.addView(r);
        lists = Theme.text(a, "", 14, Theme.MUTED);
        k.addView(lists);

        LinearLayout s = card(l, "Keep asleep", "Chosen apps can't run in the background until you open them.");
        LinearLayout sr = Theme.row(a);
        button(sr, "Add an app…", v -> ButtonEditor.pickApp(a, "Keep which app asleep?", app ->
            send(Commands.keepAsleep(app[1], true), "asleep:" + app[0], "Putting " + app[0] + " to sleep…")));
        button(sr, "Kept-asleep apps…", v -> send(Debloat.recorded("bucket"), "list", "Reading…"));
        s.addView(sr);

        LinearLayout an = card(l, "Animation speed", "Faster animations make menus feel snappier.");
        anim = choices(an, new String[] { "1×", "0.5×", "Off" }, i ->
            send(Commands.animation(ANIM[i]), "anim:" + i, "Setting animations…"));

        refresh();
        return Theme.scroll(a, l);
    }

    StickdConf conf() { return StickdConf.parse(Store.stickdText(a)); }

    void save(StickdConf c) {
        applyStickdConf(c.serialize(), "conf");
        refresh();
    }

    void refresh() {
        StickdConf c = conf();
        onLeave.setText("Close apps when I leave them:  " + (c.onLeave ? "On" : "Off"));
        onLeave.setSelected(c.onLeave);
        int sel = 0;
        for (int i = 0; i < INTERVALS.length; i++) if (INTERVALS[i] == c.intervalMin) sel = i;
        select(interval, sel);
        lists.setText("Never close: " + names(c.whitelist) + "\nAlways force-stop: " + names(c.blacklist));
        select(anim, pref("anim", -1));
    }

    String names(Set<String> pkgs) {
        if (pkgs.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) sb.append(sb.length() > 0 ? ", " : "").append(ButtonEditor.appLabel(a, p));
        return sb.toString();
    }

    /** Multi-choice picker over installed apps (minus Stickman) for the whitelist or blacklist. */
    void pickList(final boolean white) {
        final List<String[]> apps = new ArrayList<>();
        for (String[] app : ButtonEditor.launchables(a)) if (!app[1].equals(a.getPackageName())) apps.add(app);
        if (apps.isEmpty()) { say("Couldn't list the installed apps."); return; }
        final StickdConf c = conf();
        final Set<String> set = white ? c.whitelist : c.blacklist;
        String[] names = new String[apps.size()];
        final boolean[] on = new boolean[apps.size()];
        for (int i = 0; i < names.length; i++) { names[i] = apps.get(i)[0]; on[i] = set.contains(apps.get(i)[1]); }
        a.dialog().setTitle(white ? "Never close these apps" : "Force-stop these apps (not just close)")
            .setMultiChoiceItems(names, on, (d, w, checked) -> on[w] = checked)
            .setPositiveButton("Save", (d, w) -> {
                set.clear();
                for (int i = 0; i < on.length; i++) if (on[i]) set.add(apps.get(i)[1]);
                if (white) c.blacklist.removeAll(set); else c.whitelist.removeAll(set);
                save(c);
            })
            .setNegativeButton("Cancel", null).show();
    }

    void boost() {
        if (!stickdOn()) return;
        boostAt = System.currentTimeMillis();
        boostInfo.setText("Boosting…");
        send(Commands.act(Actions.BOOST, null), "boost", "Boosting…");
    }

    @Override public void onShow() { if (a != null) refresh(); }

    @Override public void onTick() {
        if (a == null || boostAt == 0) return;
        String st = LogTail.status("boost");
        if (st != null && LogTail.statusAt("boost") >= boostAt - 1000) {
            Matcher m = BOOST.matcher(st);
            boostAt = 0;
            say(m.find() ? "Boost done: freed " + m.group(1) + " MB, closed " + m.group(2) + " apps." : "Boost done: " + st);
            boostInfo.setText(result.getText());
        } else if (System.currentTimeMillis() - boostAt > BOOST_TIMEOUT_MS && !Bridge.busy) {
            boostAt = 0;
            boostInfo.setText("No answer from Stickman's helper within 20 s. Check Status: is stickd running?");
            result.setText(boostInfo.getText());
        }
    }

    @Override String describe(String kind, String out) {
        if (kind.equals("boost")) return null;   // the STATUS boost line reports the outcome
        if (kind.equals("conf")) return Store.pid(out, "STICKD") != null ? "Saved ✓" : "Saved, but stickd isn't running.";
        if (kind.startsWith("anim:")) {
            int i = Integer.parseInt(kind.substring(5));
            putPref("anim", i);
            select(anim, i);
            return "Animations set to " + anim[i].getText() + ".";
        }
        if (kind.startsWith("asleep:")) return kind.substring(7) + " is kept asleep.";
        if (kind.startsWith("awake:")) return kind.substring(6) + " runs normally again.";
        if (kind.equals("list")) { showAsleep(out); return null; }
        return out;
    }

    /** List of kept-asleep apps (from the originals journal); choosing one lets it run normally again. */
    void showAsleep(String out) {
        final List<String> pkgs = new ArrayList<>();
        for (String p : out.split("\n")) if (ButtonEditor.validPkg(p.trim())) pkgs.add(p.trim());
        if (pkgs.isEmpty()) { say("No apps are kept asleep."); return; }
        result.setText("");
        String[] names = new String[pkgs.size()];
        for (int i = 0; i < names.length; i++) names[i] = ButtonEditor.appLabel(a, pkgs.get(i));
        a.dialog().setTitle("Kept asleep (choose one to wake it)").setItems(names, (d, w) -> {
            String n = names[w];
            send(Commands.keepAsleep(pkgs.get(w), false), "awake:" + n, "Letting " + n + " run normally…");
        }).setNegativeButton("Close", null).show();
    }
}
