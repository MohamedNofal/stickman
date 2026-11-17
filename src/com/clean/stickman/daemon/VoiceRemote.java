package com.clean.stickman.daemon;

import com.clean.stickman.core.cmd.DaemonCmds;

import java.util.concurrent.FutureTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Associates the evdev node held by remapd with the InputDevice that Fire OS uses for voice search. */
final class VoiceRemote {
    private static final Pattern DEVICE = Pattern.compile("    (-?\\d+): .*");
    private String path;
    private Node last;
    private FutureTask<Node> pending;

    static final class Node {
        final int id;
        final String descriptor;

        Node(int id, String descriptor) {
            this.id = id;
            this.descriptor = descriptor;
        }
    }

    static boolean isVoiceKey(String key) {
        return "ALEXA".equals(key) || "VOICE_CMD".equals(key) || "SEARCH".equals(key)
                || "VOICE_ASSIST".equals(key) || "ASSIST".equals(key);
    }

    static Node nodeForPath(String dump, String path) {
        boolean inHub = false, selected = false;
        int id = -1;
        for (String line : dump.split("\n")) {
            if (!inHub) {
                inHub = "Event Hub State:".equals(line.trim());
                continue;
            }
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) break;
            Matcher header = DEVICE.matcher(line);
            if (header.matches()) {
                id = Integer.parseInt(header.group(1));
                if (line.endsWith("(aka device 0 - built-in keyboard)")) id = 0;
                selected = false;
            } else {
                String field = line.trim();
                if (field.startsWith("Path: ")) selected = field.substring(6).equals(path);
                if (selected && id >= 0 && field.startsWith("Descriptor: ")) {
                    return new Node(id, field.substring(12));
                }
            }
        }
        return null;
    }

    // Re-pairing can reuse the same path, model and name. Take a fresh snapshot on every adoption.
    // Do this off the input pipe so ordinary navigation never waits for dumpsys.
    void bind(String newPath) {
        path = newPath;
        last = null;
        if (pending != null) pending.cancel(true);
        pending = new FutureTask<>(() -> lookup(newPath));
        Thread worker = new Thread(pending, "voice-remote");
        worker.setDaemon(true);
        worker.start();
    }

    private static Node lookup(String path) {
        return nodeForPath(Sh.run(DaemonCmds.inputDevices(), 2000), path);
    }

    int deviceId() {
        if (path == null) return Injector.VIRTUAL_KEYBOARD;
        try {
            if (pending != null) {
                last = pending.get();
                pending = null;
            }
            int id = liveId(last);
            if (id >= 0) return id;
            // InputReader can register its device just after the kernel's hotplug notification.
            last = lookup(path);
            return liveId(last);
        } catch (Exception e) {
            return Injector.VIRTUAL_KEYBOARD;
        }
    }

    private static int liveId(Node node) throws Exception {
        if (node == null) return Injector.VIRTUAL_KEYBOARD;
        Class<?> type = Class.forName("android.view.InputDevice");
        Object device = type.getMethod("getDevice", int.class).invoke(null, node.id);
        if (device != null && node.descriptor.equals(type.getMethod("getDescriptor").invoke(device))) {
            return node.id;
        }
        return Injector.VIRTUAL_KEYBOARD;
    }
}
