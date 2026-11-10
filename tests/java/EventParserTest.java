import com.clean.stickman.core.*;
import java.util.Arrays;

public class EventParserTest {
    public void testResumedPackage() {
        T.eq("org.xbmc.kodi", EventParser.resumedPackage(
            "11-04 20:52:09.443 689 757 I wm_set_resumed_activity: [0,org.xbmc.kodi/.Splash,resumeTopActivityInnerLocked]"), "kodi");
        T.eq("com.wolf.firelauncher", EventParser.resumedPackage(
            "11-04 20:52:09.443   689   757 I wm_set_resumed_activity: [0,com.wolf.firelauncher/.screens.launcher.LauncherActivity,x]"), "spaces");
        T.eq(null, EventParser.resumedPackage("11-04 20:52:09.443 689 757 I am_proc_start: [0,1234,10050,org.xbmc.kodi,activity,x]"), "unrelated");
        T.eq(null, EventParser.resumedPackage("--------- beginning of events"), "banner");
        T.eq(null, EventParser.resumedPackage(null), "null");
        T.eq("org.xbmc.kodi", EventParser.resumedPackage("I am_set_resumed_activity: [0,org.xbmc.kodi/.Main,resumeTopActivityInnerLocked]"), "Android 8-10 tag");
        T.eq("com.netflix.ninja", EventParser.resumedPackage("I am_focused_activity: [0,com.netflix.ninja/.MainActivity,appDiedLocked]"), "Android 7 tag");
        T.eq("com.netflix.ninja", EventParser.resumedPackage("I am_focused_activity: [0,com.netflix.ninja/.MainActivity]"), "Android 5 tag, no reason");
        T.eq(null, EventParser.resumedPackage("I wm_set_resumed_activity: [0,garbage]"), "no slash");
    }
    public void testSelectProfile() {
        T.eq("org.xbmc.kodi", EventParser.selectProfile("org.xbmc.kodi", Arrays.asList("default", "org.xbmc.kodi")), "listed");
        T.eq("default", EventParser.selectProfile("app.wako", Arrays.asList("default", "org.xbmc.kodi")), "unlisted");
        T.eq("default", EventParser.selectProfile(null, Arrays.asList("default")), "null pkg");
    }
    public void testDumpsysResumed() {
        T.eq("app.wako", EventParser.dumpsysResumed(
            "    mResumedActivity: ActivityRecord{18bef71 u0 app.wako/.MainActivity t260}\n"), "resumed");
        T.eq(null, EventParser.dumpsysResumed(""), "empty");
    }

    public void testRemapKeyEpochMs() {
        T.eq(1793831929889L, EventParser.remapKeyEpochMs("         1793831929.889  9787  9787 I RemapD  : KEY 103"), "key line");
        T.eq(1793831929889L, EventParser.remapKeyEpochMs("1793831929.889 1 2 I RemapD: KEY 4 new"), "compact");
        T.eq(-1L, EventParser.remapKeyEpochMs("--------- beginning of main"), "header");
        T.eq(-1L, EventParser.remapKeyEpochMs("         1793831929.889  9787  9787 I RemapD  : started"), "not KEY");
        T.eq(-1L, EventParser.remapKeyEpochMs("         1793831929.889  9787  9787 I Other   : KEY 103"), "other tag");
        T.eq(-1L, EventParser.remapKeyEpochMs(null), "null");
    }
}
