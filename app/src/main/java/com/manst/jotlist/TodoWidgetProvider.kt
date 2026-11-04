package com.manst.jotlist

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val WIDGET_PREFS_NAME = "todo_prefs"
private const val WIDGET_KEY_TODO_LIST = "todo_list_json"

/** 防御性上限：即使 manifest 的 exported 配置被改错，也不会被畸形 ids 拖垮主线程。 */
private const val MAX_WIDGET_IDS = 100

class TodoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // 先处理到点的重复任务，再渲染。
        sweepRecurringResets(context)
        appWidgetIds.take(MAX_WIDGET_IDS).forEach { appWidgetId ->
            appWidgetManager.updateAppWidget(appWidgetId, createViews(context, appWidgetId))
        }
    }

    /**
     * 桌面上的小组件被拉伸/收缩时重新排版：同一个组件在不同尺寸下会换成
     * 紧凑 / 标准 / 扩展三套布局，而不是把一套布局硬拉变形。
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        sweepRecurringResets(context)
        appWidgetManager.updateAppWidget(appWidgetId, createViews(context, appWidgetId))
    }

    // 注意：这里不覆写 onReceive 处理自定义动作。
    // 本 Provider 必须 exported="true" 才能收到系统的 APPWIDGET_UPDATE，
    // 任何应用都能向它发显式广播；「完成任务」这类写操作放在非导出的
    // WidgetActionReceiver 里，避免第三方应用伪造广播篡改用户数据。

    private fun createViews(context: Context, appWidgetId: Int): RemoteViews {
        val manager = AppWidgetManager.getInstance(context)
        val options = manager.getAppWidgetOptions(appWidgetId)
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val spec = WidgetSpec.forSize(widthDp, heightDp)

        val views = RemoteViews(context.packageName, spec.layoutRes)
        val activeTodos = readActiveTodos(context)
        val visibleTodos = activeTodos.take(spec.rowsThatFit(heightDp))

        // 调色板在设置页里选，这里把底板、计数、按钮和勾选环换成对应颜色
        val palette = readWidgetPalette(context)
        val accent = palette.todoAccent(context)
        views.setInt(R.id.widget_root, "setBackgroundResource", palette.backgroundRes)
        views.setTextColor(R.id.widget_count, accent)
        views.setTextColor(R.id.widget_add, accent)
        if (spec.showsQuickCapture) {
            views.setTextColor(R.id.widget_quick_capture, accent)
        }

        views.setOnClickPendingIntent(
            R.id.widget_add,
            createAddPendingIntent(context, appWidgetId)
        )
        if (spec.showsQuickCapture) {
            views.setOnClickPendingIntent(
                R.id.widget_quick_capture,
                createQuickCapturePendingIntent(context, appWidgetId)
            )
        }
        if (spec.showsDate) {
            views.setTextViewText(R.id.widget_date, formatHeaderDate())
        }

        val hasTodos = activeTodos.isNotEmpty()
        if (spec.showsCount) {
            views.setTextViewText(
                R.id.widget_count,
                context.getString(R.string.widget_count, activeTodos.size)
            )
            views.setViewVisibility(
                R.id.widget_count,
                if (hasTodos) View.VISIBLE else View.GONE
            )
        }
        views.setViewVisibility(
            R.id.widget_empty,
            if (hasTodos) View.GONE else View.VISIBLE
        )

        // 待办少的时候把字号放大、行高由 layout_weight 自动撑开；
        // 条数多时收回到 12.5sp，保证一屏放得下。
        val taskSp = when {
            visibleTodos.size <= 2 -> 15f
            visibleTodos.size <= 4 -> 13.5f
            else -> 12.5f
        }

        spec.rows.forEachIndexed { index, row ->
            val todo = visibleTodos.getOrNull(index)
            views.setViewVisibility(row.container, if (todo == null) View.GONE else View.VISIBLE)
            if (todo != null) {
                views.setTextViewText(row.text, todo.text)
                views.setTextViewTextSize(row.text, TypedValue.COMPLEX_UNIT_SP, taskSp)
                views.setInt(row.complete, "setBackgroundResource", palette.ringRes)
                views.setContentDescription(
                    row.complete,
                    context.getString(R.string.widget_complete_task, todo.text)
                )
                views.setOnClickPendingIntent(
                    row.complete,
                    createCompletePendingIntent(context, todo.id)
                )
            }
        }

        if (spec.showsFooter) {
            bindFooter(context, views, activeTodos.size)
        }
        return views
    }

    /** 底部进度：今天已完成 / 今天相关总数，让大尺寸组件有点信息量而不只是列表。 */
    private fun bindFooter(context: Context, views: RemoteViews, activeCount: Int) {
        val completedToday = completionsToday(context)
        val total = completedToday + activeCount
        if (total <= 0) {
            views.setViewVisibility(R.id.widget_footer, View.GONE)
            return
        }
        views.setViewVisibility(R.id.widget_footer, View.VISIBLE)
        views.setProgressBar(R.id.widget_progress, 100, completedToday * 100 / total, false)
        views.setTextViewText(
            R.id.widget_footer_text,
            context.getString(R.string.widget_footer_progress, completedToday, total)
        )
    }

    private fun completionsToday(context: Context): Int {
        val history = loadCompletionHistory(context) as? CompletionHistoryLoadResult.Loaded
            ?: return 0
        val now = System.currentTimeMillis()
        return history.timestamps.count { isSameLocalDay(it, now) }
    }

    private fun formatHeaderDate(): String {
        val locale = Locale.getDefault()
        // 用 ICU 的最佳模式，中文是「11月2日 周日」，英文自动变「Sun, Nov 2」。
        val pattern = DateFormat.getBestDateTimePattern(locale, "MMMdEEE")
        return SimpleDateFormat(pattern, locale).format(Date())
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
        // 写操作发往非导出接收器：导出的 Provider 可能被第三方应用伪造广播。
        val intent = Intent(context, WidgetActionReceiver::class.java).apply {
            action = WidgetActionReceiver.ACTION_COMPLETE_TODO
            putExtra(WidgetActionReceiver.EXTRA_TODO_ID, todoId)
            data = Uri.parse("todo-widget://complete/$todoId")
        }
        return PendingIntent.getBroadcast(
            context,
            todoId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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

    private data class WidgetTodo(
        val id: Long,
        val text: String,
        val isPlannedToday: Boolean,
        val isPinned: Boolean,
        val priority: Int,
        val dueAt: Long
    )

    private data class RowIds(val container: Int, val text: Int, val complete: Int)

    /**
     * 三套布局对应三种桌面尺寸。rows 只包含当前布局里真实存在的 id，
     * 避免 RemoteViews 去操作不存在的 View。
     */
    private enum class WidgetSpec(
        val layoutRes: Int,
        val rows: List<RowIds>,
        val showsCount: Boolean,
        val showsDate: Boolean,
        val showsQuickCapture: Boolean,
        val showsFooter: Boolean,
        private val chromeHeightDp: Int
    ) {
        COMPACT(
            layoutRes = R.layout.todo_widget_compact,
            rows = listOf(
                RowIds(R.id.widget_row_1, R.id.widget_task_1, R.id.widget_complete_1),
                RowIds(R.id.widget_row_2, R.id.widget_task_2, R.id.widget_complete_2),
                RowIds(R.id.widget_row_3, R.id.widget_task_3, R.id.widget_complete_3),
                RowIds(R.id.widget_row_4, R.id.widget_task_4, R.id.widget_complete_4)
            ),
            showsCount = true,
            showsDate = false,
            showsQuickCapture = false,
            showsFooter = false,
            chromeHeightDp = 50
        ),
        STANDARD(
            layoutRes = R.layout.todo_widget_standard,
            rows = listOf(
                RowIds(R.id.widget_row_1, R.id.widget_task_1, R.id.widget_complete_1),
                RowIds(R.id.widget_row_2, R.id.widget_task_2, R.id.widget_complete_2),
                RowIds(R.id.widget_row_3, R.id.widget_task_3, R.id.widget_complete_3),
                RowIds(R.id.widget_row_4, R.id.widget_task_4, R.id.widget_complete_4),
                RowIds(R.id.widget_row_5, R.id.widget_task_5, R.id.widget_complete_5)
            ),
            showsCount = true,
            showsDate = false,
            showsQuickCapture = true,
            showsFooter = false,
            chromeHeightDp = 50
        ),
        EXPANDED(
            layoutRes = R.layout.todo_widget_expanded,
            rows = listOf(
                RowIds(R.id.widget_row_1, R.id.widget_task_1, R.id.widget_complete_1),
                RowIds(R.id.widget_row_2, R.id.widget_task_2, R.id.widget_complete_2),
                RowIds(R.id.widget_row_3, R.id.widget_task_3, R.id.widget_complete_3),
                RowIds(R.id.widget_row_4, R.id.widget_task_4, R.id.widget_complete_4),
                RowIds(R.id.widget_row_5, R.id.widget_task_5, R.id.widget_complete_5),
                RowIds(R.id.widget_row_6, R.id.widget_task_6, R.id.widget_complete_6)
            ),
            showsCount = true,
            showsDate = true,
            showsQuickCapture = true,
            showsFooter = true,
            chromeHeightDp = 94
        );

        fun rowsThatFit(heightDp: Int): Int {
            if (heightDp <= 0) return rows.size
            val fit = (heightDp - chromeHeightDp) / ROW_HEIGHT_DP
            return fit.coerceIn(1, rows.size)
        }

        companion object {
            private const val ROW_HEIGHT_DP = 26

            /**
             * 尺寸分桶：高度够就优先给信息量大的布局；只有宽度变化时退到标准布局；
             * 都小就用紧凑布局。宽度/高度为 0 表示 Launcher 还没回报尺寸，按标准处理。
             */
            fun forSize(widthDp: Int, heightDp: Int): WidgetSpec {
                if (heightDp >= 200) return EXPANDED
                if (widthDp >= 200 || widthDp <= 0) return STANDARD
                return COMPACT
            }
        }
    }
}
