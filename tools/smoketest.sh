#!/bin/bash
# smoketest.sh - does every catalogue app start on a real Fire TV?
#
# TV Bro's small build crashed at start on Amazon's WebView (Fire OS 8, 2026-11-15); an emulator with Google's
# WebView would never have shown it. So before an app goes into (or stays in) the catalogue, it is installed on a
# real Fire TV, started from its TV launcher entry, watched for 15 s (crash buffer, ANR, process alive, what is in
# front), photographed, and removed again if it was not there before.
#
# Usage: STICK=<adb serial> tools/smoketest.sh [catalog.json] [id ...]
#   catalog.json defaults to the published catalogue; ids default to all apps in it.
#   FORCE=1 runs even when the TV is in use (playing, or touched in the last 10 minutes).
# Writes build/smoketest/<id>.png and prints one line per app: PASS / FAIL / SKIP with the reason.
# Apps already installed are only started, never removed. The files come from the catalogue's own links and are
# checked against its SHA-256 before they are installed.
set -u
cd "$(dirname "$0")/.."
STICK=${STICK:?set STICK to the adb serial}
CAT=${1:-https://raw.githubusercontent.com/MohamedNofal/stickman/catalog/catalog.json}; shift || true
OUT=build/smoketest; mkdir -p "$OUT"
CACHE=${TMPDIR:-/tmp}/stickman-smoke; mkdir -p "$CACHE"
sh_() { timeout "${T:-60}" adb -s "$STICK" shell "$@" </dev/null 2>&1 | tr -d '\r'; }
reconnect() { case "$STICK" in *:*) adb disconnect "$STICK" >/dev/null 2>&1; adb connect "$STICK" >/dev/null 2>&1;; esac; }

reconnect
if [ "${FORCE:-0}" != 1 ]; then
    playing=$(sh_ 'dumpsys media_session | grep -c "state=PlaybackState {state=3"')
    idle_ms=$(sh_ 'dumpsys power | grep -m1 "mLastUserActivityTime=" | sed "s/.*(\([0-9]*\) ms ago).*/\1/"')
    if [ "${playing:-0}" != 0 ] || [ "${idle_ms:-0}" -lt 600000 ]; then
        echo "the TV is in use (playing=$playing, last touched ${idle_ms:-?} ms ago): not now (FORCE=1 to run anyway)"; exit 3
    fi
fi
case "$CAT" in http*) curl -sS -m 60 -o "$CACHE/catalog.json" "$CAT" || exit 2; CAT=$CACHE/catalog.json;; esac
ABIS=$(sh_ 'getprop ro.product.cpu.abilist')

python3 - "$CAT" "$ABIS" "$@" > "$CACHE/plan.txt" <<'PY'
import json, sys
cat, abis, ids = sys.argv[1], [a for a in sys.argv[2].strip().split(",") if a], set(sys.argv[3:])
for a in json.load(open(cat))["apps"]:
    if ids and a["id"] not in ids: continue
    f = next((a["files"][x] for x in abis if x in a["files"]), a["files"].get("any"))
    if not f: print(a["id"], a["package"], "-", "-", a.get("minSdk", 1)); continue
    print(a["id"], a["package"], f["url"], f["sha256"], a.get("minSdk", 1))
PY

SDK=$(sh_ 'getprop ro.build.version.sdk')
pass=0; fail=0; skip=0
while read -r id pkg url sha minsdk; do
    if [ "$url" = - ]; then echo "SKIP $id: no file for this Fire TV's processor ($ABIS)"; skip=$((skip+1)); continue; fi
    if [ "$minsdk" -gt "${SDK:-99}" ]; then echo "SKIP $id: needs Android API $minsdk, this is $SDK"; skip=$((skip+1)); continue; fi
    reconnect
    had=$(sh_ "pm list packages $pkg" | grep -c "^package:$pkg\$")
    if [ "$had" = 0 ]; then
        f=$CACHE/$id-${sha:0:12}.apk
        [ -s "$f" ] || curl -sSL -m 1800 -o "$f" "$url" || { echo "FAIL $id: download failed"; fail=$((fail+1)); continue; }
        [ "$(sha256sum "$f" | cut -d' ' -f1)" = "$sha" ] || { echo "FAIL $id: checksum differs from the catalogue"; rm -f "$f"; fail=$((fail+1)); continue; }
        adb -s "$STICK" push "$f" /data/local/tmp/smoke.apk >/dev/null 2>&1 || { reconnect; adb -s "$STICK" push "$f" /data/local/tmp/smoke.apk >/dev/null 2>&1; }
        reconnect
        r=$(T=600 sh_ 'pm install -r /data/local/tmp/smoke.apk; rm -f /data/local/tmp/smoke.apk')
        echo "$r" | grep -q Success || { echo "FAIL $id: install refused: $(echo "$r" | tail -1)"; fail=$((fail+1)); continue; }
    fi
    reconnect
    act=$(sh_ "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER $pkg" | tail -1)
    case "$act" in */*) ;; *) act=$(sh_ "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg" | tail -1);; esac
    case "$act" in
        */*) sh_ "logcat -c -b crash; logcat -c; am start -n '$act'" >/dev/null
             sleep 15
             reconnect
             front=$(sh_ 'dumpsys activity activities | grep -m1 mResumedActivity' | sed 's/.*u0 //; s/ .*//')
             alive=$(sh_ "pidof $pkg")
             crash=$(sh_ "logcat -d -b crash | grep -A2 'Process: $pkg' | grep -m1 -E 'Exception|Error' | sed 's/^.*AndroidRuntime([^)]*): //'")
             anr=$(sh_ "logcat -d | grep -m1 'ANR in $pkg'")
             timeout 30 adb -s "$STICK" exec-out screencap -p > "$OUT/$id.png" 2>/dev/null
             if [ -n "$crash" ]; then echo "FAIL $id: crashed: $crash"; fail=$((fail+1))
             elif [ -n "$anr" ]; then echo "FAIL $id: not responding (ANR)"; fail=$((fail+1))
             elif [ -z "$alive" ]; then echo "FAIL $id: not running 15 s after start (front: $front)"; fail=$((fail+1))
             else echo "PASS $id: running, in front: ${front:-?}"; pass=$((pass+1)); fi
             sh_ "am force-stop $pkg" >/dev/null;;
        *)   echo "FAIL $id: no launcher entry (nothing to open on a TV)"; fail=$((fail+1));;
    esac
    [ "$had" = 0 ] && sh_ "pm uninstall $pkg" >/dev/null
done < "$CACHE/plan.txt"
sh_ 'input keyevent HOME' >/dev/null
echo "$pass passed, $fail failed, $skip skipped"
[ $fail = 0 ]
