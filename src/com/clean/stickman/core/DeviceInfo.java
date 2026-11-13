package com.clean.stickman.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the output of the Status page's one-shot bridge command (core.cmd.StatusCmds.deviceInfo) into display
 * values; every line is recognised by pattern, so missing pieces just stay empty.
 */
public final class DeviceInfo {

    private static final Pattern MEM = Pattern.compile("^(MemTotal|MemAvailable):\\s+(\\d+)\\s*kB");
    private static final Pattern TEMP = Pattern.compile("mValue=(-?[0-9.]+)(?:.*?mType=(-?\\d+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DF = Pattern.compile("^\\S+\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\d+%)\\s+/\\S*\\s*$");
    private static final Pattern RSSI = Pattern.compile("RSSI:\\s*(-?\\d+)");
    private static final Pattern SSID = Pattern.compile("SSID:\\s*\"([^\"]*)\"");
    private static final Pattern INET = Pattern.compile("^\\s*inet\\s+([0-9.]+)");

    public long memTotalMb, memAvailMb;
    public float cpuMax = Float.NaN, anyMax = Float.NaN;
    public String storage, ssid, ip, fireOs;
    public int rssi; // 0 = unknown

    public static DeviceInfo parse(String out) {
        DeviceInfo d = new DeviceInfo();
        if (out == null) return d;
        String last = null;
        for (String line : out.split("\r?\n")) {
            if (line.trim().isEmpty()) continue;
            Matcher m;
            if ((m = MEM.matcher(line.trim())).find()) {
                long mb = Long.parseLong(m.group(2)) / 1024;
                if (m.group(1).equals("MemTotal")) d.memTotalMb = mb; else d.memAvailMb = mb;
            } else if ((m = TEMP.matcher(line)).find()) {
                try {
                    float v = Float.parseFloat(m.group(1));
                    if (Float.isNaN(d.anyMax) || v > d.anyMax) d.anyMax = v;
                    if ("0".equals(m.group(2)) && (Float.isNaN(d.cpuMax) || v > d.cpuMax)) d.cpuMax = v;
                } catch (NumberFormatException ignored) {}
            } else if ((m = DF.matcher(line.trim())).find()) {
                d.storage = m.group(3) + " free of " + m.group(1) + " (" + m.group(4) + " used)";
            } else if ((m = RSSI.matcher(line)).find()) {
                d.rssi = Integer.parseInt(m.group(1));
                Matcher s = SSID.matcher(line);
                if (s.find()) d.ssid = s.group(1);
            } else if ((m = INET.matcher(line)).find()) {
                d.ip = m.group(1);
            } else {
                last = line.trim();
            }
        }
        d.fireOs = last;
        return d;
    }

    public String cpuTemp() {
        float v = Float.isNaN(cpuMax) ? anyMax : cpuMax;
        return Float.isNaN(v) ? null : String.format(Locale.ROOT, "%.1f °C", v);
    }

    public static String signal(int rssi) {
        if (rssi == 0) return "Unknown";
        if (rssi >= -50) return "Excellent";
        if (rssi >= -60) return "Good";
        if (rssi >= -70) return "Fair";
        return "Weak";
    }
}
