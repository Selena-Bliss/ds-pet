package com.jianyu.ds_pet.pet

/**
 * 菜单要落在这块矩形里。坐标跟悬浮窗一样，是屏幕物理像素，原点在整块面板左上角。
 */
internal data class MenuRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * 从整屏扣掉系统栏、挖孔，再留一圈边距。边距把区域挤没时退回扣过系统栏的矩形，
 * 避免菜单被算到状态栏或导航条下面。
 */
internal fun menuBounds(
    screenLeft: Int,
    screenTop: Int,
    screenRight: Int,
    screenBottom: Int,
    insetLeft: Int,
    insetTop: Int,
    insetRight: Int,
    insetBottom: Int,
    margin: Int,
): MenuRect {
    val outer = MenuRect(
        screenLeft + insetLeft.coerceAtLeast(0),
        screenTop + insetTop.coerceAtLeast(0),
        screenRight - insetRight.coerceAtLeast(0),
        screenBottom - insetBottom.coerceAtLeast(0),
    )
    val safeOuter = if (outer.width > 0 && outer.height > 0) {
        outer
    } else {
        MenuRect(
            screenLeft,
            screenTop,
            screenRight.coerceAtLeast(screenLeft + 1),
            screenBottom.coerceAtLeast(screenTop + 1),
        )
    }
    val pad = margin.coerceAtLeast(0)
    val inner = MenuRect(safeOuter.left + pad, safeOuter.top + pad, safeOuter.right - pad, safeOuter.bottom - pad)
    return if (inner.width >= 48 && inner.height >= 48) inner else safeOuter
}

/**
 * 根菜单贴着锚点（桌宠窗口）展开，但整块必须落在 [bounds] 里。
 * 放得下时优先在锚点下方、左缘对齐；下方不够就翻到上方。两边都放不下整份内容时，
 * 用空间更大的那一侧，高度收到那一侧剩下的区域，调用方自己滚动。
 * 锚点已经贴边或探出屏幕时，水平方向夹回 [bounds]，不再跟着窗口原点滑出去。
 */
internal fun placeMenuPanel(
    anchor: MenuRect,
    contentWidth: Int,
    contentHeight: Int,
    bounds: MenuRect,
    gap: Int,
    minRoom: Int = 48,
): MenuRect {
    if (bounds.width <= 1 || bounds.height <= 1) {
        return MenuRect(bounds.left, bounds.top, bounds.left + 1, bounds.top + 1)
    }
    val g = gap.coerceAtLeast(0)
    val w = contentWidth.coerceAtLeast(1).coerceAtMost(bounds.width)
    val h = contentHeight.coerceAtLeast(1).coerceAtMost(bounds.height)
    var left = anchor.left
    if (left + w > bounds.right) left = bounds.right - w
    if (left < bounds.left) left = bounds.left

    val spaceBelow = (bounds.bottom - (anchor.bottom + g)).coerceAtLeast(0)
    val spaceAbove = (anchor.top - g - bounds.top).coerceAtLeast(0)
    val placeBelow = when {
        h <= spaceBelow -> true
        h <= spaceAbove -> false
        else -> spaceBelow >= spaceAbove
    }
    val room = if (placeBelow) spaceBelow else spaceAbove
    val shown = if (room >= minRoom) h.coerceAtMost(room) else h
    var top = if (placeBelow) anchor.bottom + g else anchor.top - g - shown
    if (top + shown > bounds.bottom) top = bounds.bottom - shown
    if (top < bounds.top) top = bounds.top
    val finalH = shown.coerceAtMost((bounds.bottom - top).coerceAtLeast(1))
    return MenuRect(left, top, left + w, top + finalH)
}

/**
 * 子菜单优先开在条目右侧，右侧不够就翻到左侧，垂直与条目顶对齐后再夹进 [bounds]。
 * 内容比屏幕高时高度收到 [bounds]，调用方自己滚动。
 */
internal fun placeSubmenuPanel(
    item: MenuRect,
    contentWidth: Int,
    contentHeight: Int,
    bounds: MenuRect,
    gap: Int,
): MenuRect {
    if (bounds.width <= 1 || bounds.height <= 1) {
        return MenuRect(bounds.left, bounds.top, bounds.left + 1, bounds.top + 1)
    }
    val g = gap.coerceAtLeast(0)
    val w = contentWidth.coerceAtLeast(1).coerceAtMost(bounds.width)
    val h = contentHeight.coerceAtLeast(1).coerceAtMost(bounds.height)
    val atRight = item.right + g
    val atLeft = item.left - g - w
    val fitsRight = atRight >= bounds.left && atRight + w <= bounds.right
    val fitsLeft = atLeft >= bounds.left && atLeft + w <= bounds.right
    var left = when {
        fitsRight -> atRight
        fitsLeft -> atLeft
        else -> if (bounds.right - item.right >= item.left - bounds.left) atRight else atLeft
    }
    if (left + w > bounds.right) left = bounds.right - w
    if (left < bounds.left) left = bounds.left
    var top = item.top
    if (top + h > bounds.bottom) top = bounds.bottom - h
    if (top < bounds.top) top = bounds.top
    val finalH = h.coerceAtMost((bounds.bottom - top).coerceAtLeast(1))
    return MenuRect(left, top, left + w, top + finalH)
}
