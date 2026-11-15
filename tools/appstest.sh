#!/bin/bash
# appstest.sh - drive the app installer (daemon/AppInstaller) from the build machine and show what it logs.
#
# Sends the same spool actions the Apps page sends (core/Actions: app_install, app_get, app_confirm, app_cancel,
# app_update, app_remove, app_auto, apps_refresh) and follows stickd's "STATUS app:..." / "STATUS apps ..." lines
# until the job ends. stickd must be running on the device (Stickman set up and ON, GitHub edition).
#
# Usage: STICK=<adb serial> tools/appstest.sh <command>
#   install <catalogue id> <package>       e.g. install aerialviews com.neilturner.aerialviews
#   get <code | https link | owner/name>   downloads it and stops at the question ("state=confirm")
#   confirm | cancel                       answers the question of the last get
#   update <package> | remove <package> | auto <package> 0|1 | refresh
#   catalog <url> | catalog -              point the daemon and the app at another catalogue / back to the published one
#   list                                   what the daemon tracks (apps.list) and what is in its download folder
#   watch                                  only follow the log
# WAIT=<seconds> (default 900) is how long a job is followed.
#
# WARNING: install, confirm, update and remove really change what is installed on the device.
set -u
STICK=${STICK:?set STICK to the adb serial}
ADB="adb -s $STICK"
D=/data/local/tmp/stickman
WAIT=${WAIT:-900}
cmd=${1:-}; shift || true

# One spool line, written the way core/cmd/RemoteCmds.act writes it (.tmp, then renamed to .act). The line goes in
# on stdin, so links with & and ? need no quoting.
act() {
    printf '%s\n' "$1" | $ADB shell 'd='$D'/act; mkdir -p $d; t=$(date +%s%N); case $t in *N) t=${t%N}000;; *) t=${t%??????};; esac; n=$d/$t-$$-$RANDOM; cat > $n.tmp && chmod 644 $n.tmp && mv $n.tmp $n.act' \
        || { echo "could not write the action" >&2; exit 2; }
}

# Follows the installer's lines from device time $1 on; ends at an outcome for key $2 (empty: until WAIT is over).
follow() {
    local since=$1 key=$2
    timeout "$WAIT" $ADB shell "logcat -v time -s Stickd -T '$since'" </dev/null | tr -d '\r' | awk -v key="$key" '
        /STATUS app/ { sub(/^.*STATUS /, "STATUS "); print; fflush() }
        key != "" && $0 ~ ("STATUS app:" key " state=(installed|removed|error|uptodate|confirm)") { exit }'
}

now() { $ADB shell 'date "+%m-%d %H:%M:%S.000"' </dev/null | tr -d '\r'; }

run() { local since; since=$(now); act "$1"; follow "$since" "$2"; }

case "$cmd" in
    install) [ $# -ge 2 ] || { echo "install <catalogue id> <package>" >&2; exit 2; }; run "app_install $1 $2" "$2" ;;
    get)     [ $# -ge 1 ] || { echo "get <code | https link | owner/name>" >&2; exit 2; }; run "app_get $1" link ;;
    confirm) run "app_confirm" link ;;
    cancel)  act "app_cancel"; echo "cancelled" ;;
    update)  [ $# -ge 1 ] || exit 2; run "app_update $1" "$1" ;;
    remove)  [ $# -ge 1 ] || exit 2; run "app_remove $1" "$1" ;;
    auto)    [ $# -ge 2 ] || exit 2; since=$(now); act "app_auto $1 $2"; WAIT=8 follow "$since" "" ;;
    refresh) since=$(now); act "apps_refresh"; WAIT=${WAIT_REFRESH:-20} follow "$since" "" ;;
    catalog)
        [ $# -ge 1 ] || { echo "catalog <url> | catalog -" >&2; exit 2; }
        if [ "$1" = - ]; then $ADB shell 'setprop debug.stickman.catalog ""'; else $ADB shell "setprop debug.stickman.catalog '$1'"; fi
        echo "debug.stickman.catalog = '$($ADB shell getprop debug.stickman.catalog </dev/null | tr -d '\r')' (the daemon reads it at its next catalogue fetch, the app when the Apps page is next shown)" ;;
    list)    $ADB shell "echo '--- apps.list (package, source, automatic, tag)'; cat $D/apps.list 2>/dev/null; echo '--- downloads'; ls -la $D/apps 2>/dev/null" </dev/null ;;
    watch)   follow "$(now)" "" ;;
    *)       sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
