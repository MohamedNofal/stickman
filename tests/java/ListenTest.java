import com.clean.stickman.core.Listen;
import com.clean.stickman.core.Originals;

public class ListenTest {
    public void testCommands() {
        T.yes(Listen.ON.contains("touch " + Originals.DIR + "/listen"), Listen.ON);
        T.yes(Listen.OFF.startsWith("rm -f " + Originals.DIR + "/listen"), Listen.OFF);
        T.eq(true, Listen.onOk(Listen.ON, "LISTENING\n"), "on ok");
        T.eq(false, Listen.onOk(Listen.ON, "FAIL vpn_busy"), "on failed");
        T.eq(false, Listen.onOk(Listen.OFF, "LISTENING"), "other command");
    }

    public void testRemapdLogLineMatchesSource() throws Exception {
        String c = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("native/remapd.c")));
        T.yes(c.contains("\"" + Listen.REMAPD_ON + "\""), "remapd logs " + Listen.REMAPD_ON);
        T.yes(c.contains("LISTEN_PATH DIR_PATH \"listen\""), "same file name as Listen.FILE");
        T.yes(c.contains("if (listening) return;"), "keys swallowed while listening");
    }
}
