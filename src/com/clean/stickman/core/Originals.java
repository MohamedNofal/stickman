package com.clean.stickman.core;

import com.clean.stickman.core.cmd.JournalCmds;

/**
 * The restore-defaults journal: "record the old value, then set" commands and the replay script. The command text
 * lives in {@link JournalCmds}; these names stay for the many callers (Commands, pages, daemons, tests).
 */
public final class Originals {
    private Originals() {}

    public static final String DIR = JournalCmds.DIR;
    public static final String FILE = JournalCmds.FILE;

    public static String q(String s) { return JournalCmds.q(s); }

    public static String setting(String ns, String key, String value) { return JournalCmds.setting(ns, key, value); }

    /** device_config put, journaling the original as "dc ns key old" (restored with put, or delete when null). */
    public static String dconfig(String ns, String key, String value) { return JournalCmds.dconfig(ns, key, value); }

    /** Gives back one journaled "setting"/"dc" value; does nothing when the key was never changed by Stickman. */
    public static String giveBack(String kind, String a, String b) { return JournalCmds.giveBack(kind, a, b); }

    static boolean validPkg(String pkg) { return JournalCmds.validPkg(pkg); }

    /** Journaled pm disable-user (only journaled when pm reports "disabled-user"). null when pkg is not a plain name. */
    public static String disablePkg(String pkg) { return JournalCmds.disablePkg(pkg); }

    public static String bucket(String pkg, String bucket) { return JournalCmds.bucket(pkg, bucket); }

    /** Records that the appop op of pkg was touched (restored with 'default'), then sets it. */
    public static String appop(String pkg, String op, String mode) { return JournalCmds.appop(pkg, op, mode); }

    /**
     * Give back a stay-on owned by stickd (also the documented after-uninstall recovery), Deep sleep off, then
     * replay the journal in reverse and delete it.
     */
    public static String restoreScript() { return JournalCmds.restoreDefaults(EnsureScript.RELEASE_STAYON_FADE); }

    /** The replay alone, with an explicit journal path (host tests). */
    public static String restoreScript(String path) { return JournalCmds.restore(path); }
}
