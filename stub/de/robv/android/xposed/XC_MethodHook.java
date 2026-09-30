package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * 编译期 stub —— 与 XposedBridgeAPI 的公开签名一致，不编进 APK。
 * 运行时由 LSPosed 提供的真实实现接管。
 */
public class XC_MethodHook {

    public static final int PRIORITY_HIGHEST = -10000;
    public static final int PRIORITY_DEFAULT = 0;
    public static final int PRIORITY_LOWEST = 10000;

    protected XC_MethodHook() {}
    protected XC_MethodHook(int priority) {}

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        private boolean returnEarly;

        public Object getResult() { return result; }
        public void setResult(Object result) {
            this.result = result;
            this.returnEarly = true;
        }
        public Throwable getThrowable() { return throwable; }
        public boolean hasThrowable() { return throwable != null; }
        public void setThrowable(Throwable t) {
            this.throwable = t;
            this.returnEarly = true;
        }
        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
        public boolean isReturnEarly() { return returnEarly; }
        /** 反射用：把改写后的参数写回。 */
        public void setObjectExtra(Object o) {}
        public Object getObjectExtra() { return null; }
    }

    public static final class Unhook {
        private final Member member;
        public Unhook(Member m) { this.member = m; }
        public Member getHookedMethod() { return member; }
        public void unhook() {}
    }
}
