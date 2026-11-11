package com.clean.stickman;

import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.clean.stickman.core.Actions;
import com.clean.stickman.core.BgTime;
import com.clean.stickman.core.BootStart;
import com.clean.stickman.core.Catalog;
import com.clean.stickman.core.Commands;
import com.clean.stickman.core.Lean;
import com.clean.stickman.core.LiveValues;
import com.clean.stickman.core.ResultText;
import com.clean.stickman.core.StickdConf;
import com.clean.stickman.core.Starve;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Boost / Trim, the one-press recommended setup, lean memory, auto-close (stickd.conf), keep-asleep apps and
 * animation speed. Every result line says what actually happened ("Nothing to close", "closed 2 apps").
 */
public class SpeedPage extends ApplyPage {
    static final int[] INTERVALS = { 0, 5, 15, 30, 60 };
    static final float[] ANIM = { 1f, 0.5f, 0f };
    /** The chips for {@link #ANIM}: plain words instead of "0.5×". */
    static final String[] ANIM_LABELS = { "Normal", "Fast", "Off" };
    static final long BOOST_TIMEOUT_MS = 20000;
    static final String NO_ANSWER = "No answer from the stick within 20 s. Check the Status page: is background automation running?";
    static final String READING = "Reading the current settings…";

    static final String BOOT_SEP = "---stickman-asleep---";
    static final int[] TRIM_MIN = { 0, 0, 15, 60 };
    static final String[] TRIM_LABELS = { "Off", "When the TV sleeps", "Every 15 min", "Every hour" };
    static final String[] TRIM_SAID = { "Automatic trim off.", "Idle apps are trimmed whenever the TV goes to sleep.",
        "Idle apps are trimmed every 15 minutes.", "Idle apps are trimmed every hour." };

    Button[] onLeave, interval, anim, lean, doze, cpu, quiet, trimAuto;
    TextView boostInfo, lists, animNow, leanNow;
    /** When the live animation scale was last read (0 = not yet). */
    long readAt;
    /** When Boost / Trim was pressed (0 = not waiting for its STATUS line). */
    long boostAt, trimAt;
    /** Last LEAN read: the level and whether the CPU limit is on (both live in one Android setting). */
    int leanLevel;
    boolean cpuOn;
    /** What to say when the pending stickd.conf save completes (null = a plain "Saved"). */
    String savedMsg;
    /** Bridge commands of a running "Use recommended settings" still to answer (0 = none). */
    int recPending;

    @Override public String title() { return "Speed"; }

    @Override public String subtitle() { return "Boost now, keep memory free, and stop apps running behind your back."; }

    @Override public android.view.View build(MainActivity act) {
        LinearLayout l = start(act);

        LinearLayout b = card(l, "Boost", "Feels slow? Boost closes the apps you've left open in the background and trims "
            + "memory. The app you're using, home, anything playing and your never-close apps are left alone.");
        LinearLayout br = Theme.row(a);
        button(br, "Boost now", v -> boost());
        button(br, "Trim memory", v -> trim());
        b.addView(br);
        b.addView(Theme.text(a, "Trim closes nothing: every app that isn't in front just gives back the memory it "
            + "doesn't need (Amazon's services included).", 13, Theme.MUTED));
        boostInfo = Theme.autoHide(Theme.text(a, "", 15, Theme.TEXT));
        b.addView(boostInfo);

        LinearLayout lm = card(l, "Lean memory", "Keeps the stick fast between Boosts: Android holds fewer idle apps in "
            + "memory, so what you're watching gets the RAM. Balanced suits most sticks; Max frees more, but apps you "
            + "left take longer to reopen.");
        button(lm, "Use recommended settings…", v -> recommended());
        lm.addView(Theme.text(a, "Idle apps kept in memory", 14, Theme.MUTED));
        lean = choices(lm, new String[] { "Off", "Balanced", "Max" }, i ->
            send(Lean.memory(i, cpuOn), "lean:" + i, "Setting lean memory…"));
        lm.addView(Theme.text(a, "Deep sleep while the TV is off: background apps rest until you turn it on again", 14, Theme.MUTED));
        doze = choices(lm, new String[] { "On", "Off" }, i ->
            send(i == 0 ? Lean.dozeOn() : Lean.dozeOff(), "doze:" + i, "Setting deep sleep…"));
        lm.addView(Theme.text(a, "Background CPU limit: an app that keeps the stick busy while you're not using it is closed", 14, Theme.MUTED));
        cpu = choices(lm, new String[] { "On", "Off" }, i ->
            send(Lean.cpuLimit(Math.max(leanLevel, 0), i == 0), "cpu:" + i, "Setting the CPU limit…"));
        lm.addView(Theme.text(a, "Fewer wake-ups: background apps get to wake the stick less often (some of their updates arrive a little later)", 14, Theme.MUTED));
        quiet = choices(lm, new String[] { "On", "Off" }, i ->
            send(Lean.quiet(i == 0), "quiet:" + i, "Setting wake-ups…"));
        lm.addView(Theme.text(a, "Trim idle apps automatically (nothing is closed)", 14, Theme.MUTED));
        trimAuto = choices(lm, TRIM_LABELS, i -> {
            StickdConf c = conf();
            c.trimOnSleep = i == 1;
            c.trimIntervalMin = TRIM_MIN[i];
            save(c, TRIM_SAID[i]);
        });
        leanNow = Theme.autoHide(Theme.text(a, "", 14, Theme.MUTED));
        lm.addView(leanNow);

        LinearLayout k = card(l, "Auto-close apps", "Closes the apps you've left behind 10 s after you switch to another one, "
            + "so they don't sit in memory. Home, anything playing and your never-close apps are left alone.");
        k.addView(Theme.text(a, "Close apps when I leave them", 14, Theme.MUTED));
        onLeave = choices(k, new String[] { "On", "Off" }, i -> {
            StickdConf c = conf();
            if (c.onLeave == (i == 0)) return;
            c.onLeave = i == 0;
            save(c, i == 0 ? "Auto-close on: the apps you leave are closed after 10 s." : "Auto-close off: apps stay open until you close them.");
        });
        k.addView(Theme.text(a, "Also close background apps every…", 14, Theme.MUTED));
        String[] il = new String[INTERVALS.length];
        for (int i = 0; i < il.length; i++) il[i] = INTERVALS[i] == 0 ? "Off" : INTERVALS[i] + " min";
        interval = choices(k, il, i -> {
            StickdConf c = conf();
            if (c.intervalMin == INTERVALS[i]) return;
            c.intervalMin = INTERVALS[i];
            save(c, INTERVALS[i] == 0 ? "Timed closing off." : "Background apps are also closed every " + INTERVALS[i] + " minutes.");
        });
        LinearLayout r = Theme.row(a);
        button(r, "Never close…", v -> pickList(true));
        button(r, "Always kill…", v -> pickList(false));
        k.addView(r);
        lists = Theme.text(a, "", 14, Theme.MUTED);
        k.addView(lists);

        LinearLayout s = card(l, "Keep asleep / Starve", "Stops an app from running behind your back. Asleep: it can't "
            + "start in the background until you open it. Starve: it also can't keep the stick awake or dodge being "
            + "closed, and it's closed now if it's in the background.");
        s.addView(Theme.text(a, "Pick an app from…", 14, Theme.MUTED));
        LinearLayout sr = Theme.row(a);
        button(sr, "Background apps…", v -> send(BgTime.CMD, "bg", "Reading the last " + BgTime.HOURS + " hours…"));
        button(sr, "Starts at boot…", v -> send(BootStart.CMD + "; echo " + BOOT_SEP + "; " + Starve.LIST_CMD, "boot", "Reading boot apps…"));
        s.addView(sr);
        LinearLayout sr2 = Theme.row(a);
        button(sr2, "Your apps…", v -> pickAsleep());
        if (!com.clean.stickman.core.Edition.STORE)
            button(sr2, "Amazon services…", v -> send("pm list packages -e com.amazon", "svc", "Reading Amazon services…"));
        s.addView(sr2);
        s.addView(Theme.text(a, "Background apps: who ran behind your back over the last " + BgTime.HOURS + " hours, busiest "
            + "first. Starts at boot: apps that launch themselves when the stick starts.", 13, Theme.MUTED));
        button(s, "Wake an app…", v -> send(Starve.LIST_CMD, "list", "Reading…"));
        s.addView(Theme.text(a, "Changed your mind? Wake an app lists what's asleep or starved and puts it back to normal.", 13, Theme.MUTED));

        LinearLayout an = card(l, "Animation speed", "Fast makes menus feel snappier; Off skips the animations entirely.");
        anim = choices(an, ANIM_LABELS, i ->
            send(Commands.animation(ANIM[i]), "anim:" + i, "Setting animations…"));
        animNow = Theme.autoHide(Theme.text(a, "", 14, Theme.MUTED));
        an.addView(animNow);

        refresh();
        return Theme.scroll(a, l);
    }

    StickdConf conf() { return StickdConf.parse(Store.stickdText(a)); }

    /** Saves the edited stickd.conf; msg (plain words) is shown once the stick has it, instead of a bare "Saved". */
    void save(StickdConf c, String msg) {
        savedMsg = msg;
        applyStickdConf(c.serialize(), "conf");
        refresh();
    }

    void refresh() {
        StickdConf c = conf();
        select(onLeave, c.onLeave ? 0 : 1);
        int sel = 0;
        for (int i = 0; i < INTERVALS.length; i++) if (INTERVALS[i] == c.intervalMin) sel = i;
        select(interval, sel);
        select(trimAuto, c.trimIntervalMin >= 60 ? 3 : c.trimIntervalMin > 0 ? 2 : c.trimOnSleep ? 1 : 0);
        lists.setText("Never close ★: " + names(c.whitelist) + "\nAlways kill ⚡: " + names(c.blacklist));
        select(anim, pref("anim", -1));
        if (pref("anim", -1) < 0 && animNow.length() == 0) animNow.setText("Current: unknown");
    }

    /** Live animation scale + lean state (read-only, once per page show); the stored choice stays the fallback. */
    void readLive() {
        if (Bridge.otherVpnActive(a) || Bridge.busy && readAt != 0) return;
        readAt = System.currentTimeMillis();
        if (leanNow.length() == 0 && !anySelected(lean)) leanNow.setText(READING);   // the chips show nothing until it answers
        String cmd = LiveValues.ANIM_READ + "; " + Lean.READ;
        mine.put(cmd, "live");
        Bridge.run(a, cmd, false);
    }

    static boolean anySelected(Button[] bs) {
        for (Button b : bs) if (b.isSelected()) return true;
        return false;
    }

    /** Selects the Lean memory / deep sleep chips from a LEAN line (no chip when the stick has other values). */
    void showLean(String out) {
        int[] v = Lean.parse(out);
        if (v == null) return;
        leanLevel = v[0];
        cpuOn = v.length > 2 && v[2] == 1;
        select(lean, v[0]);
        select(doze, v[1] == 1 ? 0 : 1);
        select(cpu, cpuOn ? 0 : 1);
        select(quiet, v.length > 3 && v[3] == 1 ? 0 : 1);
        leanNow.setText(v[0] < 0 ? "Current: custom values set outside Stickman" : "");
    }

    void showLive(String out) {
        showLean(out);
        String v = LiveValues.value(out, "ANIM");
        int i = LiveValues.indexOfScale(ANIM, v);
        // the live value wins over the stored choice: no chip matches -> none selected, say what it is
        if (i >= 0) { putPref("anim", i); select(anim, i); animNow.setText(""); }
        else { select(anim, -1); animNow.setText("Current: " + LiveValues.describeScale(v)); }
    }

    String names(Set<String> pkgs) {
        if (pkgs.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) sb.append(sb.length() > 0 ? ", " : "").append(ButtonEditor.appLabel(a, p));
        return sb.toString();
    }

    /** Installed launchable apps, minus Stickman; minus every protected package (Net Filter, home, ...) when asked. */
    List<String[]> apps(boolean dropProtected) {
        List<String[]> out = new ArrayList<>();
        for (String[] app : ButtonEditor.launchables(a)) {
            if (app[1].equals(a.getPackageName())) continue;
            if (dropProtected && Catalog.isProtected(app[1])) continue;
            out.add(app);
        }
        return out;
    }

    /** Keep-asleep / Starve picker: protected packages (Net Filter's proxy, home, Stickman, updates, ...) are never offered. */
    void pickAsleep() { pickFrom(apps(true), "Keep which app asleep?"); }

    void pickFrom(final List<String[]> apps, String title) { pickFrom(apps, null, title); }

    /** rows: what each entry shows (null = its name); apps[i] = {name, pkg}. */
    void pickFrom(final List<String[]> apps, String[] rows, String title) {
        if (apps.isEmpty()) { say("Couldn't list the installed apps."); return; }
        String[] names = new String[apps.size()];
        for (int i = 0; i < names.length; i++) names[i] = rows != null ? rows[i] : apps.get(i)[0];
        new ThemedDialog(a).title(title).items(names, w -> chooseLevel(apps.get(w))).cancel("Cancel").show();
    }

    /** Boot-start apps with their asleep/starved state; choosing one offers Keep asleep / Starve. */
    void pickBoot(String out) {
        int s = out.indexOf(BOOT_SEP);
        List<String> pkgs = BootStart.parse(s < 0 ? out : out.substring(0, s));
        Map<String, Boolean> st = Starve.parseList(s < 0 ? "" : out.substring(s + BOOT_SEP.length()));
        result.setText("");
        if (pkgs.isEmpty()) { say("No app starts itself at boot."); return; }
        List<String[]> apps = new ArrayList<>();
        String[] rows = new String[pkgs.size()];
        for (int i = 0; i < rows.length; i++) {
            String p = pkgs.get(i), n = ButtonEditor.appLabel(a, p);
            apps.add(new String[] { n, p });
            Boolean b = st.get(p);
            rows[i] = n + (b == null ? "" : b ? "  · starved" : "  · asleep");
        }
        pickFrom(apps, rows, ResultText.apps(pkgs.size()) + " start at boot: keep one asleep?");
    }

    /** Who ran in the background over the last hours, busiest first; choosing one offers Keep asleep / Starve. */
    void pickBackground(String out) {
        List<BgTime.Entry> es = BgTime.parse(out);
        result.setText("");
        if (es.isEmpty()) { say("Nothing ran in the background worth mentioning in the last " + BgTime.HOURS + " hours."); return; }
        List<String[]> apps = new ArrayList<>();
        String[] rows = new String[es.size()];
        for (int i = 0; i < rows.length; i++) {
            BgTime.Entry e = es.get(i);
            String n = ButtonEditor.appLabel(a, e.pkg);
            apps.add(new String[] { n, e.pkg });
            rows[i] = n + "  · " + e.pct + "% of the time" + (e.avgMb > 0 ? "  · ~" + e.avgMb + " MB" : "");
        }
        pickFrom(apps, rows, "Busiest in the background, last " + BgTime.HOURS + " hours");
    }

    /** Keep asleep (gentle, enough for most apps) first; Starve is the stronger option; Cancel keeps the focus. */
    void chooseLevel(final String[] app) {
        new ThemedDialog(a).title(app[0])
            .message("Keep asleep: " + app[0] + " can't start in the background until you open it. Enough for most apps.\n\n"
                + "Starve: it also can't keep the stick awake or run a background service to dodge being closed, and it's "
                + "closed now if it's in the background. Its notifications, downloads or Alexa features may stop working.\n\n"
                + "Core Amazon services that Fire OS keeps alive ignore this. Undo it with Wake an app.")
            .button("Keep asleep", () -> send(Starve.apply(app[1], false), "asleep:" + app[0], "Putting " + app[0] + " to sleep…"))
            .button("Starve", () -> send(Starve.apply(app[1], true), "starved:" + app[0], "Starving " + app[0] + "…"))
            .cancel("Cancel").focusButton(2).show();
    }

    /** Enabled Amazon packages minus protected ones, labelled by app name (falls back to the package). */
    void pickService(String out) {
        List<String[]> apps = new ArrayList<>();
        for (String line : out.split("\n")) {
            String p = line.trim();
            if (!p.startsWith("package:")) continue;
            p = p.substring(8);
            if (!ButtonEditor.validPkg(p) || Catalog.isProtected(p)) continue;
            String n = ButtonEditor.appLabel(a, p);
            if (n.equals(p) && Catalog.label(p) != null) n = Catalog.label(p);
            apps.add(new String[] { n.equals(p) ? p : n + "  (" + p + ")", p });
        }
        java.util.Collections.sort(apps, (x, y) -> x[0].compareToIgnoreCase(y[0]));
        result.setText("");
        pickFrom(apps, "Which Amazon service?");
    }

    /** Multi-choice picker for the whitelist (any app) or the always-kill blacklist (protected apps excluded). */
    void pickList(final boolean white) {
        final List<String[]> apps = apps(!white);
        if (apps.isEmpty()) { say("Couldn't list the installed apps."); return; }
        final StickdConf c = conf();
        final Set<String> set = white ? c.whitelist : c.blacklist;
        String[] names = new String[apps.size()];
        final boolean[] on = new boolean[apps.size()];
        for (int i = 0; i < names.length; i++) { names[i] = apps.get(i)[0]; on[i] = set.contains(apps.get(i)[1]); }
        new ThemedDialog(a).title(white ? "Never close these apps ★" : "Always kill these apps ⚡")
            .message(white ? "Boost, auto-close and End all leave them alone."
                : "Closed every time you leave them, even with auto-close off.")
            .multi(names, on)
            .button("Save", () -> {
                set.clear();
                for (int i = 0; i < on.length; i++) if (on[i]) set.add(apps.get(i)[1]);
                if (!white) for (java.util.Iterator<String> it = set.iterator(); it.hasNext(); ) if (Catalog.isProtected(it.next())) it.remove();   // removeIf is API 24
                if (white) c.blacklist.removeAll(set); else c.whitelist.removeAll(set);
                save(c, (white ? "Never close ★: " : "Always kill ⚡: ") + names(set));
            })
            .cancel("Cancel").show();
    }

    /** One press for the sensible defaults, listed in plain words; Apply is focused: nothing is closed or disabled. */
    void recommended() {
        StringBuilder sb = new StringBuilder();
        for (String s : Lean.RECOMMENDED_STEPS) sb.append("•  ").append(s).append('\n');
        new ThemedDialog(a).title("Use recommended settings?")
            .message(sb + "\nNothing is closed or disabled, and every setting can be changed back below.")
            .button("Apply", this::applyRecommended).cancel("Cancel").focusButton(0).show();
    }

    /** The chips' own commands, queued as one apply (so another VPN is asked about once); the last answer reports. */
    void applyRecommended() {
        final String[] cmds = Lean.recommended();
        final StickdConf c = conf();
        if (c.trimIntervalMin == 0) c.trimOnSleep = true;   // a timed trim already covers sleep: keep it
        final List<String> all = new ArrayList<>();
        for (int i = 0; i < cmds.length; i++) { mine.put(cmds[i], "rec:" + i); all.add(cmds[i]); }
        if (Store.enabled(a)) { mine.put(Ensure.MARKER, "rec:conf"); all.add(Ensure.MARKER); }
        recPending = all.size();
        a.run(() -> {
            Store.saveStickdText(a, c.serialize());
            refresh();
            result.setText("Applying the recommended settings…");
            pendingSince = System.currentTimeMillis();
            h.removeCallbacks(timeout);
            h.postDelayed(timeout, PENDING_TIMEOUT_MS);
        }, all.toArray(new String[0]));
    }

    /** Boost closes apps: ask first (focus on Cancel). */
    void boost() {
        if (!stickdOn()) return;
        ThemedDialog.confirm(a, "Boost now?", "Close the apps you've left open in the background and trim memory? The app "
            + "you're using, home, anything playing and your never-close apps are left alone.", "Boost", this::boostNow);
    }

    void boostNow() {
        boostAt = System.currentTimeMillis();
        boostInfo.setText("Boosting…");
        send(Commands.act(Actions.BOOST, null), "boost", "Boosting…");
    }

    /** Trim closes nothing: no confirmation needed. */
    void trim() {
        if (!stickdOn()) return;
        trimAt = System.currentTimeMillis();
        boostInfo.setText("Trimming…");
        send(Commands.act(Actions.TRIM, null), "trim", "Trimming…");
    }

    @Override public void onShow() {
        if (a == null) return;
        refresh();
        readLive();
    }

    @Override public void onTick() {
        if (a == null) return;
        long now = System.currentTimeMillis();
        if (boostAt != 0) {
            String st = LogTail.status("boost");
            if (st != null && LogTail.statusAt("boost") >= boostAt - 1000) {
                boostAt = 0;
                String s = ResultText.boost(st);
                say(s != null ? s : "Boost done: " + st);
                boostInfo.setText(result.getText());
            } else if (now - boostAt > BOOST_TIMEOUT_MS && !Bridge.busy) {
                boostAt = 0;
                boostInfo.setText(NO_ANSWER);
                result.setText(NO_ANSWER);
            }
        }
        if (trimAt != 0) {
            String st = LogTail.status("trim");
            if (st != null && LogTail.statusAt("trim") >= trimAt - 1000) {
                trimAt = 0;
                String s = ResultText.trim(st);
                say(s != null ? s : "Trim done: " + st);
                boostInfo.setText(result.getText());
            } else if (now - trimAt > BOOST_TIMEOUT_MS && !Bridge.busy) {
                trimAt = 0;
                boostInfo.setText(NO_ANSWER);
                result.setText(NO_ANSWER);
            }
        }
        // a connect failure gives no per-command answer: don't say "Reading…" forever
        if (readAt != 0 && now - readAt > PENDING_TIMEOUT_MS && !Bridge.busy && READING.contentEquals(leanNow.getText()))
            leanNow.setText("Couldn't read the current settings. Choosing a chip sets it anyway.");
    }

    @Override public void onResult(String cmd, String output, boolean ok) {
        boolean live = "live".equals(mine.get(cmd));
        super.onResult(cmd, output, ok);
        if (live && !ok && READING.contentEquals(leanNow.getText()))
            leanNow.setText("Couldn't read the current settings. Choosing a chip sets it anyway.");
    }

    @Override String describe(String kind, String out) {
        if (kind.equals("live")) { showLive(out); return null; }
        if (kind.equals("boost") || kind.equals("trim")) return null;   // the STATUS line reports the outcome
        if (kind.startsWith("rec:")) {
            if (!kind.equals("rec:conf")) showLean(out);
            if (--recPending > 0) return null;
            recPending = 0;
            return "Recommended settings applied ✓";
        }
        if (kind.startsWith("cpu:")) {
            showLean(out);
            return kind.endsWith("0") ? "Background CPU limit on: an app hogging the CPU in the background is closed within a minute."
                : "Background CPU limit off: Android's defaults are back.";
        }
        if (kind.startsWith("quiet:")) {
            showLean(out);
            return kind.endsWith("0") ? "Fewer wake-ups on: background apps may wake the stick at most every 15 minutes."
                : "Wake-ups back to Android's defaults.";
        }
        if (kind.equals("boot")) { pickBoot(out); return null; }
        if (kind.equals("bg")) { pickBackground(out); return null; }
        if (kind.equals("conf")) {
            String m = savedMsg != null ? savedMsg : "Saved ✓";
            savedMsg = null;
            return Store.pid(out, "STICKD") != null ? m : "Saved, but background automation isn't running (see the Status page).";
        }
        if (kind.startsWith("anim:")) {
            int i = Integer.parseInt(kind.substring(5));
            putPref("anim", i);
            select(anim, i);
            animNow.setText("");
            return "Animations: " + ANIM_LABELS[i] + ".";
        }
        if (kind.startsWith("lean:")) {
            showLean(out);
            int i = Integer.parseInt(kind.substring(5));
            return i == 0 ? "Lean memory off: Android's defaults are back."
                : "Lean memory: " + (i == 1 ? "Balanced" : "Max") + ". Android keeps up to " + Lean.CAP[i] + " idle apps and closes the rest.";
        }
        if (kind.startsWith("doze:")) {
            showLean(out);
            return kind.endsWith("0") ? "Deep sleep on: background apps rest while the TV is off (Net Filter and Stickman keep working)."
                : "Deep sleep off.";
        }
        if (kind.equals("svc")) { pickService(out); return null; }
        if (kind.startsWith("asleep:")) return kind.substring(7) + " is kept asleep: it won't start in the background until you open it.";
        if (kind.startsWith("starved:")) return kind.substring(8) + " is starved: it can't run in the background until you open it.";
        if (kind.startsWith("awake:")) return kind.substring(6) + " runs normally again.";
        if (kind.equals("list")) { showAsleep(out); return null; }
        return out;
    }

    /** Asleep & starved apps (from the originals journal); choosing one lets it run normally again. */
    void showAsleep(String out) {
        Map<String, Boolean> m = Starve.parseList(out);
        // Amazon's reporting apps are quieted from the Privacy page: not "apps" the user put to sleep here
        int reporting = 0;
        for (String p : com.clean.stickman.core.Telemetry.packages()) if (m.remove(p) != null) reporting++;
        if (m.isEmpty()) {
            say(reporting > 0 && !com.clean.stickman.core.Edition.STORE ? "No apps are kept asleep or starved (Amazon's reporting apps are quiet: see Privacy)."
                : "No apps are kept asleep or starved.");
            return;
        }
        result.setText("");
        final List<String> pkgs = new ArrayList<>(m.keySet());
        final String[] names = new String[pkgs.size()];
        for (int i = 0; i < names.length; i++) names[i] = ButtonEditor.appLabel(a, pkgs.get(i));
        String[] rows = new String[names.length];
        for (int i = 0; i < rows.length; i++) rows[i] = names[i] + (m.get(pkgs.get(i)) ? "  · starved" : "  · asleep");
        new ThemedDialog(a).title("Asleep & starved: choose one to wake it").items(rows, w -> {
            final String n = names[w];
            final String pkg = pkgs.get(w);
            ThemedDialog.confirm(a, "Wake " + n + "?", n + " will be allowed to run in the background again.", "Wake it",
                () -> send(Starve.wake(pkg), "awake:" + n, "Letting " + n + " run normally…"));
        }).cancel("Close").show();
    }
}
