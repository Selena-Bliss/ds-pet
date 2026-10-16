package com.jianyu.ds_pet.pet

import android.content.Context

class PetSettings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("pet", Context.MODE_PRIVATE)

    var sizeDp: Int
        get() = sp.getInt("sizeDp", 240)
        set(v) = sp.edit().putInt("sizeDp", v).apply()
    var petCount: Int
        get() = sp.getInt("petCount", 1)
        set(v) = sp.edit().putInt("petCount", v).apply()
    var corner: String
        get() = sp.getString("corner", "bottom-right")!!
        set(v) = sp.edit().putString("corner", v).apply()
    var marginXDp: Int
        get() = sp.getInt("marginXDp", 8)
        set(v) = sp.edit().putInt("marginXDp", v).apply()
    var marginYDp: Int
        get() = sp.getInt("marginYDp", 48)
        set(v) = sp.edit().putInt("marginYDp", v).apply()
    /** 阶段 2 的固定头顶比例。阶段 3 起气泡按实际高度撑开，不再读取。 */
    var topMarginRatio: Float
        get() = sp.getFloat("topMarginRatio", 0.3f)
        set(v) = sp.edit().putFloat("topMarginRatio", v).apply()
    var fixed: Boolean
        get() = sp.getBoolean("fixed", false)
        set(v) = sp.edit().putBoolean("fixed", v).apply()
    var petCollision: Boolean
        get() = sp.getBoolean("petCollision", false)
        set(v) = sp.edit().putBoolean("petCollision", v).apply()
    /** 左右各裁掉 [COMPACT_SIDE_CROP] 倍宠物宽度的透明边。 */
    var compact: Boolean
        get() = sp.getBoolean("compact", false)
        set(v) = sp.edit().putBoolean("compact", v).apply()
    /**
     * 电量低于 20% 时，每段动画播完静止一会儿再继续。
     * 拖拽、甩抛和走路过程中不插入这段静止。
     */
    var lowBatteryHold: Boolean
        get() = sp.getBoolean("lowBatteryHold", true)
        set(v) = sp.edit().putBoolean("lowBatteryHold", v).apply()
    /** 留空表示用 deepseek-chat。密钥不放在这里。 */
    var aiModel: String
        get() = sp.getString("aiModel", "")!!
        set(v) = sp.edit().putString("aiModel", v.trim()).apply()

    companion object {
        /** 不超过身体左侧透明区（画布 200/640）。再大就会切到身体。 */
        const val COMPACT_SIDE_CROP = 0.15
    }

    fun loadPos(petId: String): String = sp.getString("pos.$petId", "null")!!
    fun savePos(petId: String, json: String) = sp.edit().putString("pos.$petId", json).apply()

    /** 丢掉拖拽落点，下次按角落和边距摆放。 */
    fun clearPositions() {
        val editor = sp.edit()
        for (key in sp.all.keys) {
            if (key.startsWith("pos.")) editor.remove(key)
        }
        editor.apply()
    }
}
