import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local, source-built PackageInstaller client. Not an installed Android application. */
public final class LocalInstall {
    private static final String PREFIX = "HUWIFIADB_INSTALL_JSON ";
    private static final AtomicBoolean finished = new AtomicBoolean();
    private static int sessionId = -1;
    private static boolean committed;
    private static String runId;
    private static String packageName;

    private static synchronized void emit(String event, Object... values) {
        try {
            JSONObject record = new JSONObject();
            record.put("event", event);
            record.put("run_id", runId);
            record.put("session_id", sessionId);
            record.put("package", packageName);
            for (int i = 0; i < values.length; i += 2) record.put((String) values[i], values[i + 1]);
            System.out.println(PREFIX + record.toString());
            System.out.flush();
        } catch (Exception error) {
            System.err.println("Could not encode installer result: " + error);
        }
    }

    private static void finish(String event, int exitCode, Object... values) {
        if (!finished.compareAndSet(false, true)) return;
        emit(event, values);
        System.exit(exitCode);
    }

    /** Android 11 IIntentSender.send: code, optional Intent, then the remaining arguments. */
    private static final class ResultBinder extends Binder {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString("android.content.IIntentSender");
                return true;
            }
            if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
            data.enforceInterface("android.content.IIntentSender");
            data.readInt();
            Intent result = data.readInt() != 0 ? Intent.CREATOR.createFromParcel(data) : null;
            if (result == null) {
                finish("error", 4, "message", "Empty PackageInstaller callback", "outcome_unknown", true);
                return true;
            }
            int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
            String message = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            String reportedPackage = result.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
            finish("result", status == PackageInstaller.STATUS_SUCCESS ? 0 :
                            status == PackageInstaller.STATUS_PENDING_USER_ACTION ? 7 : 4,
                    "status", status, "message", message == null ? "" : message,
                    "reported_package", reportedPackage == null ? "" : reportedPackage);
            return true;
        }
    }

    private static IntentSender resultSender(IBinder binder) {
        // Public Parcelable factory builds the platform IIntentSender proxy for our Binder.
        // No PendingIntent is created with the system context / shell UID mismatch.
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeStrongBinder(binder);
            parcel.setDataPosition(0);
            return IntentSender.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    public static void main(String[] args) {
        PackageInstaller.Session session = null;
        PackageInstaller installer = null;
        try {
            if (args.length != 4) throw new IllegalArgumentException(
                    "Usage: LocalInstall <apk> <expected-package> <callback-timeout-seconds> <run-id>");
            packageName = args[1];
            runId = args[3];
            int timeout = Integer.parseInt(args[2]);
            if (timeout < 10 || timeout > 600) throw new IllegalArgumentException("Timeout out of range");
            File apk = new File(args[0]);
            if (!apk.isFile() || apk.length() == 0) throw new IllegalArgumentException("APK is missing/empty");
            Looper.prepareMainLooper();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
            if (archive == null || !packageName.equals(archive.packageName))
                throw new IllegalArgumentException("APK package does not match expected package");
            emit("started", "uid", Process.myUid(), "version_code", archive.getLongVersionCode());
            installer = context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(packageName);
            params.setSize(apk.length());
            // Request a normal data-preserving replacement; never uninstall on failure.
            try {
                Field installFlags = params.getClass().getField("installFlags");
                installFlags.setInt(params, installFlags.getInt(params) | 2); // INSTALL_REPLACE_EXISTING
            } catch (ReflectiveOperationException unavailable) {
                emit("warning", "message", "Replace flag unavailable; Android decides update eligibility");
            }
            sessionId = installer.createSession(params);
            emit("session_created");
            session = installer.openSession(sessionId);
            try (FileInputStream input = new FileInputStream(apk);
                 OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                session.fsync(output);
            }
            ResultBinder callback = new ResultBinder();
            new Handler(Looper.getMainLooper()).postDelayed(() -> finish("timeout", 5,
                    "message", "No PackageInstaller callback within deadline", "outcome_unknown", true),
                    timeout * 1000L);
            committed = true;
            emit("commit_requested");
            session.commit(resultSender(callback));
            session.close();
            session = null;
            Looper.loop();
        } catch (Throwable error) {
            if (!committed && installer != null && sessionId >= 0) {
                try { installer.abandonSession(sessionId); } catch (Exception ignored) { }
            }
            if (session != null) try { session.close(); } catch (Exception ignored) { }
            finish("error", 4, "message", error.toString(), "outcome_unknown", committed);
        }
    }
}
