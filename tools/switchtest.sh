#!/bin/bash
# switchtest.sh - regression test: remapd must hold the device the remote's buttons really come from.
#
# GitHub issue #5 (Fire TV Cube, Fire OS 7): the remote disconnects when the box sleeps. remapd then took the next
# best-looking device, Amazon's software keyboard "amzkeyboard", and never looked again, so after every sleep no
# button was remapped until Stickman was switched off and on. This plays that out with fake devices
# (native/fakeremote) and checks the rules of native/remote_pick.h on a real system:
#   1. the remote goes away and comes back, next to an "amzkeyboard": remapping works again at once, every time
#   2. the same with a remote of another name (not "Amazon Fire TV Remote")
#   3. a guess that is held is given up when a better device turns up
#   4. buttons pressed on a watched device make it the remote; typing on it does not
#   5. no key is left "held" for Android at the end
#
# Usage: STICK=<adb serial> [SU="su 0"] [REMAPD_SU="su 0"] tools/switchtest.sh
# Run it on the emulator, or on a stick with Stickman switched OFF: the test remapd looks for the remote the way
# the real one does, so the two would fight over it. It has its own build and directory (-DDIR_PATH), so nothing of
# an installed Stickman is touched. SU: prefix for /dev/uinput where shell may not open it (Android 9 emulator).
# Where shell cannot make a virtual remote (Fire OS 5-7, the emulator) Stickman must be installed: its APK holds
# the key injector. REMAPD_SU="su 0" runs the test remapd as root instead, which may make a virtual remote there:
# the way Fire OS 8 works (the clone is made again when the remote is another device).
set -u
cd "$(dirname "$0")/.."
STICK=${STICK:?set STICK to the adb serial}
SU=${SU:-}
REMAPD_SU=${REMAPD_SU:-}
ADB="adb -s $STICK"
T=/data/local/tmp/stickman-switchtest
sh_() { $ADB shell "$@" </dev/null | tr -d '\r'; }
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected $3, got $2"; fail=1; fi; }
checkre() { if echo "$2" | grep -Eq "$3"; then echo "ok   $1"; else echo "FAIL $1: expected /$3/, got $2"; fail=1; fi; }

# every fake device is "tail -f <its own command file> | fakeremote --name ..."; killed hard, like a remote going away
SEQ=0
dev_start() {   # dev_start TAG NAME [fakeremote options...]
    local tag=$1 name=$2; shift 2
    SEQ=$((SEQ + 1)); local f=$T/$tag.$SEQ.cmd
    sh_ ": > $f; chmod 666 $f; echo $f > $T/$tag.cur"
    sh_ "$SU sh -c 'setsid nohup sh -c \"tail -f $f | $T/fakeremote --tag $tag.$SEQ --name \\\"$name\\\" $* > $T/$tag.out 2>&1\" </dev/null >/dev/null 2>&1 &'"
    for i in $(seq 30); do sh_ "cat $T/$tag.out 2>/dev/null" | grep -q READY && return; sleep 0.2; done
    echo "fake device $name did not start: $(sh_ "cat $T/$tag.out")" >&2; exit 2
}
dev_kill() {    # dev_kill TAG: that device vanishes at once
    sh_ "$SU sh -c 'for P in \$(pidof fakeremote); do tr \"\\000\" \" \" < /proc/\$P/cmdline 2>/dev/null | grep -q -- \"--tag $1\\.\" && kill -9 \$P; done; true'"
}
press() { sh_ "echo 'tap $2' >> \$(cat $T/$1.cur)"; sleep "${3:-1.5}"; }   # press TAG CODE [wait]
state() { sh_ "cat $T/remote.state 2>/dev/null"; }
remaps() { sh_ "logcat -d -s RemapD" | grep " $PID " | grep -c 'key 172 tap -> disabled'; }
mark() { sh_ "logcat -c"; }
wait_state() {  # wait_state REGEX [seconds]: the state file matches
    for i in $(seq $(( ${2:-20} * 2 ))); do state | grep -Eq "$1" && return 0; sleep 0.5; done; return 1
}
cleanup() {
    sh_ "$SU sh -c 'P=\$(cat $T/remapd.pid 2>/dev/null); [ -n \"\$P\" ] && grep -q $T/ /proc/\$P/cmdline 2>/dev/null && kill \$P; for P in \$(pidof fakeremote); do grep -q $T/ /proc/\$P/cmdline 2>/dev/null && kill -9 \$P; done; for P in \$(pidof tail); do grep -q $T/ /proc/\$P/cmdline 2>/dev/null && kill \$P; done; true'"
}
trap cleanup EXIT

[ -x native/fakeremote ] || { echo "run ./build.sh first" >&2; exit 2; }
mkdir -p build
SRC=${REMAPD_SRC:-native}   # another checkout's native/ directory, to see the test fail on an older remapd
arm-linux-gnueabihf-gcc -O2 -static -w -DDIR_PATH="\"$T/\"" -o build/remapd-switchtest "$SRC/remapd.c" "$SRC/layers.c" || exit 2
cleanup
sh_ "$SU rm -rf $T; mkdir -p $T; chmod 777 $T"
$ADB push build/remapd-switchtest "$T/remapd" >/dev/null && $ADB push native/fakeremote "$T/" >/dev/null || exit 2
# "stopped": this remapd must not start a stickd of its own. Home (172) is remapped to "nothing", which shows in the log.
sh_ "chmod 755 $T/remapd $T/fakeremote; printf 'profile default\n172 tap none\n' > $T/remap.conf; echo 1 > $T/stopped; pm path com.clean.stickman 2>/dev/null | sed s/package:// > $T/apk"
REMOTE="Amazon Fire TV Remote"
USABLE='^(ok|inject) '

echo "-- 1. issue #5: the remote sleeps and returns next to amzkeyboard"
dev_start akb amzkeyboard --bus bt --id 0000:0000            # as Amazon makes it: Bluetooth bus, no vendor, every key
dev_start rem "$REMOTE"
mark
sh_ "$REMAPD_SU sh -c 'setsid nohup $T/remapd $T/remap.conf </dev/null >/dev/null 2>&1 &'"
wait_state "$USABLE$REMOTE" 60 || { echo "FAIL remapd never got the remote: $(state)"; exit 1; }
PID=$(sh_ "cat $T/remapd.pid")
sleep 1; press rem 172
check "Home is remapped at the start" "$(remaps)" 1
for cyc in 1 2; do
    dev_kill rem; sleep 3
    checkre "cycle $cyc: remote away, amzkeyboard is not taken for it" "$(state)" '^notfound .*amzkeyboard'
    dev_start rem "$REMOTE"
    wait_state "$USABLE$REMOTE" 20; checkre "cycle $cyc: the remote is taken again when it returns" "$(state)" "$USABLE$REMOTE"
    mark; press rem 108; press rem 172; press rem 172
    check "cycle $cyc: both Home presses after the return are remapped" "$(remaps)" 2
done
dev_kill rem; sleep 2

echo "-- 2. a remote of another name"
dev_start rem AR --id 0171:0419
wait_state "${USABLE}AR" 60; checkre "an Amazon Bluetooth remote called AR is taken" "$(state)" "${USABLE}AR"
mark; press rem 172; check "its Home is remapped" "$(remaps)" 1
dev_kill rem; sleep 3
checkre "AR away: amzkeyboard is still not taken" "$(state)" '^notfound '

echo "-- 3. a held guess is given up for a better device"
dev_start ir ir-keypad --bus host --id 0001:0001 --keys remote   # plain hardware with remote buttons: taken as a guess
wait_state "${USABLE}ir-keypad" 60; checkre "with nothing better, the infrared guess is taken" "$(state)" "${USABLE}ir-keypad"
dev_start rem "$REMOTE"
wait_state "$USABLE$REMOTE" 60; checkre "the remote replaces the guess as soon as it turns up" "$(state)" "$USABLE$REMOTE"
mark; press rem 108; press rem 172; check "and its Home is remapped" "$(remaps)" 1
dev_kill rem; dev_kill ir; sleep 3

echo "-- 4. presses decide; typing does not"
checkre "nothing held again" "$(state)" '^notfound '
press akb 16; press akb 57; sleep 1                           # q, space: someone typing in the phone app
checkre "typing on amzkeyboard does not make it the remote" "$(state)" '^notfound '
press akb 108 2                                               # arrow down: used as a remote
wait_state "${USABLE}amzkeyboard" 60; checkre "arrows pressed on amzkeyboard: it is the remote now" "$(state)" "${USABLE}amzkeyboard"
mark; press akb 172; check "and its Home is remapped" "$(remaps)" 1
dev_start rem "$REMOTE"
wait_state "$USABLE$REMOTE" 60; checkre "the real remote takes over again when it returns" "$(state)" "$USABLE$REMOTE"
mark; press rem 108; press rem 172; check "and is remapped" "$(remaps)" 1

echo "-- 5. afterwards"
check "Android has no key left down" "$(sh_ "dumpsys input" | grep -c 'KeyDowns: [1-9]')" 0
check "one remapd, still running" "$(sh_ "[ -d /proc/$PID ] && echo yes")" yes
[ $fail = 0 ] && echo "SWITCHTEST OK" || { echo "SWITCHTEST FAILED"; sh_ "logcat -d -s RemapD" | grep " $PID " | grep -v -e ' KEY ' -e ' UP ' | tail -25; }
exit $fail
