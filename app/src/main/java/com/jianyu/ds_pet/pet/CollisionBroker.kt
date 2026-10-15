package com.jianyu.ds_pet.pet

import org.json.JSONObject

/** 阶段 2 不广播飞行状态。多宠跨窗碰撞在阶段 3 补全。 */
class CollisionBroker(private val windows: () -> List<PetWindow>) {
    @Suppress("UNUSED_PARAMETER")
    fun update(petId: String, state: JSONObject) {
        if (windows().size < 2) return
    }

    @Suppress("UNUSED_PARAMETER")
    fun hit(targetId: String, vx: Double, vy: Double) = Unit
}
