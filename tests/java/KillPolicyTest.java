import com.clean.stickman.core.*;
import java.nio.file.*;
import java.util.*;

public class KillPolicyTest {
    static Set<String> set(String... s) { return new HashSet<>(Arrays.asList(s)); }

    public void testKeepsPlayingMediaAndHome() {
        List<LruParser.Proc> ps = LruParser.parse(
            "  #4: cch    CAC  --- 100:com.spotify.tv.android/u0a1\n"
            + "  #3: home   HOME --- 101:com.wolf.firelauncher/u0a2\n"
            + "  #2: cch+5  CEM  --- 102:org.videolan.vlc/u0a3\n"
            + "  #1: fg     TOP  --- 103:org.xbmc.kodi/u0a4\n");
        T.eq(Arrays.asList("org.videolan.vlc"),
            KillPolicy.candidates(ps, "org.xbmc.kodi", set(), set("com.spotify.tv.android")), "candidates");
    }

    public void testRespectsWhitelistAndSelf() {
        List<LruParser.Proc> ps = LruParser.parse(
            "  #4: cch    CAC  --- 100:com.white.listed/u0a1\n"
            + "  #3: cch+15 CEM  --- 101:com.clean.stickman/u0a2\n"
            + "  #2: prev   LAST --- 102:org.videolan.vlc/u0a3\n"
            + "  #1: svcb   SVC  --- 103:com.clean.netfilter/u0a4\n");
        T.eq(Arrays.asList("org.videolan.vlc"),
            KillPolicy.candidates(ps, null, set("com.white.listed"), set()), "candidates");
    }

    public void testProtectedProcessShieldsWholePackage() {
        List<LruParser.Proc> ps = LruParser.parse(
            "  #3: cch+20 CACC --- 1:com.x.app:bg/u0a1\n"
            + "  #2: vis    IMPF --- 2:com.x.app/u0a1\n"
            + "  #1: svcl   SVC  --- 3:com.y.app/u0a2\n"
            + "  #0: pers   PER  LCM 4:com.z.app/u0a3\n");
        T.eq(Arrays.asList("com.y.app"), KillPolicy.candidates(ps, null, set(), set()), "vis keeps whole pkg");
    }

    public void testFixture() throws Exception {
        String f = new String(Files.readAllBytes(Paths.get("tests/fixtures/lru.txt")), "UTF-8");
        List<String> c = KillPolicy.candidates(LruParser.parse(f), "com.amazon.ftv.screensaver", set(), set());
        T.yes(c.contains("org.videolan.vlc"), "vlc is a candidate: " + c);
        T.yes(c.contains("app.wako"), "wako is a candidate");
        for (String keep : new String[] {"com.amazon.ftv.screensaver", "com.wolf.firelauncher", "com.amazon.tv.launcher",
                "com.clean.netfilter", "com.android.proxyhandler", "com.amazon.hedwig", "com.amazon.tv.settings.v2", "system", "com.amazon.vizzini",
                "com.spotify.tv.android", "com.amazon.tv.ime"})
            T.yes(!c.contains(keep), "kept " + keep + ": " + c);
        T.eq(new HashSet<>(c).size(), c.size(), "distinct");
    }

    public void testIsNeverKill() {
        for (String p : new String[] {"com.clean.netfilter", "com.android.proxyhandler", "com.wolf.firelauncher",
                "com.amazon.tv.launcher", "com.amazon.firehomestarter", "com.clean.stickman", null, "bad pkg; rm"})
            T.yes(KillPolicy.isNeverKill(p), "never kill " + p);
        T.yes(!KillPolicy.isNeverKill("org.videolan.vlc"), "vlc killable");
    }
}
