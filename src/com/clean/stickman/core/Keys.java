package com.clean.stickman.core;

/** Remote key codes (Linux) and friendly names. */
public final class Keys {
    private Keys() {}

    public static final int[] ALL = {
        116, 217, 103, 108, 105, 106, 96,
        158, 172, 139,
        168, 164, 208,
        115, 114, 113,
        748, 249, 362, 402, 403,
        745, 744, 747, 746
    };

    public static String name(int code) {
        switch (code) {
            case 116: return "Power";
            case 217: return "Alexa";
            case 103: return "Up";
            case 108: return "Down";
            case 105: return "Left";
            case 106: return "Right";
            case 96: return "OK";
            case 158: return "Back";
            case 172: return "Home";
            case 139: return "Menu";
            case 168: return "Rewind";
            case 164: return "Play/Pause";
            case 208: return "Fast-forward";
            case 115: return "Volume up";
            case 114: return "Volume down";
            case 113: return "Mute";
            case 748: return "Recents";
            case 249: return "Settings";
            case 362: return "Live TV";
            case 402: return "Channel up";
            case 403: return "Channel down";
            case 745: return "Prime Video";
            case 744: return "Netflix";
            case 747: return "Amazon Music";
            case 746: return "Apps";
            default: return "Button " + code;
        }
    }

    /** True for a code on some preset (Keys.ALL). */
    public static boolean known(int code) {
        for (int k : ALL) if (k == code) return true;
        return false;
    }

    /** App buttons deliver press and release together, so only tap makes sense. */
    public static boolean tapOnly(int code) { return code >= 744 && code <= 747; }

    /**
     * Keys you get around menus with (the D-pad ring, OK and Back): changing what their press does can leave the
     * user stuck, so the editor warns first. Home and Menu are not in it: remapping Home is the usual case.
     */
    public static boolean navigation(int code) {
        return code == 96 || code == 103 || code == 105 || code == 106 || code == 108 || code == 158;
    }
}
