import com.clean.stickman.core.AmazonUninstall;

import java.util.List;

public class AmazonUninstallTest {
    public void testParse() {
        String out = "UAPP com.amazon.cloud9 stub 0\nUAPP com.amazon.bueller.music full 43008\nUAPP com.amazon.spiderpork full 25600\n"
            + "UAPP bad;name full 1\nUAPP com.amazon.x weird 1\nnoise\nUAPP_END\n";
        List<AmazonUninstall.App> l = AmazonUninstall.parse(out);
        T.eq(3, l.size(), "three valid rows");
        T.eq("com.amazon.cloud9", l.get(0).pkg, "first");
        T.yes(!l.get(0).installed(), "stub = already removed");
        T.yes(l.get(1).installed(), "full = installed");
        T.eq(43008L, l.get(1).kb, "size");
        T.yes(AmazonUninstall.complete(out), "complete");
        T.yes(!AmazonUninstall.complete("UAPP com.amazon.cloud9 stub 0"), "cut off");
        T.eq(0, AmazonUninstall.parse(null).size(), "null");
    }

    public void testCommands() throws Exception {
        T.eq("am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.amazon.cloud9", AmazonUninstall.open("com.amazon.cloud9"), "open");
        T.eq(null, AmazonUninstall.open("x; reboot"), "no injection");
        T.yes(AmazonUninstall.CMD.contains(AmazonUninstall.URI) && AmazonUninstall.CMD.endsWith("echo UAPP_END"), "list command");
        java.io.File f = java.io.File.createTempFile("amazonuninstall", ".sh");
        java.nio.file.Files.write(f.toPath(), AmazonUninstall.CMD.getBytes("UTF-8"));
        T.eq(0, new ProcessBuilder("sh", "-n", f.getPath()).start().waitFor(), "valid shell");
        f.delete();
    }

    public void testUpdates() throws Exception {
        String out = "UPD com.amazon.firebat 29900 0\nUPD com.amazon.gamehub 8400 1\nUPD com.amazon.alexamediaplayer.runtime.ftv 47000 1\nUPD bad;x 1 1\nUPD_END\n";
        java.util.List<AmazonUninstall.Update> u = AmazonUninstall.parseUpdates(out);
        T.eq(3, u.size(), "valid rows");
        T.eq("com.amazon.alexamediaplayer.runtime.ftv", u.get(0).pkg, "biggest first");
        T.yes(u.get(0).disabled && !u.get(1).disabled, "disabled flag");
        String r = AmazonUninstall.removeUpdate("com.amazon.gamehub");
        T.yes(r.startsWith("pm uninstall-system-updates com.amazon.gamehub") && r.contains("pm disable-user --user 0 com.amazon.gamehub"), "remove + keep disabled");
        T.eq(null, AmazonUninstall.removeUpdate("com.amazon.tv.launcher"), "protected never");
        T.eq(null, AmazonUninstall.removeUpdate("a; b"), "bad name");
        java.io.File f = java.io.File.createTempFile("amazonupdates", ".sh");
        java.nio.file.Files.write(f.toPath(), AmazonUninstall.UPDATES_CMD.getBytes("UTF-8"));
        T.eq(0, new ProcessBuilder("sh", "-n", f.getPath()).start().waitFor(), "valid shell");
        f.delete();
    }
}
