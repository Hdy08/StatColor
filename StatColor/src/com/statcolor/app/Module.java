package com.statcolor.app;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 入口。
 *
 * 注意：LSPosed 只要求入口类实现 IXposedHookLoadPackage，类名与包名由
 * assets/xposed_init 指定。这里不做任何重量级工作，全部转发给 Hook。
 */
public final class Module implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;
        Hook.loadPackage(lpparam.classLoader, lpparam.packageName);
    }
}
