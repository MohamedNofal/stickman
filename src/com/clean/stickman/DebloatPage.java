package com.clean.stickman;

import android.graphics.Typeface;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.clean.stickman.core.AmazonUninstall;
import com.clean.stickman.core.Catalog;
import com.clean.stickman.core.Commands;
import com.clean.stickman.core.Debloat;
import com.clean.stickman.core.Lean;
import com.clean.stickman.core.Ota;
import com.clean.stickman.core.Originals;
import com.clean.stickman.core.ResultText;
import com.clean.stickman.core.cmd.DebloatCmds;
import com.clean.stickman.core.cmd.OtaCmds;

import java.util.List;

/**
 * Recommended Amazon extras to disable (one-press Disable all), the "Disabled by Stickman" undo list, storage
 * tools, the after-update check, the Fire OS update freeze and the advanced list.
 */
public class DebloatPage extends ApplyPage {
    static final long AUTO_READ_MS = 30000;
    static final String ADVANCED_WARNING = "These are Amazon's own system apps. Stickman already hides the ones it knows "
        + "are essential, but disabling an unknown one can break a feature (Alexa, the store, casting…). "
        + "Everything you disable here can be turned back on under Disabled by Stickman or with Restore all.";

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

    @Override public String subtitle() { return "Switch off Amazon's extras. Everything can be undone."; }

    @Override public android.view.View build(MainActivity act) {
        LinearLayout l = start(act);
        LinearLayout top = Theme.row(a);
        button(top, "Refresh list", v -> read());
        l.addView(top);

        LinearLayout rc = card(l, "Recommended", "Amazon extras that are safe to switch off: ads, promos, usage reporting "
            + "and leftovers. Home, Settings, updates, remotes and Net Filter can never be disabled here.");
        disableAll = button(rc, "Disable all…", v -> confirmDisableAll());
        recBox = Theme.column(a);
        rc.addView(recBox);

        LinearLayout ours = card(l, "Disabled by Stickman", "Your undo list. If a feature stops working, turn its app back "
            + "on here: it's instant and nothing else changes.");
        oursBox = Theme.column(a);
        ours.addView(oursBox);
        // the text is split after "settings": the command-registry lint reads that word plus a lowercase one as a command
        restoreAll = button(ours, "Restore all…", v -> ThemedDialog.confirm(a, "Restore all?",
            "Turn every app Stickman disabled back on? Other settings" + " stay as they are.", "Restore",
            () -> send(DebloatCmds.thenRead(DebloatCmds.restoreAll(Originals.FILE)), "restore", "Restoring apps…")));

        LinearLayout st = card(l, "Storage", "Gets space back without changing what's installed or set up.");
        LinearLayout str = Theme.row(a);
        button(str, "Free up space…", v -> ThemedDialog.confirm(a, "Free up space?",
            "Amazon keeps downloading updates for apps even after they're disabled. This removes those updates from the "
            + "apps Stickman disabled, back to their small built-in version (they stay disabled).\n\nIf you turn one back "
            + "on later, the Appstore updates it again.", "Free up space",
            () -> { freeAt = System.currentTimeMillis(); send(DebloatCmds.freeSpaceDetached(), "freeing", "Freeing space…"); }));
        button(str, "Clear app caches…", v -> ThemedDialog.confirm(a, "Clear app caches?",
            "Deletes every app's cache files (thumbnails, downloaded pages) and trims the storage. Apps rebuild their "
            + "caches as you use them; nothing you set up is lost.", "Clear caches",
            () -> { clearAt = System.currentTimeMillis(); send(DebloatCmds.clearCachesDetached(), "clearing", "Clearing caches…"); }));
        st.addView(str);

        LinearLayout un = card(l, "Uninstall Amazon apps", "Amazon lets you remove a few of its apps for real. Stickman "
            + "opens Amazon's own page for the app: press Uninstall there. Only a tiny built-in placeholder stays, and you "
            + "can get the app back from the Appstore.");
        LinearLayout unr = Theme.row(a);
        button(unr, "Show apps…", v -> send(DebloatCmds.AMAZON_LIST_CMD, "amzlist", "Reading Amazon's list…"));
        button(unr, "Downloaded updates…", v -> send(DebloatCmds.UPDATES_LIST_CMD, "updlist", "Reading Amazon's app updates…"));
        un.addView(unr);
        un.addView(Theme.text(a, "Downloaded updates: other Amazon apps keep a full downloaded copy on top of their small "
            + "built-in one. For apps that are switched off, Stickman can remove that copy.", 13, Theme.MUTED));

        LinearLayout ck = card(l, "Check after an update", "Fire OS updates can quietly switch disabled apps back on and "
            + "undo Keep asleep / Starve. This finds anything that came back and offers to switch it off again.");
        button(ck, "Check now", v -> send(com.clean.stickman.core.Starve.checkScript(), "check", "Checking…"));

        LinearLayout oc = card(l, "Fire OS system updates", "Freeze stops Fire OS system updates until you unfreeze. "
            + "Fire OS protects its updater, so Stickman suspends it instead of disabling it and re-applies the freeze "
            + "at boot. Appstore app updates are unaffected.");
        otaStatus = Theme.text(a, "Checking…", 15, Theme.MUTED);
        oc.addView(otaStatus);
        otaBtn = button(oc, "Freeze updates…", v -> confirmOta());

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
        send(DebloatCmds.READ_CMD, "read", "Reading the app list…");
    }

    /** The app whose Amazon page Stickman opened; on return the page says whether it was removed. */
    String amzOpened, amzOpenedLabel;
    long amzOpenedKb;

    @Override public void onShow() {
        appLabels.clear();   // apps may have been installed/updated since the last show
        if (a == null || Bridge.otherVpnActive(a)) return;
        if (amzOpened != null) send(DebloatCmds.AMAZON_LIST_CMD, "amzcheck", "Checking " + amzOpenedLabel + "…");
        if (System.currentTimeMillis() - readAt > AUTO_READ_MS) read();
        if (otaFrozen == null || System.currentTimeMillis() - otaReadAt > AUTO_READ_MS)
            send(OtaCmds.state(), "otastate", "Checking the update state…");
    }

    void render() {
        boolean had = a.focusInPage();
        renderRows();
        renderOta();
        a.keepFocusInPage(had);   // removing the focused row must not throw focus to the sidebar
    }

    /** Update-freeze card: status line + the Freeze/Unfreeze button label. */
    void renderOta() {
        if (otaStatus == null || otaBtn == null) return;
        if (otaFrozen == null) {
            otaStatus.setText("Checking…");
            otaStatus.setTextColor(Theme.MUTED);
            otaBtn.setText("Freeze updates…");
        } else if (otaFrozen) {
            otaStatus.setText("Frozen: Fire OS system updates are blocked until you unfreeze.");
            otaStatus.setTextColor(Theme.ACCENT);
            otaBtn.setText("Unfreeze updates…");
        } else {
            otaStatus.setText("Allowed: Fire OS can download and install system updates.");
            otaStatus.setTextColor(Theme.MUTED);
            otaBtn.setText("Freeze updates…");
        }
    }

    void confirmOta() {
        if (Boolean.TRUE.equals(otaFrozen)) {
            ThemedDialog.confirm(a, "Allow Fire OS updates again?",
                "Fire OS will be able to download and install system updates again. App updates are not affected.",
                "Unfreeze", () -> {
                    Store.setOtaFrozen(a, false);
                    send(OtaCmds.thawThenState(), "otathaw", "Unfreezing…");
                });
        } else {
            ThemedDialog.confirm(a, "Freeze system updates?",
                "Stops Fire OS from downloading or installing system updates until you unfreeze. Apps still update "
                + "from the Appstore.\n\nFire OS protects its updater, so Stickman suspends it instead of disabling it. "
                + "The freeze is re-applied at boot.", "Freeze", () -> {
                    Store.setOtaFrozen(a, true);
                    send(OtaCmds.freezeThenState(), "otafreeze", "Freezing updates…");
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

        if (model.disabledByUs.isEmpty()) oursBox.addView(Theme.text(a, "None: nothing has been disabled.", 14, Theme.MUTED));
        for (final String pkg : model.disabledByUs) {
            LinearLayout row = Theme.row(a);
            final String lb = label(pkg);
            row.addView(Theme.text(a, lb, 15, Theme.TEXT), new LinearLayout.LayoutParams(0, -2, 1f));
            button(row, "Re-enable", v -> confirmEnable(pkg, lb));
            oursBox.addView(row);
        }

        if (advShown) {
            List<String> adv = model.advanced();
            if (adv.isEmpty()) advBox.addView(Theme.text(a, "No other Amazon apps.", 14, Theme.MUTED));
            else advBox.addView(Theme.text(a, adv.size() + " apps, A–Z. Disable asks first; you can turn any of "
                + "them back on under Disabled by Stickman.", 14, Theme.MUTED));
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

    /** Name for a disabled package: the catalogue's, else the installed app's label, else the package itself. */
    String label(String pkg) {
        String l = Catalog.label(pkg);
        if (l != null) return l;
        l = appLabel(pkg);
        return l == null || l.trim().isEmpty() ? pkg : l;
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
        final String cmd = cmdAndRead(DebloatCmds.enableOne(pkg, Originals.FILE));
        if (cmd == null) return;
        ThemedDialog.confirm(a, "Turn " + label + " back on?",
            "It goes back to how Amazon shipped it. You can disable it again from Recommended or Advanced.\n\n" + pkg,
            "Re-enable", () -> send(cmd, "enabled:" + label, "Turning " + label + " back on…"));
    }

    static String cmdAndRead(String cmd) { return DebloatCmds.thenRead(cmd); }

    /** Says what the app does (when the catalogue knows) and how to undo; the package name comes last. */
    void confirmDisable(final String pkg, String label) {
        final String cmd = cmdAndRead(Commands.debloat(pkg, true));
        if (cmd == null) { say(label + " is protected and can't be disabled."); return; }
        String why = Catalog.why(pkg);
        ThemedDialog.confirm(a, "Disable " + label + "?",
            (why == null ? "" : why + ".\n\n") + "You can turn it back on any time under \"Disabled by Stickman\" or with "
            + "Restore all.\n\n" + pkg, "Disable",
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
            + "\n\nYou can turn any of them back on under \"Disabled by Stickman\" or with Restore all.",
            "Disable all", () -> send(cmd, "disabledall", "Disabling " + ResultText.apps(rec.size()) + "…"));
    }

    /** When Free up space / Clear caches was started (0 = not waiting for its STATUS line). */
    long freeAt, clearAt;

    @Override public void onTick() {
        if (a == null) return;
        if (freeAt != 0) {
            String st = LogTail.status("freed");
            if (st != null && LogTail.statusAt("freed") >= freeAt - 1000) {
                freeAt = 0;
                long[] f = Lean.parseFreed(st);
                say(f == null ? "Free up space: " + st : f[1] == 0 ? "Nothing to free: none of the disabled apps have updates stored."
                    : "Freed " + Math.max(0, f[0] / 1024) + " MB from " + ResultText.apps(f[1]) + ".");
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
                say(kb == null ? "Clear caches: " + st : kb <= 0 ? "Caches were already clean: nothing to clear."
                    : "Cleared " + (kb < 1024 ? "less than 1" : Long.toString(kb / 1024)) + " MB of app caches and trimmed the storage.");
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
            sb.append("• ").append(label(d[1])).append(": ").append(what).append('\n');
        }
        if (fix == null) return dr.size() + " item(s) came back, but they're protected now, so they were left alone.";
        ThemedDialog.confirm(a, dr.size() + " came back on", sb.toString().trim() + "\n\nSwitch them off again?", "Switch off",
            () -> send(DebloatCmds.thenRead(fix), "fixed", "Switching them off again…"));
        return dr.size() + " of " + n + " came back on.";
    }

    /** Amazon's uninstallable apps: removed ones are listed too, so the user sees the whole picture. */
    void showAmazonApps(String out) {
        final List<AmazonUninstall.App> apps = AmazonUninstall.parse(out);
        if (apps.isEmpty()) {
            say(AmazonUninstall.complete(out) ? "Amazon doesn't list any removable apps on this stick." : "Couldn't read Amazon's list.");
            return;
        }
        result.setText("");
        String[] rows = new String[apps.size()];
        for (int i = 0; i < rows.length; i++) {
            AmazonUninstall.App ap = apps.get(i);
            rows[i] = label(ap.pkg) + "  ·  " + (ap.installed() ? (ap.kb / 1024) + " MB" : "removed ✓");
        }
        new ThemedDialog(a).title("Amazon apps you can uninstall").items(rows, w -> {
            AmazonUninstall.App ap = apps.get(w);
            String lb = label(ap.pkg);
            if (!ap.installed()) { say(lb + " is already removed: only its tiny built-in placeholder is left."); return; }
            ThemedDialog.confirm(a, "Uninstall " + lb + "?", "Stickman opens Amazon's page for " + lb + ". Press Uninstall "
                + "there, confirm, then press Back to come back here.\n\nThis frees about " + (ap.kb / 1024) + " MB.",
                "Open the page", () -> {
                    amzOpened = ap.pkg; amzOpenedLabel = lb; amzOpenedKb = ap.kb;
                    send(AmazonUninstall.open(ap.pkg), "amzopen", "Opening Amazon's page for " + lb + "…");
                });
        }).cancel("Close").show();
    }

    /** Amazon apps with a downloaded update: off ones can drop it; on ones would re-download it, so offer to switch off first. */
    void showUpdates(String out) {
        final List<AmazonUninstall.Update> ups = AmazonUninstall.parseUpdates(out);
        if (ups.isEmpty()) { say("No Amazon app has a downloaded update to remove."); return; }
        result.setText("");
        String[] rows = new String[ups.size()];
        for (int i = 0; i < rows.length; i++) {
            AmazonUninstall.Update u = ups.get(i);
            rows[i] = label(u.pkg) + "  ·  " + (u.kb / 1024) + " MB  ·  "
                + (Catalog.isProtected(u.pkg) ? "part of Fire OS" : u.disabled ? "off: can remove" : "on");
        }
        new ThemedDialog(a).title("Downloaded app updates").items(rows, w -> {
            final AmazonUninstall.Update u = ups.get(w);
            final String lb = label(u.pkg);
            final String mb = (u.kb / 1024) + " MB";
            if (Catalog.isProtected(u.pkg)) { say(lb + " is part of Fire OS: Stickman leaves it alone."); return; }
            if (u.disabled) {
                ThemedDialog.confirm(a, "Remove " + lb + "'s update?", "Frees about " + mb + ". " + lb + " goes back to its small "
                    + "built-in version and stays off. If you turn it on again, the Appstore downloads the update again.",
                    "Remove", () -> send(AmazonUninstall.removeUpdate(u.pkg), "updremoved:" + lb + ":" + mb, "Removing " + lb + "'s update…"));
                return;
            }
            String why = Catalog.why(u.pkg);
            ThemedDialog.confirm(a, "Switch off " + lb + " and remove its update?", lb + " is on, so Amazon would download the "
                + "update again. Stickman can switch it off first, then remove the update (about " + mb + ")."
                + (why != null ? "\n\nWhat it does: " + why + "." : "") + "\n\nYou can turn it back on under Disabled by Stickman.",
                "Switch off & remove", () -> send(AmazonUninstall.switchOffAndRemove(u.pkg),
                    "updremoved:" + lb + ":" + mb, "Switching off " + lb + "…"));
        }).cancel("Close").show();
    }

    /** Back from Amazon's page: was the app removed? */
    String amazonChecked(String out) {
        String pkg = amzOpened, lb = amzOpenedLabel;
        long kb = amzOpenedKb;
        amzOpened = null;
        if (pkg == null) return null;
        for (AmazonUninstall.App ap : AmazonUninstall.parse(out)) {
            if (!ap.pkg.equals(pkg)) continue;
            return ap.installed() ? lb + " is still installed. Use Show apps… again to retry."
                : lb + " is removed ✓  About " + (kb / 1024) + " MB freed.";
        }
        return null;
    }

    @Override String describe(String kind, String out) {
        if (kind.equals("amzlist")) { showAmazonApps(out); return null; }
        if (kind.equals("updlist")) { showUpdates(out); return null; }
        if (kind.startsWith("updremoved:")) {
            String[] k = kind.split(":", 3);
            if (out != null && out.contains(Debloat.SEP)) { model = Debloat.parse(out); render(); }   // the undo list gains the app
            return out != null && out.contains("UPDATE_REMOVED") ? k[1] + "'s update is removed ✓  About " + k[2] + " freed."
                : "Couldn't remove " + k[1] + "'s update: " + firstLine(out);
        }
        if (kind.equals("amzcheck")) return amazonChecked(out);
        if (kind.equals("amzopen")) return "Press Uninstall on Amazon's page, then Back.";
        if (kind.equals("otastate") || kind.equals("otafreeze") || kind.equals("otathaw")) {
            otaFrozen = Ota.parseFrozen(out);
            otaReadAt = System.currentTimeMillis();
            renderOta();
            if (kind.equals("otastate")) return null;
            Store.setOtaFrozen(a, otaFrozen);   // the device is the truth, not what we asked for
            if (kind.equals("otafreeze")) return otaFrozen ? "Fire OS system updates are frozen." : "Couldn't freeze updates: " + firstLine(out);
            return otaFrozen ? "Unfreeze didn't take: " + firstLine(out) : "Fire OS system updates are allowed again.";
        }
        if (kind.equals("check")) return checked(out);
        if (out.contains(Debloat.SEP)) { model = Debloat.parse(out); render(); }
        if (kind.equals("read")) { result.setText(""); return null; }
        if (kind.equals("restore")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("RESTORED (\\d+)").matcher(out);
            return m.find() ? "Turned " + ResultText.apps(Long.parseLong(m.group(1))) + " back on." : "Restored.";
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
            return m.find() ? "Switched off " + ResultText.apps(Long.parseLong(m.group(1))) + ". Any of them can be turned back on under Disabled by Stickman."
                : "Done.";
        }
        if (kind.startsWith("disabled:")) {
            String p = kind.substring(9);
            return out.contains("disabled-user") ? p + " is off. Turn it back on under Disabled by Stickman if you miss it." : p + ": " + firstLine(out);
        }
        if (kind.startsWith("enabled:")) return kind.substring(8) + " is back on.";
        return out;
    }

    static String firstLine(String s) {
        int n = s.indexOf('\n');
        return n < 0 ? s : s.substring(0, n);
    }
}
