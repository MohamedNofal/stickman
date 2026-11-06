package com.clean.stickman.core;

/**
 * Plain words for stickd's "STATUS &lt;kind&gt; ..." log lines, for the Status page's Live activity card
 * (the raw lines are for the daemon's log, not for people). Pure Java, host-tested.
 */
public final class StatusText {
    private StatusText() {}

    /** App name for a package (the page asks the package manager); returning the package itself is fine. */
    public interface Label { String of(String pkg); }

    /**
     * line = "STATUS &lt;kind&gt; [rest]" (the "STATUS " prefix is optional). null means the line is housekeeping
     * (the Tasks feed, task sampling) and shouldn't be shown; an unknown kind is shown as it is.
     */
    public static String human(String line, Label label) {
        if (line == null) return null;
        String body = line.trim();
        if (body.startsWith("STATUS ")) body = body.substring(7).trim();
        if (body.isEmpty()) return null;
        int sp = body.indexOf(' ');
        String kind = sp < 0 ? body : body.substring(0, sp);
        String rest = sp < 0 ? "" : body.substring(sp + 1).trim();
        switch (kind) {
            case "started":
                return "Stickman's background helper started.";
            case "fg": {
                String prof = field(rest, "profile");
                boolean own = prof != null && !prof.equals(RemapModel.DEFAULT);
                return "In front: " + name(word(rest), label) + (own ? " (its own buttons)" : "") + ".";
            }
            case "boost":
                return "Boost: freed " + num(field(rest, "freed_mb")) + " MB, closed " + apps(field(rest, "killed")) + ".";
            case "trim": {
                String why = field(rest, "why");
                String what = apps(field(rest, "trimmed")) + " gave back " + num(field(rest, "freed_mb")) + " MB, nothing closed.";
                if ("sleep".equals(why)) return "Trim as the TV went to sleep: " + what;
                if ("interval".equals(why)) return "Timed trim: " + what;
                return "Trim: " + what;
            }
            case "autokill": {
                String k = field(rest, "killed");
                return k == null || k.isEmpty() ? "Auto-close: nothing to close." : "Auto-closed: " + names(k, label) + ".";
            }
            case "sleep_timer":
                return "off".equals(rest) ? "Sleep timer off." : "Sleep timer: " + rest + " min.";
            case "dns":
                if ("ok".equals(rest)) return "Private DNS check: the internet works ✓";
                if ("reverted".equals(rest)) return "Private DNS switched off: the internet stopped working.";
                return "Private DNS check: " + rest;
            case "end": {
                String why = field(rest, "refused");
                String n = name(word(rest), label);
                if (why == null) return "Ended " + n + ".";
                return "Couldn't end " + n + ": " + ("never".equals(why) ? "always protected" : why) + ".";
            }
            case "end_all":
                return "End all: closed " + apps(field(rest, "killed")) + ".";
            case "freed": {
                long[] f = Lean.parseFreed(rest);
                if (f == null) return "Free up space: " + rest;
                if (f[1] == 0) return "Free up space: nothing to free.";
                return "Freed " + Math.max(0, f[0] / 1024) + " MB from " + apps(Long.toString(f[1])) + ".";
            }
            case "cleared": {
                Long kb = Lean.parseCleared(rest);
                return kb == null ? "Clear caches: " + rest : "Cleared " + Math.max(0, kb / 1024) + " MB of app caches.";
            }
            case "tasks": case "task": case "tasks_end":
                return null;
            default:
                return body;
        }
    }

    /** First word of rest ("" when empty). */
    static String word(String rest) {
        int sp = rest.indexOf(' ');
        return sp < 0 ? rest : rest.substring(0, sp);
    }

    /** Value of "key=value" in rest, or null. */
    static String field(String rest, String key) {
        for (String t : rest.split("\\s+")) {
            if (t.startsWith(key + "=")) return t.substring(key.length() + 1);
        }
        return null;
    }

    static String num(String v) { return v == null || v.isEmpty() ? "?" : v; }

    static String apps(String n) {
        if ("1".equals(n)) return "1 app";
        return num(n) + " apps";
    }

    static String name(String pkg, Label label) {
        if (pkg == null || pkg.isEmpty()) return "?";
        String n = label == null ? null : label.of(pkg);
        return n == null || n.trim().isEmpty() ? pkg : n.trim();
    }

    /** "a,b,c" -> "A, B, C". */
    static String names(String csv, Label label) {
        StringBuilder sb = new StringBuilder();
        for (String p : csv.split(",")) {
            if (p.trim().isEmpty()) continue;
            sb.append(sb.length() > 0 ? ", " : "").append(name(p.trim(), label));
        }
        return sb.length() == 0 ? "nothing" : sb.toString();
    }
}
