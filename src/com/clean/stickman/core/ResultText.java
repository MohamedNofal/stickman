package com.clean.stickman.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plain-language summary of the bridge's last result line ("OK ..." / "FAIL ..."), never raw output. */
public final class ResultText {
    private ResultText() {}

    private static final Pattern DNS = Pattern.compile("DNS (\\S+) (\\S+) ADS (\\S+)");
    private static final Pattern RESTORED = Pattern.compile("RESTORED (\\d+)");

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
}
