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
}
