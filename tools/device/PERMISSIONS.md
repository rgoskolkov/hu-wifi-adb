# App permissions helper

`app-permissions` inspects and changes explicitly selected permissions for an
installed non-system package through a source-built Android helper.

For this app the relevant command is:

`DEVICE_SERIAL` is the exact device identifier from the first column of
`adb devices`. For example, if `adb devices` shows
`192.168.1.123:5555    device`, use `--serial 192.168.1.123:5555`. For a USB
device it may be a value such as `ABC123456`.

```sh
./tools/device/app-permissions inspect org.gc.bz3x.huadb --serial DEVICE_SERIAL --json
./tools/device/app-permissions grant org.gc.bz3x.huadb \
  android.permission.WRITE_SECURE_SETTINGS \
  --serial DEVICE_SERIAL --json
./tools/device/app-permissions inspect org.gc.bz3x.huadb --serial DEVICE_SERIAL --json
```

The grant operation validates that the permission is declared and verifies the
state after the change. It does not treat runtime permissions, AppOps,
Accessibility, Notification Listener access, or Android roles as equivalent.

The tool also supports `grant-runtime`, `revoke`, `appops-get`, and a
single explicit `appops-set --op NAME --mode MODE`. It never performs a bulk
AppOps allow/reset.

Remote ADB through SSH uses the same `--ssh`, `--ssh-key`, and `--ssh-port`
options as `install-apk`.

Reports are stored under ignored `artifacts/installations/` and may contain
device-specific information.
