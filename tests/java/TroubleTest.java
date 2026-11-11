import com.clean.stickman.core.Trouble;

public class TroubleTest {
    public void testKindsFromRealMessages() {
        // MiniTcp.connect
        T.eq(Trouble.ADB_OFF, Trouble.kind("FAIL no SYN-ACK from 10.99.0.1:5555"), "no syn-ack");
        T.eq(Trouble.ADB_OFF, Trouble.kind("RST during connect (adbd refused)"), "rst");
        // AdbClient.connect
        T.eq(Trouble.NOT_ALLOWED, Trouble.kind("FAIL adb auth rejected"), "auth rejected");
        // MiniTcp read / AdbClient.exec
        T.eq(Trouble.TIMEOUT, Trouble.kind("FAIL read timeout"), "read timeout");
        T.eq(Trouble.TIMEOUT, Trouble.kind("exec timeout"), "exec timeout");
        T.eq(Trouble.DROPPED, Trouble.kind("FAIL connection reset"), "reset");
        T.eq(Trouble.DROPPED, Trouble.kind("EOF"), "eof");
        T.eq(Trouble.DROPPED, Trouble.kind("write not acked"), "not acked");
        // BridgeService / BridgeWorker
        T.eq(Trouble.VPN, Trouble.kind("FAIL VPN permission missing: open Stickman and accept its VPN request"), "vpn missing");
        T.eq(Trouble.VPN, Trouble.kind("FAIL VPN not allowed: open Stickman and accept its VPN request"), "vpn not allowed");
        // Bridge.VPN_BUSY
        T.eq(Trouble.OTHER_VPN, Trouble.kind("FAIL vpn_busy"), "other vpn");
        T.eq(Trouble.OTHER, Trouble.kind("FAIL bad adb length 99"), "unknown");
        T.eq(Trouble.OTHER, Trouble.kind(""), "empty");
        T.eq(Trouble.OTHER, Trouble.kind(null), "null");
    }

    public void testReason() {
        T.eq("no SYN-ACK from 10.99.0.1:5555", Trouble.reason("FAIL no SYN-ACK from 10.99.0.1:5555"), "strips FAIL");
        T.eq("exec timeout", Trouble.reason("  exec timeout "), "trims");
        T.eq("", Trouble.reason(null), "null");
        T.eq("", Trouble.reason("FAIL"), "bare FAIL");
    }

    public void testTextsNeverRawForKnownKinds() {
        String[] known = { "FAIL no SYN-ACK from 10.99.0.1:5555", "FAIL adb auth rejected", "FAIL read timeout",
            "FAIL connection reset", "FAIL VPN permission missing: x", "FAIL vpn_busy" };
        for (String r : known) {
            String s = Trouble.shortText(r), a = Trouble.advice(r), t = Trouble.title(r);
            T.yes(!s.contains("SYN") && !s.contains("adb") && !s.contains("FAIL"), "short is plain: " + s);
            T.yes(!a.contains("SYN-ACK") && !a.contains("FAIL") && !a.contains("vpn_busy"), "advice is plain: " + a);
            T.yes(s.length() <= 44, "short fits the sidebar: " + s);
            T.yes(a.length() > 40, "advice says what to do: " + a);
            T.yes(t.length() > 0 && t.length() < 40, "title: " + t);
        }
        T.yes(Trouble.advice("FAIL no SYN-ACK from 10.99.0.1:5555").contains("Developer options"), "adb-off advice names the menu");
        T.yes(Trouble.advice("FAIL adb auth rejected").contains("Always allow"), "not-allowed advice names the tick box");
        T.yes(Trouble.advice("FAIL VPN not allowed: x").contains("VPN"), "vpn advice");
    }

    public void testUnknownKeepsTheReason() {
        T.eq("Problem: bad adb length 99", Trouble.shortText("FAIL bad adb length 99"), "short keeps raw reason");
        T.eq("Problem: unknown error", Trouble.shortText("FAIL"), "empty reason");
        T.yes(Trouble.advice("FAIL bad adb length 99").contains("bad adb length 99"), "advice keeps raw reason");
        T.eq("Something went wrong", Trouble.title("FAIL whatever"), "generic title");
    }

    public void testAskUser() {
        T.yes(Trouble.askUser("FAIL no SYN-ACK from 10.99.0.1:5555", false), "adb off: always");
        T.yes(Trouble.askUser("FAIL adb auth rejected", false), "not allowed: always");
        T.yes(Trouble.askUser("FAIL VPN permission missing: x", false), "vpn: always");
        T.yes(Trouble.askUser("FAIL read timeout", true), "timeout on first run");
        T.yes(!Trouble.askUser("FAIL read timeout", false), "timeout later: status line only");
        T.yes(Trouble.askUser("FAIL connection reset", true), "dropped on first run");
        T.yes(!Trouble.askUser("FAIL connection reset", false), "dropped later: status line only");
        T.yes(!Trouble.askUser("FAIL vpn_busy", true), "other vpn: never a dialog");
        T.yes(!Trouble.askUser("FAIL bad adb length 99", true), "unknown: never a dialog");
        T.yes(!Trouble.askUser("", true), "empty: nothing to ask");
    }

    public void testVpnTaken() {
        String r = "FAIL " + Trouble.TAKEN_REASON;
        T.eq(Trouble.VPN_TAKEN, Trouble.kind(r), "vpn taken");
        T.eq(true, Trouble.askUser(r, false), "always explained");
        T.eq(true, Trouble.advice(r).contains("ADB debugging is fine"), "not blamed on ADB");
        T.eq(true, Trouble.shortText(r).length() <= 30, "short line fits");
    }

    /** Background "not sent" can't know whether the helpers run: it must not promise the buttons work. */
    public void testOtherVpnAdviceHonestWhenNotStarted() {
        String a = Trouble.advice("FAIL vpn_busy");
        T.yes(a.contains("once it is running"), "conditional");
        T.yes(!a.contains("Your remapped buttons keep working"), "no unconditional promise");
    }
}
