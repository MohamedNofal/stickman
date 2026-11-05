import com.clean.stickman.core.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class ProcStatsTest {
    private static String fx(String n) throws IOException {
        return new String(Files.readAllBytes(Paths.get("tests/fixtures/proc/" + n)), StandardCharsets.ISO_8859_1);
    }

    public void testParsesFixtures() throws Exception {
        for (String pid : new String[] {"14812", "30779", "1244"}) {
            T.yes(ProcStats.rssKbFromStatm(fx(pid + ".statm"), 4) > 0, pid + " rss");
            long[] c = ProcStats.cpuFromStat(fx(pid + ".stat"));
            T.yes(c[0] > 0, pid + " ticks");
            T.yes(c[1] > 0, pid + " start");
        }
        T.eq(14510L * 4, ProcStats.rssKbFromStatm(fx("14812.statm"), 4), "rss kb");
        T.eq(12L, ProcStats.cpuFromStat(fx("14812.stat"))[0], "utime+stime");
        T.eq(3370134L, ProcStats.cpuFromStat(fx("14812.stat"))[1], "start");
        T.eq(306124L + 52600 + 310055 + 12670376 + 11050 + 0 + 27134, ProcStats.totalJiffies(fx("stat")), "total jiffies");
        T.eq("com.amazon.webview.chromium", ProcStats.pkgOf(fx("30779.cmdline")), "child pkg");
        T.eq("com.wolf.firelauncher", ProcStats.pkgOf("com.wolf.firelauncher"), "plain");
        T.eq("com.x", ProcStats.pkgOf("com.x:remote"), "colon");
        T.eq("com.x", ProcStats.pkgOf("com.x\0\0"), "nul");
        T.eq(null, ProcStats.pkgOf(""), "empty");
        T.eq(null, ProcStats.pkgOf("/system/bin/foo"), "path");
    }

    public void testPct() {
        T.eq(5, ProcStats.pct(50, 1000, 4), "5");
        T.eq(100, ProcStats.pct(5000, 1000, 4), "clamp");
        T.eq(0, ProcStats.pct(0, 0, 4), "zero");
    }

    private static ProcStats.Proc p(int pid, String pkg, long rss, long ticks, long start) {
        ProcStats.Proc x = new ProcStats.Proc();
        x.pid = pid; x.pkg = pkg; x.rssKb = rss; x.ticks = ticks; x.startTicks = start;
        return x;
    }

    private static List<TaskSnapshot.App> agg(List<ProcStats.Proc> ps, String fg, Set<String> play, Set<String> cached, Set<String> launch) {
        return ProcStats.aggregate(ps, new HashMap<String, Long>(), 1000, 4, 10000, 100, fg, play, cached, launch);
    }

    private static Set<String> set(String... s) { return new HashSet<>(Arrays.asList(s)); }

    public void testAggregatesPerPackage() {
        List<ProcStats.Proc> ps = new ArrayList<>();
        ps.add(p(1, "com.x", 100, 10, 5000));
        ps.add(p(2, "com.x", 50, 10, 2000));
        List<TaskSnapshot.App> r = agg(ps, null, set(), set(), set("com.x"));
        T.eq(1, r.size(), "one app");
        T.eq(150L, r.get(0).rssKb, "rss");
        T.eq(2, r.get(0).procs, "procs");
        T.eq(80L, r.get(0).upSec, "up from earlier start");
    }

    public void testVanishedPidSkipped() {
        List<ProcStats.Proc> ps = new ArrayList<>();
        ps.add(p(1, "com.x", 100, 10, 5000));
        ps.add(p(2, "com.x", -1, 10, 2000));
        List<TaskSnapshot.App> r = agg(ps, null, set(), set(), set("com.x"));
        T.eq(1, r.get(0).procs, "procs");
        T.eq(100L, r.get(0).rssKb, "rss");
        T.eq(50L, r.get(0).upSec, "up");
    }

    public void testStateRules() {
        List<ProcStats.Proc> ps = new ArrayList<>();
        for (String s : new String[] {"fg", "pl", "ca", "bg", "sy"}) ps.add(p(s.hashCode(), "com." + s, 10, 0, 100));
        Set<String> launch = set("com.fg", "com.pl", "com.ca", "com.bg");
        Map<String, String> st = new HashMap<>();
        for (TaskSnapshot.App a : agg(ps, "com.fg", set("com.pl", "com.fg"), set("com.ca"), launch)) st.put(a.pkg, a.state);
        T.eq("fg", st.get("com.fg"), "fg wins over play");
        T.eq("play", st.get("com.pl"), "play");
        T.eq("cached", st.get("com.ca"), "cached");
        T.eq("bg", st.get("com.bg"), "bg");
        T.eq("sys", st.get("com.sy"), "sys");
    }

    public void testAggregateCpuNonZero() {
        List<ProcStats.Proc> ps = new ArrayList<>();
        ps.add(p(1, "com.x", 10, 70, 100));
        ps.add(p(2, "com.x", 10, 30, 100));
        Map<String, Long> prev = new HashMap<>();
        prev.put("com.x", 50L);
        List<TaskSnapshot.App> r = ProcStats.aggregate(ps, prev, 200, 4, 10000, 100, null, set(), set(), set("com.x"));
        T.eq(25, r.get(0).cpuPct, "(100-50)/200");
    }
}
