package com.clean.stickman;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class MainActivity extends Activity {
    TextView status, hint;
    ListView list;
    Button toggle;
    ArrayAdapter<String> adapter;
    final Handler h = new Handler();
    LogTail tail;
    long seenKeyAt;
    boolean hadLogs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        RemapConfig.initDefaults(this);
        hadLogs = checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED;
        BootJob.schedule(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(64, 40, 64, 32);
        root.setBackgroundColor(Color.rgb(18, 20, 26));

        TextView title = new TextView(this);
        title.setText("Stickman");
        title.setTextSize(30); title.setTextColor(Color.WHITE);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(16); status.setTextColor(Color.rgb(150, 200, 255));
        status.setPadding(0, 8, 0, 4);
        root.addView(status);

        hint = new TextView(this);
        hint.setTextSize(14); hint.setTextColor(Color.rgb(160, 160, 170));
        hint.setText("Press any remote button to jump to it. Select a button to change what it does.\n" +
            "Holding a remapped button (except app buttons) still does its original action. Hold Back 10 s = emergency stop.");
        hint.setPadding(0, 0, 0, 16);
        root.addView(hint);

        list = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> editKey(pos));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        toggle = new Button(this);
        toggle.setOnClickListener(v -> toggleEnabled());
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.END);
        bar.addView(toggle);
        root.addView(bar);

        setContentView(root);
        refreshList();

        Intent consent = VpnService.prepare(this);
        if (consent != null) startActivityForResult(consent, 1);
        else apply();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) { if (res == RESULT_OK) apply(); }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
        tail = new LogTail();
        new Thread(tail, "logtail").start();
        h.post(this::tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (tail != null) tail.shutdown();
        h.removeCallbacksAndMessages(null);
    }

    void tick() {
        // READ_LOGS is granted (by the shell, during the first apply) while we're running, but it only
        // takes effect in a fresh process -> restart ourselves once so the live button display works.
        if (!hadLogs && !Bridge.busy
                && checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) {
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            android.os.Process.killProcess(android.os.Process.myPid());
            return;
        }
        String r = Bridge.busy ? "working…" : Bridge.lastResult;
        String s;
        if (!RemapConfig.enabled(this)) s = "Remapper is OFF – remote works normally";
        else if (r.startsWith("FAIL")) s = "Problem: " + r.substring(5);
        else if (r.contains("REMAPD ") && r.matches("(?s).*REMAPD \\d+.*")) s = "Remapper running ✓";
        else s = r.isEmpty() || r.equals("working…") ? "Starting remapper…" : r;
        if (LogTail.lastKey != 0 && LogTail.lastKeyAt > seenKeyAt) {
            seenKeyAt = LogTail.lastKeyAt;
            int idx = indexOf(RemapConfig.CODES, LogTail.lastKey);
            if (idx >= 0) { list.setSelection(idx); list.requestFocus(); }
        }
        if (LogTail.lastKey != 0 && System.currentTimeMillis() - LogTail.lastKeyAt < 4000)
            s += "   •   last button: " + keyName(LogTail.lastKey);
        status.setText(s);
        toggle.setText(RemapConfig.enabled(this) ? "Turn remapper OFF" : "Turn remapper ON");
        h.postDelayed(this::tick, 400);
    }

    static int indexOf(int[] a, int v) { for (int i = 0; i < a.length; i++) if (a[i] == v) return i; return -1; }

    static String keyName(int code) {
        int i = indexOf(RemapConfig.CODES, code);
        if (i >= 0) return RemapConfig.NAMES[i];
        i = indexOf(RemapConfig.TARGETS, code);
        return i >= 0 ? RemapConfig.TARGET_NAMES[i] : ("key " + code);
    }

    void refreshList() {
        adapter.clear();
        for (int i = 0; i < RemapConfig.CODES.length; i++)
            adapter.add(RemapConfig.NAMES[i] + "   →   " + RemapConfig.describe(RemapConfig.get(this, RemapConfig.CODES[i])));
        adapter.notifyDataSetChanged();
    }

    void apply() {
        if (RemapConfig.enabled(this)) Bridge.run(this, RemapConfig.ENSURE, false);
        else Bridge.run(this, RemapConfig.stopCmd(), false);
    }

    void toggleEnabled() {
        RemapConfig.setEnabled(this, !RemapConfig.enabled(this));
        apply();
    }

    void editKey(int pos) {
        final int code = RemapConfig.CODES[pos];
        String[] opts = { "Default (no change)", "Open an app…", "Act as another button…", "Do nothing (disable)" };
        new AlertDialog.Builder(this).setTitle(RemapConfig.NAMES[pos])
            .setItems(opts, (d, which) -> {
                if (which == 0) save(code, null);
                else if (which == 1) pickApp(code);
                else if (which == 2) pickTarget(code);
                else save(code, "none");
            }).show();
    }

    void pickTarget(final int code) {
        new AlertDialog.Builder(this).setTitle("Act as…")
            .setItems(RemapConfig.TARGET_NAMES, (d, w) ->
                save(code, "key\t" + RemapConfig.TARGETS[w] + "\t" + RemapConfig.TARGET_NAMES[w])).show();
    }

    void pickApp(final int code) {
        PackageManager pm = getPackageManager();
        final List<String[]> apps = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (String cat : new String[] { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER }) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                apps.add(new String[] { ri.loadLabel(pm).toString(), pkg + "/" + ri.activityInfo.name });
            }
        }
        Collections.sort(apps, (a, c) -> a[0].compareToIgnoreCase(c[0]));
        String[] labels = new String[apps.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = apps.get(i)[0];
        new AlertDialog.Builder(this).setTitle("Open which app?")
            .setItems(labels, (d, w) -> save(code, "start\t" + apps.get(w)[1] + "\t" + apps.get(w)[0])).show();
    }

    void save(int code, String rule) {
        RemapConfig.set(this, code, rule);
        refreshList();
        if (!RemapConfig.enabled(this)) RemapConfig.setEnabled(this, true);
        apply();
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
    }
}
