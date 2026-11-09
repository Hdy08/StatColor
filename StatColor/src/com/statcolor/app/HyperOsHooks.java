package com.statcolor.app;

import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * HyperOS / MIUI SystemUI 适配层。
 *
 * 与 ColorOS 的差别
 * ----------------
 * ColorOS 16 的电量图标是「一组 Drawable + setColors(int,int,int)」，
 * 颜色在每次 draw(Canvas) 前重新下发（见 Hook#hookColorOsBattery）。
 *
 * HyperOS 3 完全不同：电量图标是自绘控件
 *   com.android.systemui.statusbar.views.MiuiBatteryMeterIconView
 *     ├─ onDarkChangeInternal()  ← 颜色唯一的计算点
 *     │    mTintBgDrawable.setTintList(白/黑)                 // 外框
 *     │    mProgressClipDrawable.setTintList(状态色或白/黑)   // 电量填充
 *     │    updateProgressBackgroundBitmap()                  // 缓存填充与裁剪结果
 *     └─ onDraw(Canvas)          ← 先画填充位图，再直接画外框 / 闪电
 *   以及容器
 *   com.android.systemui.statusbar.views.MiuiBatteryMeterView
 *     ├─ onDarkChanged(ArrayList, float, int, int, int, boolean)  ← 颜色入口
 *     └─ onDarkChangedInternal()   // 闪电 ImageView 的 tint + 百分比文字颜色
 *
 * 所以适配点不是「拦截绘制参数」，而是「在它算完颜色之后改写结果」：
 *   1. after onDarkChangeInternal(图标) → 给两个 Drawable 重设 tint 并重建位图；
 *   2. after onDarkChanged / onDarkChangedInternal(容器) → 改闪电与百分比文字；
 *   3. 视图树扫描（Hook#sweep）兜底：挂到窗口较晚、或系统在别处重新下发颜色时，
 *      80ms 内纠正。扫描只在状态栏窗口里跑，所以锁屏 / AOD 的同名控件不会被改。
 *
 * 边界与 ColorOS 保持一致：饱和的系统状态色（充电绿、低电量红、省电黄、
 * 高性能色）保留色相，只按配置透明度缩放 alpha；中性白/黑才换成自定义色。
 */
final class HyperOsHooks {

    static final String CLS_BATTERY_VIEW =
            "com.android.systemui.statusbar.views.MiuiBatteryMeterView";
    static final String CLS_BATTERY_ICON =
            "com.android.systemui.statusbar.views.MiuiBatteryMeterIconView";
    static final String CLS_HOLLOW_ICON =
            "com.android.systemui.statusbar.views.MiuiHollowBatteryMeterIconView";

    /** 扫描时识别「需要特殊处理的自绘控件」。 */
    static boolean isBatteryView(String className) {
        return className.equals(CLS_BATTERY_VIEW)
                || className.equals(CLS_BATTERY_ICON)
                || className.equals(CLS_HOLLOW_ICON);
    }

    private HyperOsHooks() {}

    // ───────────────────────────────────────────────────────────── 挂载

    static void install(ClassLoader cl) {
        HyperOsTint.install(cl);
        hookBatteryContainer(cl);
        hookBatteryIcon(cl);
        hookHollowIcon(cl);
    }

    private static void hookBatteryContainer(ClassLoader cl) {
        Class<?> c = findClass(cl, CLS_BATTERY_VIEW);
        if (c == null) {
            Hook.logShared("hyperos: MISS " + CLS_BATTERY_VIEW);
            return;
        }
        int hooked = 0;
        for (Method m : c.getDeclaredMethods()) {
            String nm = m.getName();
            int pc = m.getParameterTypes().length;
            if ("onDarkChanged".equals(nm) && pc == 6) {
                hookAfter(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            Object self = p.thisObject;
                            // 只认状态栏那一个实例：锁屏 / AOD 用的是同名控件的
                            // 另一个实例，它们的前景色可能是相反的。
                            if (self instanceof View
                                    && Hook.inStatusBarWindowShared((View) self)
                                    && p.args[2] instanceof Integer) {
                                Hook.noteBarTint(((Integer) p.args[2]).intValue(),
                                        "onDarkChanged");
                            }
                        } catch (Throwable t) {
                            Hook.logOnceShared("hyperos onDarkChanged err " + t);
                        }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try { applyContainer(p.thisObject); }
                        catch (Throwable t) { Hook.logOnceShared("hyperos container err " + t); }
                    }
                });
                hooked++;
            } else if ("onDarkChangedInternal".equals(nm) && pc == 0) {
                hookAfter(m, new BatteryHandler("onDarkChangedInternal") {
                    @Override void handle(Object self) { applyContainer(self); }
                });
                hooked++;
            }
        }
        Hook.logShared("hyperos: " + CLS_BATTERY_VIEW + " hooks=" + hooked);
    }

    private static void hookBatteryIcon(ClassLoader cl) {
        Class<?> c = findClass(cl, CLS_BATTERY_ICON);
        if (c == null) {
            Hook.logShared("hyperos: MISS " + CLS_BATTERY_ICON);
            return;
        }
        int hooked = 0;
        for (Method m : c.getDeclaredMethods()) {
            if ("onDarkChangeInternal".equals(m.getName())
                    && m.getParameterTypes().length == 0) {
                hookAfter(m, new BatteryHandler("icon.onDarkChangeInternal") {
                    @Override void handle(Object self) { applyIcon(self, true); }
                });
                hooked++;
            } else if ("updateProgressBackgroundBitmap".equals(m.getName())
                    && m.getParameterTypes().length == 0) {
                // 烘焙前再摆一次 tint：MIUI 的 dark 回调会先 setTintList(系统色)
                // 再调它烘焙，偶尔就出现「闪一下旧色」；在烘焙前改回来最稳。
                hookBefore(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            Object self = p.thisObject;
                            if (self instanceof View
                                    && Hook.inStatusBarWindowShared((View) self)) {
                                IconBake pending = paintIconTints(self);
                                if (pending != null) BAKING_ICONS.put(self, pending);
                                else BAKING_ICONS.remove(self);
                            }
                        } catch (Throwable ignored) {}
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (!p.hasThrowable()) rememberBakedIcon(p.thisObject);
                            else BAKING_ICONS.remove(p.thisObject);
                        } catch (Throwable t) { Hook.logOnceShared("hyperos bake record err " + t); }
                    }
                });
                hooked++;
            } else if ("onDraw".equals(m.getName()) && m.getParameterTypes().length == 1) {
                hookBefore(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!getBool(p.thisObject, "mUseLegacyDrawable")) {
                                applyIcon(p.thisObject, true);
                            }
                        }
                        catch (Throwable t) { Hook.logOnceShared("hyperos icon draw err " + t); }
                    }
                });
                hooked++;
            }
        }
        Hook.logShared("hyperos: " + CLS_BATTERY_ICON + " hooks=" + hooked);
    }

    private static void hookHollowIcon(ClassLoader cl) {
        Class<?> c = findClass(cl, CLS_HOLLOW_ICON);
        if (c == null) {
            // 空心电量是可选样式，缺失不算错
            Hook.logShared("hyperos: no " + CLS_HOLLOW_ICON + "（可选样式）");
            return;
        }
        int hooked = 0;
        for (Method m : c.getDeclaredMethods()) {
            if ("updateBatteryDrawable".equals(m.getName())
                    && m.getParameterTypes().length == 0) {
                hookAfter(m, new BatteryHandler("hollow.updateBatteryDrawable") {
                    @Override void handle(Object self) { applyHollow(self, true); }
                });
                hooked++;
            } else if (("updateFirstBitmap".equals(m.getName())
                    || "updateSecondBitmap".equals(m.getName()))
                    && m.getParameterTypes().length == 0) {
                // 与图标那条同理：系统重置 tint 后要烘焙位图，烘焙前摆正颜色。
                hookBefore(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            Object self = p.thisObject;
                            if (self instanceof View
                                    && Hook.inStatusBarWindowShared((View) self)) {
                                paintHollowTints(self);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                hooked++;
            }
        }
        Hook.logShared("hyperos: " + CLS_HOLLOW_ICON + " hooks=" + hooked);
    }

    /** 所有电量相关 after 钩子的公共外壳：吞异常 + 调用方自行判窗口。 */
    private abstract static class BatteryHandler extends XC_MethodHook {
        private final String tag;

        BatteryHandler(String tag) { this.tag = tag; }

        abstract void handle(Object self);

        @Override protected void afterHookedMethod(MethodHookParam param) {
            try {
                handle(param.thisObject);
            } catch (Throwable t) {
                Hook.logOnceShared("hyperos " + tag + " err " + t);
            }
        }
    }

    // ───────────────────────────────────────────────────── 视图树扫描兜底

    /**
     * 扫描到电量控件时调用。和上面的钩子走同一套逻辑，
     * 但只在状态栏视图树里出现，天然不会误伤锁屏 / AOD。
     */
    static void onSweep(View v) {
        if (v == null) return;
        String cn = v.getClass().getName();
        if (CLS_BATTERY_VIEW.equals(cn)) {
            applyContainerInner(v);
        } else if (CLS_BATTERY_ICON.equals(cn)) {
            applyIcon(v, false);
        } else if (CLS_HOLLOW_ICON.equals(cn)) {
            applyHollow(v, false);
        }
    }

    // ───────────────────────────────────────────────────────── 改色实现

    private static void applyContainer(Object self) {
        if (!(self instanceof View)) return;
        View v = (View) self;
        if (!Hook.inStatusBarWindowShared(v)) return;      // 锁屏 / AOD 不碰
        applyContainerInner(v);
    }

    private static void applyContainerInner(View v) {
        Integer t = target(v);
        if (t == null) return;
        int target = t.intValue();

        // 闪电（独立 ImageView，tint 由 onDarkChangedInternal 下发）
        Object charging = get(v, "mBatteryChargingView");
        if (charging instanceof ImageView) {
            setImageTint((ImageView) charging, target);
        }
        // 电量百分比与百分号文字
        setTextColor(get(v, "mBatteryPercentView"), target);
        setTextColor(get(v, "mBatteryPercentMarkView"), target);

        // 图标本体 + 空心样式
        applyIcon(get(v, "mBatteryIconView"), false);
        applyHollow(get(v, "mHollowBatteryIconView"), false);
    }

    /**
     * @param requireStatusBar true 时要求控件确实挂在状态栏窗口下。
     *        钩子路径必须传 true —— 锁屏 / AOD 用的是同名控件的另一个实例，
     *        它们也会走 onDarkChangeInternal，不判窗口就会把锁屏一起改掉。
     *        扫描路径传 false：扫描本来就从状态栏根视图出发。
     */
    private static boolean applyIcon(Object icon, boolean requireStatusBar) {
        if (icon == null) return false;
        if (requireStatusBar && !(icon instanceof View
                && Hook.inStatusBarWindowShared((View) icon))) {
            return false;
        }
        Integer t = target(icon);
        if (t == null) return false;
        int target = t.intValue();

        boolean charging = getBool(icon, "mCharging") || getBool(icon, "mQuickCharging");
        boolean low = getBool(icon, "mLow");
        boolean save = getBool(icon, "mPowerSave");
        boolean perf = getBool(icon, "mPerformanceMode");
        int progColor = iconProgColor(icon, target);

        Drawable bg = getDrawable(icon, "mTintBgDrawable");
        Drawable prog = getDrawable(icon, "mProgressClipDrawable");
        // 某些 ROM 的 Drawable 没有可读的 getTintList()。先检查已完成的烘焙，
        // 避免把「读不到 tint」当成每帧都要改色，形成 invalidate / onDraw 循环。
        if (isIconBaked(icon, target, progColor, bg, prog)) return false;
        boolean changed = false;
        if (bg != null) changed |= setTint(bg, target);
        if (prog != null) changed |= setTint(prog, progColor);

        // 外框直接绘制，填充却读取缓存位图。Drawable 已是新 tint 并不代表位图
        // 也已更新；绘制前同时核对最后一次实际烘焙的颜色，避免只有边框先变。
        if (changed || (!getBool(icon, "mUseLegacyDrawable")
                && bg != null && prog != null)) {
            // 颜色在 updateProgressBackgroundBitmap() 里被烘焙进位图，
            // 改完 tint 必须重建，否则要等下一次布局 / 电量变化才生效。
            invoke(icon, "updateProgressBackgroundBitmap");
            if (icon instanceof View) ((View) icon).invalidate();
            if (BATT_LOG.incrementAndGet() <= 12) {
                Hook.logShared("[hyperos-batt] " + state(charging, low, save, perf)
                        + " bg=#" + hex(target) + " prog=#" + hex(progColor));
            }
        }
        return changed;
    }

    private static final java.util.Map<Object, IconBake> BAKED_ICONS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, IconBake>());
    private static final java.util.Map<Object, IconBake> BAKING_ICONS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, IconBake>());

    private static boolean isIconBaked(Object icon, int background, int progress,
                                       Drawable bg, Drawable prog) {
        IconBake baked = BAKED_ICONS.get(icon);
        if (!(icon instanceof View) || baked == null) return false;
        View v = (View) icon;
        return baked.background == background && baked.progress == progress
                && baked.width == v.getWidth() && baked.height == v.getHeight()
                && baked.bg.get() == bg && baked.prog.get() == prog;
    }

    private static void rememberBakedIcon(Object icon) {
        IconBake pending = BAKING_ICONS.remove(icon);
        if (pending == null) return;
        if (!(icon instanceof View) || !Hook.inStatusBarWindowShared((View) icon)) return;
        View v = (View) icon;
        Object bitmap = get(icon, "mProgressBackgroundBitmap");
        if (!(bitmap instanceof android.graphics.Bitmap)) return;
        android.graphics.Bitmap b = (android.graphics.Bitmap) bitmap;
        if (b.isRecycled() || b.getWidth() != v.getWidth() || b.getHeight() != v.getHeight()) return;
        BAKED_ICONS.put(icon, pending);
    }

    private static final class IconBake {
        final int background, progress, width, height;
        final java.lang.ref.WeakReference<Drawable> bg, prog;

        IconBake(View view, int background, int progress, Drawable bg, Drawable prog) {
            this.background = background;
            this.progress = progress;
            this.width = view.getWidth();
            this.height = view.getHeight();
            this.bg = new java.lang.ref.WeakReference<Drawable>(bg);
            this.prog = new java.lang.ref.WeakReference<Drawable>(prog);
        }
    }

    /**
     * 电量填充色（外框始终用 target）。
     * 充电 / 低电量 / 省电 / 高性能这些系统状态色只缩 alpha、保留色相。
     */
    private static int iconProgColor(Object icon, int target) {
        int cfgA = (target >>> 24) & 0xFF;
        if (getBool(icon, "mCharging") || getBool(icon, "mQuickCharging")) {
            return keepHue(getInt(icon, "mBatteryChargingColor"), cfgA, target);
        }
        if (getBool(icon, "mLow")) {
            return keepHue(getInt(icon, "mBatteryLowColor"), cfgA, target);
        }
        if (getBool(icon, "mPowerSave")) {
            return keepHue(getInt(icon, "mBatteryPowerSaveColor"), cfgA, target);
        }
        if (getBool(icon, "mPerformanceMode")) {
            return keepHue(getInt(icon, "mBatteryPerformanceModeColor"), cfgA, target);
        }
        return target;
    }

    /**
     * 只摆正两层 tint，不重建位图。
     *
     * 挂在 `updateProgressBackgroundBitmap()` 之前用：不管是谁触发的重建
     * （MIUI 的 dark 回调、我们自己的刷新、布局变化），烘焙进位图的都是这一套颜色，
     * 不会再出现「系统拿旧色烘焙 → 下一帧才被我们改回来」的偶发闪一下。
     */
    private static IconBake paintIconTints(Object icon) {
        Integer t = target(icon);
        if (t == null) return null;
        int target = t.intValue();
        Drawable bg = getDrawable(icon, "mTintBgDrawable");
        Drawable prog = getDrawable(icon, "mProgressClipDrawable");
        int progress = iconProgColor(icon, target);
        // 这里必须写入：系统可能刚用同一实例下发过原色，不能相信上次读回的 tint。
        if (bg != null) bg.setTintList(ColorStateList.valueOf(target));
        if (prog != null) prog.setTintList(ColorStateList.valueOf(progress));
        return icon instanceof View && bg != null && prog != null
                ? new IconBake((View) icon, target, progress, bg, prog) : null;
    }

    /** 空心样式第二层的颜色（充电/低电量等系统状态色只缩 alpha）。 */
    private static int hollowSecondColor(Object h, int target) {
        int cfgA = (target >>> 24) & 0xFF;
        if (getBool(h, "charging") || getBool(h, "quickCharging")) {
            return keepHue(getInt(h, "batteryChargingColor"), cfgA, target);
        }
        if (getBool(h, "low")) {
            return keepHue(getInt(h, "batteryLowColor"), cfgA, target);
        }
        if (getBool(h, "powerSave")) {
            return keepHue(getInt(h, "batteryPowerSaveColor"), cfgA, target);
        }
        if (getBool(h, "performanceMode")) {
            return keepHue(getInt(h, "batteryPerformanceModeColor"), cfgA, target);
        }
        return target;
    }

    /** 只摆正空心样式的 tint（重建位图前调用）。 */
    private static void paintHollowTints(Object h) {
        Integer t = target(h);
        if (t == null) return;
        int target = t.intValue();
        setTint(getDrawable(h, "firstBackground"), target);
        setTint(getDrawable(h, "secondBackground"), hollowSecondColor(h, target));
        setTint(getDrawable(h, "thirdBackground"), target);
    }

    private static boolean applyHollow(Object h, boolean requireStatusBar) {
        if (h == null) return false;
        if (requireStatusBar && !(h instanceof View
                && Hook.inStatusBarWindowShared((View) h))) {
            return false;
        }
        Integer t = target(h);
        if (t == null) return false;
        int target = t.intValue();
        boolean charging = getBool(h, "charging") || getBool(h, "quickCharging");
        boolean low = getBool(h, "low");
        boolean save = getBool(h, "powerSave");
        boolean perf = getBool(h, "performanceMode");
        int secondColor = hollowSecondColor(h, target);

        boolean changed = false;
        changed |= setTint(getDrawable(h, "firstBackground"), target);
        changed |= setTint(getDrawable(h, "secondBackground"), secondColor);
        changed |= setTint(getDrawable(h, "thirdBackground"), target);

        // 空心样式里的数字用 textPaint 直接画，没有 tint 可设
        Paint p = getPaint(h, "textPaint");
        if (p != null && p.getColor() != target) {
            p.setColor(target);
            changed = true;
        }
        // 边框/描边另一支画笔
        Paint hp = getPaint(h, "hollowTextPaint");
        if (hp != null && hp.getColor() != target) {
            hp.setColor(target);
            changed = true;
        }

        if (changed) {
            invoke(h, "updateFirstBitmap");
            invoke(h, "updateSecondBitmap");
            if (h instanceof View) ((View) h).invalidate();
            if (BATT_LOG.incrementAndGet() <= 12) {
                Hook.logShared("[hyperos-hollow] " + state(charging, low, save, perf)
                        + " bg=#" + hex(target) + " second=#" + hex(secondColor));
            }
        }
        return changed;
    }

    private static final AtomicInteger BATT_LOG = new AtomicInteger();

    private static String state(boolean charging, boolean low, boolean save, boolean perf) {
        return "charging=" + charging + " low=" + low + " save=" + save + " perf=" + perf;
    }

    /** 优先使用当前视图所在区域的分发快照；接口缺失时回退到系统电量回调的种子。 */
    private static Integer target(Object view) {
        Integer t = view instanceof View ? HyperOsTint.target((View) view) : null;
        if (t != null) return t;
        return Hook.recolorShared(Hook.barSeedShared());
    }

    /**
     * 饱和的系统状态色（充电绿 / 低电量红）只改 alpha，保留系统色相。
     * 拿不到状态色时退回自定义色。
     */
    private static int keepHue(int sysColor, int cfgAlpha, int fallback) {
        int a = (sysColor >>> 24) & 0xFF;
        if (a == 0) return fallback;
        return (sysColor & 0x00FFFFFF) | (Hook.scaleAlphaShared(a, cfgAlpha) << 24);
    }

    private static void setImageTint(ImageView iv, int color) {
        try {
            ColorStateList cur = iv.getImageTintList();
            if (cur != null && cur.getDefaultColor() == color) return;
            Hook.beginSelfApply();
            try { iv.setImageTintList(ColorStateList.valueOf(color)); }
            finally { Hook.endSelfApply(); }
        } catch (Throwable ignored) {}
    }

    private static void setTextColor(Object tv, int color) {
        if (!(tv instanceof TextView)) return;
        try {
            TextView t = (TextView) tv;
            if (t.getCurrentTextColor() == color) return;
            Hook.beginSelfApply();
            try { t.setTextColor(color); } finally { Hook.endSelfApply(); }
        } catch (Throwable ignored) {}
    }

    /**
     * 读 Drawable 当前的 tint 颜色。
     *
     * 注意两点：
     *  - android.jar 的公开 stub 里 Drawable **没有** getTintList()（它是
     *    @hide 的公开方法），直接调编译不过，只能反射；
     *  - MIUI 的 drawable 子类往往不是 public，直接 getMethod + invoke 会抛
     *    IllegalAccessException（这就是 v11.5 里 tint 恒为 null、每次扫描都
     *    重建一遍位图的原因），所以必须先 setAccessible。
     */
    private static Integer tintOf(Drawable d) {
        if (d == null) return null;
        for (Class<?> c = d.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("getTintList");
                m.setAccessible(true);
                Object v = m.invoke(d);
                if (v instanceof ColorStateList) {
                    return Integer.valueOf(((ColorStateList) v).getDefaultColor());
                }
                return null;
            } catch (NoSuchMethodException ignored) {
                // 继续往父类找
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static boolean setTint(Drawable d, int color) {
        if (d == null) return false;
        try {
            Integer cur = tintOf(d);
            if (cur != null && cur.intValue() == color) return false;
            d.setTintList(ColorStateList.valueOf(color));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String hex(int c) {
        return Integer.toHexString(c);
    }

    // ───────────────────────────────────────────────────── 反射小工具

    private static Class<?> findClass(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void hookBefore(Method m, XC_MethodHook hook) {
        try {
            m.setAccessible(true);
            XposedBridge.hookMethod(m, hook);
        } catch (Throwable t) {
            Hook.logShared("hyperos: hook " + m.getName() + " failed: " + t);
        }
    }

    private static void hookAfter(Method m, XC_MethodHook hook) {
        try {
            m.setAccessible(true);
            XposedBridge.hookMethod(m, hook);
        } catch (Throwable t) {
            Hook.logShared("hyperos: hook " + m.getName() + " failed: " + t);
        }
    }

    private static final ConcurrentHashMap<String, Object> FIELD_CACHE =
            new ConcurrentHashMap<String, Object>();
    private static final Object MISSING = new Object();

    private static Field field(Object inst, String name) {
        if (inst == null) return null;
        Class<?> c = inst.getClass();
        String key = c.getName() + "#" + name;
        Object cached = FIELD_CACHE.get(key);
        if (cached != null) return cached == MISSING ? null : (Field) cached;
        Field f = null;
        for (Class<?> k = c; k != null && f == null; k = k.getSuperclass()) {
            try {
                f = k.getDeclaredField(name);
            } catch (Throwable ignored) {}
        }
        if (f != null) {
            try { f.setAccessible(true); } catch (Throwable ignored) {}
            FIELD_CACHE.put(key, f);
        } else {
            FIELD_CACHE.put(key, MISSING);
        }
        return f;
    }

    static Object get(Object inst, String name) {
        try {
            Field f = field(inst, name);
            return f == null ? null : f.get(inst);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int getInt(Object inst, String name) {
        Object v = get(inst, name);
        return (v instanceof Integer) ? ((Integer) v).intValue() : 0;
    }

    private static boolean getBool(Object inst, String name) {
        Object v = get(inst, name);
        return (v instanceof Boolean) && ((Boolean) v).booleanValue();
    }

    private static Drawable getDrawable(Object inst, String name) {
        Object v = get(inst, name);
        return (v instanceof Drawable) ? (Drawable) v : null;
    }

    private static Paint getPaint(Object inst, String name) {
        Object v = get(inst, name);
        return (v instanceof Paint) ? (Paint) v : null;
    }

    private static void invoke(Object inst, String name) {
        if (inst == null) return;
        try {
            Method m = inst.getClass().getMethod(name);
            m.invoke(inst);
        } catch (Throwable ignored) {}
    }
}
