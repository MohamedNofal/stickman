package com.clean.stickman;

import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.clean.stickman.core.Actions;
import com.clean.stickman.core.Commands;
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

    Button[] saver, sleep, off, timer;
    Button media;
    TextView timerInfo;
    String timerShown;

    @Override public String title() { return "Sleep"; }

    @Override public android.view.View build(MainActivity act) {
        LinearLayout l = start(act);

        LinearLayout t = card(l, "Sleep timer", "Pauses what's playing and puts the stick to sleep, with a gentle fade in the last minute.");
        timer = choices(t, new String[] { "Off", "15 min", "30 min", "60 min", "90 min" }, i -> {
            if (!stickdOn()) return;
            send(Commands.act(Actions.SLEEP_TIMER_SET, Integer.toString(TIMER_MIN[i])), "timer:" + i,
                i == 0 ? "Turning the sleep timer off…" : "Setting the sleep timer…");
        });
        timerInfo = Theme.text(a, "", 14, Theme.TEXT);
        t.addView(timerInfo);

        LinearLayout so = card(l, "Screen off", "Blacks out the screen while audio keeps playing. Any button brings it back.");
        button(so, "Screen off now", v -> {
            if (stickdOn()) send(Commands.act(Actions.SCREEN_OFF, null), "screenoff", "Turning the screen off…");
        });

        LinearLayout m = card(l, "Stay awake while media plays", "Stops the stick sleeping or starting the screensaver mid-film.");
        media = button(m, "", v -> {
            StickdConf c = StickdConf.parse(Store.stickdText(a));
            c.stayAwakeMedia = !c.stayAwakeMedia;
            applyStickdConf(c.serialize(), "conf");
            refresh();
        });

        LinearLayout ss = card(l, "Screensaver", null);
        saver = choices(ss, new String[] { "On", "Off" }, i ->
            send(Commands.screensaver(i == 0), "saver:" + i, "Setting the screensaver…"));

        LinearLayout st = card(l, "Sleep after", "Fire OS 8 may override this");
        sleep = choices(st, SLEEP_LABELS, i ->
            send(Commands.sleepTimeout(SLEEP_MS[i]), "sleep:" + i, "Setting sleep time…"));

        LinearLayout ot = card(l, "Screen off after", "Fire OS 8 may override this");
        off = choices(ot, OFF_LABELS, i ->
            send(Commands.screenOffTimeout(OFF_MS[i]), "off:" + i, "Setting screen-off time…"));

        refresh();
        return Theme.scroll(a, l);
    }

    void refresh() {
        boolean on = StickdConf.parse(Store.stickdText(a)).stayAwakeMedia;
        media.setText("Stay awake while media plays:  " + (on ? "On" : "Off"));
        media.setSelected(on);
        select(saver, pref("saver", -1));
        select(sleep, pref("sleep", -1));
        select(off, pref("off", -1));
    }

    @Override public void onShow() { if (a != null) refresh(); }

    @Override public void onTick() {
        if (a == null) return;
        String s = LogTail.status("sleep_timer");
        String show = s == null ? "" : "off".equals(s.trim()) ? "Sleep timer is off." : "Sleep timer: " + s.trim() + " min.";
        if (!show.equals(timerShown)) {
            timerShown = show;
            timerInfo.setText(show);
            if (s != null) {
                int sel = 0;
                for (int i = 0; i < TIMER_MIN.length; i++) if (s.trim().equals(Integer.toString(TIMER_MIN[i]))) sel = i;
                select(timer, sel);
            }
        }
    }

    @Override String describe(String kind, String out) {
        if (kind.equals("conf")) return Store.pid(out, "STICKD") != null ? "Saved ✓" : "Saved, but stickd isn't running.";
        if (kind.equals("screenoff")) return "Screen off sent. Press any button to bring it back.";
        int c = kind.indexOf(':');
        String k = kind.substring(0, c);
        int i = Integer.parseInt(kind.substring(c + 1));
        switch (k) {
            case "timer":
                select(timer, i);
                return i == 0 ? "Sleep timer off." : "Sleep timer set to " + TIMER_MIN[i] + " min.";
            case "saver": putPref("saver", i); select(saver, i); return "Screensaver " + (i == 0 ? "on." : "off.");
            case "sleep": putPref("sleep", i); select(sleep, i); return "Sleep after: " + SLEEP_LABELS[i] + ".";
            case "off": putPref("off", i); select(off, i); return "Screen off after: " + OFF_LABELS[i] + ".";
            default: return out;
        }
    }
}
