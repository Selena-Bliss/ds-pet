package com.jianyu.ds_pet.pet

import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.Executor

internal data class ChatOutcome(val ok: Boolean, val message: String = "")

internal interface AiStateSink {
    fun writeSection(name: String, json: String)
    fun writePet(petId: String, leaf: String, json: String)
}

internal class PetAi(
    private val key: () -> String?,
    private val modelName: () -> String,
    private val config: () -> AiConfig,
    private val petName: (String) -> String?,
    private val transport: DeepSeekTransport,
    private val memory: PetMemory,
    private val sink: AiStateSink,
    private val executor: Executor,
    private val onError: (String) -> Unit = {},
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val random: () -> Double = { Math.random() },
    private val isHoliday: (LocalDate) -> Boolean = ::officialChineseVacation,
) {
    fun refreshBalance(manual: Boolean) {
        val secret = key()?.trim().orEmpty()
        if (secret.isEmpty()) return
        val json = try {
            val http = transport.exchange(
                DeepSeekCall(
                    method = "GET",
                    url = BALANCE_URL,
                    bearer = secret,
                    body = null,
                    timeoutMs = 20_000,
                    retries = 3,
                    retryPauseMs = 800,
                ),
            )
            if (http.code !in 200..299) {
                balanceFailJson("deepseek balance HTTP ${http.code}", manual)
            } else {
                parseBalanceBody(http.body, deepseekPricingTier(now(), isHoliday), manual)
            }
        } catch (e: Exception) {
            balanceFailJson(publicError(e, secret), manual)
        }
        sink.writeSection("balance", json)
        if (json.contains("\"ok\":false")) onError("余额查询失败")
    }

    fun whisperLater(petId: String) {
        executor.execute { whisper(petId) }
    }

    fun whisper(petId: String) {
        val secret = key()?.trim().orEmpty()
        if (secret.isEmpty()) return
        val name = petName(petId) ?: return
        val cfg = config()
        val meme = if (cfg.whisperImageEnabled) pickMeme(cfg.memes, random) else null
        val user = if (meme != null) userTextWithMeme(meme) else WHISPER_USER_TEXT
        try {
            val text = complete(secret, petSystemPrompt(cfg.whisperPrompt, name), listOf("user" to user), 30_000)
            sink.writePet(petId, "say", sayJson(text, meme?.name))
        } catch (e: Exception) {
            onError("碎碎念没有生成 pet=$petId ${publicError(e, secret)}")
        }
    }

    fun chat(petId: String, text: String): ChatOutcome {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ChatOutcome(false, "消息为空")
        if (trimmed.length > 2000) return ChatOutcome(false, "消息过长（限 2000 字）")
        val secret = key()?.trim().orEmpty()
        if (secret.isEmpty()) return ChatOutcome(false, "还没有填写 DeepSeek API Key")
        val name = petName(petId) ?: return ChatOutcome(false, "找不到这只宠物")
        val cfg = config()
        val pool = if (cfg.chatImageEnabled) limitPool(cfg.memes, cfg.chatImageLimit) else emptyList()
        val system = petSystemPrompt(cfg.whisperPrompt, name)
        return try {
            memory.chat(petId, cfg.chatMemoryRounds) { history, commit ->
                val userContent = if (pool.isEmpty()) trimmed else trimmed + imageInstruction(pool)
                val turns = history.map { it.role to it.content } + ("user" to userContent)
                val raw = complete(secret, system, turns, 60_000)
                val picked = extractChatImage(raw, pool)
                commit(trimmed, picked.text)
                sink.writePet(petId, "say", sayJson(picked.text, picked.image))
                ChatOutcome(true)
            }
        } catch (e: Exception) {
            ChatOutcome(false, publicError(e, secret))
        }
    }

    private fun complete(secret: String, system: String, turns: List<Pair<String, String>>, timeoutMs: Int): String {
        val http = transport.exchange(
            DeepSeekCall(
                method = "POST",
                url = CHAT_URL,
                bearer = secret,
                body = chatRequestJson(normalizeModel(modelName()), system, turns),
                timeoutMs = timeoutMs,
                retries = 0,
                retryPauseMs = 0,
            ),
        )
        if (http.code !in 200..299) throw IllegalStateException(httpErrorMessage(http.body, http.code))
        return parseChatContent(http.body)
    }
}
