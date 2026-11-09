package com.statcolor.app;

import java.lang.reflect.Method;

/**
 * 运行时识别当前设备跑的是哪套 SystemUI。
 *
 * 为什么不用「系统版本号」判定
 * --------------------------
 * 同一个 ROM 版本在不同机型上 SystemUI 的类名会变（ColorOS 与 OxygenOS
 * 共用 com.oplus.*，HyperOS 与 MIUI 共用 com.android.systemui.*），
 * 而版本号字符串在 OEM 之间完全没有统一格式。真正决定「该挂哪些钩子」的
 * 只有一个事实：**目标类在不在这个 ClassLoader 里**。所以主判据是类探测，
 * 版本号只用于日志与界面展示。
 *
 * 判定顺序
 * --------
 *   1. 类探测（仅注入侧有 ClassLoader，最可靠）
 *   2. 系统属性：ro.mi.os.version.name / ro.miui.ui.version.name
 *                 ro.build.version.oplusrom / ro.build.version.opporom
 *   3. Build 字段兜底（厂商 + 显示版本），给模块界面用
 */
public final class Rom {

    public static final int UNKNOWN = 0;
    public static final int COLOROS = 1;
    public static final int HYPEROS = 2;

    /** 任一命中即认定 HyperOS / MIUI 系。 */
    private static final String[] HYPEROS_MARKERS = {
            "com.android.systemui.statusbar.views.MiuiBatteryMeterView",
            "com.android.systemui.statusbar.views.MiuiStatusIconContainer",
            "com.android.systemui.statusbar.MiuiStatusBarIconViewHelper",
    };

    /** 任一命中即认定 ColorOS / OxygenOS / realmeUI 系。 */
    private static final String[] COLOROS_MARKERS = {
            "com.oplus.systemui.statusbar.pipeline.battery.ui.drawable.BatteryContentDrawableBase",
            "com.oplus.systemui.statusbar.pipeline.battery.ui.drawable.CircleBatteryContentDrawable",
            "com.oplus.systemui.statusbar.OplusStatusBarWindowViewTouchEx",
    };

    /** -1 = 尚未探测；探测结果按进程缓存。 */
    private static volatile int sKind = -1;
    private static volatile String sVersion = "";

    private Rom() {}

    // ───────────────────────────────────────────────────────────── 探测

    public static int detect(ClassLoader cl) {
        int k = sKind;
        if (k >= 0) return k;

        k = byClass(cl);
        if (k == UNKNOWN) k = byProperty();
        if (k == UNKNOWN) k = byBuild();

        sVersion = readVersion(k);
        sKind = k;
        return k;
    }

    /** 只能在注入进程里用（需要 SystemUI 的 ClassLoader）。 */
    private static int byClass(ClassLoader cl) {
        if (cl == null) return UNKNOWN;
        if (anyExists(cl, HYPEROS_MARKERS)) return HYPEROS;
        if (anyExists(cl, COLOROS_MARKERS)) return COLOROS;
        return UNKNOWN;
    }

    private static boolean anyExists(ClassLoader cl, String[] names) {
        for (String n : names) {
            try {
                Class.forName(n, false, cl);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static int byProperty() {
        String mi = prop("ro.mi.os.version.name") + prop("ro.miui.ui.version.name");
        if (mi.trim().length() > 0) return HYPEROS;
        String op = prop("ro.build.version.oplusrom") + prop("ro.build.version.opporom");
        if (op.trim().length() > 0) return COLOROS;
        return UNKNOWN;
    }

    /**
     * 模块界面进程里没有 SystemUI 的类，系统属性也可能被 hidden API 挡住，
     * 所以再留一层 Build 字段兜底 —— 这层只用于展示，不参与挂载决策。
     */
    private static int byBuild() {
        try {
            String brand = (android.os.Build.BRAND + " " + android.os.Build.MANUFACTURER)
                    .toLowerCase();
            if (brand.contains("xiaomi") || brand.contains("redmi")
                    || brand.contains("poco")) {
                return HYPEROS;
            }
            if (brand.contains("oppo") || brand.contains("oneplus")
                    || brand.contains("realme")) {
                return COLOROS;
            }
        } catch (Throwable ignored) {
        }
        return UNKNOWN;
    }

    private static String readVersion(int kind) {
        if (kind == HYPEROS) {
            // 完整版本号（例：OS3.0.8.0.VLRCNXM），退化到 OS3.0 / V816
            String v = prop("ro.mi.os.version.incremental");
            if (v.trim().length() == 0) v = prop("ro.mi.os.version.name");
            if (v.trim().length() == 0) v = prop("ro.miui.ui.version.name");
            return v;
        }
        if (kind == COLOROS) {
            String v = prop("ro.build.version.oplusrom");
            if (v.trim().length() == 0) v = prop("ro.build.version.opporom");
            return v;
        }
        return "";
    }

    // ───────────────────────────────────────────────────────────── 查询

    public static int kind() {
        int k = sKind;
        if (k >= 0) return k;
        // 界面进程不会主动调 detect()，这里补一次懒探测（只有属性/Build 两层）
        return detect(null);
    }

    public static boolean isHyperOs() {
        return kind() == HYPEROS;
    }

    public static boolean isColorOs() {
        return kind() == COLOROS;
    }

    public static String version() {
        return sVersion == null ? "" : sVersion;
    }

    /** 例：HyperOS(OS3.0) / ColorOS(16.0.0) / 未知(Android 15)。 */
    public static String describe() {
        return describe(kind());
    }

    public static String describe(int kind) {
        String v = version();
        String suffix = (v == null || v.length() == 0) ? "" : "(" + v + ")";
        switch (kind) {
            case HYPEROS:
                return "HyperOS" + suffix;
            case COLOROS:
                return "ColorOS" + suffix;
            default:
                return "未知 ROM" + suffix;
        }
    }

    // ─────────────────────────────────────────────────────── 属性读取

    /**
     * 读系统属性。注入进程里 SystemProperties 反射通常可用；
     * 模块界面进程若被 hidden API 拦下就返回空串，由调用方兜底。
     */
    private static String prop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            Object v = get.invoke(null, key, "");
            if (v != null) return v.toString();
        } catch (Throwable ignored) {
        }
        return "";
    }
}
