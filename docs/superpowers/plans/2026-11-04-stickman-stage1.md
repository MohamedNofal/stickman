# Stickman Stage 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn KeyShift into **Stickman** (`com.clean.stickman`). Stage 1 adds:
- a remote-shaped remap screen with listen mode,
- tap/double/hold layers and per-app profiles,
- a Stickman action catalogue,
- auto-kill and Boost,
- sleep and screen controls,
- DNS ad-blocking with a safety net,
- debloat with restore,
- hidden-screen shortcuts, a status page and fixes,
- a Tung Tung Tung Sahur-style icon.

**Architecture:** The app keeps the proven **VPN self-ADB bridge** to run shell commands as uid 2000. Two daemons run as shell on the stick:
- **remapd** (native C) owns the remote: grab, uinput clone, tap/double/hold layers, per-profile rules.
- **stickd** (Java, run via `app_process` from Stickman's APK) owns automation: foreground tracking and profile switching, auto-kill/Boost, the sleep timer, stay-awake and the DNS guard.

They communicate through files in `/data/local/tmp/stickman/`, plus a FIFO from remapd to stickd for Stickman actions. The app only writes config and sends actions through the bridge, and reads daemon status from logcat.

**Tech Stack:**
- **Java side:** Java 8 source (`javac --release 8`), Android framework APIs only (no AndroidX), d8, aapt2, apksigner.
- **Native side:** static armv7 C (`arm-linux-gnueabihf-gcc -static`).
- **Host tests:** a tiny self-written Java harness, plus gcc for C.

**Spec:** `docs/superpowers/specs/2026-11-04-stickman-design.md` (read it; this plan argues from it).

## Global Constraints

- Package `com.clean.stickman`, app label **`Stickman`**, versionCode 1, versionName `1.0`. Output `stickman.apk`. Keystore `sb.keystore`, alias `sb`, pass `android`, git-ignored.
- **Target hardware:** Fire OS 8 / Android 11, 32-bit armv7, about 1.7 GB RAM, density 320. minSdk 21, targetSdk 30.
- **Toolchain:** `build.sh` only: javac `--release 8`, build-tools `d8.jar`, aapt2, zipalign, apksigner, `arm-linux-gnueabihf-gcc -O2 -static`. No Gradle, AndroidX or Kotlin. UI uses framework views; layouts are built in code.
- **Shared dir on the device:** `/data/local/tmp/stickman/`. It holds:
  - `remap.conf`, `profile`, `stickd.conf`, `act.fifo`
  - `remapd`, `remapd.pid`, `stickd.pid`, `version`, `originals`
- **Logs** (all read by the app via logcat): remapd uses tag `RemapD` (keeps the existing `KEY <code>` lines); stickd uses tag `Stickd` and prints `STATUS ...` lines; the app uses tag `Stickman`.
- **Timings:** double window `250` ms and hold threshold `600` ms, both user-tunable. Holding Back for `10` s releases the remote. Auto-kill waits `10` s after the user leaves an app.
- **Safety:**
  - Nothing ever listens on a network socket. No exported component accepts commands; only `FadeReceiver` is exported, and it only draws an overlay.
  - **Never disable** `com.amazon.tv.launcher`, `com.amazon.firehomestarter`, `com.amazon.tv.settings*`, `com.amazon.device.software.ota*`, `com.amazon.vizzini`, `com.amazon.tv.notificationcenter`, `com.amazon.hedwig`, `com.amazon.tv.livetv`, any input/remote/bluetooth package, `com.clean.netfilter`, `com.wolf.firelauncher`, or Stickman itself.
  - **Never kill** the foreground app, the home/launcher apps (`com.wolf.firelauncher`, `com.amazon.tv.launcher`, `com.amazon.firehomestarter`), persistent/system procs (lru `pers`, `PER`, `sys`, `HOME`, `TOP`, `vis`), whitelisted apps, Stickman, or any package with a playing media session.
  - **Leave the Net Filter proxy alone:** never clear `http_proxy` while something listens on its port.
- **Remote keys (Linux codes):**
  - Power 116, Alexa 217, Up 103, Down 108, Left 105, Right 106, OK 96
  - Back 158, Home 172, Menu 139
  - Rewind 168, Play/Pause 164, Fast-forward 208
  - Vol+ 115, Vol− 114, Mute 113
  - Recents 748, Settings 249, Live TV 362, Ch+ 402, Ch− 403
  - Prime Video 745, Netflix 744, Amazon Music 747, Apps 746
  - App buttons are tap-only: their press and release arrive together.
- **Device testing:** the stick is at `192.168.0.221:5555`.
  - From the phone, run `su -c 'ip route add 192.168.0.0/24 dev wlan0 scope link src 192.168.0.101'; adb connect 192.168.0.221:5555`.
  - `adb shell input` bypasses remapd. Remote behaviour is verified by the owner pressing the real remote.
  - Never reboot, factory-reset, or disable packages on the device without the owner's go-ahead.

## Shared formats (every task uses these exact formats)

**`remap.conf` v2** (written by the app; read by remapd and stickd)
```
# stickman remap v2
set double_ms 250
set hold_ms 600
profile default
<code> <layer> <action>
profile <package.name>
<code> <layer> <action>
```
- `layer` is one of `tap | double | hold`.
- `action` is one of: `start <pkg/activity>` | `key <code>` | `none` | `orig` | `act <id> [arg]` | `sh <command…>`.
- Lines before any `profile` line belong to `default`.
- A v1 line with no layer (`<code> start <comp>`) means a `tap` rule in `default`.
- Unknown or garbled lines are ignored.
- **Rule lookup:** if the active profile defines any layer for a code, that profile's layers are used for that code; otherwise the code falls back to `default`.
- **Implicit defaults** for a code that has rules:
  - a missing `tap` layer means `orig` (emit the original key);
  - a missing `hold` layer means `orig` once held for `hold_ms`.
- A code with no rules in the effective profile passes through **immediately**, with no added delay.

**`profile`**: one line holding the active profile name (a package name or `default`). stickd writes it; remapd re-reads it when its mtime changes.

**`stickd.conf`**: `key=value` lines. Unknown keys are ignored.
```
autokill.on_leave=0|1
autokill.interval_min=0|5|15|30|60      (0 = off)
autokill.whitelist=pkg,pkg
autokill.blacklist=pkg,pkg              (these get am force-stop; others only am kill)
stayawake.media=0|1
```

**`act.fifo`** (named pipe): one action per line, `<id> [arg]`. remapd writes it for `act` rules; the app writes it via the bridge.

Action ids (constants in `core/Actions.java`):

| id | what it does |
|---|---|
| `boost` | Kill background apps and trim caches |
| `sleep_now` | Put the stick to sleep |
| `sleep_timer` | Cycle 15→30→60→90→off |
| `sleep_timer_set <min>` | Set the sleep timer to `<min>` minutes |
| `screen_off` | Show the black curtain |
| `screensaver_toggle` | Turn the screensaver on/off |
| `screenshot` | Save a screenshot |
| `play_pause` | Play/pause the current media |
| `next` | Next track/item |
| `previous` | Previous track/item |
| `kill_current` | Force-stop the foreground app |
| `open_screen <amazon-action>` | Open a hidden Amazon screen |
| `dns_check` | Run the DNS safety check |
| `reload` | Re-read `stickd.conf` and `remap.conf` |

**stickd `STATUS` log lines:**
- `STATUS fg <pkg> profile=<name>`
- `STATUS boost freed_mb=<n> killed=<n>`
- `STATUS sleep_timer <min|off>`
- `STATUS dns ok|reverted`
- `STATUS autokill killed=<pkg,…>`
- `STATUS started version=<n>`

## Review Focus

1. **The remote disconnects while a key is held or a double-press window is open.** Expected: nothing stays stuck down, and the next press works normally. (Task 4: `test_device_lost_resets`.)
2. **Keys with no rules feel exactly like before.** A D-pad press must not be delayed even when other keys have double layers. (Task 4: `test_unmapped_passes_immediately`.)
3. **A different key is pressed during a double window.** Expected: the pending key resolves as a tap immediately, then the new key is handled. (Task 4: `test_other_key_flushes_pending`.)
4. **Music keeps playing from a background app while the user browses.** Auto-kill must not kill it, nor Wolf or the foreground app. (Task 6: `KillPolicyTest.keepsPlayingMediaAndHome`.)
5. **A broken or unreachable DNS provider.** Private DNS must turn itself off and report it. Settings and config with garbage lines, or a profile for an app that was uninstalled, must be ignored, not crash anything. (Task 7: `DnsGuardTest`; Task 3: `RemapModelTest.ignoresGarbage`; Task 4: `test_parse_garbage`.)

---

### Task 1: Rename to Stickman, restructure packages, host test harness

**Files:**
- Move: `src/com/clean/keyshift/*.java` → `src/com/clean/stickman/*.java`. Package `com.clean.stickman`, log tag `"Stickman"`, ADB key name `stickman@firetv`, `host::stickman`.
- Create: `src/com/clean/stickman/core/` and `src/com/clean/stickman/daemon/`. These are package dirs for later tasks; the core and daemon classes must not import `android.*`, except `daemon/Stickd.java`, which may not either, because it runs under `app_process` without a Context.
- Modify: `AndroidManifest.xml`:
  - package `com.clean.stickman`, label `Stickman`, versionCode 1, versionName `1.0`
  - keep the activity-level `android:icon` and `android:banner`
  - add `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>`
- Modify: `build.sh`:
  - output `stickman.apk`
  - compile from `src/` recursively (already done via `find`)
  - native step builds `native/remapd` from `native/remapd.c native/layers.c`
- Create: `tests/java/T.java`, a harness with `static void eq(Object exp, Object act, String msg)`, `static void yes(boolean c, String msg)`, and a `main` that runs every `test*` method of the classes named in args via reflection, prints `PASS`/`FAIL` lines and exits non-zero on failure.
- Create: `tests/run.sh`. It:
  1. compiles `src/com/clean/stickman/core/**/*.java`, `src/com/clean/stickman/daemon/**/*.java` and `tests/java/**/*.java` with plain `javac --release 8` (no android.jar) into `build/hosttest`;
  2. runs `java -cp build/hosttest T <all *Test classes>`;
  3. then, for each `native/test_*.c`, runs `gcc -O0 -Wall -o build/<name> native/<name>.c native/layers.c && build/<name>`.

**Interfaces:**
- Produces: `com.clean.stickman.*` app classes (same names as the KeyShift ones), the harness `T`, and `tests/run.sh`.

- [ ] **Step 1:** `git mv` the sources, then run `sed` for the package, log tag, key name and `host::` string. Update the manifest and `build.sh` as listed above.
- [ ] **Step 2:** Write `tests/java/SmokeTest.java` with `testHarnessRuns()` calling `T.eq(2, 1+1, "math")`, and an empty `native/layers.c` stub so the build links.
- [ ] **Step 3:** Run `./build.sh && tests/run.sh`. Expected: `DONE -> …/stickman.apk`; `PASS SmokeTest.testHarnessRuns`; exit 0. `aapt2 dump badging stickman.apk` shows `package: name='com.clean.stickman'` and `application-label:'Stickman'`.
- [ ] **Step 4:** Commit: `git commit -m "Stickman: rename package, host test harness"`.

### Task 2: Bridge as a FIFO queue + VPN-in-use guard

**Files:**
- Create: `src/com/clean/stickman/core/CommandQueue.java` (pure Java)
- Modify: `src/com/clean/stickman/Bridge.java`, `BridgeWorker.java`, `BridgeService.java`
- Test: `tests/java/CommandQueueTest.java`

**Interfaces:**
- Produces:
  - `final class CommandQueue { void add(String cmd); String poll(); boolean isEmpty(); }`. It is a FIFO with one rule: consecutive identical commands are collapsed into one.
  - `Bridge.run(Context c, String cmd, boolean fromBackground)` keeps its signature but appends to the queue.
  - `Bridge.Listener` is an interface `void onResult(String cmd, String output, boolean ok)`, registered with `Bridge.setListener(Listener)` and called on the main thread for every executed command.
  - `Bridge.otherVpnActive(Context) -> boolean` is true if any network with `TRANSPORT_VPN` exists while our VPN isn't running.
  - `Bridge.VPN_BUSY` = `"FAIL vpn_busy"`, the result reported when a background call is skipped.

- [ ] **Step 1:** Write the tests:
  - `testFifoOrder` — add `a`, `b`, `c`; polls return `a`, `b`, `c`, then `null`.
  - `testCollapsesConsecutiveDuplicates` — add `a`, `a`, `b`, `a`; polls return `a`, `b`, `a`.
- [ ] **Step 2:** Run `tests/run.sh`. Expected: FAIL (`CommandQueue` missing).
- [ ] **Step 3:** Implement `CommandQueue`, then wire it into `Bridge`/`BridgeWorker`. The worker drains the queue in one VPN session and expands `RemapConfig.ENSURE` at run time as today.
  - Before establishing the VPN: if `otherVpnActive` is true and the call came from the background, report `VPN_BUSY` and drop the queue. If it came from the foreground, the UI must already have asked (Task 9 shows the dialog).
- [ ] **Step 4:** Run `tests/run.sh` and expect `PASS`. Run `./build.sh` and expect `DONE`.
- [ ] **Step 5:** Commit: `"Bridge: FIFO queue, listener, VPN-in-use guard"`.

### Task 3: Remap model (parse/serialize remap.conf v2, import v1)

**Files:**
- Create: `src/com/clean/stickman/core/RemapModel.java`, `core/Rule.java`, `core/Actions.java`, `core/Keys.java`
- Test: `tests/java/RemapModelTest.java`

**Interfaces:**
- Produces:
  - `enum Layer { TAP, DOUBLE, HOLD }` (nested in `Rule`).
  - `final class Rule { final int code; final Rule.Layer layer; final String action; }`. `action` is the raw action text, e.g. `"start org.xbmc.kodi/org.xbmc.kodi.Splash"` or `"act boost"`.
  - `final class RemapModel`, with:
    - `static RemapModel parse(String text)` and `String serialize()`
    - `int doubleMs` (default 250) and `int holdMs` (default 600)
    - `List<String> profiles()` (`default` first, then insertion order)
    - `String get(String profile, int code, Rule.Layer layer)` (null if unset)
    - `void set(String profile, int code, Rule.Layer layer, String action)` (null clears it)
    - `void addProfile(String pkg)`, `void removeProfile(String pkg)`
    - `boolean hasRules(String profile, int code)`
  - `final class Keys`, with:
    - `static final int[] ALL` (every remote code from Global Constraints)
    - `static String name(int code)` (e.g. `"Netflix"`, `"Play/Pause"`)
    - `static boolean tapOnly(int code)` (true for 744–747)
  - `final class Actions`, with:
    - the String constants for the action ids from Shared formats
    - `static String describe(String action, String appLabel)` (human text, e.g. `"Open Kodi"`, `"Acts as Back"`, `"Boost"`, `"Original"`, `"Nothing"`)

- [ ] **Step 1:** Write the tests:
  - `testParsesV2` — default tap 744 = `start org.xbmc.kodi/org.xbmc.kodi.Splash`, and profile `org.xbmc.kodi` hold 168 = `act boost`.
  - `testImportsV1` — `"744 start a/b.C\n172 start w/x.Y"` gives default tap rules.
  - `testRoundTrip` — `parse(serialize(m))` equals `m` field-for-field, including `set double_ms 300`.
  - `testIgnoresGarbage` — lines `"x y z"`, `"999999 tap none"`, `"744 wiggle none"` and `"profile"` are dropped, and the valid lines survive.
  - `testAppButtonsTapOnly` — `Keys.tapOnly(745)` is true and `Keys.tapOnly(172)` is false.
  - `testHasRulesFallsBackPerCode` — a profile with only 168 has `hasRules(p,168)` true and `hasRules(p,744)` false.
- [ ] **Step 2:** Run `tests/run.sh`. Expected: FAIL.
- [ ] **Step 3:** Implement the classes. Codes outside 1..767 are invalid. Serialization writes `set` lines, then `profile default`, then the other profiles in order. Within a profile, codes are written ascending, then layers in `tap`, `double`, `hold` order.
- [ ] **Step 4:** Run `tests/run.sh` and expect `PASS`.
- [ ] **Step 5:** Commit: `"core: RemapModel v2 parse/serialize, keys, actions"`.

### Task 4: remapd v2: layers state machine, profiles, actions FIFO

**Files:**
- Create: `native/layers.h`, `native/layers.c` (pure logic, no syscalls), `native/test_layers.c`
- Modify: `native/remapd.c`:
  - config v2 with profiles
  - `/data/local/tmp/stickman/` paths, re-reading the `profile` file
  - `act` writes to the FIFO; `orig`, `key`, `start`, `sh`, `none` as before
  - device loss calls `lyr_reset`

**Interfaces:**
- Produces (`layers.h`):
```c
typedef struct { int has_tap, has_double, has_hold, tap_only; } lyr_keycfg;
typedef enum { OUT_PASS, OUT_FIRE } lyr_kind;           /* PASS: emit original (code,value); FIRE: run rule */
typedef struct { lyr_kind kind; int code; int value; int layer; } lyr_out; /* layer: 0 tap,1 double,2 hold */
typedef struct lyr_state lyr_state;                      /* opaque, sized for 768 codes */
lyr_state *lyr_new(int double_ms, int hold_ms);
void lyr_set_cfg(lyr_state*, int code, lyr_keycfg cfg);  /* all-zero cfg = unmapped */
int  lyr_on_key(lyr_state*, int code, int value, long long now_ms, lyr_out *out, int max); /* returns n outputs */
int  lyr_on_tick(lyr_state*, long long now_ms, lyr_out *out, int max);
long long lyr_next_deadline(lyr_state*);                 /* -1 if none */
int  lyr_reset(lyr_state*, lyr_out *out, int max);       /* device lost: release any passed-through DOWN */

/* remap.conf v2 line parser (shared by remapd's loader and tests) */
typedef enum { RMP_RULE, RMP_PROFILE, RMP_SET } rmp_kind;
typedef struct { rmp_kind kind; int code; int layer; char action[256]; char name[128]; int value; } rmp_rule;
int  rmp_parse_line(const char *line, rmp_rule *out);    /* 1 = valid (fills out), 0 = ignore */
```
- **Semantics:**
  - **Unmapped key:** every event comes back immediately as `PASS`.
  - **Mapped key, DOWN:** swallowed.
  - **Hold:** if the key is still down at `hold_ms`, `lyr_on_tick` returns `FIRE(hold)`. When the hold layer is implicit `orig`, it returns `PASS(code,1)` instead, and later repeats and UP also pass.
  - **UP before `hold_ms`, no double layer:** returns `FIRE(tap)`.
  - **UP before `hold_ms`, double layer present:** opens a `double_ms` window. A second DOWN inside it, followed by its UP, gives `FIRE(double)`. If the window times out, `FIRE(tap)`.
  - **A different key goes DOWN while a window is pending:** first `FIRE(tap)` for the pending key, then that key's own handling.
  - **Implicit `orig` tap:** remapd itself turns `FIRE(tap)` into an emitted DOWN+UP of the original key.

- [ ] **Step 1:** Write the C tests in `native/test_layers.c` (asserts, prints `PASS <name>`, exits 1 on failure):
  - `test_unmapped_passes_immediately` — DOWN/UP of 103 with no cfg returns 1 `PASS` each, at the same `now_ms`.
  - `test_tap_fires_on_up` — cfg tap; DOWN@0, UP@100 gives `FIRE(tap)`.
  - `test_hold_fires_at_threshold` — cfg hold; DOWN@0; `tick@599` gives nothing; `tick@600` gives `FIRE(hold)`; UP gives nothing.
  - `test_hold_orig_passes_through` — cfg tap only; DOWN@0; `tick@600` gives `PASS(c,1)`; repeat gives `PASS(c,2)`; UP gives `PASS(c,0)`.
  - `test_double_fires` — cfg double; DOWN@0, UP@50, DOWN@200, UP@260 gives `FIRE(double)`.
  - `test_double_window_times_out_to_tap` — cfg double; DOWN@0, UP@50; `tick@299` gives nothing; `tick@300` gives `FIRE(tap)`.
  - `test_other_key_flushes_pending` — cfg double on 168; DOWN/UP 168 at 0/50; DOWN 103@100 gives `FIRE(tap,168)` then `PASS(103,1)`.
  - `test_tap_only_key_never_holds` — cfg `tap_only`; DOWN@0, UP@0 gives `FIRE(tap)`.
  - `test_device_lost_resets` — hold-orig is passing (DOWN emitted); `lyr_reset` returns `PASS(c,0)`; a later DOWN starts fresh.
  - `test_parse_garbage` — a parse helper `int rmp_parse_line(const char*, rmp_rule*)` (declared in `layers.h`) returns 0 for garbage lines and 1 for valid v2/v1 lines.
- [ ] **Step 2:** Run `tests/run.sh`. Expected: the C tests FAIL to link or fail asserts.
- [ ] **Step 3:** Implement `layers.c`, then integrate it into `remapd.c`:
  - The poll timeout becomes `min(lyr_next_deadline, 1000)`.
  - Config now has `profiles[]`; the effective cfg is recomputed per code when `remap.conf` or `profile` changes.
  - `act` lines write `"<id> [arg]\n"` to `act.fifo` with `O_WRONLY|O_NONBLOCK`. On ENXIO, log `stickd not running`.
  - The pidfile and conf move to `/data/local/tmp/stickman/`.
  - The `KEY <code>` log and the Back-10 s panic stay.
- [ ] **Step 4:** Run `tests/run.sh` and expect all `PASS`. Run `./build.sh` and expect `native/remapd` to build statically.
- [ ] **Step 5:** Commit: `"remapd v2: tap/double/hold layers, profiles, act FIFO"`.

### Task 5: stickd core: daemon, foreground tracking, profile switching, action dispatch

**Files:**
- Create: `src/com/clean/stickman/daemon/Stickd.java`, `daemon/Sh.java`, `core/EventParser.java`, `core/StickdConf.java`
- Test: `tests/java/EventParserTest.java`, `tests/java/StickdConfTest.java`

**Interfaces:**
- Consumes: `RemapModel.parse(...).profiles()` (Task 3); `Actions` ids (Task 3).
- Produces:
  - `EventParser.resumedPackage(String logLine) -> String`. It returns the package from an events-log `wm_set_resumed_activity: [0,<pkg>/<act>,<reason>]` line, or null.
  - `EventParser.selectProfile(String pkg, List<String> profiles) -> String`. It returns `pkg` if listed, else `"default"`.
  - `StickdConf.parse(String) -> StickdConf`, with public fields `boolean onLeave; int intervalMin; Set<String> whitelist, blacklist; boolean stayAwakeMedia`.
  - `Sh.run(String cmd) -> String` runs `/system/bin/sh -c` and returns stdout (10 s timeout). `Sh.log(String)` writes to logcat via `log -t Stickd`.
  - `Stickd.main(String[] args)`, where `args[0]` is the shared dir:
    - writes `stickd.pid`;
    - creates `act.fifo` if missing (`mkfifo`);
    - thread **A** follows `logcat -b events -T 1 -s wm_set_resumed_activity` (fresh lines only), writes `profile` whenever the selected profile changes, and logs `STATUS fg <pkg> profile=<p>`;
    - thread **B** reads `act.fifo` lines and dispatches them to handlers registered in a `Map<String, Handler>` (`interface Handler { void run(String arg); }`, nested);
    - re-reads both confs on `reload`;
    - logs `STATUS started version=<n>`.
  - Handlers registered here: `screen_off`, `screensaver_toggle`, `screenshot`, `play_pause`, `next`, `previous`, `kill_current`, `open_screen`, `sleep_now`, `reload`.
    - `screen_off` runs `am start -a com.amazon.tv.settings.action.SHOW_BLACK_CURTAIN`.
    - `screenshot` runs `screencap -p /sdcard/Pictures/Stickman/shot_<yyyyMMdd_HHmmss>.png`.
    - Media actions run `cmd media_session dispatch play-pause|next|previous`.
    - `kill_current` runs `am force-stop` on the foreground pkg unless it's a home/launcher pkg.
    - `open_screen` runs `am start -a <arg>`.
    - `sleep_now` runs `input keyevent KEYCODE_SLEEP`.
    - `boost`, `sleep_timer*` and `dns_check` are registered in Tasks 6 and 7.

- [ ] **Step 1:** Write the tests:
  - `testResumedPackage` — `"11-04 20:52:09.443 689 757 I wm_set_resumed_activity: [0,org.xbmc.kodi/.Splash,resumeTopActivityInnerLocked]"` gives `"org.xbmc.kodi"`, and an unrelated line gives `null`.
  - `testSelectProfile` — `("org.xbmc.kodi", [default, org.xbmc.kodi])` gives `"org.xbmc.kodi"`; `("app.wako", …)` gives `"default"`.
  - `testStickdConf` — parses all keys from Shared formats, ignores unknown keys and bad numbers (`autokill.interval_min=abc` gives 0), and splits/trims the CSV lists.
- [ ] **Step 2:** Run `tests/run.sh`. Expected: FAIL.
- [ ] **Step 3:** Implement. stickd is launched as `CLASSPATH=<apk> setsid nohup app_process /system/bin --nice-name=stickd com.clean.stickman.daemon.Stickd /data/local/tmp/stickman` (Task 9 builds the command).
- [ ] **Step 4:** Run `tests/run.sh` and expect `PASS`. Then check on the device:
  1. `adb push stickman.apk /data/local/tmp/s.apk`
  2. Run the launch command with `CLASSPATH=/data/local/tmp/s.apk`.
  3. `logcat -d -s Stickd` shows `STATUS started`.
  4. `am start -n org.videolan.vlc/.StartActivity` logs `STATUS fg org.videolan.vlc profile=default`.
  5. `echo screenshot > /data/local/tmp/stickman/act.fifo` creates the PNG.
  6. Kill stickd and delete the test PNG.
- [ ] **Step 5:** Commit: `"stickd: daemon core, foreground tracking, profiles, action dispatch"`.

### Task 6: Auto-kill and Boost (stickd)

**Files:**
- Create: `src/com/clean/stickman/core/LruParser.java`, `core/KillPolicy.java`, `core/MediaState.java`, `src/com/clean/stickman/daemon/AutoKill.java`
- Test: `tests/java/LruParserTest.java`, `tests/java/KillPolicyTest.java`, `tests/java/MediaStateTest.java`

**Interfaces:**
- Consumes: `StickdConf` and the `Stickd` handler map / foreground events (Task 5).
- Produces:
  - `LruParser.parse(String dumpsysActivityLru) -> List<LruParser.Proc>`, where `Proc { String adj; String state; int pid; String process; String pkg; }`. `pkg` is the process name up to `:`. The input line format is `#N: <adj> <state> <flags> pid:proc/uid` (e.g. `#12: cch+15 CEM ---- 4321:org.videolan.vlc/u0a88`).
  - `MediaState.playingPackages(String dumpsysMediaSession) -> Set<String>`: packages whose session has `state=PlaybackState {state=3`.
  - `KillPolicy.candidates(List<Proc> procs, String fgPkg, Set<String> whitelist, Set<String> playing) -> List<String>`. It returns distinct pkgs whose adj starts with `cch`, `prev`, `svcb` or `svcl` (or whose state is `CEM`/`CAC`/`LAST`), excluding everything on the never-kill list in Global Constraints.
  - `AutoKill`:
    - registers `boost` (candidates → for each: `am send-trim-memory <pkg> COMPLETE`, then `am kill <pkg>`, or `am force-stop` if blacklisted; then `pm trim-caches 999G`);
    - computes `freed_mb` from `/proc/meminfo` MemAvailable before and after;
    - logs `STATUS boost freed_mb=<n> killed=<n>`;
    - **on leave:** 10 s after the foreground pkg changes, kills candidates if `onLeave`;
    - **interval:** kills every `intervalMin` minutes if > 0;
    - logs `STATUS autokill killed=<csv>`.

- [ ] **Step 1:** Write the tests. Use real `dumpsys activity lru` and `dumpsys media_session` output captured from the stick into `tests/fixtures/lru.txt` and `tests/fixtures/media_session.txt`; Step 1a captures them with `adb shell dumpsys …`.
  - `LruParserTest.testParsesFixture` checks the count > 5, and that the Wolf entry has adj `home` and pkg `com.wolf.firelauncher`.
  - `KillPolicyTest.keepsPlayingMediaAndHome` — procs for spotify (`cch`, playing), wolf (`home`), vlc (`cch`), kodi (`fg TOP`, fg). Expected: candidates == `[org.videolan.vlc]`.
  - `KillPolicyTest.respectsWhitelistAndSelf` — whitelisted pkg and `com.clean.stickman` are excluded.
  - `MediaStateTest.testPlaying` — fixture lines with `state=3` give that pkg; `state=2` (paused) gives nothing.
- [ ] **Step 2:** Run `tests/run.sh`. Expected: FAIL.
- [ ] **Step 3:** Implement and register in `Stickd`.
- [ ] **Step 4:** Run `tests/run.sh` and expect `PASS`. On the device, with stickd running from `/data/local/tmp/s.apk`:
  1. Open VLC, then go Home.
  2. `echo boost > act.fifo`.
  3. `logcat -s Stickd` shows `STATUS boost freed_mb=… killed=…`.
  4. VLC is no longer in `dumpsys activity lru`.
  5. Wolf is still running, and the foreground app is unchanged.
- [ ] **Step 5:** Commit: `"stickd: auto-kill + boost with media/home/foreground protection"`.

### Task 7: Sleep timer, fade, stay-awake, DNS guard (stickd + fade overlay)

**Files:**
- Create: `src/com/clean/stickman/core/SleepTimer.java`, `core/DnsGuard.java`, `src/com/clean/stickman/daemon/Sleep.java`, `src/com/clean/stickman/FadeReceiver.java`, `src/com/clean/stickman/FadeOverlay.java`
- Modify: `AndroidManifest.xml` (exported receiver `.FadeReceiver` for actions `com.clean.stickman.FADE_START` / `FADE_END`)
- Test: `tests/java/SleepTimerTest.java`, `tests/java/DnsGuardTest.java`

**Interfaces:**
- Consumes: the `Stickd` handler map, `MediaState` (Task 6) and `StickdConf.stayAwakeMedia` (Task 5).
- Produces:
  - `SleepTimer`:
    - `int next(int currentMin)` cycles `0→15→30→60→90→0`;
    - `long fadeStartAt(long endAtMs)` returns `endAtMs - 60_000`.
  - `DnsGuard.decide(boolean[] probes) -> boolean revert`, given probe results at +20 s and +60 s. It reverts if both failed.
  - The DNS probe command is `ping -c 1 -W 3 1.1.1.1 && ping -c 1 -W 3 dns.google`: the IP is reachable and the name resolves.
  - Handlers:
    - **`sleep_timer`** cycles the timer; **`sleep_timer_set <min>`** sets it directly.
      - At fade start: `am broadcast -n com.clean.stickman/.FadeReceiver -a com.clean.stickman.FADE_START --ei seconds 60`.
      - At the end: `cmd media_session dispatch pause`, `input keyevent KEYCODE_SLEEP`, then `FADE_END`.
      - Logs `STATUS sleep_timer <min|off>`.
    - **`dns_check`** runs the guard. On revert it runs `settings put global private_dns_mode off` and logs `STATUS dns reverted`; otherwise `STATUS dns ok`.
  - **Stay-awake:** every 30 s, if `stayAwakeMedia` is on and `MediaState` shows playing, run `svc power stayon true`. Restore `svc power stayon false` when playback stops (if Stickman turned it on).
  - **`FadeOverlay`:** a `TYPE_APPLICATION_OVERLAY` black, non-focusable, non-touchable view whose alpha animates 0 → 0.85 over `seconds`. `FADE_END` or any key press removes it. Stickman holds `SYSTEM_ALERT_WINDOW` through the appop the ensure step grants (Task 9).

- [ ] **Step 1:** Write the tests:
  - `SleepTimerTest.testCycle` — 0→15, 90→0, and an unknown value goes to 15.
  - `SleepTimerTest.testFadeStart` — `fadeStartAt(1_000_000)` is 940000.
  - `DnsGuardTest.revertsOnlyWhenBothFail` — `{false,false}` is true; `{false,true}` and `{true,true}` are false.
- [ ] **Step 2:** Run `tests/run.sh`. Expected: FAIL.
- [ ] **Step 3:** Implement and register.
- [ ] **Step 4:** Run `tests/run.sh` and expect `PASS`. On the device:
  1. Install the build.
  2. `echo "sleep_timer_set 2" > act.fifo` makes the fade begin after about 60 s (verify with a screencap mid-fade showing a darkened frame). Cancel with `sleep_timer_set 0` before sleep, unless the owner agreed to let it sleep.
  3. `settings put global private_dns_mode hostname; settings put global private_dns_specifier invalid.example; echo dns_check > act.fifo` gives `STATUS dns reverted` within about 60 s, and `private_dns_mode` reads `off`.
- [ ] **Step 5:** Commit: `"stickd: sleep timer with fade overlay, stay-awake, DNS guard"`.

### Task 8: System commands, originals journal, catalogues

**Files:**
- Create: `src/com/clean/stickman/core/Commands.java`, `core/Originals.java`, `core/Catalog.java`
- Copy into core data: hidden-screen actions from `docs/device/amazon-settings-actions.txt`
- Test: `tests/java/CommandsTest.java`, `tests/java/OriginalsTest.java`

**Interfaces:**
- Produces — all of these are pure functions returning shell command strings:
  - `Originals.setting(String ns, String key, String value) -> String` records the current value into `/data/local/tmp/stickman/originals` (line `setting <ns> <key> <old>`) only if that key isn't recorded yet, then runs `settings put`.
  - `Originals.disablePkg(String pkg)` records `pkg <pkg>`, then runs `pm disable-user --user 0`.
  - `Originals.bucket(String pkg, String bucket)` records `bucket <pkg> <old>`, then runs `am set-standby-bucket`.
  - `Originals.restoreScript() -> String` replays the file in reverse. For an old value `null` it runs `settings delete`; for `pkg` lines, `pm enable`; then removes the file.
  - `Commands`:
    - `screensaver(boolean on)`
    - `sleepTimeout(long ms)` (secure `sleep_timeout`), `screenOffTimeout(long ms)` (system)
    - `animation(float scale)` (the three global scales)
    - `privateDns(String host)` (`null` → mode `off`; otherwise `hostname` + specifier, followed by `echo dns_check > act.fifo`)
    - `privacy(boolean limit)`: `amz_limit_ad_tracking`, `limit_ad_tracking`, `advertisingIdApp:interestBasedAds:value`, `USAGE_METRICS_UPLOAD_ENABLED` (secure); `com.amazon.tv.settings.featured_item_video_enabled` (global)
    - `keepAsleep(String pkg, boolean on)` (bucket `rare` + appop `RUN_ANY_IN_BACKGROUND ignore`; off = restore)
    - `debloat(String pkg, boolean disable)` (refuses, returning `null`, for anything on `Catalog.NEVER_DISABLE` or with the `com.amazon.tv.settings` prefix)
    - `setTime(long epochMs)` (`cmd alarm set-time` + `ntp_server time.google.com` + `auto_time 1`)
    - `clearDeadProxy()` (reads `http_proxy`; if a port is set and `/proc/net/tcp` + `tcp6` show no LISTEN (`0A`) on it, sets `:0`; prints `PROXY kept|cleared|none`)
    - `act(String id, String arg)` (`echo '<id> <arg>' > act.fifo`)
  - `Catalog`:
    - `NEVER_DISABLE` (`Set<String>` from Global Constraints)
    - `DEBLOAT_RECOMMENDED` (`String[][]` of `{pkg, label, why}`). The candidates are `com.amazon.tv.alexaalerts`, `com.amazon.tv.releasenotes`, `com.amazon.ods.kindleconnect`, `com.amazon.ags.app`, `com.amazon.alexashopping`, `com.amazon.tv.csapp`. The UI only shows entries that are installed and enabled.
    - `SHORTCUTS` (`String[][]` of `{label, action}`): Display & Sounds, Network, Applications, My Fire TV, Remotes & Bluetooth, Developer options (`com.amazon.device.settings.action.APPLICATION_DEVELOPMENT_SETTINGS`), Inputs (`com.amazon.tv.inputpreference.action.LAUNCH_INPUTS`), Quick settings (`com.amazon.tv.quicksettings.ui.action.SETTING_ID_QS`), Profiles (`com.amazon.tv.action.LAUNCH_PROFILE_PICKER`), Ambient / screensaver (`com.amazon.device.settings.action.SCREENSAVERS`), AV sync (`com.amazon.device.settings.action.AVSYNC_TUNING_ACTION`), Notifications (`com.amazon.tv.action.NOTIFICATION_CENTER`), Troubleshooting. Exact actions are taken from the probe file.
    - `DNS_PRESETS` (`{label, host}`): AdGuard `dns.adguard-dns.com`; AdGuard Family `family.adguard-dns.com`; Cloudflare Security `security.cloudflare-dns.com`; Cloudflare Family `family.cloudflare-dns.com`; NextDNS (custom ID → `<id>.dns.nextdns.io`); Off.

- [ ] **Step 1:** Write the tests:
  - `OriginalsTest.recordsOnce` — the command contains `grep -q '^setting secure screensaver_enabled '` before appending, and ends with `settings put secure screensaver_enabled 0`.
  - `OriginalsTest.restoreReplaysReverse` — a sample originals text `"setting secure a 1\npkg com.x\nsetting global b null"` gives a script ordered `settings delete global b`, `pm enable com.x`, `settings put secure a 1`.
  - `CommandsTest.debloatRefusesCritical` — `debloat("com.amazon.tv.launcher", true)` and `debloat("com.amazon.tv.settings.v2", true)` both return `null`.
  - `CommandsTest.privateDnsOffAndOn` — `privateDns(null)` contains `private_dns_mode off`; `privateDns("dns.adguard-dns.com")` contains `hostname`, the specifier and `dns_check`.
  - `CommandsTest.clearDeadProxyKeepsListening` — the command checks port `8888` via `/proc/net/tcp` with state `0A`.
- [ ] **Step 2:** Run `tests/run.sh`. Expected: FAIL.
- [ ] **Step 3:** Implement. Verify each `SHORTCUTS` action against `docs/device/amazon-settings-actions.txt`. On the device, check each `DEBLOAT_RECOMMENDED` pkg exists with `pm list packages <pkg>`; drop any that are absent. **Do not disable any of them in testing.**
- [ ] **Step 4:** Run `tests/run.sh` and expect `PASS`. On the device:
  - `Commands.clearDeadProxy()` prints `PROXY kept` (Net Filter is listening on 8888).
  - Run `Commands.screensaver(false)` then the restore script; `settings get secure screensaver_enabled` returns to `1`.
- [ ] **Step 5:** Commit: `"core: system commands, originals journal, catalogues"`.

### Task 9: App shell, ensure v2 + migration, Status and Settings pages

**Files:**
- Rewrite: `src/com/clean/stickman/MainActivity.java`. A left sidebar (Remote, Speed, Sleep, Privacy, Debloat, Shortcuts, Status, Settings) and a content area that swaps `Page` views.
- Create: `src/com/clean/stickman/Page.java` (`abstract class Page { abstract View build(MainActivity a); void onShow(){} void onHide(){} }`), `Theme.java`, `StatusPage.java`, `SettingsPage.java`, `Ensure.java`, `Store.java`
- Modify: `BootJob.java`, `BootReceiver.java` (use `Ensure`; skip when master off); `RemapConfig.java` (delete it; its role moves to `Ensure` + `Store`)
- Test: `tests/java/EnsureScriptTest.java` (pure string checks via `core/EnsureScript.java`)

**Interfaces:**
- Consumes: `Bridge` (Task 2), `RemapModel` (Task 3), the stickd launch line (Task 5), `Commands`/`Originals`/`Catalog` (Task 8).
- Produces:
  - `Theme`:
    - constants `BG=#14110E`, `PANEL=#1F1A15`, `WOOD=#C68A4E`, `ACCENT=#FF9A3C`, `TEXT=#F3EDE6`, `MUTED=#A39A90`
    - `static TextView title(Context,String)`, `static Button button(Context,String)` (focus state draws an `ACCENT` outline), `static View card(Context)`
  - `Store`:
    - `RemapModel remap(Context)` and `void saveRemap(Context, RemapModel)` (persisted in app files as `remap.conf` text)
    - `StickdConf`-text getters/setters
    - `boolean enabled` / `setEnabled`
  - `core/EnsureScript.build(String apkPath, String libPath, int version, String remapConf, String stickdConf, boolean migrate) -> String` is the idempotent script. It:
    1. runs `mkdir -p /data/local/tmp/stickman`;
    2. installs `remapd` from `libPath` when `cmp` differs;
    3. writes both confs via `base64 -d`;
    4. runs `pm grant com.clean.stickman android.permission.READ_LOGS` and `appops set com.clean.stickman SYSTEM_ALERT_WINDOW allow`;
    5. if `migrate`: kills `/data/local/tmp/remap/remapd.pid` and `wolfd`, runs `pm uninstall com.clean.keyshift`, then `rm -rf /data/local/tmp/remap`;
    6. runs `am force-stop com.clean.homeswitch`;
    7. if the `version` file differs from `version`, kills the running remapd/stickd;
    8. starts any daemon not alive (checking `/proc/<pid>/cmdline` contains `remapd` / `stickd`);
    9. writes `version`;
    10. prints `REMAPD <pid> STICKD <pid>`.
  - `EnsureScript.stop()` kills both daemons and prints `STOPPED`.
  - `Ensure.cmd(Context)` fills `EnsureScript.build` from `ApplicationInfo.sourceDir`, `nativeLibraryDir`, `BuildConfig`-style versionCode (read from `PackageInfo`) and `Store`. `migrate` is true when `pm list packages com.clean.keyshift` is present, or always: the script guards with `[ -e ]`/`pm path`.
  - **First-run import:** if the app has no stored remap yet, the worker first runs `cat /data/local/tmp/remap/remap.conf /data/local/tmp/stickman/remap.conf 2>/dev/null` and imports the v1/v2 rules (keeps KeyShift's Netflix→Kodi). If nothing was found, it seeds default tap 172 = `start com.wolf.firelauncher/.screens.launcher.LauncherActivity` when Wolf is installed.
  - **Status page:**
    - shows the live `STATUS` lines and daemon health (`REMAPD/STICKD` pids from the last ensure);
    - shows the output of one bridge command, `grep -E 'MemTotal|MemAvailable' /proc/meminfo; dumpsys thermalservice | grep -m3 -i 'mValue'; df -h /data | tail -1; dumpsys wifi | grep -m1 'RSSI'; getprop ro.build.version.name`, parsed into cards for RAM, CPU temperature, storage, Wi-Fi and Fire OS;
    - has a **Refresh** button.
  - **Settings page:**
    - Master on/off (off → `EnsureScript.stop()` and `Store.setEnabled(false)`)
    - **Restore defaults** (confirm dialog → `Originals.restoreScript()`)
    - double-press window (200/250/300/400 ms) and hold time (500/600/800 ms), saved into `RemapModel` and applied
    - Fix clock: fetch `Date` from `https://www.google.com` via `HttpURLConnection` HEAD → `Commands.setTime`
    - Clear stuck proxy
    - Show the last bridge result
  - **VPN guard UI:** if `Bridge.otherVpnActive`, every user-initiated apply first shows "A VPN (e.g. NordVPN) is connected. Applying will disconnect it. Continue?".

- [ ] **Step 1:** Write `EnsureScriptTest`:
  - `testIdempotentPieces` — contains `mkdir -p /data/local/tmp/stickman`, `cmp -s`, `base64 -d > remap.conf`, `--nice-name=stickd`, `appops set com.clean.stickman SYSTEM_ALERT_WINDOW allow`.
  - `testMigrateBlock` — `migrate=true` contains `pm uninstall com.clean.keyshift`; `false` doesn't.
  - `testStop` — kills via both pid files and prints `STOPPED`.
- [ ] **Step 2:** Run `tests/run.sh`. Expected: FAIL.
- [ ] **Step 3:** Implement the classes. The sidebar is a vertical list of focusable `Theme.button`s. D-pad Right enters the content area and Left returns. The first launch selects **Remote**.
- [ ] **Step 4:** Run `tests/run.sh` and expect `PASS`. Run `./build.sh` and expect `DONE`. Device verification is done in Task 13.
- [ ] **Step 5:** Commit: `"app shell, ensure v2 with KeyShift migration, status + settings pages"`.

### Task 10: Remote drawing, focus navigation, listen mode

**Files:**
- Create: `src/com/clean/stickman/core/RemoteLayout.java` (pure geometry), `src/com/clean/stickman/RemoteView.java` (custom `View`), `src/com/clean/stickman/RemotePage.java`, `src/com/clean/stickman/ButtonEditor.java`
- Modify: `LogTail.java` (expose `lastKey`/`lastKeyAt` and also the `Stickd` STATUS lines)
- Test: `tests/java/RemoteLayoutTest.java`

**Interfaces:**
- Consumes: `RemapModel`, `Rule.Layer`, `Keys`, `Actions` (Task 3); `Store` and `Ensure` (Task 9); `Bridge` (Task 2).
- Produces:
  - `RemoteLayout`:
    - `static final Btn[] BUTTONS`, where `Btn { int code; String label; float cx, cy, r; boolean round; }` is in a 0..1 × 0..1 unit space of a tall remote body. The arrangement, top to bottom, matches the Alexa Voice Remote Enhanced:
      1. Power (top left) | Alexa (top centre)
      2. D-pad ring: Up/Down/Left/Right around OK
      3. Back | Home | Menu
      4. Rewind | Play/Pause | Fast-forward
      5. Vol+ / Vol− column (left) | Mute | Ch+ / Ch− column (right)
      6. Recents | Live TV | Settings
      7. App buttons 2×2: Prime Video, Netflix / Amazon Music, Apps
    - `static int next(int fromCode, int dir)` (`dir` = 0 up, 1 down, 2 left, 3 right). It moves to the nearest button whose centre lies in that direction, scored by distance + 2 × the off-axis offset. It returns `fromCode` if nothing is there.
  - `RemoteView`:
    - draws the body (rounded rect, `PANEL` fill, `WOOD` rim) and each button;
    - shows a small badge dot if the key has rules in the current profile, plus a hint label under the focused button (`Actions.describe` of its tap layer);
    - handles D-pad focus via `RemoteLayout.next`, and OK calls `onPick(code)`;
    - `void flash(int code)` animates a highlight pulse (listen mode).
  - `RemotePage`:
    - a profile switcher row: `◀ Default ▶`, plus "Add app profile" (app picker) and "Remove profile";
    - the `RemoteView`;
    - a **Listen** toggle button. While listening, the next `LogTail` `KEY <code>` calls `flash(code)` and then opens `ButtonEditor` for it.
  - `ButtonEditor`, a dialog:
    - three rows: Tap / Double / Hold. Hold is hidden for `Keys.tapOnly`; Double has the note "adds a short delay to this button".
    - Each row opens an action chooser: Original, Do nothing, Open an app…, Act as another button…, a Stickman action… (`Actions` list with friendly names; `open_screen` → submenu from `Catalog.SHORTCUTS`).
    - Saving calls `Store.saveRemap`, then `Bridge.run(…, Ensure.cmd(…))`.

- [ ] **Step 1:** Write the tests:
  - `testAllRemoteKeysPresent` — the `BUTTONS` codes are exactly `Keys.ALL`.
  - `testNoOverlap` — no two button circles overlap.
  - `testDpadNeighbours` — `next(96 OK, up)` is 103; `next(103, down)` is 96; `next(96, left)` is 105.
  - `testAppGrid` — `next(745 Prime, right)` is 744 (Netflix); `next(745, down)` is 747 (Amazon Music).
  - `testEdgeStays` — `next(116 Power, up)` is 116.
- [ ] **Step 2:** Run `tests/run.sh`. Expected: FAIL.
- [ ] **Step 3:** Implement. The remote is centred and scaled to the content height; buttons use `Theme` colours, the focused button gets the `ACCENT` ring, and app buttons are labelled with their app names.
- [ ] **Step 4:** Run `tests/run.sh` and expect `PASS`. Build, install, `am start` the app, `screencap` the Remote page, and check the image shows every button with no overlaps.
- [ ] **Step 5:** Commit: `"remote page: drawn remote, spatial focus, listen mode, layer editor"`.

### Task 11: Speed, Sleep, Privacy, Debloat, Shortcuts pages

**Files:**
- Create: `src/com/clean/stickman/SpeedPage.java`, `SleepPage.java`, `PrivacyPage.java`, `DebloatPage.java`, `ShortcutsPage.java`

**Interfaces:**
- Consumes: `Commands`, `Originals`, `Catalog` (Task 8); `Store`/`Ensure`/`Page`/`Theme` (Task 9); `Bridge` (Task 2); `StickdConf` keys (Task 5); action ids (Task 3).
- Produces:
  - **SpeedPage:**
    - **Boost** button (`Commands.act("boost")`, then shows the next `STATUS boost` result);
    - Auto-kill: on leave toggle, interval chooser (off/5/15/30/60), whitelist and blacklist pickers (installed user apps), all written to `stickd.conf` via Ensure then `act reload`;
    - Keep-asleep app picker;
    - Animation speed 1× / 0.5× / Off.
  - **SleepPage:**
    - Screensaver on/off;
    - Sleep timeout (Never/15/30/60 min/2 h) with a note: "Fire OS 8 may override this";
    - Screen-off timeout;
    - Stay awake while media plays;
    - Sleep timer (Off/15/30/60/90 → `act sleep_timer_set`);
    - **Screen off now**.
  - **PrivacyPage:**
    - DNS preset chooser (NextDNS asks for an ID with the TV keyboard), a status card showing the current private DNS (read via bridge `settings get`), and the last `STATUS dns`;
    - "Limit ads & tracking" toggle (`Commands.privacy`);
    - a note: "Net Filter keeps running alongside".
  - **DebloatPage:**
    - Recommended (installed + enabled entries of `Catalog.DEBLOAT_RECOMMENDED`, with their why);
    - Advanced (all other enabled `com.amazon.*` packages from the bridge's `pm list packages -e com.amazon`, minus `NEVER_DISABLE`), behind a warning dialog;
    - a "Disabled by Stickman" list with per-app re-enable;
    - **Restore all** (only `pkg` lines of originals).
  - **ShortcutsPage:** a grid of `Catalog.SHORTCUTS` tiles → `Commands.act("open_screen", action)`, plus a "Screen off" tile.

- [ ] **Step 1:** Implement the pages. Each control applies through one `Bridge.run` per user action and shows the result via `Bridge.Listener` (toast + status line).
- [ ] **Step 2:** Run `./build.sh` and expect `DONE`. Run `tests/run.sh` and expect all `PASS` (no regressions).
- [ ] **Step 3:** On the device, install, then check each over ADB:
  - Speed → animation 0.5× sets `settings get global window_animation_scale` to `0.5`; restore defaults returns it to `1.0`.
  - Privacy → AdGuard sets `private_dns_specifier` to `dns.adguard-dns.com` and logs `STATUS dns ok`; then switch Off.
  - Shortcuts → "Developer options" makes `dumpsys window | grep mCurrentFocus` show `com.amazon.tv.settings.v2`.
  - Debloat → the list renders. **Do not disable anything without the owner's go-ahead.**
- [ ] **Step 4:** Commit: `"speed, sleep, privacy, debloat, shortcuts pages"`.

### Task 12: Tung Tung Tung Sahur icon and banner

**Files:**
- Modify: `art/make_icons.py`. Draw an original Sahur-style character:
  - a tall upright wooden log body (bark texture, wood-grain rings at the top cut, warm browns `#8B5A2B`/`#C68A4E`);
  - big round eyes with dark pupils and a wide grin;
  - stick arms and legs;
  - a wooden bat held diagonally in one hand;
  - on a warm orange-to-deep-brown gradient rounded-square background;
  - the banner shows the character on the left and **"Stickman"** in bold on the right.
- Output: `res/mipmap-*/ic_launcher.png`, `res/drawable-xhdpi/banner.png`, `art/icon-512.png`, `art/banner-640.png`.

**Interfaces:**
- Produces: the same resource names as now (`@mipmap/ic_launcher`, `@drawable/banner`). No code changes.

- [ ] **Step 1:** Rewrite the drawing functions in `art/make_icons.py`, keeping `save()` and the density table.
- [ ] **Step 2:** Run `python3 art/make_icons.py`. Expected: `icons written`. View `art/icon-512.png` and `art/banner-640.png`. The character must be recognisable at 48 px (`res/mipmap-mdpi`), and the banner text must be legible at 320×180.
- [ ] **Step 3:** Commit: `"Stickman icon: Tung Tung Tung Sahur-style character + banner"`.

### Task 13: Device integration, migration, owner remote test, release

**Files:**
- Modify: `README.md` (rename to Stickman, update the features list, the architecture with remapd + stickd + bridge, and install: `adb install stickman.apk` + `appops set com.clean.stickman ACTIVATE_VPN allow`)
- Modify: memory notes are handled by the orchestrator, not this task.

**Interfaces:**
- Consumes: everything above.

- [ ] **Step 1:** Run `./build.sh && tests/run.sh`. Expected: `DONE` and all `PASS`.
- [ ] **Step 2:** On the device:
  1. `adb install stickman.apk`
  2. `appops set com.clean.stickman ACTIVATE_VPN allow`
  3. `input keyevent KEYCODE_WAKEUP`
  4. `am start -n com.clean.stickman/.MainActivity`
  5. Ask the owner to accept the "Allow USB debugging" prompt (key `stickman@firetv`).
- [ ] **Step 3:** Verify the migration over ADB:
  - `pm list packages com.clean.keyshift` is empty;
  - `/data/local/tmp/stickman/remap.conf` contains `744 tap start org.xbmc.kodi/org.xbmc.kodi.Splash` and the Home→Wolf rule;
  - `remapd.pid` and `stickd.pid` are alive;
  - logcat shows `STATUS started`.
- [ ] **Step 4:** Ask the owner to test on the real remote:
  1. Home → Wolf instantly.
  2. Netflix → Kodi.
  3. Set "Rewind double → Boost" via the drawn remote and try it.
  4. Listen mode highlights pressed buttons.
  5. Create a Kodi profile where Fast-forward → Next and confirm it only applies inside Kodi.
  6. D-pad has no lag.
  7. Hold Back 10 s releases the remote.
  
  Fix any failures before continuing.
- [ ] **Step 5:** Check reboot persistence, **only with the owner's go-ahead:**
  - `adb reboot`;
  - within about 60 s of boot both daemons are alive and `STATUS started` is logged;
  - the remote is remapped after its first press.
- [ ] **Step 6:**
  1. Commit the README.
  2. Check the GitHub repo name is still `firetv-remote-remap`.
  3. `git push`.
  4. `gh release create v4.0 stickman.apk --title "Stickman v4.0"`, with notes covering the features and migration.

## Parallelism (for the orchestrator)

| Wave | Tasks | Depends on |
|---|---|---|
| A | 1 | none |
| B | 2, 3, 4, 8, 12 | Wave A; independent of each other |
| C | 5, 6, 7 | 3 and 5 (6 and 7 extend `Stickd`, so run 5 first, then 6 and 7 in parallel) |
| D | 9, then 10 and 11 in parallel | 9 needs 2, 3, 5 and 8; 10 and 11 need 9 |
| E | 13 | everything |
