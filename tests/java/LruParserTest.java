import com.clean.stickman.core.*;
import java.nio.file.*;
import java.util.*;

public class LruParserTest {
    static String fixture(String n) throws Exception {
        return new String(Files.readAllBytes(Paths.get("tests/fixtures/" + n)), "UTF-8");
    }

    static LruParser.Proc find(List<LruParser.Proc> ps, String pkg) {
        for (LruParser.Proc p : ps) if (p.pkg.equals(pkg)) return p;
        return null;
    }

    public void testParsesFixture() throws Exception {
        List<LruParser.Proc> ps = LruParser.parse(fixture("lru.txt"));
        T.yes(ps.size() > 5, "count > 5: " + ps.size());
        // On this stick Wolf is not the system HOME (Home Switch redirects), so dumpsys gives it
        // adj prev/fg rather than home; the system launcher holds adj home.
        LruParser.Proc wolf = find(ps, "com.wolf.firelauncher");
        T.yes(wolf != null, "wolf present");
        T.eq("com.wolf.firelauncher", wolf.pkg, "wolf pkg");
        T.eq(329, wolf.pid, "wolf pid");
        LruParser.Proc home = find(ps, "com.amazon.tv.launcher");
        T.eq("home", home.adj, "launcher adj");
        T.eq("CEM", home.state, "launcher state");
    }

    public void testLineFormats() {
        List<LruParser.Proc> ps = LruParser.parse(
            "  #12: cch+15 CEM  ---- 4321:org.videolan.vlc/u0a88\n"
            + "  #33: cch+ 5 CEM  --- 4192:com.amazon.bueller.photos/u0a124\n"
            + "  # 9: vis    BFGS --- 1390:android.ext.services/u0a236\n"
            + "  #36: cch+20 CACC --- 13387:com.amazon.webview.chromium:AWVArcusServiceProcess/u0a227 act:client\n"
            + "  # 0: sys    PER  LCM 697:system/1000\n"
            + "  Activities:\ngarbage\n");
        T.eq(5, ps.size(), "parsed");
        LruParser.Proc v = ps.get(0);
        T.eq("cch+15", v.adj, "adj"); T.eq("CEM", v.state, "state"); T.eq(4321, v.pid, "pid");
        T.eq("org.videolan.vlc", v.process, "proc"); T.eq("org.videolan.vlc", v.pkg, "pkg");
        T.eq("cch+5", ps.get(1).adj, "spaced adj");
        T.eq("vis", ps.get(2).adj, "single-digit index");
        T.eq("com.amazon.webview.chromium:AWVArcusServiceProcess", ps.get(3).process, "proc with :");
        T.eq("com.amazon.webview.chromium", ps.get(3).pkg, "pkg up to :");
        T.eq("system", ps.get(4).pkg, "system");
    }

    /** Fire OS 5-7 have no "dumpsys activity lru": the list comes from "dumpsys activity processes". */
    public void testOldAndroidProcessList() {
        String a7 = "  Process LRU list (sorted by oom_adj, 19 total, non-act at 6, non-svc at 6):\n"
            + "    PERS #18: sys   F/ /P  trm: 0 1147:system/1000 (fixed)\n"
            + "    Proc # 0: fore  T/A/T  trm: 0 5189:com.clean.stickman/u0a63 (top-activity)\n"
            + "        android.ext.services/.notification.Ranker<=Proc{1147:system/1000}\n"
            + "    Proc # 2: cch   B/ /CA trm: 0 4797:com.android.contacts/u0a5 (cch-act)\n"
            + "    Proc #11: cch   B/ /CE trm: 0 1889:android.process.acore/u0a1 (provider)\n";
        java.util.List<LruParser.Proc> p = LruParser.parse(a7);
        T.eq(4, p.size(), "four processes, the dependency line is skipped");
        T.eq("fg", p.get(1).adj, "fore -> fg");
        T.eq("TOP", p.get(1).state, "T -> TOP");
        T.eq("com.android.contacts", p.get(2).pkg, "pkg");
        T.eq("CAC", p.get(2).state, "CA -> CAC");
        T.eq(4797, p.get(2).pid, "pid");
        String a9 = "    Proc # 3: vis   F/ /BFGS trm: 0 4315:com.android.launcher3/u0a32 (service)\n";
        T.eq("BFGS", LruParser.parse(a9).get(0).state, "Android 9 states are already the modern names");
        // read(): falls back to the processes dump when the lru command is unknown
        java.util.List<LruParser.Proc> r = LruParser.read(cmd -> cmd.equals(com.clean.stickman.core.cmd.StatusCmds.lru()) ? "Unknown command: lru\nBad activity command" : a7);
        T.eq(4, r.size(), "fallback used");
        T.eq(1, LruParser.read(cmd -> "  #25: fg     TPSL --- 22837:com.wolf.firelauncher/u0a248 act:activities|recents\n").size(), "modern output: no fallback");
    }

    /** "vis  +1" style adj (offset after spaces) must not drop the line: a dropped visible process un-protects its package. */
    public void testOldAdjWithOffset() {
        String t = "    Proc # 5: vis  +1 F/ /IMPF trm: 0 2222:com.x.app/u0a70 (service)\n"
            + "    Proc # 6: cch+2 B/ /CEM trm: 0 2223:com.x.app:bg/u0a70 (cch-empty)\n";
        java.util.List<LruParser.Proc> p = LruParser.parse(t);
        T.eq(2, p.size(), "both lines");
        T.eq("vis", p.get(0).adj, "adj");
        T.yes(com.clean.stickman.core.KillPolicy.busyPackages(p).contains("com.x.app"), "visible process protects the package");
    }
}
