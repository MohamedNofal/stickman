package com.clean.stickman.daemon;

import java.util.Objects;

public class VoiceRemoteTest {
    private static final String PATH = "/dev/input/event7";
    private static final String FIRST = "1161aa33db731d933ba00d5272978d082f8f2032";
    private static final String SECOND = "d12b6151fb8706663ebd21a1abddb130da38693f";

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }

    private static void expectNode(String text, String path, int id, String descriptor) {
        VoiceRemote.Node node = VoiceRemote.nodeForPath(text, path);
        if (node == null) throw new AssertionError("Missing device entry for " + path);
        equal(id, node.id);
        equal(descriptor, node.descriptor);
    }

    // Pie's EventHub emits a block per kernel node. Paired remotes of the same model share a name and VID/PID.
    private static String device(int id, String name, String path, String descriptor) {
        return "    " + id + ": " + name + "\n"
                + "      Classes: 0x80000001\n"
                + "      Path: " + path + "\n"
                + "      Enabled: true\n"
                + (descriptor == null ? "" : "      Descriptor: " + descriptor + "\n")
                + "      Identifier: bus=0x0005, vendor=0x0171, product=0x0427, version=0x0001\n";
    }

    private static String dump(String... devices) {
        StringBuilder text = new StringBuilder("INPUT MANAGER (dumpsys input)\n\n"
                + "Event Hub State:\n  BuiltInKeyboardId: 3\n  Devices:\n");
        for (String device : devices) text.append(device);
        return text.append("\nInput Reader State:\n").toString();
    }

    public void testSelectedNodeAmongIdenticalRemotes() {
        String text = dump(device(11, "Amazon Fire TV Remote", "/dev/input/event70", SECOND),
                           device(24, "Amazon Fire TV Remote", PATH, FIRST));
        expectNode(text, PATH, 24, FIRST);
        expectNode(text, "/dev/input/event70", 11, SECOND);
        equal(null, VoiceRemote.nodeForPath(text, "/dev/input/event"));
    }

    public void testDescriptorCannotLeakFromNeighborOrOtherDumpSection() {
        String text = dump(device(3, "keyboard (aka device 0 - built-in keyboard)", "/dev/input/event1", FIRST),
                           device(11, "Amazon Fire TV Remote", PATH, null),
                           device(24, "Amazon Fire TV Remote", "/dev/input/event8", SECOND));
        equal(null, VoiceRemote.nodeForPath(text, PATH));
        expectNode(text, "/dev/input/event1", 0, FIRST);
        equal(null, VoiceRemote.nodeForPath(text
                + device(30, "outside Event Hub", "/dev/input/event42", SECOND), "/dev/input/event42"));
    }

    public void testDuplicateDescriptorsKeepSeparateIds() {
        String text = dump(device(7, "Amazon Fire TV Remote", "/dev/input/event2", FIRST),
                           device(35, "Amazon Fire TV Remote", PATH, FIRST));
        expectNode(text, "/dev/input/event2", 7, FIRST);
        expectNode(text, PATH, 35, FIRST);
    }

    public void testReconnectAtReusedKernelPath() {
        expectNode(dump(device(11, "Amazon Fire TV Remote", PATH, FIRST)), PATH, 11, FIRST);
        equal(null, VoiceRemote.nodeForPath(dump(), PATH));
        expectNode(dump(device(34, "Amazon Fire TV Remote", PATH, SECOND)), PATH, 34, SECOND);
    }

    public void testVoiceKeysAndOrdinaryNavigation() {
        for (String key : new String[]{"ALEXA", "VOICE_CMD", "SEARCH", "VOICE_ASSIST", "ASSIST"}) {
            equal(true, VoiceRemote.isVoiceKey(key));
        }
        for (String key : new String[]{"DPAD_CENTER", "HOME", "BACK", "POWER", "A", null}) {
            equal(false, VoiceRemote.isVoiceKey(key));
        }
    }
}
