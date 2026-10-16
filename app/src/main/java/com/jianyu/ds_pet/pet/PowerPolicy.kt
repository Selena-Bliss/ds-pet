package com.jianyu.ds_pet.pet

/** 设置页上的厂商保活说明。路径按移植方案 9.1，不代替用户去点系统开关。 */
data class KeepAliveGuide(
    val vendor: String,
    val actions: String,
    val settingsPath: String,
)

/**
 * 按厂商和品牌挑一段手动设置说明。
 * 荣耀有的机器品牌是 HONOR、厂商仍报 HUAWEI，所以两边都认，文案与华为相同。
 */
fun keepAliveGuide(manufacturer: String, brand: String): KeepAliveGuide {
    val blob = "${manufacturer.lowercase()} ${brand.lowercase()}"
    fun has(vararg keys: String) = keys.any { blob.contains(it) }
    return when {
        has("xiaomi", "redmi", "poco") -> KeepAliveGuide(
            vendor = "小米 HyperOS / MIUI",
            actions = "请手动打开自启动，把省电策略设为「无限制」，并允许后台弹出界面。对话窗口需要后台弹出界面。",
            settingsPath = "设置 → 应用设置 → 应用管理 → DS-pet",
        )
        has("huawei", "honor") -> KeepAliveGuide(
            vendor = "华为 / 荣耀",
            actions = "把应用启动管理改为「手动管理」，自启动、关联启动和后台活动都打开。",
            settingsPath = "设置 → 电池 → 应用启动管理",
        )
        has("oppo", "oneplus", "realme") -> KeepAliveGuide(
            vendor = "OPPO / 一加 / realme",
            actions = "允许自启动，并允许后台运行。",
            settingsPath = "设置 → 电池 → 应用耗电管理",
        )
        has("vivo", "iqoo") -> KeepAliveGuide(
            vendor = "vivo / iQOO",
            actions = "允许后台高耗电，并打开自启动。",
            settingsPath = "设置 → 电池 → 后台耗电管理",
        )
        has("samsung") -> KeepAliveGuide(
            vendor = "三星",
            actions = "把 DS-pet 从「深度休眠应用」里移除。",
            settingsPath = "设置 → 电池 → 后台使用限制",
        )
        else -> KeepAliveGuide(
            vendor = "当前系统",
            actions = "如果划掉任务或息屏后桌宠被清掉，请在电池优化里把 DS-pet 选为不优化，并按本机说明允许它在后台运行。",
            settingsPath = "系统设置 → 电池 → 电池优化",
        )
    }
}

/** 低于这个百分比才在两段动画之间停住。正好 20% 仍按正常速度播。 */
const val LOW_BATTERY_PERCENT = 20

/** 低电量时，一段动画结束后静止这么久再接下一段。 */
const val LOW_BATTERY_HOLD_MS = 8_000

/**
 * 返回要告诉页面的静止毫秒数。读不到电量（小于 0）或开关关闭时为 0，页面就按原速度接动画。
 */
fun powerHoldMs(enabled: Boolean, batteryPercent: Int): Int {
    if (!enabled || batteryPercent !in 0 until LOW_BATTERY_PERCENT) return 0
    return LOW_BATTERY_HOLD_MS
}

/** 把粘性电量广播里的 level/scale 折成 0–100。任一字段无效就返回 -1。 */
fun batteryPercent(level: Int, scale: Int): Int {
    if (level < 0 || scale <= 0) return -1
    return (level * 100 / scale).coerceIn(0, 100)
}
