package com.clean.stickman.core.cmd;

/** Drawn pointer (daemon/SoftPointer, run by the injector): shows or removes the app's cursor overlay. */
public final class PointerCmds {
    private PointerCmds() {}

    /** The app's receiver. build.sh rewrites this exact form for the Appstore edition. */
    public static final String OVERLAY = "com.clean.stickman/.FadeReceiver";

    /** POINTER_ON with the session token (hex, from SecureRandom), or POINTER_OFF. -f 32 = FLAG_INCLUDE_STOPPED_PACKAGES. */
    public static final Cmd OVERLAY_TOGGLE = Cmd.def("pointer.overlay", "Mouse", 22, "am:broadcast");
    public static String overlay(boolean show, String token) {
        return "am broadcast -f 32 -n " + OVERLAY + " -a com.clean.stickman.POINTER_" + (show ? "ON --es token " + token : "OFF")
            + " >/dev/null 2>&1";
    }
}
