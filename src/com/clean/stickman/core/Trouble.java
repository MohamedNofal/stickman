package com.clean.stickman.core;

/**
 * Plain words for a failed bridge run. The bridge reports failures as "FAIL &lt;reason&gt;" (or just the reason,
 * for a per-command callback); the reasons are the raw exception messages of the VPN / TCP / adb layers
 * ("no SYN-ACK from 10.99.0.1:5555", "adb auth rejected", ...). This maps them to what happened and what the
 * user can do about it. Pure Java, host-tested.
 */
public final class Trouble {
    private Trouble() {}

    public static final int OTHER = 0;
    /** Our own VPN permission is missing (declined, or taken over by another VPN app). */
    public static final int VPN = 1;
    /** adbd isn't listening: ADB debugging is off (the main first-run mistake). */
    public static final int ADB_OFF = 2;
    /** adbd refused our key: "Allow USB debugging?" was declined. */
    public static final int NOT_ALLOWED = 3;
    /** No answer in time (typically the "Allow USB debugging?" prompt left unanswered). */
    public static final int TIMEOUT = 4;
    /** The connection dropped mid-way. */
    public static final int DROPPED = 5;
    /** Another VPN app is connected: nothing was applied (Bridge.VPN_BUSY). */
    public static final int OTHER_VPN = 6;
    /**
     * Our VPN was taken away while connecting: another VPN app reconnected straight away (always-on / auto-connect, or a
     * VPN "monitor"). Without this the failure looked like "ADB debugging is off", and Try again looped through the
     * "another VPN is on" question forever (issue #4).
     */
    public static final int VPN_TAKEN = 7;
    /** Reason the bridge reports when Android revoked our VPN during the run. */
    public static final String TAKEN_REASON = "vpn_taken";

    /** The reason without the "FAIL" prefix, trimmed; "" for null. */
    public static String reason(String r) {
        if (r == null) return "";
        String s = r.trim();
        if (s.startsWith("FAIL")) s = s.substring(4).trim();
        return s;
    }

    public static int kind(String r) {
        String s = reason(r).toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) return OTHER;
        if (s.equals("vpn_busy")) return OTHER_VPN;
        if (s.equals(TAKEN_REASON)) return VPN_TAKEN;
        if (s.contains("vpn permission missing") || s.contains("vpn not allowed")) return VPN;
        if (s.contains("no syn-ack") || s.contains("rst during connect") || s.contains("adbd refused")) return ADB_OFF;
        if (s.contains("auth rejected")) return NOT_ALLOWED;
        if (s.contains("timeout") || s.contains("timed out")) return TIMEOUT;
        if (s.contains("connection reset") || s.equals("eof") || s.contains("write not acked")) return DROPPED;
        return OTHER;
    }

    /** One short line for the sidebar (about 30 characters). */
    public static String shortText(String r) {
        switch (kind(r)) {
            case VPN: return "Needs the VPN permission";
            case ADB_OFF: return "Can't reach the stick: is ADB debugging on?";
            case NOT_ALLOWED: return "Not allowed yet: see Status";
            case TIMEOUT: return "The stick didn't answer";
            case DROPPED: return "Lost the connection to the stick";
            case OTHER_VPN: return "Another VPN is on: not applied";
            case VPN_TAKEN: return "Another VPN app took over";
            default: {
                String s = reason(r);
                return "Problem: " + (s.isEmpty() ? "unknown error" : s);
            }
        }
    }

    /** Dialog / card title. */
    public static String title(String r) {
        switch (kind(r)) {
            case VPN: return "Stickman needs the VPN permission";
            case ADB_OFF: return "Turn on ADB debugging";
            case NOT_ALLOWED: return "Allow Stickman on the stick";
            case TIMEOUT: return "The stick didn't answer";
            case DROPPED: return "The connection dropped";
            case OTHER_VPN: return "Another VPN is on";
            case VPN_TAKEN: return "Another VPN app keeps taking over";
            default: return "Something went wrong";
        }
    }

    /** What happened and what to do, in full sentences (Status page, dialogs). */
    public static String advice(String r) {
        switch (kind(r)) {
            case VPN:
                return "Stickman talks to the stick through its own tiny VPN, which only carries Stickman's commands. "
                    + "Allow the VPN when asked; your internet traffic doesn't go through it.";
            case ADB_OFF:
                return "Stickman can't reach the stick's debugging service, which usually means ADB debugging is off.\n\n"
                    + "Settings → My Fire TV → Developer options → ADB debugging: On. "
                    + "(If Developer options is missing: in About, press OK on the device name 7 times.)\n\n"
                    + "Already on? Then something blocks the connection inside the stick: switch off any firewall or "
                    + "ad-blocking app, restart the stick and try again.";
            case NOT_ALLOWED:
                return "The stick didn't allow Stickman to use its debugging service.\n\n"
                    + "Try again and, when the stick asks “Allow USB debugging?”, tick Always allow and choose OK.";
            case TIMEOUT:
                return "The stick didn't answer in time. If it is showing “Allow USB debugging?”, tick Always allow "
                    + "and choose OK (wake the TV first if the screensaver is on). Otherwise just try again.";
            case DROPPED:
                return "The connection to the stick dropped. This also happens when “Allow USB debugging?” is declined. "
                    + "Try again and allow it if the stick asks.";
            case OTHER_VPN:
                return "Another VPN app is connected, so this wasn't sent to the stick. Stickman only needs the VPN for "
                    + "a few seconds, to start after the stick turns on or to send a change; once it is running, your "
                    + "remapped buttons keep working with the other VPN on. Press Refresh and choose Continue, then "
                    + "reconnect your VPN afterwards.";
            case VPN_TAKEN:
                return "Stickman started its connection, but another VPN app took the VPN back straight away, so nothing "
                    + "was applied. ADB debugging is fine.\n\n"
                    + "This happens with VPN apps set to connect automatically or always on, and with apps that watch "
                    + "for VPNs. Turn that option off, or disconnect or uninstall the app, then try again. Once Stickman "
                    + "is running, the other VPN can be used again: Stickman only needs the VPN for a few seconds when "
                    + "it starts or applies a change.";
            default: {
                String s = reason(r);
                return "Something went wrong" + (s.isEmpty() ? "." : ": " + s + ".")
                    + " Try again; if it keeps happening, turn Stickman off and on again in Settings.";
            }
        }
    }

    /**
     * Whether a failure deserves a dialog with the fix, not just the status line: always for a missing
     * permission (ADB off, not allowed, VPN); for an unanswered or dropped connection only while Stickman has never
     * connected (first run: the user is probably looking at the "Allow USB debugging?" prompt, or dismissed it).
     */
    public static boolean askUser(String r, boolean neverConnected) {
        int k = kind(r);
        if (k == VPN || k == ADB_OFF || k == NOT_ALLOWED || k == VPN_TAKEN) return true;
        return neverConnected && (k == TIMEOUT || k == DROPPED);
    }
}
