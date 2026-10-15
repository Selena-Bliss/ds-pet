package com.jianyu.ds_pet.pet

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.Executor

class Phase4Test {
    @Test
    fun pricingTier_followsShanghaiPeakAndWeekend() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        assertEquals("peak", deepseekPricingTier(ZonedDateTime.of(2026, 10, 14, 10, 0, 0, 0, shanghai)))
        assertEquals("idle", deepseekPricingTier(ZonedDateTime.of(2026, 10, 14, 13, 0, 0, 0, shanghai)))
        assertEquals("peak", deepseekPricingTier(ZonedDateTime.of(2026, 10, 14, 15, 30, 0, 0, shanghai)))
        assertEquals("idle", deepseekPricingTier(ZonedDateTime.of(2026, 10, 14, 18, 0, 0, 0, shanghai)))
        assertEquals("idle", deepseekPricingTier(ZonedDateTime.of(2026, 10, 14, 8, 0, 0, 0, shanghai)))
        assertEquals("idle", deepseekPricingTier(ZonedDateTime.of(2026, 10, 17, 10, 0, 0, 0, shanghai)))
        assertEquals("idle", deepseekPricingTier(ZonedDateTime.of(2026, 10, 18, 15, 0, 0, 0, shanghai)))
        assertEquals(
            "peak",
            deepseekPricingTier(ZonedDateTime.of(2026, 10, 14, 2, 0, 0, 0, ZoneId.of("UTC"))),
        )
    }

    @Test
    fun pricingTier_vacationsAndCalendarHolidaysAreIdleAllDay() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        fun at(year: Int, month: Int, day: Int, hour: Int) =
            deepseekPricingTier(ZonedDateTime.of(year, month, day, hour, 0, 0, 0, shanghai))

        assertEquals("idle", at(2026, 1, 2, 10))
        assertEquals("idle", at(2026, 2, 20, 10))
        assertEquals("peak", at(2026, 2, 24, 10))
        assertEquals("idle", at(2026, 5, 4, 10))
        assertEquals("idle", at(2026, 10, 5, 15))
        assertEquals("peak", at(2026, 10, 8, 10))
        assertEquals("idle", at(2026, 9, 20, 10))
        assertEquals("idle", at(2026, 10, 10, 11))
        assertEquals(
            "idle",
            deepseekPricingTier(ZonedDateTime.of(2027, 2, 8, 10, 0, 0, 0, shanghai)) {
                it == LocalDate.of(2027, 2, 8)
            },
        )
        assertEquals(
            setOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 7)),
            datesCoveredBy(
                listOf(HolidaySpan("国庆节", LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 8))),
            ),
        )
        assertTrue(
            datesCoveredBy(listOf(HolidaySpan("国庆节补班", LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11)))).isEmpty(),
        )
        assertTrue(isChineseHolidayCalendar("中国节假日", "", "zh.china#holiday@group.v.calendar.google.com"))
        assertTrue(isChineseHolidayCalendar("Holidays in China", "Holidays in China", ""))
        assertFalse(isChineseHolidayCalendar("Holidays in the United States", "", "en.usa#holiday@group.v.calendar.google.com"))
        assertFalse(isChineseHolidayCalendar("我的日历", "personal", "local"))
    }

    @Test
    fun balanceJson_matchesUpstreamMoneyShape() {
        val ok = parseBalanceBody(
            """{"balance_infos":[{"currency":"CNY","total_balance":"8.79","granted_balance":"0.00","topped_up_balance":"8.79"}]}""",
            tier = "peak",
            manual = true,
        )
        assertEquals(
            """{"ok":true,"provider":"deepseek-official","shape":"money","data":{"currency":"CNY","total":"8.79","granted":"0.00","toppedUp":"8.79","fullBalance":"20","tier":"peak"},"manual":true}""",
            ok,
        )
        val parsed = JsonText.parse(ok) as JsonValue.Obj
        assertEquals(true, parsed.bool("ok"))
        assertEquals(true, parsed.bool("manual"))
        assertEquals("money", parsed.str("shape"))
        assertEquals("8.79", parsed.obj("data")?.str("total"))

        val failed = balanceFailJson("deepseek balance HTTP 401", manual = false)
        val fail = JsonText.parse(failed) as JsonValue.Obj
        assertEquals(false, fail.bool("ok"))
        assertEquals("fetch-error", fail.str("reason"))
        assertEquals("deepseek-official", fail.str("provider"))
        assertNull(fail.bool("manual"))
    }

    @Test
    fun prompts_matchUpstreamCopy() {
        val meme = MemeEntry("可爱", "标准正面卖萌立绘")
        assertEquals(
            "随便说一句日常碎碎念，一句就好，20 字以内。\n" +
                "这次会配一张表情包一起显示，图的内容是：可爱（标准正面卖萌立绘）。\n" +
                "请让这句话和这张图的情绪/场景自然契合，像是配合画面说出来的；不要描述画面本身。",
            userTextWithMeme(meme),
        )
        assertEquals("你的名字是“蓝毛小女仆”。", petSystemPrompt("", "蓝毛小女仆"))
        assertEquals("人设\n你的名字是“蓝毛小女仆”。", petSystemPrompt("人设", "蓝毛小女仆"))
        assertTrue(imageInstruction(listOf(meme)).contains("- 可爱：标准正面卖萌立绘"))
        assertTrue(imageInstruction(listOf(meme)).contains("[图:名称]"))
    }

    @Test
    fun chatImage_stripsOnlyAPooledTag() {
        val pool = listOf(MemeEntry("可爱", "卖萌"), MemeEntry("哦鲸鲸", "无语"))
        assertEquals(Picked("你好", null), extractChatImage("你好", pool))
        assertEquals(Picked("你好", "可爱"), extractChatImage("你好\n[图:可爱]", pool))
        assertEquals(Picked("你好", "哦鲸鲸"), extractChatImage("你好\n[图：哦鲸鲸]", pool))
        assertEquals(Picked("你好 [图:没有]", null), extractChatImage("你好 [图:没有]", pool))
        assertEquals(Picked("[图:可爱]", null), extractChatImage("[图:可爱]", pool))
        assertEquals(listOf(pool[0]), limitPool(pool, 1))
        assertEquals(pool, limitPool(pool, 0))
        assertEquals(pool, limitPool(pool, -1))
        assertEquals("可爱", pickMeme(pool) { 0.0 }?.name)
    }

    @Test
    fun memory_keepsWholeFileAndSendsOnlyRecentRounds() {
        val six = (1..3).flatMap { i ->
            listOf(ChatTurn("user", "q$i", i.toLong()), ChatTurn("assistant", "a$i", i.toLong()))
        }
        assertTrue(sliceMemoryRounds(six, 0).isEmpty())
        assertEquals(listOf("q3", "a3"), sliceMemoryRounds(six, 1).map { it.content })
        assertEquals(six, sliceMemoryRounds(six, 5))

        val dir = File("build/tmp/phase4-memory").apply { deleteRecursively(); mkdirs() }
        val memory = PetMemory(File(dir, "memory.json"), now = { 42L })
        var seen = emptyList<String>()
        memory.chat("main", 5) { history, commit ->
            assertTrue(history.isEmpty())
            commit("早", "在的")
        }
        memory.chat("main", 1) { history, commit ->
            seen = history.map { it.content }
            commit("再来", "好呀")
        }
        assertEquals(listOf("早", "在的"), seen)
        val stored = JsonText.parse(File(dir, "memory.json").readText()) as JsonValue.Obj
        val messages = stored.obj("main")?.obj("main")?.arr("messages")
        assertEquals(4, messages?.items?.size)
        assertEquals("好呀", (messages?.items?.last() as JsonValue.Obj).str("content"))
        assertFalse(File(dir, "memory.json").readText().contains("[配图]"))
    }

    @Test
    fun config_readsPeriodsPromptAndExistingMemes() {
        val cfg = parseAiConfig(
            """
            {
              "whisperPrompt": "人设",
              "chatMemoryRounds": 5,
              "whisperImageEnabled": true,
              "chatImageEnabled": true,
              "chatImageLimit": 1,
              "eventsRefreshSec": {"balance": 1800, "whisper": 300},
              "pets": [{"name": "蓝毛小女仆"}],
              "memes": {"可爱": "卖萌", "缺图": "没有文件", "哦鲸鲸": "无语"}
            }
            """.trimIndent(),
            setOf("可爱", "哦鲸鲸"),
        )
        assertEquals("蓝毛小女仆", cfg.templateName)
        assertEquals("人设", cfg.whisperPrompt)
        assertEquals(5, cfg.chatMemoryRounds)
        assertEquals(1800, cfg.balancePeriodSec)
        assertEquals(300, cfg.whisperPeriodSec)
        assertEquals(1, cfg.chatImageLimit)
        assertEquals(listOf("可爱", "哦鲸鲸"), cfg.memes.map { it.name })
        assertEquals(listOf("可爱"), limitPool(cfg.memes, cfg.chatImageLimit).map { it.name })
        assertEquals(DEFAULT_AI_MODEL, normalizeModel("  "))
        assertEquals(DEFAULT_AI_MODEL, normalizeModel("bad model"))
        assertEquals("deepseek-reasoner", normalizeModel("deepseek-reasoner"))
    }

    @Test
    fun picPaths_coverBothMemeForms() {
        assertEquals("pet/memes/可爱.png", picAssetPath("memes/可爱.png"))
        assertEquals("pet/memes/可爱.png", picAssetPath("memes/main/可爱.png"))
        assertNull(picAssetPath("memes/other/可爱.png"))
        assertNull(picAssetPath("memes/../a.png"))
        assertEquals("pet/pic/icon.png", picAssetPath("icon.png"))
        assertNull(picAssetPath("a/b.png"))
    }

    @Test
    fun servedPets_enableBalanceAndWhisperWithAKey() {
        val pets = servedPetConfigs("蓝毛小女仆", 2, 240, "bottom-right", 8, 48, false, aiEnabled = true)
        assertTrue(pets.all { it.balanceEnabled && it.whisperEnabled && !it.workStatusEnabled })
        assertEquals(listOf("main", "main1"), pets.map { it.id })
    }

    @Test
    fun ai_writesBalanceWhisperAndChatWithoutLeakingTheKey() {
        val sink = MemorySink()
        val transport = ScriptedTransport()
        val dir = File("build/tmp/phase4-ai").apply { deleteRecursively(); mkdirs() }
        val cfg = AiConfig(
            templateName = "蓝毛小女仆",
            whisperPrompt = "人设",
            chatMemoryRounds = 5,
            balancePeriodSec = 1800,
            whisperPeriodSec = 300,
            whisperImageEnabled = true,
            chatImageEnabled = true,
            chatImageLimit = 10,
            memes = listOf(MemeEntry("可爱", "卖萌")),
        )
        val ai = PetAi(
            key = { "sk-secret" },
            modelName = { "" },
            config = { cfg },
            petName = { id -> if (id == "main") "蓝毛小女仆" else null },
            transport = transport,
            memory = PetMemory(File(dir, "memory.json"), now = { 7L }),
            sink = sink,
            executor = Executor { it.run() },
            now = { ZonedDateTime.of(2026, 10, 14, 10, 0, 0, 0, ZoneId.of("Asia/Shanghai")) },
            random = { 0.0 },
        )
        transport.next = {
            DeepSeekHttp(
                200,
                """{"balance_infos":[{"currency":"CNY","total_balance":"8.79","granted_balance":"0.00","topped_up_balance":"8.79"}]}""",
            )
        }
        ai.refreshBalance(manual = true)
        assertEquals("GET", transport.calls.single().method)
        assertEquals(BALANCE_URL, transport.calls.single().url)
        assertEquals("sk-secret", transport.calls.single().bearer)
        assertTrue(sink.sections.getValue("balance").contains(""""manual":true"""))
        assertTrue(sink.sections.getValue("balance").contains(""""tier":"peak""""))
        assertFalse(sink.sections.getValue("balance").contains("sk-secret"))

        transport.calls.clear()
        transport.next = { throw IllegalStateException("boom sk-secret") }
        ai.refreshBalance(manual = false)
        assertTrue(sink.sections.getValue("balance").contains("boom ***"))
        assertFalse(sink.sections.getValue("balance").contains("sk-secret"))

        transport.next = { DeepSeekHttp(200, """{"choices":[{"message":{"content":"今天也好"}}]}""") }
        ai.whisper("main")
        val whisper = transport.calls.last()
        assertEquals(CHAT_URL, whisper.url)
        assertTrue(whisper.body!!.contains("deepseek-chat"))
        assertTrue(whisper.body.contains("你的名字是“蓝毛小女仆”。"))
        assertTrue(whisper.body.contains("可爱（卖萌）"))
        assertEquals("""{"text":"今天也好","image":"可爱"}""", sink.pets.getValue("main"))

        transport.next = { DeepSeekHttp(200, """{"choices":[{"message":{"content":"在呢\n[图:可爱]"}}]}""") }
        val outcome = ai.chat("main", "在吗")
        assertTrue(outcome.ok)
        assertEquals("""{"text":"在呢","image":"可爱"}""", sink.pets.getValue("main"))
        val stored = File(dir, "memory.json").readText()
        assertTrue(stored.contains("在吗"))
        assertTrue(stored.contains("在呢"))
        assertFalse(stored.contains("[图:"))
        assertFalse(stored.contains("[配图]"))
        assertFalse(stored.contains("sk-secret"))

        assertEquals("还没有填写 DeepSeek API Key", PetAi(
            key = { null },
            modelName = { "" },
            config = { cfg },
            petName = { "蓝毛小女仆" },
            transport = transport,
            memory = PetMemory(File(dir, "empty.json")),
            sink = sink,
            executor = Executor { it.run() },
        ).chat("main", "嗨").message)
    }

    @Test
    fun scheduler_runsOnScreenAndSkipsWhenIdleOrLocked() {
        val clock = ManualClock()
        var enabled = true
        val balances = mutableListOf<Long>()
        val whispers = mutableListOf<String>()
        val scheduler = PetAiScheduler(
            clock = clock,
            enabled = { enabled },
            balancePeriodMs = { 1_800_000L },
            whisperPeriodMs = { 300_000L },
            petIds = { listOf("main") },
            refreshBalance = { balances += clock.time },
            publishWhisper = { whispers += it },
        )
        scheduler.start(screenOn = false)
        clock.fireDue()
        assertTrue(balances.isEmpty())

        scheduler.setScreenOn(true)
        clock.fireDue()
        assertEquals(listOf(0L), balances)
        assertEquals(listOf("main"), whispers)

        clock.advance(1_000)
        scheduler.setScreenOn(false)
        assertTrue(clock.queue.isEmpty())
        scheduler.setScreenOn(true)
        assertEquals(clock.time + 299_000L, clock.queue.minOf { it.first })
        assertEquals(clock.time + 1_799_000L, clock.queue.maxOf { it.first })

        scheduler.kickBalance()
        clock.fireDue()
        assertEquals(listOf(0L, clock.time), balances)
        assertEquals(listOf("main"), whispers)

        enabled = false
        scheduler.kick()
        assertTrue(clock.queue.isEmpty())
        clock.fireDue()
        assertEquals(2, balances.size)
    }

    @Test
    fun transport_postsBearerAndDoesNotFollowRedirects() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var auth = ""
        var method = ""
        var body = ""
        server.createContext("/chat") { exchange ->
            method = exchange.requestMethod
            auth = exchange.requestHeaders.getFirst("Authorization") ?: ""
            body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val bytes = """{"choices":[{"message":{"content":"ok"}}]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/go") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1/elsewhere")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            val port = server.address.port
            val http = UrlConnectionTransport().exchange(
                DeepSeekCall("POST", "http://127.0.0.1:$port/chat", "sk-test", """{"a":1}""", 5_000, 0, 0),
            )
            assertEquals(200, http.code)
            assertEquals("Bearer sk-test", auth)
            assertEquals("POST", method)
            assertEquals("""{"a":1}""", body)
            assertTrue(http.body.contains("ok"))
            val redirected = UrlConnectionTransport().exchange(
                DeepSeekCall("GET", "http://127.0.0.1:$port/go", "sk-test", null, 5_000, 0, 0),
            )
            assertEquals(302, redirected.code)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun shippedConfig_parsesWhenAssetsArePresent() {
        val file = File("src/main/assets/pet/config.json")
        if (!file.isFile) return
        val names = File("src/main/assets/pet/memes").listFiles()
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            .orEmpty()
        val cfg = parseAiConfig(file.readText(Charsets.UTF_8), names)
        assertTrue(cfg.whisperPrompt.contains("女仆"))
        assertEquals(1800, cfg.balancePeriodSec)
        assertEquals(300, cfg.whisperPeriodSec)
        assertEquals(5, cfg.chatMemoryRounds)
        if (names.isNotEmpty()) assertTrue(cfg.memes.isNotEmpty())
    }
}

private class MemorySink : AiStateSink {
    val sections = linkedMapOf<String, String>()
    val pets = linkedMapOf<String, String>()
    override fun writeSection(name: String, json: String) {
        sections[name] = json
    }
    override fun writePet(petId: String, leaf: String, json: String) {
        pets[petId] = json
    }
}

private class ScriptedTransport : DeepSeekTransport {
    val calls = mutableListOf<DeepSeekCall>()
    var next: (DeepSeekCall) -> DeepSeekHttp = { error("no script") }
    override fun exchange(call: DeepSeekCall): DeepSeekHttp {
        calls += call
        return next(call)
    }
}

private class ManualClock : PetAiScheduler.Clock {
    var time = 0L
    val queue = mutableListOf<Pair<Long, Runnable>>()
    override fun now(): Long = time
    override fun post(delayMs: Long, task: Runnable) {
        queue += (time + delayMs) to task
    }
    override fun cancel(task: Runnable) {
        queue.removeAll { it.second === task }
    }
    fun fireDue() {
        val due = queue.filter { it.first <= time }.toList()
        queue.removeAll(due.toSet())
        due.forEach { it.second.run() }
    }
    fun advance(ms: Long) {
        time += ms
    }
}
