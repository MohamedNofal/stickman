# Amazon Appstore listing: Stickman (Appstore edition)

Build: `./build.sh appstore` -> `stickman-appstore.apk` (package `com.clean.stickman.tv`, targets API 34 as Amazon requires; the GitHub build stays on 30).
Price: USD 1.99 (Amazon converts for other marketplaces). Category: Utilities. Platform: Fire TV only.
Privacy policy URL: https://github.com/MohamedNofal/stickman/blob/main/store/appstore/PRIVACY.md

## Title
Stickman: Remote Button Remapper

## Short description
Make any Fire TV remote button do what you want: open any app, act as another button, sleep timer, screenshot, close the current app and more. Press, double press and hold each work separately. No root.

## Product feature bullets
- Remap any remote button, including the app buttons, to open any app or run an action
- Press, double press and hold can each do something different, per app if you like
- Live memory map of running apps, with one-press Boost and Trim
- Sleep timer with fade-out, screen off with sound on, stay awake while a video plays
- Shortcuts to hidden Fire TV settings screens, and an Undo everything button

## Long description
Stickman turns your Fire TV remote into the remote you want.

REMAP ANY BUTTON
Press Listen, press any button on your remote, and choose what it should do: open any app, act as another button, or run an action such as sleep timer, screen off, screenshot, play/pause, next, or close the current app. The original action never fires, so there's no Amazon home flash and no delay. Press, double press and hold each work separately, and an app can have its own buttons. Works with every Fire TV remote, including buttons Stickman has never seen before.

KEEP THE STICK QUICK
- Tasks: a live map of running apps sized by the memory they use. End an app, always close it when you leave it, or protect it.
- Boost closes idle background apps; Trim asks every app that isn't in front to give back memory it doesn't need, without closing anything.
- Recommended settings in one press: fewer idle apps in memory, a background CPU limit, deep sleep while the TV is off, fewer background wake-ups.
- Keep any app asleep so it can't start itself in the background.

SLEEP AND SCREEN
Sleep timer with a gentle fade-out, screen off with sound still playing, stay awake while a video plays, and an HDMI-CEC switch.

SAFE BY DESIGN
Every change is recorded. Settings > Undo everything puts your Fire TV back exactly as it was. Holding Back for 10 seconds always gives you the normal remote back.

BEFORE YOU BUY
Stickman needs ADB debugging turned on: Settings > My Fire TV > Developer options > ADB debugging (on newer Fire OS, first press OK 7 times on Settings > My Fire TV > About > your device name). On first launch, accept the VPN request and tick "Always allow" on "Allow USB debugging?". The VPN is local only: it carries Stickman's commands to your TV's own debugging service and nothing else. No data is collected.

Tested on Fire TV Stick 4K Max (Fire OS 8). Other Fire TV devices with Fire OS 7 or 8 should work.

## Keywords
remote, remap, button, remote buttons, netflix button, launcher, sleep timer, memory, ram, boost, task manager, fire tv remote

## Assets (store/appstore/assets)
- Small icon 114x114: icon-small-114.png
- Large icon 512x512: icon-large-512.png
- Fire TV app icon 1280x720: firetv-app-icon-1280x720.png
- Fire TV background 1920x1080: firetv-background-1920x1080.png
- Promo image 1024x500: promo-1024x500.png
- Screenshots 1920x1080: screenshots/1-remote … 7-settings.png (Appstore edition, taken on the stick 2026-11-07)

## Content rating answers
No violence, no sexual content, no gambling, no user-generated content, no location sharing, no in-app purchases, no ads, no account or personal data collection.

## Notes for the Amazon reviewer
Stickman needs ADB debugging enabled (Settings > My Fire TV > Developer options). On first launch it asks for a VPN connection and then shows Fire OS's "Allow USB debugging?" prompt: tick "Always allow". The VPN is local only (address 10.99.0.1/10.99.0.2, routes only Stickman's own commands to the device's debugging service). To try a remap: Remote page > Listen > press a remote button > choose "Open an app". Holding Back for 10 seconds releases the remote; Settings > Undo everything reverts every change.
