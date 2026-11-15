# How Stickman works


Everything useful needs the `shell` user, which can read input devices, create virtual ones and run privileged `am`/`cmd`/`settings` commands. A normal app can't get shell, and Fire OS blocks the usual workaround.

## VPN self-ADB bridge

1. **Fire OS adbd refuses connections from the device itself.** Amazon patched adbd's TCP accept loop. For each new client it tries to `bind()` a socket to the client's own `ip:port`. If that succeeds (or fails with `EADDRINUSE`), the address belongs to this device and the connection is closed silently. Loopback, Wi-Fi IP, IPv6, even a hotspot address: all are refused. Wireless debugging is also stripped from Fire OS.
2. **Bypass: a VPN with a user-space TCP stack.** The app starts a `VpnService` that routes *only* a fake address, `10.99.0.2/32`. Normal traffic is untouched. The app writes raw IPv4/TCP packets into the tun interface, with source `10.99.0.2` and destination the tun's own address `10.99.0.1:5555`. The kernel delivers them to adbd, whose `bind()` check now fails with `EADDRNOTAVAIL`, so the client counts as remote. The app then speaks the ADB protocol itself, including RSA auth. You approve its key on the TV once. Now it can run commands as **shell (uid 2000)**.
3. Commands go through a FIFO queue (`Bridge`, `core/CommandQueue`). The bridge refuses to run while a different VPN is active without the user acting.

## The daemons (run as shell)

- **`remapd`** (`native/remapd.c`, static armv7 C, plus the layer state machine in `native/layers.c`):
  - Takes exclusive control of the remote with `EVIOCGRAB`. SELinux allows shell only read access to input devices, but a read-only handle is enough to grab.
  - Which device is the remote (`native/remote_pick.h`): the one named `"Amazon Fire TV Remote"` where there is one; otherwise the best of the devices with remote buttons, in tiers (Amazon Bluetooth, other Bluetooth, other hardware). Devices made by software, such as Amazon's `amzkeyboard` for the phone app, are only watched. The remote disconnects in standby and returns as a new device, so remapd never settles for a stand-in: a better device replaces a held guess as soon as it appears, and buttons pressed on another remote-like device make remapd follow them there. The grab is never taken while a button is down (Android would not see the release and the key would stay "held").
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

## Apps (installing and updating other apps)

GitHub edition only. Stickman's shell can run `pm install`, so an app can be installed, updated and removed without the "install unknown apps" prompt.

- **Where an app comes from** (`core/Apps.java`): the hand-picked **catalogue**, a typed **link** to an APK (https only), the latest release of a **GitHub project**, or an AFTVnews **Downloader code** (their short link is looked up, then treated like a link).
- **The file decides, not the link.** Before anything is installed the daemon reads the package name and version code from the APK's own manifest (`core/Axml.java`, `daemon/ApkFile.java`). A catalogue file must also match the catalogue's SHA-256. A typed link is downloaded, then held until the user has seen what it is and said yes. Android itself refuses an update that is signed with a different key than the installed app.
- **`daemon/AppInstaller.java`** (inside stickd) does the work on one worker thread. Spool actions: `app_install <id> <pkg>`, `app_get <typed>`, `app_confirm`, `app_cancel`, `app_update <pkg>`, `app_remove <pkg>`, `app_auto <pkg> 0|1`, `apps_refresh`. None of them appear in the button-action menu.
  - Progress is logged as `STATUS app:<package|link> state=<queued|lookup|downloading|confirm|installing|installed|removed|uptodate|error> [pct=] [pkg=] [vc=] [size=] [msg=]`.
  - `STATUS apps list=<pkg>:<auto>:<kind>:<update>,...` says what is kept up to date and whether a newer file is waiting.
  - The screen (`AppsPage`, `AppStore`) follows those lines, and reads what is installed from PackageManager.
- **Downloads** (`daemon/Http.java`) continue after a cut connection (HTTP `Range` + `If-Range`). Every bridge command brings the VPN up and down, and Android cuts the shell user's open connections each time; without this a 90 MB download would start over whenever another page sent a command.
- **Tracked apps** (`/data/local/tmp/stickman/apps.list`): what was installed through Stickman, and from where. Every 6 hours the daemon looks for newer files (catalogue: a higher version code; link, project or code: the file behind it changed) and installs them while the TV is asleep and nothing plays, unless that app's automatic updates are switched off. A user's action never waits behind an automatic download.
- **The catalogue.** `catalog/sources.json` lists the apps and where their own publishers release them, each pinned to its package name and the SHA-256 of its signing certificate. `catalog/resolve.py` turns that into `catalog.json` + `icons/` (version, link, size and SHA-256 per CPU type, banner art); a scheduled GitHub workflow (`catalog/catalog-workflow.yml`, parked there until the release token may publish workflows; until then it is run by hand) runs it every 6 hours and publishes the result as the only content of the `catalog` branch, which the app reads from `raw.githubusercontent.com`. A release that is another app, or is signed with another key, is refused until a person changes the pin; a source that fails keeps its last good entry; an unchanged file is not downloaded again.
- **Developer testing**: `setprop debug.stickman.catalog <url>` (shell) makes both the daemon and the app read another catalogue, with its art under `icons/` next to it. `tools/appstest.sh` sends the installer's spool actions over adb (`install`, `get`, `confirm`, `remove`, ...) and follows its `STATUS` lines until the job ends; it really installs and uninstalls.

| Area | Where |
|---|---|
| Bridge | `src/com/clean/stickman/{Bridge,BridgeService,BridgeWorker,MiniTcp,TunReader,AdbClient,AdbKey}.java` |
| Pure logic (host-tested) | `src/com/clean/stickman/core/` (remap model, presets, catalogues, kill policy, sleep timer, DNS guard, parsers) |
| Daemon | `src/com/clean/stickman/daemon/` |
| UI | `MainActivity`, `RemotePage`, `RemoteView`, `ButtonEditor`, `SpeedPage`, `SleepPage`, `TasksPage`, `AppsPage` (+ `AppStore`), `PrivacyPage`, `DebloatPage`, `ShortcutsPage`, `StatusPage`, `SettingsPage` |
| App catalogue | `catalog/sources.json`, `catalog/resolve.py`, `catalog/catalog-workflow.yml` (output on the `catalog` branch) |
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

