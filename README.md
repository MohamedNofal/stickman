# Remote Remap for Fire TV (no root)

<img src="art/icon-512.png" width="96" align="right" alt="icon">

Remap the buttons on an Amazon Fire TV remote, including **Home** and the app shortcut buttons (Netflix, Prime Video, …), with **no root**. The original action never fires. There's no flash of the Amazon home screen and no half-opened Netflix.

Tested on a Fire TV Stick 4K Max (Fire OS 8 / Android 11, model `AFTMA08C15`).

## What it does

- A TV-friendly screen lists the remote's buttons. For each one you choose: **default**, **open an app**, **act as another button**, or **do nothing**.
- Press any remote button while the screen is open and the list jumps to it.
- Holding a remapped *normal* button (e.g. Home) for 0.7 s still performs its original action.
- It starts by itself after a reboot.
- **Emergency stop:** hold **Back for 10 s**. The remote is released instantly.

## How it works (the interesting part)

Remapping a Fire TV remote properly needs the `shell` user, which can read input devices and create virtual ones. A normal app can't get shell, and Fire OS blocks the usual workaround:

1. **Fire OS adbd refuses connections from the device itself.** Amazon patched adbd's TCP accept loop. For each new client it tries to `bind()` a socket to the client's own `ip:port`. If that succeeds (or fails with `EADDRINUSE`), the address belongs to this device and the connection is closed silently. Loopback, Wi-Fi IP, IPv6, even a hotspot address: all are refused. Wireless debugging is also stripped from Fire OS.
2. **Bypass: a VPN with a user-space TCP stack.** The app starts a `VpnService` that routes *only* a fake address, `10.99.0.2/32`. Normal traffic is untouched. The app then writes raw IPv4/TCP packets into the tun interface, with source `10.99.0.2` and destination the tun's own address `10.99.0.1:5555`. The kernel delivers them to adbd, whose `bind()` check now fails with `EADDRNOTAVAIL`, so the client counts as remote. The app then speaks the ADB protocol itself, including RSA auth. You approve its key on the TV once. Now it can run commands as **shell (uid 2000)**.
3. **`remapd`, a small static native daemon started as shell:**
   - It takes exclusive control of `"Amazon Fire TV Remote"` with `EVIOCGRAB`. SELinux allows shell only read access to input devices, but a read-only handle is enough to grab.
   - It creates a `uinput` clone with the same vendor/product IDs, so Android applies the same key layout. Shell is in group `net_bt_admin`, which owns `/dev/uinput`.
   - It forwards every event to the clone, except remapped keys, which run their action. Apps are opened with `am start` as shell, which isn't subject to Android's 5-second "app switch" delay after Home.
   - It re-grabs the remote as soon as it reconnects after sleep (inotify on `/dev/input`).

| File | Role |
|---|---|
| `src/.../MiniTcp.java` | Minimal TCP client over the tun fd |
| `src/.../AdbClient.java`, `AdbKey.java` | ADB wire protocol + RSA key / signing / adb public-key format |
| `src/.../BridgeService.java`, `BridgeWorker.java`, `Bridge.java` | VPN lifecycle and the shell-command queue |
| `native/remapd.c` | The remapper daemon |
| `src/.../MainActivity.java`, `RemapConfig.java`, `LogTail.java` | UI, rules, live "last button" display |
| `src/.../BootReceiver.java`, `BootJob.java` | Restart after reboot (+ periodic fallback job) |

## Install

You need a computer with `adb` **once**, for setup.

1. On the Fire TV: **Settings → My Fire TV → Developer options → ADB debugging: ON**.
2. From the computer:
   ```sh
   adb connect <fire-tv-ip>:5555
   adb install shellbridge.apk               # from the Releases page, or build it yourself
   adb shell appops set com.clean.shellbridge ACTIVATE_VPN allow   # pre-approve the VPN
   ```
3. Open **Remote Remap** on the TV. When **"Allow USB debugging?"** appears, tick **Always allow** and press **OK**. Wake the TV first if the screensaver is on, or the prompt can be hidden behind it.

After that the computer isn't needed. ADB debugging must stay enabled, since the app talks to the TV's own adbd.

## Build

Requirements: JDK 17+, Android SDK `platforms/android-34` and `build-tools` (`d8.jar`), `aapt2`, `zipalign`, `apksigner`, and an ARM cross compiler:

```sh
sudo apt install gcc-arm-linux-gnueabihf libc6-dev-armhf-cross
ANDROID_JAR=/path/to/android.jar D8_JAR=/path/to/d8.jar ./build.sh   # -> shellbridge.apk
```

`build.sh` creates its own signing keystore on first run (`sb.keystore`, git-ignored). Icon and banner are drawn by `art/make_icons.py` (PIL) into `res/`.

## Remote key codes (Linux, Fire TV 4K Max remote)

Home 172 · Back 158 · Menu 139 · Netflix 744 · Prime Video 745 · Apps 746 · Amazon Music 747 · D-pad 103/108/105/106 · OK 96 · Vol 114/115 · Mute 113 · Play/Pause 164 · Rewind 168 · Fast-forward 208

## Limitations

- App-shortcut buttons report press and release at the same instant, so "hold for original" can't work on them.
- After a reboot the remote reconnects on its first press, and that first press may act normally.
- Shell is not root. Amazon's protected system apps can't be changed.

## Security note

Anything that can make this app run a command gets shell on the device. The app only runs its own built-in commands and exposes nothing to the network or other apps. Keep it that way if you fork it.
