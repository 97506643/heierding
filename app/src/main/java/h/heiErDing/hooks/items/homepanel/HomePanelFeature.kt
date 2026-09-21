package h.heiErDing.hooks.items.homepanel

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import h.heiErDing.hooks.core.BaseFeature
import h.heiErDing.hooks.core.HookRegistry
import h.heiErDing.hooks.core.DexInstallScheduler
import h.heiErDing.event.Events
import java.lang.ref.WeakHashMap

/**
 * 主页负一屏（完全按 F3 插件逻辑移植）
 */
class HomePanelFeature : BaseFeature() {

    @Volatile
    private var runtime: HomePanelRuntime? = null

    @Volatile
    private var featureContext: h.heiErDing.hooks.core.FeatureContext? = null

    override fun featureId(): String = ID

    override fun name(): String = "主页负一屏"

    override fun onFeatureInit(context: h.heiErDing.hooks.core.FeatureContext) {
        featureContext = context
    }

    override fun onFeatureInstall(context: h.heiErDing.hooks.core.FeatureContext) {
        featureContext = context
        val rt = HomePanelRuntime(context) { message, throwable -> logError(message, throwable) }
        runtime = rt
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: h.heiErDing.hooks.core.FeatureContext) {
        runtime?.uninstall()
        runtime = null
    }

    override fun onConfigChanged(context: h.heiErDing.hooks.core.FeatureContext, key: String?) {
        runtime?.refresh()
        scheduleInstall()
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name(), DexInstallScheduler.Stage.WARMUP) {
            runtime?.install() == true
        }
    }

    companion object {
        const val ID = "home_panel"
    }
}

/**
 * 负一屏运行时：严格对应 F3 main.java 的字段与方法集合。
 */
private class HomePanelRuntime(
    private val context: h.heiErDing.hooks.core.FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {

    private val featureId: String = HomePanelFeature.ID
    private val configStore = context.configStore()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = WeakHashMap<View, MutableMap<String, Any?>>()

    @Volatile
    private var enabled = true

    @Volatile
    private var installed = false

    private var mainTabHook: XC_MethodHook.Unhook? = null
    private var launcherHook: XC_MethodHook.Unhook? = null
    private var pagerHook: XC_MethodHook.Unhook? = null

    companion object {
        const val DRAWER_FRACTION = 0.84f
        const val HOME_TAB = 0
        const val SLOP_DP = 8
        const val SP_PANEL = "hchat_panel_settings"
        const val SP_WEATHER = "hchat_weather"
        const val KEY_ENABLED = "enabled"
        const val DEFAULT_CARD_ORDER = "time,weather,wallet,actions,quote"
        val CARD_KEYS = arrayOf("time", "weather", "wallet", "actions", "quote")
    }

    private fun toast(msg: String) {
        try {
            val ctx = context.hostContext()
            mainHandler.post {
                try {
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                } catch (ignored: Throwable) {
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    // ==================== 抽屉主体（对译 F3 buildDrawer） ====================

    private fun buildDrawer(state: MutableMap<String, Any?>, activity: Activity): FrameLayout {
        val box = FrameLayout(activity)
        val bg = GradientDrawable()
        bg.setColor(Color.rgb(250, 250, 250))
        bg.cornerRadii = floatArrayOf(0f, 0f, 28f, 28f, 28f, 28f, 0f, 0f)
        box.background = bg
        box.elevation = dp(activity, 6).toFloat()
        var statusInset = 0
        try {
            val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) statusInset = activity.resources.getDimensionPixelSize(id)
        } catch (ignored: Throwable) {
        }
        box.setPadding(0, statusInset, 0, 0)

        val sv = ScrollView(activity)
        sv.isFillViewport = true
        sv.clipToPadding = false
        val col = LinearLayout(activity)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(activity, 18), dp(activity, 20), dp(activity, 18), dp(activity, 28))
        state["showProfile"] = loadPanelBool(activity, "show_profile", true)
        state["hideWalletBalance"] = loadPanelBool(activity, "hide_wallet_balance", false)
        state["showLunar"] = loadPanelBool(activity, "show_lunar", false)
        state["cardOrder"] = loadPanelString(activity, "card_order", DEFAULT_CARD_ORDER)
        val hiddenCardsInit = loadPanelString(activity, "hidden_cards", "")
        for (k in CARD_KEYS) state["visible_$k"] = hiddenCardsInit.indexOf(k) < 0

        val profile = LinearLayout(activity)
        profile.gravity = Gravity.CENTER_VERTICAL
        val avatar = ImageView(activity)
        avatar.scaleType = ImageView.ScaleType.CENTER_CROP
        val avbg = GradientDrawable()
        avbg.setColor(Color.rgb(220, 235, 225))
        avbg.shape = GradientDrawable.OVAL
        avatar.background = avbg
        avatar.clipToOutline = true
        avatar.setImageBitmap(makeInitialBitmap(activity, "微", 58))
        state["avatarView"] = avatar
        profile.addView(avatar, LinearLayout.LayoutParams(dp(activity, 58), dp(activity, 58)))
        val pn = LinearLayout(activity)
        pn.orientation = LinearLayout.VERTICAL
        pn.setPadding(dp(activity, 10), 0, dp(activity, 4), 0)
        val nick = txt(activity, "微信用户", 20f, true)
        state["nicknameView"] = nick
        pn.addView(nick, LinearLayout.LayoutParams(-1, dp(activity, 30)))
        val status = LinearLayout(activity)
        status.gravity = Gravity.CENTER_VERTICAL
        val dot = txt(activity, "●", 10f, false)
        dot.setTextColor(Color.rgb(49, 179, 107))
        status.addView(dot, LinearLayout.LayoutParams(dp(activity, 16), dp(activity, 20)))
        val online = txt(activity, "在线", 13f, false)
        online.setTextColor(Color.DKGRAY)
        status.addView(online, LinearLayout.LayoutParams(-2, dp(activity, 20)))
        pn.addView(status, LinearLayout.LayoutParams(-1, dp(activity, 22)))
        profile.addView(pn, LinearLayout.LayoutParams(0, dp(activity, 58), 1f))
        val gear = txt(activity, "⚙", 27f, false)
        gear.gravity = Gravity.CENTER
        gear.contentDescription = "负一屏设置"
        gear.isClickable = true
        gear.isFocusable = true
        gear.setOnClickListener { showPanelSettings(state, activity) }
        profile.addView(gear, LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 58)))
        col.addView(profile, LinearLayout.LayoutParams(-1, dp(activity, 64)))
        val cardContainer = LinearLayout(activity)
        cardContainer.orientation = LinearLayout.VERTICAL
        cardContainer.clipChildren = false
        cardContainer.clipToPadding = false
        state["cardContainer"] = cardContainer
        loadRealProfileAsync(state, activity)
        nick.visibility = if (state["showProfile"] == true) View.VISIBLE else View.GONE
        avatar.visibility = if (state["showProfile"] == true) View.VISIBLE else View.GONE

        // ---------- 时间卡片 ----------
        val timeCard = card(activity, Color.rgb(244, 247, 241), 24f)
        val tc = LinearLayout(activity)
        tc.orientation = LinearLayout.VERTICAL
        tc.setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 22))
        val tr = LinearLayout(activity)
        tr.orientation = LinearLayout.VERTICAL
        val topRow = LinearLayout(activity)
        topRow.gravity = Gravity.CENTER_VERTICAL
        val clock = txt(activity, "00:00", 43f, true)
        state["clock"] = clock
        clock.setTextColor(Color.rgb(25, 28, 25))
        clock.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
        topRow.addView(clock, LinearLayout.LayoutParams(0, dp(activity, 58), 1f))
        val timeEdit = txt(activity, "✎", 18f, false)
        timeEdit.gravity = Gravity.CENTER
        timeEdit.setTextColor(Color.rgb(65, 95, 70))
        timeEdit.tag = "time"
        timeEdit.setOnClickListener { showLunarSettings(state, activity) }
        topRow.addView(timeEdit, LinearLayout.LayoutParams(dp(activity, 30), dp(activity, 58)))
        tr.addView(topRow, LinearLayout.LayoutParams(-1, dp(activity, 58)))
        val date = txt(activity, "", 16f, false)
        state["date"] = date
        date.setTextColor(Color.rgb(75, 78, 73))
        date.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
        date.isSingleLine = true
        date.maxLines = 1
        date.includeFontPadding = true
        tr.addView(date, LinearLayout.LayoutParams(-1, dp(activity, 34)))
        val lunar = txt(activity, "", 15f, false)
        state["lunarText"] = lunar
        lunar.setTextColor(Color.rgb(88, 101, 89))
        lunar.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
        lunar.isSingleLine = true
        lunar.maxLines = 1
        lunar.includeFontPadding = true
        lunar.visibility = if (state["showLunar"] == true) View.VISIBLE else View.GONE
        tr.addView(lunar, LinearLayout.LayoutParams(-1, dp(activity, 34)))
        tc.addView(tr, LinearLayout.LayoutParams(-1, -2))
        val greeting = txt(activity, "", 21f, true)
        state["greeting"] = greeting
        greeting.setTextColor(Color.rgb(220, 58, 58))
        greeting.gravity = Gravity.LEFT or Gravity.TOP
        greeting.setLineSpacing(3f, 1.0f)
        greeting.isSingleLine = false
        greeting.maxLines = 4
        val gp = LinearLayout.LayoutParams(-1, -2)
        gp.setMargins(0, dp(activity, 4), 0, 0)
        tc.addView(greeting, gp)
        timeCard.addView(tc, FrameLayout.LayoutParams(-1, -2))
        state["card_time"] = timeCard
        refreshLunarDisplay(state, activity)

        // ---------- 天气卡片 ----------
        val weather = card(activity, Color.rgb(181, 239, 184), 24f)
        val wc = LinearLayout(activity)
        wc.orientation = LinearLayout.VERTICAL
        wc.setPadding(dp(activity, 18), dp(activity, 14), dp(activity, 18), dp(activity, 6))
        val wh = LinearLayout(activity)
        wh.gravity = Gravity.CENTER_VERTICAL
        val wl = txt(activity, "⌖  正在定位…", 18f, true)
        wl.setTextColor(Color.rgb(31, 91, 48))
        wl.isSingleLine = true
        wl.isClickable = true
        wl.setOnClickListener { showWeatherCityDialog(state, activity) }
        state["weatherCity"] = wl
        wh.addView(wl, LinearLayout.LayoutParams(0, dp(activity, 32), 1f))
        val wu = txt(activity, "更新中…", 14f, false)
        wu.setTextColor(Color.rgb(68, 128, 79))
        wu.gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        wu.isSingleLine = true
        state["weatherUpdate"] = wu
        wh.addView(wu, LinearLayout.LayoutParams(-2, dp(activity, 32)))
        wc.addView(wh, LinearLayout.LayoutParams(-1, dp(activity, 34)))
        val wm = LinearLayout(activity)
        wm.gravity = Gravity.CENTER_VERTICAL
        val leftWeather = LinearLayout(activity)
        leftWeather.orientation = LinearLayout.VERTICAL
        val temp = txt(activity, "--°", 43f, true)
        temp.setTextColor(Color.rgb(22, 87, 43))
        state["weatherTemp"] = temp
        leftWeather.addView(temp, LinearLayout.LayoutParams(-1, dp(activity, 61)))
        val feels = txt(activity, "体感 --°", 17f, false)
        feels.setTextColor(Color.rgb(55, 111, 63))
        state["weatherFeels"] = feels
        leftWeather.addView(feels, LinearLayout.LayoutParams(-1, dp(activity, 31)))
        wm.addView(leftWeather, LinearLayout.LayoutParams(0, dp(activity, 92), 1f))
        val rightWeather = LinearLayout(activity)
        rightWeather.orientation = LinearLayout.VERTICAL
        rightWeather.gravity = Gravity.CENTER_HORIZONTAL
        val weatherIcon = txt(activity, "☁☀", 43f, false)
        weatherIcon.gravity = Gravity.CENTER
        weatherIcon.setTextColor(Color.rgb(22, 87, 43))
        state["weatherIcon"] = weatherIcon
        rightWeather.addView(weatherIcon, LinearLayout.LayoutParams(dp(activity, 112), dp(activity, 60)))
        val condition = txt(activity, "天气获取中", 17f, true)
        condition.gravity = Gravity.CENTER
        condition.setTextColor(Color.rgb(22, 87, 43))
        condition.isSingleLine = true
        state["weatherCondition"] = condition
        rightWeather.addView(condition, LinearLayout.LayoutParams(dp(activity, 112), dp(activity, 31)))
        wm.addView(rightWeather, LinearLayout.LayoutParams(dp(activity, 120), dp(activity, 92)))
        wc.addView(wm, LinearLayout.LayoutParams(-1, dp(activity, 98)))
        val divider = View(activity)
        divider.setBackgroundColor(Color.argb(45, 30, 105, 50))
        wc.addView(divider, LinearLayout.LayoutParams(-1, dp(activity, 1)))
        val metrics = LinearLayout(activity)
        metrics.gravity = Gravity.CENTER_VERTICAL
        addMetricWeather(metrics, activity, "最高 / 最低", "--° / --°")
        addMetricWeather(metrics, activity, "湿度", "--%")
        addMetricWeather(metrics, activity, "风速", "-- km/h")
        state["weatherHighLow"] = (metrics.getChildAt(0) as LinearLayout).getChildAt(0)
        state["weatherHumidity"] = (metrics.getChildAt(2) as LinearLayout).getChildAt(0)
        state["weatherWind"] = (metrics.getChildAt(4) as LinearLayout).getChildAt(0)
        wc.addView(metrics, LinearLayout.LayoutParams(-1, dp(activity, 68)))
        weather.addView(wc, FrameLayout.LayoutParams(-1, -2))
        state["card_weather"] = weather

        // ---------- 钱包卡片 ----------
        val wallet = card(activity, Color.rgb(181, 239, 184), 24f)
        val wv = LinearLayout(activity)
        wv.orientation = LinearLayout.VERTICAL
        wv.setPadding(dp(activity, 18), dp(activity, 15), dp(activity, 18), dp(activity, 14))
        val wt = txt(activity, "▣  当前余额", 15f, true)
        wt.setTextColor(Color.rgb(45, 75, 50))
        wv.addView(wt, LinearLayout.LayoutParams(-1, dp(activity, 28)))
        val balance = txt(activity, "¥ ****", 32f, true)
        balance.setTextColor(Color.rgb(45, 75, 50))
        state["walletBalance"] = balance
        state["walletBalanceText"] = "****"
        balance.visibility = if (state["hideWalletBalance"] == true) View.GONE else View.VISIBLE
        wv.addView(balance, LinearLayout.LayoutParams(-1, dp(activity, 55)))
        val wr = LinearLayout(activity)
        wr.gravity = Gravity.CENTER_VERTICAL
        addActionButton(wr, activity, state, "扫一扫", "com.tencent.mm.plugin.scanner.ui.BaseScanUI", true)
        addActionButton(wr, activity, state, "付款码", "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI", false)
        wv.addView(wr, LinearLayout.LayoutParams(-1, dp(activity, 50)))
        wallet.addView(wv, FrameLayout.LayoutParams(-1, -2))
        state["card_wallet"] = wallet

        // ---------- 快捷操作卡片 ----------
        val actionCard = card(activity, Color.rgb(245, 245, 245), 22f)
        val ac = LinearLayout(activity)
        ac.orientation = LinearLayout.VERTICAL
        ac.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
        val row1 = LinearLayout(activity)
        row1.gravity = Gravity.CENTER
        addActionTile(row1, activity, state, "➕\n添加朋友", "com.tencent.mm.plugin.subapp.ui.pluginapp.AddMoreFriendsUI")
        addActionTile(row1, activity, state, "◎\n朋友圈", "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI")
        addActionTile(row1, activity, state, "◉\n视频号", "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI")
        ac.addView(row1, LinearLayout.LayoutParams(-1, dp(activity, 82)))
        val row2 = LinearLayout(activity)
        row2.gravity = Gravity.CENTER
        addActionTile(row2, activity, state, "★\n收藏", "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI")
        addActionTile(row2, activity, state, "⌘\n扫一扫", "com.tencent.mm.plugin.scanner.ui.BaseScanUI")
        addActionTile(row2, activity, state, "⚙\n微信设置", "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI")
        ac.addView(row2, LinearLayout.LayoutParams(-1, dp(activity, 82)))
        actionCard.addView(ac, FrameLayout.LayoutParams(-1, -2))
        state["card_actions"] = actionCard

        // ---------- 一言卡片 ----------
        val quote = card(activity, Color.rgb(235, 246, 236), 24f)
        val qc = LinearLayout(activity)
        qc.orientation = LinearLayout.VERTICAL
        qc.setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 16))
        val qh = LinearLayout(activity)
        qh.gravity = Gravity.CENTER_VERTICAL
        val qtitle = txt(activity, "✦  一言", 16f, true)
        qtitle.setTextColor(Color.rgb(45, 100, 55))
        qh.addView(qtitle, LinearLayout.LayoutParams(0, dp(activity, 28), 1f))
        val qedit = txt(activity, "✎", 18f, false)
        qedit.gravity = Gravity.CENTER
        qedit.setTextColor(Color.rgb(65, 105, 70))
        qedit.contentDescription = "一言设置"
        qedit.setOnClickListener { showQuoteSettings(state, activity) }
        qh.addView(qedit, LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 28)))
        qc.addView(qh, LinearLayout.LayoutParams(-1, dp(activity, 30)))
        val qt = txt(activity, "“ 生活明朗，万物可爱。 ”", 18f, true)
        qt.setTextColor(Color.rgb(39, 73, 43))
        qt.gravity = Gravity.CENTER
        qt.setLineSpacing(3f, 1.0f)
        qt.maxLines = 4
        qt.isClickable = true
        qt.setOnClickListener { loadQuoteAsync(state, activity) }
        state["quoteText"] = qt
        qc.addView(qt, LinearLayout.LayoutParams(-1, dp(activity, 104)))
        val qs = txt(activity, "— 一言", 13f, false)
        qs.setTextColor(Color.rgb(83, 112, 87))
        qs.gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        state["quoteSource"] = qs
        qc.addView(qs, LinearLayout.LayoutParams(-1, dp(activity, 34)))
        quote.addView(qc, FrameLayout.LayoutParams(-1, -2))
        state["card_quote"] = quote

        col.addView(cardContainer, LinearLayout.LayoutParams(-1, -2))
        state["cardColumn"] = cardContainer
        applyCardLayout(state, activity)
        sv.addView(col, ViewGroup.LayoutParams(-1, -2))
        box.addView(sv, FrameLayout.LayoutParams(-1, -1))
        return box
    }

    fun install(): Boolean {
        if (installed) return true
        enabled = configStore.getBoolean(featureId, KEY_ENABLED, true)
        if (!enabled) {
            logger("主页负一屏：当前关闭", null)
            installed = true
            return true
        }
        try {
            installMainTabHook()
            val pager = context.dexKitBridge()?.findClass("com.tencent.mm.ui.base.CustomViewPager")
            if (pager != null) {
                val dispatch = findMethod(pager, "dispatchTouchEvent", 1)
                if (dispatch != null) {
                    logger("CustomViewPager.dispatchTouchEvent Hook 已安装", null)
                    pagerHook = HookRegistry.get().hook(dispatch, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            handlePagerTouch(param)
                        }
                    })
                } else {
                    logger("错误：找不到 CustomViewPager.dispatchTouchEvent", null)
                }
            }
            val launcher = context.dexKitBridge()?.findClass("com.tencent.mm.ui.LauncherUI")
            if (launcher != null) {
                val ldispatch = findMethod(launcher, "dispatchTouchEvent", 1)
                if (ldispatch != null) {
                    launcherHook = HookRegistry.get().hook(ldispatch, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            handleLauncherTouchLite(param)
                        }
                    })
                    logger("LauncherUI.dispatchTouchEvent Hook 已安装：仅负一屏打开后关闭使用", null)
                }
            }
            installed = true
            logger("主页负一屏已加载", null)
            return true
        } catch (t: Throwable) {
            logger("主页负一屏加载失败", t)
            return false
        }
    }

    fun uninstall() {
        try {
            sessions.values.toList().forEach { st ->
                try { detachSession(st) } catch (t: Throwable) { logger("卸载分离异常", t) }
            }
            sessions.clear()
            mainTabHook?.unhook()
            launcherHook?.unhook()
            pagerHook?.unhook()
            mainTabHook = null
            launcherHook = null
            pagerHook = null
            installed = false
            logger("主页负一屏已卸载", null)
        } catch (t: Throwable) {
            logger("主页负一屏卸载异常", t)
        }
    }

    fun refresh() {
        enabled = configStore.getBoolean(featureId, KEY_ENABLED, true)
    }

    private fun findMethod(clazz: Class<*>?, name: String, count: Int): java.lang.reflect.Method? {
        if (clazz == null) return null
        try {
            clazz.declaredMethods.forEach { m ->
                if (m.name == name && m.parameterTypes.size == count) {
                    m.isAccessible = true
                    return m
                }
            }
            clazz.methods.forEach { m ->
                if (m.name == name && m.parameterTypes.size == count) {
                    m.isAccessible = true
                    return m
                }
            }
        } catch (ignored: Throwable) {}
        return null
    }

    private fun installMainTabHook() {
        try {
            val dexKit = context.dexKitBridge() ?: run {
                logger("错误：dexKit=null，无法执行共享 DexKit 查询", null)
                return
            }
            val candidates = dexKit.findMemberList(
                listOf("MicroMsg.LauncherUI.MainTabUI", "doOnCreate")
            )
            if (candidates.isNullOrEmpty()) {
                logger("DexKit findMemberList 返回空", null)
                return
            }
            logger("DexKit findMemberList 候选数量=${candidates.size}", null)
            var target: java.lang.reflect.Method? = null
            var index = 0
            for (member in candidates) {
                index++
                try {
                    if (member !is java.lang.reflect.Method) {
                        logger("DexKit候选[$index] 非 Method=$member", null)
                        continue
                    }
                    val owner = member.declaringClass.name
                    logger("DexKit候选[$index]=${member.toGenericString()}", null)
                    if ("com.tencent.mm.ui.MainTabUI" == owner && target == null) {
                        target = member
                    }
                } catch (one: Throwable) {
                    logger("解析 DexKit 候选失败", one)
                }
            }
            if (target == null) {
                logger("错误：findMemberList 没有返回 MainTabUI 成员", null)
                return
            }
            var noArg: java.lang.reflect.Method? = null
            for (member in candidates) {
                try {
                    if (member !is java.lang.reflect.Method) continue
                    if ("com.tencent.mm.ui.MainTabUI" == member.declaringClass.name &&
                        member.parameterTypes.isEmpty()
                    ) {
                        noArg = member
                        break
                    }
                } catch (ignored: Throwable) {}
            }
            if (noArg != null) target = noArg
            val exactTarget = target
            exactTarget.isAccessible = true
            mainTabHook = HookRegistry.get().hook(exactTarget, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (param.thisObject != null) attachMainTabUI(param.thisObject)
                    } catch (t: Throwable) {
                        logger("MainTabUI Hook 回调异常=$exactTarget", t)
                    }
                }
            })
            logger("MainTabUI Hook 已安装=$exactTarget；来源=dexKit.findMemberList", null)
        } catch (t: Throwable) {
            logger("dexKit.findMemberList 查询失败", t)
        }
    }

    private fun attachMainTabUI(mainTabUI: Any?) {
        if (!enabled || mainTabUI == null) return
        try {
            val c = mainTabUI.javaClass
            logger("MainTabUI实例=${c.name}", null)
            var vf: java.lang.reflect.Field? = null
            var x: Class<*>? = c
            while (x != null && vf == null) {
                try { vf = x.getDeclaredField("mViewPager") } catch (ignored: Throwable) { x = x.superclass }
            }
            if (vf == null) {
                logger("错误：MainTabUI 找不到字段 mViewPager", null)
                return
            }
            vf.isAccessible = true
            val vpObj = vf.get(mainTabUI)
            if (vpObj !is View) {
                logger("错误：mViewPager 不是 View，实际=${vpObj?.javaClass?.name}", null)
                return
            }
            val pager = vpObj
            logger("MainTabUI.mViewPager=${pager.javaClass.name}", null)
            if (sessions.containsKey(pager)) return
            val ctx = pager.context
            val activity: Activity? = if (ctx is Activity) ctx else null
            if (activity == null) {
                logger("错误：mViewPager Context 不是 Activity=$ctx", null)
                return
            }
            val info = getParentInfo(pager)
            if (info == null) {
                logger("错误：mViewPager 尚未挂载到 ViewGroup，稍后重试", null)
                mainHandler.postDelayed({ attachMainTabUI(mainTabUI) }, 500)
                return
            }
            if (info.parent !is FrameLayout) {
                logger("错误：mViewPager parent 不是 FrameLayout，parent=${info.parent?.javaClass?.name}", null)
                return
            }
            val state = HashMap<String, Any?>()
            state["activity"] = activity
            state["pager"] = pager
            state["decor"] = activity.window.decorView as FrameLayout
            state["attached"] = false
            state["tracking"] = false
            state["dragging"] = false
            state["progress"] = 0f
            state["animToken"] = 0
            sessions[pager] = state
            attachSession(state)
            logger("负一屏已成功绑定 MainTabUI.mViewPager=${pager.javaClass.name}", null)
        } catch (t: Throwable) {
            logger("attachMainTabUI失败", t)
        }
    }

    private class ViewParentInfo(val parent: ViewGroup?)

    private fun getParentInfo(v: View): ViewParentInfo? {
        try {
            val p = v.parent
            if (p is ViewGroup) return ViewParentInfo(p)
        } catch (ignored: Throwable) {}
        return null
    }

    private fun attachSession(state: MutableMap<String, Any?>) {
        if (state["attached"] == true) return
        try {
            val activity = state["activity"] as Activity
            val decor = state["decor"] as FrameLayout
            val root = FrameLayout(activity)
            root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            root.isClickable = true
            root.isFocusable = true
            root.visibility = View.GONE
            val dim = View(activity)
            dim.setBackgroundColor(android.graphics.Color.BLACK)
            dim.alpha = 0f
            dim.isClickable = true
            dim.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        state["dimDownX"] = e.x
                        state["dimDownY"] = e.y
                        true
                    }
                    MotionEvent.ACTION_UP -> { closeSession(state); true }
                    else -> true
                }
            }
            root.addView(dim, FrameLayout.LayoutParams(-1, -1))
            val drawer = buildDrawer(state, activity)
            drawer.isClickable = true
            val dlp = FrameLayout.LayoutParams(1, -1)
            dlp.gravity = android.view.Gravity.LEFT
            root.addView(drawer, dlp)
            decor.addView(root, FrameLayout.LayoutParams(-1, -1))
            state["root"] = root
            state["dim"] = dim
            state["drawer"] = drawer
            state["attached"] = true
            updateSessionSize(state)
            val h = Handler(Looper.getMainLooper())
            state["handler"] = h
            initRandomGreeting(state, activity)
            lateinit var clockTask: Runnable
            clockTask = Runnable {
                updateClockState(state)
                if (state["attached"] == true) h.postDelayed(clockTask, 1000)
            }
            state["clockTask"] = clockTask
            h.post(clockTask)
            loadRealWeatherAsync(state, activity)
            lateinit var weatherTask: Runnable
            weatherTask = Runnable {
                if (state["attached"] == true) {
                    loadRealWeatherAsync(state, activity)
                    h.postDelayed(weatherTask, 1800000)
                }
            }
            state["weatherTask"] = weatherTask
            h.postDelayed(weatherTask, 1800000)
            loadQuoteAsync(state, activity)
            lateinit var quoteTask: Runnable
            quoteTask = Runnable {
                if (state["attached"] == true) {
                    loadQuoteAsync(state, activity)
                    h.postDelayed(quoteTask, 1800000)
                }
            }
            state["quoteTask"] = quoteTask
            h.postDelayed(quoteTask, 1800000)
            logger("负一屏 UI attach 完成", null)
        } catch (t: Throwable) {
            logger("负一屏 UI attach失败", t)
            state.remove("attached")
        }
    }

    private fun updateSessionSize(state: MutableMap<String, Any?>) {
        val drawer = state["drawer"] as? FrameLayout ?: return
        val decor = state["decor"] as? FrameLayout ?: return
        val w = decor.width
        if (w == 0) {
            mainHandler.postDelayed({ updateSessionSize(state) }, 300)
            return
        }
        val width = maxOf(1, (w * DRAWER_FRACTION).toInt())
        state["width"] = width
        val lp = drawer.layoutParams as FrameLayout.LayoutParams
        lp.width = width
        lp.height = -1
        drawer.layoutParams = lp
        applySession(state)
    }

    private fun applySession(state: MutableMap<String, Any?>) {
        val drawer = state["drawer"] as? FrameLayout ?: return
        val dim = state["dim"] as? View
        val root = state["root"] as? FrameLayout
        val width = (state["width"] as? Number)?.toInt() ?: 1
        val p = (state["progress"] as? Number)?.toFloat() ?: 0f
        if (p > 0) root?.visibility = View.VISIBLE
        drawer.translationX = -width * (1f - p)
        dim?.alpha = 0.42f * p
    }

    private fun openSession(state: MutableMap<String, Any?>) {
        openSessionWithRandom(state)
    }

    private fun openSessionWithRandom(state: MutableMap<String, Any?>) {
        try {
            val a = state["activity"] as Activity
            initRandomGreeting(state, a)
            updateClockState(state)
        } catch (t: Throwable) {
            logger("openSessionWithRandom失败", t)
        }
        animateSession(state, 1f)
    }

    private fun closeSession(state: MutableMap<String, Any?>?) {
        if (state == null || state["attached"] != true) return
        var token = 0
        try { token = (state["animToken"] as? Number)?.toInt() ?: 0 } catch (ignored: Throwable) {}
        state["animToken"] = token + 1
        state["tracking"] = false
        state["dragging"] = false
        state["launcherTracking"] = false
        state["launcherDragging"] = false
        state["outsideClosing"] = false
        animateSession(state, 0f)
    }

    private fun animateSession(state: MutableMap<String, Any?>, target: Float) {
        val token: Int
        try { token = ((state["animToken"] as? Number)?.toInt() ?: 0) + 1 } catch (ignored: Throwable) { return }
        state["animToken"] = token
        val start = (state["progress"] as? Number)?.toFloat() ?: 0f
        val st = System.currentTimeMillis()
        val h = state["handler"] as? Handler ?: return
        val duration = if (target > start) 420L else 520L
        var root = state["root"] as? FrameLayout
        if (root != null && start > 0f) root.visibility = View.VISIBLE
        val step = object : Runnable {
            override fun run() {
                try {
                    val tv = state["animToken"] as? Number ?: return
                    if (tv.toInt() != token) return
                    val now = System.currentTimeMillis()
                    val q = minOf(1f, (now - st).toFloat() / duration)
                    val e = (1.0 - Math.pow((1.0 - q).toDouble(), 2.8)).toFloat()
                    val value = start + (target - start) * e
                    state["progress"] = value
                    applySession(state)
                    if (q < 1f) {
                        h.post(this)
                    } else if (target <= 0.001f) {
                        state["progress"] = 0f
                        val r = state["root"] as? FrameLayout
                        r?.visibility = View.GONE
                    }
                } catch (ex: Throwable) {
                    logger("动画异常", ex)
                }
            }
        }
        h.post(step)
    }

    private fun detachSession(state: MutableMap<String, Any?>?) {
        if (state == null) return
        state["attached"] = false
        val h = state["handler"] as? Handler
        if (h != null) {
            (state["clockTask"] as? Runnable)?.let { h.removeCallbacks(it) }
            (state["weatherTask"] as? Runnable)?.let { h.removeCallbacks(it) }
            (state["quoteTask"] as? Runnable)?.let { h.removeCallbacks(it) }
        }
        val root = state["root"] as? FrameLayout
        val decor = state["decor"] as? FrameLayout
        if (root != null && decor != null) {
            try { decor.removeView(root) } catch (ignored: Throwable) {}
        }
    }
    // ================= 时钟 / 农历 / 问候 =================
    private fun updateClockState(state: MutableMap<String, Any?>) {
        val c = state["clock"] as? TextView
        val d = state["date"] as? TextView
        val g = state["greeting"] as? TextView
        if (c == null) return
        val cal = Calendar.getInstance()
        c.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)
        var week = SimpleDateFormat("E", Locale.CHINA).format(cal.time)
        if (week != null && week.isNotEmpty() && week.startsWith("星期")) week = "周" + week.substring(2)
        d?.text = SimpleDateFormat("M月d日", Locale.CHINA).format(cal.time) + "  " + week
        refreshLunarDisplay(state, null)
        if (g != null) {
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val bucket = greetingBucket(hour)
            val savedBucket = state["greetingBucket"]
            if (savedBucket == null || bucket != savedBucket.toString()) {
                state["greetingBucket"] = bucket
                initRandomGreeting(state, state["activity"] as? Activity)
                return
            }
            val hi = state["greetingHi"].toString()
            val wish = state["greetingWish"].toString()
            (state["greetingColor"] as? Number)?.let { g.setTextColor(it.toInt()) }
            g.text = hi + (if (wish.isNotEmpty()) "，\n" + wish else "")
        }
    }

    private fun refreshLunarDisplay(state: MutableMap<String, Any?>, a: Activity?) {
        val lunar = state["lunarText"] as? TextView ?: return
        val on = state["showLunar"] == true
        if (!on) {
            lunar.text = ""
            lunar.visibility = View.GONE
            return
        }
        try {
            val cal = Calendar.getInstance()
            val cc = ChineseCalendar()
            cc.timeInMillis = cal.timeInMillis
            val lm = cc.get(ChineseCalendar.MONTH) + 1
            val ld = cc.get(ChineseCalendar.DAY_OF_MONTH)
            lunar.text = "农历" + lunarMonthName(lm) + " " + lunarDayName(ld)
            lunar.visibility = View.VISIBLE
        } catch (e: Throwable) {
            logger("农历显示失败", e)
            lunar.text = "农历获取失败"
            lunar.visibility = View.VISIBLE
        }
    }

    private fun lunarMonthName(m: Int): String {
        val a = arrayOf("正月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "冬月", "腊月")
        return if (m in 1..12) a[m - 1] else ""
    }

    private fun lunarDayName(d: Int): String {
        val n = arrayOf("", "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
            "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十")
        return if (d in 1..30) n[d] else ""
    }
    // ================= 问候语 =================
    private fun greetingBucket(h: Int): String {
        if (h < 6) return "dawn"
        if (h < 9) return "early_morning"
        if (h < 12) return "morning"
        if (h < 18) return "afternoon"
        return "evening"
    }

    private fun greetingHiByHour(h: Int): String {
        if (h < 6) return "凌晨好"
        if (h < 9) return "清晨好"
        if (h < 12) return "上午好"
        if (h < 18) return "下午好"
        return "晚上好"
    }

    private fun greetingTextMatchesBucket(bucket: String, text: String?): Boolean {
        if (text == null) return false
        val s = text.trim()
        if (s.isEmpty()) return false
        if (bucket == "dawn") {
            return s.contains("凌晨") || s.contains("夜深") || s.contains("深夜") || s.contains("晚安") || s.contains("好梦") || s.contains("夜里")
        }
        if (bucket == "early_morning") {
            return !containsAny(s, arrayOf("下午", "晚上", "晚安", "夜深", "凌晨", "午后", "中午")) &&
                (s.contains("清晨") || s.contains("早晨") || s.contains("早安") || s.contains("晨光") || s.contains("早起") || s.contains("早上"))
        }
        if (bucket == "morning") {
            return !containsAny(s, arrayOf("清晨", "早晨", "早安", "早起", "早上", "晨光", "凌晨", "夜深", "晚安", "下午", "午后", "晚上", "中午"))
        }
        if (bucket == "afternoon") {
            return !containsAny(s, arrayOf("清晨", "早晨", "早安", "早起", "早上", "晨光", "凌晨", "夜深", "晚安", "晚上")) &&
                (s.contains("下午") || s.contains("午后") || s.contains("中午") || !containsAny(s, arrayOf("早")))
        }
        return !containsAny(s, arrayOf("清晨", "早晨", "早安", "早起", "早上", "晨光", "上午", "下午", "午后", "中午", "凌晨")) &&
            (s.contains("晚上") || s.contains("夜晚") || s.contains("夜色") || s.contains("晚风") || s.contains("晚安") || !s.contains("早"))
    }

    private fun containsAny(s: String?, words: Array<String>): Boolean {
        if (s == null) return false
        for (w in words) if (s.contains(w)) return true
        return false
    }
    private fun randomLocalGreetingWish(h: Int): String {
        val b = greetingBucket(h)
        val wishes: Array<String> = when (b) {
            "dawn" -> arrayOf(
                "夜深了，愿你安然入睡。", "凌晨安好，愿一夜好梦。", "这个时间还醒着，愿你一切安好。",
                "夜已深，放下疲惫，好好休息吧。", "愿凌晨的安静给你一份好心情。", "晚安，愿明天醒来又是美好的一天。",
                "夜深人静，愿你今晚睡得香甜。", "愿所有疲惫都在今晚慢慢消散。", "凌晨安静，愿你心安好梦。",
                "忙碌了一天，愿你在夜色中放松下来。", "夜已深，愿你与好梦相遇。", "早点休息，明天继续元气满满。"
            )
            "early_morning" -> arrayOf(
                "清晨的第一缕阳光，愿你今天元气满满。", "新的一天，愿一切顺顺利利。", "早起的你，愿今天有个好心情。",
                "晨光正好，愿你带着期待出发。", "新的一天开始了，愿所有美好如期而至。", "愿今天的你精神饱满，状态满满。",
                "微风正好，愿你带着轻松的心情出发。", "阳光温柔，愿今天也温柔待你。", "新的一天，愿你从容又有力量。",
                "醒来后，愿今天比昨天更接近心中的期待。", "愿你今天一路顺心。", "阳光洒落，愿好心情从现在开始。"
            )
            "morning" -> arrayOf(
                "今天的事情都进展顺利。", "愿今天有惊喜，也有收获。", "愿上午的好状态一直延续下去。",
                "愿你带着好心情，认真过好今天。", "忙碌之余，也别忘了给自己一点轻松。", "愿今天的努力，都有值得期待的回应。",
                "愿手边的事情一件件顺利完成。", "愿你今天思路清晰，做事顺心。", "时光正好，愿你保持专注也保持好心情。",
                "愿今天的每一步，都走得踏实又顺利。", "愿你所忙皆有所获。", "愿你的上午充实而不慌张，忙碌也有收获。"
            )
            "afternoon" -> arrayOf(
                "愿午后的时光轻松愉快。", "忙碌了一上午，下午也要保持好状态。", "午后阳光正好，愿你的心情也很好。",
                "愿工作顺利，心情舒畅。", "不慌不忙，愿下午也有小小的惊喜。", "愿你的下午从容自在，事事顺心。",
                "愿接下来的时间也顺顺利利。", "午后时光，愿你忙有所值，闲有所乐。", "愿下午的每一件小事都朝着好的方向发展。",
                "愿你保持耐心，也保持好心情。", "愿午后的阳光带来一点轻松和惬意。", "愿今天的努力慢慢开花结果。"
            )
            else -> arrayOf(
                "忙碌了一天，愿今晚轻松自在。", "晚风轻拂，愿你卸下疲惫，好好放松。", "愿今天的一切都顺顺利利。",
                "一天辛苦了，今晚记得留一点时间给自己。", "愿夜色温柔，愿你心情舒畅。", "愿你今晚拥有属于自己的轻松时光。",
                "夜幕降临，愿你把今天的疲惫留在身后。", "愿你有一段安静而舒服的时光。", "愿今晚的你放松自在，享受属于自己的时间。",
                "忙碌告一段落，愿今晚的心情轻松一些。", "愿你所想皆有回应。", "愿夜色带走疲惫，也带来好心情。"
            )
        }
        return wishes[Random().nextInt(wishes.size)]
    }

    private fun greetingColorForHour(h: Int): Int {
        val colors = intArrayOf(
            Color.rgb(220, 58, 58), Color.rgb(230, 120, 40), Color.rgb(200, 160, 30),
            Color.rgb(60, 150, 80), Color.rgb(40, 130, 160), Color.rgb(90, 90, 190),
            Color.rgb(150, 70, 170), Color.rgb(190, 70, 120), Color.rgb(90, 110, 80), Color.rgb(120, 90, 60)
        )
        return colors[Random().nextInt(colors.size)]
    }
    private fun removeGreetingTimeWord(bucket: String, text: String?): String {
        if (text == null) return ""
        var s = text.trim()
        if (s.isEmpty()) return s
        val words: Array<String> = when (bucket) {
            "dawn" -> arrayOf("凌晨")
            "early_morning" -> arrayOf("清晨", "早晨", "早安", "早上")
            "morning" -> arrayOf("上午")
            "afternoon" -> arrayOf("下午")
            else -> arrayOf("晚上")
        }
        for (w in words) s = s.replace(w, "")
        s = s.replace("， ，", "，").replace("，，", "，").replace("  ", " ").trim()
        while (s.startsWith("，") || s.startsWith("、") || s.startsWith(" ")) s = s.substring(1).trim()
        while (s.endsWith("，") || s.endsWith("、") || s.endsWith(" ")) s = s.substring(0, s.length - 1).trim()
        return s
    }

    private fun setGreeting(state: MutableMap<String, Any?>, hi: String?, wish: String?, color: Int) {
        try {
            val bucket = state["greetingBucket"].toString()
            val cleanWish = removeGreetingTimeWord(bucket, wish)
            state["greetingHi"] = hi ?: ""
            state["greetingWish"] = cleanWish
            state["greetingColor"] = color
            val g = state["greeting"] as? TextView
            if (g != null) {
                g.setTextColor(color)
                g.text = (hi ?: "") + (if (cleanWish.isNotEmpty()) "，\n" + cleanWish else "")
            }
        } catch (e: Throwable) {
            logger("设置问候语失败", e)
        }
    }

    private fun initRandomGreeting(state: MutableMap<String, Any?>?, a: Activity?) {
        if (state == null) return
        val cal = Calendar.getInstance()
        val localHour = cal.get(Calendar.HOUR_OF_DAY)
        val localBucket = greetingBucket(localHour)
        val localHi = greetingHiByHour(localHour)
        val localWish = randomLocalGreetingWish(localHour)
        val localColor = greetingColorForHour(localHour)
        state["greetingBucket"] = localBucket
        state["greetingLoadedNetwork"] = false
        setGreeting(state, localHi, localWish, localColor)
        logger("本地问候备用：hour=$localHour bucket=$localBucket wish=$localWish")
        Thread {
            try {
                val url = "https://api.kuleu.com/api/getGreetingMessage?type=json&_t=" + System.currentTimeMillis()
                val root = HttpUtil.getJson(url, 5000) ?: return@Thread
                if (root.optInt("code", 0) != 200) {
                    logger("网络问候接口无有效结果")
                    return@Thread
                }
                val data = root.optJSONObject("data") ?: return@Thread
                val serverTime = data.optString("currentTime", "").trim()
                val serverGreeting = data.optString("greeting", "").trim()
                val serverTip = data.optString("tip", "").trim()
                if (serverTime.length < 2 || serverGreeting.isEmpty()) return@Thread
                var serverHour = -1
                try {
                    val tp = serverTime.split(":")
                    if (tp.isNotEmpty()) serverHour = tp[0].toInt()
                } catch (ignored: Throwable) {}
                if (serverHour < 0 || serverHour > 23) return@Thread
                val serverBucket = greetingBucket(serverHour)
                if (localBucket != serverBucket) {
                    logger("丢弃网络问候：本机bucket=$localBucket serverTime=$serverTime serverBucket=$serverBucket")
                    return@Thread
                }
                if (!greetingTextMatchesBucket(localBucket, serverGreeting) && !greetingTextMatchesBucket(localBucket, serverTip)) {
                    logger("丢弃网络问候内容：bucket=$localBucket greeting=$serverGreeting tip=$serverTip")
                    return@Thread
                }
                val networkText = if (greetingTextMatchesBucket(localBucket, serverTip)) serverTip else serverGreeting
                mainHandler.post {
                    try {
                        setGreeting(state, localHi, networkText, localColor)
                        state["greetingLoadedNetwork"] = true
                        logger("网络问候已采用：$localHi / $networkText")
                    } catch (e: Throwable) {
                        logger("应用网络问候失败", e)
                    }
                }
            } catch (e: Throwable) {
                logger("获取网络问候失败", e)
            }
        }.start()
    }

    // ==================== 视图工具函数（对译 F3 v3.77） ====================

    private fun txt(a: Activity, s: String?, size: Float, bold: Boolean): TextView {
        val t = TextView(a)
        t.text = s
        t.setTextSize(size)
        t.setTextColor(Color.rgb(25, 25, 25))
        t.gravity = Gravity.CENTER_VERTICAL
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        return t
    }

    private fun dp(a: Activity, n: Int): Int = (n * a.resources.displayMetrics.density + 0.5f).toInt()

    private fun section(a: Activity, s: String): TextView {
        val t = txt(a, s, 17f, true)
        t.setPadding(0, dp(a, 12), 0, dp(a, 8))
        return t
    }

    private fun card(a: Activity, color: Int, radiusDp: Float): LinearLayout {
        val l = LinearLayout(a)
        l.orientation = LinearLayout.VERTICAL
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(a, radiusDp.toInt()).toFloat()
        l.background = g
        l.clipToOutline = true
        return l
    }

    // ==================== 卡片顺序 / 显隐持久化（对译 F3） ====================

    private fun joinOrder(order: ArrayList<String>): String {
        val b = StringBuilder()
        for (i in order.indices) {
            if (i > 0) b.append(",")
            b.append(order[i])
        }
        return b.toString()
    }

    private fun indexOfKey(arr: Array<String>, value: String?): Int {
        if (value == null) return -1
        for (i in arr.indices) if (value == arr[i]) return i
        return -1
    }

    private fun indexOfKey(arr: ArrayList<String>, value: String?): Int {
        if (value == null) return -1
        for (i in arr.indices) if (value == arr[i]) return i
        return -1
    }

    private fun saveCardVisibility(a: Activity, keys: Array<String>, visible: BooleanArray) {
        try {
            val hidden = StringBuilder()
            for (i in keys.indices) {
                if (!visible[i]) {
                    if (hidden.isNotEmpty()) hidden.append(",")
                    hidden.append(keys[i])
                }
            }
            a.getSharedPreferences(SP_PANEL, 0).edit().putString("hidden_cards", hidden.toString()).commit()
        } catch (e: Throwable) {
            logger("保存卡片显示状态失败", e)
        }
    }

    private fun saveCardVisibilityFromState(a: Activity, state: MutableMap<String, Any?>, keys: Array<String>) {
        try {
            val hidden = StringBuilder()
            for (k in keys) {
                if (state["visible_$k"] != true) {
                    if (hidden.isNotEmpty()) hidden.append(",")
                    hidden.append(k)
                }
            }
            a.getSharedPreferences(SP_PANEL, 0).edit().putString("hidden_cards", hidden.toString()).commit()
        } catch (e: Throwable) {
            logger("从状态保存卡片显示状态失败", e)
        }
    }

    private fun applyCardLayout(state: MutableMap<String, Any?>, activity: Activity) {
        try {
            val col = state["cardContainer"] as? LinearLayout ?: return
            col.removeAllViews()
            val order = state["cardOrder"]?.toString() ?: DEFAULT_CARD_ORDER
            val parts = order.split(",")
            for (p in parts) {
                val k = p.trim()
                val v = state["card_$k"] as? View ?: continue
                if (state["visible_$k"] == true) {
                    val lp = LinearLayout.LayoutParams(-1, -2)
                    if (k == "wallet") lp.setMargins(0, dp(activity, 10), 0, 0)
                    col.addView(v, lp)
                }
            }
        } catch (e: Throwable) {
            logger("应用主页布局失败", e)
        }
    }

    private fun savePanelString(a: Activity, key: String, value: String) {
        try {
            a.getSharedPreferences(SP_PANEL, 0).edit().putString(key, value).commit()
        } catch (ignored: Throwable) {
        }
    }

    private fun loadPanelString(a: Activity, key: String, def: String): String {
        return try {
            a.getSharedPreferences(SP_PANEL, 0).getString(key, def) ?: def
        } catch (e: Throwable) {
            def
        }
    }

    private fun savePanelBool(a: Activity, key: String, value: Boolean) {
        try {
            a.getSharedPreferences(SP_PANEL, 0).edit().putBoolean(key, value).commit()
        } catch (ignored: Throwable) {
        }
    }

    private fun loadPanelBool(a: Activity, key: String, def: Boolean): Boolean {
        return try {
            a.getSharedPreferences(SP_PANEL, 0).getBoolean(key, def)
        } catch (e: Throwable) {
            def
        }
    }
    // ==================== state 版设置读写重载（设置对话框使用） ====================
    private fun loadPanelBool(state: Map<String, Any?>, key: String, def: Boolean): Boolean {
        val v = state[key]
        return when (v) {
            is Boolean -> v
            is String -> v == "true"
            else -> def
        }
    }
    private fun loadPanelString(state: Map<String, Any?>, key: String, def: String): String {
        val v = state[key]
        return if (v == null) def else v.toString()
    }
    private fun savePanelBool(state: MutableMap<String, Any?>, a: Activity, key: String, value: Boolean) {
        try {
            state[key] = value
            a.getSharedPreferences(SP_PANEL, 0).edit().putBoolean(key, value).commit()
        } catch (ignored: Throwable) {
        }
    }
    private fun savePanelString(state: MutableMap<String, Any?>, a: Activity, key: String, value: String) {
        try {
            state[key] = value
            a.getSharedPreferences(SP_PANEL, 0).edit().putString(key, value).commit()
        } catch (ignored: Throwable) {
        }
    }
    private fun savePanelInt(state: MutableMap<String, Any?>, a: Activity, key: String, value: Int) {
        try {
            state[key] = String.valueOf(value)
            a.getSharedPreferences(SP_PANEL, 0).edit().putString(key, String.valueOf(value)).commit()
        } catch (ignored: Throwable) {
        }
    }
    private fun parseSettingBool(state: Map<String, Any?>, key: String, def: Boolean): Boolean {
        val v = state[key]
        return when (v) {
            is Boolean -> v
            is String -> v == "true"
            else -> def
        }
    }
    private fun joinOrder(state: Map<String, Any?>, a: Activity): String {
        val order = state["cardOrder"]?.toString() ?: DEFAULT_CARD_ORDER
        return order
    }

    // ==================== 天气城市持久化（对译 F3） ====================

    private fun saveWeatherCity(a: Activity, city: String, lat: Double, lon: Double, manual: Boolean) {
        try {
            a.getSharedPreferences(SP_WEATHER, 0).edit()
                .putString("city", city)
                .putFloat("lat", lat.toFloat())
                .putFloat("lon", lon.toFloat())
                .putBoolean("manual", manual)
                .apply()
        } catch (e: Throwable) {
            logger("保存天气城市失败", e)
        }
    }

    private fun clearWeatherCity(a: Activity) {
        try {
            a.getSharedPreferences(SP_WEATHER, 0).edit().clear().apply()
        } catch (e: Throwable) {
            logger("清除天气城市失败", e)
        }
    }

    // ==================== 卡片度量 / 操作按钮（对译 F3） ====================

    private fun addMetric(row: LinearLayout, a: Activity, label: String, value: String) {
        val m = LinearLayout(a)
        m.orientation = LinearLayout.VERTICAL
        m.gravity = Gravity.CENTER
        val v = txt(a, value, 14f, true)
        v.setTextColor(Color.DKGRAY)
        val t = txt(a, label, 11f, false)
        t.setTextColor(Color.GRAY)
        m.addView(v, LinearLayout.LayoutParams(-1, dp(a, 24)))
        m.addView(t, LinearLayout.LayoutParams(-1, dp(a, 20)))
        row.addView(m, LinearLayout.LayoutParams(0, dp(a, 46), 1f))
    }

    private fun addMetricWeather(row: LinearLayout, a: Activity, label: String, value: String) {
        val green = Color.rgb(31, 91, 48)
        val m = LinearLayout(a)
        m.orientation = LinearLayout.VERTICAL
        m.gravity = Gravity.CENTER
        val v = txt(a, value, 15f, true)
        v.setTextColor(green)
        v.gravity = Gravity.CENTER
        v.isSingleLine = true
        val t = txt(a, label, 13f, false)
        t.setTextColor(Color.rgb(63, 116, 70))
        t.gravity = Gravity.CENTER
        t.isSingleLine = true
        m.addView(v, LinearLayout.LayoutParams(-1, dp(a, 32)))
        m.addView(t, LinearLayout.LayoutParams(-1, dp(a, 30)))
        val idx = row.childCount
        if (idx > 0) {
            val d = View(a)
            d.setBackgroundColor(Color.argb(55, 30, 105, 50))
            val dlp = LinearLayout.LayoutParams(dp(a, 1), dp(a, 50))
            dlp.setMargins(0, 0, 0, 0)
            row.addView(d, dlp)
        }
        val weight = if (idx == 0) 1.25f else 0.875f
        row.addView(m, LinearLayout.LayoutParams(0, dp(a, 64), weight))
    }

    private fun addActionButton(row: LinearLayout, a: Activity, state: MutableMap<String, Any?>, label: String, cn: String, outline: Boolean) {
        val b = Button(a)
        b.text = label
        b.isAllCaps = false
        b.setTextSize(13f)
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(dp(a, 8), 0, dp(a, 8), 0)
        b.tag = arrayOf<Any>(label, cn)
        b.setOnClickListener { v ->
            val t = v.tag as Array<*>
            val l = t[0].toString()
            val c = t[1].toString()
            logger("v3.0 点击卡片操作=$l target=$c", null)
            launchSession(state, c, l)
        }
        val p = LinearLayout.LayoutParams(0, dp(a, 44), 1f)
        p.setMargins(dp(a, 3), 0, dp(a, 3), 0)
        row.addView(b, p)
    }

    private fun addActionTile(row: LinearLayout, a: Activity, state: MutableMap<String, Any?>, label: String, cn: String) {
        val b = Button(a)
        b.text = label
        b.isAllCaps = false
        b.setTextSize(12f)
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(dp(a, 2), 0, dp(a, 2), 0)
        b.tag = arrayOf<Any>(label, cn)
        val g = GradientDrawable()
        g.setColor(Color.WHITE)
        g.cornerRadius = dp(a, 18).toFloat()
        b.background = g
        b.setOnClickListener { v ->
            val t = v.tag as Array<*>
            val l = t[0].toString()
            val c = t[1].toString()
            logger("v3.0 点击快捷操作=${l.replace("\n", " ")} target=$c", null)
            launchSession(state, c, l)
        }
        val p = LinearLayout.LayoutParams(0, dp(a, 70), 1f)
        p.setMargins(dp(a, 3), dp(a, 3), dp(a, 3), dp(a, 3))
        row.addView(b, p)
    }

    private fun makeInitialBitmap(a: Activity, text: String, sizeDp: Int): Bitmap? {
        return try {
            val px = dp(a, sizeDp)
            val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(b)
            c.drawColor(Color.rgb(220, 235, 225))
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.rgb(70, 120, 85)
            paint.textSize = dp(a, 24).toFloat()
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textAlign = android.graphics.Paint.Align.CENTER
            val fm = paint.fontMetrics
            val y = px / 2f - (fm.ascent + fm.descent) / 2f
            c.drawText(text, px / 2f, y, paint)
            b
        } catch (e: Throwable) {
            null
        }
    }

    private fun launchSession(state: MutableMap<String, Any?>, cn: String, label: String) {
        closeSession(state)
        val h = state["handler"] as? Handler
        val r = Runnable {
            try {
                val a = state["activity"] as Activity
                val i = Intent()
                i.setClassName(a.packageName, cn)
                a.startActivity(i)
                logger("v3.0 快捷操作已启动=$label target=$cn", null)
            } catch (e: Throwable) {
                if ("付款码" == label) {
                    try {
                        val a = state["activity"] as Activity
                        val f = Intent()
                        f.setClassName(a.packageName, "com.tencent.mm.plugin.mall.ui.MallIndexUIv2")
                        a.startActivity(f)
                        logger("v2.8 付款码备用入口已启动", null)
                        return@Runnable
                    } catch (ignored: Throwable) {
                    }
                }
                toast("$label 打开失败")
                logger("v2.8 $label launch failed", e)
            }
        }
        if (h != null) h.postDelayed(r, 200) else r.run()
    }

    private fun addGrid(col: LinearLayout, activity: Activity, state: MutableMap<String, Any?>) {
        val names = arrayOf(
            arrayOf("扫一扫", "付款码", "朋友圈"),
            arrayOf("收藏", "视频号", "微信设置")
        )
        val cls = arrayOf(
            arrayOf(
                "com.tencent.mm.plugin.scanner.ui.BaseScanUI",
                "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI",
                "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI"
            ),
            arrayOf(
                "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI",
                "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI",
                "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI"
            )
        )
        for (r in 0..1) {
            val row = LinearLayout(activity)
            row.orientation = LinearLayout.HORIZONTAL
            for (c in 0..2) {
                val b = Button(activity)
                b.text = names[r][c]
                b.isAllCaps = false
                b.setTextSize(14f)
                b.isClickable = true
                b.isFocusable = true
                b.tag = arrayOf<Any>(names[r][c], cls[r][c])
                b.setOnClickListener { v ->
                    try {
                        val tag = v.tag as Array<*>
                        val label = tag[0].toString()
                        val cn = tag[1].toString()
                        logger("v3.0 点击快捷操作=$label target=$cn", null)
                        launchSession(state, cn, label)
                    } catch (e: Throwable) {
                        logger("v2.8 快捷操作点击异常", e)
                    }
                }
                val bp = LinearLayout.LayoutParams(0, dp(activity, 54), 1f)
                bp.setMargins(dp(activity, 3), dp(activity, 3), dp(activity, 3), dp(activity, 3))
                row.addView(b, bp)
            }
            col.addView(row, LinearLayout.LayoutParams(-1, dp(activity, 60)))
        }
    }

    // ==================== 设置分隔线 / 解析 ====================

    private fun addSettingsDivider(parent: LinearLayout, a: Activity) {
        val d = View(a)
        d.setBackgroundColor(Color.rgb(225, 235, 226))
        parent.addView(d, LinearLayout.LayoutParams(-1, dp(a, 1)))
    }

    private fun parseSettingInt(s: String?, def: Int, min: Int, max: Int): Int {
        return try {
            var v = s!!.trim().toInt()
            if (v < min) v = min
            if (v > max) v = max
            v
        } catch (e: Throwable) {
            def
        }
    }

    // ==================== 视频风开关（对译 F3 makeVideoToggle/renderVideoToggle） ====================

    private fun makeVideoToggle(a: Activity, on: Boolean): FrameLayout {
        return try {
            val box = FrameLayout(a)
            box.tag = on
            renderVideoToggle(box, a, on)
            box
        } catch (e: Throwable) {
            FrameLayout(a)
        }
    }

    private fun renderVideoToggle(box: FrameLayout, a: Activity, on: Boolean) {
        try {
            val pill = GradientDrawable()
            pill.cornerRadius = dp(a, 18).toFloat()
            pill.setColor(if (on) Color.rgb(49, 117, 68) else Color.rgb(224, 230, 224))
            pill.setStroke(dp(a, 1), if (on) Color.rgb(49, 117, 68) else Color.rgb(119, 132, 120))
            box.background = pill
            box.removeAllViews()
            val knob = txt(a, if (on) "✓" else "×", 13f, true)
            knob.gravity = Gravity.CENTER
            val kb = GradientDrawable()
            kb.shape = GradientDrawable.OVAL
            kb.setColor(if (on) Color.WHITE else Color.rgb(190, 198, 190))
            if (on) kb.setStroke(dp(a, 1), Color.rgb(235, 240, 235))
            knob.background = kb
            knob.setTextColor(if (on) Color.rgb(49, 117, 68) else Color.rgb(246, 250, 246))
            val size = dp(a, 24)
            val lp = FrameLayout.LayoutParams(size, size)
            lp.gravity = if (on) (Gravity.RIGHT or Gravity.CENTER_VERTICAL) else (Gravity.LEFT or Gravity.CENTER_VERTICAL)
            lp.setMargins(dp(a, 3), 0, dp(a, 3), 0)
            box.addView(knob, lp)
        } catch (e: Throwable) {
        }
    }

    // ==================== 一言分类（对译 F3 quoteCategoryCodes/Names） ====================

    private fun quoteCategoryCodes(): Array<String> =
        arrayOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l")

    private fun quoteCategoryNames(): Array<String> =
        arrayOf("动画", "漫画", "游戏", "文学", "原创", "网络", "其他", "影视", "诗词", "网易云", "哲学", "抖机灵")

    private fun defaultQuoteCategories(): String = "a,d,e,g,h,i,j,k,l"

    private fun quoteSelectedCodes(raw: String?): MutableSet<String> {
        val set = mutableSetOf<String>()
        if (raw == null) return set
        for (p in raw.split(",")) {
            val t = p.trim()
            if (t.isNotEmpty()) set.add(t)
        }
        return set
    }

    // ==================== 城市名规范化 / 显示（对译 F3 normalizeCityName/displayCityName） ====================

    private fun normalizeCityName(raw: String?): String {
        if (raw == null) return ""
        var s = raw.trim()
        if (s.isEmpty()) return ""
        val map = mapOf(
            "beijing" to "北京", "shanghai" to "上海", "guangzhou" to "广州", "shenzhen" to "深圳",
            "hangzhou" to "杭州", "nanjing" to "南京", "wuhan" to "武汉", "chengdu" to "成都",
            "chongqing" to "重庆", "tianjin" to "天津", "suzhou" to "苏州", "xian" to "西安",
            "xi'an" to "西安", "changsha" to "长沙", "zhengzhou" to "郑州", "qingdao" to "青岛",
            "jinan" to "济南", "shenyang" to "沈阳", "dalian" to "大连", "xiamen" to "厦门",
            "fuzhou" to "福州", "kunming" to "昆明", "hefei" to "合肥", "nanchang" to "南昌",
            "harbin" to "哈尔滨", "changchun" to "长春", "shijiazhuang" to "石家庄", "taiyuan" to "太原",
            "nanning" to "南宁", "guiyang" to "贵阳", "lanzhou" to "兰州", "haikou" to "海口",
            "sanya" to "三亚", "ningbo" to "宁波", "wuxi" to "无锡", "wenzhou" to "温州",
            "foshan" to "佛山", "dongguan" to "东莞", "zhuhai" to "珠海", "zhongshan" to "中山",
            "huizhou" to "惠州", "quanzhou" to "泉州", "yantai" to "烟台", "weifang" to "潍坊",
            "luoyang" to "洛阳", "shaoxing" to "绍兴", "jiaxing" to "嘉兴", "taizhou" to "台州",
            "zhanjiang" to "湛江", "guilin" to "桂林", "wuhu" to "芜湖", "xuzhou" to "徐州",
            "changzhou" to "常州", "nantong" to "南通", "yangzhou" to "扬州", "zhenjiang" to "镇江",
            "tangshan" to "唐山", "baoding" to "保定", "handan" to "邯郸", "linyi" to "临沂",
            "zibo" to "淄博", "jinhua" to "金华", "lanzhou" to "兰州", "urumqi" to "乌鲁木齐",
            "lhasa" to "拉萨", "hohhot" to "呼和浩特", "yinchuan" to "银川", "xining" to "西宁",
            "hong kong" to "香港", "hongkong" to "香港", "macau" to "澳门", "macao" to "澳门",
            "taipei" to "台北", "taichung" to "台中", "kaohsiung" to "高雄"
        )
        val lower = s.lowercase()
        map[lower]?.let { return it }
        return s
    }

    private fun displayCityName(raw: String?): String {
        if (raw == null) return ""
        var s = raw.trim()
        if (s.isEmpty()) return ""
        val separators = arrayOf(" · ", "·", ",", "，")
        for (sep in separators) {
            val idx = s.indexOf(sep)
            if (idx > 0) {
                s = s.substring(0, idx).trim()
                break
            }
        }
        return normalizeCityName(s)
    }

    // ==================== 微信资料读取（对译 F3 loadRealProfileAsync / readRealWeChatProfile / getWeChatSqliteDatabase / queryOneString / loadAvatarBitmap） ====================

    private fun loadRealProfileAsync(state: MutableMap<String, Any?>, a: Activity) {
        Thread {
            try {
                val profile = readRealWeChatProfile(a)
                val nickname = profile?.first
                val avatarPath = profile?.second
                if (nickname != null && nickname.isNotEmpty()) {
                    mainHandler.post {
                        try {
                            val tv = state["nicknameView"] as? android.widget.TextView ?: return@post
                            tv.text = nickname
                        } catch (e: Throwable) {
                        }
                    }
                }
                if (avatarPath != null && avatarPath.isNotEmpty()) {
                    val bmp = loadAvatarBitmap(avatarPath)
                    if (bmp != null) {
                        mainHandler.post {
                            try {
                                val iv = state["avatarView"] as? android.widget.ImageView ?: return@post
                                iv.setImageBitmap(bmp)
                            } catch (e: Throwable) {
                            }
                        }
                    } else if (avatarPath.startsWith("http")) {
                        Thread {
                            try {
                                val conn = java.net.URL(avatarPath).openConnection() as java.net.HttpURLConnection
                                conn.connectTimeout = 5000
                                conn.readTimeout = 8000
                                conn.instanceFollowRedirects = true
                                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                                conn.connect()
                                val bm = android.graphics.BitmapFactory.decodeStream(conn.inputStream)
                                conn.disconnect()
                                if (bm != null) {
                                    mainHandler.post {
                                        try {
                                            val iv = state["avatarView"] as? android.widget.ImageView ?: return@post
                                            iv.setImageBitmap(bm)
                                        } catch (e: Throwable) {
                                        }
                                    }
                                }
                            } catch (e: Throwable) {
                            }
                        }.start()
                    }
                }
            } catch (e: Throwable) {
                logger("v3.0 读取微信资料失败", e)
            }
        }.start()
    }

    private fun readRealWeChatProfile(a: Activity): Pair<String?, String?>? {
        try {
            val db = getWeChatSqliteDatabase(a) ?: return null
            var nickname: String? = null
            var wxid: String? = null
            nickname = queryOneString(db, "SELECT value FROM userinfo WHERE id=4")
            wxid = queryOneString(db, "SELECT value FROM userinfo WHERE id=2")
            if ((nickname == null || nickname.isEmpty()) && wxid != null && wxid.isNotEmpty()) {
                val safe = wxid.replace("'", "''")
                nickname = queryOneString(db, "SELECT conRemark FROM rcontact WHERE username='$safe'")
                if (nickname == null || nickname.isEmpty()) {
                    nickname = queryOneString(db, "SELECT nickname FROM rcontact WHERE username='$safe'")
                }
            }
            var avatar: String? = null
            if (wxid != null && wxid.isNotEmpty()) {
                val safe = wxid.replace("'", "''")
                avatar = queryOneString(db, "SELECT reserved2 FROM img_flag WHERE username='$safe'")
            }
            return Pair(nickname, avatar)
        } catch (e: Throwable) {
            logger("v3.0 查询微信资料失败", e)
            return null
        }
    }

    private fun getWeChatSqliteDatabase(a: Activity): Any? {
        try {
            val bridge = context.dexKitBridge() ?: return null
            val methods = bridge.findMemberList(listOf("mCoreStorage not initialized!"))
            if (methods != null && methods.isNotEmpty()) {
                for (m in methods) {
                    try {
                        m.isAccessible = true
                        val storage = m.invoke(null)
                        if (storage != null) {
                            val dbCls = try {
                                Class.forName(
                                    "com.tencent.wcdb.database.SQLiteDatabase",
                                    false,
                                    context.hostClassLoader()
                                )
                            } catch (e: Throwable) {
                                null
                            }
                            if (dbCls == null) return null
                            var cls: Class<*>? = storage.javaClass
                            while (cls != null) {
                                for (f in cls.declaredFields) {
                                    try {
                                        f.isAccessible = true
                                        val name = f.name ?: ""
                                        val typeName = f.type?.name ?: ""
                                        if (!name.contains("SqliteDB") && !name.contains("SqliteDb") &&
                                            !typeName.startsWith("com.tencent.wcdb")
                                        ) {
                                            continue
                                        }
                                        val val1 = f.get(storage) ?: continue
                                        if (dbCls.isInstance(val1)) return val1
                                        var c2: Class<*>? = val1.javaClass
                                        while (c2 != null) {
                                            for (dm in c2.declaredMethods) {
                                                try {
                                                    if (dm.parameterTypes.isEmpty() &&
                                                        dbCls.isAssignableFrom(dm.returnType)
                                                    ) {
                                                        dm.isAccessible = true
                                                        val r = dm.invoke(val1)
                                                        if (r != null && dbCls.isInstance(r)) return r
                                                    }
                                                } catch (e: Throwable) {
                                                }
                                            }
                                            c2 = c2.superclass
                                        }
                                    } catch (e: Throwable) {
                                    }
                                }
                                cls = cls.superclass
                            }
                        }
                    } catch (e: Throwable) {
                    }
                }
            }
        } catch (e: Throwable) {
            logger("v3.0 获取微信数据库失败", e)
        }
        return null
    }

    private fun queryOneString(db: Any, sql: String): String? {
        try {
            val cursor = db.javaClass.getMethod("rawQuery", String::class.java, Array<String>::class.java)
                .invoke(db, sql, null)
            if (cursor == null) return null
            val cls = cursor.javaClass
            val moveToFirst = cls.getMethod("moveToFirst")
            val ok = moveToFirst.invoke(cursor) as? Boolean ?: false
            if (ok) {
                val getString = cls.getMethod("getString", Int::class.javaPrimitiveType)
                val value = getString.invoke(cursor, 0) as? String
                try {
                    cls.getMethod("close").invoke(cursor)
                } catch (e: Throwable) {
                }
                return value
            }
            try {
                cls.getMethod("close").invoke(cursor)
            } catch (e: Throwable) {
            }
        } catch (e: Throwable) {
        }
        return null
    }

    private fun loadAvatarBitmap(path: String): android.graphics.Bitmap? {
        try {
            if (path.startsWith("http")) return null
            return android.graphics.BitmapFactory.decodeFile(path)
        } catch (e: Throwable) {
            return null
        }
    }

    // ==================== 定位（对译 F3 hasLocationPermission / getDeviceLocation / getCityFromDeviceLocation / getIpLocation） ====================

    private fun hasLocationPermission(a: Activity): Boolean {
        try {
            return android.os.Build.VERSION.SDK_INT < 23 ||
                a.checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED ||
                a.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            return false
        }
    }

    private fun hostDeclaresLocationPermission(a: Activity): Boolean {
        try {
            val info = a.packageManager.getPackageInfo(
                a.packageName,
                android.content.pm.PackageManager.GET_PERMISSIONS
            )
            val perms = info.requestedPermissions ?: return false
            for (p in perms) {
                if (p == android.Manifest.permission.ACCESS_FINE_LOCATION ||
                    p == android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) {
                    return true
                }
            }
        } catch (e: Throwable) {
        }
        return false
    }

    private fun requestHostLocationPermission(a: Activity): Boolean {
        if (hasLocationPermission(a)) return false
        if (!hostDeclaresLocationPermission(a)) {
            logger("v3.71 宿主未声明 ACCESS_COARSE/FINE_LOCATION，无法请求系统定位权限")
            return false
        }
        try {
            mainHandler.post {
                try {
                    if (hasLocationPermission(a)) return@post
                    logger("v3.71 正在向微信请求 ACCESS_COARSE/FINE_LOCATION 权限")
                    if (android.os.Build.VERSION.SDK_INT >= 23) {
                        a.requestPermissions(
                            arrayOf(
                                "android.permission.ACCESS_COARSE_LOCATION",
                                "android.permission.ACCESS_FINE_LOCATION"
                            ),
                            7097
                        )
                    }
                } catch (e: Throwable) {
                    logger("v3.71 请求微信定位权限失败=" + e)
                }
            }
            return true
        } catch (e: Throwable) {
            logger("v3.71 投递定位权限请求失败=" + e)
            return false
        }
    }

    // 对译 F3 getDeviceLocation(1040-1132)
    private fun getDeviceLocation(activity: Activity): android.location.Location? {
        if (!hasLocationPermission(activity)) {
            logger("v3.71 设备定位：没有 ACCESS_COARSE/FINE_LOCATION 权限")
            return null
        }
        try {
            val lm = activity.getSystemService(android.content.Context.LOCATION_SERVICE)
                    as? android.location.LocationManager ?: return null

            /*
             * 兼容脚本运行环境：
             * 不使用 Android 30+ 的 getCurrentLocation/CancellationSignal API，
             * 优先读取系统最近位置，再用 requestSingleUpdate 获取一次新位置。
             */
            var provider: String? = null
            try {
                if (lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                    provider = android.location.LocationManager.NETWORK_PROVIDER
                }
            } catch (ignored: Throwable) {
            }
            if (provider == null) {
                try {
                    if (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                        provider = android.location.LocationManager.GPS_PROVIDER
                    }
                } catch (ignored: Throwable) {
                }
            }
            if (provider == null) {
                logger("v3.71 设备定位：NETWORK/GPS 均未开启")
                return null
            }

            /* 先使用最近的系统位置，避免等待导致天气卡顿。 */
            try {
                val last = lm.getLastKnownLocation(provider)
                if (last != null) {
                    val age = Math.max(0L, System.currentTimeMillis() - last.time)
                    if (age <= 30L * 60L * 1000L) {
                        logger(
                            "v3.71 使用30分钟内系统最近位置 provider=" + provider +
                                    " ageMs=" + age + " lat=" + last.latitude + " lon=" + last.longitude
                        )
                        return last
                    }
                    logger("v3.71 最近位置过旧 ageMs=" + age)
                }
            } catch (e: Throwable) {
                logger("v3.71 读取最近位置失败=" + e)
            }

            /* 最近位置不可用时，请系统定位服务返回一次新位置。 */
            val selectedProvider = provider
            val latch = java.util.concurrent.CountDownLatch(1)
            val result = arrayOfNulls<android.location.Location>(1)

            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    result[0] = location
                    try {
                        lm.removeUpdates(this)
                    } catch (ignored: Throwable) {
                    }
                    latch.countDown()
                }
            }

            try {
                activity.runOnUiThread {
                    try {
                        lm.requestSingleUpdate(selectedProvider, listener, android.os.Looper.getMainLooper())
                    } catch (e: Throwable) {
                        logger("v3.71 requestSingleUpdate失败=" + e)
                        latch.countDown()
                    }
                }
            } catch (e: Throwable) {
                logger("v3.71 启动单次定位失败=" + e)
                latch.countDown()
            }

            try {
                latch.await(10L, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            try {
                lm.removeUpdates(listener)
            } catch (ignored: Throwable) {
            }

            if (result[0] != null) {
                val l = result[0]!!
                logger(
                    "v3.71 新获取真实设备定位成功 provider=" + selectedProvider +
                            " lat=" + l.latitude + " lon=" + l.longitude +
                            " accuracy=" + l.accuracy
                )
                return l
            }
        } catch (e: Throwable) {
            logger("v3.71 真实设备定位异常=" + e)
        }
        return null
    }

    // 对译 F3 getCityFromDeviceLocation(1134-1156)
    private fun getCityFromDeviceLocation(activity: Activity, location: android.location.Location?): String {
        if (location == null) return ""
        try {
            if (!android.location.Geocoder.isPresent()) return ""
            val geocoder = android.location.Geocoder(activity, java.util.Locale.SIMPLIFIED_CHINESE)
            val list = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            if (list != null && list.isNotEmpty()) {
                val ad = list[0]
                var city = ad.locality
                if (city == null || city.trim().length == 0) city = ad.subAdminArea
                if (city == null || city.trim().length == 0) city = ad.adminArea
                city = displayCityName(city)
                if (city.length > 0) {
                    logger(
                        "v3.71 真实坐标反向地理编码 city=" + city +
                                " province=" + ad.adminArea + " sub=" + ad.subAdminArea
                    )
                    return city
                }
            }
        } catch (e: Throwable) {
            logger("v3.71 真实坐标反向地理编码失败=" + e)
        }
        return ""
    }

    // 对译 F3 parseIpProviderResult(1158-1186)
    private fun parseIpProviderResult(provider: String, url: String): org.json.JSONObject? {
        try {
            val j = h.heiErDing.utils.HttpUtil.getJson(url, 5000) ?: return null
            val city = j.optString("city", "").trim()
            if (city.length == 0) return null
            var lat = ""
            var lon = ""
            if (j.has("latitude") && j.has("longitude")) {
                lat = j.optDouble("latitude", Double.NaN).toString()
                lon = j.optDouble("longitude", Double.NaN).toString()
            } else if (j.has("loc")) {
                val loc = j.optString("loc", "")
                val parts = loc.split(",")
                if (parts.size == 2) {
                    lat = parts[0].trim()
                    lon = parts[1].trim()
                }
            }
            val dlat = lat.toDouble()
            val dlon = lon.toDouble()
            if (dlat.isNaN() || dlon.isNaN()) return null
            j.put("_provider", provider)
            j.put("_source", url)
            j.put("_lat", dlat)
            j.put("_lon", dlon)
            j.put("_city", displayCityName(city))
            return j
        } catch (e: Throwable) {
            logger("v3.72 IP源失败 " + provider + "=" + e)
            return null
        }
    }

    // 对译 F3 getIpLocation(1188-1200)
    private fun getIpLocation(): org.json.JSONObject? {
        // v3.76：仅使用 IPinfo 作为自动 IP 定位数据源。
        // ipapi.co 在当前网络环境持续返回 403，ipwho.is 持续给出错误城市，因此不再请求。
        val provider = "IPinfo"
        val url = "https://ipinfo.io/json"
        val r = parseIpProviderResult(provider, url)
        if (r != null) {
            logger(
                "v3.76 IP定位：IPinfo -> " + r.optString("_city", "") +
                        " lat=" + r.optDouble("_lat", Double.NaN) +
                        " lon=" + r.optDouble("_lon", Double.NaN)
            )
            return r
        }
        logger("v3.76 IP定位：IPinfo 获取失败")
        return null
    }

    // ==================== 天气 / 一言异步（对译 F3 loadRealWeatherAsync / loadQuoteAsync） ====================

    // 对译 F3 loadRealWeatherAsync(1202-1277)
    private fun loadRealWeatherAsync(state: MutableMap<String, Any?>, activity: Activity) {
        Thread {
            try {
                val p = activity.getSharedPreferences(SP_WEATHER, 0)
                val manual = p.getBoolean("manual", false)
                val savedCity = p.getString("city", "") ?: ""
                var city = savedCity
                var lat = p.getFloat("lat", Float.NaN).toDouble()
                var lon = p.getFloat("lon", Float.NaN).toDouble()

                if (manual && city.length > 0 && !lat.isNaN() && !lon.isNaN()) {
                    // 用户搜索/选择的城市：固定使用保存的城市和坐标，不再重新定位。
                    logger("v3.72 使用用户选择的固定城市=" + city + " lat=" + lat + " lon=" + lon)
                } else {
                    // 自动模式：严格按照用户选择的“跟随 IP 自动定位”使用公网 IP。
                    val ip = getIpLocation()
                    val ipCity = if (ip == null) "" else ip.optString("city", "")
                    if (ipCity.length == 0) throw Exception("IP定位失败")
                    city = displayCityName(ipCity)
                    lat = (if (ip == null) "NaN" else ip.optString("_lat", "NaN")).toDouble()
                    lon = (if (ip == null) "NaN" else ip.optString("_lon", "NaN")).toDouble()
                    if (lat.isNaN() || lon.isNaN()) throw Exception("IP定位坐标无效")
                    saveWeatherCity(activity, city, lat, lon, false)
                    logger(
                        "v3.76 跟随IP自动定位：" + city + " lat=" + lat + " lon=" + lon +
                                " source=" + (ip?.optString("_source", "") ?: "") +
                                " provider=" + (ip?.optString("_provider", "") ?: "")
                    )
                }

                val url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon +
                        "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
                        "&daily=temperature_2m_max,temperature_2m_min&timezone=auto"
                val json = h.heiErDing.utils.HttpUtil.getJson(url, 10000)
                    ?: throw Exception("天气接口返回为空")
                val current = json.optJSONObject("current")
                val daily = json.optJSONObject("daily")
                if (current == null || daily == null) throw Exception("天气数据不完整")
                val resolvedCity = displayCityName(city)
                val temp = current.optDouble("temperature_2m", Double.NaN)
                val feels = current.optDouble("apparent_temperature", Double.NaN)
                val humidity = current.optInt("relative_humidity_2m", -1)
                val wind = current.optDouble("wind_speed_10m", Double.NaN)
                val code = current.optInt("weather_code", -1)
                val maxArr = daily.optJSONArray("temperature_2m_max")
                val minArr = daily.optJSONArray("temperature_2m_min")
                val max = if (maxArr != null && maxArr.length() > 0) maxArr.optDouble(0, Double.NaN) else Double.NaN
                val min = if (minArr != null && minArr.length() > 0) minArr.optDouble(0, Double.NaN) else Double.NaN
                val time = current.optString("time", "")
                val condition = weatherCondition(code)
                val icon = weatherIcon(code)
                mainHandler.post {
                    try {
                        var v = state["weatherCity"] as? android.widget.TextView
                        if (v != null) v.text = "⌖  " + resolvedCity
                        v = state["weatherTemp"] as? android.widget.TextView
                        if (v != null) v.text = formatTemp(temp)
                        v = state["weatherFeels"] as? android.widget.TextView
                        if (v != null) v.text = "体感 " + formatTemp(feels)
                        v = state["weatherIcon"] as? android.widget.TextView
                        if (v != null) v.text = icon
                        v = state["weatherCondition"] as? android.widget.TextView
                        if (v != null) v.text = condition
                        v = state["weatherHighLow"] as? android.widget.TextView
                        if (v != null) v.text = formatTemp(max) + " / " + formatTemp(min)
                        v = state["weatherHumidity"] as? android.widget.TextView
                        if (v != null) v.text = if (humidity >= 0) humidity.toString() + "%" else "--%"
                        v = state["weatherWind"] as? android.widget.TextView
                        if (v != null) v.text =
                            if (wind.isNaN()) "-- km/h" else String.format(java.util.Locale.US, "%.1f km/h", wind)
                        v = state["weatherUpdate"] as? android.widget.TextView
                        if (v != null) v.text = "更新于 " + formatWeatherTime(time)
                        logger(
                            "v3.33 天气更新：" + resolvedCity + " " + formatTemp(temp) + " " + condition +
                                    " 湿度=" + humidity + "% 风速=" + wind + " manual=" + manual
                        )
                    } catch (e: Throwable) {
                        logger("v3.32 更新天气UI失败=" + e)
                    }
                }
            } catch (e: Throwable) {
                logger("v3.33 获取真实天气失败=" + e)
                val msg = e.message ?: "网络请求失败"
                mainHandler.post {
                    try {
                        var v = state["weatherCity"] as? android.widget.TextView
                        if (v != null) v.text = "⌖  定位失败，点击重试"
                        v = state["weatherCondition"] as? android.widget.TextView
                        if (v != null) v.text = "天气获取失败"
                        v = state["weatherUpdate"] as? android.widget.TextView
                        if (v != null) v.text = "暂时无法更新"
                        android.widget.Toast.makeText(activity, "天气定位失败：" + msg, android.widget.Toast.LENGTH_SHORT).show()
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }.start()
    }

    // 对译 F3 loadQuoteAsync(1279-1315)
    private fun loadQuoteAsync(state: MutableMap<String, Any?>, activity: Activity) {
        Thread {
            try {
                val cats = loadPanelString(activity, "quote_categories", defaultQuoteCategories())
                val min = loadPanelString(activity, "quote_min", "0")
                val max = loadPanelString(activity, "quote_max", "0")
                val showSource = loadPanelBool(activity, "quote_show_source", true)
                val showAuthor = loadPanelBool(activity, "quote_show_author", true)
                val cs = cats.split(",")
                val urlb = StringBuilder("https://v1.hitokoto.cn/?")
                var ci = 0
                while (ci < cs.size) {
                    if (ci > 0) urlb.append("&")
                    urlb.append("c=").append(cs[ci])
                    ci++
                }
                urlb.append("&min_length=").append(min).append("&max_length=").append(max)
                val j = h.heiErDing.utils.HttpUtil.getJson(urlb.toString(), 5000)
                val hit = if (j == null) "" else j.optString("hitokoto", "").trim()
                val from = if (j == null) "" else j.optString("from", "").trim()
                val creator = if (j == null) "" else j.optString("from_who", "").trim()
                if (hit.length == 0) throw Exception("一言接口返回为空")
                val text = hit
                val source = from
                val author = creator
                val fShowSource = showSource
                val fShowAuthor = showAuthor
                mainHandler.post {
                    if (state["attached"] != true) return@post
                    try {
                        var v = state["quoteText"] as? android.widget.TextView
                        if (v != null) v.text = "“ " + text + " ”"
                        v = state["quoteSource"] as? android.widget.TextView
                        if (v != null) {
                            val meta = StringBuilder("—")
                            if (fShowAuthor && author.length > 0) meta.append(" ").append(author)
                            if (fShowSource && source.length > 0) {
                                if (meta.length > 1) meta.append(" | ")
                                meta.append(source)
                            }
                            if (meta.length == 1) meta.append(" 一言")
                            v.text = meta.toString()
                        }
                        state["quoteLast"] = text
                        logger("v3.59 一言更新：" + text + " / " + author + " / " + source)
                    } catch (e: Throwable) {
                        logger("v3.56 一言UI更新失败=" + e)
                    }
                }
            } catch (e: Throwable) {
                val quotes = arrayOf(
                    "生活明朗，万物可爱。", "慢慢来，好戏都在烟火里。", "愿你所愿皆如愿，所行皆坦途。",
                    "保持热爱，奔赴下一场山海。", "心有微光，缓缓生长。", "今天也要好好生活。",
                    "把普通的日子过得浪漫一些。", "向前走，别回头，风会替你说晚安。", "山高水长，自在欢喜。",
                    "日子清静，抬头有光。"
                )
                val last = state["quoteLast"].toString()
                var idx = ((System.currentTimeMillis() / 1800000L) % quotes.size).toInt()
                if (quotes[idx] == last) idx = (idx + 1) % quotes.size
                val text = quotes[idx]
                mainHandler.post {
                    if (state["attached"] != true) return@post
                    try {
                        var v = state["quoteText"] as? android.widget.TextView
                        if (v != null) v.text = "“ " + text + " ”"
                        v = state["quoteSource"] as? android.widget.TextView
                        if (v != null) {
                            val ss = loadPanelBool(activity, "quote_show_source", true)
                            val aa = loadPanelBool(activity, "quote_show_author", true)
                            v.text = if (ss || aa) "— 本地一言" else ""
                        }
                        state["quoteLast"] = text
                        logger("v3.59 一言使用本地备用：" + text)
                    } catch (ignored: Throwable) {
                    }
                }
                logger("v3.59 一言网络获取失败，已使用本地备用：" + e)
            }
        }.start()
    }

    // 对译 F3 formatTemp(1317-1320)
    private fun formatTemp(v: Double): String {
        if (v.isNaN()) return "--°"
        return String.format(java.util.Locale.US, "%.0f°", v)
    }

    // 对译 F3 formatWeatherTime(1322-1325)
    private fun formatWeatherTime(s: String?): String {
        if (s == null || s.length < 16) return "刚刚"
        return s.substring(11, 16)
    }

    // 对译 F3 weatherCondition(1327-1340)
    private fun weatherCondition(c: Int): String {
        if (c == 0) return "晴"
        if (c == 1) return "基本晴朗"
        if (c == 2) return "多云"
        if (c == 3) return "阴"
        if (c == 45 || c == 48) return "雾"
        if (c in 51..57) return "毛毛雨"
        if (c in 61..67) return "下雨"
        if (c in 71..77) return "下雪"
        if (c in 80..82) return "阵雨"
        if (c == 85 || c == 86) return "阵雪"
        if (c == 95 || c == 96 || c == 99) return "雷雨"
        return "未知天气"
    }

    // 对译 F3 weatherIcon(1342-1354)
    private fun weatherIcon(c: Int): String {
        if (c == 0) return "☀"
        if (c == 1 || c == 2) return "☁☀"
        if (c == 3) return "☁"
        if (c == 45 || c == 48) return "≋"
        if (c in 51..57) return "☂"
        if (c in 61..67) return "☂"
        if (c in 71..77) return "❄"
        if (c in 80..82) return "☔"
        if (c == 85 || c == 86) return "❄"
        if (c == 95 || c == 96 || c == 99) return "⚡"
        return "☁"
    }

    // ==================== 触摸处理（对译 F3 handlePagerTouch / handleLauncherTouch / handleLauncherTouchLite / handleOverlayTouch / cancelUnderlyingPagerTouch / isHomePager / ensurePagerSession / getDrawerWidth / findSessionByActivity） ====================

    private fun isHomePager(pager: View?): Boolean {
        if (pager == null) return false
        try {
            val m = findMethod(pager.javaClass, "getCurrentItem", 0)
            if (m != null) {
                val v = m.invoke(pager)
                if (v is Number) return v.toInt() == HOME_TAB
            }
        } catch (e: Throwable) {
        }
        return true
    }

    // 对译 F3 getDrawerWidth(1895-1922)：优先缓存 width，其次 decor 宽度，再次 pager 宽度，兜底 1
    private fun getDrawerWidth(s: MutableMap<String, Any?>): Int {
        try {
            val v = s["width"]
            if (v is Number) {
                val n = v.toInt()
                if (n > 0) return n
            }
            val decorObj = s["decor"]
            if (decorObj is View) {
                val w = decorObj.width
                if (w > 0) {
                    val n = Math.max(1, (w * DRAWER_FRACTION).toInt())
                    s["width"] = n
                    return n
                }
            }
            val pagerObj = s["pager"]
            if (pagerObj is View) {
                val w = pagerObj.width
                if (w > 0) {
                    val n = Math.max(1, (w * DRAWER_FRACTION).toInt())
                    s["width"] = n
                    return n
                }
            }
        } catch (ignored: Throwable) {
        }
        return 1
    }

    private fun findSessionByActivity(a: Activity): MutableMap<String, Any?>? {
        try {
            for (s in ArrayList(sessions.values)) {
                try {
                    val act = s["activity"] as? Activity
                    if (act === a) return s
                } catch (e: Throwable) {
                }
            }
        } catch (e: Throwable) {
        }
        return null
    }

    // 对译 F3 cancelUnderlyingPagerTouch(2118-2133)：sendingCancel 幂等守卫 + 向 pager 派发 ACTION_CANCEL
    private fun cancelUnderlyingPagerTouch(pager: View?, ev: MotionEvent?, s: MutableMap<String, Any?>?) {
        if (pager == null || ev == null || s == null) return
        if ((s["sendingCancel"] as? Boolean) == true) return
        try {
            s["sendingCancel"] = true
            val cancel = MotionEvent.obtain(ev)
            cancel.action = MotionEvent.ACTION_CANCEL
            pager.dispatchTouchEvent(cancel)
            cancel.recycle()
            logger("v3.7 已向 CustomViewPager 发送真实 ACTION_CANCEL，终止底层长按状态", null)
        } catch (e: Throwable) {
            logger("v3.7 发送 ACTION_CANCEL 失败=" + e, null)
        } finally {
            s["sendingCancel"] = false
        }
    }

    // 对译 F3 ensurePagerSession(2094-2116)：以 pager 为键懒加载 session
    private fun ensurePagerSession(pager: View?) {
        try {
            if (pager == null || sessions[pager] != null) return
            val ctx = pager.context
            val a = if (ctx is Activity) ctx else null
            if (a == null) {
                logger("v2.8 CustomViewPager 找不到 Activity context=" + ctx, null)
                return
            }
            val dv = a.window.decorView
            if (dv !is FrameLayout) {
                logger("v2.8 DecorView 不是 FrameLayout", null)
                return
            }
            val state = HashMap<String, Any?>()
            state["activity"] = a
            state["pager"] = pager
            state["decor"] = dv
            state["attached"] = false
            state["tracking"] = false
            state["dragging"] = false
            state["progress"] = 0f
            state["animToken"] = 0
            state["sendingCancel"] = false
            sessions[pager] = state
            attachSession(state)
            logger("v3.1 根据 CustomViewPager 实例懒加载负一屏 session 完成", null)
        } catch (e: Throwable) {
            logger("v3.0 懒加载 session 失败=" + e, null)
        }
    }

    // 对译 F3 handlePagerTouch(2217-2285)
    private fun handlePagerTouch(param: MethodHookParam) {
        try {
            if (!enabled) return
            val pager = param.thisObject as? View ?: return
            val args = param.args ?: return
            if (args.isEmpty()) return
            var s: MutableMap<String, Any?>? = sessions[pager]
            if (s == null) {
                ensurePagerSession(pager)
                s = sessions[pager]
            }
            val sess = s ?: return
            val ev = args[0] as? MotionEvent ?: return
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && !isHomePager(pager)) {
                sess["tracking"] = false
                sess["dragging"] = false
                return
            }
            val root = sess["root"] as? FrameLayout
            if (root != null && root.visibility == View.VISIBLE &&
                ((sess["progress"] as? Number)?.toFloat() ?: 0f) > 0.001f &&
                (sess["tracking"] as? Boolean) != true
            ) {
                return
            }
            val a = sess["activity"] as? Activity ?: return
            val slop = dp(a, SLOP_DP)
            val width = getDrawerWidth(sess)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    logger(
                        "v3.10 Pager DOWN width=" + width + " progress=" +
                            ((sess["progress"] as? Number)?.toFloat() ?: 0f), null
                    )
                    sess["downX"] = ev.x
                    sess["downY"] = ev.y
                    sess["downT"] = ev.eventTime
                    sess["tracking"] = true
                    sess["dragging"] = false
                    sess["startProgress"] = (sess["progress"] as? Number)?.toFloat() ?: 0f
                    sess["lastX"] = ev.x
                    sess["lastT"] = ev.eventTime
                    sess["velocity"] = 0f
                    return
                }

                MotionEvent.ACTION_MOVE -> {
                    if ((sess["tracking"] as? Boolean) != true) return
                    val downX = (sess["downX"] as? Number)?.toFloat() ?: return
                    val downY = (sess["downY"] as? Number)?.toFloat() ?: return
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if ((sess["dragging"] as? Boolean) != true) {
                        if (Math.abs(dx) < slop && Math.abs(dy) < slop) return
                        if (Math.abs(dy) > Math.abs(dx) * 1.15f) {
                            sess["tracking"] = false
                            return
                        }
                        val start = (sess["startProgress"] as? Number)?.toFloat() ?: 0f
                        if (start <= 0.001f && dx <= 0f) {
                            sess["tracking"] = false
                            return
                        }
                        sess["dragging"] = true
                        cancelUnderlyingPagerTouch(pager, ev, sess)
                        param.result = true
                    }
                    val lastT = (sess["lastT"] as? Number)?.toLong() ?: ev.eventTime
                    val lastX = (sess["lastX"] as? Number)?.toFloat() ?: ev.x
                    val dt = Math.max(1L, ev.eventTime - lastT)
                    val velocity = (ev.x - lastX) / dt.toFloat()
                    sess["velocity"] = velocity
                    sess["lastX"] = ev.x
                    sess["lastT"] = ev.eventTime
                    val start = (sess["startProgress"] as? Number)?.toFloat() ?: 0f
                    val next = Math.max(0f, Math.min(1f, start + dx / width.toFloat()))
                    sess["progress"] = next
                    applySession(sess)
                    param.result = true
                    return
                }

                MotionEvent.ACTION_UP -> {
                    if ((sess["tracking"] as? Boolean) != true) return
                    val dragging = (sess["dragging"] as? Boolean) == true
                    val current = (sess["progress"] as? Number)?.toFloat() ?: 0f
                    if (!dragging) {
                        sess["tracking"] = false
                        sess["dragging"] = false
                        return
                    }
                    val velocity = (sess["velocity"] as? Number)?.toFloat() ?: 0f
                    sess["tracking"] = false
                    sess["dragging"] = false
                    val projected = current + velocity * 160f / width.toFloat()
                    val open = projected >= 0.25f
                    if (open) openSessionWithRandom(sess) else animateSession(sess, 0f)
                    param.result = true
                    return
                }

                MotionEvent.ACTION_CANCEL -> {
                    if ((sess["sendingCancel"] as? Boolean) == true) return
                    val dragging = (sess["dragging"] as? Boolean) == true
                    val current = (sess["progress"] as? Number)?.toFloat() ?: 0f
                    sess["tracking"] = false
                    sess["dragging"] = false
                    if (dragging) {
                        if (current >= 0.25f) openSessionWithRandom(sess) else animateSession(sess, 0f)
                    }
                }
            }
        } catch (e: Throwable) {
            logger("v3.0 负一屏触摸处理失败", e)
        }
    }

    // 对译 F3 handleLauncherTouchLite(2147-2215)
    private fun handleLauncherTouchLite(param: MethodHookParam) {
        try {
            if (!enabled) return
            val args = param.args ?: return
            if (args.isEmpty()) return
            val ev = args[0] as? MotionEvent ?: return
            val a = param.thisObject as? Activity ?: return
            var s: MutableMap<String, Any?>? = null
            for (value in ArrayList(sessions.values)) {
                try {
                    if ((value["activity"] as? Activity) === a) {
                        s = value
                        break
                    }
                } catch (ignored: Throwable) {
                }
            }
            val sess = s ?: return
            val root = sess["root"] as? FrameLayout
            if (root == null || root.visibility != View.VISIBLE) return
            val progress = (sess["progress"] as? Number)?.toFloat() ?: 0f
            if (progress <= 0.001f) return
            val action = ev.actionMasked
            val width = getDrawerWidth(sess)
            val slop = dp(a, SLOP_DP)

            if (action == MotionEvent.ACTION_DOWN) {
                sess["closeDownX"] = ev.x
                sess["closeDownY"] = ev.y
                sess["closeLastX"] = ev.x
                sess["closeLastT"] = ev.eventTime
                sess["closeTracking"] = true
                sess["closeDragging"] = false
                sess["closeVelocity"] = 0f
                if (ev.x > width) {
                    closeSession(sess)
                    param.result = true
                }
                return
            }

            if (action == MotionEvent.ACTION_MOVE && (sess["closeTracking"] as? Boolean) == true) {
                val closeDownX = (sess["closeDownX"] as? Number)?.toFloat() ?: return
                val closeDownY = (sess["closeDownY"] as? Number)?.toFloat() ?: return
                val dx = ev.x - closeDownX
                val dy = ev.y - closeDownY
                if ((sess["closeDragging"] as? Boolean) != true) {
                    if (Math.abs(dx) < slop && Math.abs(dy) < slop) return
                    if (Math.abs(dy) > Math.abs(dx) * 1.15f || dx >= 0f) {
                        sess["closeTracking"] = false
                        return
                    }
                    sess["closeDragging"] = true
                    val pager = sess["pager"] as? View
                    if (pager != null) {
                        cancelUnderlyingPagerTouch(pager, ev, sess)
                    }
                    param.result = true
                    return
                }
                param.result = true
                return
            }

            if (action == MotionEvent.ACTION_UP && (sess["closeTracking"] as? Boolean) == true) {
                val dragging = (sess["closeDragging"] as? Boolean) == true
                sess["closeTracking"] = false
                sess["closeDragging"] = false
                if (dragging) {
                    animateSession(sess, 0f)
                    param.result = true
                }
                return
            }

            if (action == MotionEvent.ACTION_CANCEL) {
                sess["closeTracking"] = false
                sess["closeDragging"] = false
            }
        } catch (ignored: Throwable) {
        }
    }

    // 对译 F3 handleLauncherTouch(1924-2040)
    private fun handleLauncherTouch(param: MethodHookParam) {
        try {
            if (!enabled) return
            val args = param.args ?: return
            if (args.isEmpty()) return
            val ev = args[0] as? MotionEvent ?: return
            val a = param.thisObject as? Activity ?: return
            var s: MutableMap<String, Any?>? = null
            for (value in ArrayList(sessions.values)) {
                try {
                    if ((value["activity"] as? Activity) === a) {
                        s = value
                        break
                    }
                } catch (ignored: Throwable) {
                }
            }
            val sess = s ?: return
            val root = sess["root"] as? FrameLayout ?: return
            val slop = dp(a, SLOP_DP)
            val width = getDrawerWidth(sess)
            val progress = (sess["progress"] as? Number)?.toFloat() ?: 0f

            if (progress > 0.001f && root.visibility == View.VISIBLE) {
                val outsideClosing = (sess["outsideClosing"] as? Boolean) == true
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    sess["launcherDownX"] = ev.x
                    sess["launcherDownY"] = ev.y
                    sess["launcherLastX"] = ev.x
                    sess["launcherLastT"] = ev.eventTime
                    sess["launcherTracking"] = true
                    sess["launcherDragging"] = false
                    sess["launcherStartProgress"] = progress
                    sess["launcherVelocity"] = 0f
                    if (ev.x > width) {
                        sess["outsideClosing"] = true
                        logger("v3.0 点击负一屏外部，立即关闭 x=" + ev.x + " drawerWidth=" + width, null)
                        closeSession(sess)
                        param.result = true
                        return
                    }
                    return
                }
                if (outsideClosing) {
                    if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                        sess["outsideClosing"] = false
                    }
                    param.result = true
                    return
                }
                if (ev.actionMasked == MotionEvent.ACTION_MOVE &&
                    (sess["launcherTracking"] as? Boolean) == true
                ) {
                    val launcherDownX = (sess["launcherDownX"] as? Number)?.toFloat() ?: return
                    val launcherDownY = (sess["launcherDownY"] as? Number)?.toFloat() ?: return
                    val dx = ev.x - launcherDownX
                    val dy = ev.y - launcherDownY
                    if ((sess["launcherDragging"] as? Boolean) != true) {
                        if (Math.abs(dx) < slop && Math.abs(dy) < slop) return
                        if (Math.abs(dy) > Math.abs(dx) * 1.15f) {
                            sess["launcherTracking"] = false
                            return
                        }
                        if (dx >= 0f) {
                            sess["launcherTracking"] = false
                            return
                        }
                        sess["launcherDragging"] = true
                        logger("v3.0 开始接管负一屏左滑关闭 dx=" + dx, null)
                    }
                    val launcherLastT = (sess["launcherLastT"] as? Number)?.toLong() ?: ev.eventTime
                    val launcherLastX = (sess["launcherLastX"] as? Number)?.toFloat() ?: ev.x
                    val dt = Math.max(1L, ev.eventTime - launcherLastT)
                    val velocity = (ev.x - launcherLastX) / dt.toFloat()
                    sess["launcherVelocity"] = velocity
                    sess["launcherLastX"] = ev.x
                    sess["launcherLastT"] = ev.eventTime
                    val next = Math.max(0f, Math.min(1f, progress + dx / width.toFloat()))
                    sess["progress"] = next
                    applySession(sess)
                    param.result = true
                    return
                }
                if (ev.actionMasked == MotionEvent.ACTION_UP &&
                    (sess["launcherTracking"] as? Boolean) == true
                ) {
                    val dragging = (sess["launcherDragging"] as? Boolean) == true
                    val current = (sess["progress"] as? Number)?.toFloat() ?: 0f
                    val velocity = (sess["launcherVelocity"] as? Number)?.toFloat() ?: 0f
                    sess["launcherTracking"] = false
                    sess["launcherDragging"] = false
                    if (dragging) {
                        val projected = current + velocity * 160f / width.toFloat()
                        val keepOpen = projected >= 0.62f
                        logger(
                            "v3.0 负一屏左滑结束 current=" + current + " velocity=" + velocity +
                                " projected=" + projected + " keepOpen=" + keepOpen, null
                        )
                        animateSession(sess, if (keepOpen) 1f else 0f)
                        param.result = true
                        return
                    }
                    return
                }
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    sess["launcherTracking"] = false
                    sess["launcherDragging"] = false
                    return
                }
                return
            }

            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                logger(
                    "v3.0 DOWN x=" + ev.x + " y=" + ev.y + " drawerWidth=" + width +
                        " progress=" + progress, null
                )
                sess["launcherDownX"] = ev.x
                sess["launcherDownY"] = ev.y
                sess["launcherLastX"] = ev.x
                sess["launcherLastT"] = ev.eventTime
                sess["launcherTracking"] = true
                sess["launcherStartProgress"] = progress
                sess["launcherDragging"] = false
                sess["launcherVelocity"] = 0f
                return
            }
            if (ev.actionMasked == MotionEvent.ACTION_MOVE &&
                (sess["launcherTracking"] as? Boolean) == true
            ) {
                val launcherDownX = (sess["launcherDownX"] as? Number)?.toFloat() ?: return
                val launcherDownY = (sess["launcherDownY"] as? Number)?.toFloat() ?: return
                val dx = ev.x - launcherDownX
                val dy = ev.y - launcherDownY
                if ((sess["launcherDragging"] as? Boolean) != true) {
                    if (Math.abs(dx) < slop && Math.abs(dy) < slop) return
                    if (Math.abs(dy) > Math.abs(dx) * 1.15f) {
                        sess["launcherTracking"] = false
                        logger("v3.0 放弃：竖向手势 dx=" + dx + " dy=" + dy, null)
                        return
                    }
                    if (progress <= 0.001f && dx <= 0f) {
                        sess["launcherTracking"] = false
                        return
                    }
                    sess["launcherDragging"] = true
                    logger("v3.0 开始接管右滑 dx=" + dx + " startProgress=" + progress, null)
                    if (root.visibility != View.VISIBLE) root.visibility = View.VISIBLE
                    val old = ev.action
                    ev.action = MotionEvent.ACTION_CANCEL
                    param.result = true
                    ev.action = old
                }
                val launcherLastT = (sess["launcherLastT"] as? Number)?.toLong() ?: ev.eventTime
                val launcherLastX = (sess["launcherLastX"] as? Number)?.toFloat() ?: ev.x
                val dt = Math.max(1L, ev.eventTime - launcherLastT)
                val velocity = (ev.x - launcherLastX) / dt.toFloat()
                sess["launcherVelocity"] = velocity
                sess["launcherLastX"] = ev.x
                sess["launcherLastT"] = ev.eventTime
                val startProgress = (sess["launcherStartProgress"] as? Number)?.toFloat() ?: progress
                val next = Math.max(0f, Math.min(1f, startProgress + dx / width.toFloat()))
                sess["progress"] = next
                applySession(sess)
                param.result = true
                return
            }
            if (ev.actionMasked == MotionEvent.ACTION_UP &&
                (sess["launcherTracking"] as? Boolean) == true
            ) {
                val current = (sess["progress"] as? Number)?.toFloat() ?: 0f
                val velocity = (sess["launcherVelocity"] as? Number)?.toFloat() ?: 0f
                val dragging = (sess["launcherDragging"] as? Boolean) == true
                val projected = current + velocity * 160f / width.toFloat()
                val open = if (dragging) projected >= 0.38f else current >= 0.38f
                sess["launcherTracking"] = false
                sess["launcherDragging"] = false
                logger(
                    "v3.0 UP current=" + current + " velocity=" + velocity +
                        " projected=" + projected + " open=" + open, null
                )
                if (open) openSessionWithRandom(sess) else animateSession(sess, 0f)
                param.result = true
                return
            }
            if (ev.actionMasked == MotionEvent.ACTION_CANCEL &&
                (sess["launcherTracking"] as? Boolean) == true
            ) {
                val dragging = (sess["launcherDragging"] as? Boolean) == true
                val current = (sess["progress"] as? Number)?.toFloat() ?: 0f
                sess["launcherTracking"] = false
                sess["launcherDragging"] = false
                if (dragging) {
                    if (current >= 0.38f) openSessionWithRandom(sess) else animateSession(sess, 0f)
                }
                logger("v3.0 CANCEL current=" + current + " dragging=" + dragging, null)
            }
        } catch (e: Throwable) {
            logger("v2.8 LauncherUI触摸处理异常=" + e, null)
        }
    }

    // 对译 F3 handleOverlayTouch(2042-2092)
    // 注：F3 中该函数从未被 hookBefore/hookAfter 引用（死代码），此处保留为内部方法，不注册。
    private fun handleOverlayTouch(s: MutableMap<String, Any?>, e: MotionEvent): Boolean {
        try {
            if (e == null) return false
            val a = s["activity"] as? Activity ?: return false
            val slop = dp(a, SLOP_DP)
            val width = getDrawerWidth(s)
            val action = e.actionMasked
            if (action == MotionEvent.ACTION_DOWN) {
                s["overlayDownX"] = e.x
                s["overlayDownY"] = e.y
                s["overlayLastX"] = e.x
                s["overlayLastT"] = e.eventTime
                s["overlayTracking"] = true
                s["overlayDragging"] = false
                s["overlayStartProgress"] = (s["progress"] as? Number)?.toFloat() ?: 0f
                s["overlayVelocity"] = 0f
                return true
            }
            if (action == MotionEvent.ACTION_MOVE && (s["overlayTracking"] as? Boolean) == true) {
                val overlayDownX = (s["overlayDownX"] as? Number)?.toFloat() ?: return true
                val overlayDownY = (s["overlayDownY"] as? Number)?.toFloat() ?: return true
                val dx = e.x - overlayDownX
                val dy = e.y - overlayDownY
                if ((s["overlayDragging"] as? Boolean) != true) {
                    if (Math.abs(dx) < slop && Math.abs(dy) < slop) return true
                    if (Math.abs(dy) > Math.abs(dx) * 1.15f) {
                        s["overlayTracking"] = false
                        return false
                    }
                    s["overlayDragging"] = true
                }
                val overlayLastT = (s["overlayLastT"] as? Number)?.toLong() ?: e.eventTime
                val overlayLastX = (s["overlayLastX"] as? Number)?.toFloat() ?: e.x
                val dt = Math.max(1L, e.eventTime - overlayLastT)
                val velocity = (e.x - overlayLastX) / dt.toFloat()
                s["overlayVelocity"] = velocity
                s["overlayLastX"] = e.x
                s["overlayLastT"] = e.eventTime
                val start = (s["overlayStartProgress"] as? Number)?.toFloat() ?: 0f
                val next = Math.max(0f, Math.min(1f, start + dx / width.toFloat()))
                s["progress"] = next
                applySession(s)
                return true
            }
            if (action == MotionEvent.ACTION_UP) {
                val dragging = (s["overlayDragging"] as? Boolean) == true
                val current = (s["progress"] as? Number)?.toFloat() ?: 0f
                val velocity = (s["overlayVelocity"] as? Number)?.toFloat() ?: 0f
                val projected = current + velocity * 160f / width.toFloat()
                s["overlayTracking"] = false
                s["overlayDragging"] = false
                if (dragging) {
                    if (projected >= 0.38f) openSessionWithRandom(s) else animateSession(s, 0f)
                }
                return true
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                val dragging = (s["overlayDragging"] as? Boolean) == true
                val current = (s["progress"] as? Number)?.toFloat() ?: 0f
                s["overlayTracking"] = false
                s["overlayDragging"] = false
                if (dragging) {
                    if (current >= 0.38f) openSessionWithRandom(s) else animateSession(s, 0f)
                }
                return true
            }
            return true
        } catch (ex: Throwable) {
            logger("负一屏 overlay 手势异常=" + ex, null)
            return true
        }
    }

    // ==================== 第四批：设置对话框（对译 F3 515-965） ====================

    private fun showLunarSettings(state: MutableMap<String, Any?>, activity: Activity) {
        try {
            val dlg = android.app.Dialog(activity)
            val page = LinearLayout(activity)
            page.orientation = LinearLayout.VERTICAL
            val bg = android.graphics.drawable.GradientDrawable()
            bg.setColor(Color.rgb(246, 251, 244))
            bg.cornerRadii = floatArrayOf(0f, 0f, dp(activity, 32).toFloat(), dp(activity, 32).toFloat(), dp(activity, 32).toFloat(), dp(activity, 32).toFloat(), 0f, 0f)
            page.background = bg
            page.setPadding(dp(activity, 18), dp(activity, 18), dp(activity, 18), dp(activity, 18))

            val head = LinearLayout(activity)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            val back = txt(activity, "‹", 40f, false)
            back.setTextColor(Color.rgb(55, 65, 58))
            back.gravity = Gravity.CENTER
            back.setOnClickListener { dlg.dismiss() }
            head.addView(back, LinearLayout.LayoutParams(dp(activity, 58), dp(activity, 62)))
            val title = txt(activity, "日期与时间设置", 25f, true)
            title.setTextColor(Color.rgb(31, 91, 48))
            title.gravity = Gravity.CENTER_VERTICAL
            head.addView(title, LinearLayout.LayoutParams(0, dp(activity, 62), 1f))
            page.addView(head, LinearLayout.LayoutParams(-1, dp(activity, 70)))

            val row = LinearLayout(activity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val label = txt(activity, "显示农历", 19f, false)
            label.setTextColor(Color.rgb(55, 65, 58))
            label.gravity = Gravity.CENTER_VERTICAL
            row.addView(label, LinearLayout.LayoutParams(0, dp(activity, 66), 1f))
            var on = loadPanelBool(state, "show_lunar", false)
            val tg = makeVideoToggle(activity, on)
            tg.setOnClickListener {
                on = !on
                state["showLunar"] = on
                savePanelBool(state, activity, "show_lunar", on)
                renderVideoToggle(tg, activity, on)
                refreshLunarDisplay(state, activity)
                (state["lunarText"] as? android.widget.TextView)?.requestLayout()
                (state["lunarText"] as? android.widget.TextView)?.invalidate()
            }
            val tgLp = LinearLayout.LayoutParams(dp(activity, 58), dp(activity, 32))
            tgLp.setMargins(0, 0, dp(activity, 18), 0)
            row.addView(tg, tgLp)
            val rowLp = LinearLayout.LayoutParams(-1, dp(activity, 66))
            rowLp.setMargins(0, dp(activity, 18), 0, 0)
            page.addView(row, rowLp)

            dlg.setContentView(page)
            dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            dlg.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dlg.window?.setDimAmount(0.55f)
            dlg.window?.setGravity(Gravity.LEFT or Gravity.CENTER_VERTICAL)
            dlg.setOnShowListener {
                val sw = activity.resources.displayMetrics.widthPixels
                dlg.window?.setLayout((sw * 0.84f).toInt(), -1)
            }
            dlg.show()
        } catch (e: Throwable) {
            logger("showLunarSettings 异常=" + e, null)
        }
    }

    private fun showQuoteSettings(state: MutableMap<String, Any?>, activity: Activity) {
        try {
            val codes = quoteCategoryCodes()
            val names = quoteCategoryNames()
            var sel = loadPanelString(state, "quote_categories", defaultQuoteCategories())
            val checked = BooleanArray(codes.size)
            for (i in codes.indices) checked[i] = sel.indexOf(codes[i]) >= 0
            var qmin = parseSettingInt(state["quote_min"] as? String, 0, 0, 100)
            var qmax = parseSettingInt(state["quote_max"] as? String, 0, 0, 100)
            var showSource = parseSettingBool(state, "quote_show_source", true)
            var showAuthor = parseSettingBool(state, "quote_show_author", true)

            val dlg = android.app.Dialog(activity)
            val page = LinearLayout(activity)
            page.orientation = LinearLayout.VERTICAL
            val bg = android.graphics.drawable.GradientDrawable()
            bg.setColor(Color.rgb(246, 251, 244))
            bg.cornerRadii = floatArrayOf(0f, 0f, dp(activity, 32).toFloat(), dp(activity, 32).toFloat(), dp(activity, 32).toFloat(), dp(activity, 32).toFloat(), 0f, 0f)
            page.background = bg
            page.setPadding(dp(activity, 18), dp(activity, 18), dp(activity, 18), dp(activity, 18))

            val head = LinearLayout(activity)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            val back = txt(activity, "‹", 40f, false)
            back.setTextColor(Color.rgb(55, 65, 58))
            back.gravity = Gravity.CENTER
            back.setOnClickListener { dlg.dismiss() }
            head.addView(back, LinearLayout.LayoutParams(dp(activity, 58), dp(activity, 62)))
            val title = txt(activity, "一言设置", 25f, true)
            title.setTextColor(Color.rgb(31, 91, 48))
            title.gravity = Gravity.CENTER_VERTICAL
            head.addView(title, LinearLayout.LayoutParams(0, dp(activity, 62), 1f))
            page.addView(head, LinearLayout.LayoutParams(-1, dp(activity, 70)))

            val scroll = android.widget.ScrollView(activity)
            val body = LinearLayout(activity)
            body.orientation = LinearLayout.VERTICAL
            scroll.addView(body)
            page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

            val cat = txt(activity, "分类", 21f, true)
            cat.setTextColor(Color.rgb(31, 91, 48))
            cat.gravity = Gravity.CENTER_VERTICAL
            body.addView(cat, LinearLayout.LayoutParams(-1, dp(activity, 44)))

            val chips = ArrayList<TextView>()
            var gi = 0
            while (gi < codes.size) {
                val gr = LinearLayout(activity)
                gr.orientation = LinearLayout.HORIZONTAL
                for (j in 0 until 3) {
                    val idx = gi + j
                    if (idx >= codes.size) {
                        gr.addView(View(activity), LinearLayout.LayoutParams(0, dp(activity, 66), 1f))
                    } else {
                        val chip = TextView(activity)
                        chip.text = names[idx]
                        chip.gravity = Gravity.CENTER
                        chip.setTextSize(15f)
                        renderCategoryChip(chip, activity, checked[idx])
                        val ci = idx
                        chip.setOnClickListener {
                            checked[ci] = !checked[ci]
                            renderCategoryChip(chip, activity, checked[ci])
                        }
                        chips.add(chip)
                        val clp = LinearLayout.LayoutParams(0, dp(activity, 56), 1f)
                        clp.setMargins(dp(activity, 6), dp(activity, 5), dp(activity, 6), dp(activity, 5))
                        gr.addView(chip, clp)
                    }
                }
                body.addView(gr, LinearLayout.LayoutParams(-1, dp(activity, 66)))
                gi += 3
            }

            fun addSeek(label: String, value: Int, onChange: (Int) -> Unit) {
                val rr = LinearLayout(activity)
                rr.orientation = LinearLayout.HORIZONTAL
                rr.gravity = Gravity.CENTER_VERTICAL
                val lb = txt(activity, label, 19f, false)
                lb.setTextColor(Color.rgb(55, 65, 58))
                lb.gravity = Gravity.CENTER_VERTICAL
                rr.addView(lb, LinearLayout.LayoutParams(0, dp(activity, 60), 1f))
                val sb = android.widget.SeekBar(activity)
                sb.max = 100
                sb.progress = value
                val vv = txt(activity, value.toString(), 17f, true)
                vv.setTextColor(Color.rgb(49, 117, 68))
                vv.gravity = Gravity.CENTER
                rr.addView(sb, LinearLayout.LayoutParams(0, dp(activity, 60), 1.2f))
                rr.addView(vv, LinearLayout.LayoutParams(dp(activity, 58), dp(activity, 60)))
                sb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                        vv.text = progress.toString()
                        onChange(progress)
                    }
                    override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
                })
                body.addView(rr, LinearLayout.LayoutParams(-1, dp(activity, 60)))
            }

            addSeek("最短长度", qmin) { qmin = it }
            addSeek("最长长度", qmax) { qmax = it }

            val st = makeVideoToggle(activity, showSource)
            addToggleRow(body, activity, "显示来源", st)
            st.setOnClickListener {
                showSource = !showSource
                renderVideoToggle(st, activity, showSource)
            }
            val au = makeVideoToggle(activity, showAuthor)
            addToggleRow(body, activity, "显示作者", au)
            au.setOnClickListener {
                showAuthor = !showAuthor
                renderVideoToggle(au, activity, showAuthor)
            }

            val bottom = LinearLayout(activity)
            bottom.orientation = LinearLayout.HORIZONTAL
            bottom.gravity = Gravity.CENTER_VERTICAL
            val reset = TextView(activity)
            reset.text = "恢复默认"
            reset.gravity = Gravity.CENTER
            reset.setTextSize(16f)
            reset.setTextColor(Color.rgb(49, 117, 68))
            val rb = android.graphics.drawable.GradientDrawable()
            rb.setCornerRadius(dp(activity, 30).toFloat())
            rb.setStroke(dp(activity, 1), Color.rgb(49, 117, 68))
            rb.setColor(Color.TRANSPARENT)
            reset.background = rb
            reset.setOnClickListener {
                for (i in codes.indices) checked[i] = defaultQuoteCategories().indexOf(codes[i]) >= 0
                for (i in chips.indices) renderCategoryChip(chips[i], activity, checked[i])
                qmin = 0; qmax = 0
                showSource = true; showAuthor = true
                renderVideoToggle(st, activity, true)
                renderVideoToggle(au, activity, true)
                dlg.dismiss()
                showQuoteSettings(state, activity)
            }
            val rlp = LinearLayout.LayoutParams(0, dp(activity, 48), 1f)
            rlp.setMargins(0, dp(activity, 10), dp(activity, 6), 0)
            bottom.addView(reset, rlp)

            val save = TextView(activity)
            save.text = "保存"
            save.gravity = Gravity.CENTER
            save.setTextSize(16f)
            save.setTextColor(Color.WHITE)
            val sbg = android.graphics.drawable.GradientDrawable()
            sbg.setCornerRadius(dp(activity, 30).toFloat())
            sbg.setColor(Color.rgb(49, 117, 68))
            save.background = sbg
            save.setOnClickListener {
                var any = false
                for (b in checked) if (b) any = true
                if (!any) {
                    android.widget.Toast.makeText(activity, "至少选择一个分类", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (qmax > 0 && qmin > qmax) {
                    android.widget.Toast.makeText(activity, "最长长度不能小于最短长度", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val sb2 = StringBuilder()
                for (i in codes.indices) {
                    if (checked[i]) {
                        if (sb2.isNotEmpty()) sb2.append(",")
                        sb2.append(codes[i])
                    }
                }
                state["quote_categories"] = sb2.toString()
                state["quote_min"] = qmin.toString()
                state["quote_max"] = qmax.toString()
                state["quote_show_source"] = showSource
                state["quote_show_author"] = showAuthor
                savePanelString(state, activity, "quote_categories", sb2.toString())
                savePanelInt(state, activity, "quote_min", qmin)
                savePanelInt(state, activity, "quote_max", qmax)
                savePanelBool(state, activity, "quote_show_source", showSource)
                savePanelBool(state, activity, "quote_show_author", showAuthor)
                loadQuoteAsync(state, activity)
                dlg.dismiss()
            }
            val slp = LinearLayout.LayoutParams(0, dp(activity, 48), 1f)
            slp.setMargins(dp(activity, 6), dp(activity, 10), 0, 0)
            bottom.addView(save, slp)
            page.addView(bottom, LinearLayout.LayoutParams(-1, dp(activity, 58)))

            dlg.setContentView(page)
            dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            dlg.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dlg.window?.setDimAmount(0.55f)
            dlg.window?.setGravity(Gravity.LEFT or Gravity.CENTER_VERTICAL)
            dlg.setOnShowListener {
                val sw = activity.resources.displayMetrics.widthPixels
                dlg.window?.setLayout((sw * 0.84f).toInt(), -1)
            }
            dlg.show()
        } catch (e: Throwable) {
            logger("showQuoteSettings 异常=" + e, null)
        }
    }

    private fun renderCategoryChip(chip: TextView, a: Activity, on: Boolean) {
        val g = android.graphics.drawable.GradientDrawable()
        g.setCornerRadius(dp(a, 16).toFloat())
        if (on) {
            g.setColor(Color.rgb(211, 236, 214))
            chip.setTextColor(Color.rgb(48, 88, 57))
        } else {
            g.setColor(Color.rgb(246, 251, 244))
            g.setStroke(dp(a, 1), Color.rgb(190, 203, 191))
            chip.setTextColor(Color.rgb(55, 65, 58))
        }
        chip.background = g
    }

    private fun addToggleRow(parent: LinearLayout, a: Activity, label: String, tg: FrameLayout) {
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val lb = txt(a, label, 21f, false)
        lb.setTextColor(Color.rgb(55, 65, 58))
        lb.gravity = Gravity.CENTER_VERTICAL
        row.addView(lb, LinearLayout.LayoutParams(0, dp(a, 68), 1f))
        row.addView(tg, LinearLayout.LayoutParams(dp(a, 58), dp(a, 32)))
        val lp = LinearLayout.LayoutParams(-1, dp(a, 74))
        lp.setMargins(0, dp(a, 6), 0, 0)
        parent.addView(row, lp)
    }

    private fun showPanelSettings(state: MutableMap<String, Any?>, activity: Activity) {
        try {
            val green = Color.rgb(31, 91, 48)
            val root = LinearLayout(activity)
            root.orientation = LinearLayout.VERTICAL
            root.setPadding(dp(activity, 18), dp(activity, 8), dp(activity, 18), dp(activity, 8))

            val edit = txt(activity, "✎ 编辑主页布局", 20f, true)
            edit.setTextColor(green)
            edit.gravity = Gravity.CENTER_VERTICAL
            edit.setOnClickListener { showPanelEditor(state, activity) }
            root.addView(edit, LinearLayout.LayoutParams(-1, dp(activity, 52)))
            addSettingsDivider(root, activity)

            val r1 = LinearLayout(activity)
            r1.orientation = LinearLayout.HORIZONTAL
            r1.gravity = Gravity.CENTER_VERTICAL
            val l1 = txt(activity, "显示顶部头像和昵称", 19f, false)
            l1.setTextColor(Color.rgb(55, 65, 58))
            l1.gravity = Gravity.CENTER_VERTICAL
            r1.addView(l1, LinearLayout.LayoutParams(0, dp(activity, 62), 1f))
            val sw = android.widget.Switch(activity)
            sw.isChecked = loadPanelBool(state, "show_profile", true)
            sw.setOnCheckedChangeListener { _, b ->
                state["showProfile"] = b
                (state["avatarView"] as? View)?.visibility = if (b) View.VISIBLE else View.GONE
                (state["nicknameView"] as? View)?.visibility = if (b) View.VISIBLE else View.GONE
                savePanelBool(state, activity, "show_profile", b)
            }
            r1.addView(sw, LinearLayout.LayoutParams(dp(activity, 52), dp(activity, 54)))
            root.addView(r1, LinearLayout.LayoutParams(-1, dp(activity, 62)))
            addSettingsDivider(root, activity)

            val city = txt(activity, "☁ 天气城市", 19f, false)
            city.setTextColor(Color.rgb(55, 65, 58))
            city.gravity = Gravity.CENTER_VERTICAL
            city.setOnClickListener { showWeatherCityDialog(state, activity) }
            root.addView(city, LinearLayout.LayoutParams(-1, dp(activity, 54)))
            addSettingsDivider(root, activity)

            val r2 = LinearLayout(activity)
            r2.orientation = LinearLayout.HORIZONTAL
            r2.gravity = Gravity.CENTER_VERTICAL
            val l2 = txt(activity, "显示钱包余额", 19f, false)
            l2.setTextColor(Color.rgb(55, 65, 58))
            l2.gravity = Gravity.CENTER_VERTICAL
            r2.addView(l2, LinearLayout.LayoutParams(0, dp(activity, 62), 1f))
            var hide = loadPanelBool(state, "hide_wallet_balance", false)
            val wt = makeVideoToggle(activity, !hide)
            wt.setOnClickListener {
                hide = !hide
                state["hideWalletBalance"] = hide
                renderVideoToggle(wt, activity, !hide)
                (state["walletBalance"] as? View)?.visibility = if (hide) View.GONE else View.VISIBLE
                savePanelBool(state, activity, "hide_wallet_balance", hide)
            }
            r2.addView(wt, LinearLayout.LayoutParams(dp(activity, 58), dp(activity, 32)))
            root.addView(r2, LinearLayout.LayoutParams(-1, dp(activity, 62)))

            val dlg = android.app.AlertDialog.Builder(activity).setTitle("负一屏设置").setView(root).setPositiveButton("关闭", null).create()
            dlg.show()
        } catch (e: Throwable) {
            logger("showPanelSettings 异常=" + e, null)
        }
    }

    private fun showPanelEditor(state: MutableMap<String, Any?>, activity: Activity) {
        try {
            val keys = arrayOf("time", "weather", "wallet", "actions", "quote")
            val names = arrayOf("时间 / 问候", "天气", "钱包", "快捷操作", "一言")
            val order = ArrayList<String>()
            val saved = loadPanelString(state, "card_order", "")
            if (saved.isNotEmpty()) {
                for (k in saved.split(",")) {
                    val t = k.trim()
                    if (t.isNotEmpty() && keys.indexOf(t) >= 0 && !order.contains(t)) order.add(t)
                }
            }
            for (k in keys) if (!order.contains(k)) order.add(k)

            val root = LinearLayout(activity)
            root.orientation = LinearLayout.VERTICAL
            root.setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 8))
            val tip = txt(activity, "调整卡片顺序与显示", 13f, false)
            tip.setTextColor(Color.rgb(110, 125, 112))
            root.addView(tip, LinearLayout.LayoutParams(-1, dp(activity, 38)))
            val list = LinearLayout(activity)
            list.orientation = LinearLayout.VERTICAL
            root.addView(list, LinearLayout.LayoutParams(-1, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))

            lateinit var rebuild: Runnable
            rebuild = Runnable {
                list.removeAllViews()
                for (i in order.indices) {
                    val key = order[i]
                    val row = LinearLayout(activity)
                    row.orientation = LinearLayout.HORIZONTAL
                    row.gravity = Gravity.CENTER_VERTICAL
                    val rbg = android.graphics.drawable.GradientDrawable()
                    rbg.setColor(Color.rgb(242, 248, 243))
                    rbg.setCornerRadius(dp(activity, 16).toFloat())
                    row.background = rbg
                    row.setPadding(dp(activity, 12), 0, dp(activity, 10), 0)
                    val nm = txt(activity, names[keys.indexOf(key)], 16f, false)
                    nm.setTextColor(Color.rgb(55, 65, 58))
                    nm.gravity = Gravity.CENTER_VERTICAL
                    row.addView(nm, LinearLayout.LayoutParams(0, dp(activity, 50), 1f))
                    val sw2 = android.widget.Switch(activity)
                    sw2.isChecked = loadPanelBool(state, "visible_" + key, true)
                    val kk = key
                    sw2.setOnCheckedChangeListener { _, b ->
                        state["visible_" + kk] = b
                        saveCardVisibilityFromState(state, activity)
                        val o = joinOrder(state, activity)
                        savePanelString(state, activity, "card_order", o)
                        applyCardLayout(state, activity)
                    }
                    row.addView(sw2, LinearLayout.LayoutParams(dp(activity, 52), dp(activity, 50)))
                    val up = txt(activity, "↑", 20f, false)
                    up.setTextColor(Color.rgb(49, 117, 68))
                    up.gravity = Gravity.CENTER
                    up.setOnClickListener {
                        val idx = indexOfKey(order, kk)
                        if (idx > 0) {
                            val t = order[idx]; order[idx] = order[idx - 1]; order[idx - 1] = t
                            val o = joinOrder(state, activity)
                            savePanelString(state, activity, "card_order", o)
                            applyCardLayout(state, activity)
                            rebuild.run()
                        }
                    }
                    row.addView(up, LinearLayout.LayoutParams(dp(activity, 38), dp(activity, 50)))
                    val dn = txt(activity, "↓", 20f, false)
                    dn.setTextColor(Color.rgb(49, 117, 68))
                    dn.gravity = Gravity.CENTER
                    dn.setOnClickListener {
                        val idx = indexOfKey(order, kk)
                        if (idx >= 0 && idx < order.size - 1) {
                            val t = order[idx]; order[idx] = order[idx + 1]; order[idx + 1] = t
                            val o = joinOrder(state, activity)
                            savePanelString(state, activity, "card_order", o)
                            applyCardLayout(state, activity)
                            rebuild.run()
                        }
                    }
                    row.addView(dn, LinearLayout.LayoutParams(dp(activity, 38), dp(activity, 50)))
                    val rlp = LinearLayout.LayoutParams(-1, dp(activity, 50))
                    rlp.setMargins(0, dp(activity, 4), 0, dp(activity, 4))
                    list.addView(row, rlp)
                }
            }
            rebuild.run()

            val dlg = android.app.AlertDialog.Builder(activity)
                .setTitle("编辑主页布局")
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create()
            dlg.setOnShowListener {
                dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    for (k in keys) state["visible_" + k] = loadPanelBool(state, "visible_" + k, true)
                    savePanelString(state, activity, "card_order", joinOrder(state, activity))
                    saveCardVisibilityFromState(state, activity)
                    applyCardLayout(state, activity)
                    dlg.dismiss()
                }
            }
            dlg.show()
        } catch (e: Throwable) {
            logger("showPanelEditor 异常=" + e, null)
        }
    }

    private fun showWeatherCityDialog(state: MutableMap<String, Any?>, activity: Activity) {
        try {
            val root = card(activity, Color.rgb(247, 252, 247), 24f)
            root.orientation = LinearLayout.VERTICAL
            root.setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 14))
            val title = txt(activity, "天气城市", 22f, true)
            title.setTextColor(Color.rgb(31, 91, 48))
            title.gravity = Gravity.CENTER_VERTICAL
            root.addView(title, LinearLayout.LayoutParams(-1, dp(activity, 32)))
            val tip = txt(activity, "输入城市名进行搜索，或跟随 IP 自动定位", 13f, false)
            tip.setTextColor(Color.rgb(110, 125, 112))
            tip.gravity = Gravity.CENTER_VERTICAL
            root.addView(tip, LinearLayout.LayoutParams(-1, dp(activity, 25)))

            val et = android.widget.EditText(activity)
            et.hint = "输入城市，例如：苏州"
            et.isSingleLine = true
            et.setTextSize(15f)
            et.setTextColor(Color.rgb(55, 65, 58))
            val ebg = android.graphics.drawable.GradientDrawable()
            ebg.setColor(Color.rgb(242, 248, 242))
            ebg.setCornerRadius(dp(activity, 16).toFloat())
            et.background = ebg
            et.setPadding(dp(activity, 14), 0, dp(activity, 14), 0)
            val elp = LinearLayout.LayoutParams(-1, dp(activity, 48))
            elp.setMargins(0, dp(activity, 10), 0, 0)
            root.addView(et, elp)

            val btn = TextView(activity)
            btn.text = "搜索城市"
            btn.gravity = Gravity.CENTER
            btn.setTextSize(15f)
            btn.setTextColor(Color.WHITE)
            val bbg = android.graphics.drawable.GradientDrawable()
            bbg.setColor(Color.rgb(55, 125, 69))
            bbg.setCornerRadius(dp(activity, 16).toFloat())
            btn.background = bbg
            val blp = LinearLayout.LayoutParams(-1, dp(activity, 44))
            blp.setMargins(0, dp(activity, 10), 0, 0)
            root.addView(btn, blp)

            val auto = TextView(activity)
            auto.text = "⌖ 跟随 IP 自动定位"
            auto.gravity = Gravity.CENTER
            auto.setTextSize(15f)
            auto.setTextColor(Color.rgb(31, 91, 48))
            val abg = android.graphics.drawable.GradientDrawable()
            abg.setColor(Color.rgb(224, 242, 226))
            abg.setCornerRadius(dp(activity, 16).toFloat())
            auto.background = abg
            val alp = LinearLayout.LayoutParams(-1, dp(activity, 44))
            alp.setMargins(0, dp(activity, 8), 0, 0)
            root.addView(auto, alp)

            val dlg = android.app.AlertDialog.Builder(activity)
                .setView(root)
                .setNegativeButton("取消", null)
                .create()

            fun doSearch() {
                val q = et.text.toString().trim()
                if (q.isEmpty()) {
                    et.error = "请输入城市名称"
                } else {
                    searchWeatherCities(state, activity, q, dlg)
                }
            }
            btn.setOnClickListener { doSearch() }
            et.setOnEditorActionListener { _, actionId, event ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
                    doSearch(); true
                } else false
            }
            auto.setOnClickListener {
                clearWeatherCity(state, activity)
                state["weatherCity"] = "⌖  正在定位…"
                (state["weatherCityView"] as? android.widget.TextView)?.text = "⌖  正在定位…"
                dlg.dismiss()
                loadRealWeatherAsync(state, activity)
            }
            dlg.setOnShowListener {
                dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.rgb(31, 91, 48))
            }
            dlg.show()
        } catch (e: Throwable) {
            logger("showWeatherCityDialog 异常=" + e, null)
        }
    }

    private fun showWeatherSearchResults(
        state: MutableMap<String, Any?>,
        activity: Activity,
        parent: android.app.AlertDialog,
        names: ArrayList<String>,
        coords: ArrayList<DoubleArray>
    ) {
        mainHandler.post {
            try {
                val holder = LinearLayout(activity)
                holder.orientation = LinearLayout.VERTICAL
                val cardV = card(activity, Color.rgb(247, 252, 247), 24f)
                cardV.orientation = LinearLayout.VERTICAL
                cardV.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 10))
                holder.addView(cardV, LinearLayout.LayoutParams(-1, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))

                val title = txt(activity, "选择城市", 21f, true)
                title.setTextColor(Color.rgb(31, 91, 48))
                title.gravity = Gravity.CENTER_VERTICAL
                cardV.addView(title, LinearLayout.LayoutParams(-1, dp(activity, 32)))
                val tip = txt(activity, names.size.toString() + " 个搜索结果", 13f, false)
                tip.setTextColor(Color.rgb(110, 125, 112))
                tip.gravity = Gravity.CENTER_VERTICAL
                cardV.addView(tip, LinearLayout.LayoutParams(-1, dp(activity, 24)))

                val sv = android.widget.ScrollView(activity)
                sv.isFillViewport = true
                val list = LinearLayout(activity)
                list.orientation = LinearLayout.VERTICAL
                sv.addView(list)
                cardV.addView(sv, LinearLayout.LayoutParams(-1, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))

                val resultDlg = android.app.AlertDialog.Builder(activity)
                    .setView(holder)
                    .setNegativeButton("返回", null)
                    .create()

                for (i in names.indices) {
                    val city = names[i]
                    val cd = coords[i]
                    val item = card(activity, Color.rgb(232, 246, 233), 18f)
                    item.orientation = LinearLayout.VERTICAL
                    item.setPadding(dp(activity, 15), dp(activity, 8), dp(activity, 15), dp(activity, 8))
                    val nv = txt(activity, city, 17f, false)
                    nv.setTextColor(Color.rgb(31, 91, 48))
                    nv.gravity = Gravity.CENTER_VERTICAL
                    item.addView(nv, LinearLayout.LayoutParams(-1, dp(activity, 27)))
                    val sub = txt(activity, String.format("%.4f, %.4f", cd[0], cd[1]), 12f, false)
                    sub.setTextColor(Color.rgb(100, 125, 104))
                    sub.gravity = Gravity.CENTER_VERTICAL
                    item.addView(sub, LinearLayout.LayoutParams(-1, dp(activity, 21)))
                    item.setOnClickListener {
                        saveWeatherCity(activity, city, cd[0], cd[1], true)
                        state["weatherCity"] = "⌖  " + city
                        (state["weatherCityView"] as? android.widget.TextView)?.text = "⌖  " + city
                        resultDlg.dismiss()
                        parent.dismiss()
                        loadRealWeatherAsync(state, activity)
                    }
                    val ilp = LinearLayout.LayoutParams(-1, dp(activity, 66))
                    ilp.setMargins(0, 0, 0, dp(activity, 8))
                    list.addView(item, ilp)
                }

                resultDlg.setOnShowListener {
                    resultDlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.rgb(31, 91, 48))
                }
                resultDlg.show()
            } catch (e: Throwable) {
                logger("showWeatherSearchResults 异常=" + e, null)
            }
        }
    }

    private fun addLocalChineseCityResults(query: String, names: ArrayList<String>, coords: ArrayList<DoubleArray>): Boolean {
        try {
            val q = query.trim()
            if (q.isEmpty()) return false
            val low = q.lowercase()
            val table = arrayOf(
                arrayOf("北京", "39.9042", "116.4074"),
                arrayOf("上海", "31.2304", "121.4737"),
                arrayOf("天津", "39.3434", "117.3616"),
                arrayOf("重庆", "29.5630", "106.5516"),
                arrayOf("广州", "23.1291", "113.2644"),
                arrayOf("深圳", "22.5431", "114.0579"),
                arrayOf("东莞", "23.0207", "113.7518"),
                arrayOf("佛山", "23.0215", "113.1214"),
                arrayOf("珠海", "22.2707", "113.5767"),
                arrayOf("中山", "22.5176", "113.3928"),
                arrayOf("惠州", "23.1115", "114.4162"),
                arrayOf("汕头", "23.3535", "116.6822"),
                arrayOf("南京", "32.0603", "118.7969"),
                arrayOf("苏州", "31.2989", "120.5853"),
                arrayOf("无锡", "31.4912", "120.3119"),
                arrayOf("常州", "31.8107", "119.9741"),
                arrayOf("徐州", "34.2058", "117.2841"),
                arrayOf("南通", "31.9802", "120.8943"),
                arrayOf("扬州", "32.3944", "119.4128"),
                arrayOf("杭州", "30.2741", "120.1551"),
                arrayOf("宁波", "29.8683", "121.5440"),
                arrayOf("温州", "27.9938", "120.6994"),
                arrayOf("嘉兴", "30.7522", "120.7500"),
                arrayOf("绍兴", "30.0303", "120.5802"),
                arrayOf("金华", "29.0784", "119.6474"),
                arrayOf("台州", "28.6564", "121.4207"),
                arrayOf("合肥", "31.8206", "117.2272"),
                arrayOf("芜湖", "31.3526", "118.4331"),
                arrayOf("武汉", "30.5928", "114.3055"),
                arrayOf("宜昌", "30.6919", "111.2865"),
                arrayOf("襄阳", "32.0090", "112.1220"),
                arrayOf("长沙", "28.2282", "112.9388"),
                arrayOf("株洲", "27.8274", "113.1340"),
                arrayOf("衡阳", "26.8934", "112.5719"),
                arrayOf("成都", "30.5728", "104.0668"),
                arrayOf("绵阳", "31.4675", "104.6796"),
                arrayOf("郑州", "34.7466", "113.6254"),
                arrayOf("洛阳", "34.6197", "112.4540"),
                arrayOf("济南", "36.6512", "117.1201"),
                arrayOf("青岛", "36.0671", "120.3826"),
                arrayOf("烟台", "37.4638", "121.4479"),
                arrayOf("潍坊", "36.7068", "119.1617"),
                arrayOf("福州", "26.0745", "119.2965"),
                arrayOf("厦门", "24.4798", "118.0894"),
                arrayOf("泉州", "24.8741", "118.6757"),
                arrayOf("南昌", "28.6820", "115.8579"),
                arrayOf("赣州", "25.8306", "114.9350"),
                arrayOf("西安", "34.3416", "108.9398"),
                arrayOf("宝鸡", "34.3610", "107.2371"),
                arrayOf("沈阳", "41.8057", "123.4315"),
                arrayOf("大连", "38.9140", "121.6147"),
                arrayOf("长春", "43.8171", "125.3235"),
                arrayOf("哈尔滨", "45.8038", "126.5350"),
                arrayOf("石家庄", "38.0428", "114.5149"),
                arrayOf("唐山", "39.6304", "118.1802"),
                arrayOf("太原", "37.8706", "112.5489"),
                arrayOf("昆明", "25.0453", "102.7097"),
                arrayOf("贵阳", "26.6470", "106.6302"),
                arrayOf("南宁", "22.8170", "108.3665"),
                arrayOf("海口", "20.0442", "110.1999"),
                arrayOf("三亚", "18.2528", "109.5119"),
                arrayOf("兰州", "36.0611", "103.8343"),
                arrayOf("西宁", "36.6171", "101.7782"),
                arrayOf("银川", "38.4872", "106.2309"),
                arrayOf("乌鲁木齐", "43.8256", "87.6168"),
                arrayOf("呼和浩特", "40.8414", "111.7519"),
                arrayOf("拉萨", "29.6520", "91.1721"),
                arrayOf("香港", "22.3193", "114.1694"),
                arrayOf("澳门", "22.1987", "113.5439"),
                arrayOf("台北", "25.0330", "121.5654")
            )
            for (row in table) {
                if (names.size >= 8) break
                val name = row[0]
                val py = normalizeCityName(name)
                val hit = name.indexOf(q) >= 0 || (q.isNotEmpty() && name.indexOf(q) >= 0) ||
                    py.indexOf(low) >= 0 || low.indexOf(py) >= 0 || name.indexOf(low) >= 0
                if (hit) {
                    val nm = displayCityName(name)
                    if (names.indexOf(nm) < 0) {
                        names.add(nm)
                        coords.add(doubleArrayOf(row[1].toDouble(), row[2].toDouble()))
                    }
                }
            }
            return names.size > 0
        } catch (e: Throwable) {
            logger("addLocalChineseCityResults 异常=" + e, null)
            return false
        }
    }

    private fun searchWeatherCities(state: Map<String, Any?>, activity: Activity, query: String, parent: android.app.AlertDialog) {
        try {
            (state["weatherCityView"] as? android.widget.TextView)?.text = "⌖  搜索中…"
            Thread {
                val names = ArrayList<String>()
                val coords = ArrayList<DoubleArray>()
                try {
                    val geo = android.location.Geocoder(activity, java.util.Locale.CHINA)
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        val latch = java.util.concurrent.CountDownLatch(1)
                        val ref = java.util.concurrent.atomic.AtomicReference<java.util.List<android.location.Address>?>(null)
                        geo.getFromLocationName(query, 12, object : android.location.Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<android.location.Address>) {
                                ref.set(addresses)
                                latch.countDown()
                            }

                            override fun onError(errorMessage: String?) {
                                latch.countDown()
                            }
                        })
                        latch.await(2200, java.util.concurrent.TimeUnit.MILLISECONDS)
                        val list = ref.get()
                        if (list != null) {
                            for (addr in list) {
                                if (names.size >= 8) break
                                val nm = addr.locality ?: addr.subAdminArea ?: addr.featureName ?: query
                                val nm2 = displayCityName(nm)
                                if (names.indexOf(nm2) < 0) {
                                    names.add(nm2)
                                    coords.add(doubleArrayOf(addr.latitude, addr.longitude))
                                }
                            }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val list = geo.getFromLocationName(query, 12)
                        if (list != null) {
                            for (addr in list) {
                                if (names.size >= 8) break
                                val nm = addr.locality ?: addr.subAdminArea ?: addr.featureName ?: query
                                val nm2 = displayCityName(nm)
                                if (names.indexOf(nm2) < 0) {
                                    names.add(nm2)
                                    coords.add(doubleArrayOf(addr.latitude, addr.longitude))
                                }
                            }
                        }
                    }
                } catch (ex: Throwable) {
                    logger("searchWeatherCities geocoder 异常=" + ex, null)
                }

                if (names.isEmpty()) {
                    addLocalChineseCityResults(query, names, coords)
                }

                if (names.isNotEmpty()) {
                    mainHandler.post { showWeatherSearchResults(state, activity, parent, names, coords) }
                    return@Thread
                }

                val lock = Object()
                var delivered = false
                var done = 0
                val fail = Runnable {
                    synchronized(lock) {
                        done++
                        if (!delivered && done >= 2) {
                            delivered = true
                            mainHandler.post {
                                try {
                                    android.app.AlertDialog.Builder(activity)
                                        .setTitle("找不到城市")
                                        .setMessage("没有找到\"" + query + "\"。\n\n请检查城市名称后重试。")
                                        .setPositiveButton("知道了", null)
                                        .show()
                                } catch (e: Throwable) {
                                    logger("searchWeatherCities fail 弹窗异常=" + e, null)
                                }
                            }
                        }
                    }
                }

                Thread {
                    try {
                        val url = "https://nominatim.openstreetmap.org/search?q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&format=jsonv2&limit=8&accept-language=zh-CN"
                        val txt = h.heiErDing.utils.HttpUtil.getText(url, 1200)
                        if (txt != null && txt.trim().startsWith("[")) {
                            val arr = org.json.JSONArray(txt)
                            val tn = ArrayList<String>()
                            val tc = ArrayList<DoubleArray>()
                            for (i in 0 until arr.length()) {
                                if (tn.size >= 8) break
                                val o = arr.optJSONObject(i) ?: continue
                                val dn = o.optString("display_name", "")
                                if (dn.isEmpty()) continue
                                val first = dn.split(",")[0].trim()
                                val nm2 = displayCityName(first)
                                val lat = o.optString("lat", "").toDoubleOrNull()
                                val lon = o.optString("lon", "").toDoubleOrNull()
                                if (lat != null && lon != null && tn.indexOf(nm2) < 0) {
                                    tn.add(nm2)
                                    tc.add(doubleArrayOf(lat, lon))
                                }
                            }
                            synchronized(lock) {
                                if (!delivered && tn.isNotEmpty()) {
                                    delivered = true
                                    mainHandler.post { showWeatherSearchResults(state, activity, parent, tn, tc) }
                                }
                            }
                            if (tn.isEmpty()) fail.run() else synchronized(lock) { if (!delivered) done++ }
                        } else {
                            fail.run()
                        }
                    } catch (e: Throwable) {
                        logger("searchWeatherCities nominatim 异常=" + e, null)
                        fail.run()
                    }
                }.start()

                Thread {
                    try {
                        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" + java.net.URLEncoder.encode(query, "UTF-8") + "&count=8&language=zh&format=json"
                        val txt = h.heiErDing.utils.HttpUtil.getText(url, 1500)
                        if (txt != null && txt.trim().startsWith("{")) {
                            val jo = org.json.JSONObject(txt)
                            val arr = jo.optJSONArray("results")
                            val tn = ArrayList<String>()
                            val tc = ArrayList<DoubleArray>()
                            if (arr != null) {
                                for (i in 0 until arr.length()) {
                                    if (tn.size >= 8) break
                                    val o = arr.optJSONObject(i) ?: continue
                                    val nm = o.optString("name", "")
                                    if (nm.isEmpty()) continue
                                    val nm2 = displayCityName(nm)
                                    val lat = o.optDouble("latitude", Double.NaN)
                                    val lon = o.optDouble("longitude", Double.NaN)
                                    if (!lat.isNaN() && !lon.isNaN() && tn.indexOf(nm2) < 0) {
                                        tn.add(nm2)
                                        tc.add(doubleArrayOf(lat, lon))
                                    }
                                }
                            }
                            synchronized(lock) {
                                if (!delivered && tn.isNotEmpty()) {
                                    delivered = true
                                    mainHandler.post { showWeatherSearchResults(state, activity, parent, tn, tc) }
                                }
                            }
                            if (tn.isEmpty()) fail.run() else synchronized(lock) { if (!delivered) done++ }
                        } else {
                            fail.run()
                        }
                    } catch (e: Throwable) {
                        logger("searchWeatherCities open-meteo 异常=" + e, null)
                        fail.run()
                    }
                }.start()
            }.start()
        } catch (e: Throwable) {
            logger("searchWeatherCities 异常=" + e, null)
        }
    }
}
