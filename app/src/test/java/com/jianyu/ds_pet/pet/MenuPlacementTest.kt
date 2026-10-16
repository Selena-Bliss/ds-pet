package com.jianyu.ds_pet.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuPlacementTest {
    private val phone = MenuRect(8, 104, 1072, 2248)

    @Test
    fun menuBounds_insetsBarsThenMargin() {
        assertEquals(
            MenuRect(8, 104, 1072, 2248),
            menuBounds(0, 0, 1080, 2400, insetLeft = 0, insetTop = 96, insetRight = 0, insetBottom = 144, margin = 8),
        )
    }

    @Test
    fun menuBounds_keepsCutoutSideClear() {
        assertEquals(
            MenuRect(140, 8, 2392, 1009),
            menuBounds(0, 0, 2400, 1080, insetLeft = 132, insetTop = 0, insetRight = 0, insetBottom = 63, margin = 8),
        )
    }

    @Test
    fun menuBounds_fallsBackWhenMarginEatsTheScreen() {
        assertEquals(MenuRect(0, 0, 40, 40), menuBounds(0, 0, 40, 40, 0, 0, 0, 0, margin = 30))
    }

    @Test
    fun root_sitsBelowThePetWhenItFits() {
        val anchor = MenuRect(400, 280, 700, 620)
        val placed = placeMenuPanel(anchor, 480, 700, phone, gap = 8)
        assertEquals(400, placed.left)
        assertEquals(628, placed.top)
        assertEquals(480, placed.width)
        assertEquals(700, placed.height)
        assertInside(placed, phone)
    }

    @Test
    fun root_scrollsInsideTheSpaceBelowALongMenu() {
        val anchor = MenuRect(360, 220, 680, 560)
        val placed = placeMenuPanel(anchor, 520, 2400, phone, gap = 8, minRoom = 48)
        assertEquals(568, placed.top)
        assertEquals(phone.bottom, placed.bottom)
        assertTrue(placed.height < 2400)
        assertInside(placed, phone)
    }

    @Test
    fun root_flipsAboveWhenThePetIsNearTheBottom() {
        val anchor = MenuRect(200, 1900, 520, 2180)
        val placed = placeMenuPanel(anchor, 400, 500, phone, gap = 8)
        assertEquals(anchor.top - 8, placed.bottom)
        assertEquals(500, placed.height)
        assertInside(placed, phone)
    }

    @Test
    fun root_clampsWhenThePetWindowHangsOffTheLeft() {
        val anchor = MenuRect(-220, 180, 80, 520)
        val placed = placeMenuPanel(anchor, 560, 900, phone, gap = 8)
        assertEquals(phone.left, placed.left)
        assertTrue(placed.right <= phone.right)
        assertInside(placed, phone)
    }

    @Test
    fun root_shiftsLeftWhenThePetIsOnTheRightEdge() {
        val anchor = MenuRect(900, 400, 1080, 760)
        val placed = placeMenuPanel(anchor, 400, 300, phone, gap = 8)
        assertEquals(phone.right, placed.right)
        assertInside(placed, phone)
    }

    @Test
    fun submenu_opensToTheRightOfTheRow() {
        val item = MenuRect(100, 400, 360, 448)
        val placed = placeSubmenuPanel(item, 280, 600, phone, gap = 8)
        assertEquals(368, placed.left)
        assertEquals(400, placed.top)
        assertEquals(600, placed.height)
        assertInside(placed, phone)
    }

    @Test
    fun submenu_flipsLeftWhenTheRowIsAgainstTheRightEdge() {
        val item = MenuRect(760, 400, 1040, 448)
        val placed = placeSubmenuPanel(item, 400, 300, phone, gap = 8)
        assertEquals(item.left - 8, placed.right)
        assertInside(placed, phone)
    }

    @Test
    fun submenu_movesUpWhenTheRowIsNearTheBottom() {
        val item = MenuRect(100, 2100, 360, 2148)
        val placed = placeSubmenuPanel(item, 280, 500, phone, gap = 8)
        assertEquals(phone.bottom, placed.bottom)
        assertTrue(placed.top < item.top)
        assertInside(placed, phone)
    }

    private fun assertInside(panel: MenuRect, bounds: MenuRect) {
        assertTrue(panel.left >= bounds.left)
        assertTrue(panel.top >= bounds.top)
        assertTrue(panel.right <= bounds.right)
        assertTrue(panel.bottom <= bounds.bottom)
        assertTrue(panel.width > 0)
        assertTrue(panel.height > 0)
    }
}
