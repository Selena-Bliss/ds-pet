package com.jianyu.ds_pet.pet

import kotlin.math.hypot

/** 摇一摇给出的抛掷初速，单位是 CSS 像素/秒，和页面里 startThrow 用的是同一套。 */
data class ShakeFling(val vx: Double, val vy: Double)

/**
 * 平面加速度超过这个值才算一次摇。走路和慢慢转手腕到不了。
 * 灭屏时服务会停掉采样，口袋里的颠簸不会进来。
 */
const val SHAKE_THRESHOLD_MPS2 = 14f

/** 必须先落回这个值以下，下一次摇才算新的一下。 */
const val SHAKE_REARM_MPS2 = 6f

/** 同一次来回摇里，加速和停住各有一个尖峰，这段时间里只甩一次。 */
const val SHAKE_COOLDOWN_MS = 550L

const val SHAKE_MIN_SPEED = 1700.0
const val SHAKE_MAX_SPEED = 3400.0

/** 到达这个加速度时用满初速，再大力也停在 [SHAKE_MAX_SPEED]。 */
const val SHAKE_FULL_MPS2 = 30f

private const val GRAVITY_ALPHA = 0.82f

/**
 * 设备坐标的水平加速度转到屏幕坐标。
 * 设备：+x 向右，+y 朝手机自然方向的顶部。
 * 屏幕：+x 向右，+y 向下。
 * [rotation] 用 [android.view.Surface] 的数值：0、1、2、3 对应 0° / 90° / 180° / 270°。
 */
fun screenAccel(ax: Float, ay: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
    0 -> ax to -ay
    1 -> -ay to -ax
    2 -> -ax to ay
    3 -> ay to ax
    else -> ax to -ay
}

/** 越用力摇，飞得越快；到顶之后不再加快。 */
fun flingSpeed(magnitude: Float): Double {
    val span = SHAKE_FULL_MPS2 - SHAKE_THRESHOLD_MPS2
    val t = ((magnitude - SHAKE_THRESHOLD_MPS2) / span).coerceIn(0f, 1f)
    return SHAKE_MIN_SPEED + (SHAKE_MAX_SPEED - SHAKE_MIN_SPEED) * t
}

fun flingJson(vx: Double, vy: Double): String = """{"vx":$vx,"vy":$vy}"""

/**
 * 把加速度采样收成一次甩飞。
 * 方向跟手机这次加速的方向一致：往右甩，桌宠就往右飞。
 * 朝屏幕里外的分量不算，避免拍一下背面也飞出去。
 */
class ShakeDetector {
    private var gx = 0f
    private var gy = 0f
    private var gravityReady = false
    private var armed = true
    private var lastFlingAt = -SHAKE_COOLDOWN_MS

    fun reset() {
        gx = 0f
        gy = 0f
        gravityReady = false
        armed = true
        lastFlingAt = -SHAKE_COOLDOWN_MS
    }

    /**
     * [includesGravity] 为真时输入是原始加速度计，先减掉重力。
     * 线性加速度传感器已经去掉重力，传 false。
     */
    fun sample(
        ax: Float,
        ay: Float,
        az: Float,
        rotation: Int,
        nowMs: Long,
        includesGravity: Boolean,
    ): ShakeFling? {
        if (!ax.isFinite() || !ay.isFinite() || !az.isFinite()) return null
        val (lx, ly) = withoutGravity(ax, ay, includesGravity)
        val (sx, sy) = screenAccel(lx, ly, rotation)
        val mag = hypot(sx, sy)
        if (!mag.isFinite()) return null
        if (mag < SHAKE_REARM_MPS2) armed = true
        if (mag < SHAKE_THRESHOLD_MPS2 || !armed) return null
        if (nowMs - lastFlingAt < SHAKE_COOLDOWN_MS) {
            // 同一次来回的回摆也要吃掉，不然冷却一到就会朝反方向再飞一次。
            armed = false
            return null
        }
        armed = false
        lastFlingAt = nowMs
        val speed = flingSpeed(mag)
        val scale = speed / mag
        return ShakeFling(sx * scale, sy * scale)
    }

    private fun withoutGravity(ax: Float, ay: Float, includesGravity: Boolean): Pair<Float, Float> {
        if (!includesGravity) return ax to ay
        if (!gravityReady) {
            gx = ax
            gy = ay
            gravityReady = true
            return 0f to 0f
        }
        gx = GRAVITY_ALPHA * gx + (1f - GRAVITY_ALPHA) * ax
        gy = GRAVITY_ALPHA * gy + (1f - GRAVITY_ALPHA) * ay
        return (ax - gx) to (ay - gy)
    }
}
