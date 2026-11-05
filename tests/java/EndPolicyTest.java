import com.clean.stickman.core.*;

import java.util.*;

public class EndPolicyTest {
    private static Set<String> s(String... a) { return new HashSet<>(Arrays.asList(a)); }
    private static final Set<String> LAUNCH = s("org.videolan.vlc", "com.netflix.ninja", "com.x.app", "com.wolf.firelauncher", "com.clean.stickman");

    public void testReasons() {
        T.eq("in front", EndPolicy.check("com.x.app", "com.x.app", s(), s(), LAUNCH), "fg");
        T.eq("playing", EndPolicy.check("com.netflix.ninja", "com.x.app", s("com.netflix.ninja"), s(), LAUNCH), "playing");
        T.eq("protected", EndPolicy.check("com.x.app", "a.b", s(), s("com.x.app"), LAUNCH), "whitelist");
        T.eq("system", EndPolicy.check("com.amazon.something", "a.b", s(), s(), LAUNCH), "non-launchable");
        T.eq("never", EndPolicy.check("com.wolf.firelauncher", "a.b", s(), s(), LAUNCH), "launcher");
        T.eq("never", EndPolicy.check("com.clean.stickman", "a.b", s(), s(), LAUNCH), "self");
        T.eq("never", EndPolicy.check("x; reboot", "a.b", s(), s(), s("x; reboot")), "not a package name");
        T.eq("never", EndPolicy.check(null, "a.b", s(), s(), LAUNCH), "null");
        T.eq(null, EndPolicy.check("org.videolan.vlc", "com.x.app", s(), s(), LAUNCH), "plain background app");
        T.eq(null, EndPolicy.check("org.videolan.vlc", null, null, null, LAUNCH), "null sets");
        T.eq("system", EndPolicy.check("org.videolan.vlc", null, null, null, null), "no launchable list fails closed");
    }
}
