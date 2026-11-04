package com.manst.jotlist

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * 提醒的调度、通知与两个广播接收器。
 *
 * 这些逻辑原先都在 MainActivity.kt 里且是文件私有，导致桌面小组件完成任务时
 * 无法取消已排的闹钟（会对着已完成的任务继续弹提醒）。抽到独立文件后由
 * Activity、通知和小组件共用同一套调度代码。
 */

internal const val REMINDER_CHANNEL_ID = "todo_reminders_sound_v1"

private const val REMINDER_GROUP = "com.manst.jotlist.REMINDERS"
private const val REMINDER_SUMMARY_ID = 7302

/** 通知点击后要定位的待办 id。 */
internal const val EXTRA_OPEN_TODO_ID = "com.manst.jotlist.EXTRA_OPEN_TODO_ID"

/** 通知/小组件改完数据后广播，前台界面据此重新读取，避免被内存里的旧列表覆盖。 */
internal const val ACTION_TODO_DATA_CHANGED = "com.manst.jotlist.TODO_DATA_CHANGED"

internal fun notifyTodoDataChanged(context: Context) {
    context.sendBroadcast(
        Intent(ACTION_TODO_DATA_CHANGED).setPackage(context.packageName)
    )
}

private const val ACTION_COMPLETE_REMINDER = "com.manst.jotlist.COMPLETE_REMINDER"
private const val ACTION_SNOOZE_PREFIX = "com.manst.jotlist.SNOOZE."
private const val ACTION_SNOOZE_TEN_MINUTES = "10_MINUTES"
private const val ACTION_SNOOZE_TOMORROW = "TOMORROW"
private const val LOG_TAG = "TodoReminder"
private const val TEN_MINUTES_MILLIS = 10 * 60_000L
private const val TOMORROW_MILLIS = 24L * 60L * 60_000L

internal fun notificationsEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    return manager.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED)
}

/**
 * 是否允许排精确闹钟。
 *
 * Android 12 起需要用户在系统设置里单独授权；12 以下没有这个限制，直接视为允许。
 */
internal fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return alarmManager.canScheduleExactAlarms()
}

internal fun reminderPendingIntent(context: Context, item: TodoItem): PendingIntent {
    val intent = Intent(context, TodoReminderReceiver::class.java)
        .setData(Uri.parse("todo-reminder://${item.id}"))
        .putExtra("todo_id", item.id)
        .putExtra("todo_text", item.text)
    return PendingIntent.getBroadcast(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

internal fun scheduleReminder(context: Context, item: TodoItem): Boolean {
    val dueAt = item.dueAt ?: return false
    if (item.isDone || dueAt <= System.currentTimeMillis()) return false
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val rawReminderAt = (dueAt - item.reminderMinutesBefore * 60_000L)
        .coerceAtLeast(System.currentTimeMillis() + 1_000L)
    // 免打扰窗口内的提醒推迟到窗口结束，避免半夜响。
    val dnd = readDoNotDisturb(context)
    val reminderAt = if (dnd.enabled) {
        shiftOutsideDoNotDisturb(rawReminderAt, dnd.startMinute, dnd.endMinute)
    } else {
        rawReminderAt
    }
    val pendingIntent = reminderPendingIntent(context, item)
    if (canScheduleExactAlarms(context)) {
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminderAt,
                pendingIntent
            )
            return true
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminderAt, pendingIntent)
            return false
        }
    }
    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminderAt, pendingIntent)
    return false
}

internal fun cancelReminder(context: Context, item: TodoItem) {
    cancelReminderForId(context, item.id)
}

/**
 * 为"下一次自动恢复"排提醒。
 *
 * 重复任务完成后会变成完成状态，[scheduleReminder] 会拒绝排期；但用户仍然希望
 * 明天到点收到提醒，所以这里按 resetAt 单独排一次。接收器触发前会先把到点的
 * 任务恢复成未完成，因此不会被"已完成"挡掉。
 */
internal fun scheduleResetReminder(context: Context, item: TodoItem) {
    val resetAt = item.resetAt ?: return
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val dnd = readDoNotDisturb(context)
    val raw = (resetAt - item.reminderMinutesBefore * 60_000L)
        .coerceAtLeast(System.currentTimeMillis() + 1_000L)
    val reminderAt = if (dnd.enabled) {
        shiftOutsideDoNotDisturb(raw, dnd.startMinute, dnd.endMinute)
    } else {
        raw
    }
    val pendingIntent = reminderPendingIntent(context, item)
    if (canScheduleExactAlarms(context)) {
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminderAt,
                pendingIntent
            )
            return
        } catch (_: SecurityException) {
            // 退化为非精确闹钟
        }
    }
    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminderAt, pendingIntent)
}

/**
 * 按 id 取消提醒。小组件与通知都能调用，用于"任务已完成/已删除，别再响"。
 */
internal fun cancelReminderForId(context: Context, todoId: Long) {
    if (todoId <= 0L) return
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, TodoReminderReceiver::class.java)
        .setData(Uri.parse("todo-reminder://$todoId"))
        .putExtra("todo_id", todoId)
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    alarmManager.cancel(pendingIntent)
    pendingIntent.cancel()
    (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .cancel(todoId.hashCode())
    refreshReminderSummary(context)
}

/**
 * 同时存在多条到期提醒时补一条汇总通知，避免通知栏被一条条任务刷屏。
 * 只有一条时撤掉汇总。
 */
private fun refreshReminderSummary(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val active = manager.activeNotifications.filter {
        it.notification.group == REMINDER_GROUP && it.id != REMINDER_SUMMARY_ID
    }
    if (active.size < 2) {
        manager.cancel(REMINDER_SUMMARY_ID)
        return
    }
    val samples = active.mapNotNull {
        it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
    }
    val summary = android.app.Notification.Builder(context, REMINDER_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("${active.size} 项任务到期")
        .setContentText(samples.take(3).joinToString("、"))
        .setGroup(REMINDER_GROUP)
        .setGroupSummary(true)
        .setAutoCancel(true)
        // 锁屏上只显示数量，不拼接任务正文。
        .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
        .setPublicVersion(lockScreenNotification(context, "${active.size} 项任务到期"))
        .build()
    manager.notify(REMINDER_SUMMARY_ID, summary)
}

/** 锁屏展示的公开版本通知：不含任何待办正文，仅通用文案。 */
private fun lockScreenNotification(
    context: Context,
    text: String,
    contentIntent: PendingIntent? = null
): android.app.Notification =
    android.app.Notification.Builder(context, REMINDER_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Jotlist")
        .setContentText(text)
        .setGroup(REMINDER_GROUP)
        .setAutoCancel(true)
        .apply { contentIntent?.let { setContentIntent(it) } }
        .build()

private fun snoozeIntent(
    context: Context,
    todoId: Long,
    text: String,
    delayMillis: Long,
    label: String
): PendingIntent {
    val intent = Intent(context, SnoozeReminderReceiver::class.java)
        .setAction(ACTION_SNOOZE_PREFIX + label)
        .setData(Uri.parse("todo-snooze://$todoId/$label"))
        .putExtra("todo_id", todoId)
        .putExtra("todo_text", text)
        .putExtra("delay_millis", delayMillis)
    return PendingIntent.getBroadcast(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

private fun completeReminderIntent(context: Context, todoId: Long): PendingIntent {
    val intent = Intent(context, SnoozeReminderReceiver::class.java)
        .setAction(ACTION_COMPLETE_REMINDER)
        .setData(Uri.parse("todo-reminder-complete://$todoId"))
        .putExtra("todo_id", todoId)
    return PendingIntent.getBroadcast(
        context,
        todoId.hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

private fun quickCaptureIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
        .setAction("com.manst.jotlist.QUICK_CAPTURE")
        .setData(Uri.parse("todo-quick-capture://reminder"))
        .putExtra(TodoWidgetProvider.EXTRA_OPEN_QUICK_CAPTURE, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return PendingIntent.getActivity(
        context,
        7301,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

private fun openTaskIntent(context: Context, todoId: Long): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
        .setAction("com.manst.jotlist.OPEN_TASK")
        .setData(Uri.parse("todo-open://$todoId"))
        .putExtra(EXTRA_OPEN_TODO_ID, todoId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return PendingIntent.getActivity(
        context,
        todoId.hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

internal fun postReminder(context: Context, todoId: Long, text: String) {
    if (!notificationsEnabled(context)) return

    val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.createNotificationChannel(
        NotificationChannel(
            REMINDER_CHANNEL_ID,
            "待办提醒",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            // 锁屏默认只展示不敏感的公开版本。
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
    )
    val tenMinutesIntent = snoozeIntent(
        context,
        todoId,
        text,
        TEN_MINUTES_MILLIS,
        ACTION_SNOOZE_TEN_MINUTES
    )
    val tomorrowIntent = snoozeIntent(
        context,
        todoId,
        text,
        TOMORROW_MILLIS,
        ACTION_SNOOZE_TOMORROW
    )
    val completeIntent = completeReminderIntent(context, todoId)
    // 通知栏最多可靠展示 3 个操作按钮，所以只保留"完成/10分钟后/明天"；
    // "快速记录"入口改由桌面小组件与通知正文点击承担。
    val notification = android.app.Notification.Builder(context, REMINDER_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("待办事项到期")
        .setContentText(text)
        .setContentIntent(openTaskIntent(context, todoId))
        .setGroup(REMINDER_GROUP)
        .setAutoCancel(true)
        // 锁屏上只显示通用文案，不泄露具体待办内容。
        .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
        .setPublicVersion(
            lockScreenNotification(context, "有一条待办提醒", openTaskIntent(context, todoId))
        )
        .addAction(
            android.app.Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(
                    context,
                    android.R.drawable.checkbox_on_background
                ),
                "完成",
                completeIntent
            ).build()
        )
        .addAction(
            android.app.Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(
                    context,
                    android.R.drawable.ic_lock_idle_alarm
                ),
                "10分钟后",
                tenMinutesIntent
            ).build()
        )
        .addAction(
            android.app.Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(
                    context,
                    android.R.drawable.ic_lock_idle_alarm
                ),
                "明天",
                tomorrowIntent
            ).build()
        )
        .build()
    notificationManager.notify(todoId.hashCode(), notification)
    refreshReminderSummary(context)
}

class SnoozeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val todoId = intent.getLongExtra("todo_id", 0L)
        if (todoId == 0L) return
        if (intent.action == ACTION_COMPLETE_REMINDER) {
            completeTask(context, todoId)
            return
        }

        val delayMillis = intent.getLongExtra("delay_millis", 0L)
        if (delayMillis <= 0L) return
        val currentTasks = when (val result = loadTodoList(context)) {
            is TodoListLoadResult.Loaded -> result.items
            TodoListLoadResult.InvalidJson -> {
                Log.e(LOG_TAG, "Cannot snooze reminder: saved task data is invalid")
                return
            }
        }
        val taskIndex = currentTasks.indexOfFirst { it.id == todoId }
        if (taskIndex < 0) {
            Log.w(LOG_TAG, "Cannot snooze reminder: task $todoId was not found")
            cancelReminderForId(context, todoId)
            return
        }
        if (currentTasks[taskIndex].isDone) {
            cancelReminderForId(context, todoId)
            return
        }
        val snoozedDueAt = if (intent.action?.endsWith(ACTION_SNOOZE_TOMORROW) == true) {
            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        } else {
            System.currentTimeMillis() + delayMillis
        }
        val updatedTodo = currentTasks[taskIndex].copy(
            dueAt = snoozedDueAt,
            reminderMinutesBefore = 0,
            reviewHiddenUntil = null
        )
        val updatedTasks = currentTasks.toMutableList().apply { this[taskIndex] = updatedTodo }
        saveTodoList(context, updatedTasks)
        scheduleReminder(context, updatedTodo)
        TodoWidgetProvider.refresh(context)
        notifyTodoDataChanged(context)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(todoId.hashCode())
    }

    private fun completeTask(context: Context, todoId: Long) {
        val current = when (val result = loadTodoList(context)) {
            is TodoListLoadResult.Loaded -> result.items
            TodoListLoadResult.InvalidJson -> {
                Log.e(LOG_TAG, "Cannot complete reminder: saved task data is invalid")
                return
            }
        }
        val task = current.firstOrNull { it.id == todoId } ?: run {
            Log.w(LOG_TAG, "Cannot complete reminder: task $todoId was not found")
            cancelReminderForId(context, todoId)
            return
        }
        if (task.isDone) {
            cancelReminderForId(context, todoId)
            return
        }
        val completedAt = System.currentTimeMillis()
        // 与界面一致：重复任务只标记完成并记录恢复时间，不复制新任务。
        val updated = completeTodoItem(task, RecurrenceCompletionChoice.SKIP, completedAt)
        val taskIndex = current.indexOfFirst { it.id == todoId }
        val updatedTasks = current.toMutableList().apply {
            if (taskIndex >= 0) this[taskIndex] = updated
        }
        saveTodoList(context, updatedTasks)
        when (val history = loadCompletionHistory(context)) {
            is CompletionHistoryLoadResult.Loaded ->
                saveCompletionHistory(context, history.timestamps + completedAt)
            CompletionHistoryLoadResult.Invalid ->
                Log.e(LOG_TAG, "Task completed, but completion history is invalid")
        }
        cancelReminderForId(context, todoId)
        updated.resetAt?.let { scheduleResetReminder(context, updated) }
        TodoWidgetProvider.refresh(context)
        notifyTodoDataChanged(context)
    }
}

class TodoReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            when (val result = loadTodoList(context)) {
                is TodoListLoadResult.Loaded -> result.items.forEach {
                    if (!it.isDone) scheduleReminder(context, it)
                }
                TodoListLoadResult.InvalidJson -> Unit
            }
            return
        }
        val todoId = intent.getLongExtra("todo_id", 0L)
        val text = intent.getStringExtra("todo_text") ?: return
        // 到点的重复任务先恢复成未完成，再判断状态；
        // 否则"下一次"的提醒会被"已完成"挡掉。
        sweepRecurringResets(context)
        // 闹钟可能比任务的完成动作晚一步送达，触发前再确认一次状态，
        // 避免对着已完成或已删除的任务弹提醒。
        val task = (loadTodoList(context) as? TodoListLoadResult.Loaded)
            ?.items?.firstOrNull { it.id == todoId }
        if (task == null || task.isDone) {
            cancelReminderForId(context, todoId)
            return
        }
        postReminder(context, todoId, task.text.ifBlank { text })
    }
}
