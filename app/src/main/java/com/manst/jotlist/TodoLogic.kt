package com.manst.jotlist

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 完成一个逾期重复任务时，下一次该从哪天开始算。 */
internal enum class RecurrenceCompletionChoice {
    /** 从今天继续：以今天为新的锚点，保持相同时间点。 */
    CONTINUE_FROM_TODAY,

    /** 跳过本次：保留原有节奏，直接顺延到下一个未来时间点。 */
    SKIP,

    /** 暂停重复：只保留这一次，之后不再生成。 */
    PAUSE
}

/**
 * 完成一条任务。
 *
 * 重复任务不再复制出"下一次"，而是同一条任务循环：
 * 标记完成，同时记录 [TodoItem.resetAt]（下一个周期的时间），
 * 到点由 [resetIfDue] 自动改回未完成。
 */
internal fun completeTodoItem(
    item: TodoItem,
    choice: RecurrenceCompletionChoice = RecurrenceCompletionChoice.SKIP,
    now: Long = System.currentTimeMillis()
): TodoItem {
    val recurrence = Recurrence.entries.firstOrNull { it.name == item.recurrence }
        ?: Recurrence.NONE
    if (recurrence == Recurrence.NONE) {
        return item.copy(
            isDone = true,
            completedAt = now,
            resetAt = null,
            recurrenceNextId = null
        )
    }
    if (choice == RecurrenceCompletionChoice.PAUSE) {
        return item.copy(
            isDone = true,
            completedAt = now,
            resetAt = null,
            recurrence = Recurrence.NONE.name,
            recurrenceNextId = null
        )
    }
    // 没有截止时间时以"现在"为基准，保证恢复时间落在未来。
    val base = item.dueAt ?: now
    val next = when (choice) {
        RecurrenceCompletionChoice.CONTINUE_FROM_TODAY ->
            continueFromToday(base, recurrence)
        else -> advanceUntilFuture(base, recurrence)
    }
    // 只记录恢复时间，不改 dueAt：这样中途取消勾选还能回到原来的截止时间。
    return item.copy(
        isDone = true,
        completedAt = now,
        resetAt = next,
        recurrenceNextId = null
    )
}

/** 到了下一个周期，把已完成的重复任务恢复成未完成（子步骤一并清空）。 */
internal fun TodoItem.resetIfDue(now: Long): TodoItem? {
    val scheduled = resetAt ?: return null
    if (!isDone || scheduled > now) return null
    return copy(
        isDone = false,
        completedAt = null,
        resetAt = null,
        dueAt = scheduled,
        subtasks = subtasks.map { it.copy(isDone = false) }
    )
}

private fun Calendar.addOnePeriod(recurrence: Recurrence) {
    when (recurrence) {
        Recurrence.DAILY -> add(Calendar.DAY_OF_YEAR, 1)
        Recurrence.WEEKLY -> add(Calendar.WEEK_OF_YEAR, 1)
        Recurrence.MONTHLY -> add(Calendar.MONTH, 1)
        Recurrence.NONE -> Unit
    }
}

/** 单次完成后最多推进的周期数，防御被篡改的极端时间戳造成的长循环。 */
private const val MAX_RECURRENCE_ADVANCE_STEPS = 100_000

private const val ONE_DAY_MILLIS = 24L * 60L * 60_000L

/** 保留原有节奏：从原定时间不断顺延，直到落在未来。 */
private fun advanceUntilFuture(dueAt: Long, recurrence: Recurrence): Long {
    val fallback = System.currentTimeMillis() + ONE_DAY_MILLIS
    return try {
        val calendar = Calendar.getInstance().apply { timeInMillis = dueAt }
        repeat(MAX_RECURRENCE_ADVANCE_STEPS) {
            calendar.addOnePeriod(recurrence)
            if (calendar.timeInMillis > System.currentTimeMillis()) {
                return calendar.timeInMillis
            }
        }
        fallback
    } catch (_: RuntimeException) {
        // 异常时间戳（例如被篡改的备份）可能让 Calendar 溢出；退回一天后，避免 ANR。
        fallback
    }
}

/** 从今天继续：把今天当作新的起点，保持原来的时/分。 */
private fun continueFromToday(dueAt: Long, recurrence: Recurrence): Long {
    val source = Calendar.getInstance().apply { timeInMillis = dueAt }
    return Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, source.get(Calendar.HOUR_OF_DAY))
        set(Calendar.MINUTE, source.get(Calendar.MINUTE))
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= System.currentTimeMillis()) addOnePeriod(recurrence)
    }.timeInMillis
}

internal fun formatTime(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

internal fun formatReminderDate(ts: Long): String =
    SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(ts))

internal fun formatDueTime(ts: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(ts))

internal fun formatTimelineTime(ts: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(ts))

internal fun formatMonthDay(ts: Long): String =
    SimpleDateFormat("M月d日", Locale.CHINA).format(Date(ts))

internal fun startOfDay(ts: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ts
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * 需要整理的任务：逾期未完成，或者没有截止时间却放了很多天的未完成任务。
 *
 * 后者是任务堆积的主要来源，之前只按 dueAt 过滤，导致"昨天记下来、没设日期、
 * 一直没做"的任务永远不会出现在整理列表里。
 */
internal fun TodoItem.needsTriage(dayStart: Long, now: Long): Boolean {
    if (isDone) return false
    if (reviewHiddenUntil != null && reviewHiddenUntil >= now) return false
    return when {
        dueAt != null -> dueAt < dayStart
        else -> createdAt < dayStart - STALE_UNDATED_MILLIS
    }
}

internal fun isSameLocalDay(first: Long, second: Long): Boolean {
    val firstCalendar = Calendar.getInstance().apply { timeInMillis = first }
    val secondCalendar = Calendar.getInstance().apply { timeInMillis = second }
    return firstCalendar.get(Calendar.YEAR) == secondCalendar.get(Calendar.YEAR) &&
        firstCalendar.get(Calendar.DAY_OF_YEAR) == secondCalendar.get(Calendar.DAY_OF_YEAR)
}

internal fun localDayTime(day: Long, referenceTime: Long?, fallbackHour: Int): Long {
    val target = Calendar.getInstance().apply {
        timeInMillis = day
        set(Calendar.HOUR_OF_DAY, fallbackHour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    referenceTime?.let { reference ->
        val source = Calendar.getInstance().apply { timeInMillis = reference }
        target.set(Calendar.HOUR_OF_DAY, source.get(Calendar.HOUR_OF_DAY))
        target.set(Calendar.MINUTE, source.get(Calendar.MINUTE))
    }
    return target.timeInMillis
}

/** 把 "HH:mm" 形式的一天内分钟数格式化成可读时间。 */
internal fun formatMinuteOfDay(minuteOfDay: Int): String {
    val normalized = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
    return "%02d:%02d".format(normalized / 60, normalized % 60)
}

/**
 * 免打扰：落在窗口内的提醒顺延到窗口结束。
 *
 * 窗口可以跨午夜（如 22:00–08:00）；`start == end` 视为未启用。
 */
internal fun shiftOutsideDoNotDisturb(
    triggerAt: Long,
    startMinute: Int,
    endMinute: Int
): Long {
    if (startMinute == endMinute) return triggerAt
    val calendar = Calendar.getInstance().apply { timeInMillis = triggerAt }
    val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    val inside = if (startMinute < endMinute) {
        minuteOfDay >= startMinute && minuteOfDay < endMinute
    } else {
        minuteOfDay >= startMinute || minuteOfDay < endMinute
    }
    if (!inside) return triggerAt

    calendar.set(Calendar.HOUR_OF_DAY, endMinute / 60)
    calendar.set(Calendar.MINUTE, endMinute % 60)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    if (calendar.timeInMillis <= triggerAt) {
        calendar.add(Calendar.DAY_OF_YEAR, 1)
    }
    return calendar.timeInMillis
}

internal const val MINUTES_PER_DAY = 24 * 60
