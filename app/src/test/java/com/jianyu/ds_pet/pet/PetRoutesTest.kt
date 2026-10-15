package com.jianyu.ds_pet.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
