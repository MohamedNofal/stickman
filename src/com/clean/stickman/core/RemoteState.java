package com.clean.stickman.core;

/**
 * remapd's view of the remote, from the "REMOTE <state>" line of the ensure report (remapd writes the state to
 * remote.state): {@code ok <name>}, {@code inject <name>} (keys replayed by the injector), {@code passthrough <name>: <why>}, {@code notfound <name|name|...>}.
 * Fire OS 8 always gives "ok Amazon Fire TV Remote"; the other states are what older Fire OS versions and
 * unknown remotes run into, and the Status page has to say so in plain words.
 */
public final class RemoteState {
    private RemoteState() {}

    /** Shell appended to the ensure script: prints the REMOTE line when remapd has written its state. */
    public static final String REPORT = "[ -f remote.state ] && echo \"REMOTE $(cat remote.state)\"";

    /** The state after "REMOTE ", or null when the report has none. */
    public static String of(String ensureOut) {
        if (ensureOut == null) return null;
        for (String l : ensureOut.split("\n")) {
            l = l.trim();
            if (l.startsWith("REMOTE ")) return l.substring(7).trim();
        }
        return null;
    }

    /** Lines for the Status page, or null when there is nothing to say. */
    public static String describe(String ensureOut) {
        String s = of(ensureOut);
        if (s == null || s.isEmpty()) return null;
        if (s.startsWith("ok ")) return "Remote:  " + s.substring(3) + " ✓";
        if (s.startsWith("inject "))
            return "Remote:  " + s.substring(7) + " ✓ (compatibility mode)\n"
                + "This Fire OS version has no virtual remote for apps, so Stickman replays the buttons itself.";
        if (s.startsWith("passthrough ")) {
            String rest = s.substring(12);
            int c = rest.lastIndexOf(": ");
            String name = c < 0 ? rest : rest.substring(0, c), why = c < 0 ? "" : rest.substring(c + 2);
            return "Remote:  " + name + " (limited)\n"
                + "This Fire OS version does not let Stickman replace the remote's buttons"
                + (why.isEmpty() ? "" : " (" + why + ")")
                + ". Remapped buttons run their Stickman action and still do their normal job too.";
        }
        if (s.startsWith("notfound")) {
            String seen = s.length() > 9 ? s.substring(9).trim() : "";
            String list = seen.isEmpty() || seen.equals("-") ? "" : "\nInput devices seen: " + seen.replace("|", ", ");
            return "Remote:  not found yet\nPress any button on the remote, then Refresh." + list;
        }
        return null;
    }
}
