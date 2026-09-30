package com.statcolor.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 配置读写。
 *
 * UI 进程与 SystemUI（被注入）进程通过同名 SharedPreferences 交换配置。
 * LSPosed 会把被注入进程的 Context 映射到模块的偏好文件上，因此两边用同一套键名。
 *
 * 所有颜色以 #AARRGGBB 字符串存储。
 */
public final class Config {

    public static final String PREF_NAME = "statcolor";

    public static final String KEY_ENABLE   = "enable";
    public static final String KEY_DARK     = "color_dark";    // 深色背景时（浅色图标）
    public static final String KEY_LIGHT    = "color_light";   // 浅色背景时（深色图标）
    public static final String KEY_ALPHA    = "alpha";         // 0-100，全局透明度

    public static final String DEFAULT_DARK  = "#FFFFFFFF";
    public static final String DEFAULT_LIGHT = "#FF000000";


    private Config() {}

    /**
     * 取得配置。
     *
     * 先试 MODE_WORLD_READABLE：LSPosed 会为被注入进程把这个模式映射到模块偏好，
     * 让 SystemUI 里的 Hook 能读到本应用写入的配置。
     * 但该模式在 Android 7 (API 24) 起对普通应用抛 SecurityException，
     * 所以必须留 MODE_PRIVATE 兜底 —— 否则界面一启动就崩。
     */
    public static SharedPreferences prefs(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREF_NAME, Context.MODE_WORLD_READABLE);
        } catch (Throwable t) {
            return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        }
    }

    /** 解析 #RGB / #ARGB / #RRGGBB / #AARRGGBB，失败返回 fallback。 */
    public static int parseColor(String s, int fallback) {
        if (s == null) return fallback;
        s = s.trim();
        if (s.isEmpty()) return fallback;
        if (s.charAt(0) != '#') s = "#" + s;
        try {
            if (s.length() == 4) {            // #RGB
                int r = hex(s.charAt(1)), g = hex(s.charAt(2)), b = hex(s.charAt(3));
                return 0xFF000000 | (r * 17 << 16) | (g * 17 << 8) | (b * 17);
            }
            if (s.length() == 5) {            // #ARGB
                int a = hex(s.charAt(1)), r = hex(s.charAt(2)),
                    g = hex(s.charAt(3)), b = hex(s.charAt(4));
                return (a * 17 << 24) | (r * 17 << 16) | (g * 17 << 8) | (b * 17);
            }
            if (s.length() == 7) {            // #RRGGBB
                return 0xFF000000 | Integer.parseInt(s.substring(1), 16);
            }
            if (s.length() == 9) {            // #AARRGGBB
                return (int) Long.parseLong(s.substring(1), 16);
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static int hex(char c) {
        int v = Character.digit(c, 16);
        return v < 0 ? 0 : v;
    }

    /** 转成规范的 #AARRGGBB 大写字符串。 */
    public static String toHex(int color) {
        return String.format("#%08X", color);
    }

    /** 叠加全局透明度：在颜色自带 alpha 的基础上再乘一个系数（0-100）。 */
    public static int applyAlpha(int color, int alphaPercent) {
        int a = (color >>> 24);
        int scaled = a * clamp(alphaPercent, 0, 100) / 100;
        return (color & 0x00FFFFFF) | (scaled << 24);
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
