package com.statcolor.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 注入 SystemUI，为「状态栏窗口」内的所有元素改色。
 *
 * 设计（v2.0：按窗口划界）
 * ----------------------
 * 前三版按类名猜哪些视图属于状态栏，结果不是漏（时钟/信号/WiFi/电池没改到）
 * 就是误伤（通知栏、控制中心、锁屏被一起染了）。
 *
 * 真正的边界其实很干净：**状态栏是一个独立的 Window**（type=STATUS_BAR），
 * 根视图在 ColorOS 上是
 *   com.oplus.systemui.statusbar.OplusStatusBarWindowViewTouchEx
 * 而通知栏 / 控制中心 / 锁屏属于另外的 Window。
 *
 * 本版改为按窗口划界：
 *   1. 挂 ViewRootImpl#performDraw()，拿到「本帧正在绘制哪个窗口」的根视图；
 *   2. 判断该根视图是否为状态栏窗口；
 *   3. 只有状态栏窗口的绘制过程里，才替换 Paint 的颜色。
 *
 * 收益：「状态栏上所有元素」自动全覆盖（时钟、信号、WiFi、电池、
 * 温度/功耗文字都是它的子视图），而通知栏/控制中心/锁屏自动全排除，
 * 不需要任何类名白名单。
 *
 * 另按用户要求：**通知图标不改色**，故本版不再挂 StatusBarIconView 的
 * tint 相关 Hook。
 */
public final class Hook {

    private static final String TAG = "StatColor";

    public static final String VERSION = "10.3";

    /** 配置镜像文件，由模块界面写出，权限 0644。 */
    public static final String CONF_FILE = "/data/local/tmp/statcolor.conf";

    private static volatile Context sCtx;

    /** 本线程当前是否正在绘制状态栏窗口。 */
    private static final ThreadLocal<Boolean> IN_STATUS_BAR = new ThreadLocal<Boolean>();

    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicInteger PAINT_HITS = new AtomicInteger();
    private static final AtomicInteger TINT_HITS = new AtomicInteger();
    private static final java.util.Set<Integer> SEEN_COLORS =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<Integer>());
    private static volatile boolean sConfLogged = false;
    private static volatile boolean sWindowLogged = false;
    private static volatile boolean sTreeDumped = false;

    private Hook() {}

    // ─────────────────────────────────────────────────────────── 入口

    public static void loadPackage(ClassLoader cl, String pkg) {
        boolean wanted = pkg.startsWith("com.android.systemui")
                || pkg.startsWith("com.oplus.systemui");
        if (!wanted) return;

        try {
            log("=== v" + VERSION + " enter " + pkg);

            // 尽早抓 Context：StatusBarIconView 构造得早且必定发生
            Class<?> iconView = findClass("com.android.systemui.statusbar.StatusBarIconView", cl);
            if (iconView != null) {
                XposedBridge.hookAllConstructors(iconView, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        captureContext(param.thisObject);
                        registerIconView(param.thisObject);
                    }
                });
            }

            hookStatusBarDrawScope(cl);
            hookPaint(cl);
            hookImageTint(cl);
            // 关键：系统图标（信号/WiFi/电池）也是 StatusBarIconView，
            // 必须单独挂 tint —— 但要与左侧通知图标区分开
            // 恢复 v1.3 中确实生效过的路径：对已登记的 StatusBarIconView
            // 实例挂框架层 tint。电量图标就是靠这条改成功的。
            // 实测靶点：电量图标 CircleBatteryContentDrawable.setColors(int,int,int)
            // 以及信号/WiFi 的 StatusBarIconView.updateDecorColor
            hookBatteryAndDecorColor(cl);
            // WiFi / 信号：颜色走通用 drawable tint，用调用栈判定归属
            hookMobileWifiTint(cl);
            // 诊断：把状态栏窗口绘制期间的所有 drawable tint 调用列出来，
            // 用来确认 WiFi/信号到底走不走 tint（v6.0 证明它们不走 setTint）
            hookDrawableTintSurvey(cl);

            log("=== v" + VERSION + " done " + pkg);
        } catch (Throwable t) {
            log("loadPackage FAILED: " + t);
        }
    }

    // ────────────────────────────── 判定「本帧画的是不是状态栏窗口」

    /**
     * 挂 ViewRootImpl#performDraw()：
     *   before → 判断该 ViewRootImpl 的根视图是不是状态栏窗口，置标志
     *   after  → 清标志
     * 一帧内每个窗口各调一次，天然就是「按窗口」的边界。
     */
    private static void hookStatusBarDrawScope(ClassLoader cl) {
        try {
            Class<?> vri = Class.forName("android.view.ViewRootImpl", false, cl);
            XposedBridge.hookAllMethods(vri, "performDraw", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        View root = rootViewOf(param.thisObject);
                        boolean isSb = isStatusBarRoot(root);
                        IN_STATUS_BAR.set(isSb);
                        if (isSb) { dumpStatusBarTree(root); maybeSweep(root); }
                    } catch (Throwable t) {
                        IN_STATUS_BAR.set(Boolean.FALSE);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    IN_STATUS_BAR.remove();
                }
            });
            log("hooked ViewRootImpl#performDraw");
        } catch (Throwable t) {
            log("hookStatusBarDrawScope failed: " + t);
        }
    }

    // ───────── 状态栏图标「扫描式」上色（v8.0）

    /**
     * 为什么需要扫描
     * ------------
     * WiFi / 信号 / VPN / NFC / 蓝牙这些图标的 tint 是在**子线程**里设的
     * （日志实测 tid=11706/15295/16153/17208/25009，从不是主线程 5807），
     * 设置时 View 还没挂到窗口：既判断不了窗口归属，View.post 也永远不会
     * 被 RunQueue 执行（v7.3/v7.4 两次失败的根因）。
     *
     * 所以反过来做：不追「谁设的」，而是从**状态栏窗口根视图**出发，
     * 周期性遍历真实视图树，把 ImageView 的 tint 改成目标色。边界天然正确，
     * 新图标（VPN/NFC/蓝牙）不需要再加白名单。
     *
     * 通知图标排除：类名含 Notification 的子树整块跳过，且只处理右半屏
     * （时钟在左半屏是 TextView，走另一条路，不受影响）。
     */
    private static long sLastSweepAt;
    private static final AtomicInteger SWEEP_HITS = new AtomicInteger();
    private static final AtomicInteger SWEEP_SKIP = new AtomicInteger();

    private static void maybeSweep(View root) {
        long now = android.os.SystemClock.uptimeMillis();
        // 80ms 而不是 1s：布局变化（网速文字变长把左边图标挤动）时
        // 系统会重新给图标设一次白色 tint，1s 的节流就表现为
        // 「颜色暂时失效、稳定后才恢复」。配置已加缓存，这里跑得起。
        if (now - sLastSweepAt < 80) return;
        sLastSweepAt = now;
        try { sweep(root, 0); } catch (Throwable t) { logOnce("sweep err " + t); }
    }

    /**
     * 已经被扫描确认「属于状态栏、且位于右半屏」的图标 View。
     * 有了它，系统在布局变化后重新设 tint 时可以在**当帧**直接改掉，
     * 不必等下一次扫描 —— 这才是消除闪烁的关键。
     */
    private static final java.util.Set<View> SB_ICON_VIEWS =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(
                            new java.util.WeakHashMap<View, Boolean>()));

    private static void sweep(View v, int depth) {
        if (v == null || depth > 16) return;
        String cn = v.getClass().getName();
        if (cn.contains("Notification")) return;          // 通知图标不动
        if (v instanceof android.widget.ImageView) tintIcon((android.widget.ImageView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n && i < 64; i++) sweep(g.getChildAt(i), depth + 1);
        }
    }

    private static void tintIcon(android.widget.ImageView iv) {
        try {
            int w = iv.getWidth();
            if (w <= 0) return;                            // 没布局，位置不可信
            // 必须用屏幕坐标：getLeft() 是相对父容器的，状态栏图标容器的
            // left 只有几十像素，用它判断「右半屏」永远为假（v8.0 实测
            // 一条 [sweep] 都没打出来就是这个原因）。
            int[] loc = new int[2];
            iv.getLocationOnScreen(loc);
            int screenW = iv.getResources().getDisplayMetrics().widthPixels;
            if (loc[0] + w / 2 <= screenW / 2) {
                if (SWEEP_SKIP.incrementAndGet() <= 6) {
                    log("[sweep-skip] 左半屏 " + iv.getClass().getSimpleName()
                            + " x=" + loc[0] + " w=" + w);
                }
                return;
            }
            android.content.res.ColorStateList tl = iv.getImageTintList();
            if (tl == null) return;
            int cur = tl.getDefaultColor();
            if (((cur >>> 24) & 0xFF) == 0) return;
            Integer r = recolor(cur);
            if (r == null) return;
            int out = r.intValue();
            if (out == cur) return;
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(out));
            SB_ICON_VIEWS.add(iv);
            if (SWEEP_HITS.incrementAndGet() <= 25) {
                log("[sweep] " + iv.getClass().getSimpleName()
                        + " x=" + iv.getLeft() + " w=" + w
                        + " #" + Integer.toHexString(cur)
                        + " -> #" + Integer.toHexString(out));
            }
        } catch (Throwable ignored) {}
    }

    /** 反射取 ViewRootImpl.mView。 */
    private static View rootViewOf(Object vri) {
        try {
            Field f = vri.getClass().getDeclaredField("mView");
            f.setAccessible(true);
            Object v = f.get(vri);
            return v instanceof View ? (View) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 是不是状态栏窗口的根视图。三重判据，任一命中即算：
     *   1) 类名含 StatusBarWindow（AOSP 与 ColorOS 的根视图都带这个）
     *   2) WindowManager.LayoutParams.type == TYPE_STATUS_BAR
     *   3) 视图树浅层扫描出现 StatusBarWindow 类名
     * 只打印一次判定结果，便于核对。
     */
    private static boolean isStatusBarRoot(View root) {
        if (root == null) return false;
        try {
            String cn = root.getClass().getName();

            boolean hit = false;
            if (cn.contains("StatusBarWindow")) hit = true;
            else {
                try {
                    Object lp = root.getLayoutParams();
                    if (lp instanceof WindowManager.LayoutParams
                            && ((WindowManager.LayoutParams) lp).type
                                == WindowManager.LayoutParams.TYPE_STATUS_BAR) {
                        hit = true;
                    }
                } catch (Throwable ignored) {}
                if (!hit && root instanceof ViewGroup
                        && containsStatusBarWindow((ViewGroup) root, 0)) {
                    hit = true;
                }
            }

            // 列出每个窗口的根视图，便于确认右边图标到底在哪个窗口绘制
            if (WINDOWS_LOGGED.add(cn) && WINDOWS_LOGGED.size() <= 20) {
                log("[window] " + cn + " " + root.getWidth() + "x" + root.getHeight()
                        + " -> " + (hit ? "STATUSBAR" : "other"));
            }
            if (hit) logOnce("statusbar root = " + cn);
            return hit;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final java.util.Set<String> WINDOWS_LOGGED =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());

    private static boolean containsStatusBarWindow(ViewGroup g, int depth) {
        if (depth > 4) return false;
        int n = g.getChildCount();
        for (int i = 0; i < n && i < 64; i++) {
            View c = g.getChildAt(i);
            if (c == null) continue;
            if (c.getClass().getName().contains("StatusBarWindow")) return true;
            if (c instanceof ViewGroup && containsStatusBarWindow((ViewGroup) c, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static void logOnce(String msg) {
        if (!sWindowLogged) { sWindowLogged = true; log(msg); }
    }

    // ─────────────── 诊断：dump 状态栏视图树（一次性）

    /**
     * 把状态栏窗口的视图树打印出来：类名、位置、文字颜色、drawable 类型。
     * 为的是一次看清右边那组图标（信号/WiFi/电池）究竟是什么控件、
     * 颜色从哪来 —— 而不是继续靠猜。
     */
    private static void dumpStatusBarTree(View root) {
        if (sTreeDumped) return;
        sTreeDumped = true;
        try {
            StringBuilder sb = new StringBuilder();
            walk(root, 0, sb);
            for (String line : sb.toString().split("\n")) {
                if (line.length() > 0) log(line);
            }
            log("=== end view tree ===");
        } catch (Throwable t) {
            log("dump tree failed: " + t);
        }
    }

    private static void walk(View v, int depth, StringBuilder sb) {
        if (v == null || depth > 8) return;
        try {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < depth; i++) line.append("  ");
            line.append('[').append(depth).append("] ")
                .append(v.getClass().getSimpleName())
                .append(" @").append(v.getLeft()).append(',').append(v.getTop())
                .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                .append(" vis=").append(v.getVisibility());

            if (v instanceof android.widget.TextView) {
                android.widget.TextView tv = (android.widget.TextView) v;
                CharSequence cs = tv.getText();
                String txt = cs == null ? "" : cs.toString();
                if (txt.length() > 14) txt = txt.substring(0, 14) + "…";
                line.append(" text='").append(txt).append('\'')
                    .append(" color=#").append(Integer.toHexString(tv.getCurrentTextColor()));
            } else if (v instanceof android.widget.ImageView) {
                android.graphics.drawable.Drawable d = ((android.widget.ImageView) v).getDrawable();
                line.append(" drawable=").append(d == null ? "null" : d.getClass().getSimpleName());
                try {
                    android.content.res.ColorStateList tl =
                            ((android.widget.ImageView) v).getImageTintList();
                    if (tl != null) {
                        line.append(" tint=#").append(Integer.toHexString(tl.getDefaultColor()));
                    }
                } catch (Throwable ignored) {}
            }
            try {
                android.content.res.ColorStateList bg = v.getBackgroundTintList();
                if (bg != null) {
                    line.append(" bgTint=#").append(Integer.toHexString(bg.getDefaultColor()));
                }
            } catch (Throwable ignored) {}

            sb.append(line).append('\n');
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                int n = Math.min(g.getChildCount(), 40);
                for (int i = 0; i < n; i++) walk(g.getChildAt(i), depth + 1, sb);
            }
        } catch (Throwable ignored) {}
    }

    // ───────── v1.3 恢复：已登记 StatusBarIconView 的框架层 tint

    /**
     * v1.3 实测能改电量图标的那条路径，原样恢复。
     *
     * 做法：StatusBarIconView 构造时把实例登记下来，然后挂
     * ImageView#setImageTintList / setColorFilter，只对登记的实例生效。
     * 与 v1.3 唯一不同的是**不加位置过滤**（位置在 tint 调用时还没算出来），
     * 但会打印每个被改视图的位置，用来确认到底改到了谁。
     */
    private static void hookRegisteredIconViewTint(ClassLoader cl) {
        try {
            Class<?> iv = Class.forName("android.widget.ImageView", false, cl);
            Class<?> csl = Class.forName("android.content.res.ColorStateList", false, cl);
            Class<?> pdm = Class.forName("android.graphics.PorterDuff$Mode", false, cl);

            final java.lang.reflect.Method tintM = iv.getDeclaredMethod("setImageTintList", csl);
            XposedBridge.hookMethod(tintM, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        if (!isRegisteredIconView(self)) return;
                        Object o = param.args[0];
                        if (o == null) return;
                        int cur = (Integer) o.getClass().getMethod("getDefaultColor").invoke(o);
                        Integer r = recolor(cur);
                        if (r == null || r.intValue() == cur) return;
                        java.lang.reflect.Constructor<?> ctor =
                                o.getClass().getDeclaredConstructor(int.class);
                        ctor.setAccessible(true);
                        param.args[0] = ctor.newInstance(r.intValue());
                        logViewHit("reg-tint", self, cur, r);
                    } catch (Throwable t) { logOnce("reg-tint err " + t); }
                }
            });
            log("hooked ImageView#setImageTintList (registered StatusBarIconView)");

            final java.lang.reflect.Method cfM =
                    iv.getDeclaredMethod("setColorFilter", int.class, pdm);
            XposedBridge.hookMethod(cfM, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        if (!isRegisteredIconView(self)) return;
                        Object a0 = param.args[0];
                        if (!(a0 instanceof Integer)) return;
                        int cur = (Integer) a0;
                        if (!isIconColor(cur)) return;      // 状态色（充电绿等）不动
                        Integer r = recolor(cur);
                        if (r == null || r.intValue() == cur) return;
                        param.args[0] = r;
                        logViewHit("reg-cf", self, cur, r);
                    } catch (Throwable ignored) {}
                }
            });
            log("hooked ImageView#setColorFilter (registered StatusBarIconView)");
        } catch (Throwable t) {
            log("hookRegisteredIconViewTint failed: " + t);
        }
    }

    /** 记录被改色的视图：位置 + 类名，用来确认到底改到了哪些图标。 */
    private static void logViewHit(String tag, Object self, int cur, int rep) {
        try {
            String pos = "?";
            String cls = self == null ? "?" : self.getClass().getSimpleName();
            if (self instanceof View) {
                View v = (View) self;
                pos = v.getLeft() + "," + v.getTop()
                        + " " + v.getWidth() + "x" + v.getHeight();
            }
            if (VIEW_HITS.incrementAndGet() <= 25) {
                log("[" + tag + "] " + cls + " @" + pos
                        + " #" + Integer.toHexString(cur)
                        + " -> #" + Integer.toHexString(rep));
            }
        } catch (Throwable ignored) {}
    }

    private static final java.util.Map<Object, Boolean> ICON_VIEWS =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<Object, Boolean>());

    private static void registerIconView(Object v) {
        try { if (v != null) ICON_VIEWS.put(v, Boolean.TRUE); } catch (Throwable ignored) {}
    }

    private static boolean isRegisteredIconView(Object v) {
        try { return v != null && ICON_VIEWS.containsKey(v); } catch (Throwable t) { return false; }
    }

    private static final AtomicInteger VIEW_HITS = new AtomicInteger();

    // ───────── 实测靶点：电量图标 + 信号/WiFi 装饰色

    /**
     * 依据 v1.3 日志定位到的两个真实入口：
     *
     * 1) com.oplus.systemui.statusbar.pipeline.battery.ui.drawable.*
     *      void setColors(int, int, int)     ← 电量图标（圆圈/横向/竖向）
     *    这是 ColorOS 电量图标颜色的唯一来源，签名 shorty='VIII'。
     *
     * 2) com.android.systemui.statusbar.StatusBarIconView
     *      updateDecorColor(...)             ← 信号/WiFi 的装饰色
     *    v1.3 日志显示它的调用栈里会走 Paint#setColor。
     *
     * 两处都按「方法名 + 逐个替换 int 参数」处理，并用 recolor 的
     * 亮度判定避开背景色。
     */
    private static void hookBatteryAndDecorColor(ClassLoader cl) {
        // ── 1) 电量图标：所有 *BatteryContentDrawable / BatteryBarDrawable 的 setColors
        String[] batteryCls = {
                "com.oplus.systemui.statusbar.pipeline.battery.ui.drawable.CircleBatteryContentDrawable",
                "com.oplus.systemui.statusbar.pipeline.battery.ui.drawable.HorizontalBatteryContentDrawable",
                "com.oplus.systemui.statusbar.pipeline.battery.ui.drawable.VerticalBatteryContentDrawable",
                "com.oplus.systemui.statusbar.pipeline.battery.ui.drawable.BatteryContentDrawableBase",
                "com.oplus.systemui.statusbar.pipeline.battery.ui.drawable.BatteryBarDrawableBase",
        };
        Class<?> canvasCls = null;
        try { canvasCls = Class.forName("android.graphics.Canvas", false, cl); }
        catch (Throwable t) { log("no Canvas class: " + t); }

        for (String cn : batteryCls) {
            Class<?> c = findClass(cn, cl);
            if (c == null) { log("MISS " + cn); continue; }

            // 1a) setColors 只「记账」，不当场改色。
            //     当场改色无法区分状态栏与控制中心（两者共用同一个类，
            //     v7.1 实测 QS 排除靠调用栈判定并不可靠）。
            java.lang.reflect.Method setColors = null;
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("setColors")) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            recordBatteryColors(param.thisObject, param.args);
                        }
                    });
                    if (m.getParameterTypes().length == 3) setColors = m;
                    log("hooked " + c.getSimpleName() + "#setColors/"
                            + m.getParameterTypes().length + " (record only)");
                } catch (Throwable t) { log("hook setColors fail: " + t); }
            }
            if (setColors != null) BATT_SET_COLORS.put(c, setColors);

            // 1b) 真正的改色点：draw(Canvas)。
            //     此刻 ViewRootImpl#performDraw 已经把「本帧画的是哪个窗口」
            //     记在 IN_STATUS_BAR 里 —— 状态栏窗口才套用目标色，
            //     其它窗口（控制中心 / 通知栏）一律还原原始色。
            if (canvasCls != null) {
                try {
                    java.lang.reflect.Method dm = c.getDeclaredMethod("draw", canvasCls);
                    XposedBridge.hookMethod(dm, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            applyBatteryColors(param.thisObject,
                                    Boolean.TRUE.equals(IN_STATUS_BAR.get()));
                        }
                    });
                    log("hooked " + c.getSimpleName() + "#draw(Canvas)");
                } catch (NoSuchMethodException e) {
                    log("no draw(Canvas) in " + c.getSimpleName());
                } catch (Throwable t) { log("hook draw fail: " + t); }
            }
        }

        // ── 2) 信号/WiFi 装饰色
        Class<?> iconView = findClass(
                "com.android.systemui.statusbar.StatusBarIconView", cl);
        if (iconView != null) {
            for (java.lang.reflect.Method m : iconView.getDeclaredMethods()) {
                String nm = m.getName();
                if (!"updateDecorColor".equals(nm) && !"setDecorColor".equals(nm)) continue;
                final int cnt = m.getParameterTypes().length;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            replaceAllIntArgs(param, "decor", cnt);
                        }
                    });
                    log("hooked StatusBarIconView#" + nm + "/" + cnt);
                } catch (Throwable t) { log("hook " + nm + " fail: " + t); }
            }
        } else {
            log("MISS StatusBarIconView for decor");
        }
    }

    /** 把参数里所有 int 颜色逐个替换（保留非颜色 int 由亮度判定兜底）。 */
    private static void replaceAllIntArgs(XC_MethodHook.MethodHookParam param,
                                          String tag, int declaredCount) {
        try {
            if (param.args == null) return;
            // 控制中心（QS）里的元素共用同一批类，按栈兜底排除
            if (isQsContext()) {
                if (QS_SKIP.incrementAndGet() <= 6) log("[" + tag + "] skipped (QS)");
                return;
            }

            int replaced = 0;
            for (int i = 0; i < param.args.length; i++) {
                Object a = param.args[i];
                if (!(a instanceof Integer)) continue;
                int cur = (Integer) a;
                int curA = (cur >>> 24) & 0xFF;
                // 全透明的是背景占位，别动
                if (curA == 0) continue;
                Integer r = recolor(cur);
                if (r == null) continue;
                // 直接用配置的颜色（含它自带的 alpha）
                int out = r.intValue();
                if (out == cur) continue;
                param.args[i] = Integer.valueOf(out);
                replaced++;
                if (BATT_HITS.incrementAndGet() <= 30) {
                    log("[" + tag + "] arg" + i + " #" + Integer.toHexString(cur)
                            + " -> #" + Integer.toHexString(out)
                            + " (alpha " + Integer.toHexString(curA)
                                + "->" + Integer.toHexString((out >>> 24) & 0xFF) + ")");
                }
            }
            if (replaced == 0 && BATT_MISS.incrementAndGet() <= 6) {
                StringBuilder sb = new StringBuilder("[" + tag + "] no-int-replaced args=");
                for (Object a : param.args) {
                    sb.append(a == null ? "null"
                            : (a instanceof Integer
                                ? "#" + Integer.toHexString((Integer) a)
                                : a.getClass().getSimpleName())).append(' ');
                }
                log(sb.toString());
            }
        } catch (Throwable t) { logOnce(tag + " err " + t); }
    }

    private static final AtomicInteger QS_SKIP = new AtomicInteger();

    // ───────── 电量图标：按『正在绘制哪个窗口』决定改色（v7.2）

    /**
     * 为什么不再当场改色
     * ------------------
     * 状态栏电量与控制中心电量是**同一个类**（CircleBatteryContentDrawable），
     * 两者的 setColors 调用在日志里几乎同时出现（21.976 与 22.059），
     * 只能靠调用栈猜。v7.1 实测：8 次命中 QS 被跳过，但仍有若干次
     * 判定失败被改色 —— 用户看到的「下拉后电量图标被改」就是这么来的。
     *
     * 真正的确定性边界是**窗口**：setColors 只是记账，
     * 到 draw(Canvas) 时用 IN_STATUS_BAR 判断本帧画的是哪个窗口，
     * 状态栏才套目标色，其它窗口还原原色。判断依据不再依赖栈。
     */
    private static final java.util.Map<Object, int[]> BATT_ORIG =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<Object, int[]>());
    private static final java.util.Map<Object, int[]> BATT_APPLIED =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<Object, int[]>());
    private static final java.util.Map<Class<?>, java.lang.reflect.Method> BATT_SET_COLORS =
            java.util.Collections.synchronizedMap(
                    new java.util.HashMap<Class<?>, java.lang.reflect.Method>());

    /** 我们自己去调 setColors 时置位，避免把自己的改色结果当成原始色记下来。 */
    private static final ThreadLocal<Boolean> BATT_APPLYING = new ThreadLocal<Boolean>();

    private static final AtomicInteger BATT_REC = new AtomicInteger();
    private static final AtomicInteger BATT_DRAW = new AtomicInteger();

    /** 只记账：把系统传进来的原始三个颜色存下来，绝不修改。 */
    private static void recordBatteryColors(Object inst, Object[] args) {
        try {
            if (inst == null || args == null || args.length != 3) return;
            if (Boolean.TRUE.equals(BATT_APPLYING.get())) return;   // 自己调的不算
            for (Object a : args) if (!(a instanceof Integer)) return;
            int[] v = new int[] { (Integer) args[0], (Integer) args[1], (Integer) args[2] };
            BATT_ORIG.put(inst, v);
            // 系统重新下发颜色 = 我们之前的改动已被覆盖，下次绘制要重新套用
            BATT_APPLIED.remove(inst);
            if (BATT_REC.incrementAndGet() <= 24) {
                log("[batt-rec] " + inst.getClass().getSimpleName()
                        + " #" + Integer.toHexString(v[0])
                        + " #" + Integer.toHexString(v[1])
                        + " #" + Integer.toHexString(v[2])
                        + " qs=" + isQsContext());
            }
        } catch (Throwable t) { logOnce("batt-rec err " + t); }
    }

    /**
     * 绘制前把 drawable 的颜色设成「当前窗口应该用的颜色」。
     * 状态栏窗口 → 目标色；其它窗口 → 原始色。
     */
    private static void applyBatteryColors(Object inst, boolean wantTint) {
        try {
            if (inst == null) return;
            int[] orig = BATT_ORIG.get(inst);
            if (orig == null) return;                       // 还没记过账，不动

            // 用户明确的边界（v8.9）：
            //   固定部分（背景 / 边框 / 闪电）→ RGBA 始终由自定义颜色控制
            //   随电量变化的部分            → 未充电时自定义 RGB+alpha；
            //                                 充电时只给 alpha，RGB 用系统默认（绿）
            // 「随电量变化的那一份」不用下标猜：充电时它是唯一被系统染成
            // 高饱和状态色（绿/红）的参数，按颜色就能认出来。
            boolean charging = batteryIsCharging(inst, orig);
            int[] desired = wantTint ? tintOf(orig, charging) : orig;
            if (desired == null) desired = orig;
            if (sameColors(BATT_APPLIED.get(inst), desired)) {
                if (wantTint) tintChargeBolt(inst);      // 闪电始终自定义色
                return;
            }

            java.lang.reflect.Method m = BATT_SET_COLORS.get(inst.getClass());
            if (m == null) {
                m = findSetColors(inst.getClass());
                if (m == null) return;
                BATT_SET_COLORS.put(inst.getClass(), m);
            }
            invokeSetColors(inst, desired);
            BATT_APPLIED.put(inst, desired);
            if (BATT_DRAW.incrementAndGet() <= 24) {
                log("[batt-draw] " + inst.getClass().getSimpleName()
                        + (wantTint ? " APPLY" : " RESTORE")
                        + " charging=" + charging
                        + " #" + Integer.toHexString(desired[0])
                        + " #" + Integer.toHexString(desired[1])
                        + " #" + Integer.toHexString(desired[2]));
            }
            if (wantTint) tintChargeBolt(inst);          // 闪电始终自定义色
        } catch (Throwable t) { logOnce("batt-draw err " + t); }
    }

    /** 反射调用 setColors(int,int,int)，并挡住我们自己引发的记账。 */
    private static void invokeSetColors(Object inst, int[] v) throws Exception {
        java.lang.reflect.Method m = BATT_SET_COLORS.get(inst.getClass());
        if (m == null) {
            m = findSetColors(inst.getClass());
            if (m == null) return;
            BATT_SET_COLORS.put(inst.getClass(), m);
        }
        BATT_APPLYING.set(Boolean.TRUE);
        try {
            m.invoke(inst, Integer.valueOf(v[0]), Integer.valueOf(v[1]), Integer.valueOf(v[2]));
        } finally {
            BATT_APPLYING.remove();
        }
    }

    private static final java.util.Map<Class<?>, java.lang.reflect.Method[]> BATT_STATE_M =
            java.util.Collections.synchronizedMap(
                    new java.util.HashMap<Class<?>, java.lang.reflect.Method[]>());

    /**
     * 这支电量 drawable 是不是处于「充电 / 特殊色」状态。
     * 只看实例自己的状态方法，不依赖颜色参数 —— 颜色参数会被我们改，
     * 用它判断会形成时序依赖（实测：环已变红而 p1 仍是白）。
     */
    private static boolean batteryIsCharging(Object inst, int[] orig) {
        try {
            Class<?> c = inst.getClass();
            java.lang.reflect.Method[] ms = BATT_STATE_M.get(c);
            if (ms == null) {
                java.util.List<java.lang.reflect.Method> list =
                        new java.util.ArrayList<java.lang.reflect.Method>();
                for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                    for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                        String n = m.getName();
                        if (!n.equals("getChargeIconId") && !n.equals("getSpecialColor")) continue;
                        if (m.getParameterTypes().length != 0) continue;
                        m.setAccessible(true);
                        list.add(m);
                    }
                }
                ms = list.toArray(new java.lang.reflect.Method[0]);
                BATT_STATE_M.put(c, ms);
            }
            for (java.lang.reflect.Method m : ms) {
                Object v = m.invoke(inst);
                if (v instanceof Integer && ((Integer) v).intValue() != 0) return true;
                if (v instanceof Boolean && ((Boolean) v)) return true;
            }

            // ② 横向 / 竖向电量继承的是 BatteryBarDrawableBase，
            //    **没有** getChargeIconId/getSpecialColor（那是
            //    BatteryContentDrawableBase 才有的），所以上面那一轮必然为假 ——
            //    这就是「横/竖电量充电时绿色部分被改成自定义色」的原因。
            //    它们的充电标志是公开字段 chargingBgIconId。
            if (readIntField(inst, "chargingBgIconId") != 0) return true;
            if (readIntField(inst, "chargeIconId") != 0) return true;

            // ③ 兜底：任一颜色参数本身就是高饱和的系统状态色（充电绿/低电量红）
            for (int i = 0; i < orig.length; i++) {
                if (isSystemStatusColor(orig[i])) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static final java.util.Map<String, java.lang.reflect.Field> INT_FIELDS =
            java.util.Collections.synchronizedMap(
                    new java.util.HashMap<String, java.lang.reflect.Field>());

    /** 读实例上的任意字段（跨类层次找），找不到返回 null。 */
    private static Object readField(Object inst, String name) {
        try {
            Class<?> c = inst.getClass();
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = k.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(inst);
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 读实例上的 int 字段（跨类层次找），找不到返回 0。 */
    private static int readIntField(Object inst, String name) {
        try {
            Class<?> c = inst.getClass();
            String key = c.getName() + "#" + name;
            java.lang.reflect.Field f = INT_FIELDS.get(key);
            if (f == null && !INT_FIELDS.containsKey(key)) {
                for (Class<?> k = c; k != null && f == null; k = k.getSuperclass()) {
                    try { f = k.getDeclaredField(name); } catch (Throwable ignored) {}
                }
                if (f != null) f.setAccessible(true);
                INT_FIELDS.put(key, f);          // 允许存 null，避免反复查找
            }
            if (f == null) return 0;
            Object v = f.get(inst);
            return (v instanceof Integer) ? ((Integer) v).intValue() : 0;
        } catch (Throwable ignored) {}
        return 0;
    }

    private static final java.util.Map<Class<?>, java.lang.reflect.Field> BATT_BOLT_F =
            java.util.Collections.synchronizedMap(
                    new java.util.HashMap<Class<?>, java.lang.reflect.Field>());

    /**
     * 把闪电换成配置色。
     * 不走 Drawable#setTintList 的钩子 —— 那些 drawable 常自己重写该方法，
     * 挂在基类上收不到（日志里 [batt-bolt] 一次都没触发就是这个原因）。
     * 直接取 chargingDrawable 字段，在实例本身上调。
     */
    private static void tintChargeBolt(Object inst) {
        try {
            Class<?> c = inst.getClass();
            java.lang.reflect.Field f = BATT_BOLT_F.get(c);
            if (f == null) {
                for (Class<?> k = c; k != null && f == null; k = k.getSuperclass()) {
                    try { f = k.getDeclaredField("chargingDrawable"); } catch (Throwable ignored) {}
                }
                if (f == null) {
                    BATT_BOLT_F.put(c, null);
                    logOnce("no chargingDrawable field");
                    return;
                }
                f.setAccessible(true);
                BATT_BOLT_F.put(c, f);
            }
            Object d = f.get(inst);
            if (d == null) return;

            Integer r = recolor(0xFFFFFFFF);          // 以白色为基准取「深色背景」那组色
            if (r == null) return;
            final int want = r.intValue();

            // 全部走反射：android.jar 的 stub 里 Drawable 没有 getTintList/setTintList
            Class<?> cslCls = Class.forName("android.content.res.ColorStateList");

            // ① 色相：tint 用「不透明」的配置色。
            //    闪电的 tintMode 是 PorterDuff.SRC_ATOP（见 setColors），
            //    该模式的输出 alpha = **目标 alpha**，把 alpha 塞进 tint 完全无效
            //    —— 这就是「只有闪电没有透明度」的根因。
            int tintColor = (want & 0x00FFFFFF) | 0xFF000000;
            Object cur = null;
            try { cur = d.getClass().getMethod("getTintList").invoke(d); } catch (Throwable ignored) {}
            int curColor = 0;
            if (cur != null) {
                try {
                    curColor = (Integer) cur.getClass().getMethod("getDefaultColor").invoke(cur);
                } catch (Throwable ignored) {}
            }
            if (curColor != tintColor) {
                Object tl = cslCls.getMethod("valueOf", int.class)
                        .invoke(null, Integer.valueOf(tintColor));
                d.getClass().getMethod("setTintList", cslCls).invoke(d, tl);
                if (BATT_BOLT_LOG.incrementAndGet() <= 8) {
                    log("[batt-bolt] bolt hue -> #" + Integer.toHexString(tintColor)
                            + " (was #" + Integer.toHexString(curColor) + ")");
                }
            }

            // ② 横向 / 竖向电量的闪电是 **Bitmap**（字段 chargingBitmap），
            //    用 chargePaint 画出来，根本没有 drawable tint 可改 ——
            //    这就是「闪电未受自定义颜色控制」的原因。
            //    办法是给 chargePaint 挂 PorterDuffColorFilter(SRC_IN)：
            //    SRC_IN 的输出 alpha = Sa × Da，色相和透明度都由配置色决定。
            Object cp = readField(inst, "chargePaint");
            if (cp instanceof android.graphics.Paint) {
                android.graphics.Paint paint = (android.graphics.Paint) cp;
                android.graphics.PorterDuffColorFilter cf =
                        new android.graphics.PorterDuffColorFilter(
                                want, android.graphics.PorterDuff.Mode.SRC_IN);
                paint.setColorFilter(cf);
            }

            // ③ 透明度：只能走 Drawable#setAlpha
            final int cfgA = (want >>> 24) & 0xFF;
            int curA = -1;
            try { curA = (Integer) d.getClass().getMethod("getAlpha").invoke(d); }
            catch (Throwable ignored) {}
            if (curA != cfgA) {
                d.getClass().getMethod("setAlpha", int.class).invoke(d, Integer.valueOf(cfgA));
                if (BATT_BOLT_LOG.incrementAndGet() <= 8) {
                    log("[batt-bolt] bolt alpha " + curA + " -> " + cfgA);
                }
            }
        } catch (Throwable t) { logOnce("bolt err " + t); }
    }

    private static final AtomicInteger BATT_BOLT_LOG = new AtomicInteger();

    /**
     * 三个颜色各自的处理规则（来自反编译 CircleBatteryContentDrawable#setColors）：
     *   p1 → mCircleFrontPaint  电量环（充电时系统会把它变成绿色 #ff24b232）
     *   p2 → mCircleBackPaint   底环（30% 白）
     *   p3 → mCircleChargingPaint + circleFramePaint(30%) + 闪电(90%)
     *
     * 所以：**系统状态色（充电绿/低电量红）保留色相**，只按全局透明度缩放；
     * 中性白才换成主题色。透明度按「元素原 alpha × 配置 alpha / 255」缩放，
     * 相对层次（底环 30% < 电量环 80%）不会被抹平。
     */
    private static int[] tintOf(int[] orig, boolean charging) {
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            int cur = orig[i];
            int a = (cur >>> 24) & 0xFF;
            if (a == 0) { out[i] = cur; continue; }          // 全透明占位不动
            Integer r = recolor(cur);
            if (r == null) { out[i] = cur; continue; }
            int theme = r.intValue();
            int cfgA = (theme >>> 24) & 0xFF;
            if (charging && isSystemStatusColor(cur)) {
                // 随电量变化的那一份：保留系统色相（充电绿），只改 alpha
                out[i] = (cur & 0x00FFFFFF) | (scaleAlphaBy(a, cfgA) << 24);
            } else {
                // 自定义 RGB + 按原相对 alpha 缩放。
                //
                // 这里**不能**直接用配置的 alpha：横向/竖向电量的电量信息
                // 就编码在 p1 的 alpha 里（实测 #f0ffffff / #b5ffffff 随电量变），
                // 背景与边框也各自带 0x4d 之类的层次。统一成配置 alpha 会把
                // 它们抹平成同一个亮度 —— 表现就是「图标始终填满」。
                out[i] = (theme & 0x00FFFFFF) | (scaleAlphaBy(a, cfgA) << 24);
            }
        }
        return out;
    }

    /** 元素原 alpha × 配置 alpha / 255。配置不透明(255)时原样保留。 */
    private static int scaleAlpha(int origAlpha, int themeColor) {
        return scaleAlphaBy(origAlpha, (themeColor >>> 24) & 0xFF);
    }

    private static int scaleAlphaBy(int origAlpha, int cfgAlpha) {
        int a = origAlpha * cfgAlpha / 255;
        return a > 255 ? 255 : a;
    }

    /** 配置的透明度（取「深色背景」那组，状态栏就是这个语境）。 */
    private static int configAlpha() {
        Integer t = recolor(0xFFFFFFFF);          // 亮度 1.0 → 深色组
        return t == null ? 0xFF : ((t.intValue() >>> 24) & 0xFF);
    }

    /**
     * 是不是系统的「状态色」：充电绿、低电量红、省电黄这类高饱和色。
     * 中性白/灰返回 false（那些才应该被换成主题色）。
     */
    private static boolean isSystemStatusColor(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        int mx = Math.max(r, Math.max(g, b));
        int mn = Math.min(r, Math.min(g, b));
        return (mx - mn) > 40;
    }

    private static boolean sameColors(int[] a, int[] b) {
        if (a == null || b == null) return false;
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    /** 沿类层次找 setColors(int,int,int)。 */
    private static java.lang.reflect.Method findSetColors(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals("setColors")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 3 && p[0] == int.class && p[1] == int.class
                        && p[2] == int.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * 判断调用栈是否来自控制中心 / 通知栏。
     * 这两处的电量图标共用 CircleBatteryContentDrawable#setColors，
     * 但用户只希望改状态栏那一个。
     */
    private static boolean isQsContext() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            for (int i = 2; i < Math.min(st.length, 30); i++) {
                String cn = st[i].getClassName();
                if (cn == null) continue;
                if (cn.startsWith("com.statcolor")) continue;
                if (cn.contains(".qs.") || cn.contains(".qs")
                        || cn.contains("QuickSettings") || cn.contains("QSPanel")
                        || cn.contains("QSTile") || cn.contains("shade")
                        || cn.contains("Shade")) {
                    return true;
                }
                // 走到状态栏自己的绑定器，说明是状态栏
                if (cn.contains("StatBatteryIconInteractor")
                        || cn.contains("HomeStatusBar")
                        || cn.contains("CollapsedStatusBarFragment")) {
                    return false;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static final AtomicInteger BATT_HITS = new AtomicInteger();
    private static final AtomicInteger BATT_MISS = new AtomicInteger();

    // ───────── WiFi / 信号：按调用栈判定归属的 drawable tint

    /**
     * 实测：WiFi 与信号图标在 ui 层没有任何专用颜色方法
     * （只有 MobileIconColors.getTint 这一个取值方法），
     * 说明它们的颜色是通过通用 drawable tint 下发的。
     *
     * 因此挂 Drawable#setTint / setTintList，用**调用栈**判断这次调用
     * 是否来自移动信号 / WiFi 的 binder 或 view：
     *   com.android.systemui.statusbar.pipeline.mobile.*
     *   com.android.systemui.statusbar.pipeline.wifi.*
     *   com.oplus.systemui.statusbar.pipeline.*
     * 命中才改色，避免波及别处。
     */
    private static void hookMobileWifiTint(ClassLoader cl) {
        try {
            Class<?> drawableCls = Class.forName("android.graphics.drawable.Drawable", false, cl);
            Class<?> csl = Class.forName("android.content.res.ColorStateList", false, cl);

            java.lang.reflect.Method setTint =
                    drawableCls.getDeclaredMethod("setTint", int.class);
            XposedBridge.hookMethod(setTint, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!callerIsMobileOrWifi()) return;
                        Object a = param.args[0];
                        if (!(a instanceof Integer)) return;
                        int cur = (Integer) a;
                        int curA = (cur >>> 24) & 0xFF;
                        if (curA == 0) return;
                        Integer r = recolor(cur);
                        if (r == null) return;
                        int out = (r.intValue() & 0x00FFFFFF) | (curA << 24);
                        if (out == cur) return;
                        // 只观察不修改：改色统一交给 ImageView#setImageTintList
                        // 那条路（那里能按「View 在哪个窗口」判定状态栏/控制中心）。
                        // 这里若也改，会连控制中心的图标一起改掉。
                        if (MW_HITS.incrementAndGet() <= 12) {
                            log("[mw-see] Drawable#setTint #" + Integer.toHexString(cur)
                                    + " -> 会改成 #" + Integer.toHexString(out)
                                    + " @ " + MW_FROM.get());
                        }
                    } catch (Throwable ignored) {}
                }
            });
            log("hooked Drawable#setTint (mobile/wifi by stack)");

            java.lang.reflect.Method setTintList =
                    drawableCls.getDeclaredMethod("setTintList", csl);
            XposedBridge.hookMethod(setTintList, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        // 电量图标的闪电：setColors 内部会调
                        // chargingDrawable.setTintList(argb(0xe6, p3.rgb))
                        // —— alpha 写死 0xe6，改不了入参，只能在这一步缩放。
                        if (callerInBatteryDrawable()) {
                            Object o0 = param.args[0];
                            if (o0 != null) {
                                int c0 = (Integer) o0.getClass()
                                        .getMethod("getDefaultColor").invoke(o0);
                                Integer r0 = recolor(c0);
                                if (r0 != null) {
                                    int out0 = r0.intValue();   // rgba 全用配置色
                                    if (out0 != c0) {
                                        param.args[0] = newCsl(o0, out0);
                                        if (BATT_BOLT.incrementAndGet() <= 8) {
                                            log("[batt-bolt] 闪电 alpha #"
                                                    + Integer.toHexString(c0)
                                                    + " -> #" + Integer.toHexString(out0));
                                        }
                                    }
                                }
                            }
                            return;
                        }
                        if (!callerIsMobileOrWifi()) return;
                        Object o = param.args[0];
                        if (o == null) return;
                        int cur = (Integer) o.getClass().getMethod("getDefaultColor").invoke(o);
                        int curA = (cur >>> 24) & 0xFF;
                        if (curA == 0) return;
                        Integer r = recolor(cur);
                        if (r == null) return;
                        int out = (r.intValue() & 0x00FFFFFF) | (curA << 24);
                        if (out == cur) return;
                        // 同样只观察不修改（原因见上）
                        if (MW_HITS.incrementAndGet() <= 12) {
                            log("[mw-see] Drawable#setTintList #" + Integer.toHexString(cur)
                                    + " -> 会改成 #" + Integer.toHexString(out)
                                    + " @ " + MW_FROM.get());
                        }
                    } catch (Throwable ignored) {}
                }
            });
            log("hooked Drawable#setTintList (mobile/wifi by stack)");
        } catch (Throwable t) {
            log("hookMobileWifiTint failed: " + t);
        }
    }

    private static final AtomicInteger BATT_BOLT = new AtomicInteger();

    /** 调用栈里是否来自电量 drawable（用于识别闪电的 setTintList）。 */
    private static boolean callerInBatteryDrawable() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            int limit = Math.min(st.length, 40);
            for (int i = 0; i < limit; i++) {
                String cn = st[i].getClassName();
                if (cn != null && cn.contains("pipeline.battery.ui.drawable")) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 判断调用栈里是否来自移动信号 / WiFi 的 UI 代码。 */
    private static boolean callerIsMobileOrWifi() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            int limit = Math.min(st.length, 32);
            for (int i = 3; i < limit; i++) {
                String cn = st[i].getClassName();
                if (cn == null) continue;
                if (cn.contains("pipeline.mobile") || cn.contains("pipeline.wifi")) {
                    MW_FROM.set(cn.substring(cn.lastIndexOf('.') + 1)
                            + "." + st[i].getMethodName());
                    return true;
                }
                // 已在别的 SystemUI 业务类里，不必再往上找
                if (cn.startsWith("com.android.systemui.") || cn.startsWith("com.oplus.systemui.")) {
                    if (MW_SKIP.incrementAndGet() <= 8) {
                        log("[mw-skip] " + cn + "." + st[i].getMethodName());
                    }
                    return false;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static final AtomicInteger MW_HITS = new AtomicInteger();
    private static final AtomicInteger MW_SKIP = new AtomicInteger();
    private static final ThreadLocal<String> MW_FROM = new ThreadLocal<String>();

    // ───────── 诊断：drawable tint 全量普查（一次性）

    /**
     * v6.0 证明 WiFi/信号不走 Drawable#setTint（零 [mw-tint]），
     * 但它们也不走 setImageTintList。
     *
     * 这里在「状态栏窗口绘制期间」把每一次 drawable tint 调用都记下来
     * （类名 + 调用方），看这两个图标到底有没有出现、以什么形式出现。
     */
    private static void hookDrawableTintSurvey(ClassLoader cl) {
        try {
            Class<?> drawableCls = Class.forName("android.graphics.drawable.Drawable", false, cl);
            Class<?> csl = Class.forName("android.content.res.ColorStateList", false, cl);

            XposedBridge.hookMethod(drawableCls.getDeclaredMethod("setTint", int.class),
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            survey("setTint", param);
                        }
                    });
            XposedBridge.hookMethod(drawableCls.getDeclaredMethod("setTintList", csl),
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            survey("setTintList", param);
                        }
                    });
            log("hooked drawable tint survey");
        } catch (Throwable t) {
            log("tint survey failed: " + t);
        }
    }

    private static void survey(String kind, XC_MethodHook.MethodHookParam param) {
        try {
            if (SURVEY.incrementAndGet() > 40) return;
            Object self = param.thisObject;
            String dcls = self == null ? "?" : self.getClass().getSimpleName();
            Object a = param.args[0];
            String val;
            if (a instanceof Integer) {
                val = "#" + Integer.toHexString((Integer) a);
            } else if (a != null) {
                try {
                    Object dc = a.getClass().getMethod("getDefaultColor").invoke(a);
                    val = "#" + Integer.toHexString((Integer) dc);
                } catch (Throwable t) { val = a.getClass().getSimpleName(); }
            } else {
                val = "null";
            }
            // 调用方（第一个非 Drawable 帧）
            String from = "?";
            StackTraceElement[] st = new Throwable().getStackTrace();
            for (int i = 2; i < Math.min(st.length, 24); i++) {
                String cn = st[i].getClassName();
                if (cn.startsWith("android.graphics.drawable.Drawable")) continue;
                if (cn.startsWith("com.statcolor")) continue;      // 跳过自己的钩子帧
                if (cn.startsWith("de.robv.android.xposed")) continue;
                from = cn.substring(cn.lastIndexOf('.') + 1) + "." + st[i].getMethodName();
                break;
            }
            log("[survey] " + kind + " " + dcls + " " + val + " @ " + from);
        } catch (Throwable ignored) {}
    }

    private static final AtomicInteger SURVEY = new AtomicInteger();

    // ───────────────────────────────────────────────────────── Paint

    /**
     * 状态栏里的图标与文字最终都通过 Paint 上色。只在「正在绘制状态栏窗口」
     * 时替换，因此不会波及通知栏 / 控制中心 / 锁屏。
     *
     * 过滤：灰阶（R=G=B）且足够亮 —— 即系统为浅色图标准备的色。
     * 彩色图标（电量红、充电绿等）不动。
     */
    private static void hookPaint(ClassLoader cl) {
        try {
            Class<?> paintCls = Class.forName("android.graphics.Paint", false, cl);
            final java.lang.reflect.Method m = paintCls.getDeclaredMethod("setColor", int.class);
            m.setAccessible(true);

            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Boolean inSb = IN_STATUS_BAR.get();
                        if (inSb == null || !inSb) return;

                        Object a0 = param.args[0];
                        if (!(a0 instanceof Integer)) return;
                        int orig = (Integer) a0;

                        // 诊断：去重记录状态栏窗口内出现过的所有颜色，
                        // 用于确认右边图标是否真的经过 Paint#setColor
                        if (orig != 0 && SEEN_COLORS.add(orig) && SEEN_COLORS.size() <= 24) {
                            log("[sb-color] #" + Integer.toHexString(orig)
                                    + " gray=" + isIconColor(orig));
                        }

                        // 只改「中性色」（R=G=B 且够亮）。
                        //
                        // v7.1 曾放宽成「亮度下限」，理由是以为信号/WiFi 走这条路。
                        // 后来反编译证明它们走 ImageView tint（现由视图树扫描处理），
                        // 放宽反而出问题：电量图标在充电时会被系统把画笔设成
                        // 绿色 #ff24b232，那条路把绿色也当成"图标色"改成了主题红，
                        // 于是「充电时保持系统绿环」永远被覆盖掉。
                        // 实测状态栏绘制期间出现的颜色只有 #ccffffff(文字) 和
                        // #ff24b232(电量绿)，收紧到中性色即可两全。
                        if (!isIconColor(orig)) return;

                        Integer r = recolor(orig);
                        if (r == null) return;

                        // 直接用配置的颜色（含它自带的 alpha）
                        int out = r.intValue();
                        param.args[0] = Integer.valueOf(out);
                        int n = PAINT_HITS.incrementAndGet();
                        if (n <= 20) log("[sb-paint] #" + Integer.toHexString(orig)
                                + " -> #" + Integer.toHexString(out));
                    } catch (Throwable ignored) {}
                }
            });
            log("hooked Paint#setColor (status-bar scoped)");

            // 诊断：右上角图标可能通过 shader / colorFilter 上色，
            // 这两条路 setColor 抓不到，单独观察
            hookPaintDiag(paintCls, "setShader",
                    Class.forName("android.graphics.Shader", false, cl));
            hookPaintDiag(paintCls, "setColorFilter",
                    Class.forName("android.graphics.ColorFilter", false, cl));
        } catch (Throwable t) {
            log("hookPaint failed: " + t);
        }
    }

    /** 诊断用：观察状态栏窗口内是否走了 shader / colorFilter 上色。 */
    private static void hookPaintDiag(Class<?> paintCls, String name, Class<?> argType) {
        try {
            java.lang.reflect.Method m = paintCls.getDeclaredMethod(name, argType);
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Boolean inSb = IN_STATUS_BAR.get();
                        if (inSb == null || !inSb) return;
                        Object a = param.args[0];
                        if (a == null) return;
                        if (DIAG_COUNT.incrementAndGet() <= 12) {
                            log("[sb-" + "diag" + "] Paint#" + name + " arg="
                                    + a.getClass().getName());
                        }
                    } catch (Throwable ignored) {}
                }
            });
            log("hooked Paint#" + name + " (diag)");
        } catch (Throwable t) {
            log("Paint#" + name + " diag failed: " + t);
        }
    }

    private static final AtomicInteger DIAG_COUNT = new AtomicInteger();

    /**
     * 放宽版判定：状态栏里「不像背景」的颜色都算图标色。
     *
     * 为什么需要：ColorOS 的信号/WiFi 图标自带色系（绿/黄/红），
     * 严格的灰阶判定会把它们全部挡掉。这里改用亮度下限 +
     * 排除全透明，背景色（很暗）仍然会被放过。
     */
    private static boolean isProbablyIconColor(int c) {
        int a = (c >>> 24) & 0xFF;
        if (a < 0x40) return false;              // 几乎全透明 → 背景/占位
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        int lum = (r * 299 + g * 587 + b * 114) / 1000;
        return lum >= 0x60;                      // 太暗的放过（多为背景）
    }

    /** 灰阶且亮 —— 系统浅色图标所用色域。 */    /** 灰阶且亮 —— 系统浅色图标所用色域。 */
    private static boolean isIconColor(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        if (r != g || g != b) return false;
        if (r < 0x99) return false;
        return ((c >>> 24) & 0xFF) >= 0x60;
    }

    // ───────── 系统图标（信号/WiFi/电池）——按位置与通知图标区分

    /**
     * 实测结论（来自用户反馈 v1.3 能改电量图标）：
     * 右侧的信号、WiFi、电池图标 **也是 StatusBarIconView 实例**，
     * 颜色通过 ImageView#setImageTintList 设置，而不是 Paint。
     *
     * 但 StatusBarIconView 同时承载两类东西：
     *   - 左侧：通知图标（用户明确要求不要改）
     *   - 右侧：系统图标（用户要求改）
     *
     * 两者的区分点是**水平位置**：系统图标固定在屏幕右半部分。
     * 所以这里只对「中心点落在右半屏」的 StatusBarIconView 生效。
     */
    private static void hookSystemIconTint(Class<?> iconViewCls) {
        if (iconViewCls == null) { log("systemIconTint: no class"); return; }
        try {
            Class<?> csl = Class.forName("android.content.res.ColorStateList",
                    false, iconViewCls.getClassLoader());

            java.lang.reflect.Method mTint =
                    iconViewCls.getMethod("setImageTintList", csl);
            XposedBridge.hookMethod(mTint, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        if (!(self instanceof View)) return;
                        View v = (View) self;
                        Object o = param.args[0];
                        if (o == null) return;
                        int cur = (Integer) o.getClass().getMethod("getDefaultColor").invoke(o);
                        Integer r = recolor(cur);
                        if (r == null || r.intValue() == cur) return;

                        java.lang.reflect.Constructor<?> ctor =
                                o.getClass().getDeclaredConstructor(int.class);
                        ctor.setAccessible(true);
                        param.args[0] = ctor.newInstance(r.intValue());

                        if (SYSTINT_HITS.incrementAndGet() <= 20) {
                            log("[sys-tint@init] #" + Integer.toHexString(cur)
                                    + " -> #" + Integer.toHexString(r));
                        }
                        // 关键：此时还没布局，坐标全是 0，必须再排一次
                        scheduleRecolorAfterLayout(v);
                    } catch (Throwable t) { logOnce("systint err " + t); }
                }
            });
            log("hooked StatusBarIconView#setImageTintList (deferred by layout)");

            java.lang.reflect.Method mCf = iconViewCls.getMethod("setColorFilter",
                    int.class, Class.forName("android.graphics.PorterDuff$Mode",
                            false, iconViewCls.getClassLoader()));
            XposedBridge.hookMethod(mCf, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        if (!(self instanceof View)) return;
                        View v = (View) self;
                        Object a0 = param.args[0];
                        if (!(a0 instanceof Integer)) return;
                        int cur = (Integer) a0;
                        if (!isIconColor(cur)) return;      // 状态色（充电绿等）不动
                        Integer r = recolor(cur);
                        if (r == null || r.intValue() == cur) return;
                        param.args[0] = r;
                        scheduleRecolorAfterLayout(v);
                    } catch (Throwable ignored) {}
                }
            });
            log("hooked StatusBarIconView#setColorFilter (deferred by layout)");
        } catch (Throwable t) {
            log("hookSystemIconTint failed: " + t);
        }
    }

    /**
     * 布局完成后再判定位置并改色。
     *
     * 为什么必须延迟：setImageTintList 是在 View 构造阶段调用的，
     * 那时 getLeft()/getWidth() 还是 0，位置判定必然失败（v3.0 实测）。
     * 用 post() 排到消息队列尾部，等布局完成再跑。
     */
    private static void scheduleRecolorAfterLayout(final View v) {
        if (v == null || RECOLOR_SCHEDULED.contains(v)) return;
        RECOLOR_SCHEDULED.add(v);
        try {
            v.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (!isSystemIcon(v)) return;
                        Object d = v instanceof android.widget.ImageView
                                ? ((android.widget.ImageView) v).getDrawable() : null;
                        if (d == null) return;
                        reTintDrawable(d);
                        if (SYSTINT_HITS.incrementAndGet() <= 30) {
                            log("[sys-apply] x=" + v.getLeft() + " w=" + v.getWidth()
                                    + " cls=" + v.getClass().getSimpleName()
                                    + " drawable=" + d.getClass().getSimpleName());
                        }
                    } catch (Throwable t) { logOnce("sys-apply err " + t); }
                }
            });
        } catch (Throwable ignored) {}
    }

    /** 把 drawable 的 tint 换成配置颜色。 */
    private static void reTintDrawable(Object d) throws Throwable {
        try {
            Object tl = d.getClass().getMethod("getTintList").invoke(d);
            if (tl == null) return;
            int cur = (Integer) tl.getClass().getMethod("getDefaultColor").invoke(tl);
            Integer r = recolor(cur);
            if (r == null || r.intValue() == cur) return;
            java.lang.reflect.Constructor<?> ctor = tl.getClass().getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            Object newTl = ctor.newInstance(r.intValue());
            d.getClass().getMethod("setTintList",
                    Class.forName("android.content.res.ColorStateList")).invoke(d, newTl);
        } catch (Throwable ignored) {}
    }

    private static final java.util.Set<View> RECOLOR_SCHEDULED =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(
                            new java.util.WeakHashMap<View, Boolean>()));

    /**
     * 是不是「系统图标」：位于屏幕右半部分。
     * 首次判定时把候选位置打出来，便于核对分界线。
     */
    private static boolean isSystemIcon(View v) {
        try {
            int screenW = v.getResources().getDisplayMetrics().widthPixels;
            if (screenW <= 0) return false;
            int w = v.getWidth();
            if (w <= 0) return false;              // 还没布局，交给下一次
            int cx = v.getLeft() + w / 2;
            boolean right = cx > screenW / 2;

            if (ICON_POS_LOGGED.incrementAndGet() <= 25) {
                log("[icon-pos] x=" + v.getLeft() + " w=" + w
                        + " cx=" + cx + " screenW=" + screenW
                        + " -> " + (right ? "SYSTEM(右)" : "notification(左)"));
            }
            return right;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final AtomicInteger SYSTINT_HITS = new AtomicInteger();
    private static final AtomicInteger ICON_POS_LOGGED = new AtomicInteger();

    // ───────────── 状态栏 ImageView 的 tint（信号/WiFi/电池走这条）

    private static java.lang.reflect.Method M_SET_TINT_LIST;
    private static java.lang.reflect.Method M_SET_COLOR_FILTER;

    /** 作用域内替换 int 型颜色参数，并做节流日志。 */
    private static void replaceIntArgInStatusBar(XC_MethodHook.MethodHookParam param,
                                                 int idx, String tag) {
        try {
            Boolean inSb = IN_STATUS_BAR.get();
            if (inSb == null || !inSb) return;
            Object a = param.args[idx];
            if (!(a instanceof Integer)) return;
            int cur = (Integer) a;
            // 只改中性色，与 Paint#setColor 同一条规矩：
            // 横向/竖向电量充电时是**在绘制过程中**用 Drawable#setTint
            // 把绿色刷上去的，这里若无过滤就会把绿色改成自定义色
            // （表现：要隐藏再显示状态栏才恢复 —— 那是走了另一条时序）。
            if (!isIconColor(cur)) return;
            Integer r = recolor(cur);
            if (r == null || r.intValue() == cur) return;
            param.args[idx] = r;
            if (TINT_HITS.incrementAndGet() <= 20) {
                log(tag + " #" + Integer.toHexString(cur)
                        + " -> #" + Integer.toHexString(r));
            }
        } catch (Throwable ignored) {}
    }

    /**
     * ColorOS 的信号、WiFi、电池图标颜色来自电池色系（黄/绿/红），
     * 不是灰阶，所以过不了 Paint 那条路的灰阶过滤。
     * 它们实际是通过 ImageView 的 tint 设置的，这里直接挂上。
     *
     * 作用域同样受 IN_STATUS_BAR 约束 —— 只影响状态栏窗口。
     */
    private static void hookImageTint(ClassLoader cl) {
        try {
            Class<?> iv = Class.forName("android.widget.ImageView", false, cl);
            Class<?> csl = Class.forName("android.content.res.ColorStateList", false, cl);
            Class<?> pdm = Class.forName("android.graphics.PorterDuff$Mode", false, cl);

            M_SET_TINT_LIST = iv.getDeclaredMethod("setImageTintList", csl);
            M_SET_COLOR_FILTER = iv.getDeclaredMethod("setColorFilter", int.class, pdm);

            XposedBridge.hookMethod(M_SET_TINT_LIST, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        if (!(self instanceof View)) return;
                        View v = (View) self;

                        // 通知图标走的是同一个 setImageTintList
                        // （StatusBarIconView#onDarkChanged），调用链里没有
                        // 系统图标绑定器，因此被下面的白名单挡掉。
                        // 这里不按 View 类排除 —— 否则万一系统图标的
                        // iconView 恰好是 StatusBarIconView，就会被误伤。
                        // 快路径：这个 View 已被扫描确认是状态栏右半屏图标。
                        // 布局变化 / 系统主题变化时框架会重新下发白色 tint，
                        // 在这里当帧改掉，避免「颜色暂时失效」的闪烁。
                        if (SB_ICON_VIEWS.contains(v)) {
                            Object o0 = param.args[0];
                            if (o0 == null) return;
                            int c0 = (Integer) o0.getClass()
                                    .getMethod("getDefaultColor").invoke(o0);
                            if (((c0 >>> 24) & 0xFF) == 0) return;
                            Integer r0 = recolor(c0);
                            if (r0 == null) return;
                            if (r0.intValue() != c0) {
                                param.args[0] = newCsl(o0, r0.intValue());
                                logTintHit(v, c0, r0.intValue(), "known-icon");
                            }
                            return;
                        }

                        if (!callerIsSystemIconBinder()) return;

                        Object o = param.args[0];
                        if (o == null) return;
                        int cur = (Integer) o.getClass().getMethod("getDefaultColor").invoke(o);
                        int curA = (cur >>> 24) & 0xFF;
                        if (curA == 0) return;
                        Integer r = recolor(cur);
                        if (r == null) return;
                        final int out = r.intValue();
                        if (out == cur) return;

                        // 关键：不再看「窗口是否正在绘制」（绑定发生在绘制之外，
                        // 这就是 v7.2 之前 WiFi/信号一直没反应的原因），
                        // 改为看「这个 View 挂在哪个窗口下」。
                        // 只观察，不改。
                        // 原因：这些调用发生在子线程、视图还没挂窗口，
                        // 无法判断它是状态栏的还是控制中心的（两者共用同一批
                        // 绑定器）。在绑定期改色会把控制中心的图标一起改掉。
                        // 真正的改色由「状态栏视图树扫描」完成 —— 那里边界确定。
                        logTintHit(v, cur, out,
                                v.isAttachedToWindow() ? "attached" : "pre-attach");
                    } catch (Throwable t) { logOnce("tint err " + t); }
                }
            });

            XposedBridge.hookMethod(M_SET_COLOR_FILTER, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Boolean inSb = IN_STATUS_BAR.get();
                        if (inSb == null || !inSb) return;
                        Object a0 = param.args[0];
                        if (!(a0 instanceof Integer)) return;
                        int cur = (Integer) a0;
                        if (!isIconColor(cur)) return;      // 状态色（充电绿等）不动
                        Integer r = recolor(cur);
                        if (r == null || r.intValue() == cur) return;
                        param.args[0] = r;
                        if (TINT_HITS.incrementAndGet() <= 20) {
                            log("[sb-cf] #" + Integer.toHexString(cur)
                                    + " -> #" + Integer.toHexString(r));
                        }
                    } catch (Throwable ignored) {}
                }
            });

            log("hooked ImageView tint (status-bar scoped)");

            // Drawable 自己的 tint —— 信号/WiFi 图标的颜色常走这条路
            Class<?> drawableCls = Class.forName("android.graphics.drawable.Drawable", false, cl);
            try {
                final java.lang.reflect.Method setTint = drawableCls.getDeclaredMethod("setTint", int.class);
                XposedBridge.hookMethod(setTint, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        replaceIntArgInStatusBar(param, 0, "[sb-drw]");
                    }
                });
                log("hooked Drawable#setTint");
            } catch (Throwable t) { log("Drawable#setTint: " + t); }

            try {
                final java.lang.reflect.Method setTintList =
                        drawableCls.getDeclaredMethod("setTintList", csl);
                XposedBridge.hookMethod(setTintList, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Boolean inSb = IN_STATUS_BAR.get();
                            if (inSb == null || !inSb) return;
                            Object o = param.args[0];
                            if (o == null) return;
                            int cur = (Integer) o.getClass().getMethod("getDefaultColor").invoke(o);
                            if (!isIconColor(cur)) return;      // 状态色不动
                            Integer r = recolor(cur);
                            if (r == null || r.intValue() == cur) return;
                            param.args[0] = newCsl(o, r.intValue());   // 工厂方法；构造函数不存在
                            if (TINT_HITS.incrementAndGet() <= 20) {
                                log("[sb-drw-csl] #" + Integer.toHexString(cur)
                                        + " -> #" + Integer.toHexString(r));
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                log("hooked Drawable#setTintList");
            } catch (Throwable t) { log("Drawable#setTintList: " + t); }
        } catch (Throwable t) {
            log("hookImageTint failed: " + t);
        }
    }

    // ───────── 系统图标（WiFi / 移动信号）的 tint 判定

    /**
     * 反编译结论（classes4.dex）
     * ------------------------
     * WiFi 图标：
     *   WifiViewBinder$bind$1$1$3$1.emit()
     *     ColorStateList.valueOf(tint) → iconView.setImageTintList(...)
     *     dotView.setDecorColor(tint)
     * 移动信号：
     *   MobileIconBinder$bind$2$2$1$10$1.emit()
     *   MobileIconBinderKairos$...、SingleBindableStatusBarIconView$...emit()
     *   OPLUS: OplusWifiSignalExImpl$...emit()、*StatusBarMobileViewBinder#bindCustEx$updateTint
     *
     * 它们的颜色都存在 Drawable 的 tintFilter 里，绘制时用 ColorFilter 生效，
     * 所以 Paint#setColor 那条路对它们无效（v7.1 的 55 次 sb-paint 全是文字）。
     *
     * 通知图标走的是**同一个** setImageTintList
     * （StatusBarIconView#onDarkChanged），所以必须按调用方白名单区分。
     */
    private static final String[] ICON_BINDER_MARKERS = {
            "pipeline.wifi.ui.binder.WifiViewBinder",
            "pipeline.mobile.ui.binder.MobileIconBinder",
            "pipeline.shared.ui.view.SingleBindableStatusBarIconView",
            "OplusWifiSignalEx",
            "StatusBarMobileViewBinder",
    };

    /** 整条栈里出现任一系统图标绑定器即算命中（不依赖栈顶，避开框架 trampoline 帧）。 */
    private static boolean callerIsSystemIconBinder() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            int limit = Math.min(st.length, 48);
            for (int i = 0; i < limit; i++) {
                String cn = st[i].getClassName();
                if (cn == null) continue;
                for (String mk : ICON_BINDER_MARKERS) {
                    if (cn.contains(mk)) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * 这个 View 是不是挂在状态栏窗口下。
     * 用视图树判定，与 v7.2 电量图标的「按窗口判定」是同一套思路。
     */
    private static boolean isInStatusBarWindow(View v) {
        try {
            View cur = v;
            for (int i = 0; i < 32 && cur != null; i++) {
                String cn = cur.getClass().getName();
                if (cn.contains("StatusBarWindow")) return true;
                if (cn.contains("PhoneStatusBarView")) return true;
                android.view.ViewParent p = cur.getParent();
                if (!(p instanceof View)) return false;
                cur = (View) p;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * 造一个 ColorStateList。
     *
     * 实测 android.content.res.ColorStateList **没有 (int) 构造函数**
     * （日志：NoSuchMethodException: ColorStateList.<init> [int]），
     * 这是之前所有「改 ImageView/闪电 tint」全部静默失败的真正原因 ——
     * 每次都抛异常被 catch 掉，图标自然一直是白的。
     * 改用公开静态工厂 valueOf(int)。
     */
    private static Object newCsl(Object proto, int color) throws Exception {
        return proto.getClass().getMethod("valueOf", int.class)
                .invoke(null, Integer.valueOf(color));
    }

    /**
     * 已经先按状态栏改过色了，挂到窗口之后复核一次：
     * 真在状态栏 → 什么都不做；不在（控制中心/锁屏）→ 还原成系统原色。
     * 用 View.post：未 attach 时排进 run queue，attach 时执行。
     */
    private static final java.util.Set<View> VERIFYING =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(
                            new java.util.WeakHashMap<View, Boolean>()));

    private static void verifyAfterAttach(final View v, final Object originalCsl) {
        if (!VERIFYING.add(v)) return;
        try {
            v.post(new Runnable() {
                @Override public void run() {
                    VERIFYING.remove(v);
                    try {
                        if (isInStatusBarWindow(v)) {
                            if (ICON_TINT_LOG.incrementAndGet() <= 30) {
                                log("[icon-tint] attach 复核：在状态栏，保留改色");
                            }
                            return;
                        }
                        if (M_SET_TINT_LIST != null && originalCsl != null) {
                            M_SET_TINT_LIST.invoke(v, originalCsl);
                        }
                        if (ICON_TINT_LOG.incrementAndGet() <= 30) {
                            log("[icon-tint] attach 复核：不在状态栏，已还原 "
                                    + v.getClass().getSimpleName());
                        }
                    } catch (Throwable t) {
                        logOnce("verify err " + t);
                    }
                }
            });
        } catch (Throwable t) { logOnce("verify post err " + t); }
    }

    private static final AtomicInteger ICON_TINT_LOG = new AtomicInteger();

    private static void logTintHit(View v, int cur, int out, String where) {
        if (ICON_TINT_LOG.incrementAndGet() <= 30) {
            log("[icon-tint] " + where + " " + v.getClass().getSimpleName()
                    + " #" + Integer.toHexString(cur)
                    + " -> #" + Integer.toHexString(out));
        }
    }

    // ─────────────────────────────────────────────────────── 颜色计算

    private static Integer recolor(int original) {
        int n = CALLS.incrementAndGet();
        boolean verbose = n <= 10;

        Context ctx = sCtx;
        if (ctx == null) { if (verbose) log("no ctx"); return null; }

        Conf c = readConf(ctx);
        if (c == null) { if (verbose) log("conf unreadable"); return null; }
        if (!c.enable) { if (verbose) log("disabled"); return null; }

        if (verbose) {
            log("CALL#" + n + " orig=#" + Integer.toHexString(original)
                    + " mode=" + c.mode + " dark=" + c.dark + " light=" + c.light
                    + " alpha=" + c.alpha);
        }

        int base;
        if (Config.MODE_DARK.equals(c.mode)) {
            base = Config.parseColor(c.dark, 0xFFFFFFFF);
        } else if (Config.MODE_LIGHT.equals(c.mode)) {
            base = Config.parseColor(c.light, 0xFF000000);
        } else {
            base = luminance(original) > 0.5f
                    ? Config.parseColor(c.dark, 0xFFFFFFFF)
                    : Config.parseColor(c.light, 0xFF000000);
        }

        int out = Config.applyAlpha(base, c.alpha);

        // 幂等保护：如果传进来的已经是目标色（RGB 相同），原样返回。
        // 否则「自动」模式会二次换算 —— 用户选的红 #ff3b30 亮度只有 0.45，
        // 落到 0.5 以下就会被判成「深色图标」，再换成浅色组的 #FF000000，
        // 表现就是电量图标突然变成纯黑（20:09 截图实测）。
        int rawDark = Config.parseColor(c.dark, 0xFFFFFFFF);
        int rawLight = Config.parseColor(c.light, 0xFF000000);
        int rgb = original & 0x00FFFFFF;
        // 注意：命中时必须返回「配置颜色」（含它的 alpha），
        // 不能返回 original —— 否则把颜色设成纯白 #80FFFFFF 时，
        // 本来就是白色的状态栏元素（#ccffffff）会命中这里并被原样返回，
        // 原 alpha 0xcc 覆盖掉配置的 0x80，表现就是「设纯白时透明度不生效」。
        if (rgb == (rawDark & 0x00FFFFFF)) {
            return Integer.valueOf(Config.applyAlpha(rawDark, c.alpha));
        }
        if (rgb == (rawLight & 0x00FFFFFF)) {
            return Integer.valueOf(Config.applyAlpha(rawLight, c.alpha));
        }

        if (verbose) log("  -> #" + Integer.toHexString(out));
        return Integer.valueOf(out);
    }

    // ─────────────────────────────────────────────────────── 配置读取

    private static final class Conf {
        boolean enable;
        String dark, light, mode;
        int alpha;
    }

    private static volatile Conf sCachedConf;
    private static volatile long sCachedAt;
    private static final long CONF_TTL_MS = 1000;

    private static Conf readConf(Context ctx) {
        long now = android.os.SystemClock.uptimeMillis();
        Conf cached = sCachedConf;
        if (cached != null && now - sCachedAt < CONF_TTL_MS) return cached;

        Conf c = readConfFile();
        if (c != null) { once("conf source = FILE " + CONF_FILE); }
        else {
            c = readConfXsp(ctx);
            if (c != null) once("conf source = XSharedPreferences");
            else {
                c = readConfSp(ctx);
                if (c != null) once("conf source = SharedPreferences");
                else once("conf UNREADABLE from all 3 sources");
            }
        }
        if (c != null) { sCachedConf = c; sCachedAt = now; }
        return c;
    }

    private static void once(String msg) {
        if (!sConfLogged) { sConfLogged = true; log(msg); }
    }

    private static Conf readConfFile() {
        File f = new File(CONF_FILE);
        if (!f.exists() || !f.canRead()) return null;
        BufferedReader br = null;
        try {
            Conf c = new Conf();
            c.alpha = 100;
            c.mode = Config.MODE_AUTO;
            c.dark = Config.DEFAULT_DARK;
            c.light = Config.DEFAULT_LIGHT;

            br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                int i = line.indexOf('=');
                if (i <= 0) continue;
                String k = line.substring(0, i).trim();
                String v = line.substring(i + 1).trim();
                if (Config.KEY_ENABLE.equals(k)) c.enable = "true".equalsIgnoreCase(v);
                else if (Config.KEY_DARK.equals(k)) c.dark = v;
                else if (Config.KEY_LIGHT.equals(k)) c.light = v;
                else if (Config.KEY_MODE.equals(k)) c.mode = v;
                else if (Config.KEY_ALPHA.equals(k)) {
                    try { c.alpha = Integer.parseInt(v); } catch (Throwable ignored) {}
                }
            }
            return c;
        } catch (Throwable t) {
            return null;
        } finally {
            if (br != null) try { br.close(); } catch (Throwable ignored) {}
        }
    }

    private static Conf readConfXsp(Context ctx) {
        try {
            ClassLoader myCl = Hook.class.getClassLoader();
            Class<?> xsp = Class.forName("de.robv.android.xposed.XSharedPreferences", false, myCl);
            Object o = xsp.getConstructor(String.class, String.class, Context.class)
                    .newInstance(ctx.getPackageName(), Config.PREF_NAME, ctx);
            try { xsp.getMethod("reload").invoke(o); } catch (Throwable ignored) {}

            Boolean en = (Boolean) xsp.getMethod("getBoolean", String.class, boolean.class)
                    .invoke(o, Config.KEY_ENABLE, false);
            if (en == null) return null;

            Conf c = new Conf();
            c.enable = en.booleanValue();
            if (!c.enable) return c;

            c.dark = (String) xsp.getMethod("getString", String.class, String.class)
                    .invoke(o, Config.KEY_DARK, Config.DEFAULT_DARK);
            c.light = (String) xsp.getMethod("getString", String.class, String.class)
                    .invoke(o, Config.KEY_LIGHT, Config.DEFAULT_LIGHT);
            c.mode = (String) xsp.getMethod("getString", String.class, String.class)
                    .invoke(o, Config.KEY_MODE, Config.MODE_AUTO);
            c.alpha = (Integer) xsp.getMethod("getInt", String.class, int.class)
                    .invoke(o, Config.KEY_ALPHA, 100);
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Conf readConfSp(Context ctx) {
        try {
            SharedPreferences sp = Config.prefs(ctx);
            if (sp == null) return null;
            Conf c = new Conf();
            c.enable = sp.getBoolean(Config.KEY_ENABLE, false);
            if (!c.enable) return null;
            c.dark = sp.getString(Config.KEY_DARK, Config.DEFAULT_DARK);
            c.light = sp.getString(Config.KEY_LIGHT, Config.DEFAULT_LIGHT);
            c.mode = sp.getString(Config.KEY_MODE, Config.MODE_AUTO);
            c.alpha = sp.getInt(Config.KEY_ALPHA, 100);
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────── 工具

    private static float luminance(int color) {
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private static void captureContext(Object view) {
        if (sCtx != null || !(view instanceof View)) return;
        try {
            Context c = ((View) view).getContext();
            if (c != null) { sCtx = c; log("context = " + c.getPackageName()); }
        } catch (Throwable t) { log("captureContext: " + t); }
    }

    private static Class<?> findClass(String name, ClassLoader cl) {
        try { return XposedHelpers.findClass(name, cl); }
        catch (Throwable t) { return null; }
    }

    private static void log(String s) {
        try { XposedBridge.log(TAG + ": " + s); } catch (Throwable ignored) {}
    }
}
