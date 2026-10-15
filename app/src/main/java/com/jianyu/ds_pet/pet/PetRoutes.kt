package com.jianyu.ds_pet.pet

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

class PetRoutes(
    private val ctx: Context,
    private val settings: PetSettings,
    val state: PetStateStore,
) {
    fun handle(req: WebResourceRequest): WebResourceResponse? {
        val path = req.url.path ?: return null
        if (!path.startsWith(PREFIX)) return null
        val rest = path.removePrefix(PREFIX)
        val range = req.requestHeaders.entries.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value
        return when {
            rest == "config" -> json(configJson())
            rest == "state" -> json(state.snapshot())
            rest.startsWith("thumb/") -> thumb(rest.removePrefix("thumb/"), range)
            rest.startsWith("font/") -> safe(rest.removePrefix("font/"))?.let { asset("pet/fonts/$it", "font/ttf", null) } ?: notFound()
            rest.startsWith("pic/") -> safe(rest.removePrefix("pic/"))?.let { asset("pet/pic/$it", "image/png", null) } ?: notFound()
            rest in setOf("balance", "whisper", "chat", "reload", "anim", "broadcast") ->
                json("""{"ok":false,"reason":"unsupported"}""")
            else -> notFound()
        }
    }

    private fun configJson(): String {
        val base = JSONObject(ctx.assets.open("pet/config.json").bufferedReader().use { it.readText() })
        val template = base.getJSONArray("pets").getJSONObject(0)
        val pets = JSONArray()
        repeat(settings.petCount.coerceIn(1, 4)) { i ->
            pets.put(JSONObject(template.toString()).apply {
                put("id", if (i == 0) "main" else "main$i")
                put("name", template.optString("name", "main") + if (i == 0) "" else " ${i + 1}")
                put("size", settings.sizeDp)
                put("display", "desktop")
                put("balanceEnabled", false)
                put("whisperEnabled", false)
                put("workStatusEnabled", false)
                put("fixedEnabled", settings.fixed)
                put("position", JSONObject()
                    .put("corner", settings.corner)
                    .put("marginX", settings.marginXDp + i * settings.sizeDp / 3)
                    .put("marginY", settings.marginYDp))
            })
        }
        base.put("pets", pets)
        base.put("notificationsEnabled", false)
        base.getJSONObject("physics").put("petCollision", settings.petCollision)
        return JSONObject().put("main", base).toString()
    }

    private fun thumb(rest: String, range: String?): WebResourceResponse {
        val parts = rest.split('/')
        if (parts.size != 2 || parts[0] != "main") return notFound()
        val file = safe(parts[1]) ?: return notFound()
        if (!file.endsWith(".webm")) return notFound()
        return asset("pet/webm/$file", "video/webm", range)
    }

    private fun asset(path: String, mime: String, rangeHeader: String?): WebResourceResponse {
        val afd = try {
            ctx.assets.openFd(path)
        } catch (e: IOException) {
            return try {
                WebResourceResponse(mime, null, ctx.assets.open(path))
            } catch (e: IOException) {
                notFound()
            }
        }
        val total = afd.length
        if (total <= 0L) {
            return WebResourceResponse(mime, null, ClosingStream(afd.createInputStream(), afd, Long.MAX_VALUE))
        }
        val range = parseRange(rangeHeader, total)
        val start = range?.first ?: 0L
        val end = range?.last ?: (total - 1)
        val len = end - start + 1
        val stream = afd.createInputStream().also { it.skipFully(start) }
        val headers = mutableMapOf(
            "Accept-Ranges" to "bytes",
            "Content-Length" to len.toString(),
            "Cache-Control" to "max-age=86400",
        )
        if (range != null) headers["Content-Range"] = "bytes $start-$end/$total"
        val status = if (range != null) 206 else 200
        Log.i(TAG, "$status $path bytes $start-$end/$total")
        return WebResourceResponse(
            mime, null,
            status,
            if (range != null) "Partial Content" else "OK",
            headers, ClosingStream(stream, afd, len),
        )
    }

    private fun json(body: String) = WebResourceResponse(
        "application/json", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), body.byteInputStream(),
    )

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())

    companion object {
        private const val TAG = "PetSpike"
        const val ORIGIN = "https://appassets.androidplatform.net"
        const val PREFIX = "/dsh-pet-7340/"

        fun safe(name: String): String? =
            name.takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it && ".." !in it }

        fun parseRange(header: String?, total: Long): LongRange? {
            val m = Regex("""bytes=(\d*)-(\d*)""").matchEntire(header?.trim() ?: return null) ?: return null
            val (s, e) = m.destructured
            val start = if (s.isEmpty()) total - (e.toLongOrNull() ?: return null) else s.toLong()
            val end = if (s.isEmpty() || e.isEmpty()) total - 1 else minOf(e.toLong(), total - 1)
            return if (start in 0..end) start..end else null
        }
    }
}

private fun InputStream.skipFully(n: Long) {
    var left = n
    while (left > 0) {
        val skipped = skip(left)
        if (skipped <= 0) {
            if (read() < 0) return
            left--
        } else {
            left -= skipped
        }
    }
}

private class ClosingStream(
    private val inner: InputStream,
    private val closeable: AutoCloseable,
    private var left: Long,
) : InputStream() {
    override fun read(): Int = if (left <= 0) -1 else inner.read().also { if (it >= 0) left-- }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) left -= n
        return n
    }
    override fun close() {
        try {
            inner.close()
        } finally {
            closeable.close()
        }
    }
}
