package com.jianyu.ds_pet.pet

import android.content.Context

internal fun loadAiConfig(ctx: Context): AiConfig {
    return try {
        val json = ctx.assets.open("pet/config.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val files = ctx.assets.list("pet/memes")
            ?.mapNotNull { name ->
                if (name.endsWith(".png", ignoreCase = true)) name.dropLast(4) else null
            }
            ?.toSet()
            ?: emptySet()
        parseAiConfig(json, files)
    } catch (_: Exception) {
        AiConfig.DEFAULT
    }
}
