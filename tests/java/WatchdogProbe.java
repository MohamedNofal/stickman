/** Test shim: Stickd.apkGone() is package-private in com.clean.stickman.daemon. */
public class WatchdogProbe {
    static boolean gone(String dir) throws Exception {
        com.clean.stickman.daemon.Stickd d = new com.clean.stickman.daemon.Stickd(dir);
        java.lang.reflect.Method m = d.getClass().getDeclaredMethod("apkGone");
        m.setAccessible(true);
        return (Boolean) m.invoke(d);
    }
}
