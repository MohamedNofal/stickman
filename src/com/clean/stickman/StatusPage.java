package com.clean.stickman;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.clean.stickman.core.DeviceInfo;
import com.clean.stickman.core.StatusText;
import com.clean.stickman.core.Trouble;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** RAM, CPU temperature, storage, Wi-Fi, Fire OS, daemon health and stickd's live STATUS lines. */
public class StatusPage extends Page {
    static final long AUTO_REFRESH_MS = 30000;

    MainActivity a;
    TextView daemons, info, live, ram, temp, storage, wifi, os;
    /** "Start here" card, shown until a button has been changed. */
    LinearLayout start;
    long refreshedAt, liveShownAt = -1;
    /** App names for the Live activity lines (looked up once per package). */
    final StatusText.Label label = pkg -> ButtonEditor.appLabel(a, pkg);

    @Override public String title() { return "Status"; }

    @Override public String subtitle() { return "Is Stickman working, and how the stick is doing."; }

    @Override public View build(MainActivity act) {
        a = act;
        LinearLayout l = Theme.column(a);
        l.addView(Theme.title(a, title()));
        l.addView(Theme.subtitle(a, subtitle()));

        LinearLayout top = Theme.row(a);
        Button refresh = Theme.button(a, "Refresh");
        refresh.setOnClickListener(v -> refresh());
        top.addView(refresh);
        info = Theme.text(a, "", 14, Theme.MUTED);
        info.setPadding(Theme.dp(a, 12), 0, 0, 0);
        top.addView(info);
        top.setPadding(0, 0, 0, Theme.dp(a, 12));
        l.addView(top);

        start = Theme.card(a);
        start.addView(Theme.heading(a, "Start here"));
        start.addView(Theme.text(a, "1.  Remote → Listen, then press the button on your remote you want to change.\n"
            + "2.  Speed → Boost when the stick feels slow, then Use recommended settings to keep it that way.\n"
            + (com.clean.stickman.core.Edition.STORE ? "3.  Sleep → a sleep timer, or keep the TV awake while a video plays.\n\n"
                : "3.  Debloat → Disable all switches off Amazon's extras; undo under Disabled by Stickman.\n\n")
            + "You can close Stickman afterwards: your buttons keep working.", 15, Theme.TEXT));
        l.addView(start);

        LinearLayout d = Theme.card(a);
        d.addView(Theme.heading(a, "Background services"));
        daemons = Theme.text(a, "", 16, Theme.TEXT);
        d.addView(daemons);
        l.addView(d);

        LinearLayout row1 = Theme.row(a), row2 = Theme.row(a);
        ram = stat(row1, "RAM");
        temp = stat(row1, "CPU temperature");
        storage = stat(row1, "Storage");
        wifi = stat(row2, "Wi-Fi");
        os = stat(row2, "Fire OS");
        l.addView(row1, new LinearLayout.LayoutParams(-1, -2));
        l.addView(row2, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout lv = Theme.card(a);
        lv.addView(Theme.heading(a, "Live activity"));
        live = Theme.text(a, "", 14, Theme.TEXT);
        live.setLineSpacing(Theme.dp(a, 2), 1f);
        lv.addView(live);
        l.addView(lv);

        LinearLayout w = Theme.card(a);
        w.addView(Theme.heading(a, "Something wrong?"));
        w.addView(Theme.text(a, "Hold Back for 10 seconds: the remote works normally again until Stickman restarts.\n"
            + "To switch everything off and put the stick back the way it was: Settings → Undo everything.", 14, Theme.MUTED));
        Button settings = Theme.button(a, "Open Settings");
        settings.setOnClickListener(v -> a.goTo("Settings"));
        w.addView(settings);
        l.addView(w);
        return Theme.scroll(a, l);
    }

    private TextView stat(LinearLayout row, String heading) {
        LinearLayout c = Theme.card(a);
        // same outer edges as the full-width cards: a gap only between tiles
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.setMargins(row.getChildCount() == 0 ? 0 : Theme.dp(a, 12), 0, 0, Theme.dp(a, 12));
        c.setLayoutParams(lp);
        c.addView(Theme.heading(a, heading));
        TextView v = Theme.text(a, "–", 18, Theme.TEXT);
        c.addView(v);
        row.addView(c);
        return v;
    }

    void refresh() {
        // "Reading…" only once it really goes (Cancel on the other-VPN question must not leave it hanging)
        a.run(() -> { info.setText("Reading…"); refreshedAt = System.currentTimeMillis(); },
            Store.enabled(a) ? Ensure.MARKER : null, DeviceInfo.CMD);
    }

    @Override public void onShow() {
        if (a == null) return;
        start.setVisibility(Store.hasRemap(a) && !Store.remap(a).codes().isEmpty() ? View.GONE : View.VISIBLE);
        if (System.currentTimeMillis() - refreshedAt > AUTO_REFRESH_MS && !Bridge.otherVpnActive(a)) refresh();
        else if (refreshedAt == 0) info.setText("Press Refresh to read the stick's status.");
        onTick();
    }

    @Override public void onTick() {
        if (a == null) return;
        daemons.setText(daemonText());
        // a connect/auth failure produces no per-command callback
        if (!Bridge.busy && Bridge.lastResult.startsWith("FAIL") && Bridge.lastResultAt >= refreshedAt
                && "Reading…".contentEquals(info.getText()))
            info.setText("Couldn't read: " + Trouble.shortText(Bridge.lastResult));
        if (LogTail.statusAt != liveShownAt) {
            liveShownAt = LogTail.statusAt;
            live.setText(liveText(LogTail.statusLines()));
        }
    }

    /** Newest first, "HH:mm:ss  what happened" in plain words; housekeeping lines are left out. */
    String liveText(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = lines.size() - 1; i >= 0; i--) {
            String l = lines.get(i);
            int st = l.indexOf("STATUS ");
            String time = st < 0 ? "" : l.substring(0, st).trim();
            String text = StatusText.human(st < 0 ? l : l.substring(st), label);
            if (text == null) continue;
            if (sb.length() > 0) sb.append('\n');
            if (!time.isEmpty()) sb.append(time).append("  ");
            sb.append(text);
        }
        return sb.length() == 0 ? "Nothing yet. Boosts, sleep timers and auto-closed apps show up here." : sb.toString();
    }

    String daemonText() {
        String r = Bridge.lastResult;
        StringBuilder sb = new StringBuilder();
        String le = Store.lastEnsure(a);
        boolean down = false;
        if (!Store.enabled(a)) {
            // the last check predates the OFF: its "running" lines would be stale
            sb.append("Stickman is OFF: the remote works normally and nothing runs in the background.\n"
                + "Turn it on again in Settings.");
        } else if (le.isEmpty()) {
            sb.append("Not checked yet.");
        } else {
            String remap = Store.pid(le, "REMAPD"), stick = Store.pid(le, "STICKD");
            down = remap == null || stick == null;
            sb.append(line("Button remapping", remap)).append('\n');
            sb.append(line("Background automation", stick)).append('\n');
            String remote = com.clean.stickman.core.RemoteState.describe(le);
            if (remote != null && remap != null) sb.append(remote).append('\n');
            sb.append("Checked ").append(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(Store.lastEnsureAt(a))));
        }
        if (Bridge.busy) sb.append("\nTalking to the stick…");
        else if (r.startsWith("FAIL")) sb.append("\n\n").append(Trouble.advice(r));
        else if (down && Store.enabled(a)) sb.append("\nPress Refresh to start it again.");
        return sb.toString();
    }

    static String line(String name, String pid) {
        return name + ":  " + (pid == null ? "not running" : "running ✓");
    }

    @Override public void onResult(String cmd, String output, boolean ok) {
        if (a == null) return;
        // a later successful start (e.g. Try again in the trouble dialog) must not leave an old failure next to Refresh;
        // reading again would take the VPN once more, so just offer it
        if (ok && Ensure.MARKER.equals(cmd) && info.getText().toString().startsWith("Couldn't read"))
            info.setText(Store.pid(output, "REMAPD") != null
                ? "Stickman is running. Press Refresh to read the stick's status."
                : "Press Refresh to read the stick's status.");
        if (!DeviceInfo.CMD.equals(cmd)) return;
        if (!ok) { info.setText("Couldn't read: " + Trouble.shortText(output)); return; }
        DeviceInfo d = DeviceInfo.parse(output);
        info.setText("Updated " + new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()));
        ram.setText(d.memTotalMb == 0 ? "–" : d.memAvailMb + " MB free\nof " + d.memTotalMb + " MB");
        String t = d.cpuTemp();
        temp.setText(t == null ? "–" : t);
        storage.setText(d.storage == null ? "Unavailable" : d.storage.replace(" free of ", " free\nof "));
        StringBuilder w = new StringBuilder();
        if (d.rssi != 0) w.append(DeviceInfo.signal(d.rssi)).append(" (").append(d.rssi).append(" dBm)");
        if (d.ssid != null && !d.ssid.isEmpty()) w.append(w.length() > 0 ? "\n" : "").append(d.ssid);
        if (d.ip != null) w.append(w.length() > 0 ? "\n" : "").append(d.ip);
        wifi.setText(w.length() == 0 ? "–" : w.toString());
        os.setText(d.fireOs == null ? "–" : d.fireOs);
    }
}
