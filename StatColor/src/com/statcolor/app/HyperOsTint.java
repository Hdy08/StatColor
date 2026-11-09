package com.statcolor.app;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** HyperOS 当次系统颜色分发的快照；文字、图标、电量共享同一进度和区域规则。 */
final class HyperOsTint {
    private static final Map<Integer, State> STATES = new ConcurrentHashMap<Integer, State>();
    private static final Map<View, Integer> APPLIED =
            Collections.synchronizedMap(new WeakHashMap<View, Integer>());
    private static Method sIsInAreas;

    private HyperOsTint() {}

    static void install(ClassLoader cl) {
        try {
            Class<?> api = Class.forName("com.android.systemui.plugins.DarkIconDispatcher", false, cl);
            sIsInAreas = api.getDeclaredMethod("isInAreas", Collection.class, View.class);
            sIsInAreas.setAccessible(true);
            Class<?> dispatcher = Class.forName(
                    "com.android.systemui.statusbar.phone.DarkIconDispatcherImpl", false, cl);
            for (final Method m : dispatcher.getDeclaredMethods()) {
                String name = m.getName();
                final boolean dispatch = "applyIconTint".equals(name)
                        && m.getParameterTypes().length == 0;
                if (!dispatch && !"addDarkReceiver".equals(name) && !"applyDark".equals(name)) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        capture(p.thisObject);
                    }

                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!dispatch || p.hasThrowable()) return;
                        try {
                            // 原生接收者可能不回调电量，或通过协程稍后才更新图标。
                            // 在这次分发返回前补齐整棵状态栏，下一次绘制直接呈现同一进度。
                            Hook.refreshStatusBarShared(displayId(p.thisObject));
                        } catch (Throwable t) {
                            Hook.logOnceShared("hyperos dispatch refresh err " + t);
                        }
                    }
                });
                Hook.logShared("hyperos: dispatcher#" + name + " hooked");
            }
        } catch (Throwable t) {
            Hook.logShared("hyperos: dispatcher unavailable, use view callbacks: " + t);
        }
    }

    private static int displayId(Object dispatcher) {
        Object c = HyperOsHooks.get(dispatcher, "mContext");
        if (c instanceof Context) {
            try { return ((Context) c).getDisplay().getDisplayId(); }
            catch (Throwable ignored) {}
        }
        return 0;
    }

    private static void capture(Object dispatcher) {
        try {
            Object intensity = HyperOsHooks.get(dispatcher, "mDarkIntensity");
            Object useTint = HyperOsHooks.get(dispatcher, "mUseTint");
            Object areas = HyperOsHooks.get(dispatcher, "mTintAreas");
            if (!(intensity instanceof Float) || !(useTint instanceof Boolean)
                    || !(areas instanceof Collection)) return;
            ArrayList<Rect> copy = new ArrayList<Rect>();
            for (Object area : (Collection<?>) areas) {
                if (!(area instanceof Rect)) return;
                copy.add(new Rect((Rect) area));
            }
            int id = displayId(dispatcher);
            State state = new State((Float) intensity, (Boolean) useTint, copy);
            State old = STATES.put(id, state);
            if (old == null || old.useTint != state.useTint) {
                Hook.logShared("[hyperos-sync] display=" + id + " intensity=" + state.intensity
                        + " useTint=" + state.useTint + " areas=" + copy);
            }
        } catch (Throwable t) {
            Hook.logOnceShared("hyperos dispatcher snapshot err " + t);
        }
    }

    /** 只在受控窗口中使用；按每个 View 的实际位置判断区域，兼容分屏与副屏。 */
    static Integer target(View view) {
        if (view == null || view.getDisplay() == null) return null;
        State state = STATES.get(view.getDisplay().getDisplayId());
        if (state == null || sIsInAreas == null) return null;
        try {
            boolean inArea = (Boolean) sIsInAreas.invoke(null, state.areas, view);
            float fraction = inArea ? state.intensity : 0f;
            // MIUI 非 tint 模式在 intensity > 0 时直接使用深色资源；与原生时钟一致。
            if (!state.useTint) fraction = fraction > 0f ? 1f : 0f;
            return Hook.colorAtDarkIntensityShared(fraction);
        } catch (Throwable t) {
            Hook.logOnceShared("hyperos tint area err " + t);
            return null;
        }
    }

    /** 记录自己生成的中间色，下一次扫描不再把它误认成系统状态色或重新按亮度判组。 */
    static Integer recolor(View view, int original) {
        if (!Hook.isThemeColorShared(original)
                && !Integer.valueOf(original).equals(APPLIED.get(view))) return null;
        Integer out = target(view);
        if (out == null) out = Hook.recolorShared(original);
        if (out != null) APPLIED.put(view, out);
        return out;
    }

    private static final class State {
        final float intensity;
        final boolean useTint;
        final ArrayList<Rect> areas;

        State(float intensity, boolean useTint, ArrayList<Rect> areas) {
            this.intensity = intensity;
            this.useTint = useTint;
            this.areas = areas;
        }
    }
}
