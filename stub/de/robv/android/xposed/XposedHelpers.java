package de.robv.android.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

/** 编译期 stub，签名与 XposedBridgeAPI 一致。 */
public final class XposedHelpers {

    private XposedHelpers() {}

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        return null;
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Object... parameterTypes) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName,
                                                         Object... parameterTypesAndCallback) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader,
                                                         String methodName,
                                                         Object... parameterTypesAndCallback) {
        return null;
    }

    public static Method findMethodExact(String className, ClassLoader classLoader,
                                         String methodName, Object... parameterTypes) {
        return null;
    }

    public static Set<Method> findMethodsByName(Class<?> clazz, String methodName) {
        return null;
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        return null;
    }

    public static Object getObjectField(Object obj, String fieldName) {
        return null;
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {}

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return null;
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        return null;
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        return null;
    }
}
