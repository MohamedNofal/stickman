package com.clean.stickman;

import android.graphics.Typeface;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.clean.stickman.core.Catalog;
import com.clean.stickman.core.Commands;
import com.clean.stickman.core.Debloat;
import com.clean.stickman.core.Lean;
import com.clean.stickman.core.Ota;

import java.util.List;

/** Recommended + advanced Amazon extras to disable, the "disabled by Stickman" list and Restore all. */
public class DebloatPage extends ApplyPage {
    static final long AUTO_READ_MS = 30000;
    static final String ADVANCED_WARNING = "These are Amazon's own system apps. Stickman already hides the ones it knows "
        + "are essential, but disabling an unknown one can break a feature (Alexa, the store, casting…). "
        + "Everything you disable here can be turned back on below or with Restore all.";

    LinearLayout recBox, advBox, oursBox;
    Button advToggle, restoreAll, disableAll;
    boolean advShown;
    Debloat model;
    long readAt;
    /** OTA freeze state; null until the first state read comes back. */
    Boolean otaFrozen;
    long otaReadAt;
    TextView otaStatus;
    Button otaBtn;

    @Override public String title() { return "Debloat"; }

    @Override public android.view.View build(MainActivity act) {
        LinearLayout l = start(act);
        LinearLayout top = Theme.row(a);
        button(top, "Refresh list", v -> read());
        l.addView(top);

        LinearLayout oc = card(l, "System updates (OTA)", "Fire OS protects its updater, so it can't be switched "
            + "off like the apps below. Freeze suspends the OTA app and its forced updater, and re-applies the freeze "
            + "at boot. Apps still update from the Appstore.");
        otaStatus = Theme.text(a, "Checking…", 15, Theme.MUTED);
        oc.addView(otaStatus);
        otaBtn = button(oc, "Freeze OTA…", v -> confirmOta());

        LinearLayout rc = card(l, "Recommended", "Safe Amazon extras you can switch off. Home, Settings, updates, "
            + "remotes and Net Filter can never be disabled here.");
        disableAll = button(rc, "Disable all…", v -> confirmDisableAll());
        recBox = Theme.column(a);
        rc.addView(recBox);

        LinearLayout ours = card(l, "Disabled by Stickman", null);
        oursBox = Theme.column(a);
        ours.addView(oursBox);
        restoreAll = button(ours, "Restore all…", v -> ThemedDialog.confirm(a, "Restore all?",
            "Turn every app Stickman disabled back on? Other settings stay as they are.", "Restore",
            () -> send(Debloat.restoreAllScript() + "; " + Debloat.READ_CMD, "restore", "Restoring apps…")));
        button(ours, "Free up space…", v -> ThemedDialog.confirm(a, "Free up space?",
            "Amazon keeps downloading updates for apps even after they're disabled. This removes those updates from the "
            + "apps Stickman disabled, back to their small built-in version (they stay disabled).\n\nIf you turn one back "
            + "on later, the Appstore updates it again.", "Free up space",
            () -> { freeAt = System.currentTimeMillis(); send(com.clean.stickman.core.Lean.freeSpaceDetached(), "freeing", "Freeing space…"); }));
        button(ours, "Clear app caches…", v -> ThemedDialog.confirm(a, "Clear app caches?",
            "Deletes every app's cache files (thumbnails, downloaded pages) and trims the storage. Apps rebuild their "
            + "caches as you use them; nothing you set up is lost.", "Clear caches",
            () -> { clearAt = System.currentTimeMillis(); send(Lean.clearCachesDetached(), "clearing", "Clearing caches…"); }));
        LinearLayout ck = card(l, "Check after an update", "Fire OS updates can quietly switch disabled apps back on and "
            + "undo Keep asleep / Starve. This finds anything that came back and offers to switch it off again.");
        button(ck, "Check now", v -> send(com.clean.stickman.core.Starve.checkScript(), "check", "Checking…"));

        LinearLayout ad = card(l, "Advanced", "All other Amazon apps. For people who know what they're switching off.");
        advToggle = button(ad, "Show advanced list…", v -> {
            if (advShown) { advShown = false; render(); return; }
            ThemedDialog.confirm(a, "Careful", ADVANCED_WARNING, "Show them", () -> { advShown = true; render(); });
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
        appLabels.clear();   // apps may have been installed/updated since the last show
        if (a == null || Bridge.otherVpnActive(a)) return;
        if (System.currentTimeMillis() - readAt > AUTO_READ_MS) read();
        if (otaFrozen == null || System.currentTimeMillis() - otaReadAt > AUTO_READ_MS)
            send(Ota.stateCmd(), "otastate", "Checking OTA state…");
    }

    void render() {
        boolean had = a.focusInPage();
        renderRows();
        renderOta();
        a.keepFocusInPage(had);   // removing the focused row must not throw focus to the sidebar
    }

    /** OTA card: status line + the Freeze/Unfreeze button label. */
    void renderOta() {
        if (otaStatus == null || otaBtn == null) return;
        if (otaFrozen == null) {
            otaStatus.setText("Checking…");
            otaStatus.setTextColor(Theme.MUTED);
            otaBtn.setText("Freeze OTA…");
        } else if (otaFrozen) {
            otaStatus.setText("Frozen — Fire OS system updates are blocked.");
            otaStatus.setTextColor(Theme.ACCENT);
            otaBtn.setText("Unfreeze OTA…");
        } else {
            otaStatus.setText("Allowed — Fire OS can download and install system updates.");
            otaStatus.setTextColor(Theme.MUTED);
            otaBtn.setText("Freeze OTA…");
        }
    }

    void confirmOta() {
        if (Boolean.TRUE.equals(otaFrozen)) {
            ThemedDialog.confirm(a, "Allow Fire OS updates again?",
                "Fire OS will be able to download and install system updates again. App updates are not affected.",
                "Unfreeze", () -> {
                    Store.setOtaFrozen(a, false);
                    send(Ota.thawScript() + "; " + Ota.stateCmd(), "otathaw", "Unfreezing…");
                });
        } else {
            ThemedDialog.confirm(a, "Freeze system updates?",
                "Stops Fire OS from downloading or installing system updates until you unfreeze. Apps still update "
                + "from the Appstore.\n\nFire OS protects its updater, so Stickman suspends it instead of disabling it. "
                + "The freeze is re-applied at boot.", "Freeze", () -> {
                    Store.setOtaFrozen(a, true);
                    send(Ota.freezeScript() + "; " + Ota.stateCmd(), "otafreeze", "Freezing OTA…");
                });
        }
    }

    void renderRows() {
        recBox.removeAllViews();
        oursBox.removeAllViews();
        advBox.removeAllViews();
        advToggle.setText(advShown ? "Hide advanced list" : "Show advanced list…");
        // nothing to restore: no focusable, active-looking button
        restoreAll.setVisibility(model != null && !model.disabledByUs.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
        boolean anyRec = model != null && !model.recommended().isEmpty();
        disableAll.setVisibility(anyRec ? android.view.View.VISIBLE : android.view.View.GONE);
        if (anyRec) disableAll.setText("Disable all " + model.recommended().size() + "…");
        if (model == null) {
            oursBox.addView(Theme.text(a, "Not read yet.", 14, Theme.MUTED));
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
            final String lb = label(pkg);
            button(row, "Re-enable", v -> confirmEnable(pkg, lb));
            oursBox.addView(row);
        }

        if (advShown) {
            List<String> adv = model.advanced();
            if (adv.isEmpty()) advBox.addView(Theme.text(a, "No other Amazon apps.", 14, Theme.MUTED));
            else advBox.addView(Theme.text(a, adv.size() + " apps, A–Z. Disable asks first; you can turn any of "
                + "them back on above.", 14, Theme.MUTED));
            java.util.List<String[]> rows = new java.util.ArrayList<>();
            for (String pkg : adv) rows.add(new String[] { appLabel(pkg), pkg });
            java.util.Collections.sort(rows, (x, y) -> x[0].compareToIgnoreCase(y[0]));
            for (String[] r : rows) {
                final String pkg = r[1], label = r[0];
                LinearLayout row = Theme.row(a);
                LinearLayout txt = Theme.column(a);
                TextView name = Theme.text(a, label, 16, Theme.TEXT);
                name.setTypeface(Typeface.DEFAULT_BOLD);
                txt.addView(name);
                if (!label.equals(pkg)) txt.addView(Theme.text(a, pkg, 13, Theme.MUTED));
                row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
                button(row, "Disable", v -> confirmDisable(pkg, label));
                advBox.addView(row);
            }
        }
    }

    String label(String pkg) {
        for (String[] r : Catalog.DEBLOAT_RECOMMENDED) if (r[0].equals(pkg)) return r[1];
        return pkg;
    }

    /** App labels for the advanced list, looked up once per page show (not on every render). */
    final java.util.Map<String, String> appLabels = new java.util.HashMap<>();

    String appLabel(String pkg) {
        String l = appLabels.get(pkg);
        if (l == null) { l = ButtonEditor.appLabel(a, pkg); appLabels.put(pkg, l); }
        return l;
    }

    /** Re-enabling changes a package too: ask first, Cancel focused. */
    void confirmEnable(final String pkg, final String label) {
        final String cmd = cmdAndRead(Debloat.enableOne(pkg));
        if (cmd == null) return;
        ThemedDialog.confirm(a, "Turn " + label + " back on?",
            pkg + "\n\nIt goes back to how Amazon shipped it. You can disable it again from Recommended or Advanced.",
            "Re-enable", () -> send(cmd, "enabled:" + label, "Turning " + label + " back on…"));
    }

    static String cmdAndRead(String cmd) { return cmd == null ? null : cmd + "; " + Debloat.READ_CMD; }

    void confirmDisable(final String pkg, String label) {
        final String cmd = cmdAndRead(Commands.debloat(pkg, true));
        if (cmd == null) { say(label + " is protected and can't be disabled."); return; }
        ThemedDialog.confirm(a, "Disable " + label + "?",
            pkg + "\n\nYou can turn it back on from \"Disabled by Stickman\" or with Restore all.", "Disable",
            () -> send(cmd, "disabled:" + label, "Disabling " + label + "…"));
    }

    void confirmDisableAll() {
        if (model == null) return;
        List<String[]> rec = model.recommended();
        String script = Debloat.disableAllScript(rec);
        if (script == null) return;
        final String cmd = cmdAndRead(script);
        StringBuilder keep = new StringBuilder();
        for (String[] r : rec) if (r[2].contains("keep if")) keep.append(keep.length() == 0 ? "" : ", ").append(r[1]);
        ThemedDialog.confirm(a, "Disable all " + rec.size() + "?",
            "Switches off every app in Recommended."
            + (keep.length() > 0 ? "\n\nIncludes " + keep + ": skip this if you use them." : "")
            + "\n\nYou can turn any of them back on from \"Disabled by Stickman\" or with Restore all.",
            "Disable all", () -> send(cmd, "disabledall", "Disabling " + rec.size() + " apps…"));
    }

    /** When Free up space / Clear caches was started (0 = not waiting for its STATUS line). */
    long freeAt, clearAt;

    @Override public void onTick() {
        if (a == null) return;
        if (freeAt != 0) {
            String st = LogTail.status("freed");
            if (st != null && LogTail.statusAt("freed") >= freeAt - 1000) {
                freeAt = 0;
                long[] f = com.clean.stickman.core.Lean.parseFreed(st);
                say(f == null ? "Free up space: " + st : f[1] == 0 ? "Nothing to free: none of the disabled apps have updates stored."
                    : "Freed " + Math.max(0, f[0] / 1024) + " MB from " + f[1] + " app(s).");
            } else if (System.currentTimeMillis() - freeAt > 5 * 60000) {
                freeAt = 0;
                say("Free up space didn't report back within 5 minutes.");
            }
        }
        if (clearAt != 0) {
            String st = LogTail.status("cleared");
            if (st != null && LogTail.statusAt("cleared") >= clearAt - 1000) {
                clearAt = 0;
                Long kb = Lean.parseCleared(st);
                say(kb == null ? "Clear caches: " + st : "Cleared " + Math.max(0, kb / 1024) + " MB of app caches and trimmed the storage.");
            } else if (System.currentTimeMillis() - clearAt > 5 * 60000) {
                clearAt = 0;
                say("Clear caches didn't report back within 5 minutes.");
            }
        }
    }

    /** Shows the check result; when something came back on, offers to switch it off again. */
    String checked(String out) {
        int n = com.clean.stickman.core.Starve.checkedCount(out);
        if (n < 0) return "Check failed: " + firstLine(out);
        if (n == 0) return "Nothing to check yet: Stickman hasn't switched anything off.";
        final java.util.List<String[]> dr = com.clean.stickman.core.Starve.parseDrift(out);
        if (dr.isEmpty()) return "All " + n + " are still off ✓";
        final String fix = com.clean.stickman.core.Starve.fixScript(dr);
        StringBuilder sb = new StringBuilder();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String[] d : dr) if (seen.add(d[1])) {
            String what = "pkg".equals(d[0]) ? "turned back on" : "can run in the background again";
            sb.append("• ").append(ButtonEditor.appLabel(a, d[1])).append(": ").append(what).append('\n');
        }
        if (fix == null) return dr.size() + " item(s) came back, but they're protected now, so they were left alone.";
        ThemedDialog.confirm(a, dr.size() + " came back on", sb.toString().trim() + "\n\nSwitch them off again?", "Switch off",
            () -> send(fix + "; " + Debloat.READ_CMD, "fixed", "Switching them off again…"));
        return dr.size() + " of " + n + " came back on.";
    }

    @Override String describe(String kind, String out) {
        if (kind.equals("otastate") || kind.equals("otafreeze") || kind.equals("otathaw")) {
            otaFrozen = Ota.parseFrozen(out);
            otaReadAt = System.currentTimeMillis();
            renderOta();
            if (kind.equals("otastate")) return null;
            Store.setOtaFrozen(a, otaFrozen);   // the device is the truth, not what we asked for
            if (kind.equals("otafreeze")) return otaFrozen ? "OTA updates are frozen." : "Couldn't freeze OTA: " + firstLine(out);
            return otaFrozen ? "Unfreeze didn't take: " + firstLine(out) : "OTA updates are allowed again.";
        }
        if (kind.equals("check")) return checked(out);
        if (out.contains(Debloat.SEP)) { model = Debloat.parse(out); render(); }
        if (kind.equals("read")) { result.setText(""); return null; }
        if (kind.equals("restore")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("RESTORED (\\d+)").matcher(out);
            return m.find() ? "Turned " + m.group(1) + " app(s) back on." : "Restored.";
        }
        if (kind.equals("freeing")) {
            if (!out.contains("FREEING")) { freeAt = 0; return "Couldn't start: " + firstLine(out); }
            return "Freeing space… this can take a minute or two.";
        }
        if (kind.equals("clearing")) {
            if (!out.contains("CLEARING")) { clearAt = 0; return "Couldn't start: " + firstLine(out); }
            return "Clearing caches…";
        }
        if (kind.equals("fixed")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("FIXED (\\d+)").matcher(out);
            return m.find() ? "Switched " + m.group(1) + " back off." : "Done.";
        }
        if (kind.equals("disabledall")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("DISABLED_ALL (\\d+)").matcher(out);
            return m.find() ? "Disabled " + m.group(1) + " app(s)." : "Done.";
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
