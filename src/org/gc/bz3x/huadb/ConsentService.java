package org.gc.bz3x.huadb;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;

public class ConsentService extends AccessibilityService {
    static volatile ConsentService live;
    static volatile String status = "не подключён";
    private long deadline;
    private boolean sawSystemUi;
    private boolean sawDialog;
    private final Handler timer = new Handler();

    private final Runnable check = new Runnable() {
        @Override public void run() {
            if (deadline == 0) return;
            if (SystemClock.elapsedRealtime() > deadline) {
                stop(sawDialog ? "запрос найден, но кнопка Allow недоступна"
                        : sawSystemUi ? "окно SystemUI видно, но точный запрос Wi-Fi ADB не найден"
                        : "время ожидания истекло; системное окно не обнаружено");
                return;
            }
            inspect();
            if (deadline != 0) timer.postDelayed(this, 300);
        }
    };

    static boolean arm() {
        ConsentService service = live;
        if (service == null) return false;
        service.sawSystemUi = false;
        service.sawDialog = false;
        service.deadline = SystemClock.elapsedRealtime() + 20_000;
        service.report("ожидание системного запроса (до 20 секунд)");
        service.timer.removeCallbacks(service.check);
        service.timer.post(service.check);
        return true;
    }

    private void report(String value) {
        if (!value.equals(status)) {
            status = value;
            Log.i("HuAdbConsent", value);
        }
    }

    private void stop(String value) {
        deadline = 0;
        timer.removeCallbacks(check);
        report(value);
    }

    @Override protected void onServiceConnected() { live = this; stop("готов; действие только после ручного разрешения"); }
    @Override public void onInterrupt() { stop("прервано"); }
    @Override public boolean onUnbind(android.content.Intent intent) { stop("служба отключена"); if (live == this) live = null; return super.onUnbind(intent); }
    @Override public void onDestroy() { stop("отключён"); if (live == this) live = null; super.onDestroy(); }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (deadline != 0 && "com.android.systemui".contentEquals(event.getPackageName() == null ? "" : event.getPackageName())) {
            Log.d("HuAdbConsent", "event=" + event.getEventType() + " class=" + event.getClassName());
        }
    }

    private void inspect() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) {
                    try { inspectRoot(root); } finally { root.recycle(); }
                }
                window.recycle();
                if (deadline == 0) return;
            }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try { inspectRoot(root); } finally { root.recycle(); }
            }
        } catch (Exception e) {
            stop("ошибка доступа к окну: " + e.getClass().getSimpleName());
        }
    }

    private void inspectRoot(AccessibilityNodeInfo root) {
        if (deadline == 0 || !"com.android.systemui".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return;
        sawSystemUi = true;
        List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByText("Allow wireless debugging on this network?");
        boolean exact = false;
        for (AccessibilityNodeInfo node : titles) {
            if ("Allow wireless debugging on this network?".contentEquals(node.getText() == null ? "" : node.getText())) exact = true;
            node.recycle();
        }
        if (!exact) return;
        sawDialog = true;
        report("найден точный системный запрос Wi-Fi ADB");
        List<AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByViewId("android:id/button1");
        boolean attempted = false;
        for (AccessibilityNodeInfo node : buttons) {
            if (!attempted && node.isVisibleToUser() && node.isEnabled() && node.isClickable()
                    && "Allow".contentEquals(node.getText() == null ? "" : node.getText())) {
                attempted = true;
                deadline = 0;
                timer.removeCallbacks(check);
                boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                report(ok ? "нажатие Allow передано системе" : "система отклонила нажатие Allow");
            }
            node.recycle();
        }
        if (!attempted) report("найден запрос, кнопка Allow пока недоступна");
    }
}
