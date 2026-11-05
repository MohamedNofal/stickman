import com.clean.stickman.core.*;

import java.util.HashMap;
import java.util.Map;

public class ListenDecisionTest {
    static RemoteLayout.Btn[] pro() { return RemotePresets.get("pro").buttons; }

    public void testPlainListening() {
        T.eq(ListenDecision.PICK, ListenDecision.decide(96, pro(), null), "drawn key opens editor");
        T.eq(ListenDecision.OFFER, ListenDecision.decide(600, pro(), null), "off-drawing key offered when unlearned exist");
        T.eq(ListenDecision.IGNORE, ListenDecision.decide(600, RemoteLayout.forPreset("enhanced"), null), "nothing to assign: keep listening");
        T.eq(ListenDecision.CANCEL, ListenDecision.decide(158, pro(), null), "Back cancels");
        T.eq(ListenDecision.IGNORE, ListenDecision.decide(0, pro(), null), "no key");
    }

    public void testTeaching() {
        T.eq(ListenDecision.ASSIGN, ListenDecision.decide(600, pro(), "custom1"), "teach assigns");
        T.eq(ListenDecision.ALREADY, ListenDecision.decide(96, pro(), "custom1"), "drawn key is not it");
        T.eq(ListenDecision.CANCEL, ListenDecision.decide(158, pro(), "custom1"), "Back cancels teaching");
        T.eq(ListenDecision.IGNORE, ListenDecision.decide(600, pro(), "nosuch"), "unknown slot");
        Map<String, Integer> l = new HashMap<>();
        l.put("custom1", 600);
        RemoteLayout.Btn[] b = RemotePresets.resolve(RemotePresets.get("pro"), l, null);
        T.eq(ListenDecision.IGNORE, ListenDecision.decide(601, b, "custom1"), "already-learned slot not overwritten");
    }

    /** A stale slot must not hijack plain listening: with learnSlot null an off-drawing key is only offered. */
    public void testPlainNeverAssigns() {
        for (int code = 1; code <= 767; code++)
            T.yes(ListenDecision.decide(code, pro(), null) != ListenDecision.ASSIGN, "plain never assigns " + code);
    }

    public void testHomeAccentOnlyOn2026() {
        for (RemotePresets.Preset p : RemotePresets.ALL)
            T.eq(p.id.equals("remote2026"), p.homeAccent, p.id + " homeAccent");
    }
}
