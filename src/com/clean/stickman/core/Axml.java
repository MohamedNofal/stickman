package com.clean.stickman.core;

import java.nio.charset.StandardCharsets;

/**
 * Reads the package name and version code out of an APK's binary AndroidManifest.xml (pure). Stickman installs APKs
 * the user points it at: before anything is installed it must know which app the file really is, whatever its
 * name or link says. Only the manifest element's two attributes are read; anything unexpected gives null.
 */
public final class Axml {
    private Axml() {}

    public static final class Info {
        public final String pkg;
        public final long versionCode;
        /** android:versionName when the manifest holds it as plain text ("1.11.0"), else null (e.g. a resource). */
        public final String versionName;
        Info(String pkg, long versionCode, String versionName) { this.pkg = pkg; this.versionCode = versionCode; this.versionName = versionName; }
    }

    private static final int CHUNK_STRINGS = 0x0001, CHUNK_RESMAP = 0x0180, CHUNK_START = 0x0102;
    private static final int ATTR_VERSION_CODE = 0x0101021b, ATTR_VERSION_NAME = 0x0101021c, TYPE_STRING = 0x03;

    public static Info read(byte[] b) {
        try {
            if (b == null || b.length < 16 || u16(b, 0) != 0x0003) return null;
            String[] strings = null;
            int[] resMap = new int[0];
            for (int p = u16(b, 2); p + 8 <= b.length; ) {
                int type = u16(b, p), head = u16(b, p + 2), size = i32(b, p + 4);
                if (size < 8 || p + size > b.length) return null;
                if (type == CHUNK_STRINGS) strings = strings(b, p);
                else if (type == CHUNK_RESMAP) {
                    resMap = new int[(size - head) / 4];
                    for (int k = 0; k < resMap.length; k++) resMap[k] = i32(b, p + head + 4 * k);
                } else if (type == CHUNK_START && strings != null) {
                    int name = i32(b, p + head + 4);
                    if (name < 0 || name >= strings.length || !"manifest".equals(strings[name])) return null;   // first element must be it
                    int attrStart = u16(b, p + head + 8), attrSize = u16(b, p + head + 10), count = u16(b, p + head + 12);
                    String pkg = null, vn = null;
                    long vc = -1;
                    for (int k = 0; k < count; k++) {
                        int a = p + head + attrStart + k * attrSize;
                        int an = i32(b, a + 4), raw = i32(b, a + 8), dtype = b[a + 15] & 0xff, data = i32(b, a + 16);
                        String aname = an >= 0 && an < strings.length ? strings[an] : "";
                        int res = an >= 0 && an < resMap.length ? resMap[an] : 0;
                        if ("package".equals(aname) && res == 0) {
                            int idx = dtype == TYPE_STRING ? data : raw;
                            if (idx >= 0 && idx < strings.length) pkg = strings[idx];
                        } else if (res == ATTR_VERSION_CODE || ("versionCode".equals(aname) && res == 0)) {
                            vc = data & 0xffffffffL;
                        } else if (res == ATTR_VERSION_NAME || ("versionName".equals(aname) && res == 0)) {
                            int idx = raw >= 0 ? raw : (dtype == TYPE_STRING ? data : -1);
                            if (idx >= 0 && idx < strings.length) vn = strings[idx];
                        }
                    }
                    if (vn != null && !vn.matches("[A-Za-z0-9._+()-]{1,40}")) vn = null;   // shown and logged: plain text only
                    return pkg == null || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+") ? null : new Info(pkg, vc, vn);
                }
                p += size;
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String[] strings(byte[] b, int p) {
        int count = i32(b, p + 8), flags = i32(b, p + 16), start = p + i32(b, p + 20);
        boolean utf8 = (flags & 0x100) != 0;
        if (count < 0 || count > 100000) throw new IllegalStateException();
        String[] out = new String[count];
        for (int k = 0; k < count; k++) {
            int o = start + i32(b, p + 28 + 4 * k);
            if (utf8) {
                o += (b[o] & 0x80) != 0 ? 2 : 1;                       // length in characters
                int len = b[o] & 0xff;
                if ((len & 0x80) != 0) { len = ((len & 0x7f) << 8) | (b[o + 1] & 0xff); o += 2; } else o += 1;
                out[k] = new String(b, o, len, StandardCharsets.UTF_8);
            } else {
                int len = u16(b, o);
                if ((len & 0x8000) != 0) { len = ((len & 0x7fff) << 16) | u16(b, o + 2); o += 4; } else o += 2;
                out[k] = new String(b, o, len * 2, StandardCharsets.UTF_16LE);
            }
        }
        return out;
    }

    private static int u16(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }

    private static int i32(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24; }
}
