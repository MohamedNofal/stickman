import com.clean.stickman.core.*;

/** Remote page / button editor helpers that live in core: navigation-key warning set, plain action hints. */
public class RemoteUxTest {
    public void testNavigationKeys() {
        for (int k : new int[] { 96, 103, 105, 106, 108, 158 }) T.yes(Keys.navigation(k), Keys.name(k) + " is a navigation key");
        for (int k : new int[] { 172, 139, 164, 744, 116, 217, 113, 0, 600 }) T.yes(!Keys.navigation(k), Keys.name(k) + " is not");
        int n = 0;
        for (int k : Keys.ALL) if (Keys.navigation(k)) n++;
        T.eq(6, n, "exactly the ring, OK and Back");
    }

    public void testEveryMenuActionHasAPlainHint() {
        for (String id : Actions.menu()) {
            String h = Actions.actHint(id);
            T.yes(!h.isEmpty(), id + " has a hint");
            T.yes(h.length() <= 60, id + " hint short enough for a TV row: " + h);
            T.yes(Character.isLowerCase(h.charAt(0)), id + " hint reads on from the name: " + h);
            for (String jargon : new String[] { "uinput", "slot", "profile", "layer", "remapd", "stickd", "spool", "act " })
                T.yes(!h.toLowerCase().contains(jargon), id + " hint has no jargon: " + h);
        }
        T.yes(!Actions.actHint(Actions.OPEN_SCREEN).isEmpty(), "open_screen submenu has a hint");
        for (String id : new String[] { Actions.DNS_CHECK, Actions.RELOAD, Actions.TASKS_START, Actions.END, Actions.END_ALL, "nonsense" })
            T.eq("", Actions.actHint(id), id + " not offered, no hint");
    }

    public void testMenuLabel() {
        T.eq("Boost  ·  closes background apps to free memory", Actions.menuLabel(Actions.BOOST, false), "plain");
        T.eq("Open Amazon screen…  ·  a Fire TV settings screen", Actions.menuLabel(Actions.OPEN_SCREEN, true), "submenu");
        T.eq("DNS check", Actions.menuLabel(Actions.DNS_CHECK, false), "no hint, no separator");
    }
}
