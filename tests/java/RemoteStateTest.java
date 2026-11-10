import com.clean.stickman.core.RemoteState;

public class RemoteStateTest {
    public void testOk() {
        T.eq("Remote:  Amazon Fire TV Remote ✓", RemoteState.describe("REMAPD 12 STICKD 13\nREMOTE ok Amazon Fire TV Remote\n"), "ok");
    }
    public void testInject() {
        String d = RemoteState.describe("REMOTE inject Amazon Fire TV Remote");
        T.yes(d.startsWith("Remote:  Amazon Fire TV Remote ✓ (compatibility mode)"), "inject");
    }
    public void testNone() {
        T.eq(null, RemoteState.describe("REMAPD 12 STICKD 13"), "old report");
        T.eq(null, RemoteState.describe(null), "null");
    }
    public void testPassthrough() {
        String d = RemoteState.describe("REMAPD 1 STICKD 2\nREMOTE passthrough Amazon Remote: Permission denied");
        T.yes(d.startsWith("Remote:  Amazon Remote (limited)"), "name");
        T.yes(d.contains("(Permission denied)") && d.contains("normal job too"), "explains");
    }
    public void testNotFound() {
        String d = RemoteState.describe("REMOTE notfound gpio-keys|cec_input");
        T.yes(d.contains("not found yet") && d.contains("gpio-keys, cec_input"), "lists devices");
        T.yes(!RemoteState.describe("REMOTE notfound -").contains("seen"), "no list");
    }
}
