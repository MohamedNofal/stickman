package com.clean.stickman;

import android.graphics.Typeface;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.clean.stickman.core.Catalog;
import com.clean.stickman.core.Commands;
import com.clean.stickman.core.Debloat;

import java.util.List;

/** Recommended + advanced Amazon extras to disable, the "disabled by Stickman" list and Restore all. */
public class DebloatPage extends ApplyPage {
    static final long AUTO_READ_MS = 30000;
    static final String ADVANCED_WARNING = "These are Amazon's own system apps. Stickman already hides the ones it knows "
        + "are essential, but disabling an unknown one can break a feature (Alexa, the store, casting…). "
        + "Everything you disable here can be turned back on below or with Restore all.";

    LinearLayout recBox, advBox, oursBox;
    Button advToggle;
    boolean advShown;
    Debloat model;
    long readAt;

    @Override public String title() { return "Debloat"; }

    @Override public android.view.View build(MainActivity act) {
        LinearLayout l = start(act);
        LinearLayout top = Theme.row(a);
        button(top, "Refresh list", v -> read());
        l.addView(top);

        LinearLayout rc = card(l, "Recommended", "Safe Amazon extras you can switch off. Home, Settings, updates, "
            + "remotes and Net Filter can never be disabled here.");
        recBox = Theme.column(a);
        rc.addView(recBox);

        LinearLayout ours = card(l, "Disabled by Stickman", null);
        oursBox = Theme.column(a);
        ours.addView(oursBox);
        button(ours, "Restore all…", v -> a.dialog().setTitle("Restore all?")
            .setMessage("Turn every app Stickman disabled back on? Other settings stay as they are.")
            .setPositiveButton("Restore", (d, w) -> send(Debloat.restoreAllScript() + "; " + Debloat.READ_CMD, "restore", "Restoring apps…"))
            .setNegativeButton("Cancel", null).show());

        LinearLayout ad = card(l, "Advanced", "All other Amazon apps. For people who know what they're switching off.");
        advToggle = button(ad, "Show advanced list…", v -> {
            if (advShown) { advShown = false; render(); return; }
            a.dialog().setTitle("Careful").setMessage(ADVANCED_WARNING)
                .setPositiveButton("Show them", (d, w) -> { advShown = true; render(); })
                .setNegativeButton("Cancel", null).show();
        });
        advBox = Theme.column(a);
        ad.addView(advBox);

        render();
        return Theme.scroll(a, l);
    }

    void read() {
        readAt = System.currentTimeMillis();
        send(Debloat.READ_CMD, "read", "Reading the app list…");
    }

    @Override public void onShow() {
        if (a != null && System.currentTimeMillis() - readAt > AUTO_READ_MS && !Bridge.otherVpnActive(a)) read();
    }

    void render() {
        recBox.removeAllViews();
        oursBox.removeAllViews();
        advBox.removeAllViews();
        advToggle.setText(advShown ? "Hide advanced list" : "Show advanced list…");
        if (model == null) {
            recBox.addView(Theme.text(a, "Press Refresh list to read the apps.", 14, Theme.MUTED));
            return;
        }
        List<String[]> rec = model.recommended();
        if (rec.isEmpty()) recBox.addView(Theme.text(a, "Nothing left to disable ✓", 15, Theme.TEXT));
        for (String[] r : rec) {
            final String pkg = r[0], label = r[1];
            LinearLayout row = Theme.row(a);
            LinearLayout txt = Theme.column(a);
            TextView name = Theme.text(a, label, 16, Theme.TEXT);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            txt.addView(name);
            txt.addView(Theme.text(a, r[2], 13, Theme.MUTED));
            row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
            button(row, "Disable", v -> confirmDisable(pkg, label));
            recBox.addView(row);
        }

        if (model.disabledByUs.isEmpty()) oursBox.addView(Theme.text(a, "None.", 14, Theme.MUTED));
        for (final String pkg : model.disabledByUs) {
            LinearLayout row = Theme.row(a);
            row.addView(Theme.text(a, label(pkg), 15, Theme.TEXT), new LinearLayout.LayoutParams(0, -2, 1f));
            button(row, "Re-enable", v -> send(cmdAndRead(Debloat.enableOne(pkg)), "enabled:" + label(pkg), "Turning " + label(pkg) + " back on…"));
            oursBox.addView(row);
        }

        if (advShown) {
            List<String> adv = model.advanced();
            if (adv.isEmpty()) advBox.addView(Theme.text(a, "No other Amazon apps.", 14, Theme.MUTED));
            for (final String pkg : adv) {
                Button b = Theme.button(a, pkg);
                b.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
                b.setOnClickListener(v -> confirmDisable(pkg, pkg));
                advBox.addView(b);
            }
        }
    }

    String label(String pkg) {
        for (String[] r : Catalog.DEBLOAT_RECOMMENDED) if (r[0].equals(pkg)) return r[1];
        return pkg;
    }

    static String cmdAndRead(String cmd) { return cmd == null ? null : cmd + "; " + Debloat.READ_CMD; }

    void confirmDisable(final String pkg, String label) {
        final String cmd = cmdAndRead(Commands.debloat(pkg, true));
        if (cmd == null) { say(label + " is protected and can't be disabled."); return; }
        a.dialog().setTitle("Disable " + label + "?")
            .setMessage(pkg + "\n\nYou can turn it back on from \"Disabled by Stickman\" or with Restore all.")
            .setPositiveButton("Disable", (d, w) -> send(cmd, "disabled:" + label, "Disabling " + label + "…"))
            .setNegativeButton("Cancel", null).show();
    }

    @Override String describe(String kind, String out) {
        if (out.contains(Debloat.SEP)) { model = Debloat.parse(out); render(); }
        if (kind.equals("read")) { result.setText(""); return null; }
        if (kind.equals("restore")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("RESTORED (\\d+)").matcher(out);
            return m.find() ? "Turned " + m.group(1) + " app(s) back on." : "Restored.";
        }
        if (kind.startsWith("disabled:")) {
            String p = kind.substring(9);
            return out.contains("disabled-user") ? p + " disabled." : p + ": " + firstLine(out);
        }
        if (kind.startsWith("enabled:")) return kind.substring(8) + " is back on.";
        return out;
    }

    static String firstLine(String s) {
        int n = s.indexOf('\n');
        return n < 0 ? s : s.substring(0, n);
    }
}
