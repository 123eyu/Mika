package com.wangzan101.mika.task

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手机座位:有空座立即占、满了先来先等、取消不吞座位、座位变少不抢已占的。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneSeatsTest {
    private val noWait: (List<String>) -> Unit = { error("不该等座位,当时持有者:$it") }

    @Test fun takesFreeSeatImmediately() = runTest {
        val seats = PhoneSeats { 2 }
        seats.take("a", noWait)
        seats.take("b", noWait)
        assertEquals(listOf("a", "b"), seats.holders())
        assertTrue(seats.waiting().isEmpty())
    }

    @Test fun thirdWaitsUntilRelease() = runTest {
        val seats = PhoneSeats { 2 }
        seats.take("a", noWait)
        seats.take("b", noWait)
        val seen = mutableListOf<List<String>>()
        var got = false
        launch { seats.take("c") { seen += it }; got = true }
        advanceUntilIdle()
        assertEquals(listOf(listOf("a", "b")), seen)
        assertFalse(got)
        assertEquals(listOf("c"), seats.waiting())

        seats.release("a")
        advanceUntilIdle()
        assertTrue(got)
        assertEquals(listOf("b", "c"), seats.holders())
        assertTrue(seats.waiting().isEmpty())
    }

    @Test fun waitersAreServedInOrder() = runTest {
        val seats = PhoneSeats { 1 }
        seats.take("a", noWait)
        launch { seats.take("b") {} }
        advanceUntilIdle()
        val c = launch { seats.take("c") {} }
        advanceUntilIdle()
        assertEquals(listOf("b", "c"), seats.waiting())

        seats.release("a")
        advanceUntilIdle()
        assertEquals(listOf("b"), seats.holders())
        assertEquals(listOf("c"), seats.waiting())
        assertTrue(seats.holds("b"))
        assertFalse(seats.holds("c"))
        c.cancel()
    }

    @Test fun holderTakingAgainReturnsImmediately() = runTest {
        val seats = PhoneSeats { 1 }
        seats.take("a", noWait)
        seats.take("a", noWait)
        assertEquals(listOf("a"), seats.holders())
    }

    @Test fun cancelledWaiterDoesNotSwallowSeat() = runTest {
        val seats = PhoneSeats { 1 }
        seats.take("a", noWait)
        val b = launch { seats.take("b") {} }
        advanceUntilIdle()
        val c = launch { seats.take("c") {} }
        advanceUntilIdle()

        b.cancel()
        advanceUntilIdle()
        assertEquals(listOf("c"), seats.waiting())

        seats.release("a")
        advanceUntilIdle()
        assertTrue(c.isCompleted)
        assertEquals(listOf("c"), seats.holders())
        assertTrue(seats.waiting().isEmpty())
    }

    @Test fun releaseRacingCancelPassesSeatOn() = runTest {
        val seats = PhoneSeats { 1 }
        seats.take("a", noWait)
        val b = launch { seats.take("b") {} }
        advanceUntilIdle()

        // 座位交给 b 与 b 被取消发生在同一调度轮里:b 还没来得及醒来就被取消。
        seats.release("a")
        b.cancel()
        advanceUntilIdle()
        val holders = seats.holders()
        assertTrue("持有者:$holders", holders.isEmpty() || holders == listOf("b"))
        assertTrue(seats.waiting().isEmpty())

        // b 已被取消,不会再自己放座位:c 必须直接拿到,座位没有漏掉。
        seats.take("c", noWait)
        assertEquals(listOf("c"), seats.holders())
    }

    @Test fun shrinkingSeatsDoesNotPreemptButGatesNewTakers() = runTest {
        var count = 2
        val seats = PhoneSeats { count }
        seats.take("a", noWait)
        seats.take("b", noWait)
        count = 1
        assertEquals(listOf("a", "b"), seats.holders())

        var got = false
        launch { seats.take("c") {}; got = true }
        advanceUntilIdle()
        assertFalse(got)

        seats.release("a")
        advanceUntilIdle()
        assertFalse(got)
        assertEquals(listOf("b"), seats.holders())

        seats.release("b")
        advanceUntilIdle()
        assertTrue(got)
        assertEquals(listOf("c"), seats.holders())
    }

    @Test fun releaseOfNonHolderIsNoop() = runTest {
        val seats = PhoneSeats { 1 }
        seats.take("a", noWait)
        seats.release("x")
        assertEquals(listOf("a"), seats.holders())
        assertTrue(seats.waiting().isEmpty())
    }
}
