package com.clean.stickman.core;

import java.util.ArrayDeque;

/** FIFO of shell commands; a command equal to the last queued one is dropped. Not thread-safe. */
public final class CommandQueue {
    private final ArrayDeque<String> q = new ArrayDeque<String>();

    public void add(String cmd) {
        if (cmd == null) return;
        if (!q.isEmpty() && q.peekLast().equals(cmd)) return;
        q.addLast(cmd);
    }

    public String poll() { return q.pollFirst(); }

    public boolean isEmpty() { return q.isEmpty(); }

    public void clear() { q.clear(); }
}
