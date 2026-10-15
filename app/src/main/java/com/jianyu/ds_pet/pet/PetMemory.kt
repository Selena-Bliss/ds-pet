package com.jianyu.ds_pet.pet

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class PetMemory(
    private val file: File,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()

    fun <T> chat(
        petId: String,
        rounds: Int,
        block: (history: List<ChatTurn>, commit: (user: String, assistant: String) -> Unit) -> T,
    ): T = synchronized(lock) {
        val root = read()
        val history = sliceMemoryRounds(messagesOf(root, petId), rounds)
        block(history) { user, assistant ->
            append(root, petId, user, assistant, now())
            write(root)
        }
    }

    private fun read(): JsonValue.Obj {
        if (!file.exists() || file.length() == 0L) return JsonValue.Obj(LinkedHashMap())
        val parsed = try {
            JsonText.parse(file.readText(Charsets.UTF_8))
        } catch (_: RuntimeException) {
            throw IllegalStateException("memory.json 无法读取")
        }
        return parsed as? JsonValue.Obj ?: throw IllegalStateException("memory.json 不是对象")
    }

    private fun write(root: JsonValue.Obj) {
        val dir = file.parentFile ?: throw IllegalStateException("memory dir")
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(JsonText.stringify(root), Charsets.UTF_8)
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun messagesOf(root: JsonValue.Obj, petId: String): List<ChatTurn> {
        val bucket = root.fields["main"] as? JsonValue.Obj ?: return emptyList()
        val pet = bucket.fields[petId] as? JsonValue.Obj ?: return emptyList()
        val items = (pet.fields["messages"] as? JsonValue.Arr)?.items ?: return emptyList()
        return items.mapNotNull { item ->
            val obj = item as? JsonValue.Obj ?: return@mapNotNull null
            val role = obj.str("role") ?: return@mapNotNull null
            if (role != "user" && role != "assistant") return@mapNotNull null
            val content = obj.str("content") ?: return@mapNotNull null
            ChatTurn(role, content, obj.num("ts")?.toLong() ?: 0L)
        }
    }

    private fun append(root: JsonValue.Obj, petId: String, user: String, assistant: String, ts: Long) {
        val bucket = root.fields["main"] as? JsonValue.Obj ?: JsonValue.Obj(LinkedHashMap()).also {
            root.fields["main"] = it
        }
        val pet = bucket.fields[petId] as? JsonValue.Obj ?: JsonValue.Obj(LinkedHashMap()).also {
            bucket.fields[petId] = it
        }
        val existing = (pet.fields["messages"] as? JsonValue.Arr)?.items.orEmpty()
        pet.fields["messages"] = JsonValue.Arr(
            existing + turn("user", user, ts) + turn("assistant", assistant, ts),
        )
    }

    private fun turn(role: String, content: String, ts: Long): JsonValue.Obj = JsonValue.Obj(
        linkedMapOf(
            "role" to JsonValue.Str(role),
            "content" to JsonValue.Str(content),
            "ts" to JsonValue.Num(ts.toString()),
        ),
    )
}
