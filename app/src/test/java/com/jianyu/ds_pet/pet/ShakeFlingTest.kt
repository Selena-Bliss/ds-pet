package com.jianyu.ds_pet.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ShakeFlingTest {
    @Test
    fun screenAccel_followsDisplayRotation() {
        val portrait = screenAccel(ax = 4f, ay = -6f, rotation = 0)
        assertEquals(4f, portrait.first, 0.001f)
        assertEquals(6f, portrait.second, 0.001f)

        val land90 = screenAccel(ax = 4f, ay = -6f, rotation = 1)
        assertEquals(6f, land90.first, 0.001f)
        assertEquals(-4f, land90.second, 0.001f)

        val upside = screenAccel(ax = 4f, ay = -6f, rotation = 2)
        assertEquals(-4f, upside.first, 0.001f)
        assertEquals(-6f, upside.second, 0.001f)

        val land270 = screenAccel(ax = 4f, ay = -6f, rotation = 3)
        assertEquals(-6f, land270.first, 0.001f)
        assertEquals(4f, land270.second, 0.001f)
    }

    @Test
    fun flingSpeed_rampsThenCaps() {
        assertEquals(SHAKE_MIN_SPEED, flingSpeed(SHAKE_THRESHOLD_MPS2), 1e-6)
        assertEquals(SHAKE_MAX_SPEED, flingSpeed(SHAKE_FULL_MPS2), 1e-6)
        assertEquals(SHAKE_MAX_SPEED, flingSpeed(80f), 1e-6)
        val mid = flingSpeed((SHAKE_THRESHOLD_MPS2 + SHAKE_FULL_MPS2) / 2f)
        assertTrue(mid > SHAKE_MIN_SPEED && mid < SHAKE_MAX_SPEED)
    }

    @Test
    fun sample_flingsInTheDirectionOfTheJerk() {
        val detector = ShakeDetector()
        val right = detector.sample(20f, 0f, 0f, rotation = 0, nowMs = 1_000L, includesGravity = false)
        assertNotNull(right)
        assertTrue(right!!.vx > 0)
        assertEquals(0.0, right.vy, 1e-6)
        assertEquals(flingSpeed(20f), hypot(right.vx, right.vy), 0.01)

        val up = ShakeDetector().sample(0f, 20f, 0f, rotation = 0, nowMs = 1_000L, includesGravity = false)
        assertNotNull(up)
        assertEquals(0.0, up!!.vx, 1e-6)
        assertTrue(up.vy < 0)
    }

    @Test
    fun sample_usesLandscapeAxes() {
        val fling = ShakeDetector().sample(0f, 20f, 0f, rotation = 1, nowMs = 1_000L, includesGravity = false)
        assertNotNull(fling)
        assertTrue(fling!!.vx < 0)
        assertEquals(0.0, fling.vy, 1e-6)
    }

    @Test
    fun sample_ignoresAPushStraightIntoTheScreen() {
        val fling = ShakeDetector().sample(0f, 0f, 40f, rotation = 0, nowMs = 1_000L, includesGravity = false)
        assertNull(fling)
    }

    @Test
    fun sample_ignoresALightBumpAndABadReading() {
        val detector = ShakeDetector()
        assertNull(detector.sample(SHAKE_THRESHOLD_MPS2 - 0.1f, 0f, 0f, 0, 1_000L, includesGravity = false))
        assertNull(detector.sample(Float.NaN, 0f, 0f, 0, 1_000L, includesGravity = false))
    }

    @Test
    fun sample_oneShakeIsOneFlingUntilItSettles() {
        val detector = ShakeDetector()
        assertNotNull(detector.sample(20f, 0f, 0f, 0, 1_000L, includesGravity = false))
        assertNull(detector.sample(0f, 0f, 0f, 0, 1_100L, includesGravity = false))
        assertNull(detector.sample(-22f, 0f, 0f, 0, 1_200L, includesGravity = false))
        assertNull(detector.sample(-18f, 0f, 0f, 0, 1_000L + SHAKE_COOLDOWN_MS, includesGravity = false))
        assertNull(detector.sample(0f, 0f, 0f, 0, 1_000L + SHAKE_COOLDOWN_MS + 20, includesGravity = false))

        val again = detector.sample(-18f, 0f, 0f, 0, 1_000L + SHAKE_COOLDOWN_MS + 40, includesGravity = false)
        assertNotNull(again)
        assertTrue(again!!.vx < 0)
    }

    @Test
    fun sample_staysQuietWhileThePhoneIsJustHeld() {
        val detector = ShakeDetector()
        repeat(40) { i ->
            assertNull(detector.sample(0.2f, 9.8f, 0.4f, rotation = 0, nowMs = i * 20L, includesGravity = true))
        }
    }

    @Test
    fun sample_rawAccelerometerStillFlingAlongTheJerk() {
        val detector = ShakeDetector()
        assertNull(detector.sample(0f, 9.8f, 0f, 0, 0L, includesGravity = true))
        val fling = detector.sample(0f, 9.8f + 40f, 1f, rotation = 0, nowMs = 50L, includesGravity = true)
        assertNotNull(fling)
        assertTrue(fling!!.vy < 0)
        assertTrue(hypot(fling.vx, fling.vy) >= SHAKE_MIN_SPEED - 1e-6)
    }

    @Test
    fun flingJson_isThePayloadThePageReads() {
        assertEquals("""{"vx":1.5,"vy":-2.0}""", flingJson(1.5, -2.0))
    }
}
