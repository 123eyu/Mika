package com.wangzan101.mika.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpeedFlagsTest {
    @Test fun parseNoneIsOff() {
        assertEquals(SpeedFlags.OFF, SpeedFlags.parse("none"))
        assertEquals(SpeedFlags.OFF, SpeedFlags.parse(""))
        assertEquals("none", SpeedFlags.OFF.describe())
    }

    @Test fun parseRoundTripsDescribe() {
        val f = SpeedFlags.parse("replay=on,inlineScreen=on,batchSteps=off")
        assertEquals(SpeedFlags(inlineScreen = true, replay = true), f)
        assertEquals("inlineScreen,replay", f.describe())
        assertEquals(f, SpeedFlags.parse(f.describe()))
        val all = SpeedFlags(true, true, true, true, true, true, true)
        assertEquals(all, SpeedFlags.parse(all.describe()))
        assertEquals(SpeedFlags(adaptiveVision = true), SpeedFlags.parse("adaptiveVision"))
        val tables = SpeedFlags(tablesAtBirth = true)
        assertEquals(tables, SpeedFlags.parse(tables.describe()))
    }

    @Test fun unknownKeyRejected() {
        assertThrows(IllegalArgumentException::class.java) { SpeedFlags.parse("warpSpeed=on") }
        assertThrows(IllegalArgumentException::class.java) { SpeedFlags.parse("replay=maybe") }
    }

    @Test fun retiredKeyIgnored() {
        // 阶梯式裁剪已转正,存着的旧设置照样读得出其余开关
        assertEquals(SpeedFlags(replay = true), SpeedFlags.parse("cacheFriendlyPrune,replay"))
    }

    @Test fun legacyParallelTasksKeyIsIgnored() {
        // 「不同对话同时进行」开关已撤掉,旧存储里打开过的照样读得出其余开关
        assertEquals(SpeedFlags(replay = true, tablesAtBirth = true), SpeedFlags.parse("parallelTasks,replay,tablesAtBirth"))
        assertEquals(SpeedFlags(replay = true), SpeedFlags.parse("parallelTasks=on,replay=on"))
        assertEquals("replay", SpeedFlags.parse("parallelTasks,replay").describe())
    }
}
