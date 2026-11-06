package com.clean.stickman.core;

/**
 * Lean memory, deep sleep (Doze) while the TV is off, and freeing the storage of disabled apps.
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

    public static final String NS = "activity_manager";
    /** Kept-in-memory app caps per level (index 1 = Balanced, 2 = Max). */
    public static final int[] CAP = { 0, 8, 4 };
    public static final String RESTART = "service_restart_duration=30000,service_min_restart_time_between=60000";
    /**
     * Background CPU limit: Android's own "excessive CPU" check (checkExcessivePowerUsage) kills a background process
     * that used more than max_cpu_N % of the CPU over the last check interval (N = how long it has been in the
     * background). Stock here: every 5 min at 25/25/10/2 %; verified live-tunable on the stick (2026-11-06).
     */
    public static final String CPU_LIMIT = "power_check_interval=60000,power_check_max_cpu_1=10,power_check_max_cpu_2=10,"
        + "power_check_max_cpu_3=5,power_check_max_cpu_4=2";

    /** The activity_manager_constants value for the wanted parts, or the original back when none is wanted. */
    static String amConstants(boolean slowRestart, boolean cpuLimit) {
        if (!slowRestart && !cpuLimit) return Originals.giveBack("setting", "global", "activity_manager_constants");
        String v = slowRestart && cpuLimit ? RESTART + "," + CPU_LIMIT : slowRestart ? RESTART : CPU_LIMIT;
        return Originals.setting("global", "activity_manager_constants", v);
    }

    public static String memory(int level) { return memory(level, false); }

    /**
     * 0 = off (originals back), 1 = Balanced (cap 8 + compaction), 2 = Max (cap 4 + compaction + slow restarts).
     * cpuLimit keeps the background CPU limit (a separate switch) in the same setting.
     */
    public static String memory(int level, boolean cpuLimit) {
        if (level <= 0) {
            return Originals.giveBack("dc", NS, "max_cached_processes") + "; "
                + Originals.giveBack("dc", NS, "use_compaction") + "; "
                + amConstants(false, cpuLimit) + "; " + READ;
        }
        int l = Math.min(level, 2);
        return Originals.dconfig(NS, "max_cached_processes", Integer.toString(CAP[l])) + "; "
            + Originals.dconfig(NS, "use_compaction", "true") + "; "
            + amConstants(l == 2, cpuLimit) + "; " + READ;
    }

    /** Background CPU limit on/off; level is the current Lean level (its slow-restart part must survive). */
    public static String cpuLimit(int level, boolean on) { return amConstants(level >= 2, on) + "; " + READ; }

    /**
     * Fewer wake-ups: repeating alarms at least 15 min apart (stock 1 min; tcomm alone fired 144/h), Doze-exempt
     * alarms 30 min apart (stock 9), at most 2 background jobs at once (stock 6) and jobs batched in tens.
     * All live and reversible (verified 2026-11-06); quotas aren't used because a TV counts as charging.
     */
    public static final String QUIET_ALARM = "min_interval=900000,allow_while_idle_long_time=1800000";
    public static final String[][] QUIET_JOBS = { { "max_job_max_bg_on_normal", "2" }, { "max_job_max_bg_off_normal", "2" },
        { "min_ready_non_active_jobs_count", "10" } };

    public static String quiet(boolean on) {
        StringBuilder s = new StringBuilder();
        if (on) {
            s.append(Originals.setting("global", "alarm_manager_constants", QUIET_ALARM)).append("; ");
            for (String[] j : QUIET_JOBS) s.append(Originals.dconfig("jobscheduler", j[0], j[1])).append("; ");
        } else {
            s.append(Originals.giveBack("setting", "global", "alarm_manager_constants")).append("; ");
            for (String[] j : QUIET_JOBS) s.append(Originals.giveBack("dc", "jobscheduler", j[0])).append("; ");
        }
        return s + READ;
    }

    public static final String DOZE_FILE = Originals.DIR + "/doze.sh";
    /** Amazon background services taken off Doze's exempt list (logs, crash reports, promo notices, setup beacons). */
    public static final String[] UNEXEMPT = { "com.amazon.device.logmanager", "com.amazon.device.crashmanager",
        "com.amazon.uxnotification", "com.amazon.whisperjoin.middleware.v2.np" };
    /** Ours stay exempt so Net Filter's proxy and Stickman keep working while the stick dozes. */
    public static final String[] EXEMPT = { "com.clean.netfilter", "com.clean.stickman" };

    /** The script ensure re-runs after every boot (Doze settings reset on reboot). */
    public static String dozeScript() {
        StringBuilder s = new StringBuilder("dumpsys deviceidle enable all >/dev/null 2>&1\n");
        for (String p : EXEMPT) s.append("dumpsys deviceidle whitelist +").append(p).append(" >/dev/null 2>&1\n");
        for (String p : UNEXEMPT) s.append("dumpsys deviceidle sys-whitelist -").append(p).append(" >/dev/null 2>&1\n");
        return s.toString();
    }

    public static String dozeOn() {
        return "mkdir -p " + Originals.DIR + "; echo " + java.util.Base64.getEncoder().encodeToString(dozeScript().getBytes())
            + " | base64 -d > " + DOZE_FILE + " && sh " + DOZE_FILE + "; " + READ;
    }

    /** Doze back to Amazon's defaults; also part of Restore. */
    public static final String DOZE_OFF;
    static {
        StringBuilder s = new StringBuilder("if [ -f " + DOZE_FILE + " ]; then rm -f " + DOZE_FILE
            + "; dumpsys deviceidle disable all >/dev/null 2>&1; dumpsys deviceidle sys-whitelist reset >/dev/null 2>&1; ");
        for (String p : EXEMPT) s.append("dumpsys deviceidle whitelist -").append(p).append(" >/dev/null 2>&1; ");
        DOZE_OFF = s.append("fi").toString();
    }

    public static String dozeOff() { return DOZE_OFF + "; " + READ; }

    /** Line the ensure script runs after boot (cwd = the shared dir). */
    public static final String ENSURE_LINE = "[ -f doze.sh ] && sh doze.sh >/dev/null 2>&1; ";

    /**
     * "LEAN <cap|null> <compaction|null> <restart:on|off> DOZE <on|off> <deep:true|false> CPU <on|off> QUIET <on|off>"
     */
    public static final String READ = "echo LEAN $(device_config get " + NS + " max_cached_processes) $(device_config get " + NS
        + " use_compaction) $(settings get global activity_manager_constants | grep -q service_restart_duration=30000 && echo on || echo off)"
        + " DOZE $([ -f " + DOZE_FILE + " ] && echo on || echo off) $(dumpsys deviceidle | grep -q 'mDeepEnabled=true' && echo true || echo false)"
        + " CPU $(settings get global activity_manager_constants | grep -q power_check_interval=60000 && echo on || echo off)"
        + " QUIET $(settings get global alarm_manager_constants | grep -q min_interval=900000 && echo on || echo off)";

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

    public static String freeSpaceScript(String path) {
        return "F=" + Originals.q(path) + "; n=0; b=$(df /data | tail -n 1 | tr -s ' ' | cut -d' ' -f4); "
            + "if [ -f \"$F\" ]; then D=$(pm list packages -d 2>/dev/null); "
            + "for p in $(grep '^pkg ' \"$F\" | cut -d' ' -f2 | sort -u); do "
            + "case \"$p\" in *[!A-Za-z0-9._]*) continue;; esac; "
            + "echo \"$D\" | grep -qx \"package:$p\" || continue; "
            + "pm path $p 2>/dev/null | head -n 1 | grep -q '^package:/data/app/' || continue; "
            // pm's exit code is not reliable here (non-zero after "Success"): count by where the app lives afterwards
            + "pm uninstall-system-updates $p >/dev/null 2>&1; "
            + "pm path $p 2>/dev/null | head -n 1 | grep -q '^package:/data/app/' || n=$((n+1)); "
            + "pm disable-user --user 0 $p >/dev/null 2>&1; done; fi; "
            + "a=$(df /data | tail -n 1 | tr -s ' ' | cut -d' ' -f4); echo \"FREED $((a-b)) $n\"";
    }

    /**
     * Runs the free-space script detached (it can outlast the bridge's 20 s command timeout) and reports its result as a
     * "STATUS freed FREED <kB> <n>" logcat line under the Stickd tag. Prints FREEING.
     */
    public static String freeSpaceDetached() { return detached(freeSpaceScript(), "freespace.sh", "freed", "FREEING"); }

    /** Runs script detached; every line it prints becomes "STATUS <tag> <line>" in logcat; prints started. */
    static String detached(String script, String file, String tag, String started) {
        String f = Originals.DIR + "/" + file;
        return "mkdir -p " + Originals.DIR + "; echo " + java.util.Base64.getEncoder().encodeToString(script.getBytes())
            + " | base64 -d > " + f + " && setsid nohup sh -c 'sh " + f
            + " | while read l; do log -t Stickd -p i \"STATUS " + tag + " $l\"; done' </dev/null >/dev/null 2>&1 & echo " + started;
    }

    /** {kB freed, apps} or null. */
    public static long[] parseFreed(String out) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("FREED (-?\\d+) (\\d+)").matcher(out == null ? "" : out);
        return m.find() ? new long[] { Long.parseLong(m.group(1)), Long.parseLong(m.group(2)) } : null;
    }

    /**
     * Clear app caches (163 MB on the stick on 2026-11-06; pm trim-caches is allowed for shell) and trim the storage
     * (sm fstrim, what idle maintenance does at night). Prints "CLEARED <kB>".
     */
    public static String clearCachesScript() {
        return "b=$(df /data | tail -n 1 | tr -s ' ' | cut -d' ' -f4); pm trim-caches 999G >/dev/null 2>&1; "
            + "sm fstrim >/dev/null 2>&1; sleep 1; a=$(df /data | tail -n 1 | tr -s ' ' | cut -d' ' -f4); echo \"CLEARED $((a-b))\"";
    }

    /** Detached (fstrim can take a while); reports "STATUS cleared CLEARED <kB>"; prints CLEARING. */
    public static String clearCachesDetached() { return detached(clearCachesScript(), "clearcaches.sh", "cleared", "CLEARING"); }

    /** kB from "CLEARED <kB>", or null. */
    public static Long parseCleared(String out) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("CLEARED (-?\\d+)").matcher(out == null ? "" : out);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }
}
