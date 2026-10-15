package com.jianyu.ds_pet.pet

import android.content.Context
import android.graphics.Rect
import android.view.WindowInsets
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject

data class DeskGeometry(val work: Rect, val panel: Rect, val density: Float) {

    fun toJson(): String = JSONObject()
        .put("hull", rect(work))
        .put("areas", JSONArray().put(rect(work)))
        .put("workAreas", JSONArray().put(rect(work)))
        .put("panels", JSONArray().put(rect(panel)))
        .put("primaryIndex", 0)
        .put("topInsetRatio", 0)
        .toString()

    companion object {
        fun of(ctx: Context): DeskGeometry {
            val metrics = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics
            val panel = metrics.bounds
            val ins = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            val work = Rect(
                panel.left + ins.left,
                panel.top + ins.top,
                panel.right - ins.right,
                panel.bottom - ins.bottom,
            )
            return DeskGeometry(work, Rect(panel), ctx.resources.displayMetrics.density)
        }

        fun rect(r: Rect): JSONObject =
            JSONObject().put("x", r.left).put("y", r.top).put("width", r.width()).put("height", r.height())
    }
}
