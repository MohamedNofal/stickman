#!/bin/bash
# grabtest.sh - regression test: remapd must never grab the remote in the middle of a press.
#
# A remote reconnects on a button press. If remapd grabs it while that button is still down, Android has seen the
# DOWN and never gets the UP: the key stays "held" for Android and is dead in every app until the remote disconnects
# (happened on a real stick, 2026-11-15, with Back). This holds a button on the fake remote, starts a test remapd,
# and checks: no grab while held, grab right after release, and no key left down in Android's input reader.
#
# Usage: STICK=<adb serial> [SU="su 0"] tools/grabtest.sh     (./build.sh first)
# SU: prefix for commands that need /dev/uinput where shell may not open it (Android 9 emulator); empty on Fire OS 8.
# Uses its own directory, pid file and device name, so a production remapd on the same stick is left alone.
set -u
cd "$(dirname "$0")/.."
STICK=${STICK:?set STICK to the adb serial}
SU=${SU:-}
ADB="adb -s $STICK"
T=/data/local/tmp/stickman-grabtest
FAKE="Stickman Test Remote"
sh_() { $ADB shell "$@" </dev/null | tr -d '\r'; }
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected $3, got $2"; fail=1; fi; }
cleanup() {
    sh_ "$SU sh -c 'P=\$(cat $T/remapd.pid 2>/dev/null); [ -n \"\$P\" ] && grep -q \"$FAKE\" /proc/\$P/cmdline 2>/dev/null && kill \$P; for P in \$(pidof fakeremote); do grep -q $T/ /proc/\$P/cmdline 2>/dev/null && kill \$P; done; for P in \$(pidof tail); do grep -q $T/cmd /proc/\$P/cmdline 2>/dev/null && kill \$P; done; true'"
}
trap cleanup EXIT

[ -x native/remapd ] && [ -x native/fakeremote ] || { echo "run ./build.sh first" >&2; exit 2; }
cleanup
sh_ "$SU rm -rf $T; mkdir -p $T; chmod 777 $T"
$ADB push native/remapd native/fakeremote "$T/" >/dev/null || exit 2
sh_ "chmod 755 $T/remapd $T/fakeremote; printf 'profile default\n172 tap none\n' > $T/remap.conf; : > $T/cmd; chmod 666 $T/cmd"
sh_ "$SU sh -c 'setsid nohup sh -c \"tail -f $T/cmd | $T/fakeremote > $T/sent 2> $T/err\" </dev/null >/dev/null 2>&1 &'"
for i in $(seq 30); do sh_ "cat $T/sent 2>/dev/null" | grep -q READY && break; sleep 0.2; done
sh_ "cat $T/sent" | grep -q READY || { echo "fakeremote did not start: $(sh_ "cat $T/err")" >&2; exit 2; }

sh_ "logcat -c"
sh_ "echo 'down 108' >> $T/cmd"; sleep 1
sh_ "$SU sh -c 'setsid nohup $T/remapd $T/remap.conf \"$FAKE\" $T/remapd.pid </dev/null >/dev/null 2>&1 &'"
sleep 4
PID=$(sh_ "cat $T/remapd.pid")
[ -n "$PID" ] || { echo "test remapd did not start" >&2; exit 2; }
# only this remapd's lines: a production remapd on the same stick logs under the same tag
grabs() { sh_ "logcat -d -s RemapD" | grep " $PID " | grep -c 'remote grabbed'; }
check "no grab while a button is held" "$(grabs)" 0
sh_ "echo 'up 108' >> $T/cmd"; sleep 3
check "grab once the button is released" "$(grabs)" 1
check "Android has no key left down" "$(sh_ "dumpsys input" | grep -c 'KeyDowns: [1-9]')" 0
sh_ "echo 'tap 108' >> $T/cmd"; sleep 2
check "Android still has no key down after a normal press" "$(sh_ "dumpsys input" | grep -c 'KeyDowns: [1-9]')" 0
[ $fail = 0 ] && echo "GRABTEST OK" || echo "GRABTEST FAILED"
exit $fail
