package com.clean.stickman.core;

/** Which broadcasts BootReceiver acts on, and when they re-run ensure (pure; see BootReceiver). */
public final class BootActions {
    private BootActions() {}

    public static final String BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";
    public static final String MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED";

    /** Only the system boot / package-replaced broadcasts (explicit broadcasts skip filter matching). */
    public static boolean accepted(String action) {
        return BOOT_COMPLETED.equals(action) || MY_PACKAGE_REPLACED.equals(action);
    }

    /** An update always re-runs ensure (the daemons must restart from the new APK); a boot only until ensured. */
    public static boolean shouldEnsure(String action, boolean ensuredThisBoot) {
        if (MY_PACKAGE_REPLACED.equals(action)) return true;
        return accepted(action) && !ensuredThisBoot;
    }
}
