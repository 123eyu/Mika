package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.i18n.tr

/** 等手机座位时写的那句:说出在用手机的那个对话;取不到标题就说「另一个任务」。 */
internal fun seatWaitCopy(title: String?): String =
    if (title.isNullOrBlank()) tr(R.string.task_waiting_seat_other) else tr(R.string.task_waiting_seat, title)

/** 点这件任务的提示时打开哪个对话:等手机座位时是占着手机的那个对话,其余是它自己的对话 [own]。 */
internal fun TaskState.jumpTarget(own: String): String = (this as? TaskState.Held)?.reason?.ref ?: own
