package com.clean.stickman.core.cmd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One shell command (or one small script) Stickman runs on the stick, declared where it is built.
 *
 * Every string that names a system tool (pm, am, cmd, dumpsys, settings, device_config, ...) lives in a *Cmds class of
 * this package, next to a {@code Cmd.def(...)} entry that says which feature it belongs to, the lowest Android API
 * level it works on, and which tools and subcommands it needs. That makes every ADB command findable in one place
 * and lets a host test check each one against what every Fire OS version really has ({@link Tools}), instead of
 * finding out on a user's stick (three shipped bugs: setup on Fire OS 5/6, End all on Fire OS 5-7, media keys on
 * Fire OS 6/7).
 *
 * Tool names: plain binaries ("base64", "setsid"), or "tool:subcommand" for the multi-purpose ones
 * ("pm:disable-user", "am:force-stop", "cmd:package", "dumpsys:activity-lru", "settings", "device_config").
 * Fire OS 5 = API 22, Fire OS 6 = 25, Fire OS 7 = 28, Fire OS 8 = 30.
 */
public final class Cmd {
    private static final Map<String, Cmd> ALL = new LinkedHashMap<>();

    public final String id, feature;
    /** Lowest API level this works on (with {@link #fallback}, the lowest API of the fallback). */
    public final int minApi;
    /** Space-separated tools the primary form needs. */
    public final String tools;
    /** Tools of the form used below {@link #primaryFrom}; "" when there is none. */
    public final String fallbackTools;
    /** API level from which the primary form is used; below it the fallback (when declared). */
    public final int primaryFrom;

    private Cmd(String id, String feature, int minApi, String tools, int primaryFrom, String fallbackTools) {
        this.id = id; this.feature = feature; this.minApi = minApi; this.tools = tools;
        this.primaryFrom = primaryFrom; this.fallbackTools = fallbackTools;
    }

    /** A command that works unchanged from minApi up. */
    public static Cmd def(String id, String feature, int minApi, String tools) {
        return put(new Cmd(id, feature, minApi, tools, minApi, ""));
    }

    /** A command whose primary form needs primaryFrom, with a fallback form that works from minApi. */
    public static Cmd def(String id, String feature, int minApi, String fallbackTools, int primaryFrom, String tools) {
        return put(new Cmd(id, feature, minApi, tools, primaryFrom, fallbackTools));
    }

    private static synchronized Cmd put(Cmd c) {
        if (!c.id.matches("[a-z0-9]+(\\.[a-z0-9_]+)+")) throw new IllegalArgumentException("bad cmd id: " + c.id);
        if (ALL.containsKey(c.id)) throw new IllegalStateException("duplicate cmd id: " + c.id);
        ALL.put(c.id, c);
        return c;
    }

    /** Whether the feature behind this command can work on the given API level. */
    public boolean availableOn(int api) { return api >= minApi; }

    /** The tools the form used on this API level needs. */
    public String toolsOn(int api) { return api >= primaryFrom || fallbackTools.isEmpty() ? tools : fallbackTools; }

    /** "Needs Fire OS 7 or later" style label for a control that cannot work here, or null when it can. */
    public String unavailableLabel(int api) {
        if (availableOn(api)) return null;
        return "Needs Fire OS " + (minApi <= 25 ? 6 : minApi <= 28 ? 7 : 8) + " or later";
    }

    public static synchronized List<Cmd> all() { return Collections.unmodifiableList(new ArrayList<>(ALL.values())); }

    public static synchronized Cmd get(String id) { return ALL.get(id); }
}
