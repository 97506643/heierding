package h.heiErDing.ui.miuix

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import h.heiErDing.preferences.ConfigStore
import h.heiErDing.ui.FeatureSettingsProvider
import h.heiErDing.ui.UIRegistry
import h.heiErDing.utils.HLog

/**
 * 轻量设置页（黑耳钉）。
 *
 * 设计目标：
 *  - 不依赖 Compose / Miuix AAR，纯 Android View 实现，编译风险最低。
 *  - 保留「设置页浮层 + 功能列表菜单结构」；主页列出所有已注册功能卡片，
 *    每张卡片带标题 / 描述 / 开关，点击进入详情；详情页可开关该功能。
 *  - 开关状态直接读写 ConfigStore 的全局 key `featureId + "_enabled"`，
 *    与 Feature.isEnabled(context) 的判定口径完全一致。
 */
object MiuixSettingsPage {

    private const val TAG = "[heiErDing:SettingsPage]"
    private const val TAG_OVERLAY = "heiErDing_settings_overlay"

    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentPage: Page = Page.Home

    @JvmStatic
    fun show(context: Context) {
        currentPage = Page.Home
        render(context)
    }

    @JvmStatic
    fun showFeature(context: Context, featureId: String): Boolean {
        if (featureId.isBlank()) return false
        val provider = findProvider(featureId)
        if (provider == null) {
            HLog.e("$TAG showFeature 未找到 provider: $featureId")
            return false
        }
        currentPage = Page.Detail(featureId)
        render(context)
        return true
    }

    @JvmStatic
    fun showScriptPluginAgent(context: Context) {
        toast(context, "本模块已无脚本插件，功能已内置为原生开关")
        show(context)
    }

    private sealed class Page {
        object Home : Page()
        data class Detail(val featureId: String) : Page()
    }

    private fun render(context: Context) {
        val activity = findActivity(context)
        if (activity == null) {
            HLog.e("$TAG render 失败：未找到宿主 Activity")
            return
        }
        mainHandler.post {
            try {
                val decor = activity.window?.decorView as? ViewGroup ?: run {
                    HLog.e("$TAG render 失败：decorView 不是 ViewGroup")
                    return@post
                }
                removeExisting(decor)
                val overlay = buildOverlay(activity)
                overlay.tag = TAG_OVERLAY
                decor.addView(overlay)
            } catch (t: Throwable) {
                HLog.e("$TAG render 异常: ${t.message}", t)
            }
        }
    }

    private fun removeExisting(decor: ViewGroup) {
        val found = decor.findViewWithTag<View>(TAG_OVERLAY)
        if (found != null) {
            (found.parent as? ViewGroup)?.removeView(found)
        }
    }

    private fun buildOverlay(activity: Activity): FrameLayout {
        val root = FrameLayout(activity)
        root.setBackgroundColor(0x99000000.toInt())
        root.setOnClickListener { closeOverlay(root) }

        val panel = LinearLayout(activity)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(0xFFF7F7F9.toInt())
        panel.isClickable = true
        root.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        panel.addView(buildTopBar(activity, root))

        val scroll = ScrollView(activity)
        scroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
        val content = LinearLayout(activity)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(activity, 16f), dp(activity, 8f), dp(activity, 16f), dp(activity, 32f))
        scroll.addView(content)
        panel.addView(scroll)

        when (val page = currentPage) {
            is Page.Home -> renderHome(activity, content)
            is Page.Detail -> renderDetail(activity, content, page.featureId)
        }
        return root
    }

    private fun buildTopBar(activity: Activity, root: FrameLayout): View {
        val bar = LinearLayout(activity)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(activity, 8f), dp(activity, 10f), dp(activity, 12f), dp(activity, 10f))
        bar.setBackgroundColor(0xFFFFFFFF.toInt())

        val back = TextView(activity)
        back.text = "\u2039"
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        back.setTextColor(0xFF333333.toInt())
        back.gravity = Gravity.CENTER
        back.setPadding(dp(activity, 12f), 0, dp(activity, 12f), 0)
        back.isClickable = true
        back.setOnClickListener {
            if (currentPage is Page.Detail) {
                currentPage = Page.Home
                render(activity)
            } else {
                closeOverlay(root)
            }
        }
        bar.addView(back, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val title = TextView(activity)
        title.text = when (val p = currentPage) {
            is Page.Home -> "黑耳钉"
            is Page.Detail -> findProvider(p.featureId)?.title() ?: "功能设置"
        }
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        title.setTextColor(0xFF111111.toInt())
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val close = TextView(activity)
        close.text = "\u2715"
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        close.setTextColor(0xFF888888.toInt())
        close.setPadding(dp(activity, 12f), dp(activity, 4f), dp(activity, 12f), dp(activity, 4f))
        close.isClickable = true
        close.setOnClickListener { closeOverlay(root) }
        bar.addView(close, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return bar
    }
    // ===================== 主页（功能列表） =====================
    private fun renderHome(activity: Activity, content: LinearLayout) {
        val providers = try {
            UIRegistry.get().getAllProviders()
        } catch (t: Throwable) {
            HLog.e("$TAG 读取 UIRegistry 失败: ${t.message}", t)
            emptyList<FeatureSettingsProvider>()
        }
        content.addView(sectionTitle(activity, "功能开关"))
        if (providers.isEmpty()) {
            content.addView(hintText(activity, "当前没有已注册的功能。"))
            content.addView(hintText(activity, "若使用的是分离进程，请确认微信已重启并完成注入。"))
            return
        }
        for (p in providers) {
            content.addView(buildFeatureCard(activity, p))
        }
        content.addView(sectionTitle(activity, "关于"))
        content.addView(hintText(activity, "黑耳钉 · 微信功能扩展模块"))
        content.addView(hintText(activity, "内置功能：预设骰子猜拳顺序、主页负一屏。"))
        content.addView(hintText(activity, "开关变更后部分功能需重启微信方可完全生效。"))
    }

    private fun buildFeatureCard(activity: Activity, p: FeatureSettingsProvider): View {
        val enabled = readEnabled(p.featureId())
        val card = LinearLayout(activity)
        card.orientation = LinearLayout.VERTICAL
        card.background = roundRect(0xFFFFFFFF.toInt(), dp(activity, 14f))
        card.setPadding(dp(activity, 16f), dp(activity, 14f), dp(activity, 16f), dp(activity, 14f))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(activity, 12f)
        card.layoutParams = lp
        card.isClickable = true
        card.setOnClickListener {
            currentPage = Page.Detail(p.featureId())
            render(activity)
        }

        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val textCol = LinearLayout(activity)
        textCol.orientation = LinearLayout.VERTICAL
        val title = TextView(activity)
        title.text = p.title()
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        title.setTextColor(0xFF111111.toInt())
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        textCol.addView(title)
        val sub = TextView(activity)
        sub.text = safeSubtitle(p)
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        sub.setTextColor(0xFF888888.toInt())
        sub.setPadding(0, dp(activity, 4f), 0, 0)
        textCol.addView(sub)
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sw = Switch(activity)
        sw.isChecked = enabled
        sw.setOnCheckedChangeListener { _, checked ->
            writeEnabled(p.featureId(), checked)
        }
        row.addView(sw, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        card.addView(row)
        return card
    }

    // ===================== 详情页 =====================
    private fun renderDetail(activity: Activity, content: LinearLayout, featureId: String) {
        val p = findProvider(featureId)
        if (p == null) {
            content.addView(hintText(activity, "未找到功能：$featureId"))
            return
        }
        content.addView(sectionTitle(activity, safeSubtitle(p)))
        content.addView(buildSwitchRow(
            activity,
            p.title(),
            "开启后该功能生效，关闭后不再干预。",
            readEnabled(featureId)
        ) { checked -> writeEnabled(featureId, checked) })
        content.addView(sectionTitle(activity, "说明"))
        content.addView(hintText(activity, detailHint(featureId)))
        content.addView(hintText(activity, "功能 ID：$featureId"))
    }

    private fun buildSwitchRow(
        activity: Activity,
        title: String,
        desc: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): View {
        val card = LinearLayout(activity)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.background = roundRect(0xFFFFFFFF.toInt(), dp(activity, 14f))
        card.setPadding(dp(activity, 16f), dp(activity, 14f), dp(activity, 16f), dp(activity, 14f))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(activity, 12f)
        card.layoutParams = lp

        val textCol = LinearLayout(activity)
        textCol.orientation = LinearLayout.VERTICAL
        val t = TextView(activity)
        t.text = title
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        t.setTextColor(0xFF111111.toInt())
        textCol.addView(t)
        val d = TextView(activity)
        d.text = desc
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        d.setTextColor(0xFF888888.toInt())
        d.setPadding(0, dp(activity, 4f), 0, 0)
        textCol.addView(d)
        card.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sw = Switch(activity)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, c -> onChange(c) }
        card.addView(sw, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return card
    }

    // ===================== 开关读写（与 Feature.isEnabled 口径一致） =====================
    private fun enabledKey(featureId: String): String = featureId + "_enabled"

    private fun readEnabled(featureId: String): Boolean {
        return try {
            ConfigStore.getGlobalBoolean(enabledKey(featureId), true)
        } catch (t: Throwable) {
            HLog.e("$TAG 读取开关失败 $featureId: ${t.message}", t)
            true
        }
    }

    private fun writeEnabled(featureId: String, enabled: Boolean) {
        try {
            ConfigStore.putGlobalBoolean(enabledKey(featureId), enabled)
        } catch (t: Throwable) {
            HLog.e("$TAG 写入开关失败 $featureId: ${t.message}", t)
        }
    }

    // ===================== 小工具 =====================
    private fun sectionTitle(activity: Activity, text: String): View {
        val tv = TextView(activity)
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        tv.setTextColor(0xFF666666.toInt())
        tv.setPadding(dp(activity, 4f), dp(activity, 10f), dp(activity, 4f), dp(activity, 8f))
        return tv
    }

    private fun hintText(activity: Activity, text: String): View {
        val tv = TextView(activity)
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        tv.setTextColor(0xFF888888.toInt())
        tv.setPadding(dp(activity, 4f), dp(activity, 2f), dp(activity, 2f), dp(activity, 2f))
        return tv
    }

    private fun roundRect(color: Int, radiusPx: Float): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = radiusPx
        return d
    }

    private fun dp(context: Context, value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
        ).toInt()
    }

    private fun safeSubtitle(p: FeatureSettingsProvider): String {
        return try {
            p.subtitle().ifBlank { "" }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun detailHint(featureId: String): String = when (featureId) {
        "game_emoji_sequence" -> "按插件逻辑预设骰子/猜拳结果顺序，仅在聊天中相关表情交互时生效。"
        "home_panel" -> "按插件逻辑提供微信主页负一屏（时钟、农历、天气、一言等）。"
        else -> "该功能的详细设置项由功能自身提供。"
    }

    private fun findProvider(featureId: String): FeatureSettingsProvider? {
        return try {
            UIRegistry.get().getAllProviders().firstOrNull { it.featureId() == featureId }
        } catch (t: Throwable) {
            HLog.e("$TAG findProvider 异常: ${t.message}", t)
            null
        }
    }

    private fun closeOverlay(root: View) {
        try {
            (root.parent as? ViewGroup)?.removeView(root)
        } catch (t: Throwable) {
            HLog.e("$TAG closeOverlay 异常: ${t.message}", t)
        }
    }

    private fun findActivity(context: Context): Activity? {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? Activity
    }

    private fun toast(context: Context, msg: String) {
        try {
            mainHandler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
        } catch (_: Throwable) {
        }
    }
}
