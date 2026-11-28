package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.i18n.tr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SeatWaitCopyTest {
    @get:Rule val texts = ChineseTexts()

    @Test
    fun seatWaitCopyNamesHolder() {
        val copy = seatWaitCopy("买菜")
        assertTrue(copy, copy.contains("买菜"))
        assertEquals(tr(R.string.task_waiting_seat, "买菜"), copy)
    }

    @Test
    fun seatWaitCopyFallsBackWithoutTitle() {
        val other = tr(R.string.task_waiting_seat_other)
        assertEquals(other, seatWaitCopy(null))
        assertEquals(other, seatWaitCopy("  "))
    }

    @Test
    fun jumpTargetPrefersRef() {
        assertEquals("holder", TaskState.Held(GateReason.waitingSeat("等「买菜」用完手机", "holder")).jumpTarget("own"))
        assertEquals("own", TaskState.Held(GateReason.waitingSeat("等另一个任务用完手机", null)).jumpTarget("own"))
        assertEquals("own", TaskState.Running(step = 1, note = "").jumpTarget("own"))
    }
}
