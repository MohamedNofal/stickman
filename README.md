# Stickman: power remote and stick manager for Fire TV (no root)

<img src="art/icon-512.png" width="96" align="right" alt="icon">

Stickman remaps every button on an Amazon Fire TV remote, keeps the stick fast, controls sleep and the screensaver, blocks ads and strips bloat, all from one D-pad-friendly app, with **no root**. A remapped button's original action never fires: no flash of the Amazon home screen, no half-opened Netflix.

Package `com.clean.stickman`. Formerly **KeyShift** (`com.clean.keyshift`, before that "Remote Remap"). On first run Stickman imports the live remap rules and uninstalls `com.clean.keyshift` automatically, so an upgrade keeps your mappings.

Tested on a Fire TV Stick 4K Max (Fire OS 8 / Android 11, model `AFTMA08C15`).

## Screenshots

| Remote | Tasks |
|---|---|
| ![Remote](docs/screenshots/remote.png) | ![Tasks](docs/screenshots/tasks.png) |
| **Speed** | **Debloat** |
| ![Speed](docs/screenshots/speed.png) | ![Debloat](docs/screenshots/debloat.png) |
| **Sleep** | |
| ![Sleep](docs/screenshots/sleep.png) | |

## Features

- **Remote**: a drawn remote you navigate with the D-pad.
  - Presets for every Fire TV remote model, a "Remote model" chooser, and buttons whose keycode is unknown can be learned.
  - App buttons can be renamed.
  - **Listen mode**: press a physical button and it lights up on the drawing and opens its editor.
  - Each button has **tap, double-tap and hold** layers (hold only on keys that report holds), plus **per-app profiles** that switch automatically with the foreground app.
  - Any layer can open an app, act as another button, do nothing, or run a **Stickman action**: Boost, sleep now, sleep timer (with fade), screen off, screensaver toggle, screenshot, media play/pause/next/previous, kill the current app, or open a hidden Amazon screen.
- **Speed**:
  - Auto-kill background apps when you leave an app and/or on an interval, with whitelist/blacklist and protection for media playback, the home app and the foreground app; one-tap **Boost**.
  - **Lean memory** (Balanced / Max): caps how many idle apps Android keeps in memory and turns on memory compaction, live, no reboot. On a 4K Max, capping at 4 freed 67 MB instantly. Max also delays the restart of killed background services (30 s instead of 1 s).
  - **Deep sleep while the TV is off**: switches on Android's Doze (Amazon ships it disabled) and drops Amazon's log, crash-report and promo-notification services from its exempt list. Re-applied after every boot.
  - **Keep asleep / Starve** any app or Amazon background service: Asleep blocks background starts; Starve also blocks wake locks, legacy background work and foreground services, and closes it now if it's in the background.
  - Animation speed.
- **Sleep**: screensaver, screen/sleep timeouts, stay awake while media plays, sleep timer with a fade, screen off, and an **HDMI-CEC** on/off switch.
- **Privacy**: Private-DNS ad-blocking presets with an automatic safety revert if connectivity fails, and limit ads and tracking. Works alongside Net Filter.
- **Debloat**:
  - A **Recommended** list of ~37 Amazon extras (ad/telemetry collectors, ambient/promo screens, shopping, leftovers), cross-checked against a dozen open-source Fire TV debloaters, with a one-press **Disable all**; plus an advanced list.
  - **Free up space**: Amazon keeps downloading updates for apps even after you disable them. This puts disabled apps back to their small built-in version (they stay disabled). On a 4K Max it freed 424 MB.
  - **Check after an update**: Fire OS updates can quietly switch disabled apps back on; this finds anything that came back and switches it off again.
  - Critical package families (launcher, Settings, the system updater, remote/input services) are hard-blocked. Only packages Fire OS actually disabled are listed as "Disabled by Stickman". Every change is journaled and can be restored.
- **Shortcuts**: one-tap tiles into Amazon's hidden settings screens.
- **Tasks**: a live treemap of running apps sized by RAM (or CPU; Sort by name shows alphabetical rows), each tile in its app's brand colour, with system processes in a strip below. Select a tile for details (RAM, CPU, a RAM trend) and End, **Always kill** (close it every time it goes to the background) or **Protect** (never close it automatically); the two lists are exclusive. Menu opens sort order and End all; Play/Pause freezes the page. Never ends the foreground app, playing media, protected (whitelisted) apps, the launcher, Net Filter, Amazon core services or Stickman itself.
- **Status**: RAM, temperature, storage, Wi-Fi, Fire OS version, daemon health.
- **Settings**: master off, restore defaults, double-tap/hold timings, fix the clock, clear a stuck HTTP proxy.

## How it works

Everything useful needs the `shell` user, which can read input devices, create virtual ones and run privileged `am`/`cmd`/`settings` commands. A normal app can't get shell, and Fire OS blocks the usual workaround.

### VPN self-ADB bridge

1. **Fire OS adbd refuses connections from the device itself.** Amazon patched adbd's TCP accept loop. For each new client it tries to `bind()` a socket to the client's own `ip:port`. If that succeeds (or fails with `EADDRINUSE`), the address belongs to this device and the connection is closed silently. Loopback, Wi-Fi IP, IPv6, even a hotspot address: all are refused. Wireless debugging is also stripped from Fire OS.
2. **Bypass: a VPN with a user-space TCP stack.** The app starts a `VpnService` that routes *only* a fake address, `10.99.0.2/32`. Normal traffic is untouched. The app writes raw IPv4/TCP packets into the tun interface, with source `10.99.0.2` and destination the tun's own address `10.99.0.1:5555`. The kernel delivers them to adbd, whose `bind()` check now fails with `EADDRNOTAVAIL`, so the client counts as remote. The app then speaks the ADB protocol itself, including RSA auth. You approve its key on the TV once. Now it can run commands as **shell (uid 2000)**.
3. Commands go through a FIFO queue (`Bridge`, `core/CommandQueue`). The bridge refuses to run while a different VPN is active without the user acting.

### The daemons (run as shell)

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

## Install

No computer needed.

1. On the Fire TV: **Settings, My Fire TV, About**, then press OK on your device name 7 times to unlock **Developer options**.
2. **Settings, My Fire TV, Developer options**: turn on **ADB debugging**, and allow apps from unknown sources for **Downloader**.
3. Install **Downloader** from the Appstore and open this address in it: `https://github.com/MohamedNofal/stickman/releases/latest/download/stickman.apk`. Install it.
4. Open **Stickman**:
   - Accept the **VPN request**. It's a tiny private VPN that only carries Stickman's own commands to the TV's debugging service; your internet traffic never goes through it.
   - When **"Allow USB debugging?"** appears, tick **Always allow** and press **OK**. Wake the TV first if the screensaver is on, as it can hide the prompt.

**ADB debugging must stay on**, since the app talks to the TV's own adbd.

With a computer instead: `adb connect <fire-tv-ip>:5555 && adb install stickman.apk`, then do step 4.

## Build

Requirements: JDK 17+, Android SDK `platforms/android-34` and `build-tools` (`d8.jar`), `aapt2`, `zipalign`, `apksigner`, and an ARM cross compiler:

```sh
sudo apt install gcc-arm-linux-gnueabihf libc6-dev-armhf-cross
ANDROID_JAR=/path/to/android.jar D8_JAR=/path/to/d8.jar ./build.sh   # -> stickman.apk
```

`build.sh` creates its own signing keystore on first run (`sb.keystore`, git-ignored) and also builds `native/fakeremote` (dev-only, not in the APK).

- **Host tests**: `tests/run.sh` (plain javac for `core/` and `daemon/`, host gcc for the native tests).
- **On-device input testing**: `tools/devtest.sh <scenario-file>` (scenarios in `tools/scenarios/`) pushes `remapd` and `native/fakeremote`, which creates a fake uinput remote, runs the scenario through a test `remapd`, reports output keys, actions and latency, then restores the production daemon. Set `STICK=<ip>:5555` for your device.
- `tools/RemotePreview.java` renders the remote presets.

## Safety

- **Emergency stop:** hold **Back for 10 s** and the remote is released instantly. If remapd dies, the grab is released too.
- **Master off** (Settings) releases the remote and stops both daemons without touching anything else.
- Never-disable (debloat) and never-kill (auto-kill) lists protect the launcher, Settings, OTA, remote/input services, Stickman itself, the foreground app and media playback.
- **Restore defaults** puts back every system change. It also works after uninstalling Stickman, because the journal lives in `/data/local/tmp/stickman/originals` and is read by a shell restore script.
- Anything that can make this app run a command gets shell on the device. The app exposes no sockets or exported command interfaces; keep it that way if you fork it.

## Limitations

- No root: no audio format or resolution control, and Amazon's protected system apps can't be disabled.
- Tried and **not** effective on Fire OS 8 (so not offered): the cached-apps freezer (kernel lacks the cgroup v2 freezer), re-enabling app standby buckets (Fire OS forces app idle off), disabling components inside protected apps, `pm suspend` (allowed, but background starts still happen).
- App-shortcut buttons report press and release at the same instant, so they are tap-only (no hold).
- Pro and 2026 remotes have extra buttons whose keycodes are unknown: they need learning, and may not be visible to remapd if they appear on another input node.
- Stay awake while media plays is not yet verified on the device.
- Fire OS 8 may override sleep timeouts.
- After a reboot the remote reconnects on its first press, which may act normally.

## Licence

MIT, see `LICENSE`.

## Credits

The icon and banner come from **"Full image of Tung Tung Tung Sahur"** (Wikimedia Commons, public domain because it is AI-generated; creator credited as **@noxaasht**). Source, licence and processing details are in `art/SOURCE.md`; images are made by `art/make_icons.py` (PIL). Remote research notes are in `docs/device/fire-tv-remotes.md`.
