#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then
  echo "Set ANDROID_SDK_ROOT (or ANDROID_HOME) to your Android SDK." >&2
  exit 2
fi
BT="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/36.0.0}"
JAR="${ANDROID_PLATFORM_JAR:-$SDK/platforms/android-37.0/android.jar}"
SIGNING_KEY="${HU_ADB_SIGNING_KEY:-}"

if [ -z "$SIGNING_KEY" ] || [ ! -f "$SIGNING_KEY" ]; then
  echo "Set HU_ADB_SIGNING_KEY to a private JKS signing key." >&2
  echo "The signing key is intentionally not stored in this repository." >&2
  exit 2
fi

rm -rf out/classes out/dex
mkdir -p out/classes out/dex
"$BT/aapt" package -f -M AndroidManifest.xml -S res -I "$JAR" -F out/resources.apk
find src -name '*.java' -print > out/sources.list
javac -source 8 -target 8 -classpath "$JAR" -d out/classes @out/sources.list
jar cf out/app-classes.jar -C out/classes .
"$BT/d8" --lib "$JAR" --min-api 30 --output out/dex out/app-classes.jar
cp out/resources.apk out/unsigned.apk
(cd out/dex && zip -q ../unsigned.apk classes*.dex)
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk
"$BT/apksigner" sign --ks "$SIGNING_KEY" --ks-pass pass:android --out out/hu-wifi-adb-v1.apk out/aligned.apk
"$BT/apksigner" verify --verbose out/hu-wifi-adb-v1.apk
