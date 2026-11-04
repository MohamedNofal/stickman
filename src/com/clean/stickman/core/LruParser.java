package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses {@code dumpsys activity lru}: lines {@code #N: <adj> <state> <flags> pid:proc/uid ...}. */
public final class LruParser {
    private LruParser() {}

    public static final class Proc {
        public final String adj, state, process, pkg;
        public final int pid;
        Proc(String adj, String state, int pid, String process) {
            this.adj = adj; this.state = state; this.pid = pid; this.process = process;
            int c = process.indexOf(':');
            this.pkg = c < 0 ? process : process.substring(0, c);
        }
        @Override public String toString() { return adj + " " + state + " " + pid + ":" + process; }
    }

    // adj may contain a space before its number ("cch+ 5"); index may be space-padded ("# 9:").
    private static final Pattern LINE = Pattern.compile(
        "^\\s*#\\s*\\d+:\\s+(\\S+(?:\\s+\\d+)?)\\s+(\\S+)\\s+\\S+\\s+(\\d+):([^/\\s]+)/\\S+");

    public static List<Proc> parse(String text) {
        List<Proc> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            Matcher m = LINE.matcher(line);
            if (!m.find()) continue;
            try {
                out.add(new Proc(m.group(1).replaceAll("\\s+", ""), m.group(2), Integer.parseInt(m.group(3)), m.group(4)));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
}
