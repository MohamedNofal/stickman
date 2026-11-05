# How Stickman works


Everything useful needs the `shell` user, which can read input devices, create virtual ones and run privileged `am`/`cmd`/`settings` commands. A normal app can't get shell, and Fire OS blocks the usual workaround.

## VPN self-ADB bridge

1. **Fire OS adbd refuses connections from the device itself.** Amazon patched adbd's TCP accept loop. For each new client it tries to `bind()` a socket to the client's own `ip:port`. If that succeeds (or fails with `EADDRINUSE`), the address belongs to this device and the connection is closed silently. Loopback, Wi-Fi IP, IPv6, even a hotspot address: all are refused. Wireless debugging is also stripped from Fire OS.
2. **Bypass: a VPN with a user-space TCP stack.** The app starts a `VpnService` that routes *only* a fake address, `10.99.0.2/32`. Normal traffic is untouched. The app writes raw IPv4/TCP packets into the tun interface, with source `10.99.0.2` and destination the tun's own address `10.99.0.1:5555`. The kernel delivers them to adbd, whose `bind()` check now fails with `EADDRNOTAVAIL`, so the client counts as remote. The app then speaks the ADB protocol itself, including RSA auth. You approve its key on the TV once. Now it can run commands as **shell (uid 2000)**.
3. Commands go through a FIFO queue (`Bridge`, `core/CommandQueue`). The bridge refuses to run while a different VPN is active without the user acting.

## The daemons (run as shell)

- **`remapd`** (`native/remapd.c`, static armv7 C, plus the layer state machine in `native/layers.c`):
  - Takes exclusive control of `"Amazon Fire TV Remote"` with `EVIOCGRAB`. SELinux allows shell only read access to input devices, but a read-only handle is enough to grab.
  - Creates a `uinput` clone with the same vendor/product IDs, so Android applies the same key layout. Shell is in group `net_bt_admin`, which owns `/dev/uinput`.
  - Forwards every event to the clone, except remapped keys, which run their action. Handles tap/double/hold layers and per-app profiles, and re-grabs the remote when it reconnects (inotify on `/dev/input`).
  - Stickman actions are written to the **`act/` spool** for stickd.
- **`stickd`** (`daemon/Stickd.java`, plus `AutoKill`, `Sleep`): a Java daemon started with `app_process` as shell. It tracks the foreground app (and switches profiles), runs auto-kill and Boost, executes spooled actions, runs the sleep timer and stay-awake, and the DNS safety guard.
- **The `act/` spool**: files in `/data/local/tmp/stickman/act/`, written as `.tmp` then renamed to `.act` (`core/Spool.java`). Used instead of a FIFO because SELinux forbids shell `mkfifo`. No sockets are opened.
- **Tasks feed** (stickd -> logcat tag `Stickd`, read by the Tasks page): sampled every 2 s while the page is live, using `/proc` plus a `dumpsys` refresh every 10 s.
  - `STATUS tasks total=<kB> avail=<kB> cpu=<pct> seq=<n>`, then one `STATUS task <pkg> rss=<kB> cpu=<pct> state=<fg|play|bg|cached|sys> up=<sec> procs=<n> seq=<n>` per app, then `STATUS tasks_end seq=<n>`. `seq` restarts at 1 for each sampling session.
  - Spool actions `tasks_start` (sent again every 60 s; sampling stops 90 s after the last one) and `tasks_stop`, plus `end <pkg>` and `end_all`. None of them appear in the button-action menu.
  - System processes not shown individually are summed into one `_sys_other` aggregate line, which keeps the feed small. stickd also raises the logcat main ring buffer to 1 MB (`logcat -G 1M`, only if it is smaller; it resets at reboot) so the feed isn't evicted between reads.
  - Sampler cost measured on the stick: about 1 % of all CPUs (limit 3 %).
  - Each `tasks_start` / `tasks_stop` is a Bridge round-trip, and a Bridge command drops adb sessions to the stick. With the Tasks page open that happens every 60 s (every 10 s while the feed is stale), so anything driving the stick over adb (e.g. the phone-side redirect script) should expect reconnects then.
- **Originals journal**: before a feature first changes a system value (DNS, animation, appops, `device_config`, screensaver/sleep, HDMI-CEC, disabled packages), the original is recorded in `/data/local/tmp/stickman/originals` (`core/Originals.java`). "Restore defaults" replays it.
- **Boot**: `BootReceiver` (boot completed + package replaced) plus a persisted `BootJob` re-run the idempotent ensure script (`core/EnsureScript.java`) that installs and starts the daemons.

| Area | Where |
|---|---|
| Bridge | `src/com/clean/stickman/{Bridge,BridgeService,BridgeWorker,MiniTcp,TunReader,AdbClient,AdbKey}.java` |
| Pure logic (host-tested) | `src/com/clean/stickman/core/` (remap model, presets, catalogues, kill policy, sleep timer, DNS guard, parsers) |
| Daemon | `src/com/clean/stickman/daemon/` |
| UI | `MainActivity`, `RemotePage`, `RemoteView`, `ButtonEditor`, `SpeedPage`, `SleepPage`, `TasksPage`, `PrivacyPage`, `DebloatPage`, `ShortcutsPage`, `StatusPage`, `SettingsPage` |
| Input daemon | `native/remapd.c`, `native/layers.c` |

## Building from source

Requirements: JDK 17+, Android SDK `platforms/android-34` and `build-tools` (`d8.jar`), `aapt2`, `zipalign`, `apksigner`, and an ARM cross compiler:

```sh
sudo apt install gcc-arm-linux-gnueabihf libc6-dev-armhf-cross
ANDROID_JAR=/path/to/android.jar D8_JAR=/path/to/d8.jar ./build.sh   # -> stickman.apk
```

`build.sh` creates its own signing keystore on first run (`sb.keystore`, git-ignored) and also builds `native/fakeremote` (dev-only, not in the APK).

- **Host tests**: `tests/run.sh` (plain javac for `core/` and `daemon/`, host gcc for the native tests).
- **On-device input testing**: `tools/devtest.sh <scenario-file>` (scenarios in `tools/scenarios/`) pushes `remapd` and `native/fakeremote`, which creates a fake uinput remote, runs the scenario through a test `remapd`, reports output keys, actions and latency, then restores the production daemon. Set `STICK=<ip>:5555` for your device.
- `tools/RemotePreview.java` renders the remote presets.

