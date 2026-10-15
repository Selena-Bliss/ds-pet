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
    var topMarginRatio: Float
        get() = sp.getFloat("topMarginRatio", 0.3f)
        set(v) = sp.edit().putFloat("topMarginRatio", v).apply()
    var fixed: Boolean
        get() = sp.getBoolean("fixed", false)
        set(v) = sp.edit().putBoolean("fixed", v).apply()
    var petCollision: Boolean
        get() = sp.getBoolean("petCollision", false)
        set(v) = sp.edit().putBoolean("petCollision", v).apply()

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
