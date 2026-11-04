package com.clean.stickman.core;

/** One remap rule: a key code, a gesture layer and a raw action string. */
public final class Rule {
    public enum Layer {
        TAP, DOUBLE, HOLD;

        public String id() { return name().toLowerCase(java.util.Locale.ROOT); }

        public static Layer fromId(String s) {
            for (Layer l : values()) if (l.id().equals(s)) return l;
            return null;
        }
    }

    public final int code;
    public final Layer layer;
    public final String action;

    public Rule(int code, Layer layer, String action) {
        this.code = code;
        this.layer = layer;
        this.action = action;
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Rule)) return false;
        Rule r = (Rule) o;
        return code == r.code && layer == r.layer && action.equals(r.action);
    }

    @Override public int hashCode() { return (code * 31 + layer.hashCode()) * 31 + action.hashCode(); }

    @Override public String toString() { return code + " " + layer.id() + " " + action; }
}
