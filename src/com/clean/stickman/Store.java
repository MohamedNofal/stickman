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
    public static final String DEFAULT_STICKD = com.clean.stickman.core.StickdConf.DEFAULT_TEXT;

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

    /** The stored stickd.conf text with pkg added to list (StickdConf.addTo: the lists are exclusive); not saved. */
    public static String addToList(Context c, String list, String pkg) {
        return com.clean.stickman.core.StickdConf.parse(stickdText(c)).addTo(list, pkg).serialize();
    }

    /** The stored stickd.conf text with pkg removed from list (StickdConf.removeFrom); not saved. */
    public static String removeFromList(Context c, String list, String pkg) {
        return com.clean.stickman.core.StickdConf.parse(stickdText(c)).removeFrom(list, pkg).serialize();
    }

    // ---- remote model (preset), learned key codes and app-button names, per preset ----
    public static String remoteModel(Context c) {
        return com.clean.stickman.core.RemotePresets.get(prefs(c).getString("remoteModel", null)).id;
    }

    public static void setRemoteModel(Context c, String id) { prefs(c).edit().putString("remoteModel", id).apply(); }

    /** slot -> code from learned.<preset>.<slot>. */
    public static java.util.Map<String, Integer> learned(Context c, String preset) {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        String pre = "learned." + preset + ".";
        for (java.util.Map.Entry<String, ?> e : prefs(c).getAll().entrySet())
            if (e.getKey().startsWith(pre) && e.getValue() instanceof Integer) out.put(e.getKey().substring(pre.length()), (Integer) e.getValue());
        return out;
    }

    /** code <= 0 forgets the slot's learned code. */
    public static void setLearned(Context c, String preset, String slot, int code) {
        String k = "learned." + preset + "." + slot;
        if (code <= 0) prefs(c).edit().remove(k).apply();
        else prefs(c).edit().putInt(k, code).apply();
    }

    /** slot -> label from label.<preset>.<slot>. */
    public static java.util.Map<String, String> labels(Context c, String preset) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        String pre = "label." + preset + ".";
        for (java.util.Map.Entry<String, ?> e : prefs(c).getAll().entrySet())
            if (e.getKey().startsWith(pre) && e.getValue() instanceof String) out.put(e.getKey().substring(pre.length()), (String) e.getValue());
        return out;
    }

    /** null label restores the preset's default name. */
    public static void setLabel(Context c, String preset, String slot, String label) {
        String k = "label." + preset + "." + slot;
        if (label == null) prefs(c).edit().remove(k).apply();
        else prefs(c).edit().putString(k, label).apply();
    }

    /** The chosen preset's buttons with learned codes and renames applied. */
    public static com.clean.stickman.core.RemoteLayout.Btn[] remoteButtons(Context c) {
        String id = remoteModel(c);
        return com.clean.stickman.core.RemotePresets.resolve(com.clean.stickman.core.RemotePresets.get(id), learned(c, id), labels(c, id));
    }

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
