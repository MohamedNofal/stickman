import com.clean.stickman.core.Commands;
import com.clean.stickman.core.Catalog;

public class CommandsTest {
    public void testDebloatRefusesCritical() {
        T.eq(null, Commands.debloat("com.amazon.tv.launcher", true), "launcher");
        T.eq(null, Commands.debloat("com.amazon.tv.settings.v2", true), "settings");
        T.eq(null, Commands.debloat("com.clean.netfilter", true), "netfilter");
        T.yes(Commands.debloat("com.amazon.tv.releasenotes", true) != null, "ok pkg");
    }
    public void testPrivateDnsOffAndOn() {
        T.yes(Commands.privateDns(null).contains("private_dns_mode off"), "off");
        String on = Commands.privateDns("dns.adguard-dns.com");
        T.yes(on.contains("hostname") && on.contains("private_dns_specifier dns.adguard-dns.com") && on.contains("dns_check"), on);
    }
    public void testClearDeadProxyKeepsListening() {
        String c = Commands.clearDeadProxy();
        T.yes(c.contains("/proc/net/tcp") && c.contains("tcp6") && c.contains("0A"), c);
        T.yes(c.contains("PROXY kept") && c.contains("PROXY cleared") && c.contains("PROXY none"), c);
    }
    public void testClearDeadProxyFailsClosed() {
        String c = Commands.clearDeadProxy();
        T.yes(c.contains("*[!0-9]*") && c.contains("[ -z \"$hex\" ]"), "guards non-numeric/empty port: " + c);
        T.yes(c.indexOf("[ -z \"$hex\" ]") < c.indexOf("PROXY cleared"), "guard precedes clear");
    }
    public void testNextDnsValidation() {
        T.eq("abc123.dns.nextdns.io", Catalog.nextDns("abc123"), "ok");
        T.eq(null, Catalog.nextDns("a;rm -rf /"), "inject");
        T.eq(null, Catalog.nextDns("a b"), "space");
        T.eq(null, Catalog.nextDns(""), "empty");
        T.eq(null, Commands.privateDns("a;b"), "bad host");
    }
    public void testShortcutsScreensaverAndTroubleshooting() {
        boolean ss = false, tr = false;
        for (String[] s : Catalog.SHORTCUTS) {
            if (s[1].equals("com.amazon.device.settings.action.SCREENSAVERS")) ss = true;
            if (s[0].equals("Troubleshooting") && s[1].equals("com.amazon.firetv.troubleshooting/.ui.MainActivity")) tr = true;
        }
        T.yes(ss && tr, "ss=" + ss + " tr=" + tr);
    }
    public void testScreensaverAndActAndTime() {
        T.yes(Commands.screensaver(false).contains("screensaver_enabled 0"), "ss");
        T.yes(Commands.act("open_screen", "x.Y").contains("echo 'open_screen x.Y' > /data/local/tmp/stickman/act.fifo"), "act");
        String t = Commands.setTime(1700000000000L);
        T.yes(t.contains("cmd alarm set-time 1700000000000") && t.contains("ntp_server time.google.com") && t.contains("auto_time 1"), t);
        T.yes(Commands.animation(0.5f).contains("window_animation_scale 0.5"), "anim");
        T.yes(Commands.sleepTimeout(900000).contains("sleep_timeout 900000"), "sleep");
    }
    public void testCatalog() {
        T.yes(Catalog.NEVER_DISABLE.contains("com.amazon.tv.launcher"), "nd");
        T.yes(Catalog.DNS_PRESETS.length == 6, "dns presets");
        for (String[] s : Catalog.SHORTCUTS) T.yes(s.length == 2 && s[1].length() > 0, s[0]);
    }
}
