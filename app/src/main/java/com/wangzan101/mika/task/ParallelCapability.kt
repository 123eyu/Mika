package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr

/** 此刻最多能同时操作手机的任务数(手机座位);[reason] 说明为什么只能一个(设置页显示),没有限制时为空。 */
data class ParallelLimit(val max: Int, val reason: String?)

/**
 * 任务总数上限:宽的常量,只防一下子开太多;同时操作手机的任务数另由手机座位([ParallelCapability])限制。
 * 低内存设备放得更紧。
 */
object TaskCapacity {
    const val TOTAL = 4
    const val LOW_RAM = 2

    fun max(lowRam: Boolean): Int = if (lowRam) LOW_RAM else TOTAL
}

/**
 * 按设备能力决定手机座位数(同时操作手机的任务数):每块虚拟屏要有独立焦点(安卓 14+);低内存设备只一个;
 * 其余按用户设定的上限,限定在 1–3。
 */
object ParallelCapability {
    const val MIN_SDK = 34
    const val DEFAULT_MAX = 2
    private const val MAX = 3

    fun detect(sdk: Int, lowRam: Boolean, userMax: Int): ParallelLimit = when {
        sdk < MIN_SDK -> ParallelLimit(1, tr(R.string.parallel_needs_14))
        lowRam -> ParallelLimit(1, tr(R.string.parallel_low_ram))
        else -> ParallelLimit(userMax.coerceIn(1, MAX), null)
    }
}
