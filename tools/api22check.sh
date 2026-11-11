#!/bin/bash
# Compiles the app against the Android 5.1 (API 22, Fire OS 5) android.jar, java.* included, and fails on any call
# that doesn't exist there unless its line is visibly guarded. Catches java.util.Base64 / checkSelfPermission /
# List.sort style breakage on Fire OS 5/6 that compiling against android-34 can't see.
# Needs the platform jar: https://dl.google.com/android/repository/android-22_r02.zip -> $API22_JAR
cd "$(dirname "$0")/.."
JAR=${API22_JAR:-/opt/android-sdk/dl22/android-5.1.1/android.jar}
[ -f "$JAR" ] || { echo "api22check: SKIPPED (no $JAR)"; exit 0; }
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
# Allowed: lines behind an SDK_INT check, and the API 26 notification block of BridgeService (inside SDK_INT >= 26).
ALLOW='SDK_INT >=|modern|TYPE_APPLICATION_OVERLAY|NotificationChannel|Notification\.Builder\(this|getSystemService\(NotificationManager\.class\)'
BAD=$(javac -source 8 -target 8 -nowarn -Xmaxerrs 1000 -bootclasspath "$JAR" -d "$OUT" $(find src -name '*.java') 2>&1 \
  | grep -A1 'error:' | grep -v '^--' | paste - - | grep -vE "$ALLOW")
if [ -n "$BAD" ]; then echo "api22check: FAIL, not available on Fire OS 5 (API 22):"; echo "$BAD"; exit 1; fi
echo "api22check: OK"
