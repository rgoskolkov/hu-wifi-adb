# Toyota bZ3X HU Wi-Fi ADB

Small Android utility for the Toyota bZ3X Android 11 head unit.

The repository intentionally contains only independently useful, understood functionality:

- toggles `Settings.Global.adb_wifi_enabled` after an explicit user action;
- uses its Accessibility service for the exact SystemUI prompt
  `Allow wireless debugging on this network?`;
- discovers `_adb-tls-connect._tcp.` through Android NSD/mDNS;
- shows and copies `adb connect IP:PORT`;
- can restart only its own Accessibility service while preserving other enabled services.
- can switch the **central Android head-unit screen only** to the locale list
  `ru-RU,en-US,zh-CN`, or restore `zh-CN`.
- can use the Toyota bZ3X system `CarTimeSync` service to select a Russian time zone,
  set date/time manually, and enable or disable GPS time synchronization.
- can install and configure the original ClockOverlay 1.4.5 from the app itself through local Wi-Fi ADB;
  ClockOverlay remains a separate application and continues to work if this utility is removed.
- can grant a deliberately limited set of safe manifest permissions to a selected third-party app
  and allow its background AppOps through the head unit's local Wi-Fi ADB;
- can enable internet for third-party apps by disabling the Toyota `com.gxa.firewall` package for
  user 0, or restore the factory restriction by enabling that package again.

Instrument-cluster localization is a separate, more invasive workflow and is intentionally
not included in this application.

## Requirements

- Android 11 / API 30 target device;
- an already authorized ADB connection for the initial installation;
- Python 3.10+;
- Android Platform Tools (`adb`);
- JDK 17;
- Android SDK with a platform jar, `aapt2`, `d8`, `zipalign`, and `apksigner`.

Wi-Fi ADB requires `WRITE_SECURE_SETTINGS`. Central-screen localization requires both
`CHANGE_CONFIGURATION` and the `WRITE_SETTINGS` AppOp. On the tested Toyota bZ3X firmware
`CHANGE_CONFIGURATION` is a grantable development permission, while the OEM
`updatePersistentConfiguration()` implementation also checks `WRITE_SETTINGS`. The included
permission tool changes only these explicitly requested permissions/AppOps and verifies the
result. Accessibility remains a separate user-controlled Android setting.

## Build

For a normal local build, set the Android SDK and run:

```sh
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./build.sh
```

If no signing key is configured, the first build automatically creates
`local/dev-signing.jks` and signs the APK with it. The `local/` directory is ignored by
Git. Keep that file: future APKs signed with the same key can update your previous local
installation. If you delete the key and generate another one, Android will not accept the
new APK as an update of the old installation.

The automatically generated key is intentionally a local development key with a fixed
development password. Do not use it to publish official releases.

For an official/release build, provide a persistent private key instead:

```sh
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export HU_ADB_SIGNING_KEY="/secure/path/release-signing.jks"
export HU_ADB_SIGNING_KEY_PASSWORD="..."
export HU_ADB_SIGNING_KEY_KEY_PASSWORD="..."   # optional; defaults to keystore password
export HU_ADB_SIGNING_KEY_ALIAS="..."          # optional for a single-key keystore
./build.sh
```

Release signing keys are intentionally not stored in this repository.

Output: `out/hu-wifi-adb-v1.apk`.

## Official releases

GitHub Releases are built automatically from tags matching `v*` (for example `v1.0.0`).
The release workflow runs the host tests, builds the APK with the project's persistent
release key, verifies its signature, and publishes both the APK and a SHA256 file.

The release workflow uses these GitHub Actions secrets:

- `RELEASE_KEYSTORE_BASE64` — the release JKS file encoded as one Base64 string;
- `RELEASE_KEYSTORE_PASSWORD` — keystore password;
- `RELEASE_KEY_PASSWORD` — optional key password; when omitted, the keystore password is used;
- `RELEASE_KEY_ALIAS` — optional for a keystore containing a single signing key.

Never commit the release JKS or any of these secret values.

## Install

Read [tools/device/INSTALL.md](tools/device/INSTALL.md) and
[tools/device/PERMISSIONS.md](tools/device/PERMISSIONS.md) first.

`DEVICE_SERIAL` in the commands below is the device identifier shown in the first
column of `adb devices`:

```sh
adb devices
```

For example, if the output contains:

```text
192.168.1.123:5555    device
```

use `--serial 192.168.1.123:5555`. For a USB-connected device the serial may look
like `ABC123456`, in which case use `--serial ABC123456`.

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

To enable the central-screen localization controls as well:

```sh
python3 install.py --serial DEVICE_SERIAL --grant-settings --grant-localization
```

For an already installed app, enable both localization requirements with:

```sh
./tools/device/app-permissions grant org.gc.bz3x.huadb \
  android.permission.CHANGE_CONFIGURATION \
  --serial DEVICE_SERIAL --json
./tools/device/app-permissions appops-set org.gc.bz3x.huadb \
  --op WRITE_SETTINGS --mode allow \
  --serial DEVICE_SERIAL --json
```

The wrapper uses the source-built PackageInstaller.Session helper in
`tools/device/install-apk` and the verified permission helper in
`tools/device/app-permissions`. It does not call ordinary `adb install` or `pm install`.

## Use

1. Enable the app's Accessibility service `org.gc.bz3x.huadb/.ConsentService`.
2. Open **Toyota bZ3X** and choose the required section on the home screen.
3. Open **Подключение**, press **Включить Wi-Fi ADB** and confirm.
4. Wait until the app shows `adb connect IP:PORT`.
5. On another machine in the same Wi-Fi network, run that command and verify:

```sh
adb -s IP:PORT shell id
```

6. Disable Wi-Fi ADB in the app after use.

The home screen is a section catalog rather than one long settings list. Current sections are
**Подключение**, **Экран и язык**, **Дата и время**, and **Приложения**. Only the selected
section is shown; **Все разделы** or the Android Back button returns to the catalog. This
layout keeps future Toyota bZ3X settings and installation tools grouped as the application grows.

The Wi-Fi ADB helper status and restart action live inside **Подключение**, because that
Accessibility helper belongs to the Wi-Fi ADB workflow rather than to a separate service section.

### Central-screen language

The **Центральный экран** card has two radio-button modes and marks the mode that
matches the head unit's current persisted locale list:

- **Русский** applies `ru-RU,en-US,zh-CN`;
- **Китайский** applies `zh-CN`.

If the current locale list matches neither known mode, neither radio button is selected
and the actual locale list is shown explicitly.

Selecting another radio mode only changes the pending choice in the UI. The head-unit
locale is changed only after pressing **Применить**; the button is shown only
while the selected mode differs from the currently persisted mode.

Each action asks for confirmation and verifies the persisted locale list after the change.
This feature changes the Android locale configuration of the central screen. It does not
patch system APKs or firmware and does not perform the separate instrument-cluster
localization procedure.

### Date and time

The **Дата и время** card reads the current Android time zone and the current GPS-sync
state from Toyota's system `com.gxa.car.timesync.TimeSyncService`.

It provides the same Russian time-zone choices used by Toyota bZ3X Tool, from
`Europe/Kaliningrad` through `Asia/Kamchatka`, plus manual date/time selection and a
GPS-sync on/off mode. Selecting any of these values only creates a pending change in the
UI. Nothing is applied until **Применить** is pressed and confirmed.
Pending time changes can also be discarded with **Отмена**. Manual date/time editing uses the standard Android `DatePickerDialog` and `TimePickerDialog`, opened from compact buttons showing the currently selected date and time. The selection remains pending until **Применить** is confirmed. Action buttons use compact content-sized controls instead of full-width bars.

The same section controls the original **ClockOverlay 1.4.5** (`ru.artconcepts.bz3x.clock`).
The exact original APK is bundled into a release build and, on first enable when it is not already
installed, the app connects to the head unit's own Wi-Fi ADB endpoint, pushes the APK and a small
source-built installer helper, and installs it through `PackageInstaller.Session`. The app does
not use `adb install` or `pm install` for this operation.

The embedded ADB executable follows the lightweight LADB approach: the arm64 ADB client is
packaged as a native library and executed locally by the app. The app uses the Wi-Fi ADB port it
already discovered through NSD, so Wi-Fi ADB must be enabled and its endpoint visible before a
ClockOverlay change can be applied. After installation it grants the `SYSTEM_ALERT_WINDOW` AppOp
as the original tool's fallback and adds only
`ru.artconcepts.bz3x.clock/.ClockAccessibilityService` to the existing Accessibility list,
preserving every other enabled service. Disabling the clock only removes that component; it does
not uninstall ClockOverlay. Therefore ClockOverlay is independent and remains installed after
`org.gc.bz3x.huadb` is removed. The clock controls include a third pending mode, **Удалить ClockOverlay**. It does nothing immediately; when **Применить** is confirmed, the app removes the ClockOverlay Accessibility component while preserving every other enabled service, resets its `SYSTEM_ALERT_WINDOW` AppOp to `default`, uninstalls `ru.artconcepts.bz3x.clock`, and verifies that both the package and component are gone.

`clock-assets/ClockOverlay-1.4.5.apk` is intentionally ignored by Git. A build requires the exact
APK with SHA256 `9bddc26c79b96d020abd0003fa92d6546683e28affc466ba5acf15d7a599e24e`, or another path may be
supplied with `CLOCK_OVERLAY_APK`. The bundled arm64 ADB binary is documented under
`vendor/ladb/README.md`, together with its upstream revision, hash and license.

Time and time-zone changes are delegated to the exported Toyota `CarTimeSync` Binder
service, which runs as the system UID and owns the required `SET_TIME` and
`SET_TIME_ZONE` permissions. The standalone app does not request either privileged
permission itself.

### Applications

The **Приложения** section contains two local-ADB workflows extracted from Toyota bZ3X Tool.

**Выдать безопасные права приложению** shows installed non-system applications and grants only
permissions that the selected app actually declares in its manifest. The current safe set covers
notifications, Bluetooth/nearby-device access and media/file access, plus the `RUN_IN_BACKGROUND`
and `RUN_ANY_IN_BACKGROUND` AppOps. Location, microphone, camera, SMS, contacts and phone-related
permissions are intentionally excluded.

**Интернет для приложений** reflects the current state of Toyota's `com.gxa.firewall` package.
Selecting another radio mode is only a pending change. **Применить** disables the firewall package
for user 0 to enable internet for third-party apps, or enables it again to restore the factory
restriction. **Отмена** discards the pending selection. Both operations require Wi-Fi ADB to be
enabled and the local port to have been discovered.

## Package identity and updates

The package is `org.gc.bz3x.huadb`, version `1.4.0` / code `140`.
Because this package name differs from the earlier research build, Android treats it as
a separate application.

Official APKs and self-built APKs can use the same package name, but Android only permits
an update when both APKs are signed by the same key. Therefore a self-built APK signed
with `local/dev-signing.jks` cannot update an official release signed with the project
release key, and vice versa; switching signing identities requires uninstalling the
existing app first.

## Status

The Wi-Fi ADB workflow and simple central-screen locale workflow were extracted from a
larger research project. The locale implementation is a clean source reimplementation of
the statically understood Android configuration change; no Toyota Tool helper binary is
included. Locale switching and the simple time controls are runtime-confirmed on the Toyota
bZ3X head unit. Version 1.3.1 replaces the rejected built-in-clock experiment with the original ClockOverlay
1.4.5. The app now contains an embedded arm64 ADB client and installs/configures that separate APK
through the head unit's local Wi-Fi ADB connection. The main 1.3.1 APK is installed on the tested
head unit and its embedded ADB binary runs there. The in-app first-install path for ClockOverlay
has not yet been runtime-triggered in this build; the head unit currently already has the exact
ClockOverlay 1.4.5 installed.

Version 1.4.0 adds the **Приложения** section. The implementation was reconstructed statically
from Toyota bZ3X Tool 1.7.3: internet access is controlled through `com.gxa.firewall`, while the
safe-permission mode grants only a limited subset of declared permissions and background AppOps.
The 1.4.0 APK builds and passes the host tests, but these two new workflows have not yet been
runtime-verified on the head unit.

## License

No open-source license has been selected yet. Copyright remains with the author.
