# Tasks page (task manager): design

*2026-11-05. Adds a Tasks page to Stickman stage 1 (`docs/superpowers/specs/2026-11-04-stickman-design.md`).*

## Intent and the why

The owner wants a task manager for the stick: to free memory so the TV feels faster, and to see which app is hogging it. It should feel like a modern task manager, not a list of process names: live numbers, a clear picture of what takes the memory, and the power to kill, auto-kill and protect apps from the same screen.

Success: open Tasks, see at a glance what's eating RAM, end it with two presses, and trust that the live numbers are real. Pausing the live view with `⏯` makes it easy to read.

## What the owner chose (mockups reviewed on 2026-11-05)

- **Layout C, "memory map":** one tile per running app, sized by RAM, in the app's brand colour with its name and MB. The hog is obvious. System apps are a muted strip along the bottom.
- **Interaction B:** the map uses the full width. OK on a tile opens a themed dialog with details and actions. Back closes it.
- **An "Always kill" action** next to End / Protect / Open.

## Goals

1. **Tasks page** in the sidebar, between Remote and Speed.
   - Header: RAM used / total, CPU %, auto-kill on/off, and a LIVE indicator (green dot + "LIVE · 2 s"), or PAUSED (orange).
   - **Memory map:** your apps as tiles sized by RAM, biggest first. Tiles animate smoothly when numbers change; they never jump around on every tick.
   - **System strip:** non-launchable packages as small muted tiles at the bottom with their RAM. Read-only. Up to 6 shown, then a "+N" tile.
   - A tiny tile is never unreadable: apps below the minimum tile size collapse into a "+N more" tile that opens a plain list dialog.
2. **Live data every 2 s** while the page is visible and not paused. Sampling stops when the page is hidden or paused.
3. **`⏯` pauses / resumes** the live view. Paused freezes the map and the numbers, and stops sampling.
4. **`☰` menu:** Sort by RAM (default) / CPU / Name, End all.
   - Sort by CPU sizes tiles by CPU share; sort by Name gives equal tiles in alphabetical rows.
5. **Tile dialog (OK on a tile):** name, package, state (playing / in front / background / cached), RAM, CPU, running time, process count, a 60 s RAM trend, and buttons **End app · Always kill · Protect · Open · Cancel**.
   - **End app:** `am force-stop`. Disabled on protected apps. The dialog itself is the confirmation.
   - **Always kill:** the app is closed within 10 s whenever it goes to the background, every time, even if the general auto-kill (Speed page) is off. Shows ⚡ on its tile. Confirms once ("Close X every time it goes to the background?").
   - **Protect:** never killed by anything (End, End all, auto-kill, Boost). Shows ★. Toggle, no confirm.
   - Always kill and Protect are exclusive; setting one clears the other.
   - **Open:** launches the app.
   - System-strip tiles open a details-only dialog (no End / Always kill / Protect).
6. **End all:** closes every one of your apps that is not protected, in front, or playing. One confirmation listing how many.
7. **Shares the lists with the Speed page:** Protect = the auto-kill whitelist; Always kill = the auto-kill blacklist. Changing either here updates there and vice versa.
8. **`⏯` and `☰` always reach this page,** even if the owner remapped those buttons globally.

## Non-goals

- Per-process view (the map is per app/package).
- Killing system packages or anything on the never-kill list.
- Historical statistics beyond the last 60 s.
- Any change to the stage-1 auto-kill rules other than "blacklist is enforced even when on-leave auto-kill is off".

## Anti-goals

- **Killing what the user is watching or listening to,** the home launcher, Net Filter, or Stickman: the existing never-kill rules apply to End, End all and Always kill.
- **The sampling itself slowing the stick:** under 3 % CPU on average while live; nothing at all when the page is hidden or paused; sampling must stop by itself if the app dies.
- **A jittery map:** numbers may tick, but tiles must not swap places every 2 s.
- **Numbers that lie:** a frozen page must say PAUSED; stale data must never show a LIVE dot.

## Constraints

- Everything in the stage-1 constraints: framework views only, javac `--release 8`, pure Java in `core/` and `daemon/`, shared dir `/data/local/tmp/stickman/`, actions through the `act/` spool, log tags `Stickd` / `Stickman`, D-pad-only UI, Theme colours, themed dialogs.
- The app cannot read files in the shared dir reliably (SELinux), but it already reads logcat (`READ_LOGS`), and `LogTail` already parses `STATUS` lines. The live feed therefore travels as logcat lines.
- A logcat line must stay under ~4 KB, so the feed is one line per package, not one JSON blob.
- The stick has ~1.7 GB RAM and a slow CPU: per-app PSS from `dumpsys meminfo` takes seconds and is out; RSS from `/proc` is the metric.

## Decisions made

- **Feed (stickd → app, via logcat):**
  - The page sends `tasks_start` on show/resume and `tasks_stop` on hide/pause through the spool. While sampling, the page re-sends `tasks_start` every 60 s as a keepalive; stickd stops sampling 90 s after the last keepalive.
  - Every 2 s stickd logs `STATUS tasks total=<kB> avail=<kB> cpu=<pct> seq=<n>` followed by one `STATUS task <pkg> rss=<kB> cpu=<pct> state=<fg|play|bg|cached|sys> up=<sec> procs=<n> seq=<n>` per package, then `STATUS tasks_end seq=<n>`. The app assembles a snapshot per `seq` and ignores incomplete ones.
- **Metrics:** RSS per process from `/proc/<pid>/statm`, summed per package (process name up to `:`); CPU from `/proc/<pid>/stat` utime+stime deltas over the interval, divided by elapsed × clock ticks × CPU count; total from `/proc/stat` and `/proc/meminfo`. Running time = now − earliest `starttime` among the package's processes. State from stickd's foreground tracking, `MediaState`, and the lru adjacency; `sys` = not launchable. "Your apps" = packages with a launcher/leanback activity (the existing launchable set).
- **Treemap:** squarified layout in pure Java (`core/Treemap`), input (key, weight) → rects in unit space. Re-layout only when the set of apps changes or any weight moves more than 5 %; otherwise only the labels update. Rect changes tween over 400 ms. Focus is remembered by package across re-layouts. D-pad moves to the nearest tile in that direction.
- **Brand colours:** a small `core/Brand` map for well-known packages (Netflix, Prime, YouTube, Kodi, VLC, Spotify, Disney+, wako, Wolf…), otherwise a stable hue derived from the package name within the Stickman palette (warm, mid-saturation), so unknown apps still look intentional.
- **Always kill independent of the global toggle:** stickd's on-leave timer now runs whenever the blacklist is non-empty; when `autokill.on_leave=0` it kills only blacklisted apps. Boost and interval kills are unchanged.
- **Own remote profile:** when the Tasks page first opens, if the default profile remaps Play/Pause (164) or Menu (139), Stickman adds a `com.clean.stickman` profile with those keys set to `orig`, saved and applied through the normal path.
- **Confirms:** End all and Always kill confirm; End app and Protect don't (the dialog is the deliberate step; Protect is harmless).
- **Visual:** Theme colours; tiles with a 3 px ACCENT focus ring; the LIVE dot green with a soft glow, PAUSED orange; dialog via the existing `ThemedDialog`.

## Left to the builder

- Treemap algorithm details, minimum tile size, "+N more" threshold, label fitting rules.
- CPU-count and clock-tick discovery on the device; sampling thread and parsing.
- Exact test strategy: pure-Java tests for `Treemap`, the feed parser, state mapping and the CPU/RSS maths against captured `/proc` fixtures; device verification with the fake-remote harness for `⏯`/`☰` and with real apps for End / Always kill / Protect / End all.

## Builder-check answers (2026-11-05, my calls)

- **`/proc` access:** verified on the stick: `/proc` is `hidepid=invisible,gid=3009` and the shell user is in group 3009; `statm`/`stat`/`cmdline` of app processes are readable (297/300 sampled). `CLK_TCK` = 100, 4 CPUs.
- **Two cadences:** RSS and CPU every 2 s from `/proc`. State inputs that need `dumpsys` (media sessions, lru) refresh every 10 s and are cached; the foreground app comes from stickd's existing instant tracking. A "playing" badge may therefore lag up to 10 s. Playing/foreground guards for kills always use the freshest value available at kill time.
- **Protect blocks everything:** a protected app cannot be ended from the tile dialog (End disabled), is skipped by End all, Boost and auto-kill, and shows as protected in the Speed page pickers (same whitelist).
- **Always-kill keeps the guards:** blacklist-only on-leave kills go through `KillPolicy.candidates` filtered to the blacklist, so an always-kill app that is in front or playing media is spared until it stops; it is force-stopped on the first on-leave sweep after that.
- **Feed parsing:** `task*` lines bypass `LogTail`'s status ring and go to a separate snapshot assembler keyed by `seq`; only the newest complete snapshot is used, replayed lines with older `seq` are dropped.
- **Own profile keys:** 164 → `KEYCODE_MEDIA_PLAY_PAUSE`, 139 → `KEYCODE_MENU`. The `com.clean.stickman` profile is created only if the default profile has any layer (tap/double/hold) on 164 or 139; it sets `tap orig` for those codes, which per the lookup rule overrides all layers for that code inside Stickman. It affects only Stickman (other pages don't use those keys).
- **Never-kill apps as tiles:** Wolf, Net Filter and any other launchable never-kill app appear as tiles marked ★ with End disabled and are excluded from End all counts; Stickman itself is not shown.
- **Ordering:** tiles keep the previous layout's order; new apps are appended; ties break by package name. "+N more" collects apps whose tile would be smaller than the minimum (builder picks the threshold).
