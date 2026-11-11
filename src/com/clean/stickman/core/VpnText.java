package com.clean.stickman.core;

/**
 * What Stickman tells the user about another VPN app. The point users miss: remapping never needs the VPN. The
 * helpers (remapd, stickd) run on the stick by themselves; Stickman borrows the VPN for a few seconds only to start
 * them after the stick turns on, or to send a change. Pure Java, host-tested.
 *
 * {@code running}: the helpers were started since the stick last turned on (so the buttons work right now).
 */
public final class VpnText {
    private VpnText() {}

    /** Sidebar status line: one short line, so the menu above it keeps its room. */
    public static String statusLine(boolean running) {
        return running ? "VPN on · buttons work ✓" : "VPN on · not started yet";
    }

    public static String confirmTitle(boolean running) {
        return running ? "Disconnect your VPN to apply?" : "Disconnect your VPN to start Stickman?";
    }

    /** Asked before a user-initiated change while another VPN app is connected. */
    public static String confirmMessage(boolean running) {
        String why = "Android allows only one VPN at a time, so your VPN app will be disconnected while Stickman "
            + "sends this to the stick. That takes a few seconds. Reconnect it afterwards (many VPN apps do that by "
            + "themselves).";
        if (running)
            return "Your remapped buttons keep working with your VPN on: Stickman only needs the VPN to send changes.\n\n"
                + why;
        return "Stickman hasn't started since the stick was turned on, so your remapped buttons aren't working yet. "
            + "Starting it needs the VPN once.\n\n" + why + "\n\nAfter that, your buttons keep working with your VPN on.";
    }

    /** Pages that read live values from the stick (Tasks, Status, Speed) can't while another VPN is on. */
    public static String cantRead(boolean running) {
        return "Another VPN is on, so Stickman can't read the stick right now."
            + (running ? " Your buttons still work." : "");
    }
}
