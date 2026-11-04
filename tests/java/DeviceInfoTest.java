import com.clean.stickman.core.DeviceInfo;

public class DeviceInfoTest {
    static final String OUT =
        "MemTotal:        1745332 kB\n"
        + "MemAvailable:     812004 kB\n"
        + "\tTemperature{mValue=47.5, mType=0, mName=cpu0, mStatus=0}\n"
        + "\tTemperature{mValue=39.0, mType=2, mName=battery, mStatus=0}\n"
        + "\tTemperature{mValue=49.25, mType=0, mName=cpu1, mStatus=0}\n"
        + "/dev/block/dm-5   10G  3.2G  6.8G  33% /data\n"
        + "mWifiInfo SSID: \"Home\", BSSID: aa:bb, MAC: 02:00, Supplicant state: COMPLETED, RSSI: -52, Link speed: 433Mbps\n"
        + "    inet 192.168.0.221/24 brd 192.168.0.255 scope global wlan0\n"
        + "Fire OS 8.1.0.3 (RS8130/4049)\n";

    public void testParse() {
        DeviceInfo d = DeviceInfo.parse(OUT);
        T.eq(1704L, d.memTotalMb, "total");
        T.eq(792L, d.memAvailMb, "avail");
        T.eq("49.3 °C", d.cpuTemp(), "max cpu temp");
        T.eq("6.8G free of 10G (33% used)", d.storage, "storage");
        T.eq(-52, d.rssi, "rssi");
        T.eq("192.168.0.221", d.ip, "ip");
        T.eq("Fire OS 8.1.0.3 (RS8130/4049)", d.fireOs, "fire os");
        T.eq("Good", DeviceInfo.signal(-52), "signal word");
    }

    public void testEmpty() {
        DeviceInfo d = DeviceInfo.parse("");
        T.eq(null, d.cpuTemp(), "no temp");
        T.eq(0L, d.memTotalMb, "no mem");
        T.eq(null, d.fireOs, "no os");
    }
}
