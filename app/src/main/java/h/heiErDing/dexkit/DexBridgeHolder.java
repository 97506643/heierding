package h.heiErDing.dexkit;

import org.luckypray.dexkit.DexKitBridge;

/**
 * 共享 DexKit 实例持有者。
 *
 * 统一管理 DexKitBridge、ClassLoader 的生命周期，
 * 避免每个功能模块各自创建 DexKitBridge（浪费内存和 CPU）。
 */
public final class DexBridgeHolder {
    private static final String TAG = "[heiErDing:DexBridgeHolder]";

    private final DexKitBridge dexKitBridge;
    private final ClassLoader hostClassLoader;
    private final String apkPath;

    public DexBridgeHolder(DexKitBridge dexKitBridge, ClassLoader hostClassLoader, String apkPath) {
        this.dexKitBridge = dexKitBridge;
        this.hostClassLoader = hostClassLoader;
        this.apkPath = apkPath;
    }

    public DexKitBridge getDexKitBridge() {
        return dexKitBridge;
    }

    public ClassLoader getHostClassLoader() {
        return hostClassLoader;
    }

    public String getApkPath() {
        return apkPath;
    }

    /**
     * 通用字符串搜索：在 DexKit 中查找使用了指定字符串的方法所属类。
     *
     * @param anchorStrings 用于定位的字符串锚点
     * @return 匹配到的类名列表
     */
    public java.util.List<String> findClassesByStrings(String... anchorStrings) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (dexKitBridge == null || anchorStrings == null) return result;
        try {
            org.luckypray.dexkit.query.FindClass fc = new org.luckypray.dexkit.query.FindClass();
            org.luckypray.dexkit.query.matchers.ClassMatcher cm =
                    new org.luckypray.dexkit.query.matchers.ClassMatcher();
            cm.usingStrings(java.util.Arrays.asList(anchorStrings));
            fc.matcher(cm);
            java.util.List<org.luckypray.dexkit.result.ClassData> classes = dexKitBridge.findClass(fc);
            for (org.luckypray.dexkit.result.ClassData cd : classes) {
                result.add(cd.getName());
            }
        } catch (Throwable e) {
            h.heiErDing.utils.HLog.e(TAG + " findClassesByStrings 失败: " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * 通用方法搜索：查找使用了指定字符串的方法。
     *
     * @return 匹配到的方法信息列表（className + methodName）
     */
    public java.util.List<MethodInfo> findMethodsByStrings(String... anchorStrings) {
        java.util.List<MethodInfo> result = new java.util.ArrayList<>();
        if (dexKitBridge == null || anchorStrings == null) return result;
        try {
            org.luckypray.dexkit.query.FindMethod fm = new org.luckypray.dexkit.query.FindMethod();
            org.luckypray.dexkit.query.matchers.MethodMatcher mm =
                    new org.luckypray.dexkit.query.matchers.MethodMatcher();
            mm.usingStrings(java.util.Arrays.asList(anchorStrings));
            fm.matcher(mm);
            java.util.List<org.luckypray.dexkit.result.MethodData> methods = dexKitBridge.findMethod(fm);
            for (org.luckypray.dexkit.result.MethodData md : methods) {
                result.add(new MethodInfo(md.getClassName(), md.getName()));
            }
        } catch (Throwable e) {
            h.heiErDing.utils.HLog.e(TAG + " findMethodsByStrings 失败: " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * 方法信息简单封装。
     */
    public static final class MethodInfo {
        public final String className;
        public final String methodName;

        public MethodInfo(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }

        @Override
        public String toString() {
            return className + "." + methodName;
        }
    }
}
