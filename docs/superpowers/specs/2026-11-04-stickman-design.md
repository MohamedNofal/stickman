# Stickman: design (stage 1)

*2026-11-04. Successor to KeyShift (`com.clean.keyshift`) in this repo.*

## Intent and the why

The owner runs a Fire TV Stick 4K Max (model `AFTMA08C15`, Fire OS 8 / Android 11) with Wolf Launcher as home. Out of the box the stick has several problems:
- Amazon's home screen and ads.
- Remote buttons hard-wired to Netflix, Prime and so on.
- Screensaver and sleep behaviour the owner doesn't control.
- A sluggish UI, with background apps piling up.
- A clock that boots a week behind.

KeyShift already proved a no-root route to **shell-level control** from an app on the stick itself, and used it to remap remote buttons.

**Stickman** turns that into a full "power remote + stick manager". It has one place to:
- remap the remote visually,
- layer extra actions onto buttons,
- keep the stick fast (auto-kill),
- control sleep and screensaver,
- block ads,
- strip bloat,
- jump to Amazon's hidden settings screens.

The owner wants it to feel like a polished product, with personality. The icon is a Tung Tung Tung Sahur-style character.

Success looks like this: the owner rarely needs Amazon's own settings or a computer again. The remote does exactly what they want, in every app. The stick stays quick and ad-light. Nothing Stickman does ever leaves the TV unusable.

## What already exists (build on it, don't redo it)

Everything is in this repo (`src/com/clean/keyshift/`, `native/remapd.c`, `build.sh`, `art/`, `res/`). The README explains the mechanism.

- **VPN self-ADB bridge.**
  - `BridgeService`, `BridgeWorker`, `MiniTcp`, `TunReader`, `AdbClient`, `AdbKey`, `Bridge`.
  - A one-address VPN plus a user-space TCP stack reaches the stick's own adbd. It bypasses Fire OS 8.1's "local client ⇒ deny" check. Commands then run as **shell (uid 2000)**.
  - Commands are queued with `Bridge.run(ctx, cmd, fromBackground)`.
  - The marker `RemapConfig.ENSURE` expands at run time to the idempotent "install/start remapd" script.
- **remapd** (static armv7 C daemon, run as shell, shipped as `lib/armeabi-v7a/libremapd.so`).
  - Takes exclusive control of the remote with `EVIOCGRAB` on "Amazon Fire TV Remote".
  - Re-emits its events through a `/dev/uinput` clone with the same vendor/product.
  - Runs actions per keycode from `/data/local/tmp/remap/remap.conf`, re-reading the file when it changes.
  - Re-grabs the remote via inotify when it reconnects.
  - Logs `KEY <code>` to logd under tag `RemapD`. Holding Back for 10 s releases the remote.
- **Boot:** `BootReceiver` (BOOT_COMPLETED + MY_PACKAGE_REPLACED) plus a persisted `BootJob`. "Once per boot" is keyed on the kernel `boot_id`.
- **UI:** a programmatic list of buttons with an app picker, plus a live "last button" display (`LogTail` reads logcat; READ_LOGS is self-granted via the bridge).
- **Verified facts about this device** (see the README and the earlier findings):
  - The shell holds 382 permissions, including FORCE_STOP, KILL_BACKGROUND, SUSPEND_APPS, WRITE_SECURE_SETTINGS, INJECT_EVENTS, READ_FRAME_BUFFER, SET_TIME, MANAGE_APP_OPS_MODES and NETWORK_SETTINGS.
  - The shell cannot use HDMI_CEC, MODIFY_AUDIO_SETTINGS, MAINLINE_NETWORK_STACK or MANAGE_USERS.
  - App buttons report press and release in the same instant, so they have no hold. Normal keys report real holds.
  - Remote key codes (Linux): Home 172, Back 158, Menu 139, Netflix 744, Prime Video 745, Apps 746, Amazon Music 747, D-pad 103/108/105/106, OK 96, Vol 114/115, Mute 113, Play/Pause 164, Rewind 168, Fast-forward 208, plus Power 116, Alexa 217 and others from `Vendor_0171_Product_0427.kl`.
  - Hidden Amazon screens and their intents are catalogued in the session's probe notes. Examples:
    - Display, Wi-Fi, Remotes, Developer options, Inputs, Quick Settings, Profiles and Ambient are all `am start -a <action>`.
    - Screen off is `com.amazon.tv.settings.action.SHOW_BLACK_CURTAIN`.
  - SELinux allows shell only read access to `/dev/input`. Grabbing still works, and `/dev/uinput` is writable.

## Goals (stage 1)

1. **Rebrand to Stickman** (new package `com.clean.stickman`). Use a Tung Tung Tung Sahur-style icon and banner: an original drawing of the wooden-log character with its bat, consistent with the existing art pipeline (`art/make_icons.py`, PIL).
   - On first run, import the rules remapd is already using (KeyShift's importer already does this).
   - Remove KeyShift. Keep the old app-and-launcher bookkeeping simple.
2. **Remote screen.** A drawing of the actual Fire TV remote, with every physical button in its real place.
   - D-pad moves focus between the drawn buttons spatially; OK opens that button's editor.
   - Each drawn button shows a hint of what it currently does.
   - **Listen mode:** the user presses a physical button; it lights up on the drawing and its editor opens.
3. **Layers per button:** single press, double press, and hold (hold only on keys that report holds; app buttons are tap-only).
   - Double press is opt-in per key, because it delays the single press by about 250 ms.
   - Hold keeps today's "hold for the original action" as one choice.
4. **Per-app profiles:**
   - A default profile, plus optional profiles tied to a foreground app (e.g. inside Kodi, Rewind does X).
   - Stickman tracks the foreground app and switches the active profile automatically.
5. **Action catalogue.** Any layer can be:
   - open an app
   - act as another remote button
   - do nothing
   - any **Stickman action**: Boost, sleep now, sleep timer, screen off (black curtain), toggle screensaver, screenshot, play/pause/next/previous (media session), open a hidden Amazon screen, go to Wolf/home, and kill the current app.
6. **Speed (auto-kill and Boost):**
   - **Auto-kill** runs when the user leaves an app and/or on an interval.
     - It is gentle first: `am send-trim-memory` → `am kill` → `am force-stop` only for user-blacklisted apps.
     - It never touches the foreground app, the home or launcher, persistent or system processes, Stickman, or anything whitelisted.
   - **Boost** is a one-tap kill of background apps plus `pm trim-caches`, showing RAM before and after.
   - **Keep asleep:** chosen apps get standby bucket `rare` / background running off.
   - **Animation speed:** 1× / 0.5× / off.
7. **Sleep and screen:**
   - Screensaver on/off.
   - Sleep and screen-off timers (warn that Fire OS 8 may override them).
   - **Stay awake while media plays.**
   - **Sleep timer** (15/30/60/90), which pauses media and then sleeps, with a gentle screen fade in the last minute.
   - **Screen off** while audio continues.
8. **Ads and privacy:**
   - **Private-DNS ad-blocking** with presets (AdGuard, NextDNS custom ID, Cloudflare family/security, off). A safety net turns it off automatically if connectivity checks fail after enabling.
   - Toggles for the ad ID, tracking, featured autoplay and usage metrics, plus a status card.
9. **Debloat:**
   - A curated **safe** list of Amazon extras the user can disable. Every change is recorded so **Restore all** works.
   - The launcher, Settings, OTA, `vizzini`, remote/input services and any protected package are hard-blocked from the list.
10. **Shortcuts:** one-tap tiles into Amazon's hidden screens, from the catalogue.
11. **Status:** RAM, CPU temperature, storage, Wi-Fi signal and IP, Fire OS version, remapd/daemon health.
12. **Fixes:** fix the clock (`cmd alarm set-time` + time server), clear a stuck HTTP proxy.

## Non-goals (stage 1)

- Stage 2: phone keyboard/touchpad/LAN control page, family controls (bedtime lock, time limits, kiosk), self-healing (crash restart, resume after reboot, buffering doctor).
- Stage 3: macro recorder, chords, secret codes, D-pad mouse and gamepad, overlays (stats/clock), Skip Intro, settings editor, logcat viewer, shell console, APK installer UI, permission editor.
- Anything needing root: HDMI-CEC control, audio format, resolution/frame-rate forcing, CPU frequency, protected Amazon packages.

## Anti-goals (a failure even if it "works")

- **The remote becomes unusable.** There must always be a way out:
  - the existing Back-10s release,
  - remapd dying releases the grab,
  - Stickman's master off switch.
- **The TV ends up with no home screen, no internet or no Settings** because of Stickman. That rules out disabling critical packages, and DNS changes without the safety net.
- **Auto-kill closes what the user is watching or listening to**, or a music app playing in the background.
- **Stickman is noticeably laggy**, or adds a delay to keys that have no layers.
- **Shell power is exposed to the network or to other apps.** No listening sockets, no exported command interfaces.
- **The Stickman UI only works with a touchscreen.** It must be fully usable with the D-pad.

## Constraints

- **Hardware:** Fire OS 8 / Android 11, 32-bit armv7 userland, about 1.7 GB RAM, 1080p display, density 320.
- **Build:** the existing toolchain only, which is javac `--release 8` + build-tools d8 + aapt2 + zipalign + apksigner + `arm-linux-gnueabihf-gcc -static`. There is no Gradle, AndroidX or Kotlin. UI is plain framework views, code-built or with simple resources.
- **One trusted ADB key per package.** The new package means the TV shows the "Allow USB debugging" prompt once more; wake the screen first.
- **ADB debugging must stay enabled** on the stick.
- **Testing:** `adb shell input` bypasses remapd, so remap behaviour needs the owner pressing the real remote. Everything else can be verified over ADB from the phone. The stick is at `192.168.0.221:5555`; the route setup is in memory notes.
- **Deliverables:** keep the GitHub repo `MohamedNofal/firetv-remote-remap` (private) updated, with releases carrying the APK. The signing keystore stays out of git.

## Decisions made with the owner

- Name **Stickman**. The icon is the Tung Tung Tung Sahur character, an original drawing in our style rather than a downloaded image.
- The remap UI is a **picture of the remote**, with **listen-then-remap**.
- Build in **stages**; stage 1 is the scope above.

- **Remote layout:** the physical remote has Power, Alexa, D-pad + OK, Back/Home/Menu, Rewind/Play/Fast-forward, Volume ±, Mute, **Recents, Settings, Live TV/Guide + Channel ±**, and four app buttons: **Prime Video (745), Netflix (744), Amazon Music (747), Apps (746)**. This is the Alexa Voice Remote Enhanced layout, and it's what gets drawn.
- **NordVPN is rarely used**, so short VPN use while applying changes is acceptable. Still, the bridge must never run while a different VPN is active without the user acting. If NordVPN is up, ask before applying.
- **Net Filter stays.** Its proxy `http_proxy=127.0.0.1:8888` is intentional. The "clear stuck proxy" fix only clears a proxy whose port has nothing listening, and never Net Filter's while it is running. Private-DNS ad-blocking runs alongside it.

## My calls (owner to check)

- **New package `com.clean.stickman`.** Wolf only takes a new icon or banner for a brand-new package, as shown earlier. It costs one more TV prompt; rules are imported.
- **Shell-side automation runs in a second, Java daemon (`stickd`).**
  - It runs as shell via `app_process` from Stickman's own APK. It handles foreground tracking (logcat events), profile switching, auto-kill, the sleep timer, stay-awake and the DNS safety net.
  - remapd stays a small native process for input only.
  - stickd and remapd talk through files in `/data/local/tmp/stickman/` (the active profile, the actions). They expose no sockets.
  - The app talks to them only through the existing bridge.
- **Double press uses a 250 ms window**; hold uses 600 ms; both are tunable in Stickman settings.
- **Off switch vs. restore:**
  - **Master off** releases the remote and stops both daemons. It is instant and touches nothing else.
  - A separate **Restore defaults** puts back every system change Stickman made: DNS, animation, standby buckets, screensaver/sleep values, disabled packages.
  - Every feature records the original value before its first change, in `/data/local/tmp/stickman/originals`. That survives uninstalling Stickman, and the shell restore script reads it.
- **Migration:**
  - On first successful bridge connection, Stickman's ensure script imports the old rules, then stops the old remapd. It then runs `pm uninstall com.clean.keyshift` itself and starts its own daemons from `/data/local/tmp/stickman/`.
  - Wolf adds the new tile by itself. Removing the old one is automatic on uninstall.
- **The bridge queue becomes a real FIFO.** It replaces "latest command wins", so separate changes are never dropped.
- **Hidden-screen and settings catalogue:** `docs/device/amazon-settings-actions.txt` holds the probed intent actions. Privacy keys confirmed on this device are `amz_limit_ad_tracking`, `limit_ad_tracking`, `advertisingIdApp:interestBasedAds:value`, `USAGE_METRICS_UPLOAD_ENABLED` (secure), and `com.amazon.tv.settings.featured_item_video_enabled` / `_volume` (global). Anything else gets verified on the device before it ships.
- **Visual style:** dark UI with warm wood/orange accents to match the Sahur theme. Left sidebar sections: Remote, Speed, Sleep, Privacy, Debloat, Shortcuts, Status, Settings.

## Left to the builder

- Exact screen layouts and drawing of the remote, within the style above.
- Config file formats, daemon internals, and parsing of `dumpsys`/logcat.
- The curated debloat list contents (safe extras only, each verified on the device to not break home or Settings).
- How the sleep fade is drawn.
- Test strategy for pure logic (host-side JVM/C tests of parsers and the layer state machine), plus device verification over ADB.
