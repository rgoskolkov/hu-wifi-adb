# Toyota bZ3X HU Wi-Fi ADB

Minimal Android app for manually enabling Wi-Fi ADB on the Toyota bZ3X Android 11 head unit.

The repository intentionally contains only the working Wi-Fi path:

- toggles `Settings.Global.adb_wifi_enabled` after an explicit user action;
- uses a narrowly scoped Accessibility service for the exact SystemUI prompt
  `Allow wireless debugging on this network?`;
- discovers `_adb-tls-connect._tcp.` through Android NSD/mDNS;
- shows and copies `adb connect IP:PORT`;
- can restart only its own Accessibility service while preserving other enabled services.

## Requirements

- Android 11 / API 30 target device;
- an already authorized ADB connection for the initial installation;
- Python 3.10+;
- Android Platform Tools (`adb`);
- JDK;
- Android SDK with a platform jar, `aapt2`, `d8`, `zipalign`, and `apksigner`.

The app requires `WRITE_SECURE_SETTINGS`. The included permission tool grants only the
specific permission you request and verifies the result. Accessibility remains a separate
user-controlled Android setting.

## Build

Set the SDK and a private signing key:

```sh
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export HU_ADB_SIGNING_KEY="/path/to/private-signing.jks"
./build.sh
```

The build expects the signing key password and alias used by the original project
(`android` / `local`) unless you adjust `build.sh`. Private keys are intentionally ignored
by Git and must never be committed.

Output: `out/hu-wifi-adb-v1.apk`.

## Install

Read [tools/device/INSTALL.md](tools/device/INSTALL.md) and
[tools/device/PERMISSIONS.md](tools/device/PERMISSIONS.md) first.

Dry-run (local APK validation only):

```sh
python3 install.py --serial DEVICE_SERIAL --dry-run
```

Install without resetting app data:

```sh
python3 install.py --serial DEVICE_SERIAL
```

For a first installation that does not yet have `WRITE_SECURE_SETTINGS`:

```sh
python3 install.py --serial DEVICE_SERIAL --grant-settings
```

The wrapper uses the source-built PackageInstaller.Session helper in
`tools/device/install-apk` and the verified permission helper in
`tools/device/app-permissions`. It does not call ordinary `adb install` or `pm install`.

## Use

1. Enable the app's Accessibility service `org.gc.bz3x.huadb/.ConsentService`.
2. Open **Toyota bZ3X · Wi-Fi ADB**.
3. Press **Включить Wi-Fi ADB** and confirm.
4. Wait until the app shows `adb connect IP:PORT`.
5. On another machine in the same Wi-Fi network, run that command and verify:

```sh
adb -s IP:PORT shell id
```

6. Disable Wi-Fi ADB in the app after use.

## Package identity and updates

The package is `org.gc.bz3x.huadb`, version `1.0.0` / code `100`.
Because this package name differs from the earlier research build, Android treats it as
a separate application. Its signing key is private and is not part of this repository.

## Status

The Wi-Fi ADB workflow was extracted from a larger research project because this path was
the stable, useful part. The standalone source and installer are host-tested locally.
Installing the standalone v1 on a head unit is a separate runtime verification step.

## License

No open-source license has been selected yet. Copyright remains with the author.
