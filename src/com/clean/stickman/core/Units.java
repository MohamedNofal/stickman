package com.clean.stickman.core;

import java.util.Locale;

/** Human-readable sizes for the Tasks page. */
public final class Units {
    private Units() {}

    /** kB to "312 MB" (rounded), or "1.2 GB" (one decimal) from 1024 MB up. Negative counts as 0. */
    public static String mb(long kb) {
        if (kb < 0) kb = 0;
        long m = Math.round(kb / 1024.0);
        if (m < 1024) return m + " MB";
        return String.format(Locale.ROOT, "%.1f GB", kb / (1024.0 * 1024.0));
    }
}
