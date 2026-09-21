package h.heiErDing.hooks.items.game

import de.robv.android.xposed.XC_MethodHook
import h.heiErDing.event.Events
import h.heiErDing.hooks.core.BaseFeature
import h.heiErDing.hooks.core.DexInstallScheduler
import h.heiErDing.hooks.core.FeatureContext
import h.heiErDing.hooks.core.HookRegistry
import h.heiErDing.utils.KavaReflector
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method

class GameEmojiSequenceFeature : BaseFeature() {

    override fun featureId(): String = ID

    override fun name(): String = "预设骰子猜拳顺序"

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = GameEmojiSequenceRuntime(context) { message, throwable ->
            logError(message, throwable)
        }
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.uninstall()
        runtime = null
    }

    override fun onConfigChanged(context: FeatureContext, key: String?) {
        runtime?.refresh()
        scheduleInstall()
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name(), stage = DexInstallScheduler.Stage.WARMUP) {
            runtime?.install() == true
        }
    }

    private var runtime: GameEmojiSequenceRuntime? = null

    companion object {
        const val ID = "game_emoji_sequence"
    }
}

private class GameEmojiSequenceRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {

    private val featureId = GameEmojiSequenceFeature.ID
    private val configStore = context.configStore()
    private val dexKit = h.heiErDing.dexkit.DexKit.create(
        context.dexKitBridge(),
        context.dexBridgeHolder(),
        context.hostClassLoader()
    )

    @Volatile private var installed = false
    private var hookHandle: XC_MethodHook.Unhook? = null
    private var emojiFields: Map<String, Field> = emptyMap()

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        if (isAllExhausted()) return false
        val method = findEmojiSendMethod() ?: run {
            logger("未定位到游戏表情发送方法", null)
            return false
        }
        val fields = resolveEmojiFields(method.parameterTypes[1]) ?: run {
            logger("未解析到 EmojiInfo 字段", null)
            return false
        }
        emojiFields = fields
        return runCatching {
            hookHandle = HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handleEmojiSend(param)
                }
            })
            installed = true
            logger("已安装序列控制 Hook", null)
            true
        }.getOrElse {
            installed = false
            emojiFields = emptyMap()
            logger("序列控制 Hook 安装失败", it)
            false
        }
    }

    @Synchronized
    fun uninstall() {
        hookHandle?.let { HookRegistry.get().unhook(it) }
        hookHandle = null
        installed = false
    }

    fun refresh() {
        if (installed) return
        if (isAllExhausted()) return
        install()
    }

    private fun removeHookIfAllExhausted() {
        if (isAllExhausted()) {
            uninstall()
            logger("骰子与猜拳序列均已用尽，已自动解除 Hook", null)
        }
    }

    private fun handleEmojiSend(param: XC_MethodHook.MethodHookParam) {
        val emojiInfo = param.args?.getOrNull(1) ?: return
        when (detectGameType(emojiInfo)) {
            GAME_DICE -> handleDice(emojiInfo)
            GAME_RPS -> handleRps(emojiInfo)
            else -> Unit
        }
    }

    private fun detectGameType(emojiInfo: Any): Int {
        val name = readString(emojiInfo, FIELD_NAME)
        val content = readString(emojiInfo, FIELD_CONTENT)
        val md5 = readString(emojiInfo, FIELD_MD5)
        return when {
            name.startsWith("dice", ignoreCase = true) -> GAME_DICE
            content.contains("type=\"2\"") -> GAME_DICE
            DICE_MD5.any { it.equals(md5, ignoreCase = true) } -> GAME_DICE
            name.startsWith("jsb", ignoreCase = true) -> GAME_RPS
            content.contains("type=\"1\"") -> GAME_RPS
            RPS_MD5.any { it.equals(md5, ignoreCase = true) } -> GAME_RPS
            else -> GAME_NONE
        }
    }

    private fun handleDice(emojiInfo: Any) {
        if (configStore.getBoolean(featureId, KEY_DICE_EXHAUSTED, false)) return
        var sequence = normalizeSequence(
            configStore.getString(featureId, KEY_DICE_SEQUENCE, DEFAULT_DICE_SEQUENCE),
            1,
            6
        )
        if (sequence == null) {
            sequence = DEFAULT_DICE_SEQUENCE
            configStore.putString(featureId, KEY_DICE_SEQUENCE, sequence)
            configStore.putInt(featureId, KEY_DICE_INDEX, 0)
            configStore.putBoolean(featureId, KEY_DICE_EXHAUSTED, false)
        }
        var index = configStore.getInt(featureId, KEY_DICE_INDEX, 0)
        if (index < 0 || index >= sequence.length) index = 0
        val value = sequence[index] - '0'
        if (!applyDiceResult(emojiInfo, value)) return
        advance(featureId, KEY_DICE_INDEX, KEY_DICE_EXHAUSTED, index, sequence.length)
        logger("骰子结果置为 $value（第 ${index + 1}/${sequence.length} 次）", null)
        removeHookIfAllExhausted()
    }

    private fun handleRps(emojiInfo: Any) {
        if (configStore.getBoolean(featureId, KEY_RPS_EXHAUSTED, false)) return
        var sequence = normalizeSequence(
            configStore.getString(featureId, KEY_RPS_SEQUENCE, DEFAULT_RPS_SEQUENCE),
            1,
            3
        )
        if (sequence == null) {
            sequence = DEFAULT_RPS_SEQUENCE
            configStore.putString(featureId, KEY_RPS_SEQUENCE, sequence)
            configStore.putInt(featureId, KEY_RPS_INDEX, 0)
            configStore.putBoolean(featureId, KEY_RPS_EXHAUSTED, false)
        }
        var index = configStore.getInt(featureId, KEY_RPS_INDEX, 0)
        if (index < 0 || index >= sequence.length) index = 0
        val value = sequence[index] - '0'
        if (!applyRpsResult(emojiInfo, value)) return
        advance(featureId, KEY_RPS_INDEX, KEY_RPS_EXHAUSTED, index, sequence.length)
        logger("猜拳结果置为 $value（第 ${index + 1}/${sequence.length} 次）", null)
        removeHookIfAllExhausted()
    }

    private fun advance(
        featureId: String,
        indexKey: String,
        exhaustedKey: String,
        index: Int,
        length: Int
    ) {
        val next = index + 1
        if (next >= length) {
            configStore.putInt(featureId, indexKey, length)
            configStore.putBoolean(featureId, exhaustedKey, true)
        } else {
            configStore.putInt(featureId, indexKey, next)
        }
    }

    private fun applyDiceResult(emojiInfo: Any, value: Int): Boolean {
        val idx = (value - 1).coerceIn(0, DICE_MD5.size - 1)
        val content = "<gameext type=\"$TYPE_DICE\" content=\"${value + DICE_CONTENT_OFFSET}\" ></gameext>"
        return writeEmojiFields(
            emojiInfo,
            DICE_MD5[idx],
            DICE_SIZE[idx],
            DICE_FILE_NAMES[idx],
            content
        )
    }

    private fun applyRpsResult(emojiInfo: Any, value: Int): Boolean {
        val idx = (value - 1).coerceIn(0, RPS_MD5.size - 1)
        val content = "<gameext type=\"$TYPE_RPS\" content=\"$value\" ></gameext>"
        return writeEmojiFields(
            emojiInfo,
            RPS_MD5[idx],
            RPS_SIZE[idx],
            RPS_FILE_NAMES[idx],
            content
        )
    }

    private fun writeEmojiFields(
        emojiInfo: Any,
        md5: String,
        size: Long,
        fileName: String,
        content: String
    ): Boolean {
        val fields = emojiFields
        if (fields.isEmpty()) return false
        writeField(fields, FIELD_MD5, emojiInfo, md5)
        writeField(fields, FIELD_SIZE, emojiInfo, size)
        writeField(fields, FIELD_NAME, emojiInfo, fileName)
        writeField(fields, FIELD_CONTENT, emojiInfo, content)
        writeField(fields, FIELD_SVRID, emojiInfo, "")
        writeField(fields, FIELD_CATALOG, emojiInfo, 50)
        writeField(fields, FIELD_RESERVED3, emojiInfo, 0)
        writeField(fields, FIELD_RESERVED4, emojiInfo, 0)
        writeField(fields, FIELD_GROUPID, emojiInfo, "50")
        writeField(fields, FIELD_SOURCE, emojiInfo, 0)
        writeField(fields, FIELD_DESIGNERID, emojiInfo, null)
        writeField(fields, FIELD_THUMBURL, emojiInfo, null)
        return true
    }

    private fun writeField(fields: Map<String, Field>, name: String, receiver: Any, value: Any?) {
        val field = fields[name] ?: return
        runCatching { KavaReflector.writeField(field, receiver, value) }
    }

    private fun readString(emojiInfo: Any, fieldName: String): String? {
        return runCatching {
            val value = KavaReflector.readField(emojiInfo, fieldName) ?: return null
            value.toString()
        }.getOrNull()
    }

    private fun resolveEmojiFields(emojiInfoClass: Class<*>): Map<String, Field>? {
        val resolved = LinkedHashMap<String, Field>()
        for (name in REQUIRED_FIELDS) {
            val field = KavaReflector.findFieldRecursive(emojiInfoClass, name)
            if (field != null) {
                KavaReflector.accessible(field)
                resolved[name] = field
            }
        }
        return if (resolved.isEmpty()) null else resolved
    }

    private fun isEmojiSendMethod(method: Member): Boolean {
        if (method !is Method) return false
        if (method.returnType != Void.TYPE) return false
        val params = method.parameterTypes
        if (params.size < 4) return false
        if (params[0] != String::class.java) return false
        return params[1].name == EMOJI_INFO_CLASS
    }

    private fun findEmojiSendMethod(): Method? {
        val members = dexKit.findMemberList(listOf(STR_NET_SCENE_UPLOAD_EMOJI, STR_MSG_ID))
        for (member in members) {
            if (isEmojiSendMethod(member)) return member as Method
        }
        if (members.isNotEmpty()) {
            for (member in members) {
                if (member is Method && member.returnType == Void.TYPE) {
                    val params = member.parameterTypes
                    if (params.size >= 2 && params[1].simpleName.contains("Emoji")) {
                        return member
                    }
                }
            }
        }
        return null
    }

    private fun normalizeSequence(raw: String, min: Int, max: Int): String? {
        if (raw.isEmpty()) return null
        for (c in raw) {
            if (c !in '0'..'9') return null
            val d = c - '0'
            if (d < min || d > max) return null
        }
        return raw
    }

    private fun isAllExhausted(): Boolean {
        val dice = configStore.getBoolean(featureId, KEY_DICE_EXHAUSTED, false)
        val rps = configStore.getBoolean(featureId, KEY_RPS_EXHAUSTED, false)
        return dice && rps
    }

    companion object {
        private const val GAME_NONE = 0
        private const val GAME_DICE = 1
        private const val GAME_RPS = 2

        private const val TYPE = "gameext"
        private const val TYPE_DICE = "2"
        private const val TYPE_RPS = "1"
        private const val DICE_CONTENT_OFFSET = 3

        private const val EMOJI_INFO_CLASS = "com.tencent.mm.storage.emotion.EmojiInfo"
        private const val STR_NET_SCENE_UPLOAD_EMOJI = "NetSceneUploadEmoji"
        private const val STR_MSG_ID = "msgId"

        private const val KEY_DICE_SEQUENCE = "dice_sequence"
        private const val KEY_DICE_INDEX = "dice_index"
        private const val KEY_DICE_EXHAUSTED = "dice_exhausted"
        private const val KEY_RPS_SEQUENCE = "rps_sequence"
        private const val KEY_RPS_INDEX = "rps_index"
        private const val KEY_RPS_EXHAUSTED = "rps_exhausted"

        private const val DEFAULT_DICE_SEQUENCE = "123456"
        private const val DEFAULT_RPS_SEQUENCE = "123"

        private const val FIELD_MD5 = "field_md5"
        private const val FIELD_SIZE = "field_size"
        private const val FIELD_NAME = "field_name"
        private const val FIELD_CONTENT = "field_content"
        private const val FIELD_SVRID = "field_svrid"
        private const val FIELD_CATALOG = "field_catalog"
        private const val FIELD_RESERVED3 = "field_reserved3"
        private const val FIELD_RESERVED4 = "field_reserved4"
        private const val FIELD_GROUPID = "field_groupId"
        private const val FIELD_SOURCE = "field_source"
        private const val FIELD_DESIGNERID = "field_designerID"
        private const val FIELD_THUMBURL = "field_thumbUrl"

        private val REQUIRED_FIELDS = listOf(
            FIELD_MD5,
            FIELD_SIZE,
            FIELD_NAME,
            FIELD_CONTENT,
            FIELD_SVRID,
            FIELD_CATALOG,
            FIELD_RESERVED3,
            FIELD_RESERVED4,
            FIELD_GROUPID,
            FIELD_SOURCE,
            FIELD_DESIGNERID,
            FIELD_THUMBURL
        )

        private val DICE_MD5 = listOf(
            "da1c289d4e363f3ce1ff36538903b92f",
            "9e3f303561566dc9342a3ea41e6552a6",
            "dbcc51db2765c1d0106290bae6326fc4",
            "9a21c57defc4974ab5b7c842e3232671",
            "3a8e16d650f7e66ba5516b2780512830",
            "5ba8e9694b853df10b9f2a77b312cc09"
        )

        private val DICE_SIZE = listOf(2342L, 2278L, 2404L, 2422L, 2538L, 2536L)

        private val DICE_FILE_NAMES = listOf(
            "dice_1.png",
            "dice_2.png",
            "dice_3.png",
            "dice_4.png",
            "dice_5.png",
            "dice_6.png"
        )

        private val RPS_MD5 = listOf(
            "514914788fc461e7205bf0b6ba496c49",
            "f790e342a02e0f99d34b316547f9aeab",
            "091577322c40c05aa3dd701da29d6423"
        )

        private val RPS_SIZE = listOf(2782L, 2278L, 3612L)

        private val RPS_FILE_NAMES = listOf(
            "jsb_j.png",
            "jsb_s.png",
            "jsb_b.png"
        )
    }
}
