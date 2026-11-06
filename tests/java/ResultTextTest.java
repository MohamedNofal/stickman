import com.clean.stickman.core.Debloat;
import com.clean.stickman.core.ResultText;

public class ResultTextTest {
    public void testNeverRaw() {
        T.eq("Private DNS off, limit ads & tracking on", ResultText.human("OK DNS null null ADS 1"), "dns off");
        T.eq("Private DNS dns.adguard.com, limit ads & tracking off",
            ResultText.human("OK DNS hostname dns.adguard.com ADS 0"), "dns on");
        T.eq("Turned 3 app(s) back on.", ResultText.human("OK x\nRESTORED 3"), "restore");
        T.eq("Read the app list.", ResultText.human("OK package:a\n" + Debloat.SEP + "\n"), "debloat read");
        T.eq("Checked: button remapping and background automation are running.",
            ResultText.human("OK REMAPD 123 STICKD 456"), "ensure");
        T.eq("Done.", ResultText.human("OK whatever raw"), "unknown -> Done");
    }

    public void testPartialChecks() {
        T.eq("Checked: background automation isn't running.", ResultText.human("OK REMAPD 12 STICKD -"), "stickd down");
        T.eq("Checked: button remapping isn't running.", ResultText.human("OK REMAPD - STICKD 34"), "remapd down");
        T.eq("Checked: button remapping and background automation aren't running.",
            ResultText.human("OK REMAPD - STICKD -"), "both down");
    }

    public void testBoostAndTrimAreHonest() {
        T.eq("Nothing to close: no background apps were running.", ResultText.boost("freed_mb=0 killed=0"), "nothing");
        T.eq("Boost done: closed 2 apps and freed 38 MB.", ResultText.boost("freed_mb=38 killed=2"), "both");
        T.eq("Boost done: closed 1 app.", ResultText.boost("freed_mb=0 killed=1"), "no measured gain is not claimed");
        T.eq("Boost done: freed 12 MB. No background apps needed closing.", ResultText.boost("freed_mb=12 killed=0"), "caches only");
        T.eq(null, ResultText.boost("STATUS trim freed_mb=1 trimmed=2"), "not a boost line");
        T.eq("Trim done: 9 apps gave back 61 MB. Nothing was closed.", ResultText.trim("freed_mb=61 trimmed=9 why=now"), "trim");
        T.eq("Trim done: 1 app had nothing to give back right now. Nothing was closed.", ResultText.trim("freed_mb=0 trimmed=1 why=sleep"), "nothing back");
        T.eq("Nothing to trim: no apps were idle in the background.", ResultText.trim("freed_mb=0 trimmed=0 why=now"), "nothing to trim");
        T.eq(null, ResultText.trim("x"), "not a trim line");
    }

    public void testStateWordsAndReasons() {
        T.eq("idle in memory", ResultText.state("cached"), "cached is jargon");
        T.eq("in the background", ResultText.state("bg"), "bg");
        T.eq("in front", ResultText.state("fg"), "fg");
        T.eq("playing", ResultText.state("play"), "play");
        T.eq("system", ResultText.state("sys"), "sys");
        T.eq("?", ResultText.state(null), "null");
        T.eq("?", ResultText.state(""), "empty (never an empty word: the dialog capitalises it)");
        T.eq("it's always protected", ResultText.reason("never"), "never");
        T.eq("it's the app in front", ResultText.reason("in front"), "fg");
        T.eq("it's playing", ResultText.reason("playing"), "playing");
        T.eq("it's protected ★ (unprotect it first)", ResultText.reason("protected"), "protected");
        T.eq("it's a system app", ResultText.reason("system"), "system");
        T.eq("odd", ResultText.reason("odd"), "unknown reasons pass through");
        T.eq("40 s", ResultText.duration(40), "s");
        T.eq("42 min", ResultText.duration(2520), "min");
        T.eq("3 h 5 min", ResultText.duration(11100), "h min");
        T.eq("2 h", ResultText.duration(7200), "h");
        T.eq("0 s", ResultText.duration(-5), "negative");
    }

    public void testFailures() {
        T.eq("Nothing has run yet.", ResultText.human(""), "empty");
        T.eq("Another VPN was on, so nothing was applied.", ResultText.human("FAIL vpn_busy"), "vpn");
        T.eq("Problem: connect timed out", ResultText.human("FAIL connect timed out"), "fail");
    }
}
