# App permissions helper

`app-permissions` inspects and changes explicitly selected permissions for an
installed non-system package through a source-built Android helper.

For this app the relevant command is:

```sh
./tools/device/app-permissions inspect ru.local.huadb --serial DEVICE_SERIAL --json
./tools/device/app-permissions grant ru.local.huadb \
  android.permission.WRITE_SECURE_SETTINGS \
  --serial DEVICE_SERIAL --json
./tools/device/app-permissions inspect ru.local.huadb --serial DEVICE_SERIAL --json
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
