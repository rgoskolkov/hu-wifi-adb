# APK installer

`install-apk` installs one signed standalone APK through a source-built
`PackageInstaller.Session` helper. It does not use ordinary `adb install` or
`pm install`, does not automatically uninstall an existing app, and rejects
system packages.

Requirements: macOS/Linux, Python 3.10+, ADB, JDK, and an Android SDK containing
`aapt2`, `d8`, and at least one platform `android.jar`.

## Dry-run

```sh
./tools/device/install-apk app.apk --serial DEVICE_SERIAL --dry-run --json
```

The dry-run validates the APK and installation plan without connecting to ADB.

## Install/update

```sh
./tools/device/install-apk app.apk --serial DEVICE_SERIAL --json
```

The default update preserves application data. Signature/version conflicts are
reported and are not followed by an automatic uninstall.

`--reinstall` is intentionally explicit because it removes the existing
non-system package and its data before installation.

After PackageInstaller reports success, the tool independently verifies the
installed package/version and, when readable, the SHA256 of `base.apk`.

## Optional post-install actions

```sh
./tools/device/install-apk app.apk --serial DEVICE_SERIAL --grant-runtime-permissions
./tools/device/install-apk app.apk --serial DEVICE_SERIAL --launch
```

`--grant-runtime-permissions` only handles declared Android runtime permissions.
It does not grant special access such as Accessibility.

## Remote ADB server over SSH

```sh
./tools/device/install-apk app.apk \
  --ssh user@headunit-host \
  --ssh-key ~/.ssh/headunit \
  --serial DEVICE_SERIAL \
  --json
```

The SSH key is referenced in place and never copied. The tool starts/uses the
remote ADB server through a temporary loopback tunnel and closes the tunnel when
the operation finishes.

## Reports

Run reports are written under ignored `artifacts/installations/`. They can
contain device identifiers and command output and should not be committed.

Exit code `0` means the requested dry-run/install completed and verification
passed. Non-zero exits include the reason in the JSON report; an unknown outcome
after PackageInstaller commit is explicitly marked so the caller does not retry
blindly.
