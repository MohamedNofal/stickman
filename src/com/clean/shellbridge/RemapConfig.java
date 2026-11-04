package com.clean.shellbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Base64;

/** Button rules (persisted) + the shell commands that apply them. */
public class RemapConfig {
    // Remote buttons that can be remapped (Linux keycode, label). D-pad/OK/Back excluded on purpose.
    static final int[] CODES = { 172, 744, 745, 747, 746, 139, 164, 168, 208, 113, 114, 115, 249, 748, 362, 402, 403, 217, 116 };
    static final String[] NAMES = { "Home", "Netflix button", "Prime Video button", "Amazon Music button", "Apps button",
        "Menu (≡)", "Play/Pause", "Rewind", "Fast-forward", "Mute", "Volume down", "Volume up", "Settings",
        "Recents", "Live TV / Guide", "Channel up", "Channel down", "Alexa / voice", "Power" };
    // Buttons another button can act as.
    static final int[] TARGETS = { 172, 158, 139, 164, 168, 208, 113, 114, 115, 249, 748, 362, 103, 108, 105, 106, 96 };
    static final String[] TARGET_NAMES = { "Home", "Back", "Menu", "Play/Pause", "Rewind", "Fast-forward", "Mute",
        "Volume down", "Volume up", "Settings", "Recents", "Live TV / Guide", "Up", "Down", "Left", "Right", "OK" };

    static final String WOLF = "com.wolf.firelauncher/.screens.launcher.LauncherActivity";
    static final String D = "/data/local/tmp/remap";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("rules", 0); }

    static void initDefaults(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean("init", false)) return;
        SharedPreferences.Editor e = p.edit().putBoolean("init", true).putBoolean("enabled", true);
        try { c.getPackageManager().getPackageInfo("com.wolf.firelauncher", 0); e.putString("k172", "start\t" + WOLF + "\tWolf Launcher"); }
        catch (Exception ignored) {}
        e.apply();
    }

    static String get(Context c, int code) { return prefs(c).getString("k" + code, null); }
    static void set(Context c, int code, String rule) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (rule == null) e.remove("k" + code); else e.putString("k" + code, rule);
        e.apply();
    }
    static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", true); }
    static void setEnabled(Context c, boolean on) { prefs(c).edit().putBoolean("enabled", on).apply(); }

    static String describe(String rule) {
        if (rule == null) return "default";
        String[] p = rule.split("\t");
        if (p[0].equals("start")) return "open " + (p.length > 2 ? p[2] : p[1]);
        if (p[0].equals("key")) return "acts as " + (p.length > 2 ? p[2] : p[1]);
        if (p[0].equals("none")) return "disabled";
        return rule;
    }

    static String confText(Context c) {
        StringBuilder sb = new StringBuilder("# written by Remote Remap\n");
        for (int code : CODES) {
            String r = get(c, code);
            if (r == null) continue;
            String[] p = r.split("\t");
            if (p[0].equals("start")) sb.append(code).append(" start ").append(p[1]).append('\n');
            else if (p[0].equals("key")) sb.append(code).append(" key ").append(p[1]).append('\n');
            else if (p[0].equals("none")) sb.append(code).append(" none\n");
        }
        return sb.toString();
    }

    /** Install/refresh remapd + config and make sure it runs (idempotent). Prints "REMAPD <pid>". */
    static String ensureCmd(Context c) {
        String lib = c.getApplicationInfo().nativeLibraryDir + "/libremapd.so";
        String b64 = Base64.encodeToString(confText(c).getBytes(), Base64.NO_WRAP);
        return "D=" + D + "; mkdir -p $D && cd $D || exit 1; " +
            "NEW=0; cmp -s '" + lib + "' remapd || { cp '" + lib + "' remapd.new && chmod 755 remapd.new && mv remapd.new remapd && NEW=1; }; " +
            "echo " + b64 + " | base64 -d > conf.new && mv conf.new remap.conf; " +
            "pm grant " + c.getPackageName() + " android.permission.READ_LOGS 2>/dev/null; " +
            // retire the older Home-only daemon and the Home Switch app; remapd replaces both
            "W=$(cat /data/local/tmp/wolfd.pid 2>/dev/null); [ -n \"$W\" ] && grep -q wolfd /proc/$W/cmdline 2>/dev/null && kill -- -$W 2>/dev/null; rm -f /data/local/tmp/wolfd.pid; " +
            "am force-stop com.clean.homeswitch; " +
            "P=$(cat remapd.pid 2>/dev/null); ALIVE=0; [ -n \"$P\" ] && grep -q remapd /proc/$P/cmdline 2>/dev/null && ALIVE=1; " +
            "if [ $ALIVE = 1 ] && [ $NEW = 1 ]; then kill $P; sleep 0.3; ALIVE=0; fi; " +
            "if [ $ALIVE = 0 ]; then setsid nohup ./remapd $D/remap.conf </dev/null >/dev/null 2>&1 & sleep 0.4; fi; " +
            "echo REMAPD $(cat remapd.pid 2>/dev/null)";
    }

    static String stopCmd() {
        return "P=$(cat " + D + "/remapd.pid 2>/dev/null); [ -n \"$P\" ] && grep -q remapd /proc/$P/cmdline 2>/dev/null && kill $P; rm -f " + D + "/remapd.pid; echo STOPPED";
    }

    /** Kernel boot id: unique per boot, immune to the stick's clock jumping after boot. */
    static String bootId() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/sys/kernel/random/boot_id"));
            String s = r.readLine(); r.close(); return s == null ? "" : s.trim();
        } catch (Exception e) { return "" + (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 600000; }
    }
    static void markEnsured(Context c) { prefs(c).edit().putString("ensuredBootId", bootId()).apply(); }
    static boolean ensuredThisBoot(Context c) { return bootId().equals(prefs(c).getString("ensuredBootId", "")); }
}
