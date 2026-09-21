package h.heiErDing.hooks.core

import h.heiErDing.utils.HLog

/**
 * 功能模块管理器：负责注册、初始化、安装与销毁全部 Feature。
 */
class FeatureManager {
    private val features = LinkedHashMap<String, Feature>()

    fun register(feature: Feature): FeatureManager {
        features[feature.featureId()] = feature
        return this
    }

    fun all(): List<Feature> = features.values.toList()

    fun get(featureId: String): Feature? = features[featureId]

    /** 初始化全部功能（不含安装，用于准备阶段）。 */
    fun initAll(context: FeatureContext) {
        for (feature in features.values) {
            try {
                feature.onInit(context)
            } catch (t: Throwable) {
                HLog.e("[heiErDing:FeatureManager] onInit失败: ${feature.featureId()}", t)
            }
        }
    }

    /** 安装全部已启用的功能。 */
    fun installAll(context: FeatureContext) {
        for (feature in features.values) {
            try {
                if (!feature.isEnabled(context)) {
                    HLog.i("[heiErDing:FeatureManager] 跳过未启用功能: ${feature.featureId()}")
                    continue
                }
                feature.install(context)
                HLog.i("[heiErDing:FeatureManager] 已安装功能: ${feature.featureId()}")
            } catch (t: Throwable) {
                HLog.e("[heiErDing:FeatureManager] install失败: ${feature.featureId()}", t)
            }
        }
    }

    fun notifyConfigChanged(context: FeatureContext, key: String?) {
        for (feature in features.values) {
            try {
                feature.onConfigChanged(context, key)
            } catch (t: Throwable) {
                HLog.e("[heiErDing:FeatureManager] onConfigChanged失败: ${feature.featureId()}", t)
            }
        }
    }

    fun destroyAll(context: FeatureContext) {
        for (feature in features.values) {
            try {
                feature.onDestroy(context)
            } catch (t: Throwable) {
                HLog.e("[heiErDing:FeatureManager] onDestroy失败: ${feature.featureId()}", t)
            }
        }
    }
}
