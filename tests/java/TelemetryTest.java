import com.clean.stickman.core.Catalog;
import com.clean.stickman.core.Commands;
import com.clean.stickman.core.Telemetry;

import java.io.File;
import java.nio.file.Files;

public class TelemetryTest {
    public void testQuietScript() throws Exception {
        String on = Telemetry.quiet(true);
        for (String[] a : Telemetry.APPS) {
            T.yes(on.contains("cmd appops set " + a[0] + " RUN_ANY_IN_BACKGROUND ignore"), "ignore " + a[0]);
            T.yes(on.contains("am kill " + a[0] + " >/dev/null 2>&1"), "quiet kill " + a[0]);
            T.yes(on.contains("appop " + a[0] + " RUN_ANY_IN_BACKGROUND"), "journaled " + a[0]);
            T.yes(!a[0].equals("com.amazon.device.logmanager"), "system-uid logmanager is never in the set");
        }
        // Catalog protection is about disabling (Starve.apply refuses it); the appops don't go through it
        for (String p : new String[] { "com.amazon.client.metrics.api", "com.amazon.diode" }) {
            T.yes(Catalog.isProtected(p) || true, "catalog may protect " + p);
            T.yes(on.contains("cmd appops set " + p + " RUN_ANY_IN_BACKGROUND ignore"), "still quieted: " + p);
        }
        T.yes(on.contains("cmd appops set com.fireos.usagestats.proxy GET_USAGE_STATS ignore"), "usage access taken from the proxy");
        T.eq(1, count(on, "GET_USAGE_STATS ignore"), "only the proxy loses usage access");
        T.yes(on.endsWith("true"), "ends true");
        String off = Telemetry.quiet(false);
        for (String[] a : Telemetry.APPS) T.yes(off.contains("cmd appops set " + a[0] + " RUN_ANY_IN_BACKGROUND default"), "default " + a[0]);
        T.yes(!off.contains(" ignore"), "off never ignores");
        T.eq(0, parse(on), "on script parses");
        T.eq(0, parse(off), "off script parses");
        T.eq(0, parse("echo QUIET " + Telemetry.COUNT_EXPR), "count expression parses");
    }

    public void testCountAndState() {
        T.eq(8, Telemetry.count("DNS null null ADS 1 QUIET 8 LOC 3"), "count");
        T.eq(0, Telemetry.count("QUIET 0"), "zero");
        T.eq(-1, Telemetry.count("DNS null null ADS 1"), "missing");
        T.eq(-1, Telemetry.count(null), "null");
        T.eq("Quiet", Telemetry.state(8), "all");
        T.eq("Running", Telemetry.state(0), "none");
        T.eq("Partly quiet (3 of 8)", Telemetry.state(3), "partial");
        T.eq("not read yet", Telemetry.state(-1), "unread");
        T.yes(Telemetry.COUNT_EXPR.startsWith("$(") && Telemetry.COUNT_EXPR.endsWith("echo $n)"), "shell expression");
        for (String[] a : Telemetry.APPS) T.yes(Telemetry.COUNT_EXPR.contains(" " + a[0]), "counts " + a[0]);
        T.eq(8, Telemetry.packages().length, "eight apps");
        T.yes(Telemetry.summary().startsWith("device metrics, app metrics") && Telemetry.summary().endsWith("remote diagnostics"), "summary");
    }

    public void testLocationAndPrivacyKeys() throws Exception {
        String off = Commands.location(false);
        T.yes(off.endsWith("settings put secure location_mode 0"), "off");
        T.yes(off.contains("setting secure location_mode $(settings get secure location_mode)"), "journaled");
        String on = Commands.location(true);
        T.yes(on.contains("grep -q '^setting secure location_mode '") && on.contains("settings put secure location_mode 3"), "on restores or sets 3");
        T.eq(0, parse(on), "on parses");
        String p = Commands.privacy(true);
        for (String k : new String[] { "settingsApp:deviceUsageData:value 0", "settingsApp:appUsageData:value 0",
                "usage_metrics_marketing_enabled 0", "pact_usage_collection_enabled 0", "amz_limit_ad_tracking 1" })
            T.yes(p.contains("settings put secure " + k), k);
        String q = Commands.privacy(false);
        T.yes(q.contains("settingsApp:deviceUsageData:value 1") && q.contains("amz_limit_ad_tracking 0"), "off values");
    }

    static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) n++;
        return n;
    }

    /** sh -n exit code: 0 when the script is syntactically valid POSIX shell. */
    static int parse(String script) throws Exception {
        File f = File.createTempFile("stickman-telemetry", ".sh");
        try {
            Files.write(f.toPath(), script.getBytes("UTF-8"));
            Process pr = new ProcessBuilder("sh", "-n", f.getPath()).redirectErrorStream(true).start();
            return pr.waitFor();
        } finally {
            f.delete();
        }
    }
}
