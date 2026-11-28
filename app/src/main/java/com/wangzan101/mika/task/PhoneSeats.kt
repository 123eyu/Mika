package com.wangzan101.mika.task

import kotlinx.coroutines.CompletableDeferred

/**
 * 手机座位:「同时操作手机」的名额(见 docs/design/specs/2026-11-28-phone-seats-design.md)。
 * 聊天、查网页、跑沙箱不占座位;任务第一次碰手机时 [take],收尾时 [release]。
 *
 * - 座位数由 [count] 每次现读,设置改小时不抢已占的座位,只是放出来的座位不再补给等待者,直到持有数降到新上限以下。
 * - 满了按先来先等排队;等待者被取消时从队里移出,座位若已交给它就转给下一位,不会被吞掉。
 * - 所有查与改都在 `synchronized(this)` 里;[take] 的 onWait 在锁外调用(调用方会在里面发布界面状态)。
 */
class PhoneSeats(private val count: () -> Int) {
    private class Waiter(val owner: String) {
        val granted = CompletableDeferred<Unit>()
    }

    /** 按占座先后排列。 */
    private val holding = LinkedHashSet<String>()
    private val queue = ArrayDeque<Waiter>()

    /**
     * 为 [owner] 取一个座位。已持有直接返回;有空座(且没人在排)立即占下;
     * 否则以当时的持有者(按占座先后)调一次 [onWait],然后挂起排队,轮到时返回。
     */
    suspend fun take(owner: String, onWait: (holders: List<String>) -> Unit) {
        val woken: List<Waiter>
        // 为空表示已经拿到座位;否则是排进队里的等待者和当时的持有者。
        val queued: Pair<Waiter, List<String>>?
        synchronized(this) {
            if (owner in holding) return
            woken = fill()
            // fill 可能正好让排在队里的同一个 owner 拿到了座位。
            queued = if (owner in holding) {
                null
            } else if (queue.isEmpty() && holding.size < count()) {
                holding += owner
                null
            } else {
                val waiter = Waiter(owner)
                queue.addLast(waiter)
                waiter to holding.toList()
            }
        }
        wake(woken)
        val (waiter, snapshot) = queued ?: return
        var got = false
        try {
            onWait(snapshot)
            waiter.granted.await()
            got = true
        } finally {
            if (!got) {
                // 被取消(或 onWait 抛错):还在队里就移出;座位已交给它就放掉,转给下一位。
                val next = synchronized(this) {
                    if (!queue.remove(waiter) && holding.remove(waiter.owner)) fill() else emptyList()
                }
                wake(next)
            }
        }
    }

    /** 放掉 [owner] 的座位(不持有也不报错),空出的座位按先来先等交给等待者。 */
    fun release(owner: String) {
        val woken = synchronized(this) {
            if (holding.remove(owner)) fill() else emptyList()
        }
        wake(woken)
    }

    fun holders(): List<String> = synchronized(this) { holding.toList() }

    fun waiting(): List<String> = synchronized(this) { queue.map { it.owner } }

    fun holds(owner: String): Boolean = synchronized(this) { owner in holding }

    /** 在锁内把空座按队列顺序记给等待者,返回要唤醒的那些;唤醒放到锁外做。 */
    private fun fill(): List<Waiter> {
        val woken = mutableListOf<Waiter>()
        while (queue.isNotEmpty()) {
            val head = queue.first()
            // 同一 owner 已持有时不再多占一座,直接放行。
            if (head.owner !in holding) {
                if (holding.size >= count()) break
                holding += head.owner
            }
            queue.removeFirst()
            woken += head
        }
        return woken
    }

    private fun wake(woken: List<Waiter>) {
        woken.forEach { it.granted.complete(Unit) }
    }
}
