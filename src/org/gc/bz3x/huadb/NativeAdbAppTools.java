package org.gc.bz3x.huadb;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Small local-ADB workflows used by the app-management section. */
final class NativeAdbAppTools {
    interface Progress { void report(String value); }

    static final String FIREWALL_PACKAGE = "com.gxa.firewall";
    private static final String[] SAFE_RUNTIME_PERMISSIONS = {
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.READ_MEDIA_AUDIO",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE"
    };

    private final Context context;
    private final Progress progress;
    private final File adb;

    NativeAdbAppTools(Context context, Progress progress) {
        this.context = context.getApplicationContext();
        this.progress = progress;
        this.adb = new File(context.getApplicationInfo().nativeLibraryDir, "libadb.so");
    }

    void setInternetEnabled(int port, boolean enabled) throws Exception {
        String serial = connect(port);
        report(enabled ? "Снимаем штатное ограничение интернета…" : "Возвращаем штатное ограничение интернета…");
        String command = enabled
                ? "pm disable --user 0 " + FIREWALL_PACKAGE
                : "pm enable --user 0 " + FIREWALL_PACKAGE;
        Result result = run(20, "-s", serial, "shell", command);
        requireOk(result, "переключение com.gxa.firewall");
        Result state = run(20, "-s", serial, "shell", "pm list packages " + (enabled ? "-d " : "-e ") + FIREWALL_PACKAGE);
        requireOk(state, "проверка com.gxa.firewall");
        if (state.output.contains("package:" + FIREWALL_PACKAGE)) {
            report(enabled ? "Интернет для приложений включён." : "Штатное ограничение интернета восстановлено.");
            return;
        }
        throw new IllegalStateException("состояние com.gxa.firewall не подтвердилось");
    }

    void grantSafePermissions(int port, String packageName, String[] requestedPermissions) throws Exception {
        if (!isPackageName(packageName)) throw new IllegalArgumentException("некорректное имя пакета");
        String serial = connect(port);
        List<String> requested = requestedPermissions == null ? new ArrayList<>() : Arrays.asList(requestedPermissions);
        int granted = 0;
        for (String permission : SAFE_RUNTIME_PERMISSIONS) {
            if (!requested.contains(permission)) continue;
            report("Выдаём " + permission.substring(permission.lastIndexOf('.') + 1) + "…");
            Result result = run(20, "-s", serial, "shell", "pm grant --user 0 " + packageName + " " + permission);
            if (result.code == 0) granted++;
        }
        report("Разрешаем фоновую работу…");
        requireOk(run(20, "-s", serial, "shell", "appops set " + packageName + " RUN_IN_BACKGROUND allow"), "RUN_IN_BACKGROUND");
        requireOk(run(20, "-s", serial, "shell", "appops set " + packageName + " RUN_ANY_IN_BACKGROUND allow"), "RUN_ANY_IN_BACKGROUND");
        Result verify = run(20, "-s", serial, "shell", "dumpsys package " + packageName);
        requireOk(verify, "проверка пакета");
        for (String permission : SAFE_RUNTIME_PERMISSIONS) {
            if (!requested.contains(permission)) continue;
            if (!verify.output.contains(permission + ": granted=true")) {
                throw new IllegalStateException("не подтвердилось право " + permission);
            }
        }
        report("Готово: безопасные права выданы" + (granted > 0 ? " (" + granted + ")" : "") + ", фоновая работа разрешена.");
    }

    private String connect(int port) throws Exception {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("порт Wi-Fi ADB не найден");
        if (!adb.isFile() || !adb.canExecute()) throw new IllegalStateException("встроенный adb недоступен");
        String serial = "127.0.0.1:" + port;
        run(20, "start-server");
        Result connected = run(20, "connect", serial);
        if (connected.code != 0 || (!connected.output.contains("connected to") && !connected.output.contains("already connected"))) {
            throw new IllegalStateException("adb connect: " + compact(connected.output));
        }
        Result identity = run(20, "-s", serial, "shell", "id");
        if (identity.code != 0 || !identity.output.contains("uid=2000(shell)")) {
            throw new IllegalStateException("не подтверждён adb shell: " + compact(identity.output));
        }
        return serial;
    }

    private Result run(int timeoutSeconds, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(adb.getAbsolutePath());
        command.addAll(Arrays.asList(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(context.getFilesDir()).redirectErrorStream(true);
        builder.environment().put("HOME", context.getFilesDir().getAbsolutePath());
        builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1 && output.size() < 262144) output.write(buffer, 0, count);
            } catch (Exception ignored) { }
        }, "embedded-adb-app-tools");
        reader.start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            reader.join(2000);
            throw new IllegalStateException("таймаут adb");
        }
        reader.join(2000);
        return new Result(process.exitValue(), output.toString("UTF-8").trim());
    }

    private void requireOk(Result result, String action) {
        if (result.code != 0) throw new IllegalStateException(action + ": " + compact(result.output));
    }
    private void report(String value) { if (progress != null) progress.report(value); }
    private static boolean isPackageName(String value) { return value != null && value.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"); }
    private static String compact(String value) {
        String clean = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() > 240 ? clean.substring(clean.length() - 240) : clean;
    }
    private static final class Result {
        final int code; final String output;
        Result(int code, String output) { this.code = code; this.output = output; }
    }
}
