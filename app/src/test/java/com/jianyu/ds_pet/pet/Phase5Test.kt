package com.jianyu.ds_pet.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5Test {
    @Test
    fun keepAliveGuide_matchesTheVendorTable() {
        val xiaomi = keepAliveGuide("Xiaomi", "Redmi")
        assertEquals("小米 HyperOS / MIUI", xiaomi.vendor)
        assertTrue(xiaomi.actions.contains("无限制"))
        assertTrue(xiaomi.actions.contains("后台弹出界面"))
        assertEquals("设置 → 应用设置 → 应用管理 → DS-pet", xiaomi.settingsPath)

        assertEquals("小米 HyperOS / MIUI", keepAliveGuide("POCO", "POCO").vendor)

        val honor = keepAliveGuide("HUAWEI", "HONOR")
        assertEquals("华为 / 荣耀", honor.vendor)
        assertEquals("设置 → 电池 → 应用启动管理", honor.settingsPath)
        assertEquals("华为 / 荣耀", keepAliveGuide("HONOR", "HONOR").vendor)

        assertEquals("设置 → 电池 → 应用耗电管理", keepAliveGuide("OPPO", "OPPO").settingsPath)
        assertEquals("OPPO / 一加 / realme", keepAliveGuide("OnePlus", "OnePlus").vendor)
        assertEquals("OPPO / 一加 / realme", keepAliveGuide("realme", "realme").vendor)

        assertEquals("设置 → 电池 → 后台耗电管理", keepAliveGuide("vivo", "iQOO").settingsPath)
        assertEquals("vivo / iQOO", keepAliveGuide("vivo", "vivo").vendor)

        val samsung = keepAliveGuide("samsung", "samsung")
        assertEquals("三星", samsung.vendor)
        assertTrue(samsung.actions.contains("深度休眠"))
        assertEquals("设置 → 电池 → 后台使用限制", samsung.settingsPath)
    }

    @Test
    fun keepAliveGuide_unknownVendorStillPointsAtBatterySettings() {
        val guide = keepAliveGuide("Google", "Pixel")
        assertEquals("当前系统", guide.vendor)
        assertTrue(guide.settingsPath.contains("电池"))
        assertTrue(guide.actions.contains("不优化"))
    }

    @Test
    fun powerHold_onlyBelowTwentyPercentWhenEnabled() {
        assertEquals(0, powerHoldMs(enabled = true, batteryPercent = 20))
        assertEquals(0, powerHoldMs(enabled = true, batteryPercent = 100))
        assertEquals(0, powerHoldMs(enabled = true, batteryPercent = -1))
        assertEquals(0, powerHoldMs(enabled = false, batteryPercent = 5))
        assertEquals(LOW_BATTERY_HOLD_MS, powerHoldMs(enabled = true, batteryPercent = 19))
        assertEquals(LOW_BATTERY_HOLD_MS, powerHoldMs(enabled = true, batteryPercent = 0))
    }

    @Test
    fun batteryPercent_rejectsABrokenStickyBroadcast() {
        assertEquals(19, batteryPercent(level = 19, scale = 100))
        assertEquals(50, batteryPercent(level = 50, scale = 100))
        assertEquals(-1, batteryPercent(level = -1, scale = 100))
        assertEquals(-1, batteryPercent(level = 10, scale = 0))
        assertEquals(100, batteryPercent(level = 150, scale = 100))
    }
}
