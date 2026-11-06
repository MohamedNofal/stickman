package com.clean.stickman;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import com.clean.stickman.core.EnsureScript;

/** The ensure marker and its run-time expansion (the worker expands MARKER right before executing it). */
public final class Ensure {
    private Ensure() {}

    /** Queued instead of a concrete command so the script always carries the latest confs. */
    public static final String MARKER = "@ensure";

    public static String cmd(Context c) {
        ApplicationInfo ai = c.getApplicationInfo();
        int version = 1;
        try {
            version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception ignored) {}
        // migrate=true always: the script itself only acts when /data/local/tmp/remap or KeyShift exists.
        String s = EnsureScript.build(ai.sourceDir, ai.nativeLibraryDir + "/libremapd.so", version,
            Store.remap(c).serialize(), Store.stickdText(c), true);
        // Fire OS resets suspensions on some updates; re-freeze OTA on every ensure (cheap and idempotent).
        return com.clean.stickman.core.Edition.STORE ? s : s + "; " + com.clean.stickman.core.Ota.ensureLine(Store.otaFrozen(c));
    }
}
