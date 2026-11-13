package com.clean.stickman.core;

import com.clean.stickman.core.cmd.RemoteCmds;
import com.clean.stickman.core.cmd.SettingsCmds;
import com.clean.stickman.core.cmd.SleepCmds;

/**
 * Former home of the command builders, now in core/cmd. What is left delegates for callers in files of the other
 * migration groups (Speed/Privacy/Debloat pages, Debloat, the daemons); remove each once its callers use core/cmd.
 */
public final class Commands {
    private Commands() {}

    /** Delegates to {@link SleepCmds#screensaver} (daemon/Stickd). */
    public static String screensaver(boolean on) { return SleepCmds.screensaver(on); }

    /** Delegates to {@link SettingsCmds#animation} (SpeedPage). */
    public static String animation(float scale) { return SettingsCmds.animation(scale); }

    /** Delegates to {@link SettingsCmds#DNS_REFUSED} (PrivacyPage). */
    public static final String DNS_REFUSED = SettingsCmds.DNS_REFUSED;

    /** Delegates to {@link SettingsCmds#privateDns} (PrivacyPage). */
    public static String privateDns(String host) { return SettingsCmds.privateDns(host); }

    /** Delegates to {@link SettingsCmds#privacy} (PrivacyPage). */
    public static String privacy(boolean limit) { return SettingsCmds.privacy(limit); }

    /** Delegates to {@link SettingsCmds#location} (PrivacyPage). */
    public static String location(boolean on) { return SettingsCmds.location(on); }

    /** Keep asleep (on) / wake (off): see {@link Starve}. null for invalid names and, when on, protected packages. */
    public static String keepAsleep(String pkg, boolean on) {
        return on ? Starve.apply(pkg, false) : Starve.wake(pkg);
    }

    /** null for invalid names, and when disabling a protected pkg (enabling is always allowed). DebloatPage, Debloat. */
    public static String debloat(String pkg, boolean disable) {
        if (!Originals.validPkg(pkg) || (disable && Catalog.isProtected(pkg))) return null;
        if (disable) return Originals.disablePkg(pkg);
        return SettingsCmds.enableApp(pkg);
    }

    /** Delegates to {@link RemoteCmds#act} (SpeedPage). */
    public static String act(String id, String arg) { return RemoteCmds.act(id, arg); }

    /** Delegates to {@link RemoteCmds#media} (daemon/Sleep, daemon/Stickd). */
    public static String media(String verb) { return RemoteCmds.media(verb); }
}
