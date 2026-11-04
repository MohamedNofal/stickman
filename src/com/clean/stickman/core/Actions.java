package com.clean.stickman.core;

/** Action ids (act.fifo) and human descriptions of raw action strings. */
public final class Actions {
    private Actions() {}

    public static final String BOOST = "boost";
    public static final String SLEEP_NOW = "sleep_now";
    public static final String SLEEP_TIMER = "sleep_timer";
    public static final String SLEEP_TIMER_SET = "sleep_timer_set";
    public static final String SCREEN_OFF = "screen_off";
    public static final String SCREENSAVER_TOGGLE = "screensaver_toggle";
    public static final String SCREENSHOT = "screenshot";
    public static final String PLAY_PAUSE = "play_pause";
    public static final String NEXT = "next";
    public static final String PREVIOUS = "previous";
    public static final String KILL_CURRENT = "kill_current";
    public static final String OPEN_SCREEN = "open_screen";
    public static final String DNS_CHECK = "dns_check";
    public static final String RELOAD = "reload";

    static String actName(String id) {
        switch (id) {
            case BOOST: return "Boost";
            case SLEEP_NOW: return "Sleep now";
            case SLEEP_TIMER: return "Sleep timer";
            case SLEEP_TIMER_SET: return "Set sleep timer";
            case SCREEN_OFF: return "Screen off";
            case SCREENSAVER_TOGGLE: return "Toggle screensaver";
            case SCREENSHOT: return "Screenshot";
            case PLAY_PAUSE: return "Play/pause";
            case NEXT: return "Next";
            case PREVIOUS: return "Previous";
            case KILL_CURRENT: return "Close current app";
            case OPEN_SCREEN: return "Open Amazon screen";
            case DNS_CHECK: return "DNS check";
            case RELOAD: return "Reload settings";
            default: return id;
        }
    }

    public static String describe(String action, String appLabel) {
        if (action == null) return "Original";
        String a = action.trim();
        int sp = a.indexOf(' ');
        String verb = sp < 0 ? a : a.substring(0, sp);
        String rest = sp < 0 ? "" : a.substring(sp + 1).trim();
        switch (verb) {
            case "start":
                return "Open " + (appLabel != null && appLabel.length() > 0 ? appLabel : rest);
            case "key":
                try { return "Acts as " + Keys.name(Integer.parseInt(rest)); }
                catch (NumberFormatException e) { return a; }
            case "none": return "Nothing";
            case "orig": return "Original";
            case "act": {
                int s2 = rest.indexOf(' ');
                return actName(s2 < 0 ? rest : rest.substring(0, s2));
            }
            case "sh": return "Shell command";
            default: return a;
        }
    }
}
