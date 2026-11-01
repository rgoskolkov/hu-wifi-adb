import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Process;
import android.os.Looper;
import android.os.UserHandle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.Set;

/** One-shot permissions client for the authorized Android shell, user 0. */
public final class PermissionTool {
    private static final String PREFIX = "HUWIFIADB_PERMISSIONS_JSON ";
    private static String kind(int protection) {
        switch (protection & PermissionInfo.PROTECTION_MASK_BASE) {
            case PermissionInfo.PROTECTION_NORMAL: return "normal";
            case PermissionInfo.PROTECTION_DANGEROUS: return "runtime";
            case PermissionInfo.PROTECTION_SIGNATURE: return "signature";
            case PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM: return "signature_or_system";
            default: return "unknown";
        }
    }
    private static JSONObject permission(PackageManager pm, String pkg, String name) throws Exception {
        JSONObject item = new JSONObject().put("name", name);
        item.put("granted", pm.checkPermission(name, pkg) == PackageManager.PERMISSION_GRANTED);
        try {
            PermissionInfo info = pm.getPermissionInfo(name, 0);
            item.put("kind", kind(info.protectionLevel));
            item.put("protection_level", info.protectionLevel);
            item.put("development", (info.protectionLevel & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0);
        } catch (PackageManager.NameNotFoundException absent) {
            item.put("kind", "unknown").put("development", false);
        }
        return item;
    }
    public static void main(String[] args) {
        JSONObject result = new JSONObject();
        try {
            if (args.length < 3) throw new IllegalArgumentException("<run-id> <inspect|grant|revoke|grant-runtime> <package> [permission...]");
            result.put("run_id", args[0]).put("package", args[2]).put("action", args[1]);
            if (Process.myUid() / 100000 != 0) throw new IllegalArgumentException("Only Android user 0 supported");
            Looper.prepareMainLooper();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            PackageManager pm = context.getPackageManager();
            PackageInfo pkg = pm.getPackageInfo(args[2], PackageManager.GET_PERMISSIONS);
            boolean system = (pkg.applicationInfo.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            result.put("system", system).put("target_sdk", pkg.applicationInfo.targetSdkVersion);
            String[] requested = pkg.requestedPermissions == null ? new String[0] : pkg.requestedPermissions;
            JSONArray before = new JSONArray();
            Set<String> declared = new HashSet<>();
            for (String name : requested) { declared.add(name); before.put(permission(pm, args[2], name)); }
            result.put("before", before);
            JSONArray changes = new JSONArray();
            boolean failed = false;
            if (!args[1].equals("inspect")) {
                if (system) throw new IllegalArgumentException("System package mutations are excluded");
                if (!args[1].equals("grant") && !args[1].equals("revoke") && !args[1].equals("grant-runtime"))
                    throw new IllegalArgumentException("Unknown action");
                Set<String> chosen = new java.util.LinkedHashSet<>();
                if (args[1].equals("grant-runtime")) {
                    for (String name : requested) if (permission(pm, args[2], name).getString("kind").equals("runtime")) chosen.add(name);
                } else {
                    if (args.length < 4) throw new IllegalArgumentException("Specify permission names");
                    for (int i = 3; i < args.length; i++) chosen.add(args[i]);
                }
                // Validate the complete selection before the first mutation.
                for (String name : chosen) {
                    if (!declared.contains(name)) throw new IllegalArgumentException("Not declared by app: " + name);
                    JSONObject state = permission(pm, args[2], name);
                    if (!state.getString("kind").equals("runtime") && !state.getBoolean("development"))
                        throw new IllegalArgumentException("Not runtime/development-grantable: " + name);
                }
                boolean grant = !args[1].equals("revoke");
                String method = grant ? "grantRuntimePermission" : "revokeRuntimePermission";
                for (String name : chosen) {
                    boolean old = pm.checkPermission(name, args[2]) == PackageManager.PERMISSION_GRANTED;
                    JSONObject change = new JSONObject().put("name", name).put("before", old).put("desired", grant);
                    if (old != grant) {
                        try {
                            pm.getClass().getMethod(method, String.class, String.class, UserHandle.class)
                                .invoke(pm, args[2], name, Process.myUserHandle());
                        } catch (Exception error) {
                            Throwable cause = error instanceof InvocationTargetException ? error.getCause() : error;
                            change.put("error", cause.toString());
                        }
                    }
                    boolean after = pm.checkPermission(name, args[2]) == PackageManager.PERMISSION_GRANTED;
                    boolean verified = after == grant;
                    change.put("after", after).put("verified", verified).put("changed", old != after);
                    if (!verified || change.has("error")) failed = true;
                    changes.put(change);
                }
            }
            JSONArray after = new JSONArray();
            for (String name : requested) after.put(permission(pm, args[2], name));
            result.put("permissions", after).put("changes", changes).put("ok", !failed);
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException ? error.getCause() : error;
            try { result.put("ok", false).put("error", cause.toString()); } catch (Exception ignored) { }
        }
        System.out.println(PREFIX + result.toString());
        System.out.flush();
        System.exit(result.optBoolean("ok", false) ? 0 : 4);
    }
}
