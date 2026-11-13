package com.clean.stickman.core.cmd;

import com.clean.stickman.core.EnsureScript;
import com.clean.stickman.core.Originals;
import com.clean.stickman.core.Spool;

/** Remote and button actions: the action spool every page uses to ask stickd for something, media keys, pointer mode. */
public final class RemoteCmds {
    private RemoteCmds() {}

    /**
     * Queues action ID (with an optional argument) for stickd. Spool protocol (see Spool): write NAME.tmp then rename
     * it to NAME.act; NAME = epoch-ms-pid-random. Android 7's date has no %N (it prints "1794523775N"): seconds * 1000
     * then, so the order stays by time. Used for sleep timer, screen off, open screen (Shortcuts), End / End all,
     * Boost, Trim, updates and the reload after setup.
     */
    public static final Cmd ACT = Cmd.def("remote.act", "Remote", 22, "mkdir date echo mv");
    public static String act(String id, String arg) {
        String line = (arg == null || arg.length() == 0) ? id : id + " " + arg;
        return "d=" + Spool.DIR + "; mkdir -p $d; t=$(date +%s%N); case $t in *N) t=${t%N}000;; *) t=${t%??????};; esac; n=$d/$t-$$-$RANDOM; "
            + "echo " + Originals.q(line) + " > $n.tmp && mv $n.tmp $n.act";
    }

    /**
     * Media key to the active session. {@code cmd media_session dispatch} is Android 11+; older systems (Fire OS 5-7)
     * have the standalone {@code media} command with the same verbs. Not "cmd ... || media ...": on Android 7-9 the
     * unknown cmd subcommand still exits 0, so the fallback never ran and every media key did nothing there. Fire OS 8
     * has no media binary; older systems do, so the choice is made on the stick by whether media exists.
     */
    public static final Cmd MEDIA_KEY = Cmd.def("remote.media_key", "Remote", 22, "media", 30, "cmd:media_session");
    public static String media(String verb) {
        return "if command -v media >/dev/null 2>&1; then media dispatch " + verb + "; else cmd media_session dispatch " + verb + "; fi";
    }

    /** Pointer mode on / off: remapd reads and removes the command file (written atomically). */
    public static final Cmd POINTER = Cmd.def("remote.pointer", "Remote", 22, "echo mv");
    public static String pointer(boolean on) {
        String f = EnsureScript.DIR + "/mouse.cmd";
        return "echo " + (on ? "on" : "off") + " > " + f + ".tmp && mv " + f + ".tmp " + f;
    }
}
