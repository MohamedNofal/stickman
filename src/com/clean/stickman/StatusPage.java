package com.clean.stickman;

import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.clean.stickman.core.DeviceInfo;

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

    @Override public String title() { return "Status"; }

    @Override public View build(MainActivity act) {
        a = act;
        LinearLayout l = Theme.column(a);
        l.addView(Theme.title(a, "Status"));

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
            + "2.  Speed → Boost when the stick feels slow; Lean memory keeps it that way.\n"
            + "3.  Debloat → Disable all switches off Amazon's extras. Everything can be undone.\n\n"
            + "If the remote ever misbehaves, hold Back for 10 seconds: it works normally again.", 15, Theme.TEXT));
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
        lv.addView(live);
        l.addView(lv);
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
        info.setText("Reading…");
        refreshedAt = System.currentTimeMillis();
        // the ensure is idempotent and refreshes the daemon pids in the same bridge session
        a.run(null, Store.enabled(a) ? Ensure.MARKER : null, DeviceInfo.CMD);
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
            info.setText("Couldn't read: " + Bridge.lastResult);
        if (LogTail.statusAt != liveShownAt) {
            liveShownAt = LogTail.statusAt;
            List<String> lines = LogTail.statusLines();
            StringBuilder sb = new StringBuilder();
            for (int i = lines.size() - 1; i >= 0; i--) sb.append(lines.get(i)).append('\n');
            live.setText(sb.length() == 0 ? "Nothing yet. Boosts, sleep timers and auto-closed apps show up here." : sb.toString().trim());
            live.setTypeface(sb.length() == 0 ? Typeface.DEFAULT : Typeface.MONOSPACE);
        }
    }

    String daemonText() {
        String r = Bridge.lastResult;
        StringBuilder sb = new StringBuilder();
        if (!Store.enabled(a)) sb.append("Stickman is OFF (daemons stopped, remote works normally)\n");
        String le = Store.lastEnsure(a);
        if (le.isEmpty()) {
            sb.append("Not checked yet");
        } else {
            sb.append(line("Button remapping", Store.pid(le, "REMAPD"))).append('\n');
            sb.append(line("Background automation", Store.pid(le, "STICKD"))).append('\n');
            sb.append("Checked ").append(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(Store.lastEnsureAt(a))));
        }
        if (Bridge.busy) sb.append("\nBridge: working…");
        else if (r.startsWith("FAIL")) sb.append("\nBridge problem: ").append(r);
        return sb.toString();
    }

    static String line(String name, String pid) {
        return name + ":  " + (pid == null ? "not running" : "running ✓  (pid " + pid + ")");
    }

    @Override public void onResult(String cmd, String output, boolean ok) {
        if (a == null || !DeviceInfo.CMD.equals(cmd)) return;
        if (!ok) { info.setText("Couldn't read: " + output); return; }
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
