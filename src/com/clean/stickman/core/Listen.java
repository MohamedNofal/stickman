package com.clean.stickman.core;

/**
 * Listen mode's switch for remapd: while {@link #FILE} exists (and is younger than remapd's 30 s limit) remapd
 * swallows every button and only logs it, so pressing Home or an app button during Listen jumps to that button in
 * Stickman instead of leaving the app. {@link #ON} touches the file (a repeat press refreshes it), {@link #OFF}
 * removes it. Both are quick bridge commands; their results are matched by string.
 */
public final class Listen {
    private Listen() {}

    public static final String FILE = Originals.DIR + "/listen";
    public static final String ON = "mkdir -p " + Originals.DIR + " && touch " + FILE + " && echo LISTENING";
    public static final String OFF = "rm -f " + FILE + "; echo LISTEN_OFF";

    /** remapd's log line once it has switched to swallowing buttons. */
    public static final String REMAPD_ON = "listening: buttons swallowed";

    public static boolean onOk(String cmd, String out) { return ON.equals(cmd) && out != null && out.contains("LISTENING"); }
}
