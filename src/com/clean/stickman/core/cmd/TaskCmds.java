package com.clean.stickman.core.cmd;

/**
 * Tasks page backend (daemon/TaskSampler): log buffer size, page size, End and End all. Its other inputs are
 * {@link DaemonCmds#mediaSessions}, LruParser.read and KillPolicy.LAUNCHABLE_CMD.
 */
public final class TaskCmds {
    private TaskCmds() {}

    /** Ring buffer sizes (parsed by TaskSampler.mainBufBelow1M). */
    public static final Cmd LOG_BUF_GET = Cmd.def("tasks.log_buf_get", "Tasks", 22, "logcat");
    public static String logBufGet() { return "logcat -g 2>/dev/null"; }

    /** Grows the main ring to 1 MiB so the task feed does not evict other STATUS lines as fast. Best effort. */
    public static final Cmd LOG_BUF_GROW = Cmd.def("tasks.log_buf_grow", "Tasks", 22, "logcat logcat:-G");
    public static String logBufGrow() { return "logcat -G 1M >/dev/null 2>&1; true"; }

    /**
     * Memory page size for RSS from /proc/PID/statm. Only Fire OS 8 has getconf: everywhere else the output is not
     * digits and TaskSampler uses 4096, which is right on every Fire TV so far.
     */
    public static final Cmd PAGE_SIZE = Cmd.def("tasks.page_size", "Tasks", 30, "getconf");
    public static String pageSize() { return "getconf PAGESIZE"; }

    /** Forced End of an app without a launcher entry: is it a real package. pkg passed EndPolicy.check. */
    public static final Cmd END_PM_PATH = Cmd.def("tasks.end_pm_path", "Tasks", 22, "pm:path");
    public static String endPmPath(String pkg) { return "pm path " + pkg; }

    /** End one app. pkg passed EndPolicy.check (plain package names only). */
    public static final Cmd END = Cmd.def("tasks.end", "Tasks", 22, "am:force-stop");
    public static String end(String pkg) { return "am force-stop " + pkg; }

    /** End all, per app: prints KILLED when am accepted it. pkg passed EndPolicy.check. */
    public static final Cmd END_ALL_ONE = Cmd.def("tasks.end_all_one", "Tasks", 22, "am:force-stop");
    public static String endAllOne(String pkg) { return "am force-stop " + pkg + " && echo KILLED"; }
}
