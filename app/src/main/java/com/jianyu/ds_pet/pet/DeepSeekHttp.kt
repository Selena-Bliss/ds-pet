package com.jianyu.ds_pet.pet

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class DeepSeekCall(
    val method: String,
    val url: String,
    val bearer: String,
    val body: String?,
    val timeoutMs: Int,
    val retries: Int,
    val retryPauseMs: Long,
)

internal data class DeepSeekHttp(val code: Int, val body: String)

internal interface DeepSeekTransport {
    fun exchange(call: DeepSeekCall): DeepSeekHttp
}

internal class UrlConnectionTransport : DeepSeekTransport {
    override fun exchange(call: DeepSeekCall): DeepSeekHttp {
        var last: Exception? = null
        val attempts = call.retries.coerceAtLeast(0) + 1
        repeat(attempts) { index ->
            try {
                return once(call)
            } catch (e: IOException) {
                last = e
                if (index < attempts - 1 && call.retryPauseMs > 0) {
                    try {
                        Thread.sleep(call.retryPauseMs)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw interrupted
                    }
                }
            }
        }
        throw last ?: IOException("fetch-error")
    }

    private fun once(call: DeepSeekCall): DeepSeekHttp {
        val conn = (URL(call.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = call.method
            connectTimeout = call.timeoutMs
            readTimeout = call.timeoutMs
            setRequestProperty("Authorization", "Bearer ${call.bearer}")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "dsh-pet-android")
        }
        try {
            if (call.body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(call.body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val buf = CharArray(4096)
                val sb = StringBuilder()
                while (sb.length < MAX_BODY) {
                    val n = reader.read(buf, 0, minOf(buf.size, MAX_BODY - sb.length))
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }.orEmpty()
            return DeepSeekHttp(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val MAX_BODY = 1_000_000
    }
}
