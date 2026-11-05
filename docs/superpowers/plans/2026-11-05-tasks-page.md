# Tasks Page (Task Manager) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A "Tasks" page in Stickman: a live memory-map of running apps (tiles sized by RAM), with End / Always kill / Protect / Open per app, End all, sort, and `⏯` pause.

**Architecture:** stickd gains a sampler that reads `/proc` every 2 s while the page asks for it and logs one `STATUS task …` line per package; the app assembles snapshots from logcat (it already tails `STATUS` lines) and draws a squarified treemap in a custom view. Kill/protect actions reuse the existing spool actions, `KillPolicy` and the Speed page's whitelist/blacklist.

**Tech Stack:** Java 8 (`javac --release 8`), Android framework views (no AndroidX), pure-Java `core/` and `daemon/` with host tests via `tests/run.sh` (harness `tests/java/T.java`), `build.sh` as today.

**Spec:** `docs/superpowers/specs/2026-11-05-tasks-page-design.md` (read it, including "Builder-check answers").

## Global Constraints

- All stage-1 constraints apply (`docs/superpowers/plans/2026-11-04-stickman-stage1.md` → Global Constraints): shared dir `/data/local/tmp/stickman/`, actions via the `act/` spool using `core/Commands.act`, log tags `Stickd` / `Stickman`, `core/` and `daemon/` have no `android.*` imports, D-pad-only UI, Theme colours, `ThemedDialog` for every dialog.
- **Feed lines (exact):**
  - `STATUS tasks total=<kB> avail=<kB> cpu=<pct> seq=<n>`
  - `STATUS task <pkg> rss=<kB> cpu=<pct> state=<fg|play|bg|cached|sys> up=<sec> procs=<n> seq=<n>`
  - `STATUS tasks_end seq=<n>`
  - `seq` starts at 1 per sampling session and increments every sample. `<pct>` is an integer 0–100 (per-app CPU share of all CPUs; may exceed 100/ncpu but never 100).
- **Cadence:** sample every `2000` ms; `dumpsys`-backed inputs (media sessions, lru) refreshed every `10000` ms; sampling stops `90000` ms after the last `tasks_start`; the page re-sends `tasks_start` every `60000` ms.
- **New action ids (add to `core/Actions`):** `tasks_start`, `tasks_stop`, `end <pkg>`, `end_all`. None appear in the button-action menu (`Actions.menu()` must exclude them).
- **Never kill** (End, End all, Always kill): the foreground app, anything in `MediaState.protectedPackages`, `KillPolicy.isNeverKill`, the whitelist, non-launchable packages, `com.clean.stickman`.
- **Treemap:** squarified; re-layout only when the app set changes or any weight moved more than `5 %`; tween `400` ms; minimum tile = `4 %` of the map area, smaller apps collapse into one `+N more` tile; system strip shows at most `6` tiles then `+N`.
- **Keys on the Tasks page:** `KEYCODE_MEDIA_PLAY_PAUSE` toggles pause; `KEYCODE_MENU` opens the sort / End all menu; `DPAD_CENTER` opens the tile dialog; `BACK` closes dialogs, else leaves to the sidebar as on other pages.
- **CPU budget:** the sampler's own CPU share (sum of stickd's processes) must stay under `3 %` averaged over 30 s while live (verified on the device in Task 6).
- **Device:** the stick is at `192.168.0.221:5555`; connect with `su -c 'ip route add 192.168.0.0/24 dev wlan0 scope link src 192.168.0.101'; adb connect 192.168.0.221:5555`. The owner's TV may be off: wake with `input keyevent KEYCODE_WAKEUP`; never reboot, never uninstall, never disable packages. Kills in tests are limited to VLC (`org.videolan.vlc`), which the owner doesn't use.

## Review Focus

1. **The app dies or the page is left while sampling:** stickd must stop by itself 90 s after the last `tasks_start`. (Task 2: `testStopsWithoutKeepalive`.)
2. **A replayed, interleaved or incomplete feed** (logcat `-T` replay, a `task` line without its `tasks_end`, two sessions' `seq` overlapping): the app must show only the newest complete snapshot and never mix two. (Task 1: `testIgnoresIncompleteAndOlderSeq`.)
3. **Odd process tables:** a package with 5 processes (`pkg:remote`, `pkg:sandboxed_process0`), a pid that vanishes between reads, a non-launchable package: RSS sums per package, the vanished pid is skipped, the non-launchable one is `sys`. (Task 1: `testAggregatesPerPackage`, `testVanishedPidSkipped`.)
4. **End on something that must survive:** the foreground app, a playing app, a protected app, a system package: refused with a reason, never force-stopped. (Task 2: `EndPolicyTest`.)
5. **Degenerate maps:** zero apps, one app, all weights zero, 40 apps: no exception, no NaN rects, every rect inside the unit square, tiny ones collapsed into `+N more`. (Task 3: `testDegenerateInputs`.)

---

### Task 1: Feed model, parser and `/proc` maths (pure Java)

**Files:**
- Create: `src/com/clean/stickman/core/TaskSnapshot.java`, `core/TaskFeed.java`, `core/ProcStats.java`
- Modify: `src/com/clean/stickman/core/Actions.java` (constants `TASKS_START = "tasks_start"`, `TASKS_STOP = "tasks_stop"`, `END = "end"`, `END_ALL = "end_all"`; `menu()` excludes them)
- Test: `tests/java/TaskFeedTest.java`, `tests/java/ProcStatsTest.java`, `tests/java/ActionsMenuTest.java` (extend)
- Fixtures: `tests/fixtures/proc/` — captured from the stick in Step 1a (read-only): `stat` (`/proc/stat`), `meminfo`, and for 3 pids `<pid>.stat`, `<pid>.statm`, `<pid>.cmdline`.

**Interfaces:**
- Produces:
  - `final class TaskSnapshot { final long seq, totalKb, availKb; final int cpuPct; final List<App> apps; }` with `static final class App { final String pkg; final long rssKb; final int cpuPct; final String state; final long upSec; final int procs; }`. `apps` is sorted by `rssKb` descending, ties by `pkg`.
  - `final class TaskFeed { void onLine(String statusBody); TaskSnapshot latest(); long latestSeq(); }` where `statusBody` is the text after `STATUS ` (e.g. `task com.x rss=1 cpu=0 state=bg up=5 procs=1 seq=3`). A snapshot becomes `latest()` only when its `tasks_end` arrives and its `seq` is greater than `latestSeq()`; partial or older ones are discarded.
  - `final class ProcStats`:
    - `static long rssKbFromStatm(String statm, int pageKb)` (second field × pageKb)
    - `static long[] cpuFromStat(String stat) -> {utime+stime ticks, starttimeTicks}` (fields 14, 15, 22; the comm field may contain spaces, so parse after the last `)`)
    - `static long totalJiffies(String procStatFirstLine)` (sum of all cpu fields)
    - `static String pkgOf(String cmdline)` (text up to the first `:` or NUL, trimmed; null if empty or not matching `[A-Za-z0-9._]+`)
    - `static int pct(long deltaTicks, long deltaTotalJiffies, int ncpu)` = `round(100 * deltaTicks / deltaTotalJiffies)` clamped 0..100 (`deltaTotalJiffies` already spans all CPUs)
    - `static List<TaskSnapshot.App> aggregate(List<Proc> procs, Map<String,Long> prevTicks, long deltaTotal, int ncpu, long nowTicks, long clkTck, String fgPkg, Set<String> playing, Set<String> cached, Set<String> launchable)` where `Proc { int pid; String pkg; long rssKb; long ticks; long startTicks; }`. State rules: not launchable → `sys`; `pkg == fgPkg` → `fg`; in `playing` → `play`; in `cached` → `cached`; else `bg`. `upSec = (nowTicks − min startTicks) / clkTck`.
    - `static String formatTaskLine(TaskSnapshot.App a, long seq)` producing the exact feed line body (without `STATUS `), and `static String formatTotals(long totalKb, long availKb, int cpuPct, long seq)`, `static String formatEnd(long seq)`.

- [ ] **Step 1a:** Capture fixtures over adb (read-only): `/proc/stat` first line, `/proc/meminfo`, and `stat`/`statm`/`cmdline` for the Wolf launcher pid, a `:`-suffixed child process (e.g. any `*:remote`/`*:sandboxed_process*` from `ps -A -o PID,NAME`), and one Amazon system process.
- [ ] **Step 1b:** Write the tests:
  - `ProcStatsTest.testParsesFixtures` — rss > 0, ticks > 0, `pkgOf("com.wolf.firelauncher")` → same; `pkgOf("com.x:remote")` → `com.x`; `pkgOf("")` → null.
  - `ProcStatsTest.testPct` — `pct(50, 1000, 4)` → 5; clamps to 100; `pct(0, 0, 4)` → 0.
  - `ProcStatsTest.testAggregatesPerPackage` — two procs of `com.x` (rss 100 and 50 kB) → one App rss 150, procs 2, `upSec` from the earlier start.
  - `ProcStatsTest.testVanishedPidSkipped` — a `Proc` with `rssKb < 0` (the daemon's marker for an unreadable pid) is skipped.
  - `ProcStatsTest.testStateRules` — fg / play / cached / bg / sys as specified; `fg` wins over `play`.
  - `TaskFeedTest.testAssemblesSnapshot` — totals, two task lines, end → `latest()` has 2 apps sorted by rss desc.
  - `TaskFeedTest.testIgnoresIncompleteAndOlderSeq` — a `task` line for seq 5 then lines for seq 4 with its end: `latest()` stays null until seq 5's end; after seq 5 completes, a complete seq 3 does not replace it.
  - `TaskFeedTest.testRoundTrip` — `formatTaskLine` output fed to `onLine` reproduces the App.
  - `ActionsMenuTest` — `menu()` contains none of the four new ids.
- [ ] **Step 2:** `tests/run.sh` → FAIL. **Step 3:** implement. **Step 4:** `tests/run.sh` → PASS, `./build.sh` → DONE.
- [ ] **Step 5:** Commit `"core: task feed model/parser and /proc stats"`.

### Task 2: stickd sampler, keepalive, end / end_all, always-kill enforcement

**Files:**
- Create: `src/com/clean/stickman/daemon/TaskSampler.java`, `src/com/clean/stickman/core/EndPolicy.java`
- Modify: `daemon/Stickd.java` (register handlers; expose `fgPkg`), `daemon/AutoKill.java` (on-leave runs when the blacklist is non-empty even if `onLeave` is false, killing only blacklisted candidates in that case), `core/KillPolicy.java` only if a helper is needed
- Test: `tests/java/EndPolicyTest.java`, `tests/java/TaskSamplerTest.java` (pure parts), `tests/java/AutoKillLockTest.java` (extend for blacklist-only)

**Interfaces:**
- Consumes: `ProcStats`, `TaskFeed` formats (Task 1); `Stickd.register`, `addFgListener`, `foreground()`, `conf()`; `Sh.run/log`; `MediaState.protectedPackages`, `LruParser`, `KillPolicy.parseLaunchable/LAUNCHABLE_CMD`, `KillPolicy.candidates`.
- Produces:
  - `EndPolicy.check(String pkg, String fgPkg, Set<String> protectedMedia, Set<String> whitelist, Set<String> launchable) -> String` returning `null` when ending is allowed, else a reason: `"in front"`, `"playing"`, `"protected"`, `"system"`, `"never"` (for `KillPolicy.isNeverKill` or `com.clean.stickman`).
  - `TaskSampler` (daemon):
    - handlers `tasks_start` (starts the sampler thread if idle; records the keepalive time), `tasks_stop` (stops it), `end <pkg>` (runs `EndPolicy.check` with fresh inputs; if null → `am force-stop <pkg>` and logs `STATUS end <pkg> ok`, else `STATUS end <pkg> refused=<reason>`), `end_all` (every launchable app that passes `EndPolicy.check` and isn't `fg`/`play`, force-stopped; logs `STATUS end_all killed=<n>`).
    - Sampling loop: every 2000 ms read `/proc/stat`, `/proc/meminfo`, every numeric `/proc/<pid>/{cmdline,statm,stat}` (skip pids whose files fail to read: `rssKb = −1`), aggregate with `ProcStats.aggregate` (dropping `com.clean.stickman` itself), log the three line kinds. `playing` and `cached` sets and the launchable set are refreshed at most every 10000 ms via `Sh.run("dumpsys media_session")`, `Sh.run("dumpsys activity lru")` and `KillPolicy.LAUNCHABLE_CMD`. `ncpu` from `/proc/cpuinfo` processor lines (fallback 4); `clkTck` = 100; page size from `getconf PAGESIZE` via `Sh.run` once (fallback 4096).
    - Stops itself when `now − lastKeepalive > 90000` ms; logs `STATUS tasks stopped`.
  - `AutoKill`: `onForeground()` schedules the leave timer when `conf.onLeave || !conf.blacklist.isEmpty()`; the fired task calls `autoKill()` when `onLeave`, else `autoKillBlacklistOnly()` which filters candidates to `conf.blacklist` before killing (force-stop, as today for blacklisted).

- [ ] **Step 1:** Tests:
  - `EndPolicyTest` — fg → `"in front"`; playing → `"playing"`; whitelisted → `"protected"`; non-launchable → `"system"`; `com.wolf.firelauncher` → `"never"`; plain background app → null.
  - `TaskSamplerTest.testStopsWithoutKeepalive` — with an injectable clock, `tick()` after 90001 ms since the last keepalive returns `false` (stopped) and a keepalive resets it (extract the decision into a static `shouldStop(long lastKeepaliveMs, long nowMs)`).
  - `TaskSamplerTest.testSeqIncrements` — three samples → seq 1, 2, 3 in the formatted lines (use a fake `/proc` reader interface).
  - `AutoKillLockTest.testBlacklistOnlyRunsWithoutOnLeave` — `onLeave=false`, blacklist `{com.x}` → the leave timer is scheduled; with an empty blacklist it is not.
- [ ] **Step 2:** `tests/run.sh` → FAIL. **Step 3:** implement; register in `Stickd.start()`. **Step 4:** `tests/run.sh` → PASS; `./build.sh`.
- [ ] **Step 5 (device):** push the APK to `/data/local/tmp/s.apk`, launch a second stickd is NOT possible (single-instance) — instead `adb install -r stickman.apk` (MY_PACKAGE_REPLACED restarts both daemons on the new code), then: `tasks_start` via `Commands.act` → within 3 s logcat shows `STATUS tasks …`, ≥ 5 `STATUS task …` lines and `STATUS tasks_end`; wait 95 s with no keepalive → `STATUS tasks stopped`; open VLC (`am start -n org.videolan.vlc/.StartActivity`), go Home, `end org.videolan.vlc` → `STATUS end org.videolan.vlc ok` and VLC gone from `dumpsys activity lru`; `end com.wolf.firelauncher` → `refused=never`. Measure CPU: while sampling, `top -n 3 -d 10 | grep stickd` average `%CPU` < 3.
- [ ] **Step 6:** Commit `"stickd: task sampler feed, end/end_all, always-kill without global auto-kill"`.

### Task 3: Treemap, spatial navigation, brand colours (pure Java)

**Files:**
- Create: `src/com/clean/stickman/core/Treemap.java`, `core/TileNav.java`, `core/Brand.java`
- Test: `tests/java/TreemapTest.java`, `tests/java/TileNavTest.java`, `tests/java/BrandTest.java`

**Interfaces:**
- Produces:
  - `Treemap.layout(List<String> keys, List<Double> weights, double x, double y, double w, double h) -> List<Rect>` (squarified; `Rect { String key; double x, y, w, h; }`), input order preserved as the placement order; weights ≤ 0 get a tiny epsilon.
  - `Treemap.collapse(List<String> keys, List<Double> weights, double minFraction) -> Collapsed { List<String> keys; List<Double> weights; List<String> hidden; }` which replaces every entry whose share `< minFraction` with one `"+N more"` key (key string `MORE`), weight = their sum; no collapse when ≤ 1 entry would be hidden.
  - `Treemap.needsRelayout(List<Rect> prev, List<String> keys, List<Double> weights, double threshold)` → true when the key set differs or any weight's share moved more than `threshold` (0.05).
  - `TileNav.next(List<Rect> rects, int fromIndex, int dir) -> int` (dir 0 up, 1 down, 2 left, 3 right): nearest rect whose centre lies in that direction (same scoring idea as `RemoteLayout.next`: distance + 2 × off-axis), `fromIndex` if none.
  - `Brand.color(String pkg) -> int` ARGB: known map (Netflix `#E50914`, Prime Video `#00A8E1`, YouTube `#FF0000`, Kodi `#17B2E7`, VLC `#F58B00`, Spotify `#1DB954`, Disney+ `#113CCF`, wako `#7E57C2`, Wolf `#6C5CE7`, Plex `#E5A00D`), else a hue from `pkg.hashCode()` in the warm Stickman range with fixed saturation/lightness; `Brand.ink(int bg) -> int` returns `Theme`-compatible white or near-black for contrast.

- [ ] **Step 1:** Tests:
  - `TreemapTest.testCoversAndNoOverlap` — 6 weights → rects within the unit square, total area ≈ 1 (±1e-6), pairwise non-overlapping, areas proportional to weights (±1e-6).
  - `TreemapTest.testDegenerateInputs` — empty → empty; one → full rect; all zeros → equal rects; 40 apps → no NaN, all inside.
  - `TreemapTest.testCollapse` — shares `[0.5, 0.3, 0.1, 0.05, 0.03, 0.02]` with `minFraction 0.04` → keys `[a,b,c,d,MORE]`, hidden `[e,f]`; shares `[0.9, 0.1]` → unchanged.
  - `TreemapTest.testNeedsRelayout` — same keys, share moved 4 % → false; 6 % → true; key added → true.
  - `TileNavTest.testDirections` — a 2×2 grid: right/left/up/down neighbours and edges.
  - `BrandTest.testKnownAndStable` — Netflix colour exact; unknown package gives the same colour twice; `ink` contrast.
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS. **Step 5:** Commit `"core: squarified treemap, tile navigation, brand colours"`.

### Task 4: `TaskMapView` (drawing + focus + tween) and feed plumbing in the app

**Files:**
- Create: `src/com/clean/stickman/TaskMapView.java`
- Modify: `src/com/clean/stickman/LogTail.java` (route `STATUS task…`/`tasks…`/`tasks_end…`/`end…` bodies to a static `TaskFeed FEED` instead of the status ring; keep other kinds unchanged), `core/TaskFeed.java` only if a listener hook is needed (`setListener(Runnable)` called on each completed snapshot)

**Interfaces:**
- Consumes: `Treemap`, `TileNav`, `Brand` (Task 3), `TaskSnapshot`, `TaskFeed` (Task 1), `Theme`.
- Produces: `TaskMapView extends View`:
  - `void setSnapshot(TaskSnapshot s, Sort sort)` with `enum Sort { RAM, CPU, NAME }`; keeps system apps (`state == sys`) out of the map (they are exposed via `List<App> systemApps()` for the strip); computes collapse + layout with hysteresis; starts a 400 ms tween from the previous rects (matched by key); new keys fade in.
  - `void setPaused(boolean)` (dims the LIVE badge drawing is the page's job; the view only stops tweening).
  - `void setMarks(Set<String> protectedPkgs, Set<String> alwaysKill)` → ★ / ⚡ badges. The page passes `whitelist ∪ {pkgs where KillPolicy.isNeverKill}` as protected, so Wolf and Net Filter show ★ with End disabled.
  - focus: `int focusedIndex()`, `String focusedKey()` (`MORE` for the collapsed tile), `boolean move(int dir)` (false at an edge so the Activity can move to the sidebar), focus remembered by key across re-layouts; draws the ACCENT focus ring (3 px) and keeps `isFocusable`.
  - `OnPick onPick` interface `{ void pick(String key); }` fired on DPAD_CENTER key-up (same pattern as `RemoteView`).
  - Tile content: name (app label passed via `setLabels(Map<String,String>)`), MB (`"312 MB"`, `"1.2 GB"` ≥ 1024 MB), state pill for `fg`/`play`, badges; text sizes scale with tile size, labels ellipsized, nothing drawn when the tile is < 48 dp on a side except the colour.

- [ ] **Step 1:** Add a `tests/java/MbTextTest.java` for a static `TaskMapView`-free helper `core/Units.mb(long kb)` (`"312 MB"`, `"1.2 GB"`, `"0 MB"`); FAIL → implement → PASS.
- [ ] **Step 2:** Implement the view and the `LogTail` routing (`LogTail` must not add `task*` lines to `status`/`lastStatus`; `statusAt("tasks")` may still be recorded so the page can show staleness).
- [ ] **Step 3:** `./build.sh` → DONE; `tests/run.sh` → PASS. Render a preview of the view's layout logic via `tools/` if cheap (optional).
- [ ] **Step 4:** Commit `"app: TaskMapView with tween/focus/badges; feed routed from logcat"`.

### Task 5: `TasksPage` — header, strip, keys, dialogs, menu, lists, own profile

**Files:**
- Create: `src/com/clean/stickman/TasksPage.java`, `src/com/clean/stickman/TaskDialog.java`
- Modify: `MainActivity.java` (`SECTIONS` gets `"Tasks"` after `"Remote"`; page factory; key routing: `KEYCODE_MEDIA_PLAY_PAUSE` and `KEYCODE_MENU` reach `Page.onKey` of the current page before any other handling), `Store.java` (helpers to read/update whitelist/blacklist in the stickd conf text: `addToList(ctx, "whitelist"|"blacklist", pkg)`, `removeFromList`), `SpeedPage.java` (no behaviour change; it already reads the same lists)
- Test: `tests/java/OwnProfileTest.java` for a pure helper `core/OwnProfile.ensure(RemapModel m) -> boolean` (adds the `com.clean.stickman` profile with `tap orig` for 164 and/or 139 when the default profile has any layer on them; returns true if it changed the model)

**Interfaces:**
- Consumes: `TaskMapView` (Task 4), `TaskFeed.latest()`, `ApplyPage`/`Bridge`/`Commands.act`, `ThemedDialog.confirm/message` + a list dialog (add `ThemedDialog.list(Context, title, String[] items, IntConsumer)` if missing), `Store`, `StickdConf`, `EndPolicy` reasons (shown verbatim from `STATUS end … refused=`), `LogTail.status("end")`, `status("end_all")`, `Ensure.MARKER` apply path.
- Produces `TasksPage extends ApplyPage` (or `Page` using the apply helpers), behaviour:
  - **Header:** `RAM 1.1 / 1.7 GB` (from `totalKb`/`availKb`), `CPU 23 %`, `Auto-kill On/Off` (from `StickdConf.onLeave || intervalMin > 0`), LIVE dot (green, pulsing via the clock, not ValueAnimator) with `LIVE · 2 s`, or `PAUSED` in ACCENT; if no snapshot for > 6 s while live, show `waiting…` and no green dot.
  - **Map:** `TaskMapView` with `setSnapshot` on every new snapshot (unless paused); labels from `PackageManager` cached per package.
  - **System strip:** up to 6 muted tiles + `+N` (plain `Theme.card`s in a row, focusable; OK shows a details-only dialog; `+N` shows a list).
  - **`⏯`:** toggle paused; sends `tasks_stop` / `tasks_start`.
  - **`☰`:** `ThemedDialog.list` with `Sort by RAM`, `Sort by CPU`, `Sort by name`, `End all`.
  - **Keepalive:** `tasks_start` on show and every 60 s while live; `tasks_stop` on hide.
  - **Tile dialog (`TaskDialog`):** title = label, subtitle `pkg · state · running 42 min`, RAM / CPU / processes, a 60 s sparkline from the last 30 snapshots (kept in the page), buttons `End app` (default focus; disabled + greyed when protected or `EndPolicy`-style local check says `in front`/`playing`/`system`), `Always kill` / `Stop always killing`, `Protect` / `Unprotect`, `Open`, `Cancel`. End → `Commands.act("end", pkg)`; result from `LogTail.status("end")` (toast `Ended Netflix` / `Can't end: playing`). Always kill → confirm `"Close <label> every time it goes to the background?"` → blacklist add (+ whitelist remove) → `applyStickdConf`. Protect → whitelist add (+ blacklist remove) → `applyStickdConf`. Open → `startActivity(getLaunchIntentForPackage)`.
  - **End all:** confirm `"Close <n> apps? Protected, playing and the app in front stay."` → `Commands.act("end_all", null)`; toast from `STATUS end_all`.
  - **Own profile:** on first `onShow`, `OwnProfile.ensure(Store.remap(a))`; if true, `Store.saveRemap` + run `Ensure.MARKER`.
  - **Focus rules:** RIGHT from the sidebar lands on the map (or the strip if the map is empty); DOWN from the map goes to the strip, UP returns; LEFT at the map's left edge goes to the sidebar (reuse the `RemoteView` pattern in `MainActivity.dispatchKeyEvent`: `canMove`).

- [ ] **Step 1:** `OwnProfileTest` — default has `164 double act boost` → profile added with `164 tap orig` only; default has nothing on 164/139 → unchanged, returns false; profile already present → unchanged. FAIL → implement → PASS.
- [ ] **Step 2:** Implement page, dialog, `MainActivity` wiring, `Store` list helpers.
- [ ] **Step 3:** `./build.sh` → DONE; `tests/run.sh` → PASS.
- [ ] **Step 4 (device, owner's TV may be off; wake it):** install (`adb install -r`), open Stickman, navigate to Tasks with injected keys; screenshots to `.superpowers/sdd/<ws>/task-5-shots/`: the header shows live numbers and the green dot; tiles appear with labels and MB; D-pad moves focus between tiles; `KEYCODE_MEDIA_PLAY_PAUSE` → PAUSED and `STATUS tasks stopped` within ~95 s is NOT required (stop is immediate via `tasks_stop`); `KEYCODE_MENU` → sort menu; OK on the VLC tile (open VLC first, then come back) → dialog; End app → VLC ends and toast; `Protect` on VLC → ★ appears and `stickd.conf` whitelist contains it; `Unprotect` reverts (leave the conf as found). Do not run End all on the owner's apps; confirm its dialog text and cancel.
- [ ] **Step 5:** Commit `"Tasks page: memory map, live header, dialogs, end all, protect/always kill, own profile"`.

### Task 6: Harness scenario, CPU budget, README, release notes

**Files:**
- Create: `tools/scenarios/e-tasks-keys.txt` (fakeremote: tap 164 → page pauses; tap 164 → resumes; tap 139 → menu; expectations on logcat `STATUS tasks stopped`/`STATUS tasks …` resuming and the app's own log lines `Stickman: tasks paused|live|menu`)
- Modify: `README.md` (Tasks page in Features; feed line format under "How it works"; new action ids), `TasksPage.java` (log `Stickman: tasks paused|live|menu` so the scenario can assert)

- [ ] **Step 1:** With the Tasks page open on the stick, run `tools/devtest.sh tools/scenarios/e-tasks-keys.txt` → `RESULT PASS`. (The profile created in Task 5 must make 164/139 reach the app even though Rewind/others are remapped; if 164 is remapped in the owner's default profile, this is the real test of the own-profile rule.)
- [ ] **Step 2:** CPU budget: with the page live for 60 s, `adb shell top -b -n 6 -d 10 | grep -E "stickd|remapd"` → stickd average `%CPU` < 3 (record the numbers in the report).
- [ ] **Step 3:** README update; `./build.sh`; commit `"Tasks page: key scenario, CPU budget check, README"`.
