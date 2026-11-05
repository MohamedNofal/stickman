# Fire TV remote presets (research for Stickman)

Photos in `remote-photos/` (downloaded from AFTVnews; viewed to confirm layouts). Layouts marked (photo) were verified visually; (amazon) from Amazon help "What are the Buttons on..." pages.
Keycode legend (Vendor_0171_Product_0427 .kl, from owner): POWER 116, ALEXA 217, DPAD_CENTER 96, UP 103, LEFT 105, RIGHT 106, DOWN 108, HOME 172, BACK 158, MENU 139, REWIND 168, FF 208, PLAY_PAUSE 164, MUTE 113, VOL- 114, VOL+ 115, LIVE_TV(Guide) 362, CH+ 402, CH- 403, RECENTS 748, SETTINGS 249, APP_1..4 = 744..747.
Not known for any remote (UNCERTAIN, must be probed on-device with getevent): Pro custom buttons 1/2, Pro headphone button, 2026 star button, 2026 "sliders" (settings-like) button, 2026 mic button. Older remotes (gen1/gen2) use other .kl files; mute/vol/power keycodes there are presumably the same standard ones (UNCERTAIN).
Note on the D-pad: every model has a ring (UP/LEFT/RIGHT/DOWN) + centre select; omitted from grids below ("RING" row).
Hardware note: the Amazon remote does NOT share a single layout per "generation" name; AFTVnews counts 7 distinct voice remote models up to 2024.

---------------------------------------------------------------------
## 1. Alexa Voice Remote Lite, 1st gen (2020)  [MODEL: 1st-gen Lite]
- Year 2020. Shipped with: Fire TV Stick Lite (2020). Not sold separately.
- Photo: `lite-gen1-with-gen2.jpg` (right-hand remote; left one is the 2018 gen2). (photo)
- Grid:
  - R1: [mic hole] [MIC/voice button, black mic icon, under hole, centred (ALEXA 217 presumably)]   (no power button)
  - R2: RING (UP/LEFT/RIGHT/DOWN 103/105/106/108, SELECT 96)
  - R3: BACK 158 | HOME 172 | MENU 139
  - R4: REWIND 168 | PLAY_PAUSE 164 | FF 208
  - R5: (centre only) GUIDE/LIVE_TV 362   (photo: TV-icon button centred; AFTVnews says Lite added Channel Guide)
- No power/volume/mute/channel/settings/recents, no app buttons. Rockers: none.
- Note: voice button is a black mic icon (not blue Alexa ring).
- Sources: https://www.aftvnews.com/new-alexa-voice-remote-lite-for-fire-tv-passes-through-regulatory-filings/ ; https://www.aftvnews.com/?p=38421

## 2. Alexa Voice Remote Lite, 2nd gen (model E4GE9R, 2022)
- Year Jan 2022 (AFTVnews). Shipped with: Fire TV Stick Lite (and Fire TV Stick HD/Lite bundles in some regions, UNCERTAIN). Not sold separately.
- Photo: `lite-gen2-2022.jpg` (photo)
- Grid:
  - R1: [mic hole] ; ALEXA 217 (blue Alexa ring button) (no power)
  - R2: RING (+ SELECT)
  - R3: BACK | HOME | MENU
  - R4: REWIND | PLAY_PAUSE | FF
  - R5: GUIDE/LIVE_TV 362 (centred)
  - R6: APP_a | APP_b   (row 1 of app buttons)
  - R7: APP_c | APP_d
- No power/volume/mute/channel/settings/recents. Rockers: none.
- App buttons: US photo = Prime Video, Netflix, Disney+, Hulu. German retailer text = Prime Video, Netflix, Disney+, Amazon Music. India report = Prime Video, Netflix, Apps, Amazon Music. UNCERTAIN which keycode (744-747) maps to which slot; per owner's Enhanced: Netflix=744, Prime=745, Apps=746, Music=747, so slot order in code does NOT follow visual order — probe per model.
- Sources: https://www.aftvnews.com/?p=38421 ; https://telecomtalk.info/?p=525843 ; https://www.slashgear.com/new-alexa-voice-remote-adds-more-buttons-24665248 (unverified)

## 3. Alexa Voice Remote 1st gen family (2014-2017, 4 distinct models; no TV controls)  [LOW CONFIDENCE]
- Shipped with: Fire TV (1st/2nd gen box), Fire TV Stick (1st/2nd gen), Fire TV Cube 1st gen (UNCERTAIN: Cube 1 shipped with the gen2 remote).
- Photo: `evolution.jpg` (left three remotes of six: 1st gen variants 2014/2016, then 2018). Not individually verified.
- Layout (from the photo, approximate): mic button + hole at top, ring, BACK | HOME | MENU, REWIND | PLAY_PAUSE | FF (older models: only 1-2 rows below the ring, some with plain "Amazon" logo). No power/volume/mute. UNCERTAIN: exact button shapes/rows per variant; original 2014 remote had mic in a hole not a button.
- Source: https://www.aftvnews.com/?p=40315 (images), https://www.aftvnews.com/?p=32271 (generation numbering)

## 4. Alexa Voice Remote, 2nd gen (2018)
- Year Oct 2018. Shipped with: Fire TV Stick 4K (first), Fire TV Cube (1st gen), Fire TV Stick 2nd gen bundles (Jan 2019). Not bundled with Fire TV Edition TVs.
- Photo: `gen2-vs-gen3.jpg` (left remote) and `gen2-2018.jpg` (photo)
- Grid:
  - R1: POWER 116 | [mic hole] | [LED]
  - R2: MIC/voice button (black mic icon, ALEXA 217 presumably)
  - R3: RING (+ SELECT)
  - R4: BACK | HOME | MENU
  - R5: REWIND | PLAY_PAUSE | FF
  - R6: VOL rocker (+ top 115, - bottom 114), centred, vertical bar
  - R7: MUTE 113 (centred, below rocker)
- No guide, channel, settings, recents, app buttons. Rockers: volume only (centre).
- Sources: https://www.aftvnews.com/?p=39829 ; https://www.aftvnews.com/?p=32271 ; photo.

## 5. Alexa Voice Remote, 3rd gen (2021; also 2022 revision P4C6EN, same layout)
- Year Mar 2021 (L5B83G); Feb 2022 re-spin P4C6EN identical look. Shipped with: Fire TV Stick 4K (2021 refresh), Fire TV Stick 3rd gen, Fire TV Stick 4K Max 1st gen, Fire TV Cube 3rd gen (UNCERTAIN: Cube 3 ships with Pro-style/Enhanced in some regions), Fire TV (Pendant) 3rd gen. Compatible with many others.
- Photo: `gen2-vs-gen3.jpg` (right), `remotes-2023-std-vs-enhanced.jpg` (left remote, DIRECTV/Peacock variant). (photo)
- Grid (amazon + photo):
  - R1: POWER 116 | [mic hole] | [LED]
  - R2: ALEXA 217 (blue Alexa ring button)
  - R3: RING (+ SELECT 96)
  - R4: BACK 158 | HOME 172 | MENU 139
  - R5: REWIND 168 | PLAY_PAUSE 164 | FF 208
  - R6: MUTE 113 | VOL rocker (+ over -, 115/114, centre column, spans R6-R7) | GUIDE/LIVE_TV 362
  - R7: (below R6; only the volume rocker's lower half in centre column)
  - R8: APP_a | APP_b
  - R9: APP_c | APP_d
- No channel, settings, recents. Rockers: volume only (centre column, tall).
- App buttons: US = Prime Video, Netflix, Disney+, Hulu (photo), or DirecTV/Peacock variants in batches (photo + AFTVnews comments); Canada/UK etc = Prime Video, Netflix, Disney+/Apps/Amazon Music (UNCERTAIN per-country). Varies by region and batch.
- Sources: https://digprjsurvey.amazon.com/csad/help/node/TvF5ANMoNNjiPpopgQ ; https://www.aftvnews.com/?p=38501

## 6. Alexa Voice Remote (2024 Release) (model PA38CA)  [AKA "7th distinct model"]
- Year July 2024. Shipped with: Fire TV Stick 4K (first), later Fire TV Stick HD/4K Select/4K Plus and others. Same button layout as 3rd gen; redesigned body (sharper edges, Pro-like, top battery latch).
- Photo: `gen3-2021-vs-2024.jpg` (photo; app buttons blank in render; "NEW" = 2024)
- Grid: identical to #5 (R1 POWER+hole; ALEXA; RING; BACK HOME MENU; REW PLAY FF; MUTE | VOL rocker | GUIDE; 2x2 app buttons).
- Amazon help describes it as: Power+mic; Alexa; ring+select; Back/Home/Menu; Rewind/Play/FF; "Mute, Volume up/down (vertical bar centre), Guide"; two rows of two app buttons.
- App buttons: not specified; same region/batch variability. UNCERTAIN keycodes (assume 744-747).
- Sources: https://www.aftvnews.com/amazon-quietly-releases-new-fire-tv-voice-remote-and-the-fire-tv-stick-4k-gets-it-first/ ; https://digprjsurvey.amazon.com/csad/help/node/THKXBX5oZWzl3GmLzQ

## 7. Alexa Voice Remote Enhanced ("Fire TV Smart TV remote" 2020; re-badged Enhanced; ships with 4K Max 2nd gen 2023)
- Year: first appeared 2020 with Fire TV Smart TVs (Omni/4-Series 2021) and Fire TV Cube 3rd gen (AFTVnews, photo `fire-tv-smart-tv-remote-2020.jpg`); marketed as "Alexa Voice Remote Enhanced" and bundled with Fire TV Stick 4K Max 2nd gen (Oct 2023), Omni/QLED/4-Series TVs. Officially supported: Stick 4K Max, Cube; Fire TV 2/4-Series, Omni (amazon compat page).
- Photos: `fire-tv-smart-tv-remote-2020.jpg` (US Prime/Netflix/Disney+/Hulu variant), `remotes-2023-std-vs-enhanced.jpg` (right remote; DIRECTV/Peacock variant). (photo)
- Grid (photo + amazon, matches owner's):
  - R1: POWER 116 | [mic hole] | [LED]
  - R2: ALEXA 217
  - R3: RING (+ SELECT 96)
  - R4: BACK 158 | HOME 172 | MENU 139
  - R5: REWIND 168 | PLAY_PAUSE 164 | FF 208
  - R6: VOL rocker (+ top, - bottom, left column spanning R6-R7) | (blank / GUIDE centre-lower) | CH rocker (^ top 402, v bottom 403; right column R6-R7)
  - R7: (VOL rocker lower half) | GUIDE/LIVE_TV 362 (centre, lower than R6 centre) | (CH rocker lower half)
  - R8: MUTE 113 | SETTINGS 249 (gear) | RECENTS 748 (overlapping-squares icon)
  - R9: APP_a | APP_b
  - R10: APP_c | APP_d
- Rockers: volume (left, vertical) and channel (right, vertical). Guide sits between/under them in the centre.
- App buttons: US photo = Prime Video, Netflix, Disney+, Hulu or DirecTV/Peacock (batch variance, AFTVnews comments); UK/CA = Prime Video, Netflix, Apps, Amazon Music (owner's unit: grid R9 Prime Video, Netflix; R10 Apps, Amazon Music; keycodes Netflix=744, Prime=745, Apps=746, Music=747).
- Amazon's own help page text lists R6 as "Volume Up/Down (left bar), Guide (center), Channel Up/Down (right bar)" and R8 "Mute, Settings, Recents".
- Sources: https://digprjsurvey.amazon.com/csad/help/node/TpzeU8AlXHTw0oMiYg ; https://www.aftvnews.com/?p=43211 ; https://www.aftvnews.com/?p=46757 ; https://digitaltrends.com/home-theater/amazon-fire-tv-4k-max-2nd-gen-review

## 8. Alexa Voice Remote Pro (Nov 2022)
- Year Sept 2022 announce / Nov 16 2022 release, $34.99. Not bundled; sold separately (some regional bundles UNCERTAIN). Compatible with Cube, Stick 4K/4K Max/Lite/3rd gen/HD, Fire TV Smart TVs.
- Photo: `pro.jpg` (US Prime/Netflix/Disney+/Hulu variant) (photo); also `evolution.jpg` rightmost.
- Grid (photo + amazon QSG):
  - R1: POWER 116 | [mic hole/LED] | HEADPHONE (top-right; opens Bluetooth menu; keycode UNKNOWN)
  - R2: ALEXA 217 (teal)
  - R3: RING (+ SELECT 96) (only unbacklit buttons)
  - R4: BACK 158 | HOME 172 | MENU 139
  - R5: REWIND 168 | PLAY_PAUSE 164 | FF 208
  - R6: VOL rocker (+/-, left, spans R6-R7) | GUIDE/LIVE_TV 362 (centre) | CH rocker (right, spans R6-R7)
  - R7: (VOL lower) | SETTINGS 249 (centre) | (CH lower)
  - R8: MUTE 113 | CUSTOM 1 (keycode UNKNOWN) | CUSTOM 2 (keycode UNKNOWN)
  - R9: APP_a | APP_b
  - R10: APP_c | APP_d
- No RECENTS button (removed). Rockers: volume left, channel right. Backlit; remote finder speaker (not a button).
- App buttons: US Prime Video, Netflix, Disney+, Hulu (photo); batch variants DirecTV/Peacock (AFTVnews comments); Canada reported Apps, Netflix, Prime Video, Amazon Music (comment, unverified).
- Sources: https://d1ergij2b6wmg5.cloudfront.net/Amazon+Fire+TV+User+Guides/Amazon+Fire+TV+Device+Documentation/HTML+Guides/AlexaVoiceRemotePro_Online_QSG_USCAAU-EN.html ; https://www.aftvnews.com/?p=39805 ; https://www.aftvnews.com/?p=41017 ; https://www.aftvnews.com/?p=40315

## 9. Fire TV Remote (2026) - ships with Fire TV Stick 4K (3rd gen, Vega OS), $14.99, ship Oct 23 2026
- Redesigned: no ring labels change but NO Rewind/FF (left/right on D-pad used), no Recents, no Alexa blue ring (a MIC button), orange metal-domed HOME, star custom button, "sliders" button (replaces gear/settings; AFTVnews says Settings is under channel down - UNCERTAIN: icon is sliders).
- Photos: `fire-tv-remote-2026.jpg`, `fire-tv-remote-2026-and-plus.jpg` (black = Fire TV Remote 2026, grey aluminum = Remote Plus). (photo)
- Grid (photo):
  - R1: POWER 116 | [mic hole] | MIC/voice (keycode ALEXA 217? UNCERTAIN)
  - R2: RING (+ SELECT 96) (circular D-pad)
  - R3: BACK 158 | HOME 172 (orange) | MENU 139
  - R4: VOL rocker (+/-, left, spans R4-R5) | PLAY_PAUSE 164 | CH rocker (^v, right, spans R4-R5)
  - R5: (VOL lower) | GUIDE/LIVE_TV 362 | (CH lower)
  - R6: MUTE 113 | STAR (custom, keycode UNKNOWN) | SLIDERS/Settings-like 249? UNCERTAIN
  - R7: APP_a (Prime Video) | APP_b (Netflix)
  - R8: APP_c (YouTube) | APP_d (Disney+)
- App buttons: US photo = Prime Video, Netflix, YouTube, Disney+. Regional variants UNKNOWN.
- Fire TV Remote Plus (early 2027, replaces Pro): same buttons, aluminum, backlit, remote finder. Listed in Amazon compat page only for "Fire TV Remote (2026 Release)".
- Sources: https://www.aftvnews.com/?p=46832 ; https://digprjsurvey.amazon.co.uk/csad/help/node/GGJEXBX2A8S5UMZA

---------------------------------------------------------------------
## Not found / other
- Fire TV Edition TV remotes (Omni, 4-Series, 2-Series, Insignia, Toshiba): Omni/4-Series ship the Enhanced-style layout (#7) per CNBC review ("power top left, Alexa button top centre") and compat list. Insignia/Toshiba Fire TV Edition (2017-2019) had own TV-style remotes (not found; UNCERTAIN). Pro/Enhanced lack the red/green/yellow/blue HbbTV buttons (amazon). EU/UK Fire TV Edition TVs may have extra colour buttons (UNCERTAIN).
- Fire TV "Basic Edition" / simple non-voice remote (2014 "Fire TV remote" and Fire TV Stick 1st gen Basic Edition): no source found; assume RING, BACK, HOME, MENU, REW, PLAY, FF only (UNCERTAIN, no photo).
- Fire TV Cube 2nd gen: ships with the 2nd-gen voice remote (#4) or 3rd-gen (#5) depending on batch; 3rd-gen Cube (2022) ships with the Pro-style/Enhanced remote - UNCERTAIN; has no unique remote.
- Wikipedia/iFixit not consulted for photos (AFTVnews images used). No Gen1 individual front photo.

## Photos included (11 files)
evolution.jpg (6 remotes), gen2-2018.jpg, gen2-vs-gen3.jpg, gen3-2021-vs-2024.jpg, lite-gen1-with-gen2.jpg, lite-gen2-2022.jpg, fire-tv-smart-tv-remote-2020.jpg (Enhanced), remotes-2023-std-vs-enhanced.jpg, pro.jpg, fire-tv-remote-2026.jpg, fire-tv-remote-2026-and-plus.jpg
