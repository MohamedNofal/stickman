package com.clean.stickman.core.cmd;

/**
 * The app installer's shell steps (daemon/AppInstaller, rules in core/Apps). Downloads are plain HTTP from Java; these
 * are what only the shell user can do: install and uninstall without a prompt, and ask the system about itself.
 */
public final class AppsCmds {
    private AppsCmds() {}

    /** The stick's CPU types, best first (core.Apps.abis). */
    public static final Cmd ABILIST = Cmd.def("apps.abilist", "Apps", 22, "getprop");
    public static String abilist() { return "getprop ro.product.cpu.abilist"; }

    /** Android API level, to refuse an app that needs a newer Fire OS before downloading it. */
    public static final Cmd SDK = Cmd.def("apps.sdk", "Apps", 22, "getprop");
    public static String sdk() { return "getprop ro.build.version.sdk"; }

    /** Developer testing: another catalogue URL (core.Apps.CATALOG_PROP; shell may set debug.* properties). */
    public static final Cmd CATALOG_PROP = Cmd.def("apps.catalog_prop", "Apps", 22, "getprop");
    public static String catalogProp(String prop) { return "getprop " + prop; }

    /**
     * The same property read by the app itself (argv, run in the app's own process: debug.* properties are readable
     * by every app), so the screen shows the catalogue the daemon installs from.
     */
    public static final Cmd CATALOG_PROP_APP = Cmd.def("apps.catalog_prop_app", "Apps", 22, "getprop");
    public static String[] catalogPropArgv(String prop) { return new String[] { "getprop", prop }; }

    /**
     * Every installed package ("package:NAME" lines, core.Apps.packages): which tracked apps are gone. One call for
     * all of them, and an empty answer is recognisably a failed call, not "nothing is installed".
     */
    public static final Cmd LIST = Cmd.def("apps.list", "Apps", 22, "pm:list");
    public static String listPackages() { return "pm list packages"; }

    /**
     * Installed version code of pkg (core.Apps.installedVersionCode); no output when it is not installed, which is
     * an answer, not a failure (hence "|| true": the daemon logs every failed command). pkg: validPkg.
     */
    public static final Cmd INSTALLED = Cmd.def("apps.installed", "Apps", 22, "dumpsys:package grep");
    public static String installed(String pkg) { return "dumpsys package " + pkg + " | grep -m1 versionCode= || true"; }

    /**
     * Installs (or updates) from a file under core.Apps.DIR; prints "Success" or pm's reason. As shell there is no
     * "install unknown apps" prompt, and Android itself refuses an update signed with another key.
     */
    public static final Cmd INSTALL = Cmd.def("apps.install", "Apps", 22, "pm:install");
    public static String install(String apk) { return "pm install -r " + SetupCmds.quotePath(apk) + " 2>&1"; }

    /** Uninstalls pkg for good (core.Apps.neverRemove is checked first); prints "Success" or pm's reason. */
    public static final Cmd UNINSTALL = Cmd.def("apps.uninstall", "Apps", 22, "pm:uninstall");
    public static String uninstall(String pkg) { return "pm uninstall " + pkg + " 2>&1"; }
}
