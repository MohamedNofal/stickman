package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Action spool (replaces act.fifo; SELinux forbids shell mkfifo on Fire OS 8).
 * Writers put "<id> [arg]\n" in act/<epoch-ms>-<pid>-<seq>.tmp, then rename it to .act.
 */
public final class Spool {
    private Spool() {}

    public static final String DIR = Originals.DIR + "/act";
    public static final String ACT = ".act";
    public static final String TMP = ".tmp";

    /** The *.act names, oldest first: by leading numeric timestamp, then by name. */
    public static List<String> pending(String[] names) {
        List<String> out = new ArrayList<>();
        if (names == null) return out;
        for (String n : names) if (n != null && n.endsWith(ACT) && n.length() > ACT.length()) out.add(n);
        Collections.sort(out, (a, b) -> {
            int c = Long.compare(stamp(a), stamp(b));
            return c != 0 ? c : a.compareTo(b);
        });
        return out;
    }

    static long stamp(String name) {
        int i = 0;
        while (i < name.length() && i < 18 && Character.isDigit(name.charAt(i))) i++;
        return i == 0 ? Long.MAX_VALUE : Long.parseLong(name.substring(0, i));
    }

    /** First line of a spool file, trimmed ("" for null). */
    public static String parse(String content) {
        if (content == null) return "";
        int nl = content.indexOf('\n');
        return (nl < 0 ? content : content.substring(0, nl)).trim();
    }
}
