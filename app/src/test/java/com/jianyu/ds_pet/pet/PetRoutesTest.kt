package com.jianyu.ds_pet.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetRoutesTest {
    @Test
    fun parseRange_closed() {
        assertEquals(0L..99L, PetRoutes.parseRange("bytes=0-99", 1000))
    }

    @Test
    fun parseRange_openEnd() {
        assertEquals(100L..999L, PetRoutes.parseRange("bytes=100-", 1000))
    }

    @Test
    fun parseRange_suffix() {
        assertEquals(500L..999L, PetRoutes.parseRange("bytes=-500", 1000))
    }

    @Test
    fun parseRange_clampsToTotal() {
        assertEquals(0L..99L, PetRoutes.parseRange("bytes=0-5000", 100))
    }

    @Test
    fun parseRange_rejectsInvertedAndGarbage() {
        assertNull(PetRoutes.parseRange("bytes=20-10", 100))
        assertNull(PetRoutes.parseRange("bytes=abc", 100))
        assertNull(PetRoutes.parseRange(null, 100))
    }

    @Test
    fun safe_acceptsAnimationName() {
        assertEquals("待机呼吸休闲.webm", PetRoutes.safe("待机呼吸休闲.webm"))
    }

    @Test
    fun safe_rejectsTraversal() {
        assertNull(PetRoutes.safe("../a.webm"))
        assertNull(PetRoutes.safe("a/b.webm"))
        assertNull(PetRoutes.safe("a\\b.webm"))
        assertNull(PetRoutes.safe(""))
    }

    @Test
    fun servedPets_desktopDefaults() {
        val pets = servedPetConfigs(
            templateName = "蓝毛小女仆",
            petCount = 1,
            sizeDp = 240,
            corner = "bottom-right",
            marginXDp = 8,
            marginYDp = 48,
            fixed = false,
        )
        assertEquals(1, pets.size)
        assertEquals("main", pets[0].id)
        assertEquals("蓝毛小女仆", pets[0].name)
        assertEquals("desktop", pets[0].display)
        assertEquals(240, pets[0].sizeDp)
        assertEquals("bottom-right", pets[0].corner)
        assertEquals(8, pets[0].marginX)
        assertEquals(48, pets[0].marginY)
        assertFalse(pets[0].fixed)
        assertFalse(pets[0].balanceEnabled)
        assertFalse(pets[0].whisperEnabled)
        assertFalse(pets[0].workStatusEnabled)
    }

    @Test
    fun servedPets_clampsCountSizeAndCorner() {
        val many = servedPetConfigs("蓝毛小女仆", 9, 240, "top-left", 8, 48, true)
        assertEquals(4, many.size)
        assertEquals(listOf("main", "main1", "main2", "main3"), many.map { it.id })
        assertEquals(many.size, many.map { it.id }.toSet().size)
        assertTrue(many.all { it.display == "desktop" && it.fixed })
        assertEquals(8 + 240 / 3, many[1].marginX)
        assertEquals("蓝毛小女仆 2", many[1].name)

        val none = servedPetConfigs("蓝毛小女仆", 0, 10, "nope", -4, 48, false)
        assertEquals(1, none.size)
        assertEquals("bottom-right", none[0].corner)
        assertEquals(160, none[0].sizeDp)
        assertEquals(0, none[0].marginX)
        assertEquals(360, servedPetConfigs("a", 1, 999, "bottom-right", 0, 0, false)[0].sizeDp)
    }
}
