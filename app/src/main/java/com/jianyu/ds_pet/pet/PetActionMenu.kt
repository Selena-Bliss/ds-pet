package com.jianyu.ds_pet.pet

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.window.OnBackInvokedDispatcher
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 悬浮窗上的系统 PopupMenu 会按窗口原点排，窗口探出屏幕时字就被裁掉；
 * 列表高度也不按真机可见区域收，长菜单的底部划出屏幕。
 * 这里单独开一层全屏透明窗，面板按 [placeMenuPanel] 夹进系统栏内侧，装不下就在面板里滚。
 */
internal class PetActionMenu(
    context: Context,
    private val wm: WindowManager,
    private val onLeaf: (JSONObject) -> Unit,
) {
    private val themed = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Light)
    private val density = themed.resources.displayMetrics.density
    private var host: FrameLayout? = null
    private var rootPanel: ScrollView? = null
    private var rootRect = MenuRect(0, 0, 0, 0)
    private var subPanel: ScrollView? = null
    private var subRect = MenuRect(0, 0, 0, 0)
    private var openRow: View? = null

    fun dismiss() {
        val view = host ?: return
        host = null
        rootPanel = null
        subPanel = null
        openRow = null
        if (view.isAttachedToWindow) {
            runCatching { wm.removeViewImmediate(view) }
        }
    }

    fun show(anchor: MenuRect, items: JSONArray) {
        dismiss()
        val nodes = parseNodes(items)
        if (nodes.isEmpty()) return
        val bounds = currentBounds()
        val hostView = FrameLayout(themed).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            clipChildren = false
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { dismiss() }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismiss()
                    true
                } else {
                    false
                }
            }
        }
        val root = buildPanel(nodes) { row, node ->
            if (node.children.isEmpty()) {
                dismiss()
                node.leaf?.let(onLeaf)
            } else {
                toggleSub(hostView, row, node.children, bounds)
            }
        }
        hostView.addView(root)
        rootRect = placeMeasured(root, anchor, bounds, beside = null)
        rootPanel = root
        applyRect(root, rootRect, 0, 0)
        host = hostView
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "dsh-pet-menu"
        }
        try {
            wm.addView(hostView, lp)
        } catch (e: RuntimeException) {
            Log.e(TAG, "菜单无法显示", e)
            host = null
            return
        }
        hostView.requestFocus()
        hostView.post {
            val current = host ?: return@post
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                current.findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                ) { dismiss() }
            }
            val loc = IntArray(2)
            current.getLocationOnScreen(loc)
            rootPanel?.let { applyRect(it, rootRect, loc[0], loc[1]) }
            subPanel?.let { applyRect(it, subRect, loc[0], loc[1]) }
        }
    }

    private fun toggleSub(hostView: FrameLayout, row: View, children: List<MenuNode>, bounds: MenuRect) {
        if (openRow == row) {
            closeSub()
            return
        }
        closeSub()
        val panel = buildPanel(children) { _, node ->
            if (node.children.isNotEmpty()) return@buildPanel
            dismiss()
            node.leaf?.let(onLeaf)
        }
        hostView.addView(panel)
        val loc = IntArray(2)
        row.getLocationOnScreen(loc)
        val item = MenuRect(loc[0], loc[1], loc[0] + row.width, loc[1] + row.height)
        subRect = placeMeasured(panel, item, bounds, beside = item)
        subPanel = panel
        val origin = IntArray(2)
        hostView.getLocationOnScreen(origin)
        applyRect(panel, subRect, origin[0], origin[1])
        openRow = row
        row.setBackgroundColor(OPEN_TINT)
    }

    private fun closeSub() {
        openRow?.background = ripple()
        openRow = null
        val panel = subPanel ?: return
        subPanel = null
        (panel.parent as? ViewGroup)?.removeView(panel)
    }

    private fun placeMeasured(panel: ScrollView, anchorOrItem: MenuRect, bounds: MenuRect, beside: MenuRect?): MenuRect {
        val maxW = bounds.width.coerceAtLeast(1)
        val column = panel.getChildAt(0) as LinearLayout
        val contentW = textWidth(column, maxW)
        column.measure(
            View.MeasureSpec.makeMeasureSpec(contentW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val contentH = (column.measuredHeight + panel.paddingTop + panel.paddingBottom).coerceAtLeast(1)
        val rect = if (beside == null) {
            placeMenuPanel(anchorOrItem, contentW, contentH, bounds, gap = dp(8), minRoom = dp(48))
        } else {
            placeSubmenuPanel(beside, contentW, contentH, bounds, gap = dp(8))
        }
        panel.isVerticalScrollBarEnabled = contentH > rect.height
        panel.isScrollbarFadingEnabled = false
        return rect
    }

    private fun textWidth(column: LinearLayout, maxW: Int): Int {
        val paint = android.text.TextPaint()
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, themed.resources.displayMetrics)
        var w = dp(MIN_WIDTH_DP).coerceAtMost(maxW)
        for (i in 0 until column.childCount) {
            val row = column.getChildAt(i) as? LinearLayout ?: continue
            val label = row.getChildAt(0) as? TextView ?: continue
            val arrow = if (row.childCount > 1) dp(28) else 0
            val rowW = paint.measureText(label.text.toString()).roundToInt() + dp(16) + dp(12) + arrow + dp(8)
            if (rowW > w) w = rowW
        }
        return w.coerceIn(1, maxW)
    }

    private fun buildPanel(nodes: List<MenuNode>, onRow: (View, MenuNode) -> Unit): ScrollView {
        val column = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = dp(MIN_WIDTH_DP)
        }
        val scroll = ScrollView(themed).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = true
            scrollBarSize = dp(4)
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
            background = cardBackground()
            elevation = dp(8).toFloat()
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = true
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        scroll.setOnScrollChangeListener { _, _, y, _, oldY ->
            if (y != oldY && scroll === rootPanel) closeSub()
        }
        for (node in nodes) {
            val row = buildRow(node)
            row.setOnClickListener { onRow(row, node) }
            column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return scroll
    }

    private fun buildRow(node: MenuNode): LinearLayout {
        val row = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(16), dp(10), dp(12), dp(10))
            background = ripple()
            isClickable = true
            isFocusable = true
        }
        val label = TextView(themed).apply {
            text = node.label
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (node.children.isNotEmpty()) {
            val arrow = TextView(themed).apply {
                text = "›"
                setTextColor(ARROW)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            }
            row.addView(arrow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return row
    }

    private fun currentBounds(): MenuRect {
        val metrics = wm.maximumWindowMetrics
        val b = metrics.bounds
        val ins = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        return menuBounds(b.left, b.top, b.right, b.bottom, ins.left, ins.top, ins.right, ins.bottom, dp(12))
    }

    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(CARD)
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1).coerceAtLeast(1), 0x14000000)
    }

    private fun ripple(): android.graphics.drawable.Drawable? {
        val ta = themed.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        try {
            return ta.getDrawable(0)?.mutate()
        } finally {
            ta.recycle()
        }
    }

    private fun applyRect(panel: View, rect: MenuRect, originX: Int, originY: Int) {
        val lp = (panel.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(rect.width, rect.height)
        lp.width = rect.width
        lp.height = rect.height
        lp.leftMargin = rect.left - originX
        lp.topMargin = rect.top - originY
        panel.layoutParams = lp
    }

    private fun dp(v: Int) = (v * density).roundToInt()

    private data class MenuNode(val label: String, val leaf: JSONObject?, val children: List<MenuNode>)

    companion object {
        private const val TAG = "dsh-pet"
        private const val MIN_WIDTH_DP = 196
        private const val INK = 0xFF1F1F1F.toInt()
        private const val ARROW = 0xFF8E8E93.toInt()
        private const val CARD = 0xFFFFFFFF.toInt()
        private const val OPEN_TINT = 0x1A2B63FF

        private fun parseNodes(items: JSONArray): List<MenuNode> {
            val out = ArrayList<MenuNode>(items.length())
            for (i in 0 until items.length()) {
                val node = items.optJSONObject(i) ?: continue
                val label = node.optString("label")
                if (label.isEmpty()) continue
                val children = node.optJSONArray("children")
                if (children == null || children.length() == 0) {
                    out += MenuNode(label, node, emptyList())
                } else {
                    val leaves = parseNodes(children)
                    if (leaves.isNotEmpty()) out += MenuNode(label, null, leaves)
                }
            }
            return out
        }
    }
}
