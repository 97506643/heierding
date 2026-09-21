package h.heiErDing;

import android.content.Context;

import org.luckypray.dexkit.DexKitBridge;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import h.heiErDing.dexkit.DexBridgeHolder;
import h.heiErDing.event.EventBus;
import h.heiErDing.hooks.core.DexInstallScheduler;
import h.heiErDing.hooks.core.FeatureContext;
import h.heiErDing.hooks.core.FeatureManager;
import h.heiErDing.hooks.core.FeatureRegistry;
import h.heiErDing.loader.utils.NativeLibraryLoader;
import h.heiErDing.preferences.ConfigStore;
import h.heiErDing.ui.UIRegistry;
import h.heiErDing.utils.HLog;

/**
 * 黑耳钉（h.heiErDing）LSPosed 模块入口。
 *
 * <p>本模块由 Hchat 源码派生的独立包名版本，仅保留两个原生功能：
 * <ul>
 *     <li>game_emoji_sequence —— 预设骰子 / 猜拳顺序（对应原脚本插件 F2）</li>
 *     <li>home_panel —— 主页负一屏（对应原脚本插件 F3）</li>
 * </ul>
 * 两个功能均已从 BeanShell 脚本插件改写为模块内原生 Feature，通过设置页的
 * 全局开关（{@code <featureId>_enabled}）控制启用状态。</p>
 *
 * <p>入口职责单一：加载 Native、创建 DexKitBridge、装配 FeatureContext、
 * 注册并安装全部 Feature，最后推进 DexInstallScheduler 的阶段闸门。</p>
 */
public final class ModuleEntry implements IXposedHookLoadPackage {

    private static final String TAG = "[heiErDing:ModuleEntry]";

    /** 作用域包名：仅微信。 */
    private static final String TARGET_PACKAGE = "com.tencent.mm";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (loadPackageParam == null) {
            return;
        }
        if (!TARGET_PACKAGE.equals(loadPackageParam.packageName)) {
            return;
        }
        HLog.i(TAG + " 命中目标进程: " + loadPackageParam.packageName
                + " process=" + loadPackageParam.processName);

        Context hostContext = resolveApplicationContext(loadPackageParam);
        if (hostContext == null) {
            HLog.e(TAG + " 无法获取宿主 Application，模块终止装配", null);
            return;
        }

        ClassLoader moduleClassLoader = ModuleEntry.class.getClassLoader();
        ClassLoader hostClassLoader = loadPackageParam.classLoader != null
                ? loadPackageParam.classLoader
                : hostContext.getClassLoader();

        // 1) 加载 libdexkit.so（从模块 APK 内 lib/<abi>/ 提取，避免依赖系统 loadLibrary 路径）。
        try {
            new NativeLibraryLoader().loadDexKit(hostContext, moduleClassLoader);
            HLog.i(TAG + " libdexkit.so 加载完成");
        } catch (Throwable t) {
            HLog.e(TAG + " libdexkit.so 加载失败，模块终止装配", t);
            return;
        }

        // 2) 创建 DexKitBridge（基于宿主 ClassLoader 建立全量缓存）。
        DexKitBridge dexKitBridge;
        try {
            dexKitBridge = DexKitBridge.create(hostClassLoader, true);
            HLog.i(TAG + " DexKitBridge 创建完成");
        } catch (Throwable t) {
            HLog.e(TAG + " DexKitBridge 创建失败，模块终止装配", t);
            return;
        }

        // 3) 装配依赖与上下文。
        FeatureContext featureContext;
        try {
            String apkPath = resolveModuleApkPath(moduleClassLoader);
            DexBridgeHolder dexBridgeHolder = new DexBridgeHolder(
                    dexKitBridge, hostClassLoader, apkPath);
            ConfigStore configStore = new ConfigStore(hostContext);
            EventBus eventBus = EventBus.get();
            UIRegistry uiRegistry = UIRegistry.get();

            featureContext = new FeatureContext(
                    hostContext,
                    hostContext,          // moduleContext 目前无消费方，占位复用宿主 Context
                    hostClassLoader,
                    loadPackageParam,
                    dexKitBridge,
                    eventBus,
                    configStore,
                    dexBridgeHolder,
                    uiRegistry
            );
        } catch (Throwable t) {
            HLog.e(TAG + " FeatureContext 装配失败，模块终止装配", t);
            return;
        }

        // 4) 注册并安装全部 Feature。
        FeatureManager manager;
        try {
            manager = FeatureRegistry.createDefaultManager();
            manager.initAll(featureContext);
            manager.installAll(featureContext);
            HLog.i(TAG + " 全部功能装配结束，已注册: " + manager.all().size());
        } catch (Throwable t) {
            HLog.e(TAG + " 功能装配过程异常", t);
            return;
        }

        // 5) 推进 DexKit 阶段闸门：WARMUP 级别的延迟安装任务此时才会真正执行。
        try {
            DexInstallScheduler.markDexBridgeReady();
            DexInstallScheduler.markDexWarmupReady();
            HLog.i(TAG + " DexInstallScheduler 已推进至 WARMUP");
        } catch (Throwable t) {
            HLog.e(TAG + " DexInstallScheduler 推进失败", t);
        }
    }

    /**
     * 获取宿主 Application Context。
     *
     * <p>优先从 {@link XC_LoadPackage.LoadPackageParam} 关联的 Application 获取；
     * 若宿主 Application 尚未创建，则挂载一个 {@code Instrumentation.callApplicationOnCreate}
     * 之后可用的回调，这里为保证装配时机一致性，采用反射兜底读取已创建实例。</p>
     */
    private Context resolveApplicationContext(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        // 方式一：Xposed 环境常见的 Application 反射字段。
        try {
            Object app = XposedHelpers.getObjectField(loadPackageParam, "app");
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
            // 字段不存在时静默继续。
        }

        // 方式二：从已有静态方法获取当前 Application。
        try {
            Class<?> helper = Class.forName("android.app.AndroidAppHelper");
            Object app = helper.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
            // 非 Xposed 运行环境时继续。
        }

        return null;
    }

    /**
     * 解析模块自身 APK 路径，用于 DexBridgeHolder 的调试/定位用途。
     */
    private String resolveModuleApkPath(ClassLoader moduleClassLoader) {
        try {
            String clStr = String.valueOf(moduleClassLoader);
            int idx = clStr.indexOf("module=");
            if (idx >= 0) {
                int start = idx + 7;
                int end = clStr.indexOf(",", start);
                if (end < 0) {
                    end = clStr.indexOf("]", start);
                }
                if (end > start) {
                    String path = clStr.substring(start, end).trim();
                    if (path.endsWith(".apk")) {
                        return path;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略，返回空路径。
        }
        return "";
    }
}
