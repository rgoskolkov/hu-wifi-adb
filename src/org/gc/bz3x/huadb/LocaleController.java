package org.gc.bz3x.huadb;

import android.app.ActivityManager;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.lang.reflect.InvocationTargetException;
import java.util.Locale;

final class LocaleController {
    static final String RUSSIAN = "ru-RU,en-US,zh-CN";
    static final String CHINESE = "zh-CN";

    private LocaleController() {}

    static String currentLocales() throws Exception {
        return locales(configuration());
    }

    static void apply(String tags) throws Exception {
        validate(tags);
        Object manager = activityManager();
        Class<?> managerClass = Class.forName("android.app.IActivityManager");
        Configuration configuration = (Configuration) invoke(
                managerClass, manager, "getConfiguration", new Class<?>[0]);

        configuration.setLocales(LocaleList.forLanguageTags(tags));
        Configuration.class.getField("userSetLocale").setBoolean(configuration, true);
        invoke(managerClass, manager, "updatePersistentConfiguration",
                new Class<?>[]{Configuration.class}, configuration);

        String actual = currentLocales();
        if (!tags.equals(actual)) {
            throw new IllegalStateException("Locale readback mismatch: " + actual);
        }
    }

    private static Object activityManager() throws Exception {
        return invoke(ActivityManager.class, null, "getService", new Class<?>[0]);
    }

    private static Configuration configuration() throws Exception {
        Object manager = activityManager();
        return (Configuration) invoke(Class.forName("android.app.IActivityManager"), manager,
                "getConfiguration", new Class<?>[0]);
    }

    private static String locales(Configuration configuration) {
        return configuration.getLocales().toLanguageTags();
    }

    private static Object invoke(Class<?> type, Object receiver, String name,
                                 Class<?>[] parameterTypes, Object... args) throws Exception {
        try {
            return type.getMethod(name, parameterTypes).invoke(receiver, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw error;
        }
    }

    private static void validate(String tags) {
        if (!tags.matches("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*(?:,[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*){0,3}")) {
            throw new IllegalArgumentException("Invalid locale list");
        }
        for (String tag : tags.split(",")) {
            if (Locale.forLanguageTag(tag).getLanguage().isEmpty()) {
                throw new IllegalArgumentException("Invalid locale: " + tag);
            }
        }
    }
}
