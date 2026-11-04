package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** remap.conf v2 model: parse (also imports v1), edit, serialize. */
public final class RemapModel {
    public static final String DEFAULT = "default";
    private static final Rule.Layer[] LAYERS = Rule.Layer.values();

    public int doubleMs = 250;
    public int holdMs = 600;
    // profile -> code -> action per layer ordinal
    private final LinkedHashMap<String, TreeMap<Integer, String[]>> profiles = new LinkedHashMap<>();

    public RemapModel() { profiles.put(DEFAULT, new TreeMap<Integer, String[]>()); }

    public static boolean validCode(int c) { return c >= 1 && c <= 767; }

    private static Integer code(String s) {
        try {
            int c = Integer.parseInt(s);
            return validCode(c) ? c : null;
        } catch (NumberFormatException e) { return null; }
    }

    static boolean validAction(String a) {
        String[] t = a.split("\\s+", 2);
        String rest = t.length > 1 ? t[1].trim() : "";
        switch (t[0]) {
            case "start": case "act": case "sh": return rest.length() > 0;
            case "key": return code(rest) != null;
            case "none": case "orig": return rest.length() == 0;
            default: return false;
        }
    }

    public static RemapModel parse(String text) {
        RemapModel m = new RemapModel();
        String cur = DEFAULT;
        if (text == null) return m;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.length() == 0 || line.charAt(0) == '#') continue;
            String[] t = line.split("\\s+", 3);
            if (t[0].equals("set")) {
                String[] kv = line.split("\\s+");
                if (kv.length == 3) {
                    try {
                        int v = Integer.parseInt(kv[2]);
                        if (v > 0) {
                            if (kv[1].equals("double_ms")) m.doubleMs = v;
                            else if (kv[1].equals("hold_ms")) m.holdMs = v;
                        }
                    } catch (NumberFormatException e) { /* ignore */ }
                }
                continue;
            }
            if (t[0].equals("profile")) {
                String[] kv = line.split("\\s+");
                if (kv.length == 2) { cur = kv[1]; m.addProfile(cur); }
                continue;
            }
            Integer c = code(t[0]);
            if (c == null || t.length < 2) continue;
            Rule.Layer l = Rule.Layer.fromId(t[1]);
            if (l != null) {
                if (t.length == 3 && validAction(t[2].trim())) m.set(cur, c, l, t[2].trim());
            } else {
                // v1: "<code> <action>" means a tap rule
                String act = line.substring(t[0].length()).trim();
                if (validAction(act)) m.set(cur, c, Rule.Layer.TAP, act);
            }
        }
        return m;
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder("# stickman remap v2\n");
        sb.append("set double_ms ").append(doubleMs).append('\n');
        sb.append("set hold_ms ").append(holdMs).append('\n');
        for (Map.Entry<String, TreeMap<Integer, String[]>> p : profiles.entrySet()) {
            sb.append("profile ").append(p.getKey()).append('\n');
            for (Map.Entry<Integer, String[]> e : p.getValue().entrySet()) {
                for (Rule.Layer l : LAYERS) {
                    String a = e.getValue()[l.ordinal()];
                    if (a != null) sb.append(e.getKey()).append(' ').append(l.id()).append(' ').append(a).append('\n');
                }
            }
        }
        return sb.toString();
    }

    public List<String> profiles() { return new ArrayList<>(profiles.keySet()); }

    public String get(String profile, int code, Rule.Layer layer) {
        TreeMap<Integer, String[]> p = profiles.get(profile);
        String[] a = p == null ? null : p.get(code);
        return a == null ? null : a[layer.ordinal()];
    }

    public void set(String profile, int code, Rule.Layer layer, String action) {
        if (!validCode(code)) return;
        TreeMap<Integer, String[]> p = profiles.get(profile);
        if (p == null) {
            if (action == null) return;
            addProfile(profile);
            p = profiles.get(profile);
        }
        String[] a = p.get(code);
        if (a == null) {
            if (action == null) return;
            a = new String[LAYERS.length];
            p.put(code, a);
        }
        a[layer.ordinal()] = action;
        boolean any = false;
        for (String s : a) if (s != null) any = true;
        if (!any) p.remove(code);
    }

    public void addProfile(String pkg) {
        if (pkg != null && pkg.length() > 0 && !profiles.containsKey(pkg))
            profiles.put(pkg, new TreeMap<Integer, String[]>());
    }

    public void removeProfile(String pkg) {
        if (!DEFAULT.equals(pkg)) profiles.remove(pkg);
    }

    public boolean hasRules(String profile, int code) {
        TreeMap<Integer, String[]> p = profiles.get(profile);
        return p != null && p.containsKey(code);
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof RemapModel)) return false;
        RemapModel r = (RemapModel) o;
        if (doubleMs != r.doubleMs || holdMs != r.holdMs) return false;
        if (!profiles().equals(r.profiles())) return false;
        for (String k : profiles.keySet()) {
            TreeMap<Integer, String[]> a = profiles.get(k), b = r.profiles.get(k);
            if (!a.keySet().equals(b.keySet())) return false;
            for (Integer c : a.keySet()) if (!java.util.Arrays.equals(a.get(c), b.get(c))) return false;
        }
        return true;
    }

    @Override public int hashCode() { return serialize().hashCode(); }
}
