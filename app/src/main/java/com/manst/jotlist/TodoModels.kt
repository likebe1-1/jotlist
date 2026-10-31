package com.manst.jotlist

import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import java.util.Calendar

data class TodoItem(
    val id: Long = nextTodoId(),
    val text: String,
    var isDone: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val dueAt: Long? = null,
    val category: String = "",
    val priority: Int = 1,
    val reminderMinutesBefore: Int = 0,
    val isPinned: Boolean = false,
    val recurrence: String = "NONE",
    val subtasks: List<TodoSubtask> = emptyList(),
    val recurrenceNextId: Long? = null,
    val linkedNoteId: Long? = null,
    val completedAt: Long? = null,
    val plannedAt: Long? = null,
    val reviewHiddenUntil: Long? = null,
    /** 被选为"今日三件事"的那一天（当天 0 点时间戳）。 */
    val focusDate: Long? = null,
    /**
     * 重复任务完成后的恢复时间：到点自动把 isDone 改回 false。
     * 重复任务不再复制新任务，而是同一条循环。
     */
    val resetAt: Long? = null
)

data class TodoSubtask(
    val id: Long = nextTodoId(),
    val text: String,
    val isDone: Boolean = false
)

internal data class NoteInlineTodo(
    val id: Long = nextTodoId(),
    val text: String,
    val isDone: Boolean = false
)

internal data class NoteItem(
    val id: Long = nextTodoId(),
    val title: String,
    val body: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = updatedAt,
    val tags: List<String> = emptyList(),
    val embeddedTodos: List<NoteInlineTodo> = emptyList(),
    val isMarkdown: Boolean = false
)

internal enum class MainDestination(val label: String) {
    TODO("待办"), NOTES("笔记"), SEARCH("搜索")
}

internal enum class TodoSecondaryPage { LIST, CALENDAR, STATISTICS, DAILY_REVIEW, SETTINGS }
internal enum class NotesSection { LIST, DAILY_REVIEW, TIMELINE }
internal enum class Recurrence(val label: String) {
    NONE("不重复"), DAILY("每天"), WEEKLY("每周"), MONTHLY("每月")
}
internal enum class TodoFilter(val label: String) {
    ALL("全部"), ACTIVE("进行中"), COMPLETED("已完成")
}

internal enum class AppearanceMode(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色")
}

internal sealed interface TodoListLoadResult {
    data class Loaded(val items: List<TodoItem>) : TodoListLoadResult
    data object InvalidJson : TodoListLoadResult
}

internal sealed interface NotesLoadResult {
    data class Loaded(val items: List<NoteItem>) : NotesLoadResult
    data object Invalid : NotesLoadResult
}

internal sealed interface CompletionHistoryLoadResult {
    data class Loaded(val timestamps: List<Long>) : CompletionHistoryLoadResult
    data object Invalid : CompletionHistoryLoadResult
}
