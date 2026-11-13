import com.clean.stickman.core.PointerText;

public class PointerTextTest {
    public void testStates() {
        T.eq("Turn the pointer on", PointerText.button(null), "nothing seen: off");
        T.eq("Turn the pointer off", PointerText.button("on (uinput)"), "on");
        T.eq("Turn the pointer on", PointerText.button("off"), "off");
        T.eq("On.", PointerText.state("on (uhid)"), "real pointer");
        T.yes(PointerText.state("on (drawn)").contains("draws the pointer"), "drawn pointer explained");
        T.yes(PointerText.state("unavailable: buttons cannot be held back in listen-only mode").contains("hold back"), "listen-only");
        T.yes(PointerText.state("unavailable: Permission denied").startsWith("Couldn't"), "no pointer device");
        T.yes(!PointerText.isOn("unavailable: x"), "unavailable is not on");
    }

    public void testCommand() {
        T.eq("echo on > /data/local/tmp/stickman/mouse.cmd.tmp && mv /data/local/tmp/stickman/mouse.cmd.tmp /data/local/tmp/stickman/mouse.cmd",
            com.clean.stickman.core.cmd.RemoteCmds.pointer(true), "atomic on");
        T.yes(com.clean.stickman.core.cmd.RemoteCmds.pointer(false).startsWith("echo off "), "off");
    }
}
