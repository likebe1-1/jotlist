package com.manst.jotlist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 桌面小组件的写操作入口（非导出接收器）。
 *
 * 小组件上的「完成」按钮通过 PendingIntent 直接命中本接收器，而不是命中
 * [TodoWidgetProvider]。按 Android 的广播导出规则，exported=false 的接收器
 * 仍能收到系统广播、也能收到本应用自己发出的 PendingIntent，但任何第三方
 * 应用都无法再向它伪造广播。把「完成任务」这类写操作放进独立的非导出接收器，
 * 即使以后 Provider 的导出配置被误改，写操作也不会暴露给其他应用。
 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMPLETE_TODO) return
        val todoId = intent.getLongExtra(EXTRA_TODO_ID, Long.MIN_VALUE)
        if (todoId == Long.MIN_VALUE) return
        if (completeTodoFromWidget(context, todoId)) {
            // 小组件勾掉任务后必须一并撤掉已排的闹钟，
            // 否则到期仍会对着已完成的任务弹提醒。
            cancelReminderForId(context, todoId)
            notifyTodoDataChanged(context)
        }
        TodoWidgetProvider.refresh(context)
    }

    companion object {
        internal const val ACTION_COMPLETE_TODO =
            "com.manst.jotlist.action.COMPLETE_WIDGET_TODO"
        internal const val EXTRA_TODO_ID = "com.manst.jotlist.extra.TODO_ID"
    }
}

private val WIDGET_DATA_LOCK = Any()

/** 完成任务；任务不存在或已完成时返回 false。 */
private fun completeTodoFromWidget(context: Context, todoId: Long): Boolean =
    synchronized(WIDGET_DATA_LOCK) {
        val loaded = loadTodoList(context) as? TodoListLoadResult.Loaded
            ?: return@synchronized false
        val index = loaded.items.indexOfFirst { it.id == todoId }
        if (index < 0) return@synchronized false
        val task = loaded.items[index]
        if (task.isDone) return@synchronized false

        val completedAt = System.currentTimeMillis()
        // 与界面/通知一致：重复任务只标记完成并记录恢复时间，不再复制新任务。
        val updated = completeTodoItem(task, RecurrenceCompletionChoice.SKIP, completedAt)
        val tasks = loaded.items.toMutableList().apply { this[index] = updated }
        saveTodoList(context, tasks)
        // 下一次到点前先把提醒排好，用户不开 App 也能收到。
        updated.resetAt?.let { scheduleResetReminder(context, updated) }
        when (val history = loadCompletionHistory(context)) {
            is CompletionHistoryLoadResult.Loaded ->
                saveCompletionHistory(context, history.timestamps + completedAt)
            CompletionHistoryLoadResult.Invalid -> Unit
        }
        true
    }
