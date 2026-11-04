import com.clean.stickman.core.EnsureScript;

public class EnsureScriptTest {
    static final String APK = "/data/app/~~x==/com.clean.stickman-y==/base.apk";
    static final String LIB = "/data/app/~~x==/com.clean.stickman-y==/lib/arm/libremapd.so";

    static String script(boolean migrate) {
        return EnsureScript.build(APK, LIB, 7, "# stickman remap v2\n172 tap start a/.B\n", "autokill.on_leave=0\n", migrate);
    }

    public void testIdempotentPieces() {
        String s = script(false);
        T.yes(s.contains("mkdir -p /data/local/tmp/stickman"), "mkdir");
        T.yes(s.contains("/data/local/tmp/stickman/act"), "act spool dir");
        T.yes(!s.contains("act.fifo"), "no fifo");
        T.yes(s.contains("cmp -s"), "cmp");
        T.yes(s.contains("base64 -d > remap.conf"), "remap.conf via base64");
        T.yes(s.contains("base64 -d > stickd.conf"), "stickd.conf via base64");
        T.yes(s.contains("--nice-name=stickd"), "stickd launch");
        T.yes(s.contains("com.clean.stickman.daemon.Stickd"), "stickd class");
        T.yes(s.contains("CLASSPATH='" + APK + "'"), "apk classpath");
        T.yes(s.contains("'" + LIB + "'"), "lib path");
        T.yes(s.contains("appops set com.clean.stickman SYSTEM_ALERT_WINDOW allow"), "overlay appop");
        T.yes(s.contains("pm grant com.clean.stickman android.permission.READ_LOGS"), "read logs");
        T.yes(s.contains("am force-stop com.clean.homeswitch"), "homeswitch");
        T.yes(s.contains("/proc/$P/comm"), "alive check by comm");
        T.yes(s.contains("/proc/$P/cmdline"), "alive fallback: cmdline arg0");
        T.yes(!s.contains("grep -q remapd") && !s.contains("grep -q stickd") && !s.contains("grep -q $1"), "no substring cmdline grep");
        T.yes(s.contains("! alive remapd; do sleep 0.1"), "waits for remapd instead of fixed sleep");
        T.yes(!s.contains("sleep 0.4"), "no fixed remapd sleep");
        T.yes(s.contains("echo 7 > version") || s.contains("echo 7 >version"), "writes version");
        T.yes(s.contains("echo REMAPD"), "prints pids");
        T.yes(s.contains("STICKD"), "prints stickd pid");
    }

    public void testConfIsBase64OfText() {
        String s = script(false);
        String b64 = java.util.Base64.getEncoder().encodeToString("autokill.on_leave=0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        T.yes(s.contains("echo " + b64 + " | base64 -d > stickd.conf"), "stickd b64");
    }

    public void testMigrateBlock() {
        String on = script(true), off = script(false);
        T.yes(on.contains("pm uninstall com.clean.keyshift"), "uninstall when migrate");
        T.yes(on.contains("/data/local/tmp/remap/remapd.pid"), "kills old remapd");
        T.yes(on.contains("rm -rf /data/local/tmp/remap;"), "removes old dir");
        T.yes(on.contains("[ -e /data/local/tmp/remap ]"), "guarded by dir");
        T.yes(on.contains("pm path com.clean.keyshift 2>/dev/null | grep -q '^package:'"), "guarded by pm path output, not exit status");
        T.yes(on.indexOf("/data/local/tmp/remap/remapd.pid") < on.indexOf("pm uninstall com.clean.keyshift"), "kill before uninstall");
        T.yes(on.indexOf("pm uninstall com.clean.keyshift") < on.indexOf("--nice-name=stickd"), "migrate before start");
        T.yes(!off.contains("pm uninstall com.clean.keyshift"), "no uninstall when not migrating");
        T.yes(!off.contains("rm -rf /data/local/tmp/remap;"), "no rm when not migrating");
    }

    public void testStop() {
        String s = EnsureScript.stop();
        T.yes(s.contains("/data/local/tmp/stickman/remapd.pid"), "remapd pid file");
        T.yes(s.contains("/data/local/tmp/stickman/stickd.pid"), "stickd pid file");
        T.yes(s.contains("kill"), "kills");
        T.yes(s.trim().endsWith("echo STOPPED"), "prints STOPPED");
    }

    /** Realistic big config: 45 rules over 3 profiles + full stickd.conf must fit our 256K adb payload. */
    public void testRealisticConfFitsAdbPayload() {
        com.clean.stickman.core.RemapModel m = new com.clean.stickman.core.RemapModel();
        String[] profiles = { "default", "org.xbmc.kodi", "com.netflix.ninja" };
        int[] codes = com.clean.stickman.core.Keys.ALL;
        com.clean.stickman.core.Rule.Layer[] layers = com.clean.stickman.core.Rule.Layer.values();
        int n = 0;
        for (int p = 0; p < profiles.length; p++)
            for (int i = 0; i < 15; i++, n++)
                m.set(profiles[p], codes[i], layers[i % 3], i % 2 == 0
                    ? "start com.example.some.long.packagename" + i + "/com.example.some.long.packagename.ui.MainActivity"
                    : "act sleep_timer_set 30");
        T.yes(n >= 40, "≥40 rules");
        String stickd = "autokill.on_leave=1\nautokill.interval_min=30\n"
            + "autokill.whitelist=com.spotify.tv.android,org.xbmc.kodi,com.plexapp.android,com.google.android.youtube.tv\n"
            + "autokill.blacklist=com.amazon.avod,com.netflix.ninja,com.disney.disneyplus,com.bbc.iplayer.android\n"
            + "stayawake.media=1\n";
        String s = EnsureScript.build(APK, LIB, 1, m.serialize(), stickd, true);
        int len = com.clean.stickman.core.AdbLimits.shellPayloadLen(s);
        System.out.println("  realistic ensure payload = " + len + " bytes (" + n + " rules)");
        T.yes(len < 256 * 1024, "fits 256K: " + len);
    }

    public void testRejectsUnsafePaths() {
        boolean threw = false;
        try { EnsureScript.build("/a'b.apk", LIB, 1, "", "", false); } catch (IllegalArgumentException e) { threw = true; }
        T.yes(threw, "quote in path rejected");
    }

    public void testShellSyntax() throws Exception {
        for (String s : new String[] { script(true), script(false), EnsureScript.stop() }) {
            Process p = new ProcessBuilder("sh", "-n", "-c", s).redirectErrorStream(true).start();
            String out = new String(readAll(p.getInputStream()));
            T.eq(0, p.waitFor(), "sh -n: " + out);
        }
    }

    static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }
}
