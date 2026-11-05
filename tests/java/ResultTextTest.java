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

    public void testFailures() {
        T.eq("Nothing has run yet.", ResultText.human(""), "empty");
        T.eq("Another VPN was on, so nothing was applied.", ResultText.human("FAIL vpn_busy"), "vpn");
        T.eq("Problem: connect timed out", ResultText.human("FAIL connect timed out"), "fail");
    }
}
