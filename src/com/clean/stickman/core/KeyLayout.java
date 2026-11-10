package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Android key layout (.kl) lookup, for the key injector: on Fire OS 5-7 remapd cannot create a virtual remote, so
 * it hands Linux key codes to the injector, which must turn them into Android key names the way the system would
 * have for the real remote (same file search order as the framework's input device configuration lookup).
 */
public final class KeyLayout {
    private KeyLayout() {}

    public static final String[] DIRS = {
        "/odm/usr/keylayout", "/vendor/usr/keylayout", "/system/usr/keylayout", "/data/system/devices/keylayout" };

    /** File names (without directory) to try for a device, most specific first; Generic.kl is the fallback. */
    public static List<String> candidates(int vendor, int product, int version, String name) {
        List<String> c = new ArrayList<>();
        if (vendor != 0 && product != 0) {
            if (version != 0) c.add(String.format("Vendor_%04x_Product_%04x_Version_%04x.kl", vendor, product, version));
            c.add(String.format("Vendor_%04x_Product_%04x.kl", vendor, product));
        }
        if (name != null && !name.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (char ch : name.toCharArray())
                b.append((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-' ? ch : '_');
            c.add(b + ".kl");
        }
        c.add("Generic.kl");
        return c;
    }

    /** "key 172 HOME" lines of a .kl file as scan code -> key name; usage/axis/led lines and flags are ignored. */
    public static Map<Integer, String> parse(String text) {
        Map<Integer, String> m = new HashMap<>();
        if (text == null) return m;
        for (String line : text.split("\n")) {
            int h = line.indexOf('#');
            if (h >= 0) line = line.substring(0, h);
            String[] t = line.trim().split("\\s+");
            if (t.length < 3 || !t[0].equals("key") || t[1].equals("usage")) continue;
            try {
                int code = t[1].startsWith("0x") ? Integer.parseInt(t[1].substring(2), 16) : Integer.parseInt(t[1]);
                if (!m.containsKey(code)) m.put(code, t[2]);
            } catch (NumberFormatException ignored) { }
        }
        return m;
    }
}
