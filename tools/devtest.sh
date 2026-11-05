#!/bin/bash
# devtest.sh <scenario-file> - drive remapd on the stick from a fake uinput remote and report what came out.
#
# Runs on the build machine (needs adb + python3; ./build.sh first so native/remapd + native/fakeremote exist):
#   1. pushes remapd + fakeremote to /data/local/tmp/stickman/test/
#   2. starts fakeremote (uinput "Stickman Test Remote"), stdin fed from a host fifo through `adb shell -T`
#   3. stops the production remapd (pid file; the real remote works natively meanwhile)
#   4. starts the test remapd: test/remapd <production remap.conf> "Stickman Test Remote"
#   5. getevent -lt on the remapd/virtual node, in the background
#   6. feeds the scenario to fakeremote (down/up/tap/sleep lines; see native/fakeremote_parse.h)
#   7. collects SENT lines, virtual-node events and `logcat -s RemapD Stickd`
#   8. ALWAYS (trap) kills the test remapd + fakeremote and restarts the production remapd, then checks it
#      grabbed the real remote
#   9. prints a per-press summary: output keys, actions, latency SENT -> virtual output
# Scenario lines "#expect <regex> [<max_ms>]" are checked against the summary's event lines (time = ms after
# the scenario's first SENT); any miss makes the exit status 1. Raw logs are kept in $DEVTEST_OUT (or a temp dir).
#
# WARNING: the test remapd uses the production remap.conf and the production act/ spool, so scenario "act" rules
# really run on the stick (a Boost really kills apps, sleep_now really sleeps). The test remapd writes its own
# pid file ($T/remapd.pid), so it is never mistaken for the production remapd if this script dies untrapped.
set -u
cd "$(dirname "$0")/.."
SCEN=${1:?usage: tools/devtest.sh <scenario-file>}
[ -f "$SCEN" ] || { echo "no such scenario: $SCEN" >&2; exit 2; }
STICK=${STICK:-192.168.0.221:5555}
ADB="adb -s $STICK"
D=/data/local/tmp/stickman
T=$D/test
FAKE="Stickman Test Remote"
OUT=${DEVTEST_OUT:-$(mktemp -d /tmp/devtest.XXXXXX)}
mkdir -p "$OUT"
FR_BG= GE_BG= STARTED_TEST=0 RESTORED=0

log() { echo "[devtest] $*" >&2; }
sh_() { $ADB shell "$@" </dev/null; }

# remapd pid from the pid file if /proc/<pid>/comm is remapd, else empty
remapd_pid() { sh_ 'P=$(cat '$D'/remapd.pid 2>/dev/null); [ -n "$P" ] && [ "$(cat /proc/$P/comm 2>/dev/null)" = remapd ] && echo $P' | tr -d '\r'; }

# the "remote at" line if a "remote grabbed" follows the last RemapD line matching ERE $1, else empty
grab_after() {
    sh_ 'logcat -d -v monotonic -s RemapD' | tr -d '\r' | awk -v pat="$1" '
        $0 ~ pat { seen = 1; at = ""; g = 0; next }
        seen && /remote at/ { at = $0; sub(/.*RemapD *: */, "", at) }
        seen && /remote grabbed/ { g = 1 }
        END { if (g) print (at == "" ? "remote grabbed" : at) }'
}

restore() {
    [ $RESTORED = 1 ] && return; RESTORED=1
    log "restoring production remapd"
    exec 3>&-                                   # EOF -> fakeremote releases keys, destroys its device, exits
    [ -n "$GE_BG" ] && kill "$GE_BG" 2>/dev/null
    if [ $STARTED_TEST = 1 ]; then
        # kill only the test remapd (cmdline names the fake device; never touch a production one)
        local k
        for k in 1 2 3 4 5 6 7 8 9 10; do
            [ "$(sh_ 'n=0; for P in $(pidof remapd); do grep -q "'"$FAKE"'" /proc/$P/cmdline 2>/dev/null && kill $P && n=1; done; echo $n' | tr -d '\r')" = 1 ] || break
            sleep 0.3
        done
    fi
    # exact matches only: a pkill -f pattern would also match the sh -c running it
    sh_ 'for P in $(pidof getevent 2>/dev/null); do [ "$(tr "\000" " " </proc/$P/cmdline 2>/dev/null | cut -d" " -f1-2)" = "getevent -lt" ] && kill $P; done; true'
    sleep 0.5
    sh_ 'pkill -x fakeremote 2>/dev/null; true'
    [ -n "$FR_BG" ] && kill "$FR_BG" 2>/dev/null
    if [ -z "$(remapd_pid)" ]; then
        sh_ "cd $D && setsid nohup $D/remapd $D/remap.conf </dev/null >/dev/null 2>&1 &"
    fi
    local ok=0 P= comm= grab=
    for i in 1 2 3 4 5 6 7 8 9 10; do
        sleep 0.5
        P=$(remapd_pid)
        grab=; [ -n "$P" ] && grab=$(grab_after "remapd starting pid $P( |\$)")
        if [ -n "$grab" ]; then ok=1; break; fi
    done
    comm=$([ -n "$P" ] && sh_ "cat /proc/$P/comm; tr '\0' ' ' </proc/$P/cmdline" | tr -d '\r' | tr '\n' ' ')
    local fakeleft; fakeleft=$(sh_ 'getevent -i 2>/dev/null' | grep -c "$FAKE")
    echo "RESTORE pid=${P:-none} comm/cmdline: ${comm:-?}"
    if [ -n "$grab" ]; then echo "RESTORE log: $grab, remote grabbed"; else echo "RESTORE log: <no remote grabbed line>"; fi
    echo "RESTORE fake device present: $fakeleft"
    if [ $ok = 1 ] && [ "$fakeleft" = 0 ] && echo "$comm" | grep -q "^remapd $D/remapd "; then echo "RESTORE OK"
    else echo "RESTORE FAILED - check the stick: production remapd must run from $D/remapd"; RC=1; fi
}
RC=0
trap 'restore; exit $RC' EXIT
trap 'RC=130; exit' INT TERM HUP

# 0. connect
su -c 'ip route add 192.168.0.0/24 dev wlan0 scope link src 192.168.0.101' 2>/dev/null
adb connect "$STICK" >/dev/null 2>&1
if ! $ADB get-state 2>/dev/null | grep -q device; then adb disconnect "$STICK" >/dev/null 2>&1; adb connect "$STICK" >/dev/null 2>&1; fi
$ADB get-state 2>/dev/null | grep -q device || { log "stick not reachable"; RESTORED=1; RC=2; exit; }

# 1. push
[ -x native/remapd ] && [ -x native/fakeremote ] || { log "run ./build.sh first"; RESTORED=1; RC=2; exit; }
sh_ "mkdir -p $T"
$ADB push native/remapd native/fakeremote "$T/" >/dev/null || { log "push failed"; RESTORED=1; RC=2; exit; }
sh_ "chmod 755 $T/remapd $T/fakeremote"

# 2. fakeremote (stdin = host fifo held open on fd 3)
rm -f "$OUT/in"; mkfifo "$OUT/in"
$ADB shell -T "$T/fakeremote" <"$OUT/in" >"$OUT/sent.log" 2>"$OUT/fakeremote.err" &
FR_BG=$!
exec 3>"$OUT/in"
for i in $(seq 50); do grep -q READY "$OUT/sent.log" 2>/dev/null && break; sleep 0.1; done
grep -q READY "$OUT/sent.log" || { log "fakeremote did not start: $(cat "$OUT/fakeremote.err")"; RC=1; exit; }
M0=$(sed -n 's/^CLOCK mono=\([0-9]*\).*/\1/p' "$OUT/sent.log")

# 3. stop production remapd
PROD=$(remapd_pid)
if [ -n "$PROD" ]; then
    log "stopping production remapd pid $PROD"
    sh_ "kill $PROD"
    for i in $(seq 20); do sh_ "[ -d /proc/$PROD ]" || break; sleep 0.1; done
else log "no production remapd running (will start one at the end)"; fi

# 4. test remapd on the fake device
STARTED_TEST=1
sh_ "cd $T && rm -f $T/remapd.pid && setsid nohup $T/remapd $D/remap.conf '$FAKE' $T/remapd.pid </dev/null >/dev/null 2>&1 &"
GRAB=
for i in $(seq 30); do
    sleep 0.2
    GRAB=$(grab_after "remapd starting pid [0-9]+ device \"$FAKE\"")
    [ -n "$GRAB" ] && break
done
[ -n "$GRAB" ] || { log "test remapd never grabbed the fake remote"; RC=1; exit; }
VNODE=$(sh_ 'getevent -i 2>/dev/null' | tr -d '\r' | awk '/^add device/{n=$4} /location: "remapd\/virtual"/{print n}' | tail -1)
[ -n "$VNODE" ] || { log "no remapd/virtual node"; RC=1; exit; }
log "test remapd grabbed the fake remote; virtual output = $VNODE"

# 5. watch the virtual node
$ADB shell -T "getevent -lt $VNODE" </dev/null >"$OUT/virt.log" 2>/dev/null &
GE_BG=$!
sleep 0.8

# 6. scenario (fakeremote runs the sleeps itself); quit when done
TOTAL=$(awk '$1=="sleep"{s+=$2} $1=="tap"{s+=($3==""||$3~/^#/)?80:$3} END{print int(s/1000)+5}' "$SCEN")
log "running $SCEN (~${TOTAL}s)"
{ grep -v '^#expect' "$SCEN"; echo quit; } >&3
for i in $(seq $((TOTAL * 10))); do grep -q BYE "$OUT/sent.log" && break; sleep 0.1; done
sleep 1.5                                    # late actions (stickd STATUS after a spooled act)

# 7. collect
kill "$GE_BG" 2>/dev/null; GE_BG=
sh_ 'logcat -d -v monotonic -s RemapD Stickd' | tr -d '\r' | awk -v t="$M0" '$1*1000 >= t-500' >"$OUT/logcat.log"

# 8. restore now (so the summary is the last thing printed); 9. summary
restore
python3 - "$OUT" "$SCEN" <<'PY' || RC=1
import re, sys
out, scen = sys.argv[1], sys.argv[2]
clock = {}
sent = []
for l in open(f"{out}/sent.log"):
    m = re.match(r"CLOCK mono=(\d+) real=(\d+)", l)
    if m: clock = {"mono": int(m[1]), "real": int(m[2])}
    m = re.match(r"SENT (\d+) (\d+) (\d+)", l)
    if m: sent.append((int(m[3]), int(m[1]), int(m[2])))
off = clock["real"] - clock["mono"] if clock else 0
virt = []
for l in open(f"{out}/virt.log", errors="replace"):
    m = re.match(r"\[\s*(\d+)\.(\d+)\]\s+(\S+)\s+(\S+)\s+(\S+)", l)
    if not m or m[3] != "EV_KEY": continue
    ms = int(m[1]) * 1000 + int(m[2]) // 1000
    if ms > 10**11: ms -= off                       # getevent without EVIOCSCLOCKID -> CLOCK_REALTIME
    virt.append((ms, f"OUT {m[4]} {m[5]}"))
acts = []
for l in open(f"{out}/logcat.log", errors="replace"):
    m = re.match(r"\s*(\d+)\.(\d+)\s+\d+\s+\d+\s+\w\s+(\S+)\s*:\s?(.*)", l)
    if not m: continue
    ms = int(m[1]) * 1000 + int(m[2].ljust(3, "0")[:3])
    tag, msg = m[3], m[4]
    if tag == "RemapD" and (" -> " in msg or msg.startswith("act ")) or tag == "Stickd" and ("STATUS" in msg or "act" in msg):
        acts.append((ms, f"{tag}: {msg}"))
downs = [s for s in sent if s[2] == 1]
if not downs:
    print("no presses sent"); sys.exit(1)
t0 = downs[0][0]
events = sorted([(t, f"SENT {c} {'down' if v else 'up'}") for t, c, v in sent] + virt + acts)
print(f"\n===== devtest summary: {scen} =====")
for i, (td, code, _) in enumerate(downs):
    tend = downs[i + 1][0] if i + 1 < len(downs) else float("inf")
    tup = next((t for t, c, v in sent if c == code and v == 0 and t >= td), None)
    held = f"held {tup - td}ms" if tup is not None else "never released"
    print(f"press #{i + 1}: key {code} down @+{td - t0}ms, {held}")
    ov = [(t, e) for t, e in virt if td <= t < tend]
    for t, e in ov:
        rel = f"+{t - td}ms after down" + (f", +{t - tup}ms after up" if tup is not None and t >= tup else "")
        print(f"    {e:32s} {rel}")
    for t, e in [(t, e) for t, e in acts if td <= t < tend]:
        print(f"    {e:32s} +{t - td}ms after down (logcat)")
    if ov: print(f"    latency SENT down -> first virtual output: {ov[0][0] - td}ms")
    else: print("    no virtual output in this press's window")
print("timeline (ms after first SENT):")
for t, e in events:
    if t >= t0 - 50: print(f"  {t - t0:+7d}  {e}")
fails = 0
for l in open(scen):
    m = re.match(r"#expect\s+(.+?)(?:\s+(\d+))?\s*$", l)
    if not m: continue
    rx, lim = re.compile(m[1]), (int(m[2]) if m[2] else None)
    hit = next((t - t0 for t, e in events if rx.search(e) and (lim is None or t - t0 <= lim)), None)
    ok = hit is not None
    fails += not ok
    print(f"EXPECT {'PASS' if ok else 'FAIL'}: /{m[1]}/" + (f" within {lim}ms" if lim else "") + (f" (at +{hit}ms)" if ok else ""))
print("RESULT", "FAIL" if fails else "PASS")
sys.exit(1 if fails else 0)
PY
log "raw logs: $OUT"
