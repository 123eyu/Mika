package com.wangzan101.mika.task

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ParallelCapabilityTest {
    @get:Rule val texts = ChineseTexts()

    @Test fun android13IsOne() {
        val limit = ParallelCapability.detect(sdk = 33, lowRam = false, userMax = 3)
        assertEquals(1, limit.max)
        assertNotNull(limit.reason)
    }

    @Test fun lowRamIsOne() {
        val limit = ParallelCapability.detect(sdk = 34, lowRam = true, userMax = 2)
        assertEquals(1, limit.max)
        assertNotNull(limit.reason)
    }

    @Test fun userMaxClamped() {
        assertEquals(ParallelLimit(3, null), ParallelCapability.detect(34, false, 9))
        assertEquals(ParallelLimit(1, null), ParallelCapability.detect(34, false, 0))
        val two = ParallelCapability.detect(34, false, ParallelCapability.DEFAULT_MAX)
        assertEquals(2, two.max)
        assertNull(two.reason)
    }

    @Test fun taskTotalIsWideConstant() {
        assertEquals(4, TaskCapacity.max(lowRam = false))
        assertEquals(2, TaskCapacity.max(lowRam = true))
    }
}
