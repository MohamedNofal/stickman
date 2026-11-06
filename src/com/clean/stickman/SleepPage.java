package com.clean.stickman;

import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.clean.stickman.core.Actions;
import com.clean.stickman.core.Commands;
import com.clean.stickman.core.LiveValues;
import com.clean.stickman.core.StickdConf;

/** Screensaver, sleep / screen-off timeouts, stay-awake-for-media, the sleep timer and screen off. */
public class SleepPage extends ApplyPage {
    static final String[] SLEEP_LABELS = { "Never", "15 min", "30 min", "60 min", "2 h" };
    /** secure sleep_timeout (ms); -1 = never. */
    static final long[] SLEEP_MS = { -1, 15 * 60000L, 30 * 60000L, 60 * 60000L, 120 * 60000L };
    static final String[] OFF_LABELS = { "5 min", "10 min", "15 min", "30 min", "Never" };
    /** system screen_off_timeout (ms); Integer.MAX_VALUE = never. */
    static final long[] OFF_MS = { 5 * 60000L, 10 * 60000L, 15 * 60000L, 30 * 60000L, Integer.MAX_VALUE };
    static final int[] TIMER_MIN = { 0, 15, 30, 60, 90 };

    Button[] saver, sleep, off, timer, media, cec;
    TextView timerInfo, saverNow, sleepNow, offNow, cecNow;
    long readAt;
    String timerShown;

    @Override public String title() { return "Sleep"; }

    @Override public String subtitle() { return "Sleep timer, screen off, and what the stick does when you stop watching."; }

    @Override public android.view.View build(MainActivity act) {
        LinearLayout l = start(act);

        LinearLayout t = card(l, "Sleep timer", "Pauses what's playing and puts the stick to sleep, with a gentle fade in the last minute.");
        timer = choices(t, new String[] { "Off", "15 min", "30 min", "60 min", "90 min" }, i -> {
            if (!stickdOn()) return;
            send(Commands.act(Actions.SLEEP_TIMER_SET, Integer.toString(TIMER_MIN[i])), "timer:" + i,
                i == 0 ? "Turning the sleep timer off…" : "Setting the sleep timer…");
        });
        timerInfo = Theme.autoHide(Theme.text(a, "", 14, Theme.TEXT));
        t.addView(timerInfo);

        LinearLayout so = card(l, "Screen off", "Blacks out the screen while audio keeps playing. Any button brings it back.");
        button(so, "Screen off now", v -> {
            if (stickdOn()) send(Commands.act(Actions.SCREEN_OFF, null), "screenoff", "Turning the screen off…");
        });

        LinearLayout m = card(l, "Stay awake while media plays", "Stops the stick sleeping or starting the screensaver mid-film.");
        media = choices(m, new String[] { "On", "Off" }, i -> {
            StickdConf c = StickdConf.parse(Store.stickdText(a));
            if (c.stayAwakeMedia == (i == 0)) return;
            c.stayAwakeMedia = i == 0;
            applyStickdConf(c.serialize(), "conf");
            refresh();
        });

        LinearLayout ss = card(l, "Screensaver", "Shows the screensaver when nothing has been pressed for a while.");
        saver = choices(ss, new String[] { "On", "Off" }, i ->
            send(Commands.screensaver(i == 0), "saver:" + i, "Setting the screensaver…"));
        saverNow = now(ss);

        LinearLayout st = card(l, "Sleep after", "How long the stick stays idle before it goes to sleep (the screensaver "
            + "may run first). Fire OS 8 may override this.");
        sleep = choices(st, SLEEP_LABELS, i ->
            send(Commands.sleepTimeout(SLEEP_MS[i]), "sleep:" + i, "Setting sleep time…"));
        sleepNow = now(st);

        LinearLayout ot = card(l, "Screen off after", "How long without a button press before the screensaver or a dark "
            + "screen starts. Fire OS 8 may override this.");
        off = choices(ot, OFF_LABELS, i ->
            send(Commands.screenOffTimeout(OFF_MS[i]), "off:" + i, "Setting screen-off time…"));
        offNow = now(ot);

        LinearLayout hc = card(l, "TV control (HDMI-CEC)", "Lets the stick turn your TV on and switch it to this input, "
            + "and lets the TV remote control the stick. Turn it off if the TV keeps waking up or switching inputs by itself.");
        cec = choices(hc, new String[] { "On", "Off" }, i ->
            send(com.clean.stickman.core.Starve.hdmiCec(i == 0), "cec:" + i, "Setting TV control…"));
        cecNow = now(hc);

        refresh();
        return Theme.scroll(a, l);
    }

    void refresh() {
        boolean on = StickdConf.parse(Store.stickdText(a)).stayAwakeMedia;
        select(media, on ? 0 : 1);
        stored(saver, saverNow, "saver");
        stored(sleep, sleepNow, "sleep");
        stored(off, offNow, "off");
        stored(cec, cecNow, "cec");
    }

    /** Fallback until the live read answers: the last choice made here, else "unknown". */
    void stored(Button[] bs, TextView now, String key) {
        int i = pref(key, -1);
        select(bs, i);
        if (i < 0 && now.length() == 0) now.setText("Current setting: not read yet");
    }

    TextView now(LinearLayout card) {
        TextView t = Theme.autoHide(Theme.text(a, "", 14, Theme.MUTED));
        card.addView(t);
        return t;
    }

    /** Live values (read-only, once per page show); the stored choices stay the fallback. */
    void readLive() {
        if (Bridge.otherVpnActive(a) || Bridge.busy && readAt != 0) return;
        readAt = System.currentTimeMillis();
        String cmd = LiveValues.SLEEP_READ + "; " + com.clean.stickman.core.Starve.HDMI_READ;
        mine.put(cmd, "live");
        Bridge.run(a, cmd, false);
    }

    void showLive(String out) {
        String sv = LiveValues.value(out, "SAVER"), sl = LiveValues.value(out, "SLEEP"), of = LiveValues.value(out, "OFF");
        live(saver, saverNow, "saver", LiveValues.saver(sv), sv == null ? "unknown" : sv);
        live(sleep, sleepNow, "sleep", LiveValues.indexOfMs(SLEEP_MS, sl), LiveValues.describeMs(sl));
        live(off, offNow, "off", LiveValues.indexOfMs(OFF_MS, of), LiveValues.describeMs(of));
        Boolean c = com.clean.stickman.core.Starve.parseCec(out);
        if (c != null) live(cec, cecNow, "cec", c ? 0 : 1, "");
    }

    /** Select the chip matching the live value; when none matches say what it is instead. */
    void live(Button[] bs, TextView now, String key, int i, String desc) {
        if (i >= 0) { putPref(key, i); select(bs, i); now.setText(""); return; }
        select(bs, -1);
        now.setText("Current: " + desc);
    }

    @Override public void onShow() {
        if (a == null) return;
        refresh();
        readLive();
    }

    @Override public void onTick() {
        if (a == null) return;
        String s = LogTail.status("sleep_timer");
        String v = s == null ? null : s.trim();
        // no STATUS line seen yet: the state is unknown, so no chip is selected
        String show = v == null ? "Sleep timer: no news from Stickman's background helper yet."
            : "off".equals(v) ? "Sleep timer is off." : "Sleep timer: " + v + " min.";
        if (!show.equals(timerShown)) {
            timerShown = show;
            timerInfo.setText(show);
            int sel = -1;
            if ("off".equals(v)) sel = 0;
            else if (v != null) for (int i = 1; i < TIMER_MIN.length; i++) if (v.equals(Integer.toString(TIMER_MIN[i]))) sel = i;
            select(timer, sel);
        }
    }

    @Override String describe(String kind, String out) {
        if (kind.equals("live")) { showLive(out); return null; }
        if (kind.equals("conf")) return Store.pid(out, "STICKD") != null ? "Saved ✓" : "Saved, but stickd isn't running.";
        if (kind.equals("screenoff")) return "Screen off sent. Press any button to bring it back.";
        int c = kind.indexOf(':');
        String k = kind.substring(0, c);
        int i = Integer.parseInt(kind.substring(c + 1));
        switch (k) {
            case "timer":
                select(timer, i);
                return i == 0 ? "Sleep timer off." : "Sleep timer set to " + TIMER_MIN[i] + " min.";
            case "saver": putPref("saver", i); select(saver, i); saverNow.setText(""); return "Screensaver " + (i == 0 ? "on." : "off.");
            case "sleep": putPref("sleep", i); select(sleep, i); sleepNow.setText(""); return "Sleep after: " + SLEEP_LABELS[i] + ".";
            case "cec": putPref("cec", i); select(cec, i); cecNow.setText("");
                return "TV control (HDMI-CEC) " + (i == 0 ? "on." : "off.");
            case "off": putPref("off", i); select(off, i); offNow.setText(""); return "Screen off after: " + OFF_LABELS[i] + ".";
            default: return out;
        }
    }
}
