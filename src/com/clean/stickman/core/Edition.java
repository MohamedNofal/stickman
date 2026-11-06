package com.clean.stickman.core;

/**
 * Which build this is. The GitHub build is the full app; `./build.sh appstore` flips STORE to true in a copy of the
 * sources and builds the Amazon Appstore edition: no Debloat, no Privacy (ad DNS, ad tracking, telemetry, location),
 * no Fire OS update freeze and no Amazon-services picker. Remote, Tasks, Speed, Sleep, Shortcuts, Status and Settings
 * stay. The Appstore edition also has its own package name (STORE_PKG) so both can be told apart.
 */
public final class Edition {
    private Edition() {}

    public static final boolean STORE = false;
    public static final String STORE_PKG = "com.clean.stickman.tv";

    /** Sidebar sections the store edition leaves out. */
    public static boolean hidden(String section) {
        return STORE && ("Debloat".equals(section) || "Privacy".equals(section));
    }
}
