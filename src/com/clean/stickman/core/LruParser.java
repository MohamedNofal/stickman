package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code dumpsys activity lru}: lines {@code #N: <adj> <state> <flags> pid:proc/uid ...}.
 * That command only exists from Android 10. Before it (Fire OS 5-7) the same list is the "Process LRU list" inside
 * {@code dumpsys activity processes}: {@code Proc # 2: cch   B/ /CA trm: 0 4797:com.android.contacts/u0a5 (cch-act)};
 * those lines are parsed too, with the old names mapped to the new ones ("fore" -> "fg", "CA" -> "CAC", ...), so
 * callers see one vocabulary. Use {@link #read} to get the list on any version.
 */
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

    // Android 5-9: "Proc # 2: cch   B/ /CA trm: 0 4797:com.android.contacts/u0a5 (cch-act)" (also "PERS #18: sys ...")
    private static final Pattern OLD = Pattern.compile(
        "^\\s*(?:Proc|PERS)\\s*#\\s*\\d+:\\s+(\\S+)(?:\\s+\\+\\s*\\d+)?\\s+\\S/./(\\S+)\\s+trm:\\s*\\d+\\s+(\\d+):([^/\\s]+)/\\S+");

    public static final String CMD = "dumpsys activity lru", OLD_CMD = "dumpsys activity processes";

    /** Runs a shell command and returns its output. */
    public interface Shell { String run(String cmd); }

    /** The LRU list on any Android version: the lru command, else (nothing parsed) the processes dump. */
    public static List<Proc> read(Shell sh) {
        List<Proc> p = parse(sh.run(CMD));
        return p.isEmpty() ? parse(sh.run(OLD_CMD)) : p;
    }

    /** Android 7's two-letter process states as the names Android 8+ prints. */
    static String modernState(String s) {
        switch (s) {
            case "T": return "TOP";
            case "CA": return "CAC";
            case "Ca": return "CACC";
            case "CE": return "CEM";
            case "HO": return "HOME";
            case "LA": return "LAST";
            case "P": return "PER";
            case "PU": return "PERU";
            case "S": return "SVC";
            case "R": return "RCVR";
            case "IF": return "IMPF";
            case "IB": return "IMPB";
            case "BU": return "BKUP";
            case "HW": return "HVY";
            case "SB": return "BFGS";
            case "SF": return "FGS";
            case "TS": return "TPSL";
            default: return s;
        }
    }

    public static List<Proc> parse(String text) {
        List<Proc> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            Matcher o = OLD.matcher(line);
            if (o.find()) {
                try {
                    String adj = o.group(1).equals("fore") ? "fg" : o.group(1);
                    out.add(new Proc(adj, modernState(o.group(2)), Integer.parseInt(o.group(3)), o.group(4)));
                } catch (NumberFormatException ignored) {
                }
                continue;
            }
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
