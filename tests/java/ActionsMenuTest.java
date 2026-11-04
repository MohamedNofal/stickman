import com.clean.stickman.core.*;

import java.util.List;

public class ActionsMenuTest {
    public void testMenuIdsHaveFriendlyNames() {
        List<String> menu = Actions.menu();
        T.yes(menu.size() >= 8, "menu has actions: " + menu);
        for (String id : menu) {
            String n = Actions.actName(id);
            T.yes(n != null && !n.equals(id) && !n.isEmpty(), id + " has a friendly name");
        }
        T.yes(!Actions.actName(Actions.OPEN_SCREEN).equals(Actions.OPEN_SCREEN), "open_screen submenu has a name");
    }

    public void testMenuSkipsArgAndInternalIds() {
        List<String> menu = Actions.menu();
        T.yes(!menu.contains(Actions.SLEEP_TIMER_SET), "no sleep_timer_set");
        T.yes(!menu.contains(Actions.OPEN_SCREEN), "open_screen only via submenu");
        T.yes(!menu.contains(Actions.DNS_CHECK), "no dns_check");
        T.yes(!menu.contains(Actions.RELOAD), "no reload");
        T.yes(menu.contains(Actions.BOOST) && menu.contains(Actions.KILL_CURRENT), "keeps user actions");
    }

    public void testAllIdsNamed() {
        for (String id : Actions.ALL) T.yes(!Actions.actName(id).equals(id), id + " named");
    }
}
