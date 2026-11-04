import com.clean.stickman.core.*;
import java.nio.file.*;
import java.util.*;

public class MediaStateTest {
    static String session(String pkg, int state) {
        return "    x " + pkg + "/x (userId=0)\n      package=" + pkg + "\n      active=true\n"
            + "      state=PlaybackState {state=" + state + ", position=1, speed=1.0}\n";
    }

    // Controller ruling: playing (3), paused (2) and buffering (6) sessions are protected.
    public void testProtectedStates() {
        String s = "  Sessions Stack - have 5 sessions:\n" + session("app.play", 3) + session("app.paused", 2)
            + session("app.buffer", 6) + session("app.stopped", 1) + session("app.none", 0)
            + "    y app.nostate/y (userId=0)\n      package=app.nostate\n      state=null\n";
        T.eq(new HashSet<>(Arrays.asList("app.play", "app.paused", "app.buffer")), MediaState.protectedPackages(s), "protected");
        T.eq(new HashSet<String>(), MediaState.protectedPackages(session("a.b", 1) + session("c.d", 0)), "stopped/none");
        T.eq(new HashSet<String>(), MediaState.protectedPackages(null), "null");
    }

    public void testFixture() throws Exception {
        String f = new String(Files.readAllBytes(Paths.get("tests/fixtures/media_session.txt")), "UTF-8");
        // wako is paused (state=2) -> protected; spotify/alexa are state=0, vizzini state=null -> not.
        T.eq(new HashSet<>(Arrays.asList("app.wako")), MediaState.protectedPackages(f), "fixture");
        T.eq(new HashSet<>(Arrays.asList("app.wako")),
            MediaState.protectedPackages(f.replace("PlaybackState {state=2,", "PlaybackState {state=6,")), "buffering");
        T.eq(new HashSet<String>(),
            MediaState.protectedPackages(f.replace("PlaybackState {state=2,", "PlaybackState {state=1,")), "stopped");
    }

    public void testIsPlaying() {
        T.yes(MediaState.isPlaying(session("a.b", 1) + session("c.d", 3)), "one playing");
        T.yes(MediaState.isPlaying(session("a.b", 2) + session("c.d", 6)), "buffering counts as playing");
        T.yes(!MediaState.isPlaying(session("a.b", 2) + session("e.f", 1) + session("g.h", 0)), "paused/stopped not playing");
        T.yes(!MediaState.isPlaying(null), "null");
        T.eq(new HashSet<>(Arrays.asList("c.d")), MediaState.playingPackages(session("a.b", 2) + session("c.d", 3)), "playing pkgs");
        T.eq(new HashSet<>(Arrays.asList("c.d", "e.f")),
            MediaState.playingPackages(session("a.b", 2) + session("c.d", 3) + session("e.f", 6)), "playing+buffering");
    }
}
