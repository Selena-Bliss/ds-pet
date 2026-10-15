package com.jianyu.ds_pet.pet

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.WindowInsets
import android.view.WindowManager
import org.json.JSONObject

data class DeskGeometry(val work: Rect, val panel: Rect, val density: Float) {

    fun toJson(): String = geometryJson(
        workLeft = work.left,
        workTop = work.top,
        workWidth = work.right - work.left,
        workHeight = work.bottom - work.top,
        panelLeft = panel.left,
        panelTop = panel.top,
        panelWidth = panel.right - panel.left,
        panelHeight = panel.bottom - panel.top,
        density = density.toDouble(),
    )

    companion object {
        fun of(ctx: Context): DeskGeometry {
            val metrics = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics
            val panel = Rect(metrics.bounds)
            val ins = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            // 悬浮窗盖住整块屏幕，不跟某个分屏任务走。旋转、折叠后挖孔会换边，工作区跟着内边距收。
            val work = insetWorkRect(panel, ins.left, ins.top, ins.right, ins.bottom)
            return DeskGeometry(work, panel, ctx.resources.displayMetrics.density)
        }

        fun rect(r: Rect): JSONObject = JSONObject(boxJson(r))
    }
}

private fun boxJson(r: Rect): String =
    boxJson(r.left, r.top, r.right - r.left, r.bottom - r.top)

private fun boxJson(x: Int, y: Int, width: Int, height: Int): String =
    "{\"x\":$x,\"y\":$y,\"width\":$width,\"height\":$height}"

internal fun geometryJson(
    workLeft: Int,
    workTop: Int,
    workWidth: Int,
    workHeight: Int,
    panelLeft: Int,
    panelTop: Int,
    panelWidth: Int,
    panelHeight: Int,
    density: Double,
): String {
    val hull = boxJson(workLeft, workTop, workWidth, workHeight)
    val panelBox = boxJson(panelLeft, panelTop, panelWidth, panelHeight)
    return "{\"hull\":$hull,\"areas\":[$hull],\"workAreas\":[$hull],\"panels\":[$panelBox],\"primaryIndex\":0,\"topInsetRatio\":0,\"scale\":$density}"
}

/**
 * 面板宽高要和当前方向一致。窗口上下文在旋转后可能还报着上一帧的尺寸，
 * 拿那份去摆宠物会把竖屏的 Y 用到横屏上，人就掉到屏幕外面。
 */
internal fun boundsMatchOrientation(width: Int, height: Int, orientation: Int): Boolean {
    if (width <= 0 || height <= 0) return false
    return when (orientation) {
        Configuration.ORIENTATION_LANDSCAPE -> width >= height
        Configuration.ORIENTATION_PORTRAIT -> height >= width
        else -> true
    }
}

/** 工作区左上角与宽高。内边距把区域挤没时退回整块面板。 */
internal data class WorkBox(val x: Int, val y: Int, val width: Int, val height: Int)

internal fun insetWorkBox(
    panelX: Int,
    panelY: Int,
    panelWidth: Int,
    panelHeight: Int,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
): WorkBox {
    val x = panelX + left.coerceAtLeast(0)
    val y = panelY + top.coerceAtLeast(0)
    val width = panelWidth - left.coerceAtLeast(0) - right.coerceAtLeast(0)
    val height = panelHeight - top.coerceAtLeast(0) - bottom.coerceAtLeast(0)
    if (width <= 0 || height <= 0) return WorkBox(panelX, panelY, panelWidth, panelHeight)
    return WorkBox(x, y, width, height)
}

/** 从整屏面板扣掉系统栏和挖孔。内边距把工作区挤没时退回整屏，避免宽高为负。 */
internal fun insetWorkRect(panel: Rect, left: Int, top: Int, right: Int, bottom: Int): Rect {
    val box = insetWorkBox(
        panel.left,
        panel.top,
        panel.right - panel.left,
        panel.bottom - panel.top,
        left,
        top,
        right,
        bottom,
    )
    return Rect(box.x, box.y, box.x + box.width, box.y + box.height)
}
