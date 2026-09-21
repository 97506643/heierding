package h.heiErDing.hooks.items.settings

import h.heiErDing.hooks.core.BaseFeature
import h.heiErDing.hooks.core.FeatureContext
import h.heiErDing.ui.FeatureSettingsProvider
import h.heiErDing.ui.SettingsUI
import h.heiErDing.ui.SimpleFeatureSettingsProvider

/**
 * 设置功能：只负责向模块设置页注册两个功能的设置入口。
 *
 * 与原 Hchat 的 SettingsFeature 不同，本模块不再向微信原生设置菜单注入入口，
 * 所有配置均在模块自带的设置页（MiuixSettingsPage）中完成。
 */
class SettingsFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "设置"

    override fun onFeatureInstall(context: FeatureContext) {
        registerSettingsProvider(DiceRpsEntryProvider())
        registerSettingsProvider(HomePanelEntryProvider())
    }

    companion object {
        const val ID = "settings"
    }
}

/** 预设骰子 / 猜拳顺序 设置入口。 */
class DiceRpsEntryProvider : SimpleFeatureSettingsProvider(
    DiceRpsEntryProvider.ID,
    "预设骰子 / 猜拳顺序",
    "按序列依次输出骰子、石头剪刀布结果",
    FeatureSettingsProvider.CATEGORY_ENTERTAINMENT
) {
    companion object {
        const val ID = "game_emoji_sequence"
    }
}

/** 主页负一屏 设置入口。 */
class HomePanelEntryProvider : SimpleFeatureSettingsProvider(
    HomePanelEntryProvider.ID,
    "主页负一屏",
    "微信主页右滑打开负一屏信息面板",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
) {
    companion object {
        const val ID = "home_panel"
    }
}
