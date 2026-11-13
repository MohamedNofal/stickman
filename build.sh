#!/bin/bash
set -e
cd "$(dirname "$0")"

ANDROID_JAR=${ANDROID_JAR:-/opt/android-sdk/platforms/android-34/android.jar}
D8_JAR=${D8_JAR:-/opt/android-sdk/build-tools/35.0.0/lib/d8.jar}
PKG=com.clean.stickman
EDITION=${1:-full}          # "./build.sh appstore" -> the Amazon Appstore edition (see core/Edition.java)
OUT=build
APK=stickman.apk
SRC=src
MANIFEST=AndroidManifest.xml
TARGET_SDK=30
rm -rf "$OUT"; mkdir -p "$OUT/classes"
if [ "$EDITION" = appstore ]; then
  STORE_PKG=$(grep -o 'STORE_PKG = "[^"]*"' src/com/clean/stickman/core/Edition.java | cut -d'"' -f2)
  APK=stickman-appstore.apk
  SRC=$OUT/src-appstore
  cp -r src "$SRC"
  # edition flag on; every runtime use of the application id (string literals) moves to the store package.
  # Java packages (com.clean.stickman.*) stay; ".FadeReceiver" component names become fully qualified.
  sed -i 's/STORE = false;/STORE = true;/' "$SRC/com/clean/stickman/core/Edition.java"
  grep -rl 'com\.clean\.stickman' "$SRC" | xargs sed -i \
    -e "s#\"com\.clean\.stickman\"#\"$STORE_PKG\"#g" \
    -e "s#com\.clean\.stickman/\.FadeReceiver#$STORE_PKG/com.clean.stickman.FadeReceiver#g"
  RENAME="--rename-manifest-package $STORE_PKG"
  # Amazon now requires Fire TV apps to target API 34. On Fire OS 7/8 (API 28-30) nothing changes; on an Android 14
  # Fire TV the bridge's foreground service needs a type: VPN apps use systemExempted.
  TARGET_SDK=34
  MANIFEST=$OUT/AndroidManifest-appstore.xml
  sed -e 's#<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />#&\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED" />#' \
      -e 's#<service android:name=".BridgeService" android:exported="false"#& android:foregroundServiceType="systemExempted"#' \
      AndroidManifest.xml > "$MANIFEST"
  echo "[edition] appstore -> $STORE_PKG"
fi

echo "[0/5] build native remapd (armv7 static)"
( cd native && arm-linux-gnueabihf-gcc -O2 -static -w -o remapd remapd.c layers.c && arm-linux-gnueabihf-strip remapd )
# dev-only test remote for tools/devtest.sh (NOT packaged in the APK)
( cd native && arm-linux-gnueabihf-gcc -O2 -static -w -o fakeremote fakeremote.c && arm-linux-gnueabihf-strip fakeremote )

echo "[1/5] compile java -> classes (javac against android.jar)"
javac --release 8 -cp "$ANDROID_JAR" -d "$OUT/classes" \
   -nowarn -Xlint:none $(find "$SRC" -name '*.java')

echo "[2/5] dex classes -> classes.dex (build-tools d8)"
java -cp "$D8_JAR" com.android.tools.r8.D8 \
   --lib "$ANDROID_JAR" --min-api 21 --output "$OUT" \
   $(find "$OUT/classes" -name '*.class')

echo "[3/5] link resources -> base apk (aapt2)"
aapt2 compile --dir res -o "$OUT/res.zip"
aapt2 link -o "$OUT/base.apk" "$OUT/res.zip" \
   -I "$ANDROID_JAR" \
   --manifest "$MANIFEST" \
   --min-sdk-version 21 --target-sdk-version $TARGET_SDK $RENAME

echo "[4/5] add classes.dex into apk"
cd "$OUT"
cp base.apk app-unsigned.apk
# zip the dex in at the archive root
mkdir -p dexroot && cp classes.dex dexroot/
( cd dexroot && zip -q ../app-unsigned.apk classes.dex )
# native daemon, shipped as a "library" so Android extracts it to nativeLibraryDir (readable by shell)
mkdir -p libroot/lib/armeabi-v7a && cp ../native/remapd libroot/lib/armeabi-v7a/libremapd.so
# static toybox (0BSD, native/toybox/): the setup script installs it where the system has no such tools (Fire OS 5)
cp ../native/toybox/toybox libroot/lib/armeabi-v7a/libtoybox.so
( cd libroot && zip -q ../app-unsigned.apk lib/armeabi-v7a/libremapd.so lib/armeabi-v7a/libtoybox.so )
zipalign -f 4 app-unsigned.apk app-aligned.apk
cd ..

echo "[5/5] sign"
KS=sb.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias sb -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=shellbridge"
fi
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
   --out "$APK" "$OUT/app-aligned.apk"

echo "DONE -> $(pwd)/$APK"
