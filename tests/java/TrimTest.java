import com.clean.stickman.core.Actions;
import com.clean.stickman.core.BgTime;
import com.clean.stickman.core.BootStart;
import com.clean.stickman.core.LruParser;
import com.clean.stickman.core.Trim;

import java.util.List;

public class TrimTest {
    static final String LRU = "ACTIVITY MANAGER LRU PROCESSES (dumpsys activity lru)\n  Activities:\n"
        + "  #28: fg     TOP  LCM 2757:app.wako/u0a250 act:activities|recents\n"
        + "  #27: prev   LAST --- 28481:com.wolf.firelauncher/u0a248 act:activities|recents\n"
        + "  #27: prev   LAST --- 28482:com.wolf.firelauncher/u0a248 act:activities|recents\n"
        + "  #26: cch    CRE  --- 16229:com.clean.stickman/u0a269 act:recents\n"
        + "  #25: home   HOME --- 24084:com.amazon.tv.launcher/u0a199 act:activities|recents\n"
        + "  #20: vis    BTOP --- 2969:com.amazon.webview.chromium:AWVMetricsProcess/u0a227 act:client\n"
        + "  Other:\n"
        + "  #29: sys    PER  LCM 692:system/1000\n"
        + "  #26: pers   PER  LCM 1347:com.amazon.tcomm/u0a176\n"
        + "  #19: prcp   FGS  --- 5860:com.clean.netfilter/u0a252\n"
        + "  #17: svcb   SVC  --- 24644:com.amazon.whisperlink.core.android/u0a108\n";

    public void testTargetsSkipFrontSystemAndOurs() {
        List<String> t = Trim.targets(LruParser.parse(LRU), "app.wako");
        T.eq("[com.wolf.firelauncher, com.amazon.tv.launcher, com.amazon.webview.chromium:AWVMetricsProcess, com.amazon.tcomm, "
            + "com.amazon.whisperlink.core.android]", t.toString(), "persistent and home are trimmed, fg/system/ours never; no duplicates");
        T.yes(Trim.targets(LruParser.parse(LRU), "com.wolf.firelauncher").indexOf("com.wolf.firelauncher") < 0, "fg skipped by package");
        T.yes(Trim.cmd("a.b:c").startsWith("am send-trim-memory a.b:c COMPLETE") && Trim.cmd("a.b").endsWith("; true"), "quiet command");
        long[] s = Trim.parseStatus("STATUS trim freed_mb=61 trimmed=9 why=sleep");
        T.eq(61L, s[0], "mb");
        T.eq(9L, s[1], "count");
        T.eq(null, Trim.parseStatus("x"), "none");
    }

    public void testActions() {
        T.yes(Actions.menu().contains(Actions.TRIM), "Trim is a button action");
        T.yes(!Actions.actName(Actions.TRIM).equals(Actions.TRIM), "named");
    }

    public void testBootStart() {
        String out = "  android/com.android.server.BootReceiver\n  app.wako/app.wako.Boot\n  com.netflix.ninja/com.netflix.ninja.BootReceiver\n"
            + "  com.amazon.ods.kindleconnect/.Boot\n  com.amazon.tcomm/.Boot\n  com.amazon.tv.launcher/.Boot\n  com.clean.stickman/.BootReceiver\n"
            + "  com.android.providers.tv/.Boot\n  org.xbmc.kodi/.Boot\n  app.wako/app.wako.OtherBoot\n  bad name/x\n";
        T.eq("[app.wako, com.netflix.ninja, org.xbmc.kodi, com.amazon.ods.kindleconnect]", BootStart.parse(out).toString(),
            "third-party first, then Amazon; protected (launcher, tcomm, stickman) and Android's own dropped; unique");
        T.eq(0, BootStart.parse(null).size(), "null");
    }

    public void testBgTime() {
        String out = "  * com.amazon.vizzini / u0a210 / v662166410:\n           TOTAL: 100% (33MB-42MB-49MB/4.6MB-26MB-37MB/80MB-73MB-87MB over 25)\n"
            + "  * system / 1000 / v30:\n           TOTAL: 100% (121MB-131MB-141MB/90MB-103MB-119MB/161MB-155MB-183MB over 8)\n"
            + "  * com.netflix.ninja / u0a242 / v27249:\n           TOTAL: 73% (95MB-99MB-103MB/85MB-88MB-92MB/151MB-152MB-161MB over 9)\n"
            + "             Top: 2.1% (95MB-99MB-103MB/85MB-88MB-92MB/151MB-152MB-161MB over 1)\n"
            + "  * com.amazon.alexamediaplayer.runtime.ftv / u0a116 / v16488010:\n           TOTAL: 99% (16MB-17MB-18MB/756KB-2.2MB-4.2MB/29MB-27MB-30MB over 9)\n"
            + "  * com.amazon.alexamediaplayer.runtime.ftv:smbs / u0a116 / v16488010:\n           TOTAL: 11% (27MB-40MB-46MB/9.5MB-31MB-39MB/99MB-104MB-110MB over 3)\n"
            + "  * com.clean.stickman / u0a269 / v9:\n           TOTAL: 23% (29MB-39MB-54MB/21MB-30MB-46MB/102MB-106MB-122MB over 31)\n"
            + "  * app.wako / u0a250 / v120122001:\n           TOTAL: 3.5% (326MB-434MB-478MB/307MB-366MB-437MB/403MB-429MB-510MB over 9)\n"
            + "  * com.amazon.prism.android.service / u0a161 / v100169010:\n           TOTAL: 1.5%\n"
            + "  * com.amazon.gamehub / u0a148 / v1065510:\n           TOTAL: 0.35% (223MB-223MB-223MB/212MB-212MB-212MB/303MB-303MB-303MB over 1)\n";
        List<BgTime.Entry> es = BgTime.parse(out);
        T.eq(2, es.size(), "alexamediaplayer + netflix: vizzini protected, system not a user app, stickman ours, wako/prism/gamehub under 5%");
        T.eq("com.amazon.alexamediaplayer.runtime.ftv", es.get(0).pkg, "busiest first");
        T.eq(99, es.get(0).pct, "max share over its processes");
        T.eq(57, es.get(0).avgMb, "avg PSS summed over processes (17 + 40)");
        T.eq("com.netflix.ninja", es.get(1).pkg, "netflix");
        T.eq(73, es.get(1).pct, "pct");
        T.eq(99, es.get(1).avgMb, "avg of main process only (Top line ignored)");
        T.eq(0, BgTime.parse("").size(), "empty");
    }
}
