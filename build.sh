#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

# Android Build Tools 30.0.3 are not compatible with current JDK 27. On macOS,
# prefer an installed JDK 17 when JAVA_HOME was not explicitly selected.
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA17="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  if [ -n "$JAVA17" ]; then
    export JAVA_HOME="$JAVA17"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
fi

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then
  echo "Set ANDROID_SDK_ROOT (or ANDROID_HOME) to your Android SDK." >&2
  exit 2
fi
if [ -n "${ANDROID_BUILD_TOOLS:-}" ]; then
  BT="$ANDROID_BUILD_TOOLS"
else
  BT="$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1)"
fi

if [ -n "${ANDROID_PLATFORM_JAR:-}" ]; then
  JAR="$ANDROID_PLATFORM_JAR"
else
  JAR="$(find "$SDK/platforms" -mindepth 2 -maxdepth 2 -name android.jar -type f 2>/dev/null | sort -V | tail -n 1)"
fi

if [ -z "$BT" ] || [ ! -x "$BT/aapt" ] || [ ! -x "$BT/d8" ] || [ ! -x "$BT/zipalign" ] || [ ! -x "$BT/apksigner" ]; then
  echo "Android build-tools with aapt, d8, zipalign and apksigner were not found under $SDK." >&2
  exit 2
fi

if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  echo "No Android platform android.jar was found under $SDK/platforms." >&2
  exit 2
fi

echo "Using Android build-tools: $BT"
echo "Using Android platform: $JAR"
SIGNING_KEY="${HU_ADB_SIGNING_KEY:-local/dev-signing.jks}"
SIGNING_PASSWORD="${HU_ADB_SIGNING_KEY_PASSWORD:-android}"
SIGNING_KEY_PASSWORD="${HU_ADB_SIGNING_KEY_KEY_PASSWORD:-$SIGNING_PASSWORD}"
SIGNING_ALIAS="${HU_ADB_SIGNING_KEY_ALIAS:-}"

if [ -n "${HU_ADB_SIGNING_KEY:-}" ]; then
  if [ ! -f "$SIGNING_KEY" ]; then
    echo "Signing key not found: $SIGNING_KEY" >&2
    exit 2
  fi
  echo "Using external signing key: $SIGNING_KEY"
else
  SIGNING_ALIAS="hu-wifi-adb-dev"
  if [ ! -f "$SIGNING_KEY" ]; then
    KEYTOOL="${KEYTOOL:-${JAVA_HOME:+$JAVA_HOME/bin/keytool}}"
    if [ -z "$KEYTOOL" ]; then
      KEYTOOL="$(command -v keytool || true)"
    fi
    if [ -z "$KEYTOOL" ] || [ ! -x "$KEYTOOL" ]; then
      echo "keytool is required to create the local development signing key." >&2
      exit 2
    fi
    mkdir -p "$(dirname "$SIGNING_KEY")"
    "$KEYTOOL" -genkeypair -keystore "$SIGNING_KEY" -storepass "$SIGNING_PASSWORD" -keypass "$SIGNING_KEY_PASSWORD" -alias "$SIGNING_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Toyota bZ3X HU Wi-Fi ADB Local Development,O=Local Development"
    echo "Created local development signing key: $SIGNING_KEY"
    echo "Keep this file if you want future local builds to update the same installed app."
  else
    echo "Using local development signing key: $SIGNING_KEY"
  fi
fi

CLOCK_APK="${CLOCK_OVERLAY_APK:-clock-assets/ClockOverlay-1.4.5.apk}"
CLOCK_SHA256="9bddc26c79b96d020abd0003fa92d6546683e28affc466ba5acf15d7a599e24e"
LADB_BINARY="${LADB_BINARY:-vendor/ladb/arm64-v8a/libadb.so}"
LADB_SHA256="47ea035fa5ed57f6149a2b025bbbd4b21584c355c05d0400416804715e4c12de"

check_sha256() {
  local path="$1" expected="$2" label="$3" actual
  if [ ! -f "$path" ]; then
    echo "$label not found: $path" >&2
    exit 2
  fi
  actual="$(shasum -a 256 "$path" | awk '{print $1}')"
  if [ "$actual" != "$expected" ]; then
    echo "$label SHA256 mismatch: $actual (expected $expected)" >&2
    exit 2
  fi
}

check_sha256 "$CLOCK_APK" "$CLOCK_SHA256" "ClockOverlay 1.4.5 APK"
check_sha256 "$LADB_BINARY" "$LADB_SHA256" "LADB arm64 adb"

rm -rf out/classes out/dex out/helper-classes out/helper-dex out/assets out/native
mkdir -p out/classes out/dex out/helper-classes out/helper-dex out/assets/clock out/native/lib/arm64-v8a

# Build the same source-controlled PackageInstaller.Session helper used by the
# host installer. The app invokes it under adb shell via app_process; it never
# falls back to `adb install` or `pm install`.
javac -source 8 -target 8 -classpath "$JAR" -d out/helper-classes tools/device/apk-installer/LocalInstall.java
find out/helper-classes -name '*.class' -print > out/helper-classes.list
"$BT/d8" --lib "$JAR" --min-api 28 --output out/helper-dex @out/helper-classes.list
(cd out/helper-dex && zip -q ../assets/clock/LocalInstall.apk classes*.dex)
cp "$CLOCK_APK" out/assets/clock/ClockOverlay-1.4.5.apk
cp "$LADB_BINARY" out/native/lib/arm64-v8a/libadb.so

"$BT/aapt" package -f -M AndroidManifest.xml -S res -A out/assets -I "$JAR" -F out/resources.apk
find src -name '*.java' -print > out/sources.list
javac -source 8 -target 8 -classpath "$JAR" -d out/classes @out/sources.list
jar cf out/app-classes.jar -C out/classes .
"$BT/d8" --lib "$JAR" --min-api 30 --output out/dex out/app-classes.jar
cp out/resources.apk out/unsigned.apk
(cd out/dex && zip -q ../unsigned.apk classes*.dex)
(cd out/native && zip -qr ../unsigned.apk lib)
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk
SIGN_ARGS=(--ks "$SIGNING_KEY" --ks-pass "pass:$SIGNING_PASSWORD" --key-pass "pass:$SIGNING_KEY_PASSWORD")
if [ -n "$SIGNING_ALIAS" ]; then
  SIGN_ARGS+=(--ks-key-alias "$SIGNING_ALIAS")
fi
"$BT/apksigner" sign "${SIGN_ARGS[@]}" --out out/hu-wifi-adb-v1.apk out/aligned.apk
"$BT/apksigner" verify --verbose out/hu-wifi-adb-v1.apk
