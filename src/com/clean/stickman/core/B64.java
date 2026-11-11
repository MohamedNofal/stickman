package com.clean.stickman.core;

import java.nio.charset.StandardCharsets;

/**
 * Standard base64 (RFC 4648, padded, no line breaks). java.util.Base64 only exists from Android 8 (API 26), so
 * on Fire OS 5/6 it threw NoClassDefFoundError and setup never ran; android.util.Base64 is not usable from the
 * host-tested core. Pure Java.
 */
public final class B64 {
    private B64() {}

    private static final char[] ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    public static String encode(byte[] b) {
        StringBuilder s = new StringBuilder((b.length + 2) / 3 * 4);
        int i = 0;
        for (; i + 2 < b.length; i += 3) {
            int n = (b[i] & 0xff) << 16 | (b[i + 1] & 0xff) << 8 | (b[i + 2] & 0xff);
            s.append(ABC[n >>> 18]).append(ABC[n >>> 12 & 63]).append(ABC[n >>> 6 & 63]).append(ABC[n & 63]);
        }
        int left = b.length - i;
        if (left == 1) {
            int n = (b[i] & 0xff) << 16;
            s.append(ABC[n >>> 18]).append(ABC[n >>> 12 & 63]).append("==");
        } else if (left == 2) {
            int n = (b[i] & 0xff) << 16 | (b[i + 1] & 0xff) << 8;
            s.append(ABC[n >>> 18]).append(ABC[n >>> 12 & 63]).append(ABC[n >>> 6 & 63]).append('=');
        }
        return s.toString();
    }

    public static String encode(String s) {
        return encode((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
    }
}
