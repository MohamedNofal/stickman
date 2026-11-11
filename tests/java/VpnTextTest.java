import com.clean.stickman.core.VpnText;

public class VpnTextTest {
    public void testSaysButtonsDontNeedTheVpn() {
        T.yes(VpnText.confirmMessage(true).contains("keep working with your VPN on"), "running: buttons keep working");
        T.yes(VpnText.confirmMessage(true).contains("a few seconds"), "only briefly");
        T.yes(!VpnText.confirmMessage(true).contains("aren't working"), "running: no false alarm");
        T.yes(VpnText.confirmMessage(false).contains("aren't working yet"), "not started: honest");
        T.yes(VpnText.confirmMessage(false).contains("keep working with your VPN on"), "not started: what happens after");
        T.yes(VpnText.cantRead(true).contains("still work"), "pages: buttons still work");
        T.yes(!VpnText.cantRead(false).contains("still work"), "pages: no claim when not started");
    }

    /** The sidebar line is narrow: one short line (two lines shrink the menu above it). */
    public void testStatusLineFits() {
        for (boolean up : new boolean[] { true, false }) {
            String s = VpnText.statusLine(up);
            T.yes(!s.contains("\n"), "one line");
            T.yes(s.length() <= 26, "fits the sidebar: " + s);
        }
    }
}
