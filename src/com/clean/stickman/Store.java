package com.clean.stickman;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import com.clean.stickman.core.RemapModel;
import com.clean.stickman.core.Rule;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** App-side persisted state: remap.conf + stickd.conf text in files/, flags in prefs. */
public final class Store {
    private Store() {}

    static final String TAG = "Stickman";
    static final String REMAP = "remap.conf";
    static final String STICKD = "stickd.conf";
    static final String WOLF_PKG = "com.wolf.firelauncher";
    static final String WOLF = WOLF_PKG + "/.screens.launcher.LauncherActivity";
    public static final String DEFAULT_STICKD =
        "autokill.on_leave=0\nautokill.interval_min=0\nautokill.whitelist=\nautokill.blacklist=\nstayawake.media=0\n";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("stickman", 0); }

    // ---- files ----
    private static synchronized String read(Context c, String name) {
        File f = new File(c.getFilesDir(), name);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            return new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "read " + name + ": " + e);
            return null;
        }
    }

    private static synchronized void write(Context c, String name, String text) {
        File f = new File(c.getFilesDir(), name), tmp = new File(c.getFilesDir(), name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception e) {
            Log.w(TAG, "write " + name + ": " + e);
            return;
        }
        if (!tmp.renameTo(f)) Log.w(TAG, "rename " + name + " failed");
    }

    // ---- remap ----
    public static boolean hasRemap(Context c) { return new File(c.getFilesDir(), REMAP).exists(); }

    public static RemapModel remap(Context c) { return RemapModel.parse(read(c, REMAP)); }

    public static void saveRemap(Context c, RemapModel m) { write(c, REMAP, m.serialize()); }

    /** True when the model holds no rules at all (only header/set/profile lines). */
    static boolean isEmpty(RemapModel m) {
        for (String l : m.serialize().split("\n")) {
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("set ") || l.startsWith("profile ")) continue;
            return false;
        }
        return true;
    }

    /**
     * First run: adopt the rules remapd already uses (KeyShift's v1 /data/local/tmp/remap/remap.conf and/or an
     * earlier Stickman v2 conf, concatenated). If nothing is found, seed Home -> Wolf when Wolf is installed.
     */
    static void importRemap(Context c, String existing) {
        RemapModel m = RemapModel.parse(existing);
        if (isEmpty(m)) {
            try {
                c.getPackageManager().getPackageInfo(WOLF_PKG, 0);
                m.set(RemapModel.DEFAULT, 172, Rule.Layer.TAP, "start " + WOLF);
            } catch (Exception ignored) {}
            Log.i(TAG, "no existing rules; seeded defaults");
        } else {
            Log.i(TAG, "imported existing rules");
        }
        saveRemap(c, m);
    }

    // ---- stickd.conf ----
    public static String stickdText(Context c) {
        String s = read(c, STICKD);
        return s == null ? DEFAULT_STICKD : s;
    }

    public static void saveStickdText(Context c, String text) { write(c, STICKD, text == null ? "" : text); }

    // ---- master switch ----
    public static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", true); }

    public static void setEnabled(Context c, boolean on) { prefs(c).edit().putBoolean("enabled", on).apply(); }

    // ---- last ensure ("REMAPD <pid|-> STICKD <pid|->") ----
    static void setLastEnsure(Context c, String out) {
        prefs(c).edit().putString("lastEnsure", out).putLong("lastEnsureAt", System.currentTimeMillis()).apply();
    }

    static String lastEnsure(Context c) { return prefs(c).getString("lastEnsure", ""); }

    static long lastEnsureAt(Context c) { return prefs(c).getLong("lastEnsureAt", 0); }

    /** pid of name ("REMAPD"/"STICKD") in the last ensure output, or null. */
    static String pid(String ensureOut, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(name + " (\\d+)").matcher(ensureOut == null ? "" : ensureOut);
        return m.find() ? m.group(1) : null;
    }

    // ---- once per boot ----
    /** Kernel boot id: unique per boot, immune to the stick's clock jumping after boot. */
    static String bootId() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/sys/kernel/random/boot_id"))) {
            String s = r.readLine();
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            return "" + (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 600000;
        }
    }

    static void markEnsured(Context c) { prefs(c).edit().putString("ensuredBootId", bootId()).apply(); }

    static boolean ensuredThisBoot(Context c) { return bootId().equals(prefs(c).getString("ensuredBootId", "")); }
}
