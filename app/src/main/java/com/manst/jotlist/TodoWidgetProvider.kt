package com.manst.jotlist

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONException
import java.util.Calendar

private const val WIDGET_PREFS_NAME = "todo_prefs"
private const val WIDGET_KEY_TODO_LIST = "todo_list_json"

class TodoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // 先处理到点的重复任务，再渲染。
        sweepRecurringResets(context)
        appWidgetIds.forEach { appWidgetId ->
            appWidgetManager.updateAppWidget(appWidgetId, createViews(context, appWidgetId))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_COMPLETE_TODO) {
            val todoId = intent.getLongExtra(EXTRA_TODO_ID, Long.MIN_VALUE)
            if (todoId != Long.MIN_VALUE) {
                markTodoComplete(context, todoId)
                // 小组件勾掉任务后必须一并撤掉已排的闹钟，
                // 否则到期仍会对着已完成的任务弹提醒。
                cancelReminderForId(context, todoId)
                notifyTodoDataChanged(context)
            }
            refreshAllWidgets(context)
        }
    }

    private fun createViews(context: Context, appWidgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.todo_widget)
        val activeTodos = readActiveTodos(context).take(MAX_VISIBLE_TODOS)

        views.setOnClickPendingIntent(
            R.id.widget_add,
            createAddPendingIntent(context, appWidgetId)
        )
        views.setOnClickPendingIntent(
            R.id.widget_quick_capture,
            createQuickCapturePendingIntent(context, appWidgetId)
        )
        views.setViewVisibility(
            R.id.widget_empty,
            if (activeTodos.isEmpty()) View.VISIBLE else View.GONE
        )

        repeat(MAX_VISIBLE_TODOS) { index ->
            val rowId = ROW_IDS[index]
            val todo = activeTodos.getOrNull(index)
            views.setViewVisibility(rowId.container, if (todo == null) View.GONE else View.VISIBLE)
            if (todo != null) {
                views.setTextViewText(rowId.text, todo.text)
                views.setContentDescription(
                    rowId.complete,
                    context.getString(R.string.widget_complete_task, todo.text)
                )
                views.setOnClickPendingIntent(
                    rowId.complete,
                    createCompletePendingIntent(context, todo.id)
                )
            }
        }
        return views
    }

    private fun createAddPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_ADD_TODO, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data = Uri.parse("todo-widget://add/$appWidgetId")
        }
        return PendingIntent.getActivity(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createQuickCapturePendingIntent(
        context: Context,
        appWidgetId: Int
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_QUICK_CAPTURE, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data = Uri.parse("todo-widget://quick-capture/$appWidgetId")
        }
        return PendingIntent.getActivity(
            context,
            appWidgetId + 10_000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createCompletePendingIntent(context: Context, todoId: Long): PendingIntent {
        val intent = Intent(context, TodoWidgetProvider::class.java).apply {
            action = ACTION_COMPLETE_TODO
            putExtra(EXTRA_TODO_ID, todoId)
            data = Uri.parse("todo-widget://complete/$todoId")
        }
        return PendingIntent.getBroadcast(
            context,
            todoId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun refreshAllWidgets(context: Context) {
        refresh(context)
    }

    companion object {
        const val EXTRA_OPEN_ADD_TODO = "com.manst.jotlist.EXTRA_OPEN_ADD_TODO"
        const val EXTRA_OPEN_QUICK_CAPTURE =
            "com.manst.jotlist.EXTRA_OPEN_QUICK_CAPTURE"

        @JvmStatic
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val provider = ComponentName(context, TodoWidgetProvider::class.java)
            TodoWidgetProvider().onUpdate(context, manager, manager.getAppWidgetIds(provider))
        }

        private const val ACTION_COMPLETE_TODO =
            "com.manst.jotlist.action.COMPLETE_WIDGET_TODO"
        private const val EXTRA_TODO_ID = "com.manst.jotlist.extra.TODO_ID"
        private const val MAX_VISIBLE_TODOS = 3
        private val PREFERENCES_LOCK = Any()
        private val ROW_IDS = listOf(
            RowIds(R.id.widget_row_1, R.id.widget_task_1, R.id.widget_complete_1),
            RowIds(R.id.widget_row_2, R.id.widget_task_2, R.id.widget_complete_2),
            RowIds(R.id.widget_row_3, R.id.widget_task_3, R.id.widget_complete_3)
        )
    }

    private fun readActiveTodos(context: Context): List<WidgetTodo> {
        val json = context.getSharedPreferences(WIDGET_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(WIDGET_KEY_TODO_LIST, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            val today = Calendar.getInstance()
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    if (!item.optBoolean("isDone", false)) {
                        val plannedAt = item.optLong("plannedAt", 0L).takeIf { it > 0L }
                        add(
                            WidgetTodo(
                                id = item.optLong("id", Long.MIN_VALUE),
                                text = item.optString("text", "").trim(),
                                isPlannedToday = plannedAt?.let { timestamp ->
                                    Calendar.getInstance().apply { timeInMillis = timestamp }.let {
                                        it.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                                            it.get(Calendar.DAY_OF_YEAR) ==
                                            today.get(Calendar.DAY_OF_YEAR)
                                    }
                                } == true,
                                isPinned = item.optBoolean("isPinned", false),
                                priority = item.optInt("priority", 1),
                                dueAt = if (item.isNull("dueAt")) {
                                    Long.MAX_VALUE
                                } else {
                                    item.optLong("dueAt", Long.MAX_VALUE)
                                }
                            )
                        )
                    }
                }
            }.filter { it.id != Long.MIN_VALUE && it.text.isNotEmpty() }
                .sortedWith(
                    compareByDescending<WidgetTodo> { it.isPlannedToday }
                        .thenByDescending { it.isPinned }
                        .thenByDescending { it.priority }
                        .thenBy { it.dueAt }
                )
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun markTodoComplete(context: Context, todoId: Long) {
        synchronized(PREFERENCES_LOCK) {
            val loaded = loadTodoList(context) as? TodoListLoadResult.Loaded ?: return
            val index = loaded.items.indexOfFirst { it.id == todoId }
            if (index < 0) return
            val task = loaded.items[index]
            if (task.isDone) return

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
        }
    }

    private data class WidgetTodo(
        val id: Long,
        val text: String,
        val isPlannedToday: Boolean,
        val isPinned: Boolean,
        val priority: Int,
        val dueAt: Long
    )

    private data class RowIds(val container: Int, val text: Int, val complete: Int)
}
