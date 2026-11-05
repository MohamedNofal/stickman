package com.clean.stickman.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Assembles TaskSnapshots from STATUS feed lines (the text after "STATUS "). */
public final class TaskFeed {
    private TaskSnapshot latest;
    private long curSeq = -1, total, avail;
    private int cpu;
    private List<TaskSnapshot.App> cur = new ArrayList<>();
    private volatile Runnable listener;

    /**
     * True for the bodies (text after "STATUS ") that belong to the snapshot feed: "tasks <totals>", "task ..." and
     * "tasks_end ...". "tasks stopped", "end ..." and "end_all ..." are ordinary status lines.
     */
    public static boolean isFeedLine(String body) {
        if (body == null) return false;
        return body.startsWith("task ") || body.startsWith("tasks_end")
            || (body.startsWith("tasks ") && body.indexOf("seq=") > 0);
    }

    /** Called (on the onLine thread) after each newly completed snapshot; null clears it. */
    public void setListener(Runnable r) { listener = r; }

    public TaskSnapshot latest() { return latest; }
    public long latestSeq() { return latest == null ? 0 : latest.seq; }

    public void onLine(String body) {
        if (body == null) return;
        String[] t = body.trim().split("\\s+");
        if (t.length == 0) return;
        try {
            switch (t[0]) {
                case "tasks": {
                    Map<String, String> kv = kv(t, 1);
                    long seq = Long.parseLong(kv.get("seq"));
                    if (seq == 1) latest = null; // seq restarts at 1: a new sampling session
                    else if (curSeq >= 0 && seq < curSeq) return; // older than the sample in progress: discard
                    curSeq = seq;
                    total = Long.parseLong(kv.get("total"));
                    avail = Long.parseLong(kv.get("avail"));
                    cpu = Integer.parseInt(kv.get("cpu"));
                    cur = new ArrayList<>();
                    break;
                }
                case "task": {
                    Map<String, String> kv = kv(t, 2);
                    if (t.length < 2 || curSeq < 0 || Long.parseLong(kv.get("seq")) != curSeq) return;
                    cur.add(new TaskSnapshot.App(t[1], Long.parseLong(kv.get("rss")), Integer.parseInt(kv.get("cpu")),
                        kv.get("state"), Long.parseLong(kv.get("up")), Integer.parseInt(kv.get("procs"))));
                    break;
                }
                case "tasks_end": {
                    long seq = Long.parseLong(kv(t, 1).get("seq"));
                    if (seq == curSeq && seq > latestSeq()) {
                        List<TaskSnapshot.App> apps = cur;
                        Collections.sort(apps, TaskSnapshot.App.BY_RSS);
                        latest = new TaskSnapshot(seq, total, avail, cpu, apps);
                        Runnable l = listener;
                        if (l != null) l.run();
                    }
                    if (seq == curSeq) {
                        curSeq = -1;
                        cur = new ArrayList<>();
                    }
                    break;
                }
                default:
            }
        } catch (RuntimeException e) {
            // malformed line: drop the partial sample
            curSeq = -1;
            cur = new ArrayList<>();
        }
    }

    private static Map<String, String> kv(String[] t, int from) {
        Map<String, String> m = new HashMap<>();
        for (int i = from; i < t.length; i++) {
            int e = t[i].indexOf('=');
            if (e > 0) m.put(t[i].substring(0, e), t[i].substring(e + 1));
        }
        return m;
    }
}
