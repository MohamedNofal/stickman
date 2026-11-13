package com.clean.stickman.core.cmd;

/** HDMI-CEC switch on the Sleep page (parser: core.Starve.parseCec). */
public final class CecCmds {
    private CecCmds() {}

    public static final Cmd READ = Cmd.def("cec.read", "HDMI-CEC", 22, "settings");

    public static final String READ_CMD = "echo CEC $(settings get global hdmi_control_enabled)";

    /** Journaled, then {@link #READ_CMD}. */
    public static final Cmd SET = Cmd.def("cec.set", "HDMI-CEC", 22, "settings");

    /** HDMI-CEC: lets the stick and the TV turn each other on and switch inputs. */
    public static String set(boolean on) {
        return JournalCmds.setting("global", "hdmi_control_enabled", on ? "1" : "0") + "; " + READ_CMD;
    }
}
