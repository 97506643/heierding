package h.heiErDing.hooks.core

import android.content.Context
import de.robv.android.xposed.callbacks.XC_LoadPackage
import h.heiErDing.dexkit.DexBridgeHolder
import h.heiErDing.event.EventBus
import h.heiErDing.preferences.ConfigStore
import h.heiErDing.ui.UIRegistry
import org.luckypray.dexkit.DexKitBridge

/**
 * 功能模块运行时上下文。
 */
class FeatureContext(
    private val hostContext: Context,
    private val moduleContext: Context,
    private val hostClassLoader: ClassLoader,
    private val loadPackageParam: XC_LoadPackage.LoadPackageParam,
    private val dexKitBridge: DexKitBridge,
    private val eventBus: EventBus,
    private val configStore: ConfigStore,
    private val dexBridgeHolder: DexBridgeHolder,
    private val uiRegistry: UIRegistry
) {
    fun hostContext(): Context = hostContext
    fun moduleContext(): Context = moduleContext
    fun hostClassLoader(): ClassLoader = hostClassLoader
    fun loadPackageParam(): XC_LoadPackage.LoadPackageParam = loadPackageParam
    fun dexKitBridge(): DexKitBridge = dexKitBridge
    fun eventBus(): EventBus = eventBus
    fun configStore(): ConfigStore = configStore
    fun dexBridgeHolder(): DexBridgeHolder = dexBridgeHolder
    fun uiRegistry(): UIRegistry = uiRegistry
}
