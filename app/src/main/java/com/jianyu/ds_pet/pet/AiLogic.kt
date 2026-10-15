package com.jianyu.ds_pet.pet

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.floor

internal const val DEEPSEEK_FULL_BALANCE = "20"
internal const val DEFAULT_AI_MODEL = "deepseek-chat"
internal const val BALANCE_URL = "https://api.deepseek.com/user/balance"
internal const val CHAT_URL = "https://api.deepseek.com/chat/completions"
internal const val WHISPER_USER_TEXT = "随便说一句日常碎碎念，一句就好，20 字以内。"

private val MODEL_RE = Regex("[A-Za-z0-9._-]+")
private val IMG_TAG = Regex("""\[图[:：]\s*([^\]\n]+?)\s*\]\s*$""")
private val SHANGHAI = ZoneId.of("Asia/Shanghai")

internal data class MemeEntry(val name: String, val desc: String)

internal data class AiConfig(
    val templateName: String,
    val whisperPrompt: String,
    val chatMemoryRounds: Int,
    val balancePeriodSec: Int,
    val whisperPeriodSec: Int,
    val whisperImageEnabled: Boolean,
    val chatImageEnabled: Boolean,
    val chatImageLimit: Int,
    val memes: List<MemeEntry>,
) {
    companion object {
        val DEFAULT = AiConfig(
            templateName = "main",
            whisperPrompt = "",
            chatMemoryRounds = 5,
            balancePeriodSec = 1800,
            whisperPeriodSec = 300,
            whisperImageEnabled = false,
            chatImageEnabled = false,
            chatImageLimit = 10,
            memes = emptyList(),
        )
    }
}

internal data class ChatTurn(val role: String, val content: String, val ts: Long)

internal data class Picked(val text: String, val image: String?)

/**
 * DeepSeek 峰谷（北京时间）：周末全天空闲，含调休上班的周末。
 * 法定节假日（手机里的中国节假日日历，以及已公布的放假调休）全天空闲。
 * 其余日子 9:00–12:00、14:00–18:00 为高峰。
 */
internal fun deepseekPricingTier(
    now: ZonedDateTime,
    holiday: (LocalDate) -> Boolean = ::officialChineseVacation,
): String {
    val zoned = now.withZoneSameInstant(SHANGHAI)
    if (zoned.dayOfWeek == DayOfWeek.SATURDAY || zoned.dayOfWeek == DayOfWeek.SUNDAY) return "idle"
    if (holiday(zoned.toLocalDate())) return "idle"
    val hour = zoned.hour
    return if ((hour in 9..11) || (hour in 14..17)) "peak" else "idle"
}

internal fun normalizeModel(raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return DEFAULT_AI_MODEL
    if (text.length > 64 || !MODEL_RE.matches(text)) return DEFAULT_AI_MODEL
    return text
}

internal fun petSystemPrompt(whisperPrompt: String, petName: String): String {
    val nameLine = "你的名字是“$petName”。"
    return if (whisperPrompt.isNotEmpty()) whisperPrompt + "\n" + nameLine else nameLine
}

internal fun userTextWithMeme(meme: MemeEntry): String =
    "随便说一句日常碎碎念，一句就好，20 字以内。\n" +
        "这次会配一张表情包一起显示，图的内容是：" +
        meme.name +
        "（" +
        meme.desc +
        "）。\n" +
        "请让这句话和这张图的情绪/场景自然契合，像是配合画面说出来的；不要描述画面本身。"

internal fun memeCatalog(pool: List<MemeEntry>): String =
    pool.joinToString("\n") { "- ${it.name}：${it.desc}" }

internal fun imageInstruction(pool: List<MemeEntry>): String =
    "\n\n[配图] 回复结尾可选附一张表情包给用户看，从下列清单里挑最贴合当前语境的：\n" +
        memeCatalog(pool) +
        "\n挑中就在回复最后另起一行写 [图:名称]（名称原样照抄）；没有合适的就完全不要写这个标记。"

internal fun limitPool(pool: List<MemeEntry>, limit: Int): List<MemeEntry> {
    if (limit <= 0) return pool
    return pool.take(limit)
}

internal fun pickMeme(pool: List<MemeEntry>, random: () -> Double): MemeEntry? {
    if (pool.isEmpty()) return null
    val idx = floor(random() * pool.size).toInt() % pool.size
    return pool[idx]
}

internal fun extractChatImage(text: String, pool: List<MemeEntry>): Picked {
    val match = IMG_TAG.find(text) ?: return Picked(text, null)
    val hit = pool.firstOrNull { it.name == match.groupValues[1].trim() }
    val body = text.substring(0, match.range.first).trim()
    if (hit == null || body.isEmpty()) return Picked(text, null)
    return Picked(body, hit.name)
}

internal fun sliceMemoryRounds(messages: List<ChatTurn>, rounds: Int): List<ChatTurn> {
    if (rounds <= 0) return emptyList()
    val keep = rounds * 2
    return if (messages.size <= keep) messages else messages.takeLast(keep)
}

internal fun parseAiConfig(json: String, memeFiles: Set<String>): AiConfig {
    val root = try {
        JsonText.parse(json) as? JsonValue.Obj
    } catch (_: RuntimeException) {
        null
    } ?: return AiConfig.DEFAULT
    val pets = root.arr("pets")
    val template = (pets?.items?.firstOrNull() as? JsonValue.Obj)?.str("name")?.takeIf { it.isNotBlank() }
        ?: AiConfig.DEFAULT.templateName
    val memes = mutableListOf<MemeEntry>()
    val memeObj = root.obj("memes")
    if (memeObj != null && memeFiles.isNotEmpty()) {
        for ((name, value) in memeObj.fields) {
            val desc = (value as? JsonValue.Str)?.text?.trim().orEmpty()
            if (name.isBlank() || desc.isEmpty() || name !in memeFiles) continue
            memes += MemeEntry(name, desc)
        }
    }
    val refresh = root.obj("eventsRefreshSec")
    return AiConfig(
        templateName = template,
        whisperPrompt = root.str("whisperPrompt").orEmpty(),
        chatMemoryRounds = roundsOf(root.num("chatMemoryRounds")),
        balancePeriodSec = positiveSec(refresh?.num("balance"), 1800),
        whisperPeriodSec = positiveSec(refresh?.num("whisper"), 300),
        whisperImageEnabled = root.bool("whisperImageEnabled") == true,
        chatImageEnabled = root.bool("chatImageEnabled") == true,
        chatImageLimit = limitOf(root.num("chatImageLimit")),
        memes = memes,
    )
}

internal fun balanceOkJson(
    currency: String,
    total: String,
    granted: String,
    toppedUp: String,
    tier: String,
    manual: Boolean,
): String {
    val safeTier = if (tier == "peak") "peak" else "idle"
    val manualPart = if (manual) ""","manual":true""" else ""
    return """{"ok":true,"provider":"deepseek-official","shape":"money","data":{"currency":${quoteJson(currency)},"total":${quoteJson(total)},"granted":${quoteJson(granted)},"toppedUp":${quoteJson(toppedUp)},"fullBalance":"$DEEPSEEK_FULL_BALANCE","tier":${quoteJson(safeTier)}}$manualPart}"""
}

internal fun balanceFailJson(message: String, manual: Boolean): String {
    val manualPart = if (manual) ""","manual":true""" else ""
    return """{"ok":false,"provider":"deepseek-official","reason":"fetch-error","message":${quoteJson(message)}$manualPart}"""
}

internal fun sayJson(text: String, image: String?): String =
    if (image.isNullOrEmpty()) {
        """{"text":${quoteJson(text)}}"""
    } else {
        """{"text":${quoteJson(text)},"image":${quoteJson(image)}}"""
    }

internal fun chatRequestJson(model: String, system: String, turns: List<Pair<String, String>>): String {
    val sb = StringBuilder()
    sb.append("{\"model\":").append(quoteJson(model))
    sb.append(",\"messages\":[{\"role\":\"system\",\"content\":").append(quoteJson(system)).append('}')
    for ((role, content) in turns) {
        val safeRole = if (role == "assistant") "assistant" else "user"
        sb.append(",{\"role\":\"").append(safeRole).append("\",\"content\":").append(quoteJson(content)).append('}')
    }
    sb.append("],\"temperature\":1,\"stream\":false}")
    return sb.toString()
}

internal fun parseBalanceBody(body: String, tier: String, manual: Boolean): String {
    val root = JsonText.parse(body) as? JsonValue.Obj
        ?: throw IllegalArgumentException("dsh-pet: deepseek balance 响应缺少 balance_infos")
    val infos = root.arr("balance_infos")
        ?: throw IllegalArgumentException("dsh-pet: deepseek balance 响应缺少 balance_infos")
    if (infos.items.isEmpty()) throw IllegalArgumentException("dsh-pet: deepseek balance 响应缺少 balance_infos")
    val first = infos.items[0] as? JsonValue.Obj
        ?: throw IllegalArgumentException("dsh-pet: deepseek balance 响应缺少 balance_infos")
    return balanceOkJson(
        currency = requiredString(first, "currency"),
        total = requiredString(first, "total_balance"),
        granted = requiredString(first, "granted_balance"),
        toppedUp = requiredString(first, "topped_up_balance"),
        tier = tier,
        manual = manual,
    )
}

internal fun parseChatContent(body: String): String {
    val root = JsonText.parse(body) as? JsonValue.Obj ?: throw IllegalArgumentException("模型未返回文本")
    val message = (root.arr("choices")?.items?.firstOrNull() as? JsonValue.Obj)?.obj("message")
    val content = message?.str("content")?.trim().orEmpty()
    if (content.isEmpty()) throw IllegalArgumentException("模型未返回文本")
    return content
}

internal fun httpErrorMessage(body: String, code: Int): String {
    val parsed = try {
        (JsonText.parse(body) as? JsonValue.Obj)?.obj("error")?.str("message")?.trim()
    } catch (_: RuntimeException) {
        null
    }
    return parsed?.takeIf { it.isNotEmpty() } ?: "HTTP $code"
}

internal fun publicError(error: Throwable, secret: String): String {
    var message = (error.message ?: error.javaClass.simpleName).take(300)
    if (secret.isNotEmpty()) message = message.replace(secret, "***")
    return message.replace(Regex("Bearer\\s+\\S+"), "Bearer ***")
}

/** `pic/` 之后的路径。表情包认 `memes/<名>.png` 和 `memes/main/<名>.png`。 */
internal fun picAssetPath(afterPic: String): String? {
    if (afterPic.startsWith("memes/")) {
        val segs = afterPic.removePrefix("memes/").split('/')
        val file = when (segs.size) {
            1 -> segs[0]
            2 -> if (segs[0] == "main") segs[1] else return null
            else -> return null
        }
        val name = PetRoutes.safe(file) ?: return null
        if (!name.endsWith(".png", ignoreCase = true)) return null
        return "pet/memes/$name"
    }
    val name = PetRoutes.safe(afterPic) ?: return null
    return "pet/pic/$name"
}

private fun requiredString(obj: JsonValue.Obj, key: String): String {
    val text = obj.str(key)
    if (text.isNullOrEmpty()) throw IllegalArgumentException("dsh-pet: 余额数据非法字段 $key")
    return text
}

private fun positiveSec(value: Double?, fallback: Int): Int {
    if (value == null || !value.isFinite() || value <= 0.0) return fallback
    return value.toInt().coerceAtLeast(1)
}

private fun roundsOf(value: Double?): Int {
    if (value == null || !value.isFinite() || value < 0.0) return 5
    return value.toInt().coerceAtMost(50)
}

private fun limitOf(value: Double?): Int {
    if (value == null || !value.isFinite() || value < 0.0) return 10
    return value.toInt()
}
