package com.manst.jotlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 核心逻辑的纯 JVM 单测：不需要模拟器，秒级反馈。
 *
 * 覆盖重复任务的"完成 → 到期自动恢复"循环、日期边界、免打扰与"待整理"判定。
 */
class TodoLogicTest {

    private fun todo(
        id: Long = 1L,
        dueAt: Long? = null,
        createdAt: Long = System.currentTimeMillis(),
        isDone: Boolean = false,
        recurrence: String = "NONE",
        reviewHiddenUntil: Long? = null,
        plannedAt: Long? = null,
        resetAt: Long? = null,
        subtasks: List<TodoSubtask> = emptyList()
    ) = TodoItem(
        id = id,
        text = "task",
        isDone = isDone,
        createdAt = createdAt,
        dueAt = dueAt,
        recurrence = recurrence,
        reviewHiddenUntil = reviewHiddenUntil,
        plannedAt = plannedAt,
        resetAt = resetAt,
        subtasks = subtasks
    )

    private fun at(daysFromNow: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, daysFromNow)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun hourOf(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts }
        .get(Calendar.HOUR_OF_DAY)

    private fun minuteOf(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts }
        .get(Calendar.MINUTE)

    private fun dayOfMonth(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts }
        .get(Calendar.DAY_OF_MONTH)

    private fun dayGap(from: Long, to: Long): Int {
        val start = startOfDay(from)
        val end = startOfDay(to)
        var gap = 0
        var cursor = start
        while (cursor < end && gap < 40) {
            cursor = Calendar.getInstance().apply {
                timeInMillis = cursor
                add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            gap++
        }
        return gap
    }

    // ===== 重复任务：完成即标记，到点自动恢复 =====

    @Test
    fun nonRepeatingTaskCompletesWithoutSchedulingReset() {
        val completed = completeTodoItem(todo(dueAt = at(1, 9)))
        assertTrue(completed.isDone)
        assertNull(completed.resetAt)
    }

    @Test
    fun dailyTaskSchedulesResetForTomorrowSameTime() {
        val due = at(0, 9, 30)
        val completed = completeTodoItem(todo(dueAt = due, recurrence = "DAILY"))
        val resetAt = requireNotNull(completed.resetAt)

        assertTrue(completed.isDone)
        assertTrue("恢复时间应在未来", resetAt > System.currentTimeMillis())
        assertEquals(9, hourOf(resetAt))
        assertEquals(30, minuteOf(resetAt))
        assertEquals(1, dayGap(System.currentTimeMillis(), resetAt))
        // 截止时间保持不变，取消勾选还能回到原来的时间。
        assertEquals(due, completed.dueAt)
    }

    @Test
    fun weeklyTaskSchedulesResetOneWeekLater() {
        val due = at(-1, 8)
        val resetAt = requireNotNull(
            completeTodoItem(todo(dueAt = due, recurrence = "WEEKLY")).resetAt
        )
        val expected = Calendar.getInstance().apply {
            timeInMillis = due
            add(Calendar.WEEK_OF_YEAR, 1)
        }.timeInMillis
        assertEquals(expected, resetAt)
    }

    @Test
    fun monthlyTaskKeepsDayOfMonth() {
        val due = Calendar.getInstance().apply {
            add(Calendar.MONTH, -4)
            set(Calendar.DAY_OF_MONTH, 15)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val resetAt = requireNotNull(
            completeTodoItem(todo(dueAt = due, recurrence = "MONTHLY")).resetAt
        )
        assertTrue(resetAt > System.currentTimeMillis())
        assertEquals(15, dayOfMonth(resetAt))
    }

    @Test
    fun recurringTaskWithoutDueDateStillGetsAFutureReset() {
        val resetAt = requireNotNull(
            completeTodoItem(todo(dueAt = null, recurrence = "DAILY")).resetAt
        )
        assertTrue(resetAt > System.currentTimeMillis())
        assertEquals(1, dayGap(System.currentTimeMillis(), resetAt))
    }

    @Test
    fun pausingRecurrenceClearsResetAndStopsRepeating() {
        val completed = completeTodoItem(
            todo(dueAt = at(0, 9), recurrence = "DAILY"),
            RecurrenceCompletionChoice.PAUSE
        )
        assertTrue(completed.isDone)
        assertNull(completed.resetAt)
        assertEquals(Recurrence.NONE.name, completed.recurrence)
    }

    @Test
    fun continueFromTodayAnchorsOnTodayWhileSkipKeepsOldCadence() {
        val due = at(-10, 9)
        val skip = requireNotNull(
            completeTodoItem(
                todo(dueAt = due, recurrence = "WEEKLY"),
                RecurrenceCompletionChoice.SKIP
            ).resetAt
        )
        val today = requireNotNull(
            completeTodoItem(
                todo(dueAt = due, recurrence = "WEEKLY"),
                RecurrenceCompletionChoice.CONTINUE_FROM_TODAY
            ).resetAt
        )

        val dueWeekday = Calendar.getInstance().apply { timeInMillis = due }
            .get(Calendar.DAY_OF_WEEK)
        val todayWeekday = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)

        assertEquals(
            dueWeekday,
            Calendar.getInstance().apply { timeInMillis = skip }.get(Calendar.DAY_OF_WEEK)
        )
        assertEquals(
            todayWeekday,
            Calendar.getInstance().apply { timeInMillis = today }.get(Calendar.DAY_OF_WEEK)
        )
        assertTrue(today > System.currentTimeMillis())
    }

    @Test
    fun resetOnlyHappensWhenTheScheduledTimeHasPassed() {
        val now = System.currentTimeMillis()
        val pending = todo(
            isDone = true,
            dueAt = at(0, 9),
            recurrence = "DAILY",
            resetAt = now + 60_000L
        )
        assertNull("还没到恢复时间", pending.resetIfDue(now))

        val due = requireNotNull(pending.resetIfDue(now + 120_000L))
        assertFalse(due.isDone)
        assertNull(due.completedAt)
        assertNull(due.resetAt)
        assertEquals(pending.resetAt, due.dueAt)
    }

    @Test
    fun resetClearsSubtaskChecks() {
        val due = todo(
            isDone = true,
            dueAt = at(0, 9),
            recurrence = "DAILY",
            resetAt = System.currentTimeMillis() - 1000L,
            subtasks = listOf(TodoSubtask(id = 7L, text = "step", isDone = true))
        )
        val updated = requireNotNull(due.resetIfDue(System.currentTimeMillis()))
        assertTrue(updated.subtasks.none { it.isDone })
    }

    @Test
    fun completingKeepsSubtasksCheckedUntilReset() {
        val item = todo(
            dueAt = at(0, 9),
            recurrence = "DAILY",
            subtasks = listOf(TodoSubtask(id = 7L, text = "step", isDone = true))
        )
        val completed = completeTodoItem(item)
        assertTrue(completed.subtasks.single().isDone)
        assertNotEquals(item.id, completed.id + 1) // 仍是同一条任务，id 不变
        assertEquals(item.id, completed.id)
    }

    // ===== 日期与整理规则 =====

    @Test
    fun startOfDayClearsTimeComponents() {
        val start = startOfDay(at(0, 18, 45))
        val calendar = Calendar.getInstance().apply { timeInMillis = start }

        assertEquals(0, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, calendar.get(Calendar.MINUTE))
        assertEquals(0, calendar.get(Calendar.SECOND))
        assertEquals(0, calendar.get(Calendar.MILLISECOND))
    }

    @Test
    fun sameLocalDayDistinguishesMidnight() {
        val endOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val nextMidnight = Calendar.getInstance().apply {
            timeInMillis = endOfDay
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }.timeInMillis

        assertTrue(isSameLocalDay(startOfDay(endOfDay), endOfDay))
        assertFalse(isSameLocalDay(endOfDay, nextMidnight))
    }

    @Test
    fun localDayTimeUsesReferenceClockAndFallsBackToGivenHour() {
        val day = at(3, 0)
        val reference = at(-1, 7, 15)

        val withReference = localDayTime(day, reference, fallbackHour = 18)
        assertEquals(7, hourOf(withReference))
        assertEquals(15, minuteOf(withReference))

        val withoutReference = localDayTime(day, null, fallbackHour = 18)
        assertEquals(18, hourOf(withoutReference))
        assertEquals(0, minuteOf(withoutReference))
    }

    @Test
    fun triageCoversOverdueAndLongIgnoredUndatedTasks() {
        val now = System.currentTimeMillis()
        val dayStart = startOfDay(now)

        assertTrue("逾期应进整理", todo(dueAt = dayStart - 60_000L).needsTriage(dayStart, now))
        assertFalse("今天到期的还没逾期", todo(dueAt = dayStart + 60_000L).needsTriage(dayStart, now))
        assertFalse("刚建的无期限任务不该打扰", todo(dueAt = null, createdAt = now).needsTriage(dayStart, now))
        assertTrue(
            "搁置多日的无期限任务应进整理",
            todo(dueAt = null, createdAt = dayStart - 4L * 24 * 60 * 60 * 1000)
                .needsTriage(dayStart, now)
        )
        assertFalse(
            "今天已忽略的任务不再提示",
            todo(dueAt = dayStart - 60_000L, reviewHiddenUntil = now + 60_000L)
                .needsTriage(dayStart, now)
        )
        assertFalse(
            "已完成的不进整理",
            todo(dueAt = dayStart - 60_000L, isDone = true).needsTriage(dayStart, now)
        )
    }

    // ===== 免打扰 =====

    @Test
    fun doNotDisturbMovesLateEveningReminderToNextMorning() {
        val lateEvening = at(0, 23, 30)
        val shifted = shiftOutsideDoNotDisturb(lateEvening, startMinute = 22 * 60, endMinute = 8 * 60)

        assertEquals(8, hourOf(shifted))
        assertEquals(0, minuteOf(shifted))
        assertTrue("应顺延到次日早上", shifted > lateEvening)
        assertEquals(1, dayGap(lateEvening, shifted))
    }

    @Test
    fun doNotDisturbKeepsReminderOutsideWindow() {
        val noon = at(0, 12)
        assertEquals(noon, shiftOutsideDoNotDisturb(noon, 22 * 60, 8 * 60))
    }

    @Test
    fun doNotDisturbHandlesSameDayWindowAndBoundary() {
        val inside = at(0, 13, 30)
        val shifted = shiftOutsideDoNotDisturb(inside, 13 * 60, 14 * 60)
        assertEquals(14, hourOf(shifted))
        assertEquals(0, dayGap(inside, shifted))

        val boundary = at(0, 14)
        assertEquals(
            "正好在结束时刻不算窗口内",
            boundary,
            shiftOutsideDoNotDisturb(boundary, 13 * 60, 14 * 60)
        )
    }

    @Test
    fun doNotDisturbDisabledWhenWindowIsEmpty() {
        val lateEvening = at(0, 23, 30)
        assertEquals(lateEvening, shiftOutsideDoNotDisturb(lateEvening, 8 * 60, 8 * 60))
    }

    @Test
    fun minuteOfDayFormatsAsClockTime() {
        assertEquals("00:00", formatMinuteOfDay(0))
        assertEquals("08:00", formatMinuteOfDay(8 * 60))
        assertEquals("22:30", formatMinuteOfDay(22 * 60 + 30))
        assertEquals("00:00", formatMinuteOfDay(24 * 60))
    }
}
