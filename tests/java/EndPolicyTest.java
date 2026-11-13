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

    /** One app picked by the user: spared apps and system services are "guarded" until confirmed. */
    public void testGuardedNeedsConfirmation() {
        T.eq("guarded", EndPolicy.check("com.amazon.venezia", "a.b", s(), s(), LAUNCH, false), "Appstore: ask first");
        T.eq(null, EndPolicy.check("com.amazon.venezia", "a.b", s(), s(), LAUNCH, true), "confirmed");
        T.eq("guarded", EndPolicy.check("com.amazon.vizzini", "a.b", s(), s(), LAUNCH, false), "service without launcher");
        T.eq(null, EndPolicy.check("com.some.service", "a.b", s(), s(), LAUNCH, true), "system service, confirmed");
        T.eq("never", EndPolicy.check("com.clean.stickman", "a.b", s(), s(), LAUNCH, true), "never Stickman");
        T.eq("never", EndPolicy.check("com.clean.stickman.tv", "a.b", s(), s(), LAUNCH, true), "nor the store edition");
        T.eq("never", EndPolicy.check("com.android.bluetooth", "a.b", s(), s(), LAUNCH, true), "never Bluetooth");
        T.eq("never", EndPolicy.check("system", "a.b", s(), s(), LAUNCH, true), "never the system");
        T.eq("never", EndPolicy.check("x; reboot", "a.b", s(), s(), LAUNCH, true), "never a non-package");
        // playing / protected / in front are reasons to ask, not to refuse (a paused Stremio must be endable)
        T.eq("playing", EndPolicy.check("com.stremio.one", "a.b", s("com.stremio.one"), s(), s("com.stremio.one"), false), "paused player: ask");
        T.eq(null, EndPolicy.check("com.stremio.one", "a.b", s("com.stremio.one"), s(), s("com.stremio.one"), true), "confirmed: ended");
        T.eq(null, EndPolicy.check("c.d", "a.b", s(), s("c.d"), s("c.d"), true), "starred, confirmed");
        T.eq(null, EndPolicy.check("c.d", "c.d", s(), s(), s("c.d"), true), "in front, confirmed");
        T.yes(EndPolicy.confirmText("playing", "Stremio").contains("paused"), "says why");
        // the automatic paths (End all etc.) keep refusing
        T.eq("never", EndPolicy.check("com.amazon.venezia", "a.b", s(), s(), LAUNCH), "End all spares it");
        T.eq("system", EndPolicy.check("com.some.service", "a.b", s(), s(), LAUNCH), "End all skips system services");
    }
}
