package com.clean.stickman.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only "what is it set to now" commands for the Sleep and Speed pages, and the pure mapping from the
 * printed values to the index of the matching choice chip (-1 = no chip matches / unknown).
 */
public final class LiveValues {
    private LiveValues() {}

    /** One line: "SAVER &lt;v&gt; SLEEP &lt;v&gt; OFF &lt;v&gt;" (each value may be "null"). */
    public static final String SLEEP_READ = "echo SAVER $(settings get secure screensaver_enabled)"
        + " SLEEP $(settings get secure sleep_timeout) OFF $(settings get system screen_off_timeout)";
    /** One line: "ANIM &lt;window_animation_scale&gt;". */
    public static final String ANIM_READ = "echo ANIM $(settings get global window_animation_scale)";

    /** Value printed after KEY (last occurrence); null when missing or "null". */
    public static String value(String out, String key) {
        if (out == null) return null;
        Matcher m = Pattern.compile("(?:^|\\s)" + Pattern.quote(key) + " (\\S+)").matcher(out);
        String v = null;
        while (m.find()) v = m.group(1);
        return v == null || "null".equals(v) ? null : v;
    }

    /** Index of the option equal to v (ms); values at or above Integer.MAX_VALUE all mean "never". */
    public static int indexOfMs(long[] opts, String v) {
        Long n = parseLong(v);
        if (n == null) return -1;
        for (int i = 0; i < opts.length; i++) {
            if (opts[i] == n) return i;
            if (opts[i] >= Integer.MAX_VALUE && n >= Integer.MAX_VALUE) return i;
        }
        return -1;
    }

    /** Screensaver chips are { On, Off }: "1" -> 0, "0" -> 1, anything else unknown. */
    public static int saver(String v) {
        if ("1".equals(v)) return 0;
        if ("0".equals(v)) return 1;
        return -1;
    }

    /** Index of the animation scale; an unset value (null) is Android's default 1.0. */
    public static int indexOfScale(float[] opts, String v) {
        float f;
        if (v == null) f = 1f;
        else {
            try { f = Float.parseFloat(v.trim()); } catch (NumberFormatException e) { return -1; }
        }
        for (int i = 0; i < opts.length; i++) if (Math.abs(opts[i] - f) < 0.01f) return i;
        return -1;
    }

    /** Human text for an animation scale: "1.5×", "Off" for 0; "unknown" when unreadable (null = default 1×). */
    public static String describeScale(String v) {
        if (v == null) return "1×";
        float f;
        try { f = Float.parseFloat(v.trim()); } catch (NumberFormatException e) { return "unknown"; }
        if (f == 0f) return "Off";
        String s = Float.toString(f);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + "×";
    }

    /** Human text for a timeout in ms: "Never", "45 min", "2 h", "90 s"; "unknown" when unreadable. */
    public static String describeMs(String v) {
        Long n = parseLong(v);
        if (n == null) return "unknown";
        if (n < 0 || n >= Integer.MAX_VALUE) return "Never";
        if (n % 3600000L == 0 && n > 0) return n / 3600000L + " h";
        if (n % 60000L == 0) return n / 60000L + " min";
        return Math.round(n / 1000.0) + " s";
    }

    private static Long parseLong(String v) {
        if (v == null) return null;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return null; }
    }
}
