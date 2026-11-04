import com.clean.stickman.core.*;
import java.nio.file.*;
import java.util.*;

public class MediaStateTest {
    public void testPlaying() {
        String s = "  Sessions Stack - have 2 sessions:\n"
            + "    a app.one/x (userId=0)\n      package=app.one\n      active=true\n"
            + "      state=PlaybackState {state=3, position=1, speed=1.0}\n"
            + "    b app.two/y (userId=0)\n      package=app.two\n      active=true\n"
            + "      state=PlaybackState {state=2, position=1, speed=0.0}\n";
        T.eq(new HashSet<>(Arrays.asList("app.one")), MediaState.playingPackages(s), "playing");
        T.eq(new HashSet<String>(), MediaState.playingPackages(s.replace("state=3", "state=2")), "paused");
        T.eq(new HashSet<String>(), MediaState.playingPackages(null), "null");
    }

    public void testFixtureNothingPlaying() throws Exception {
        String f = new String(Files.readAllBytes(Paths.get("tests/fixtures/media_session.txt")), "UTF-8");
        T.eq(new HashSet<String>(), MediaState.playingPackages(f), "fixture: wako paused, others idle");
        T.eq(new HashSet<>(Arrays.asList("app.wako")),
            MediaState.playingPackages(f.replace("PlaybackState {state=2,", "PlaybackState {state=3,")), "wako playing");
    }
}
