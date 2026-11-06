package com.clean.stickman.core;

/** Action ids (act/ spool) and human descriptions of raw action strings. */
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
    public static final String TASKS_START = "tasks_start";
    public static final String TASKS_STOP = "tasks_stop";
    public static final String END = "end";
    public static final String END_ALL = "end_all";
    /** Trim: every app that isn't in front gives back memory it doesn't need; nothing is closed. */
    public static final String TRIM = "trim";

    /** Every action id, in menu order. */
    public static final String[] ALL = {
        BOOST, SLEEP_NOW, SLEEP_TIMER, SLEEP_TIMER_SET, SCREEN_OFF, SCREENSAVER_TOGGLE, SCREENSHOT,
        PLAY_PAUSE, NEXT, PREVIOUS, KILL_CURRENT, OPEN_SCREEN, DNS_CHECK, RELOAD,
        TASKS_START, TASKS_STOP, END, END_ALL, TRIM
    };

    /** Ids whose action line needs an argument. */
    public static boolean needsArg(String id) { return SLEEP_TIMER_SET.equals(id) || OPEN_SCREEN.equals(id) || END.equals(id); }

    /** Housekeeping ids the app runs itself; not offered as button actions. */
    public static boolean internal(String id) { return DNS_CHECK.equals(id) || RELOAD.equals(id) || TASKS_START.equals(id)
            || TASKS_STOP.equals(id) || END_ALL.equals(id); }

    /** Ids a button can be set to directly (no argument, not internal). open_screen is offered via its own submenu. */
    public static java.util.List<String> menu() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String id : ALL) if (!needsArg(id) && !internal(id)) out.add(id);
        return out;
    }

    public static String actName(String id) {
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
            case TASKS_START: return "Start task sampling";
            case TASKS_STOP: return "Stop task sampling";
            case END: return "End app";
            case END_ALL: return "End all apps";
            case TRIM: return "Trim memory";
            default: return id;
        }
    }

    /**
     * One plain line saying what a menu action does, shown next to its name in the button editor (a first-time
     * user can't tell "Boost" from "Trim memory" by name). "" for ids that aren't offered.
     */
    public static String actHint(String id) {
        switch (id) {
            case BOOST: return "closes background apps to free memory";
            case SLEEP_NOW: return "puts the Fire TV to sleep";
            case SLEEP_TIMER: return "each press adds time: 15, 30, 60, 90 min, then off";
            case SCREEN_OFF: return "black screen, sound keeps playing";
            case SCREENSAVER_TOGGLE: return "turns the screensaver on or off";
            case SCREENSHOT: return "saves a picture to Pictures/Stickman";
            case PLAY_PAUSE: return "whatever is playing";
            case NEXT: return "skips to the next track";
            case PREVIOUS: return "back to the previous track";
            case KILL_CURRENT: return "force-stops the app in front";
            case OPEN_SCREEN: return "a Fire TV settings screen";
            case TRIM: return "background apps give back unused memory; nothing closes";
            default: return "";
        }
    }

    /** "Name  ·  hint" for the editor's action list (more = the entry opens a further list, shown as "Name…"). */
    public static String menuLabel(String id, boolean more) {
        String h = actHint(id);
        return actName(id) + (more ? "…" : "") + (h.isEmpty() ? "" : "  ·  " + h);
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
