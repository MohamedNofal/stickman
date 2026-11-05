# Stickman (com.clean.stickman) — D-pad UI self-test bug catalogue

Date: 2026-11-04/05 · Device: Fire TV Stick 4K Max (192.168.0.221, Fire OS 8.1.8.5) · Driven only with injected D-pad/BACK keys.
Screenshots: same folder (1280x720). Pages covered: Remote, Speed, Sleep, Privacy, Debloat, Shortcuts, Status, Settings, the button editor and all its choosers, Add-app-profile chooser, Never-close / Add-app / Kept-asleep choosers, Debloat "Careful" dialog + advanced list.

Safety: nothing was applied. No Save/On/Off/Boost/DNS/Disable/Restore/Turn-off/Fix-clock/Refresh was pressed. The only buttons activated were non-mutating ones: "Listen for a button" (timed out with no input), "Show them" in the Debloat "Careful" dialog (it only expands the advanced list), and choosers that were all dismissed with BACK/Cancel.
Crashes: none from Stickman (`logcat` FATAL entries only from Amazon's whisperjoin process). Focus never left Stickman, except once when BACK on the Remote page exited to the launcher (#16).

Summary: **0 Blocker · 4 Major · 12 Minor · 10 Polish**

---

## Major

### 1. Sidebar's 8th item (Settings) is clipped to an unreadable sliver and the sidebar never scrolls
- Severity: Major · Page: all (sidebar)
- Steps: From the sidebar, press DOWN past "Status". Focus moves to an 8th item that shows only as a thin orange pill at the bottom edge with no label. Pressing RIGHT opens the Settings page, but the sidebar still doesn't scroll and the item stays clipped even while active.
- Screenshots: 145-privacy-d8.png, 146-settings-page.png, 154-restore-focus.png
- Fix: Put the sidebar in a scroll container that scrolls the focused item into view, or shrink the item height and top wordmark/action bar so all 8 items fit.

### 2. Entering a page with RIGHT puts focus on an invisible or off-screen element
- Severity: Major · Page: Speed, Sleep, Privacy, Debloat (any scrollable page)
- Steps: Sidebar → Sleep → RIGHT. No focus ring is visible anywhere (168). On Debloat after scrolling, RIGHT restores focus to "Alexa alerts → Disable", which is scrolled out of view. Pressing OK at that moment would disable an app without the user seeing what is focused (191, then DOWN 192 / UP 193 reveals it). The same happens on Speed (159) and Debloat at first entry (177).
- Screenshots: 159-speed-top.png, 168-sleep-in.png, 177-debloat-in.png, 191-step.png, 192-step-d.png, 193-step-u.png
- Fix: On page entry, request focus on the first visible control and call `requestRectangleOnScreen` / scroll it into view. Never restore focus to an off-screen child.

### 3. Many option groups show no current-value state
- Severity: Major · Page: Sleep (Sleep timer, Screensaver, Sleep after, Screen off after), Speed (Animation speed)
- Steps: Open Sleep and scroll. In each chip row, no chip has the filled "selected" style that other pages use (e.g. Speed "Off", Privacy "Off", Settings "250 ms"). The user can't tell the current setting.
- Screenshots: 168-sleep-in.png, 172-sleep-d7.png, 166-sleep-d6.png (Animation speed)
- Fix: Read the current values and apply the same selected-chip style. If a value is unknown, show "Current: unknown" text.

### 4. Profile ◀ / ▶ arrow buttons can't be reached with the D-pad
- Severity: Major · Page: Remote
- Steps: Remote → focus the D-pad's right arrow (or Menu) → RIGHT goes straight to "Add app profile" (123, 127, 128). UP from "Add app profile" jumps to the sidebar (124/125). The ◀ ▶ arrows never take focus, although they are drawn as buttons. (With only one profile they may be disabled on purpose, but nothing shows them as disabled.)
- Screenshots: 123-menu-right.png, 124-col-up.png, 127-top-right.png, 128-top-right2.png
- Fix: Make the arrows focusable with explicit nextFocusUp/Down links to "Add app profile", or show them in a dimmed disabled style when there is only one profile.

## Minor

### 5. DOWN on the last control of a page jumps focus into the sidebar
- Severity: Minor · Page: Sleep, Speed, Debloat, Privacy
- Steps: Scroll to the last control (e.g. Sleep "Screen off after" row) → DOWN. Focus lands on sidebar "Status" or the clipped "Settings" item.
- Screenshots: 173-sleep-bottom-down.png, 166-sleep-d6.png, 181-debloat-d9.png, 145-privacy-d8.png
- Fix: Block DOWN at the end of the content column (`nextFocusDown` = self, or a FocusFinder override).

### 6. Focus jumps across regions on UP: Remote side column → sidebar, sidebar top → content
- Severity: Minor · Page: Remote, Sleep
- Steps: Remote: focus "Add app profile" → UP lands on sidebar "Privacy" then "Sleep" (124, 125). Sleep: from sidebar "Status" press UP repeatedly. After "Remote", focus leaves the sidebar and lands on "Stay awake…" in the content (174).
- Screenshots: 124-col-up.png, 125-col-up2.png, 174-up.png
- Fix: Set explicit nextFocusUp links (Add app profile → profile arrows; first sidebar item → self).

### 7. LEFT from content lands on the geometrically nearest sidebar item, not the active page
- Severity: Minor · Page: Debloat (long list), others
- Steps: Debloat advanced list, focus a row level with "Privacy" → LEFT. "Privacy" is focused while "Debloat" is the active page (208). From a lower row it lands on the clipped "Settings" item (202).
- Screenshots: 208-l.png, 202-adv-left.png
- Fix: Intercept LEFT at the content edge and focus the active page's sidebar item.

### 8. Chooser dialogs are stock Amazon/Leanback system dialogs that don't match the app theme
- Severity: Minor · Page: Remote editor choosers, Add app profile, Speed choosers
- Steps: Open the Home editor → Press → "Act as another button…" / "Open an app…" / "A Stickman action…", or Double press / Hold. Each uses a grey gradient, teal highlight, thin light title font and grey Cancel button, unlike the dark/wood/orange theme. The title sits at the top screen edge (overscan risk) and the last row is cut mid-text.
- Screenshots: 105-act-as-button.png, 108-open-app-wait.png, 110-stickman-action.png, 119-double-chooser.png, 120-hold-chooser.png, 131-addprof-wait.png
- Fix: Use a custom themed list dialog with the orange focus ring and dark wood background, and keep content inside a 5% title-safe margin.

### 9. Button-editor dialog: action buttons are system grey and use a white-fill focus, misaligned with the rows
- Severity: Minor · Page: Remote (button editor)
- Steps: Remote → Home → OK. The rows use the themed orange ring, but "Save / Reset to original / Cancel" are grey system buttons whose focus is a white fill (115-117). The button row is indented differently from the rows: Save starts at x≈357 vs rows x≈339, and Cancel ends at x≈922 vs rows x≈940. Default focus (Press row) and reachability of Save are OK.
- Screenshots: 102-home-editor.png, 115-ed-d3.png, 116-ed-r.png
- Fix: Restyle the dialog buttons with the app's button style and focus ring, and align them to the row edges.

### 10. Debloat "Careful" confirm is a system-styled dialog with default focus on the risky action
- Severity: Minor · Page: Debloat
- Steps: Debloat → "Show advanced list…" → OK. A grey system dialog opens with default focus on "Show them".
- Screenshots: 196-adv-dialog-wait.png
- Fix: Theme it and default-focus "Cancel" for warning dialogs.

### 11. "Listen for a button" is below the fold, and listening gives no guidance or countdown
- Severity: Minor · Page: Remote
- Steps: Remote → Add app profile → DOWN. The right column scrolls and pushes the "Remote" header and description off the top (129). OK → "Listening…" with only an orange outline on the remote. There is no instruction ("Press any button on your remote") and no countdown, and it silently reverts after about 10 s (133-135).
- Screenshots: 129-addprof-down.png, 133-listen.png, 135-listen-10s.png
- Fix: Move Listen up (e.g. under the profile card) or make the column fit, and show an instruction plus a countdown while listening.

### 12. Status page: empty Storage tile and misaligned stat tiles
- Severity: Minor · Page: Status
- Steps: Open Status. Storage shows "–". The RAM/CPU/Storage and Wi-Fi/Fire OS tiles end at x≈1220 while the cards above and below end at x≈1237. The tiles' top borders look cut flush (no rounded top).
- Screenshots: 157-status-top.png, 158-status-d3.png
- Fix: Populate Storage (or hide the tile when unavailable), and lay the tiles out on the same grid and width as the cards.

### 13. Raw debug text shown to the user ("OK DNS null null ADS 1")
- Severity: Minor · Page: Settings
- Steps: Settings → scroll to the bottom. The "Last bridge result" card shows `OK DNS null null ADS 1`.
- Screenshots: 151-settings-d6.png
- Fix: Map results to human text ("Last change: DNS off, ad tracking limited — 00:08") or hide the card behind a debug toggle.

### 14. Debloat advanced list shows raw package names with no labels, search or action hint
- Severity: Minor · Page: Debloat
- Steps: Show advanced list. About 100 rows appear like "com.amazon.aca" and "com.amazon.tv.turnstile". Nothing says what OK on a row does, and there is no jump or filter.
- Screenshots: 199-shown-d1.png, 200-adv-d5.png, 201-adv-far.png
- Fix: Show app labels with the package as a subtitle, add a trailing "Disable" affordance like the Recommended rows, and group or filter the list.

### 15. "Restore all…" is enabled when nothing has been disabled
- Severity: Minor · Page: Debloat
- Steps: Debloat → "Disabled by Stickman: None." while "Restore all…" is still a focusable, active-looking button.
- Screenshots: 180-debloat-d7.png
- Fix: Disable or hide the button when the list is empty.

### 16. BACK on a page exits the app immediately
- Severity: Minor · Page: Remote (all pages)
- Steps: Remote → focus in the right column → BACK. The app exits to the Wolf launcher (136); focus never returns to the sidebar first.
- Screenshots: 136-listen-back.png
- Fix: Make BACK in content move focus to the active sidebar item, and only exit (or confirm) on BACK from the sidebar.

## Polish

### 17. Redundant top action bar "Stickman"
- Page: all · Screenshot: 122-editor-closed-by-back.png
- The black system action bar repeats the orange "Stickman" sidebar wordmark and wastes about 64 px of vertical space, which contributes to #1.
- Fix: Use a NoActionBar theme.

### 18. Remote: callout bubble and "Selected button" card show the same information
- Page: Remote · Screenshot: 122-editor-closed-by-back.png
- The bubble ("Open Wolf Launcher" / "Original") and the Selected-button card both show the press action.
- Fix: Keep one, or make the bubble show the button name only.

### 19. Inconsistent gap between page title and first card
- Pages: Speed, Sleep, Privacy, Debloat, Shortcuts (~75 px gap) vs Status, Settings (~35 px)
- Screenshots: 159-speed-top.png, 168-sleep-in.png, 157-status-top.png, 146-settings-page.png
- Fix: Use one header spacing token.

### 20. Cards with excess empty bottom padding
- Cards: Boost (Speed), Sleep timer (Sleep), Fixes (Settings)
- Screenshots: 159-speed-top.png, 168-sleep-in.png, 150-settings-d5.png
- Fix: Use uniform card padding; remove the fixed min-height.

### 21. DOWN into a chip row lands on the centre-aligned chip, not the first or selected one
- Pages: Speed ("Close apps…" → "5 min"), Settings ("Turn Stickman OFF" → "500 ms")
- Screenshots: 163-speed-R-D.png, 148-settings-d3.png
- Fix: Set nextFocusDown to the selected chip of the row.

### 22. Internal jargon in user copy
- Text: "remapd owns the remote and stickd runs automation", "remapd (buttons)", "stickd (automation)", "Last bridge result", "Live status (stickd)"
- Screenshots: 146-settings-page.png, 157-status-top.png
- Fix: Use plain wording ("Button remapping", "Background automation").

### 23. Shortcuts grid isn't in a card and its right edge is misaligned
- Page: Shortcuts · Screenshots: 209-shortcuts.png, 211-sc-d4.png
- The grid ends at x≈1223 vs cards at x≈1237, and the subtitle floats with a big gap below the title.
- Fix: Wrap the grid in the standard card, or align it to the card grid.

### 24. Monospace font in "Live status (stickd)"
- Page: Status · Screenshot: 158-status-d3.png
- "No status lines yet." is set in a monospace font, unlike the rest of the UI.
- Fix: Use the app font; keep monospace only for log lines, if needed.

### 25. On/off toggles are text buttons with the state embedded in the label
- Pages: Speed ("Close apps when I leave them: Off"), Sleep ("Stay awake while media plays: Off")
- Screenshots: 163-speed-R-D.png, 172-sleep-d7.png
- These look unlike the On/Off chip pairs used elsewhere (Privacy, Sleep → Screensaver).
- Fix: Use a consistent switch or On/Off chip pattern.

### 26. Remote page: large dead area and the remote graphic isn't centred in its column
- Page: Remote · Screenshot: 122-editor-closed-by-back.png
- There's an empty band of about 150 px between the remote graphic and the right column.
- Fix: Centre the remote, or widen the right column and info cards.

---
App left on the Remote page (212-final-remote.png).
