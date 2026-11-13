package com.clean.stickman.core.cmd;

/** Read-only queries behind the Status and Tasks pages (device facts, launchable apps, the process list) and Trim. */
public final class StatusCmds {
    private StatusCmds() {}

    /**
     * CPU temperature lines for the Status page. Only Fire OS 8 has the thermal service; elsewhere the line prints
     * nothing and the page shows no temperature.
     */
    public static final Cmd CPU_TEMP = Cmd.def("status.cpu_temp", "Status", 30, "dumpsys:thermalservice grep");
    public static String cpuTemp() {
        return "dumpsys thermalservice | grep -m3 -i 'mValue'; ";
    }

    /**
     * The Status page's one-shot device facts (memory, CPU temperature, storage, Wi-Fi, IP, Fire OS version), parsed
     * line by line by DeviceInfo, so a missing piece just stays empty. {@link #cpuTemp} is the optional piece; ip is
     * guarded too.
     */
    public static final Cmd DEVICE_INFO = Cmd.def("status.device_info", "Status", 22, "grep df tail dumpsys:wifi getprop");
    public static String deviceInfo() {
        return "grep -E 'MemTotal|MemAvailable' /proc/meminfo; " + cpuTemp()
            + "df -h /data | tail -1; dumpsys wifi | grep -m1 'RSSI'; "
            + "ip -4 addr show wlan0 2>/dev/null | grep -m1 'inet '; getprop ro.build.version.name";
    }

    /**
     * User-launchable activities (TV and phone launcher entries), parsed by KillPolicy.parseLaunchable. Android 5.1
     * has no cmd, so there is no launchable list there (and auto-kill / End all close nothing).
     */
    public static final Cmd LAUNCHABLE = Cmd.def("tasks.launchable", "Tasks", 25, "cmd:package");
    public static String launchable() {
        return "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER; "
            + "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER";
    }

    /**
     * The process LRU list, parsed by LruParser. {@code dumpsys activity lru} exists from Android 10; before it the same
     * list is inside {@code dumpsys activity processes} (LruParser.read tries lru first, then the processes dump).
     */
    public static final Cmd LRU = Cmd.def("tasks.lru", "Tasks", 22, "dumpsys:activity-processes", 29, "dumpsys:activity-lru");
    public static String lru() {
        return "dumpsys activity lru";
    }
    public static String lruOld() {
        return "dumpsys activity processes";
    }

    /** Trim one process (onTrimMemory COMPLETE); am refuses (non-zero) when it is gone or already trimmed: kept quiet. */
    public static final Cmd TRIM_MEMORY = Cmd.def("tasks.trim_memory", "Tasks", 25, "am:send-trim-memory");
    public static String trimMemory(String process) {
        return "am send-trim-memory " + process + " COMPLETE >/dev/null 2>&1; true";
    }
}
