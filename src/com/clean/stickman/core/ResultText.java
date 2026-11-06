package com.clean.stickman.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-language text for the UI, never raw output: the bridge's last result line ("OK ..." / "FAIL ..."), the
 * Boost / Trim / End status lines stickd logs, and the Tasks page's state words.
 */
public final class ResultText {
    private ResultText() {}

    private static final Pattern DNS = Pattern.compile("DNS (\\S+) (\\S+) ADS (\\S+)");
    private static final Pattern RESTORED = Pattern.compile("RESTORED (\\d+)");
    private static final Pattern BOOST = Pattern.compile("freed_mb=(-?\\d+)\\s+killed=(\\d+)");

    public static String human(String r) {
        if (r == null || r.isEmpty()) return "Nothing has run yet.";
        if (r.startsWith("FAIL")) {
            String why = r.substring(4).trim();
            if (why.equals("vpn_busy")) return "Another VPN was on, so nothing was applied.";
            return "Problem: " + (why.isEmpty() ? "unknown error" : why);
        }
        String b = r.startsWith("OK") ? r.substring(2).trim() : r.trim();
        Matcher m = DNS.matcher(b);
        String dns = null;
        while (m.find()) {
            boolean on = "hostname".equals(m.group(1)) && !"null".equals(m.group(2));
            dns = "Private DNS " + (on ? m.group(2) : "off") + ", limit ads & tracking " + ("1".equals(m.group(3)) ? "on" : "off");
        }
        if (dns != null) return dns;
        if ((m = RESTORED.matcher(b)).find()) return "Turned " + m.group(1) + " app(s) back on.";
        if (b.contains("PROXY cleared")) return "Stuck proxy cleared.";
        if (b.contains("PROXY kept")) return "Proxy in use, left alone.";
        if (b.contains("PROXY none")) return "No proxy was set.";
        if (b.contains(Debloat.SEP)) return "Read the app list.";
        if (b.contains("MemTotal")) return "Read the stick's status.";
        if (b.contains("REMAPD") || b.contains("STICKD")) {
            boolean remap = b.matches("(?s).*REMAPD \\d+.*"), stick = b.matches("(?s).*STICKD \\d+.*");
            if (remap && stick) return "Checked: button remapping and background automation are running.";
            if (!remap && !stick) return "Checked: button remapping and background automation aren't running.";
            return remap ? "Checked: background automation isn't running." : "Checked: button remapping isn't running.";
        }
        return "Done.";
    }

    /** "N app" / "N apps". */
    public static String apps(long n) { return n == 1 ? "1 app" : n + " apps"; }

    /**
     * Honest outcome of a Boost from its "STATUS boost freed_mb=N killed=M" line: says when there was nothing to do,
     * and never claims memory that wasn't measured. null when the line isn't a boost status.
     */
    public static String boost(String st) {
        Matcher m = BOOST.matcher(st == null ? "" : st);
        if (!m.find()) return null;
        long mb = Long.parseLong(m.group(1));
        int killed = Integer.parseInt(m.group(2));
        if (killed == 0 && mb <= 0) return "Nothing to close: no background apps were running.";
        if (killed == 0) return "Boost done: freed " + mb + " MB. No background apps needed closing.";
        if (mb <= 0) return "Boost done: closed " + apps(killed) + ".";
        return "Boost done: closed " + apps(killed) + " and freed " + mb + " MB.";
    }

    /** Honest outcome of a Trim from its "STATUS trim freed_mb=N trimmed=M ..." line; null when it isn't one. */
    public static String trim(String st) {
        long[] t = Trim.parseStatus(st);
        if (t == null) return null;
        if (t[1] == 0) return "Nothing to trim: no apps were idle in the background.";
        if (t[0] <= 0) return "Trim done: " + apps(t[1]) + " had nothing to give back right now. Nothing was closed.";
        return "Trim done: " + apps(t[1]) + " gave back " + t[0] + " MB. Nothing was closed.";
    }

    /** Why an app can't be ended (EndPolicy reasons), as a sentence fragment after "Can't end:". */
    public static String reason(String why) {
        if (why == null) return "?";
        switch (why) {
            case "never": return "it's always protected";
            case "in front": return "it's the app in front";
            case "playing": return "it's playing";
            case "protected": return "it's protected ★ (unprotect it first)";
            case "system": return "it's a system app";
            default: return why;
        }
    }

    /** A task's state word (fg / play / bg / cached / sys) in plain words. */
    public static String state(String st) {
        if (st == null || st.isEmpty()) return "?";
        switch (st) {
            case "fg": return "in front";
            case "play": return "playing";
            case "bg": return "in the background";
            case "cached": return "idle in memory";
            case "sys": return "system";
            default: return st;
        }
    }

    /** 40 -> "40 s", 2520 -> "42 min", 11100 -> "3 h 5 min". */
    public static String duration(long sec) {
        if (sec < 60) return Math.max(0, sec) + " s";
        long m = sec / 60;
        if (m < 60) return m + " min";
        long h = m / 60;
        m %= 60;
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }
}
