package h.heiErDing.hooks.core

import h.heiErDing.hooks.items.game.GameEmojiSequenceFeature
import h.heiErDing.hooks.items.homepanel.HomePanelFeature
import h.heiErDing.hooks.items.settings.SettingsFeature

/**
 * 全部功能的集中注册表（黑耳钉版本，仅保留两个核心功能）。
 */
object FeatureRegistry {
    @JvmStatic
    fun createDefaultManager(): FeatureManager {
        return FeatureManager()
            .register(SettingsFeature())
            .register(GameEmojiSequenceFeature())
            .register(HomePanelFeature())
    }
}
