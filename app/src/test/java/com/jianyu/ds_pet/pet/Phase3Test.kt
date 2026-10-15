package com.jianyu.ds_pet.pet

import android.content.res.Configuration
import android.view.Choreographer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3Test {
    @Test
    fun shouldBroadcast_needsTwoPetsAndTheSwitch() {
        assertFalse(shouldBroadcastFlight(1, true))
        assertFalse(shouldBroadcastFlight(2, false))
        assertFalse(shouldBroadcastFlight(0, true))
        assertTrue(shouldBroadcastFlight(2, true))
        assertTrue(shouldBroadcastFlight(4, true))
    }

    @Test
    fun flightPayload_matchesDesktopShape() {
        val json = flightStatesJson(
            linkedMapOf(
                "main" to FlightSample(x = 10.0, y = 20.0, vx = 1.5, vy = -2.0, size = 240.0, bottomPad = 12.0),
                "main1" to FlightSample(x = 400.0),
            ),
        )
        assertEquals(
            """{"main":{"x":10.0,"y":20.0,"vx":1.5,"vy":-2.0,"size":240.0,"bottomPad":12.0},"main1":{"x":400.0,"y":0.0,"vx":0.0,"vy":0.0,"size":0.0,"bottomPad":0.0}}""",
            json,
        )
        assertEquals("""{"vx":3.25,"vy":-8.0}""", petHitJson(3.25, -8.0))
        assertTrue(flightStatesJson(linkedMapOf("main" to FlightSample(x = Double.NaN))).contains("\"x\":0.0"))
    }

    @Test
    fun broker_coalescesUpdatesAndSkipsWhenOff() {
        var enabled = false
        val ids = linkedSetOf("main", "main1")
        var posts = 0
        var payload = ""
        val hits = mutableListOf<Pair<String, String>>()
        var pending: Choreographer.FrameCallback? = null
        val broker = CollisionBroker(
            petIds = { ids },
            collisionEnabled = { enabled },
            emitStates = { payload = it },
            emitHit = { id, body -> hits += id to body },
            postFrame = {
                posts++
                pending = it
            },
            removeFrame = {},
        )
        broker.updateSample("main", FlightSample(x = 5.0, y = 6.0, size = 100.0))
        assertEquals(0, posts)
        broker.hit("main1", 1.0, 2.0)
        assertTrue(hits.isEmpty())

        enabled = true
        broker.updateSample("main1", FlightSample(x = 9.0, vx = 4.0))
        broker.updateSample("main", FlightSample(x = 7.0, size = 80.0))
        assertEquals(1, posts)
        checkNotNull(pending).doFrame(0)
        assertEquals(
            """{"main":{"x":7.0,"y":0.0,"vx":0.0,"vy":0.0,"size":80.0,"bottomPad":0.0},"main1":{"x":9.0,"y":0.0,"vx":4.0,"vy":0.0,"size":0.0,"bottomPad":0.0}}""",
            payload,
        )

        broker.hit("main1", 1.5, -3.0)
        assertEquals("main1", hits.single().first)
        assertEquals("""{"vx":1.5,"vy":-3.0}""", hits.single().second)
        broker.hit("gone", 1.0, 1.0)
        assertEquals(1, hits.size)
    }

    @Test
    fun broker_dropsPetsThatLeftAndIgnoresAClearedFrame() {
        val ids = mutableSetOf("main", "main1", "ghost")
        var payload = ""
        var pending: Choreographer.FrameCallback? = null
        val broker = CollisionBroker(
            petIds = { ids },
            collisionEnabled = { true },
            emitStates = { payload = it },
            emitHit = { _, _ -> },
            postFrame = { pending = it },
            removeFrame = { pending = null },
        )
        broker.updateSample("main", FlightSample(x = 1.0))
        broker.updateSample("ghost", FlightSample(x = 99.0))
        val callback = checkNotNull(pending)
        ids.remove("ghost")
        callback.doFrame(0)
        assertTrue(payload.contains("\"main\""))
        assertFalse(payload.contains("ghost"))

        broker.updateSample("main", FlightSample(x = 2.0))
        val late = checkNotNull(pending)
        broker.clear()
        assertTrue(pending == null)
        payload = ""
        late.doFrame(0)
        assertEquals("", payload)
    }

    @Test
    fun boundsMatchOrientation_rejectsStaleRotation() {
        assertTrue(boundsMatchOrientation(1080, 2400, Configuration.ORIENTATION_PORTRAIT))
        assertTrue(boundsMatchOrientation(2400, 1080, Configuration.ORIENTATION_LANDSCAPE))
        assertFalse(boundsMatchOrientation(1080, 2400, Configuration.ORIENTATION_LANDSCAPE))
        assertFalse(boundsMatchOrientation(2400, 1080, Configuration.ORIENTATION_PORTRAIT))
        assertFalse(boundsMatchOrientation(0, 1080, Configuration.ORIENTATION_LANDSCAPE))
        assertTrue(boundsMatchOrientation(1080, 2400, Configuration.ORIENTATION_UNDEFINED))
    }

    @Test
    fun workRect_movesCutoutFromTopToSide() {
        assertEquals(WorkBox(0, 96, 1080, 2160), insetWorkBox(0, 0, 1080, 2400, left = 0, top = 96, right = 0, bottom = 144))
        assertEquals(WorkBox(132, 0, 2268, 1017), insetWorkBox(0, 0, 2400, 1080, left = 132, top = 0, right = 0, bottom = 63))
    }

    @Test
    fun workRect_fallsBackWhenInsetsEatTheScreen() {
        assertEquals(WorkBox(0, 0, 100, 80), insetWorkBox(0, 0, 100, 80, left = 80, top = 0, right = 80, bottom = 0))
    }

    @Test
    fun geometryJson_includesScaleAndSideCutout() {
        val work = insetWorkBox(0, 0, 2400, 1080, left = 132, top = 0, right = 0, bottom = 63)
        val json = geometryJson(
            workLeft = work.x,
            workTop = work.y,
            workWidth = work.width,
            workHeight = work.height,
            panelLeft = 0,
            panelTop = 0,
            panelWidth = 2400,
            panelHeight = 1080,
            density = 2.75,
        )
        assertTrue(json.contains("\"hull\":{\"x\":132,\"y\":0,\"width\":2268,\"height\":1017}"))
        assertTrue(json.contains("\"panels\":[{\"x\":0,\"y\":0,\"width\":2400,\"height\":1080}]"))
        assertTrue(json.contains("\"primaryIndex\":0"))
        assertTrue(json.contains("\"scale\":2.75"))
        assertTrue(PetSettings.COMPACT_SIDE_CROP <= 0.15)
    }
}
