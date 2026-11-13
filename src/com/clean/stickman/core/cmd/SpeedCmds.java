package com.clean.stickman.core.cmd;

import com.clean.stickman.core.B64;

/**
 * Speed page: Lean memory, Background CPU limit, Fewer wake-ups, Deep sleep (Doze), the LEAN read-back, Background
 * apps, Starts at boot, Keep asleep / Starve. Measurements behind the values: core.Lean, core.Starve.
 */
public final class SpeedCmds {
    private SpeedCmds() {}

    public static final String NS = "activity_manager";
    /** Kept-in-memory app caps per level (index 1 = Balanced, 2 = Max). */
    public static final int[] CAP = { 0, 8, 4 };
    public static final String RESTART = "service_restart_duration=30000,service_min_restart_time_between=60000";
    /** Background CPU limit values (see core.Lean). */
    public static final String CPU_LIMIT = "power_check_interval=60000,power_check_max_cpu_1=10,power_check_max_cpu_2=10,"
        + "power_check_max_cpu_3=5,power_check_max_cpu_4=2";

    /** The activity_manager_constants value for the wanted parts, or the original back when none is wanted. */
    static String amConstants(boolean slowRestart, boolean cpuLimit) {
        if (!slowRestart && !cpuLimit) return JournalCmds.giveBack("setting", "global", "activity_manager_constants");
        String v = slowRestart && cpuLimit ? RESTART + "," + CPU_LIMIT : slowRestart ? RESTART : CPU_LIMIT;
        return JournalCmds.setting("global", "activity_manager_constants", v);
    }

    /**
     * "LEAN <cap|null> <compaction|null> <restart:on|off> DOZE <on|off> <deep:true|false> CPU <on|off> QUIET <on|off>".
     * Below 30 the two device_config substitutions are empty, the line loses two tokens and core.Lean.parse finds no
     * LEAN line at all, so the whole read-back (also its DOZE / CPU / QUIET parts) needs 30.
     */
    public static final Cmd LEAN_READ = Cmd.def("speed.lean_read", "Speed", 30, "device_config settings dumpsys:deviceidle");

    public static final String READ = "echo LEAN $(device_config get " + NS + " max_cached_processes) $(device_config get " + NS
        + " use_compaction) $(settings get global activity_manager_constants | grep -q service_restart_duration=30000 && echo on || echo off)"
        + " DOZE $([ -f " + JournalCmds.DIR + "/doze.sh ] && echo on || echo off) $(dumpsys deviceidle | grep -q 'mDeepEnabled=true' && echo true || echo false)"
        + " CPU $(settings get global activity_manager_constants | grep -q power_check_interval=60000 && echo on || echo off)"
        + " QUIET $(settings get global alarm_manager_constants | grep -q min_interval=900000 && echo on || echo off)";

    /** cmd, then the LEAN read-back (e.g. the animation read of the Speed page's live refresh). */
    public static String withLeanRead(String cmd) { return cmd + "; " + READ; }

    /**
     * Lean memory: the max_cached_processes cap and compaction are device_config (30); without them nothing of the
     * feature applies (only Max's slow-restart setting would). Level 0 only gives back journaled values. Ends with
     * {@link #LEAN_READ}.
     */
    public static final Cmd LEAN_MEMORY = Cmd.def("speed.lean_memory", "Speed", 30, "device_config settings");

    /**
     * 0 = off (originals back), 1 = Balanced (cap 8 + compaction), 2 = Max (cap 4 + compaction + slow restarts).
     * cpuLimit keeps the background CPU limit (a separate switch) in the same setting.
     */
    public static String memory(int level, boolean cpuLimit) {
        if (level <= 0) {
            return JournalCmds.giveBack("dc", NS, "max_cached_processes") + "; "
                + JournalCmds.giveBack("dc", NS, "use_compaction") + "; "
                + amConstants(false, cpuLimit) + "; " + READ;
        }
        int l = Math.min(level, 2);
        return JournalCmds.dconfig(NS, "max_cached_processes", Integer.toString(CAP[l])) + "; "
            + JournalCmds.dconfig(NS, "use_compaction", "true") + "; "
            + amConstants(l == 2, cpuLimit) + "; " + READ;
    }

    /**
     * Background CPU limit: settings global activity_manager_constants, which ActivityManager reads from Android 8
     * (API 26; my reading of AOSP, not probed): on 25 it is stored and ignored. The trailing {@link #LEAN_READ}
     * needs 30; below that the command still applies but the page cannot show the state.
     */
    public static final Cmd CPU_LIMIT_CMD = Cmd.def("speed.cpu_limit", "Speed", 28, "settings");

    /** Background CPU limit on/off; level is the current Lean level (its slow-restart part must survive). */
    public static String cpuLimit(int level, boolean on) { return amConstants(level >= 2, on) + "; " + READ; }

    /** Fewer wake-ups, values: repeating alarms at least 15 min apart, Doze-exempt alarms 30 min apart. */
    public static final String QUIET_ALARM = "min_interval=900000,allow_while_idle_long_time=1800000";
    /** At most 2 background jobs at once and jobs batched in tens. */
    public static final String[][] QUIET_JOBS = { { "max_job_max_bg_on_normal", "2" }, { "max_job_max_bg_off_normal", "2" },
        { "min_ready_non_active_jobs_count", "10" } };

    /**
     * Fewer wake-ups is two parts in one command ({@link #quiet}): the alarm part is a setting
     * (alarm_manager_constants, read by AlarmManager since Android 7 by my reading of AOSP; not probed on 25), the
     * job part is device_config (30) and silently does nothing below 30. Declared with the alarm part, the one the
     * feature cannot work without; {@link #FEWER_WAKEUPS_JOBS} declares the other. Ends with {@link #LEAN_READ} (30).
     */
    public static final Cmd FEWER_WAKEUPS = Cmd.def("speed.fewer_wakeups", "Speed", 25, "settings");
    /** The job-limit half of {@link #quiet}. */
    public static final Cmd FEWER_WAKEUPS_JOBS = Cmd.def("speed.fewer_wakeups_jobs", "Speed", 30, "device_config");

    public static String quiet(boolean on) {
        StringBuilder s = new StringBuilder();
        if (on) {
            s.append(JournalCmds.setting("global", "alarm_manager_constants", QUIET_ALARM)).append("; ");
            for (String[] j : QUIET_JOBS) s.append(JournalCmds.dconfig("jobscheduler", j[0], j[1])).append("; ");
        } else {
            s.append(JournalCmds.giveBack("setting", "global", "alarm_manager_constants")).append("; ");
            for (String[] j : QUIET_JOBS) s.append(JournalCmds.giveBack("dc", "jobscheduler", j[0])).append("; ");
        }
        return s + READ;
    }

    public static final String DOZE_FILE = JournalCmds.DIR + "/doze.sh";
    /** Amazon background services taken off Doze's exempt list (logs, crash reports, promo notices, setup beacons). */
    public static final String[] UNEXEMPT = { "com.amazon.device.logmanager", "com.amazon.device.crashmanager",
        "com.amazon.uxnotification", "com.amazon.whisperjoin.middleware.v2.np" };
    /** Ours stay exempt so Net Filter's proxy and Stickman keep working while the stick dozes. */
    public static final String[] EXEMPT = { "com.clean.netfilter", "com.clean.stickman" };

    /**
     * Deep sleep. Needs "dumpsys deviceidle enable all" (25+; dumpsys deviceidle is missing on 22). The
     * sys-whitelist lines (taking Amazon's services off the exempt list) need 28 and fail silently on 25, where
     * Doze is still enabled. Declared with the part the feature cannot work without.
     */
    public static final Cmd DOZE_SCRIPT = Cmd.def("speed.doze_script", "Speed", 25, "dumpsys:deviceidle");

    /** The script ensure re-runs after every boot (Doze settings reset on reboot). */
    public static String dozeScript() {
        StringBuilder s = new StringBuilder("dumpsys deviceidle enable all >/dev/null 2>&1\n");
        for (String p : EXEMPT) s.append("dumpsys deviceidle whitelist +").append(p).append(" >/dev/null 2>&1\n");
        for (String p : UNEXEMPT) s.append("dumpsys deviceidle sys-whitelist -").append(p).append(" >/dev/null 2>&1\n");
        return s.toString();
    }

    /** Writes doze.sh and runs it, then {@link #LEAN_READ} (30; the page can't show the state below that). */
    public static final Cmd DOZE_ON = Cmd.def("speed.doze_on", "Speed", 25, "base64 dumpsys:deviceidle");

    public static String dozeOn() {
        return "mkdir -p " + JournalCmds.DIR + "; echo " + B64.encode(dozeScript())
            + " | base64 -d > " + DOZE_FILE + " && sh " + DOZE_FILE + "; " + READ;
    }

    /** Doze back to Amazon's defaults; also part of Restore. "sys-whitelist reset" is 28+ (fails silently on 25). */
    public static final Cmd DOZE_OFF_CMD = Cmd.def("speed.doze_off", "Speed", 25, "dumpsys:deviceidle");

    public static final String DOZE_OFF;
    static {
        StringBuilder s = new StringBuilder("if [ -f " + DOZE_FILE + " ]; then rm -f " + DOZE_FILE
            + "; dumpsys deviceidle disable all >/dev/null 2>&1; dumpsys deviceidle sys-whitelist reset >/dev/null 2>&1; ");
        for (String p : EXEMPT) s.append("dumpsys deviceidle whitelist -").append(p).append(" >/dev/null 2>&1; ");
        DOZE_OFF = s.append("fi").toString();
    }

    public static String dozeOff() { return DOZE_OFF + "; " + READ; }

    /** Line the ensure script runs after boot (cwd = the shared dir); doze.sh only exists once Deep sleep is on. */
    public static final Cmd DOZE_ENSURE = Cmd.def("speed.doze_ensure", "Speed", 25, "dumpsys:deviceidle");

    public static final String ENSURE_LINE = "[ -f doze.sh ] && sh doze.sh >/dev/null 2>&1; ";

    /** "Use recommended settings": exactly the commands the Balanced + CPU limit / deep sleep / wake-ups chips send. */
    public static String[] recommended() { return new String[] { memory(1, true), dozeOn(), quiet(true) }; }

    /** Background apps: who ran in the last hours. Format on 22/25 not probed (parser: core.BgTime). */
    public static final Cmd BG_TIME = Cmd.def("speed.bg_time", "Speed", 22, "dumpsys:procstats");

    public static final int BG_HOURS = 3;
    public static final String BG_TIME_CMD = "dumpsys procstats --hours " + BG_HOURS;

    /** Apps with a BOOT_COMPLETED receiver (parser: core.BootStart). */
    public static final Cmd BOOT_RECEIVERS = Cmd.def("speed.boot_receivers", "Speed", 25, "cmd:package");

    public static final String BOOT_RECEIVERS_CMD = "cmd package query-receivers --components -a android.intent.action.BOOT_COMPLETED";

    /** Journal lines of Keep asleep / Starve: "pkg OP" and legacy "pkg bucket" (parser: core.Starve.parseList). */
    public static final Cmd ASLEEP_LIST = Cmd.def("speed.asleep_list", "Speed", 22, "grep cut");

    public static final String ASLEEP_LIST_CMD = "grep -E '^(appop|bucket) ' " + JournalCmds.FILE + " 2>/dev/null | cut -d' ' -f2,3";

    /** Starts at boot picker: the boot receivers, sep, then the asleep list (to mark apps already asleep). */
    public static final Cmd BOOT_APPS = Cmd.def("speed.boot_apps", "Speed", 25, "cmd:package grep");

    public static String bootApps(String sep) {
        return BOOT_RECEIVERS_CMD + "; echo " + sep + "; " + ASLEEP_LIST_CMD;
    }

    /** Enabled com.amazon packages (Amazon services picker). */
    public static final Cmd AMAZON_SERVICES = Cmd.def("speed.amazon_services", "Speed", 22, "pm:list");

    public static final String AMAZON_SERVICES_CMD = "pm list packages -e com.amazon";

    /** Keep asleep: can't start in the background (alarms, broadcasts, jobs) until opened. */
    public static final String[] ASLEEP_OPS = { "RUN_ANY_IN_BACKGROUND" };
    /** Starve: also no legacy background work, no wake locks, no foreground service to dodge kills. */
    public static final String[] STARVE_OPS = { "RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND", "WAKE_LOCK", "START_FOREGROUND" };

    /** RUN_ANY_IN_BACKGROUND is an appop from Android 9 (28). The script ends in "true": refusals are not reported. */
    public static final Cmd KEEP_ASLEEP = Cmd.def("speed.keep_asleep", "Speed", 28, "cmd:appops appops:RUN_ANY_IN_BACKGROUND");
    /** Starve: the same plus three more ops and "am kill". */
    public static final Cmd STARVE = Cmd.def("speed.starve", "Speed", 28, "cmd:appops appops:RUN_ANY_IN_BACKGROUND am:kill");

    /** Keep asleep (starve false) or Starve one app; pkg must be valid and unprotected (checked by core.Starve). */
    public static String keepAsleep(String pkg, boolean starve) {
        StringBuilder s = new StringBuilder();
        for (String op : starve ? STARVE_OPS : ASLEEP_OPS) s.append(JournalCmds.appop(pkg, op, "ignore")).append("; ");
        // am kill only ends a process that is in the background, so a starved app is closed now, never the one in use
        if (starve) s.append("am kill ").append(pkg).append(" >/dev/null 2>&1; ");
        return s.append("true").toString();
    }

    /**
     * Wake: gives back the journaled appops (25+, cmd appops). The legacy bucket branch needs am:set-standby-bucket
     * (28) but such lines were only ever journaled on 28+; the final RUN_ANY_IN_BACKGROUND reset fails harmlessly
     * below 28. Also undoes Quiet telemetry.
     */
    public static final Cmd WAKE = Cmd.def("speed.wake", "Speed", 25, "cmd:appops grep");

    /** Gives back every journaled appop (and legacy bucket) of pkg and drops its journal lines. null for a bad name. */
    public static String wake(String pkg) {
        if (!JournalCmds.validPkg(pkg)) return null;
        String g = pkg.replace(".", "\\.");
        return "F=" + JournalCmds.FILE + "; if [ -f $F ]; then "
            + "for op in $(grep '^appop " + g + " ' $F | cut -d' ' -f3); do "
            + "case \"$op\" in *[!A-Z_]*) continue;; esac; cmd appops set " + pkg + " $op default; done; "
            + "b=$(grep '^bucket " + g + " ' $F | head -n 1 | cut -d' ' -f3); "
            + "case \"$b\" in ''|null|*[!a-z0-9_]*) ;; *) am set-standby-bucket " + pkg + " $b;; esac; "
            + "grep -v -e '^bucket " + g + " ' -e '^appop " + g + " ' $F > $F.tmp; mv $F.tmp $F; fi; "
            + "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND default";
    }
}
