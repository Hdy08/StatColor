package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Set;

/** 编译期 stub，签名与 XposedBridgeAPI 一致。 */
public final class XposedBridge {

    private XposedBridge() {}

    public static void log(String text) {}
    public static void log(Throwable t) {}

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        return null;
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass,
                                                           String methodName,
                                                           XC_MethodHook callback) {
        return null;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass,
                                                                XC_MethodHook callback) {
        return null;
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws Throwable {
        return null;
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args,
                                              ClassLoader classLoader) throws Throwable {
        return null;
    }
}
