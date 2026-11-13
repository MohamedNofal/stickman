package com.clean.stickman.core;

import com.clean.stickman.core.cmd.DebloatCmds;
import com.clean.stickman.core.cmd.SpeedCmds;

/**
 * Lean memory, deep sleep (Doze) while the TV is off, and freeing the storage of disabled apps: the measurements and
 * the read-back parsers. The commands are in {@link SpeedCmds} and {@link DebloatCmds}.
 *
 * Measured on the stick (AFTMA08C15, Fire OS 8 / API 30, 2026-11-05), all as the shell user:
 * - device_config activity_manager max_cached_processes takes effect live (CUR_MAX_CACHED_PROCESSES) and survives a
 *   reboot; 32 -> 4 freed 67 MB at once (MemAvailable 201 -> 268 MB). The settings-global form is ignored on R.
 * - device_config activity_manager use_compaction=true compacts cached apps live (33 compactions within minutes;
 *   MemAvailable 315 MB with the cap); survives a reboot.
 * - settings global activity_manager_constants service_restart_duration / service_min_restart_time_between apply
 *   live and survive a reboot: killed background services come back after 30 s+ instead of 1 s.
 * - Doze: deep and light idle are disabled by Amazon; "dumpsys deviceidle enable all" works (no motion sensor needed,
 *   normal maintenance windows) but resets on reboot, so ensure re-runs doze.sh. System-whitelist removals work.
 * - pm uninstall-system-updates on a disabled app freed 133 MB (ftvambient) but re-enabled it: it must be disabled
 *   again right after.
 * Not effective there (so not used): cached_apps_freezer (no cgroup v2 freezer; still off after reboot),
 * app_standby_enabled (Fire OS forces app idle off), pm disable of components inside protected apps (refused),
 * pm suspend (allowed, even for protected apps, but background starts still happen), cmd package compile.
 */
public final class Lean {
    private Lean() {}

    /** Kept-in-memory app caps per level (index 1 = Balanced, 2 = Max). */
    public static final int[] CAP = SpeedCmds.CAP;
    public static final String RESTART = SpeedCmds.RESTART;
    /**
     * Background CPU limit: Android's own "excessive CPU" check (checkExcessivePowerUsage) kills a background process
     * that used more than max_cpu_N % of the CPU over the last check interval (N = how long it has been in the
     * background). Stock here: every 5 min at 25/25/10/2 %; verified live-tunable on the stick (2026-11-06).
     */
    public static final String CPU_LIMIT = SpeedCmds.CPU_LIMIT;

    public static String memory(int level) { return memory(level, false); }

    /**
     * What "Use recommended settings" on the Speed page applies, in the user's words, one per line. The commands are
     * {@link #recommended()}; "Trim idle apps when the TV sleeps" is a stickd.conf change the page makes itself.
     */
    public static final String[] RECOMMENDED_STEPS = {
        "Lean memory: Balanced (Android keeps up to 8 idle apps)",
        "Background CPU limit on",
        "Deep sleep while the TV is off",
        "Fewer wake-ups",
        "Trim idle apps when the TV sleeps",
    };

    /** The one-press recommended setup: exactly the commands the Balanced / CPU limit / deep sleep / wake-ups chips send. */
    public static String[] recommended() { return SpeedCmds.recommended(); }

    /**
     * 0 = off (originals back), 1 = Balanced (cap 8 + compaction), 2 = Max (cap 4 + compaction + slow restarts).
     * cpuLimit keeps the background CPU limit (a separate switch) in the same setting.
     */
    public static String memory(int level, boolean cpuLimit) { return SpeedCmds.memory(level, cpuLimit); }

    /** Background CPU limit on/off; level is the current Lean level (its slow-restart part must survive). */
    public static String cpuLimit(int level, boolean on) { return SpeedCmds.cpuLimit(level, on); }

    /**
     * Fewer wake-ups: repeating alarms at least 15 min apart (stock 1 min; tcomm alone fired 144/h), Doze-exempt
     * alarms 30 min apart (stock 9), at most 2 background jobs at once (stock 6) and jobs batched in tens.
     * All live and reversible (verified 2026-11-06); quotas aren't used because a TV counts as charging.
     */
    public static final String QUIET_ALARM = SpeedCmds.QUIET_ALARM;

    public static String quiet(boolean on) { return SpeedCmds.quiet(on); }

    /** Amazon background services taken off Doze's exempt list (logs, crash reports, promo notices, setup beacons). */
    public static final String[] UNEXEMPT = SpeedCmds.UNEXEMPT;

    public static String dozeOn() { return SpeedCmds.dozeOn(); }

    /** Doze back to Amazon's defaults; also part of Restore. */
    public static final String DOZE_OFF = SpeedCmds.DOZE_OFF;

    public static String dozeOff() { return SpeedCmds.dozeOff(); }

    /** Line the ensure script runs after boot (cwd = the shared dir). */
    public static final String ENSURE_LINE = SpeedCmds.ENSURE_LINE;

    /** The LEAN read-back line parsed by {@link #parse}. */
    public static final String READ = SpeedCmds.READ;

    /**
     * {level 0..2 or -1 when it matches no level, doze on?, cpu limit on?, fewer wake-ups on?} from the last LEAN
     * line; null when there is none.
     */
    public static int[] parse(String out) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("LEAN (\\S+) (\\S+) (on|off) DOZE (on|off) (true|false)(?: CPU (on|off) QUIET (on|off))?").matcher(out == null ? "" : out);
        String[] g = null;
        while (m.find()) g = new String[] { m.group(1), m.group(2), m.group(3), m.group(4), m.group(5), m.group(6), m.group(7) };
        if (g == null) return null;
        int level;
        boolean comp = "true".equals(g[1]), slow = "on".equals(g[2]);
        if ("null".equals(g[0]) && !comp && !slow) level = 0;
        else if (Integer.toString(CAP[1]).equals(g[0]) && comp && !slow) level = 1;
        else if (Integer.toString(CAP[2]).equals(g[0]) && comp && slow) level = 2;
        else level = -1;
        return new int[] { level, "on".equals(g[3]) && "true".equals(g[4]) ? 1 : 0, "on".equals(g[5]) ? 1 : 0, "on".equals(g[6]) ? 1 : 0 };
    }

    /**
     * Free space: for every journaled (Stickman-disabled) package that is disabled and has updates in /data/app,
     * uninstall the updates (back to the small factory copy) and disable it again (uninstalling updates re-enables
     * it). Prints "FREED <kB> <n>".
     */
    public static String freeSpaceScript() { return freeSpaceScript(Originals.FILE); }

    public static String freeSpaceScript(String path) { return DebloatCmds.freeSpace(path); }

    /** Detached; reports a "STATUS freed FREED <kB> <n>" logcat line under the Stickd tag. Prints FREEING. */
    public static String freeSpaceDetached() { return DebloatCmds.freeSpaceDetached(); }

    /** {kB freed, apps} or null. */
    public static long[] parseFreed(String out) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("FREED (-?\\d+) (\\d+)").matcher(out == null ? "" : out);
        return m.find() ? new long[] { Long.parseLong(m.group(1)), Long.parseLong(m.group(2)) } : null;
    }

    /**
     * Clear app caches (163 MB on the stick on 2026-11-06; pm trim-caches is allowed for shell) and trim the storage
     * (sm fstrim, what idle maintenance does at night). Prints "CLEARED <kB>".
     */
    public static String clearCachesScript() { return DebloatCmds.clearCaches(); }

    /** Detached (fstrim can take a while); reports "STATUS cleared CLEARED <kB>"; prints CLEARING. */
    public static String clearCachesDetached() { return DebloatCmds.clearCachesDetached(); }

    /** kB from "CLEARED <kB>", or null. */
    public static Long parseCleared(String out) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("CLEARED (-?\\d+)").matcher(out == null ? "" : out);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }
}
