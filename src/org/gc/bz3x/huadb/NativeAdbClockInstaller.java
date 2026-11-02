package org.gc.bz3x.huadb;

import android.content.ComponentName;
import android.content.Context;
import android.content.res.AssetManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Installs/configures the original ClockOverlay through the bundled native adb client. */
final class NativeAdbClockInstaller {
    interface Progress { void report(String value); }

    static final String CLOCK_PACKAGE = "ru.artconcepts.bz3x.clock";
    static final String CLOCK_COMPONENT = "ru.artconcepts.bz3x.clock/.ClockAccessibilityService";
    static final String CLOCK_VERSION = "1.4.5";
    private static final String CLOCK_ASSET = "clock/ClockOverlay-1.4.5.apk";
    private static final String HELPER_ASSET = "clock/LocalInstall.apk";
    private static final String CLOCK_SHA256 = "9bddc26c79b96d020abd0003fa92d6546683e28affc466ba5acf15d7a599e24e";
    private static final String REMOTE_CLOCK = "/data/local/tmp/hu-bz3x-clock-overlay.apk";
    private static final String REMOTE_HELPER = "/data/local/tmp/hu-bz3x-clock-installer.apk";

    private final Context context;
    private final Progress progress;
    private final File adb;

    NativeAdbClockInstaller(Context context, Progress progress) {
        this.context = context.getApplicationContext();
        this.progress = progress;
        this.adb = new File(context.getApplicationInfo().nativeLibraryDir, "libadb.so");
    }

    void setEnabled(int port, boolean enabled) throws Exception {
        String serial = connect(port);

        if (enabled) {
            ensureClockInstalled(serial);
            report("Настраиваем ClockOverlay 1.4.5…");
            requireOk(run(20, "-s", serial, "shell", "appops set " + CLOCK_PACKAGE + " SYSTEM_ALERT_WINDOW allow"), "appops");
        }
        configureAccessibility(serial, enabled);
        if (enabled) verifyClockPackage(serial);
        report(enabled ? "ClockOverlay 1.4.5 установлен и включён." : "ClockOverlay выключен; приложение оставлено установленным.");
    }

    void removeCompletely(int port) throws Exception {
        String serial = connect(port);
        report("Отключаем ClockOverlay…");
        configureAccessibility(serial, false);

        Result current = run(20, "-s", serial, "shell", "dumpsys package " + CLOCK_PACKAGE);
        boolean installed = current.code == 0 && current.output.contains("Package [" + CLOCK_PACKAGE + "]");
        if (installed) {
            report("Сбрасываем настройки ClockOverlay…");
            requireOk(run(20, "-s", serial, "shell",
                    "appops set " + CLOCK_PACKAGE + " SYSTEM_ALERT_WINDOW default"), "сброс SYSTEM_ALERT_WINDOW");
            report("Удаляем ClockOverlay 1.4.5…");
            Result uninstall = run(60, "-s", serial, "shell", "pm uninstall " + CLOCK_PACKAGE);
            if (uninstall.code != 0 || !uninstall.output.contains("Success")) {
                throw new IllegalStateException("удаление ClockOverlay: " + compact(uninstall.output));
            }
        }

        Result verify = run(20, "-s", serial, "shell", "pm path " + CLOCK_PACKAGE);
        if (verify.code == 0 && verify.output.contains("package:")) {
            throw new IllegalStateException("ClockOverlay остался установлен после удаления");
        }
        Result accessibility = run(20, "-s", serial, "shell", "settings get secure enabled_accessibility_services");
        requireOk(accessibility, "проверка Accessibility");
        for (String entry : accessibility.output.trim().split(":")) {
            if (isClockComponent(entry.trim())) {
                throw new IllegalStateException("ClockAccessibilityService остался включён после удаления");
            }
        }
        report("ClockOverlay полностью удалён; его настройки сброшены.");
    }

    private String connect(int port) throws Exception {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("порт Wi-Fi ADB не найден");
        if (!adb.isFile() || !adb.canExecute()) throw new IllegalStateException("встроенный adb недоступен");
        String serial = "127.0.0.1:" + port;
        report("Подключаем локальный ADB…");
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

    private void ensureClockInstalled(String serial) throws Exception {
        Result current = run(20, "-s", serial, "shell", "dumpsys package " + CLOCK_PACKAGE);
        if (current.code == 0 && current.output.contains("versionName=" + CLOCK_VERSION) && current.output.contains("versionCode=14")) {
            report("ClockOverlay 1.4.5 уже установлен.");
            return;
        }

        File dir = new File(context.getFilesDir(), "clock-installer");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("не удалось создать каталог установщика");
        File clock = new File(dir, "ClockOverlay-1.4.5.apk");
        File helper = new File(dir, "LocalInstall.apk");
        copyAsset(CLOCK_ASSET, clock);
        if (!CLOCK_SHA256.equalsIgnoreCase(sha256(clock))) throw new SecurityException("ClockOverlay asset SHA256 не совпал");
        copyAsset(HELPER_ASSET, helper);

        report("Передаём оригинальный ClockOverlay в ADB shell…");
        requireOk(run(45, "-s", serial, "push", clock.getAbsolutePath(), REMOTE_CLOCK), "adb push ClockOverlay");
        requireOk(run(30, "-s", serial, "push", helper.getAbsolutePath(), REMOTE_HELPER), "adb push installer");
        try {
            report("Устанавливаем ClockOverlay через PackageInstaller.Session…");
            String runId = "clock-" + Long.toHexString(System.nanoTime());
            String command = "CLASSPATH=" + REMOTE_HELPER + " /system/bin/app_process /system/bin LocalInstall "
                    + REMOTE_CLOCK + " " + CLOCK_PACKAGE + " 90 " + runId;
            Result install = run(110, "-s", serial, "shell", command);
            if (install.code != 0 || !install.output.contains("\"event\":\"result\"") || !install.output.contains("\"status\":0")) {
                throw new IllegalStateException("PackageInstaller: " + compact(install.output));
            }
            verifyClockPackage(serial);
        } finally {
            run(15, "-s", serial, "shell", "rm -f " + REMOTE_CLOCK + " " + REMOTE_HELPER);
        }
    }

    private void verifyClockPackage(String serial) throws Exception {
        Result verify = run(20, "-s", serial, "shell", "dumpsys package " + CLOCK_PACKAGE);
        if (verify.code != 0 || !verify.output.contains("versionName=" + CLOCK_VERSION) || !verify.output.contains("versionCode=14")) {
            throw new IllegalStateException("ClockOverlay 1.4.5 не подтвердился после установки");
        }
    }

    private void configureAccessibility(String serial, boolean enabled) throws Exception {
        Result currentResult = run(20, "-s", serial, "shell", "settings get secure enabled_accessibility_services");
        requireOk(currentResult, "чтение Accessibility");
        String current = currentResult.output.trim();
        if ("null".equals(current) || "undefined".equals(current)) current = "";
        List<String> entries = new ArrayList<>();
        if (!current.isEmpty()) {
            for (String entry : current.split(":")) {
                String value = entry.trim();
                if (!value.isEmpty() && !isClockComponent(value)) entries.add(value);
            }
        }
        if (enabled) entries.add(CLOCK_COMPONENT);
        String updated = join(entries, ":");
        requireOk(run(20, "-s", serial, "shell", "settings put secure enabled_accessibility_services '" + updated + "'"), "запись Accessibility");
        if (enabled) requireOk(run(20, "-s", serial, "shell", "settings put secure accessibility_enabled 1"), "включение Accessibility");
        Result verify = run(20, "-s", serial, "shell", "settings get secure enabled_accessibility_services");
        requireOk(verify, "проверка Accessibility");
        boolean present = false;
        for (String entry : verify.output.trim().split(":")) {
            if (isClockComponent(entry.trim())) {
                present = true;
                break;
            }
        }
        if (present != enabled) throw new IllegalStateException("состояние ClockAccessibilityService не подтвердилось");
    }

    private static boolean isClockComponent(String value) {
        ComponentName actual = ComponentName.unflattenFromString(value);
        ComponentName expected = ComponentName.unflattenFromString(CLOCK_COMPONENT);
        return actual != null && expected != null && actual.equals(expected);
    }

    private void copyAsset(String name, File destination) throws Exception {
        AssetManager assets = context.getAssets();
        try (InputStream in = assets.open(name); FileOutputStream out = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            out.getFD().sync();
        }
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
        }, "embedded-adb-output");
        reader.start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            reader.join(2000);
            throw new IllegalStateException("таймаут adb: " + join(command, " "));
        }
        reader.join(2000);
        return new Result(process.exitValue(), output.toString("UTF-8").trim());
    }

    private void requireOk(Result result, String action) {
        if (result.code != 0) throw new IllegalStateException(action + ": " + compact(result.output));
    }

    private void report(String value) { if (progress != null) progress.report(value); }
    private static String compact(String value) {
        String clean = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() > 240 ? clean.substring(clean.length() - 240) : clean;
    }
    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder value = new StringBuilder();
        for (byte b : digest.digest()) value.append(String.format(Locale.US, "%02x", b & 255));
        return value.toString();
    }
    private static String join(List<?> values, String separator) {
        StringBuilder out = new StringBuilder();
        for (Object value : values) { if (out.length() > 0) out.append(separator); out.append(value); }
        return out.toString();
    }
    private static final class Result {
        final int code; final String output;
        Result(int code, String output) { this.code = code; this.output = output; }
    }
}
