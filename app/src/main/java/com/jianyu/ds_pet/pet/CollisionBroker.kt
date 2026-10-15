package com.jianyu.ds_pet.pet

import android.view.Choreographer
import org.json.JSONObject

/** 至少两只、并且设置里打开了互撞，才广播站场。关闭时互相穿过，也不用每帧叫醒各窗口。 */
internal fun shouldBroadcastFlight(petCount: Int, collisionEnabled: Boolean): Boolean =
    collisionEnabled && petCount >= 2

/** 与桌面 main.js 的 pet:flight-states 相同：petId -> { x, y, vx, vy, size, bottomPad }，物理像素。 */
internal data class FlightSample(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val vx: Double = 0.0,
    val vy: Double = 0.0,
    val size: Double = 0.0,
    val bottomPad: Double = 0.0,
)

internal fun flightSampleOf(state: JSONObject) = FlightSample(
    x = finite(state.optDouble("x", 0.0)),
    y = finite(state.optDouble("y", 0.0)),
    vx = finite(state.optDouble("vx", 0.0)),
    vy = finite(state.optDouble("vy", 0.0)),
    size = finite(state.optDouble("size", 0.0)),
    bottomPad = finite(state.optDouble("bottomPad", 0.0)),
)

internal fun flightStatesJson(states: Map<String, FlightSample>): String =
    states.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (id, sample) ->
        "${jsonQuote(id)}:{\"x\":${jsonNum(sample.x)},\"y\":${jsonNum(sample.y)},\"vx\":${jsonNum(sample.vx)},\"vy\":${jsonNum(sample.vy)},\"size\":${jsonNum(sample.size)},\"bottomPad\":${jsonNum(sample.bottomPad)}}"
    }

internal fun petHitJson(vx: Double, vy: Double): String =
    "{\"vx\":${jsonNum(vx)},\"vy\":${jsonNum(vy)}}"

private fun finite(v: Double): Double = if (v.isFinite()) v else 0.0

private fun jsonNum(v: Double): String = finite(v).toString()

private fun jsonQuote(text: String): String = buildString {
    append('"')
    for (c in text) {
        when (c) {
            '\\', '"' -> append('\\').append(c)
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
    }
    append('"')
}

/**
 * 跨窗碰撞站场。每只宠物的位置由 setBounds / reportFlight 写入，下一帧合成一份全量广播。
 * 飞行方在自己的页面里做重叠检测，再把被撞方的新速度经 [hit] 送回目标窗口。
 */
class CollisionBroker(
    private val petIds: () -> Set<String>,
    private val collisionEnabled: () -> Boolean,
    private val emitStates: (String) -> Unit,
    private val emitHit: (petId: String, payload: String) -> Unit,
    private val postFrame: (Choreographer.FrameCallback) -> Unit = { callback ->
        Choreographer.getInstance().postFrameCallback(callback)
    },
    private val removeFrame: (Choreographer.FrameCallback) -> Unit = { callback ->
        Choreographer.getInstance().removeFrameCallback(callback)
    },
) {
    private val states = LinkedHashMap<String, FlightSample>()
    private var scheduled = false
    private var frameToken = 0
    private var postedToken = 0

    private val frameCallback = Choreographer.FrameCallback {
        scheduled = false
        if (postedToken != frameToken) return@FrameCallback
        if (!shouldBroadcastFlight(petIds().size, collisionEnabled())) return@FrameCallback
        emitStates(flightStatesJson(liveStates()))
    }

    fun update(petId: String, state: JSONObject) = updateSample(petId, flightSampleOf(state))

    internal fun updateSample(petId: String, sample: FlightSample) {
        if (petId.isEmpty()) return
        states[petId] = sample
        if (scheduled || !shouldBroadcastFlight(petIds().size, collisionEnabled())) return
        scheduled = true
        postedToken = frameToken
        postFrame(frameCallback)
    }

    fun hit(targetId: String, vx: Double, vy: Double) {
        if (!collisionEnabled() || targetId.isEmpty() || targetId !in petIds()) return
        emitHit(targetId, petHitJson(vx, vy))
    }

    fun clear() {
        states.clear()
        frameToken++
        if (!scheduled) return
        scheduled = false
        removeFrame(frameCallback)
    }

    private fun liveStates(): Map<String, FlightSample> {
        states.keys.retainAll(petIds())
        return states
    }
}
