import com.clean.stickman.core.*;
import java.util.*;

public class SpoolTest {
    public void testPendingSortedActOnly() {
        String[] names = {"1730000000200-55-1.act", "1730000000100-77-3.act", "1730000000150-9-1.tmp", "junk", "1730000000100-12-2.act"};
        T.eq(Arrays.asList("1730000000100-12-2.act", "1730000000100-77-3.act", "1730000000200-55-1.act"), Spool.pending(names), "order");
    }
    public void testTmpIgnored() {
        T.eq(Collections.emptyList(), Spool.pending(new String[] {"1-2-3.tmp", "x.act.tmp"}), "tmp");
        T.eq(Collections.emptyList(), Spool.pending(null), "null");
    }
    public void testNumericPrefixBeatsLexical() {
        // a shorter timestamp is older even though it sorts later lexically
        T.eq(Arrays.asList("999-1-1.act", "1000-1-1.act"), Spool.pending(new String[] {"1000-1-1.act", "999-1-1.act"}), "numeric");
    }
    public void testParse() {
        T.eq("screenshot", Spool.parse("  screenshot \n"), "trim");
        T.eq("open_screen a.B", Spool.parse("open_screen a.B\nextra\n"), "first line");
        T.eq("", Spool.parse(null), "null");
    }
    public void testWriterSnippet() {
        String s = Commands.act("open_screen", "x.Y");
        T.yes(s.contains("d=/data/local/tmp/stickman/act; mkdir -p $d;"), s);
        T.yes(s.contains("echo 'open_screen x.Y' > $n.tmp && mv $n.tmp $n.act"), s);
        T.yes(!s.contains("act.fifo"), "no fifo");
        T.yes(Commands.act("x", "it's").contains("'x it'\\''s'"), "quoted");
        T.yes(Commands.privateDns("dns.adguard-dns.com").contains("echo 'dns_check' > $n.tmp && mv $n.tmp $n.act"), "dns");
    }
}
