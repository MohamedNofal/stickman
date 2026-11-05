import com.clean.stickman.core.*;
import java.nio.file.*;
import java.util.*;

public class KillPolicyTest {
    static Set<String> set(String... s) { return new HashSet<>(Arrays.asList(s)); }
    /** Every package in ps counts as launchable (tests that are not about the launchable filter). */
    static Set<String> all(List<LruParser.Proc> ps) { Set<String> s = new HashSet<>(); for (LruParser.Proc p : ps) s.add(p.pkg); return s; }
    static String fixture() throws Exception { return new String(Files.readAllBytes(Paths.get("tests/fixtures/lru.txt")), "UTF-8"); }
    static final Set<String> FIXTURE_LAUNCHABLE = set("org.videolan.vlc", "app.wako", "com.amazon.firetv.youtube",
        "com.amazon.firebat", "com.spotify.tv.android", "com.wolf.firelauncher", "com.amazon.tv.launcher", "com.nordvpn.android");

    public void testKeepsPlayingMediaAndHome() {
        List<LruParser.Proc> ps = LruParser.parse(
            "  #4: cch    CAC  --- 100:com.spotify.tv.android/u0a1\n"
            + "  #3: home   HOME --- 101:com.wolf.firelauncher/u0a2\n"
            + "  #2: cch+5  CEM  --- 102:org.videolan.vlc/u0a3\n"
            + "  #1: fg     TOP  --- 103:org.xbmc.kodi/u0a4\n");
        T.eq(Arrays.asList("org.videolan.vlc"),
            KillPolicy.candidates(ps, "org.xbmc.kodi", set(), set("com.spotify.tv.android"), all(ps)), "candidates");
    }

    public void testRespectsWhitelistAndSelf() {
        List<LruParser.Proc> ps = LruParser.parse(
            "  #4: cch    CAC  --- 100:com.white.listed/u0a1\n"
            + "  #3: cch+15 CEM  --- 101:com.clean.stickman/u0a2\n"
            + "  #2: prev   LAST --- 102:org.videolan.vlc/u0a3\n"
            + "  #1: svcb   SVC  --- 103:com.clean.netfilter/u0a4\n");
        T.eq(Arrays.asList("org.videolan.vlc"),
            KillPolicy.candidates(ps, null, set("com.white.listed"), set(), all(ps)), "candidates");
    }

    public void testProtectedProcessShieldsWholePackage() {
        List<LruParser.Proc> ps = LruParser.parse(
            "  #3: cch+20 CACC --- 1:com.x.app:bg/u0a1\n"
            + "  #2: vis    IMPF --- 2:com.x.app/u0a1\n"
            + "  #1: svcl   SVC  --- 3:com.y.app/u0a2\n"
            + "  #0: pers   PER  LCM 4:com.z.app/u0a3\n");
        T.eq(Arrays.asList("com.y.app"), KillPolicy.candidates(ps, null, set(), set(), all(ps)), "vis keeps whole pkg");
    }

    public void testFixture() throws Exception {
        List<LruParser.Proc> ps = LruParser.parse(fixture());
        List<String> c = KillPolicy.candidates(ps, "com.amazon.ftv.screensaver", set(), set(), all(ps));
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

    public void testFixtureOnlyLaunchableApps() throws Exception {
        List<String> c = KillPolicy.candidates(LruParser.parse(fixture()), "com.amazon.ftv.screensaver", set(), set(), FIXTURE_LAUNCHABLE);
        T.eq(set("org.videolan.vlc", "app.wako", "com.amazon.firebat", "com.amazon.firetv.youtube"), new HashSet<>(c), "launchable cached/prev apps only");
        for (String svc : new String[] {"com.amazon.whisperjoin.middleware.v2.np", "com.amazon.logan", "com.amazon.bueller.photos",
                "com.amazon.prism.android.service", "com.amazon.neopactservice", "com.amazon.ceviche", "com.android.permissioncontroller",
                "com.amazon.webview.chromium"})
            T.yes(!c.contains(svc), "service/provider not churned: " + svc + " in " + c);
    }

    public void testSvcbOnlyForLaunchable() {
        List<LruParser.Proc> ps = LruParser.parse("  #17: svcb   SVC  --- 30397:com.amazon.whisperjoin.middleware.v2.np/u0a212\n");
        T.eq(Arrays.asList(), KillPolicy.candidates(ps, null, set(), set(), set("org.videolan.vlc")), "whisperjoin svcb not launchable");
        T.eq(Arrays.asList("com.amazon.whisperjoin.middleware.v2.np"),
            KillPolicy.candidates(ps, null, set(), set(), set("com.amazon.whisperjoin.middleware.v2.np")), "svcb of a launchable app");
    }

    public void testPerceptibleProtectsWholePackage() throws Exception {
        // Spotify plays through a foreground service (prcp FGS) with no media session attributed: never killed.
        List<String> c = KillPolicy.candidates(LruParser.parse(fixture()), null, set(), set(), FIXTURE_LAUNCHABLE);
        T.yes(!c.contains("com.spotify.tv.android"), "prcp spotify kept: " + c);
        T.yes(!c.contains("com.nordvpn.android"), "prcl kept: " + c);
        for (String adj : new String[] {"fore", "prcp", "prcl", "psvc", "bkup", "hvy"}) {
            List<LruParser.Proc> ps = LruParser.parse(
                "  #2: cch+5  CEM  --- 1:com.x.app:bg/u0a1\n  #1: " + adj + "   IMPB --- 2:com.x.app/u0a1\n");
            T.eq(Arrays.asList(), KillPolicy.candidates(ps, null, set(), set(), set("com.x.app")), adj + " shields cached sibling");
        }
        List<LruParser.Proc> fgs = LruParser.parse("  #2: cch+5  CEM  --- 1:com.x.app:bg/u0a1\n  #1: svc    FGS  --- 2:com.x.app/u0a1\n");
        T.eq(Arrays.asList(), KillPolicy.candidates(fgs, null, set(), set(), set("com.x.app")), "FGS state shields");
    }

    public void testNoLaunchableListFailsClosed() throws Exception {
        List<LruParser.Proc> ps = LruParser.parse(fixture());
        T.eq(Arrays.asList(), KillPolicy.candidates(ps, null, set(), set(), set()), "empty");
        T.eq(Arrays.asList(), KillPolicy.candidates(ps, null, set(), set(), null), "null");
    }

    public void testParseLaunchable() {
        String out = "20 activities found:\n  Activity #0:\n    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n"
            + "    com.amazon.firetv.youtube/dev.cobalt.app.MainActivity\n  Activity #1:\n"
            + "    com.wolf.firelauncher/.screens.launcher.LauncherActivity\n    org.videolan.vlc/.StartActivity\r\n"
            + "No activities found\n    bad pkg/x\n";
        T.eq(set("com.amazon.firetv.youtube", "com.wolf.firelauncher", "org.videolan.vlc"), KillPolicy.parseLaunchable(out), "parsed");
        T.eq(set(), KillPolicy.parseLaunchable(null), "null");
        T.yes(KillPolicy.LAUNCHABLE_CMD.contains("LEANBACK_LAUNCHER") && KillPolicy.LAUNCHABLE_CMD.contains("category.LAUNCHER"), "cmd");
    }

    public void testAppstoreNeverKilled() { T.yes(KillPolicy.isNeverKill("com.amazon.venezia"), "venezia"); }
}
