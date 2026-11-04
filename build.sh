#!/bin/bash
set -e
cd "$(dirname "$0")"

ANDROID_JAR=${ANDROID_JAR:-/opt/android-sdk/platforms/android-34/android.jar}
D8_JAR=${D8_JAR:-/opt/android-sdk/build-tools/35.0.0/lib/d8.jar}
PKG=com.clean.shellbridge
OUT=build
rm -rf "$OUT"; mkdir -p "$OUT/classes"

echo "[0/5] build native remapd (armv7 static)"
( cd native && arm-linux-gnueabihf-gcc -O2 -static -w -o remapd remapd.c && arm-linux-gnueabihf-strip remapd )

echo "[1/5] compile java -> classes (javac against android.jar)"
javac --release 8 -cp "$ANDROID_JAR" -d "$OUT/classes" \
   -nowarn -Xlint:none $(find src -name '*.java')

echo "[2/5] dex classes -> classes.dex (build-tools d8)"
java -cp "$D8_JAR" com.android.tools.r8.D8 \
   --lib "$ANDROID_JAR" --min-api 21 --output "$OUT" \
   $(find "$OUT/classes" -name '*.class')

echo "[3/5] link resources -> base apk (aapt2)"
aapt2 link -o "$OUT/base.apk" \
   -I "$ANDROID_JAR" \
   --manifest AndroidManifest.xml \
   --min-sdk-version 21 --target-sdk-version 30

echo "[4/5] add classes.dex into apk"
cd "$OUT"
cp base.apk app-unsigned.apk
# zip the dex in at the archive root
mkdir -p dexroot && cp classes.dex dexroot/
( cd dexroot && zip -q ../app-unsigned.apk classes.dex )
# native daemon, shipped as a "library" so Android extracts it to nativeLibraryDir (readable by shell)
mkdir -p libroot/lib/armeabi-v7a && cp ../native/remapd libroot/lib/armeabi-v7a/libremapd.so
( cd libroot && zip -q ../app-unsigned.apk lib/armeabi-v7a/libremapd.so )
zipalign -f 4 app-unsigned.apk app-aligned.apk
cd ..

echo "[5/5] sign"
KS=sb.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias sb -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=shellbridge"
fi
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
   --out shellbridge.apk "$OUT/app-aligned.apk"

echo "DONE -> $(pwd)/shellbridge.apk"
